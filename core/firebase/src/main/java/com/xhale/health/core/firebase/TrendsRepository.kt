package com.xhale.health.core.firebase

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

data class DailyMedian(val dateIso: String, val medianPpm: Double)
data class TrendsResult(
    val daily: List<DailyMedian>,
    val smokeFreeStreakDays: Int,
    val measuredDays: Int
)

@Singleton
class TrendsRepository @Inject constructor(
    private val firestoreRepository: FirestoreRepository
) {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun fetchLast7Days(): Result<TrendsResult> {
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val endDay = truncateToDay(now.time)
        val startCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = endDay
            add(Calendar.DAY_OF_YEAR, -6)
        }
        val startDay = truncateToDay(startCal.time)
        val startIso = iso.format(startDay)

        val sessionsRes = firestoreRepository.getBreathSessions()
        val sessions = sessionsRes.getOrElse { return Result.failure(it) }

        val ppmByDay = linkedMapOf<String, MutableList<Double>>()
        var cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = startDay }
        repeat(7) {
            ppmByDay[dayFmt.format(cal.time)] = mutableListOf()
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        sessions.forEach { s ->
            // Only consider sessions on/after startDay based on startedAt
            if (s.startedAt >= startIso) {
                val ts = parseIso(s.startedAt) ?: return@forEach
                if (!ts.before(startDay) && !ts.after(endDay)) {
                    val key = dayFmt.format(ts)
                    val ppm = s.estimatedPpm ?: medianOrNull(s.dataPoints.mapNotNull { it.coPpm })
                    if (ppm != null && ppm >= 0) ppmByDay[key]?.add(ppm)
                }
            }
        }

        val daily = ppmByDay.entries.map { (day, list) ->
            DailyMedian(day, median(list))
        }

        val measuredDays = daily.count { !it.medianPpm.isNaN() }
        val smokeFreeStreak = computeStreakFromEnd(daily, threshold = 3.0)

        return Result.success(TrendsResult(daily = daily, smokeFreeStreakDays = smokeFreeStreak, measuredDays = measuredDays))
    }

    private fun parseIso(s: String): Date? = try { iso.parse(s) } catch (_: Exception) { null }

    private fun truncateToDay(date: Date): Date {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun medianOrNull(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        return median(values)
    }

    private fun computeStreakFromEnd(daily: List<DailyMedian>, threshold: Double): Int {
        var streak = 0
        // daily is chronological; compute from end
        for (i in daily.indices.reversed()) {
            val m = daily[i].medianPpm
            if (!m.isNaN() && m <= threshold) streak++ else if (!m.isNaN()) break
        }
        return streak
    }
}


