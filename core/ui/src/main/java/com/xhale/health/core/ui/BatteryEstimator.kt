package com.xhale.health.core.ui

object BatteryEstimator {
    // Simple mapping: bucket and hours assuming 170h typical, 80% effective
    private const val TYPICAL_HOURS = 170.0 * 0.8

    data class Estimate(val hoursRemaining: Double, val bucketPercent: Int)

    fun estimate(percent: Int?): Estimate? {
        percent ?: return null
        val p = percent.coerceIn(0, 100)
        val hours = TYPICAL_HOURS * (p / 100.0)
        val bucket = when {
            p >= 88 -> 100
            p >= 63 -> 75
            p >= 38 -> 50
            p >= 13 -> 25
            else -> 0
        }
        return Estimate(hours, bucket)
    }
}


