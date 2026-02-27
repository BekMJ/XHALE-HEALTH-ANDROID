package com.xhale.health.feature.breath

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

data class WindowPoint(
    val timestampMs: Long,
    val rRaw: Double,     // CO raw
    val tC: Double,       // temperature C
    val v: Double?        // battery voltage V
)

data class AnalyzeCoefficients(
    val aT_raw_per_C: Double = 0.80,
    val aV_raw_per_V: Double = 150.3,
    val humanSlope_raw_per_ppm: Double = 3.6,
    val humanIntercept_raw: Double = 0.0,
    val breathStartTempRiseC: Double = 0.8,
    val humanPathTempRiseThresholdC: Double = 2.0
)

data class GasFitCoefficients(
    val drift_raw_per_s: Double,
    val gain_raw_per_ppm: Double,
    val tauSec: Double,
    val deadSec: Double
)

data class LegacyGasCoefficients(
    val slope: Double,
    val intercept: Double
)

data class BreathFlags(
    val shortDuration: Boolean,
    val smallTemperatureRise: Boolean,
    val unstableBaseline: Boolean
)

enum class BreathCalibrationPath {
    HUMAN_BREATH,
    GAS_FIT,
    LEGACY_GAS_FALLBACK
}

data class BreathAnalysis(
    val estimatedPpm: Double,
    val deltaRComp: Double,
    val breathDurationSec: Int,
    val temperatureRiseC: Double,
    val baselineCO: Double,
    val baselineTemperature: Double,
    val baselineVoltage: Double?,
    val peakCO: Double,
    val peakTemperature: Double,
    val peakVoltage: Double?,
    val flags: BreathFlags,
    val method: String = "AnalyzeBreath_v2",
    val calibrationPath: BreathCalibrationPath,
    val calibrationMode: String,
    val calibrationSource: String,
    val calibrationSlopeRawPerPpm: Double?,
    val calibrationIntercept: Double?,
    val calibrationGainRawPerPpm: Double?,
    val calibrationDriftRawPerSec: Double?,
    val calibrationTauSec: Double?,
    val calibrationDeadSec: Double?,
    val calibrationDurationBucketSec: Int?
)

