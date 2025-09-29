package com.xhale.health.feature.breath

import kotlin.math.max

data class Baselines(
    val rBase: Double,    // baseline CO raw
    val tBase: Double,    // baseline temperature °C
    val vBase: Double     // baseline voltage V
)

data class WindowPoint(
    val timestampMs: Long,
    val rRaw: Double,     // CO raw
    val tC: Double,       // temperature °C
    val v: Double         // voltage V
)

data class AnalyzeCoefficients(
    val aT_raw_per_C: Double = 0.80,
    val aV_raw_per_V: Double = 150.3,
    val slope_raw_per_ppm: Double = 2.55
)

data class BreathFlags(
    val shortDuration: Boolean,
    val smallTemperatureRise: Boolean,
    val unstableBaseline: Boolean
)

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
    val method: String = "AnalyzeBreath_v1",
    val coefficients: AnalyzeCoefficients
)

class AnalyzeBreathUseCase(
    private val coeffs: AnalyzeCoefficients = AnalyzeCoefficients()
) {
    fun deriveBaselines(window: List<WindowPoint>, baselineMs: Long = 7_000L): Baselines {
        val start = window.first().timestampMs
        val basePoints = window.filter { it.timestampMs - start <= baselineMs }
        val rBase = basePoints.map { it.rRaw }.average()
        val tBase = basePoints.map { it.tC }.average()
        val vBase = basePoints.map { it.v }.average()
        return Baselines(rBase = rBase, tBase = tBase, vBase = vBase)
    }

    fun execute(window: List<WindowPoint>, baselines: Baselines): BreathAnalysis {
        require(window.isNotEmpty()) { "window must not be empty" }

        // Detect breath using ΔT
        val tRise = (window.maxOf { it.tC } - baselines.tBase)
        val peak = window.maxBy { it.rRaw }
        val rPeak = peak.rRaw
        val tPeak = peak.tC
        val vPeak = peak.v

        // Compensation
        val deltaR = rPeak - baselines.rBase
        val deltaT = tPeak - baselines.tBase
        val deltaV = vPeak - baselines.vBase
        val deltaRComp = deltaR - coeffs.aT_raw_per_C * deltaT - coeffs.aV_raw_per_V * deltaV

        // ppm
        val ppm = max(0.0, deltaRComp / coeffs.slope_raw_per_ppm)

        // Duration
        val durationSec = (((window.last().timestampMs - window.first().timestampMs).coerceAtLeast(0)) / 1000.0).toInt()

        // Baseline stability (simple): temperature stddev over baseline <= 0.3°C
        val start = window.first().timestampMs
        val basePoints = window.filter { it.timestampMs - start <= 7_000L }
        val tAvg = basePoints.map { it.tC }.average()
        val tStd = kotlin.math.sqrt(basePoints.map { (it.tC - tAvg) * (it.tC - tAvg) }.average())
        val flags = BreathFlags(
            shortDuration = durationSec < 5,
            smallTemperatureRise = tRise < 1.0,
            unstableBaseline = tStd > 0.3
        )

        return BreathAnalysis(
            estimatedPpm = ppm,
            deltaRComp = deltaRComp,
            breathDurationSec = durationSec,
            temperatureRiseC = tRise,
            baselineCO = baselines.rBase,
            baselineTemperature = baselines.tBase,
            baselineVoltage = baselines.vBase,
            peakCO = rPeak,
            peakTemperature = tPeak,
            peakVoltage = vPeak,
            flags = flags,
            coefficients = coeffs
        )
    }
}


