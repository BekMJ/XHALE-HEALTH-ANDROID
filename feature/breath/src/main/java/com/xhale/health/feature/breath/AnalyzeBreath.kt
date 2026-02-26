package com.xhale.health.feature.breath

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

data class WindowPoint(
    val timestampMs: Long,
    val rRaw: Double,     // CO raw
    val tC: Double,       // temperature °C
    val v: Double         // voltage V
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
    val baselineVoltage: Double,
    val peakCO: Double,
    val peakTemperature: Double,
    val peakVoltage: Double,
    val flags: BreathFlags,
    val method: String = "AnalyzeBreath_v2",
    val calibrationPath: BreathCalibrationPath
)

class AnalyzeBreathUseCase(
    private val coeffs: AnalyzeCoefficients = AnalyzeCoefficients()
) {
    companion object {
        private const val INITIAL_TEMP_BASELINE_MAX_POINTS = 10
        private const val GAS_WARMUP_SEC = 20.0
        private const val GAS_BASELINE_TAIL_SEC = 5.0
        private const val GAS_FIT_WINDOW_SEC = 20.0
        private const val GAS_DERIVATIVE_THRESHOLD = 0.1
        private const val GAS_MIN_DELTA_RAW = 1.0
        private const val PRE_BREATH_STDDEV_THRESHOLD_RAW = 12.0
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
        cloudGasFitCoefficients: GasFitCoefficients? = null
    ): BreathAnalysis {
        require(window.isNotEmpty()) { "window must not be empty" }
        val sorted = window.sortedBy { it.timestampMs }
        val durationSec = (((sorted.last().timestampMs - sorted.first().timestampMs).coerceAtLeast(0)) / 1000.0).toInt()
        val initialTempBaseline = sorted
            .take(INITIAL_TEMP_BASELINE_MAX_POINTS)
            .ifEmpty { sorted }
            .map { it.tC }
            .average()

        val breathStartTempThreshold = initialTempBaseline + coeffs.breathStartTempRiseC
        val breathStartIndex = sorted.indexOfFirst { it.tC >= breathStartTempThreshold }.let { if (it >= 0) it else 0 }
        val preBreath = if (breathStartIndex > 0) sorted.subList(0, breathStartIndex) else emptyList()

        val rBasePre = when {
            preBreath.size >= 5 -> trimmedMean(preBreath.map { it.rRaw })
            warmupBaselineRaw != null -> warmupBaselineRaw
            else -> sorted.first().rRaw
        }.takeIf { it.isFinite() } ?: sorted.first().rRaw

        val firstPoint = sorted.first()

        val tBasePre = when {
            preBreath.size >= 5 -> preBreath.map { it.tC }.average()
            else -> firstPoint.tC
        }.takeIf { it.isFinite() } ?: firstPoint.tC

        val vBasePre = when {
            preBreath.size >= 3 -> preBreath.map { it.v }.average()
            else -> firstPoint.v
        }.takeIf { it.isFinite() } ?: firstPoint.v

        val peakCandidates = sorted.drop(breathStartIndex).ifEmpty { sorted }
        val peak = peakCandidates.maxBy { it.rRaw }
        val rPeak = peak.rRaw
        val tPeak = peak.tC
        val vPeak = peak.v

        // Compensation
        val deltaR = rPeak - rBasePre
        val deltaT = tPeak - tBasePre
        val deltaV = vPeak - vBasePre
        val deltaRComp = deltaR - coeffs.aT_raw_per_C * deltaT - coeffs.aV_raw_per_V * deltaV

        val calibrationPath: BreathCalibrationPath
        val ppm: Double = if (deltaT > coeffs.humanPathTempRiseThresholdC) {
            calibrationPath = BreathCalibrationPath.HUMAN_BREATH
            max(0.0, (deltaRComp - coeffs.humanIntercept_raw) / coeffs.humanSlope_raw_per_ppm)
        } else {
            val serialPrefix = normalizedSerialPrefix(serialNumber)
            val fitPpm = computeGasFitPpm(sorted, serialPrefix, cloudGasFitCoefficients)
            if (fitPpm != null) {
                calibrationPath = BreathCalibrationPath.GAS_FIT
                fitPpm
            } else {
                calibrationPath = BreathCalibrationPath.LEGACY_GAS_FALLBACK
                legacyGasFallbackPpm(deltaRComp, durationSec)
            }
        }

        val tRise = sorted.maxOf { it.tC } - tBasePre
        val preBreathStdDev = stddev(preBreath.map { it.rRaw })
        val flags = BreathFlags(
            shortDuration = durationSec < 5,
            smallTemperatureRise = tRise < 1.0,
            unstableBaseline = preBreath.size >= 5 && preBreathStdDev > PRE_BREATH_STDDEV_THRESHOLD_RAW
        )

        return BreathAnalysis(
            estimatedPpm = ppm,
            deltaRComp = deltaRComp,
            breathDurationSec = durationSec,
            temperatureRiseC = tRise,
            baselineCO = rBasePre,
            baselineTemperature = tBasePre,
            baselineVoltage = vBasePre,
            peakCO = rPeak,
            peakTemperature = tPeak,
            peakVoltage = vPeak,
            flags = flags,
            calibrationPath = calibrationPath
        )
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

    private fun computeGasFitPpm(
        window: List<WindowPoint>,
        serialPrefix: String?,
        cloudGasFitCoefficients: GasFitCoefficients?
    ): Double? {
        val coeff = cloudGasFitCoefficients ?: perDeviceGasFit[serialPrefix] ?: globalGasFit
        if (window.size < 6 || coeff.gain_raw_per_ppm <= 0.0 || coeff.tauSec <= 0.0) return null

        val t0 = window.first().timestampMs
        val timesSec = window.map { (it.timestampMs - t0) / 1000.0 }
        val co = window.map { it.rRaw }

        val baselineValues = co.indices
            .filter { idx -> timesSec[idx] in (GAS_WARMUP_SEC - GAS_BASELINE_TAIL_SEC)..GAS_WARMUP_SEC }
            .map { co[it] }
            .ifEmpty { listOf(co.first()) }
        if (baselineValues.isEmpty()) return null
        val baseline = baselineValues.average()

        val delta = co.map { it - baseline }
        val smooth = movingAverage3(delta)

        var startIndex = -1
        for (i in 1 until smooth.size) {
            if (timesSec[i] < GAS_WARMUP_SEC) continue
            val dt = timesSec[i] - timesSec[i - 1]
            if (dt <= 0.0) continue
            val derivative = (smooth[i] - smooth[i - 1]) / dt
            if (derivative >= GAS_DERIVATIVE_THRESHOLD && smooth[i] >= GAS_MIN_DELTA_RAW) {
                startIndex = i
                break
            }
        }
        val startTime = if (startIndex >= 0) timesSec[startIndex] else GAS_WARMUP_SEC

        var numerator = 0.0
        var denominator = 0.0
        for (i in delta.indices) {
            val u = timesSec[i] - startTime
            if (u < 0.0 || u > GAS_FIT_WINDOW_SEC) continue
            val effectiveTime = max(0.0, u - coeff.deadSec)
            val f = 1.0 - exp(-effectiveTime / coeff.tauSec)
            val yCorrected = delta[i] - coeff.drift_raw_per_s * u
            numerator += yCorrected * f
            denominator += f * f
        }
        if (denominator <= 1e-9) return null

        val amplitude = numerator / denominator
        return max(0.0, amplitude / coeff.gain_raw_per_ppm)
    }

    private fun movingAverage3(values: List<Double>): List<Double> {
        if (values.isEmpty()) return emptyList()
        return values.indices.map { i ->
            val a = values[max(0, i - 1)]
            val b = values[i]
            val c = values[minOf(values.lastIndex, i + 1)]
            (a + b + c) / 3.0
        }
    }

    private fun legacyGasFallbackPpm(deltaRComp: Double, durationSec: Int): Double {
        val chosenDuration = legacyGasByDurationSec.keys.minByOrNull { kotlin.math.abs(it - durationSec) } ?: 30
        val coeff = legacyGasByDurationSec[chosenDuration] ?: LegacyGasCoefficients(0.98, -1.8)
        if (kotlin.math.abs(coeff.slope) <= 1e-9) return max(0.0, (deltaRComp + 1.8) / 0.98)
        return max(0.0, (deltaRComp - coeff.intercept) / coeff.slope)
    }

    private fun stddev(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val avg = values.average()
        return sqrt(values.map { (it - avg) * (it - avg) }.average())
    }
}