class AnalyzeBreathUseCase(
    private val coeffs: AnalyzeCoefficients = AnalyzeCoefficients()
) {
    private data class GasFitResult(
        val ppm: Double,
        val source: String,
        val coefficients: GasFitCoefficients
    )

    private data class LegacyCalibrationResult(
        val ppm: Double,
        val coefficients: LegacyGasCoefficients,
        val durationBucketSec: Int
    )

    companion object {
        private const val INITIAL_TEMP_BASELINE_MAX_POINTS = 10
        private const val GAS_FIT_WINDOW_SEC = 20.0
        private const val GAS_DERIVATIVE_THRESHOLD = 0.1
        private const val GAS_MIN_DELTA_RAW = 1.0
        private const val PRE_BREATH_STDDEV_MIN_ABS_RAW = 5.0
        private const val PRE_BREATH_STDDEV_REL = 0.02
    }

    private val globalGasFit = GasFitCoefficients(
        drift_raw_per_s = 0.0,
        gain_raw_per_ppm = 0.695,
        tauSec = 22.0,
        deadSec = 0.0
    )

    private val perDeviceGasFit = mapOf(
        "6C8A4BC7" to GasFitCoefficients(-0.0227256, 0.798849, 34.25, 5.5),
        "D1A07CD4" to GasFitCoefficients(-0.0637795, 0.653858, 14.5, 1.4),
        "D92EC0CB" to GasFitCoefficients(-0.0401157, 0.724937, 19.5, 4.0),
        "F2E4CB88" to GasFitCoefficients(-0.0314408, 0.697511, 19.5, 3.3),
        "F685F16F" to GasFitCoefficients(-0.0333294, 0.692745, 24.5, 5.6)
    )

    private val legacyGasByDurationSec = mapOf(
        5 to LegacyGasCoefficients(0.0406375, -0.0770252),
        10 to LegacyGasCoefficients(0.126693, -0.475432),
        15 to LegacyGasCoefficients(0.176892, -0.0411687),
        20 to LegacyGasCoefficients(0.241434, -0.339973),
        30 to LegacyGasCoefficients(0.305976, -0.638778),
        40 to LegacyGasCoefficients(0.349004, -0.837981),
        50 to LegacyGasCoefficients(0.370518, -0.937583),
        60 to LegacyGasCoefficients(0.370518, -0.937583)
    )

    fun execute(
        window: List<WindowPoint>,
        serialNumber: String?,
        warmupBaselineRaw: Double?,
        cloudGasFitCoefficients: GasFitCoefficients? = null,
        sampleDurationSec: Int? = null
    ): BreathAnalysis {
        require(window.isNotEmpty()) { "window must not be empty" }

        val sorted = window.sortedBy { it.timestampMs }
        val firstPoint = sorted.first()

        val initialTempBaseline = sorted
            .take(INITIAL_TEMP_BASELINE_MAX_POINTS)
            .map { it.tC }
            .average()

        val breathStartTempThreshold = initialTempBaseline + coeffs.breathStartTempRiseC
        val breathStartIndex = sorted.indexOfFirst { it.tC >= breathStartTempThreshold }
            .let { if (it >= 0) it else 0 }
        val breathStartTimeMs = sorted[breathStartIndex].timestampMs
        val preBreath = if (breathStartIndex > 0) sorted.subList(0, breathStartIndex) else emptyList()

        val rBasePre = when {
            preBreath.size >= 5 -> trimmedMean(preBreath.map { it.rRaw })
            preBreath.size >= 2 -> preBreath.take(2).map { it.rRaw }.average()
            warmupBaselineRaw != null -> warmupBaselineRaw
            sorted.size >= 2 -> sorted.take(2).map { it.rRaw }.average()
            else -> firstPoint.rRaw
        }.takeIf { it.isFinite() } ?: firstPoint.rRaw

        val tBasePre = when {
            preBreath.size >= 5 -> preBreath.map { it.tC }.average()
            else -> initialTempBaseline
        }.takeIf { it.isFinite() } ?: firstPoint.tC

        // iOS parity: treat battery voltage as effectively constant over one breath.
        val vBasePre = sorted.firstNotNullOfOrNull { sample ->
            sample.v?.takeIf { it.isFinite() }
        }

        val peakCandidates = sorted.drop(breathStartIndex).ifEmpty { sorted }
        val peak = peakCandidates.maxBy { it.rRaw }
        val rPeak = peak.rRaw
        val tPeak = peak.tC
        val vPeak = vBasePre

        val deltaT = tPeak - tBasePre
        val deltaV = 0.0
        val deltaRComp = (rPeak - rBasePre) - coeffs.aT_raw_per_C * deltaT - coeffs.aV_raw_per_V * deltaV

        val breathDurationSec = ((sorted.last().timestampMs - breathStartTimeMs).coerceAtLeast(0L) / 1000L).toInt()

        val calibrationPath: BreathCalibrationPath
        var calibrationMode: String
        var calibrationSource: String
        var calibrationSlopeRawPerPpm: Double? = null
        var calibrationIntercept: Double? = null
        var calibrationGainRawPerPpm: Double? = null
        var calibrationDriftRawPerSec: Double? = null
        var calibrationTauSec: Double? = null
        var calibrationDeadSec: Double? = null
        var calibrationDurationBucketSec: Int? = null

        val ppm: Double = if (deltaT > coeffs.humanPathTempRiseThresholdC) {
            calibrationPath = BreathCalibrationPath.HUMAN_BREATH
            calibrationMode = "human_breath"
            calibrationSource = "human"
            calibrationSlopeRawPerPpm = coeffs.humanSlope_raw_per_ppm
            calibrationIntercept = coeffs.humanIntercept_raw
            max(0.0, (deltaRComp - coeffs.humanIntercept_raw) / coeffs.humanSlope_raw_per_ppm)
        } else {
            val serialPrefix = normalizedSerialPrefix(serialNumber)
            val fit = computeGasFitResult(sorted, serialPrefix, cloudGasFitCoefficients)
            if (fit != null) {
                calibrationPath = BreathCalibrationPath.GAS_FIT
                calibrationMode = "gas_fit_20s"
                calibrationSource = fit.source
                calibrationGainRawPerPpm = fit.coefficients.gain_raw_per_ppm
                calibrationDriftRawPerSec = fit.coefficients.drift_raw_per_s
                calibrationTauSec = fit.coefficients.tauSec
                calibrationDeadSec = fit.coefficients.deadSec
                fit.ppm
            } else {
                calibrationPath = BreathCalibrationPath.LEGACY_GAS_FALLBACK
                val legacy = legacyGasFallbackPpm(deltaRComp, sampleDurationSec ?: breathDurationSec)
                calibrationMode = "calibration_gas"
                calibrationSource = "legacy_duration_bucket"
                calibrationSlopeRawPerPpm = legacy.coefficients.slope
                calibrationIntercept = legacy.coefficients.intercept
                calibrationDurationBucketSec = legacy.durationBucketSec
                legacy.ppm
            }
        }

        val preBreathRaw = preBreath.map { it.rRaw }
        val preBreathStdDev = stddev(preBreathRaw)
        val preBreathMean = averageOf(preBreathRaw)
        val unstableThreshold = max(
            PRE_BREATH_STDDEV_MIN_ABS_RAW,
            PRE_BREATH_STDDEV_REL * max(1.0, preBreathMean)
        )

        val flags = BreathFlags(
            shortDuration = breathDurationSec < 5,
            smallTemperatureRise = deltaT < 1.0,
            unstableBaseline = preBreath.size >= 5 && preBreathStdDev > unstableThreshold
        )

        return BreathAnalysis(
            estimatedPpm = ppm,
            deltaRComp = deltaRComp,
            breathDurationSec = breathDurationSec,
            temperatureRiseC = deltaT,
            baselineCO = rBasePre,
            baselineTemperature = tBasePre,
            baselineVoltage = vBasePre,
            peakCO = rPeak,
            peakTemperature = tPeak,
            peakVoltage = vPeak,
            flags = flags,
            calibrationPath = calibrationPath,
            calibrationMode = calibrationMode,
            calibrationSource = calibrationSource,
            calibrationSlopeRawPerPpm = calibrationSlopeRawPerPpm,
            calibrationIntercept = calibrationIntercept,
            calibrationGainRawPerPpm = calibrationGainRawPerPpm,
            calibrationDriftRawPerSec = calibrationDriftRawPerSec,
            calibrationTauSec = calibrationTauSec,
            calibrationDeadSec = calibrationDeadSec,
            calibrationDurationBucketSec = calibrationDurationBucketSec
        )
    }

    private fun averageOf(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values.average()
    }

    private fun trimmedMean(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        if (values.size < 5) return values.average()
        val sorted = values.sorted()
        val trim = max(1, (sorted.size * 0.1).toInt())
        val trimmed = sorted.subList(trim, sorted.size - trim).ifEmpty { sorted }
        return trimmed.average()
    }

    private fun normalizedSerialPrefix(serial: String?): String? {
        val cleaned = serial?.filter { it.isLetterOrDigit() }?.uppercase() ?: return null
        return cleaned.take(8).takeIf { it.length == 8 }
    }

    private fun computeGasFitResult(
        window: List<WindowPoint>,
        serialPrefix: String?,
        cloudGasFitCoefficients: GasFitCoefficients?
    ): GasFitResult? {
        val localCoeff = perDeviceGasFit[serialPrefix]
        val coeff = cloudGasFitCoefficients ?: localCoeff ?: globalGasFit
        val source = when {
            cloudGasFitCoefficients != null -> "cloud"
            localCoeff != null -> "local"
            else -> "global"
        }
        if (window.size < 6 || coeff.gain_raw_per_ppm <= 0.0 || coeff.tauSec <= 0.0) return null

        val t0 = window.first().timestampMs
        val timesSec = window.map { (it.timestampMs - t0) / 1000.0 }
        val co = window.map { it.rRaw }

        val anchor = calibrationBaselineAnchor(co, timesSec) ?: return null
        val delta = computeDeltaValuesWithDrift(
            coValues = co,
            times = timesSec,
            b0 = anchor.first,
            t0 = anchor.second,
            driftRawPerSec = coeff.drift_raw_per_s
        )

        val startIndex = detectCalibrationStartIndex(
            times = timesSec,
            deltaValues = delta,
            minDetectionTimeSec = 0.0
        )
        val startSec = startIndex?.let { timesSec[it] } ?: (timesSec.firstOrNull() ?: 0.0)

        val amplitude = fitCalibrationAmplitude(
            times = timesSec,
            deltaValues = delta,
            startSec = startSec,
            tauSec = coeff.tauSec,
            deadSec = coeff.deadSec
        ) ?: return null

        return GasFitResult(
            ppm = max(0.0, amplitude / coeff.gain_raw_per_ppm),
            source = source,
            coefficients = coeff
        )
    }

    private fun calibrationBaselineAnchor(coValues: List<Double>, times: List<Double>): Pair<Double, Double>? {
        if (coValues.isEmpty() || coValues.size != times.size) return null
        val seedCount = minOf(2, coValues.size)
        if (seedCount == 0) return null
        val b0 = coValues.take(seedCount).average()
        val t0 = if (seedCount >= 2) (times[0] + times[1]) / 2.0 else times[0]
        return b0 to t0
    }

    private fun computeDeltaValuesWithDrift(
        coValues: List<Double>,
        times: List<Double>,
        b0: Double,
        t0: Double,
        driftRawPerSec: Double
    ): List<Double> {
        return coValues.indices.map { idx ->
            val t = times[idx]
            val baseline = b0 + driftRawPerSec * (t - t0)
            coValues[idx] - baseline
        }
    }

    private fun detectCalibrationStartIndex(
        times: List<Double>,
        deltaValues: List<Double>,
        minDetectionTimeSec: Double = 0.0
    ): Int? {
        if (times.size != deltaValues.size || times.size <= 1) return null

        val smoothed = deltaValues.toMutableList()
        if (deltaValues.size >= 3) {
            for (i in 1 until deltaValues.lastIndex) {
                smoothed[i] = (deltaValues[i - 1] + deltaValues[i] + deltaValues[i + 1]) / 3.0
            }
        }

        for (i in 1 until times.size) {
            if (times[i] < minDetectionTimeSec) continue
            val dt = times[i] - times[i - 1]
            if (dt <= 0.0) continue
            val derivative = (smoothed[i] - smoothed[i - 1]) / dt
            if (derivative >= GAS_DERIVATIVE_THRESHOLD && deltaValues[i] >= GAS_MIN_DELTA_RAW) {
                return i
            }
        }
        return null
    }

    private fun fitCalibrationAmplitude(
        times: List<Double>,
        deltaValues: List<Double>,
        startSec: Double,
        tauSec: Double,
        deadSec: Double
    ): Double? {
        if (times.size != deltaValues.size || times.isEmpty() || tauSec <= 0.0) return null

        val dead = max(0.0, deadSec)
        var numerator = 0.0
        var denominator = 0.0

        for (i in times.indices) {
            val u = times[i] - startSec
            if (u < 0.0) continue
            if (u > GAS_FIT_WINDOW_SEC) break

            val effectiveTime = max(0.0, u - dead)
            val f = if (effectiveTime > 0.0) 1.0 - exp(-effectiveTime / tauSec) else 0.0

            numerator += deltaValues[i] * f
            denominator += f * f
        }

        if (denominator <= 1e-9) return null
        return numerator / denominator
    }

    private fun legacyGasFallbackPpm(deltaRComp: Double, durationSec: Int): LegacyCalibrationResult {
        val chosenDuration = legacyGasByDurationSec.keys.minByOrNull { abs(it - durationSec) } ?: 30
        val coeff = legacyGasByDurationSec[chosenDuration] ?: LegacyGasCoefficients(0.98, -1.8)
        val ppm = if (abs(coeff.slope) <= 1e-9) {
            max(0.0, (deltaRComp + 1.8) / 0.98)
        } else {
            max(0.0, (deltaRComp - coeff.intercept) / coeff.slope)
        }
        return LegacyCalibrationResult(
            ppm = ppm,
            coefficients = coeff,
            durationBucketSec = chosenDuration
        )
    }

    private fun stddev(values: List<Double>): Double {
        if (values.size <= 1) return 0.0
        val avg = values.average()
        val variance = values.map { (it - avg) * (it - avg) }.sum() / (values.size - 1)
        return sqrt(variance)
    }
}
