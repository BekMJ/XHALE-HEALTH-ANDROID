package com.xhale.health.core.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Lazy
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class BreathSession(
    val sessionId: String,
    val deviceId: String,
    val userId: String,
    val startedAt: String,
    val durationSeconds: Int,
    val estimatedPpm: Double? = null,
    val deltaRComp: Double? = null,
    val temperatureRiseC: Double? = null,
    val baselineCO: Double? = null,
    val baselineTemperature: Double? = null,
    val baselineVoltage: Double? = null,
    val peakCO: Double? = null,
    val peakTemperature: Double? = null,
    val peakVoltage: Double? = null,
    val batteryPercent: Int? = null,
    val qualityFlags: Map<String, Boolean> = emptyMap(),
    val calibrationMode: String? = null,
    val calibrationSource: String? = null,
    val calibrationPath: String? = null,
    val calibrationSlopeRawPerPpm: Double? = null,
    val calibrationIntercept: Double? = null,
    val calibrationGainRawPerPpm: Double? = null,
    val calibrationDriftRawPerSec: Double? = null,
    val calibrationTauSec: Double? = null,
    val calibrationDeadSec: Double? = null,
    val calibrationDurationBucketSec: Int? = null,
    val timestamps: List<String> = emptyList(),
    val dataPoints: List<BreathDataPoint> = emptyList()
)

data class BreathDataPoint(
    val timestamp: String,
    val coRaw: Double?,
    val temperatureC: Double?,
    val batteryPercent: Int?,
    val coPpm: Double? = null
)

data class DeviceCalibration(
    val driftRawPerSec: Double,
    val gainRawPerPpm: Double,
    val tauSec: Double,
    val deadSec: Double
)

@Singleton
class FirestoreRepository @Inject constructor(
    private val firestoreLazy: Lazy<FirebaseFirestore>,
    private val authLazy: Lazy<FirebaseAuth>,
    @Named("firebase_enabled") private val firebaseEnabled: Boolean
) {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val calibrationCache = mutableMapOf<String, DeviceCalibration?>()

    suspend fun saveBreathSession(session: BreathSession): Result<Unit> {
        if (!firebaseEnabled) return Result.failure(IllegalStateException("Firebase disabled"))
        return try {
            val userId = authLazy.get().currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val safeDeviceId = session.deviceId.ifBlank { "unknown_device" }
            val startedAt = session.startedAt
            val endedAt = session.timestamps.lastOrNull() ?: inferEndTime(startedAt, session.durationSeconds)
            val createdAt = formatTimestamp(System.currentTimeMillis())
            val flags = if (session.qualityFlags.isNotEmpty()) {
                session.qualityFlags
            } else {
                mapOf(
                    "shortDuration" to false,
                    "smallTemperatureRise" to false,
                    "unstableBaseline" to false
                )
            }

            val sessionData = mapOf(
                "timestamp" to createdAt,
                "startTime" to startedAt,
                "endTime" to endedAt,
                "startedAt" to startedAt,
                "deviceId" to safeDeviceId,
                "userId" to userId,
                "sessionId" to session.sessionId,
                "estimatedPPM" to session.estimatedPpm,
                "deltaRComp" to session.deltaRComp,
                "breathDurationSec" to session.durationSeconds,
                "durationSeconds" to session.durationSeconds,
                "temperatureRiseC" to session.temperatureRiseC,
                "baselineCO" to session.baselineCO,
                "baselineTemperature" to session.baselineTemperature,
                "baselineVoltage" to session.baselineVoltage,
                "peakCO" to session.peakCO,
                "peakTemperature" to session.peakTemperature,
                "peakVoltage" to session.peakVoltage,
                "batteryPercent" to session.batteryPercent,
                "flags" to flags,
                "calibrationMode" to session.calibrationMode,
                "calibrationSource" to session.calibrationSource,
                "calibrationPath" to session.calibrationPath,
                "calibrationSlopeRawPerPpm" to session.calibrationSlopeRawPerPpm,
                "calibrationIntercept" to session.calibrationIntercept,
                "calibrationGainRawPerPpm" to session.calibrationGainRawPerPpm,
                "calibrationDriftRawPerSec" to session.calibrationDriftRawPerSec,
                "calibrationTauSec" to session.calibrationTauSec,
                "calibrationDeadSec" to session.calibrationDeadSec,
                "calibrationDurationBucketSec" to session.calibrationDurationBucketSec
            )

            val firestore = firestoreLazy.get()
            firestore.collection("users")
                .document(userId)
                .collection("sensorData")
                .document(safeDeviceId)
                .collection("breaths")
                .add(sessionData)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDeviceCalibration(serialPrefix8: String): Result<DeviceCalibration?> {
        if (!firebaseEnabled) return Result.success(null)
        val normalized = serialPrefix8.filter { it.isLetterOrDigit() }.uppercase().take(8)
        if (normalized.length != 8) return Result.success(null)
        if (calibrationCache.containsKey(normalized)) return Result.success(calibrationCache[normalized])
        return try {
            val doc = firestoreLazy.get()
                .collection("deviceCalibrations")
                .document(normalized)
                .get()
                .await()
            if (!doc.exists()) {
                calibrationCache[normalized] = null
                return Result.success(null)
            }
            val enabled = doc.getBoolean("enabled") ?: true
            if (!enabled) {
                calibrationCache[normalized] = null
                return Result.success(null)
            }
            val drift = doc.getDouble("a_drift_raw_per_s")
            val gain = doc.getDouble("G_raw_per_ppm")
            val tau = doc.getDouble("tau_s")
            val dead = doc.getDouble("dead_s")
            if (drift == null || gain == null || tau == null || dead == null) {
                calibrationCache[normalized] = null
                return Result.success(null)
            }
            val calibration = DeviceCalibration(
                driftRawPerSec = drift,
                gainRawPerPpm = gain,
                tauSec = tau,
                deadSec = dead
            )
            calibrationCache[normalized] = calibration
            Result.success(calibration)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBreathSessions(deviceId: String? = null): Result<List<BreathSession>> {
        if (!firebaseEnabled) return Result.failure(IllegalStateException("Firebase disabled"))
        return try {
            val userId = authLazy.get().currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))

            val firestore = firestoreLazy.get()
            val sessions = mutableListOf<BreathSession>()

            if (deviceId != null) {
                val snapshot = firestore.collection("users")
                    .document(userId)
                    .collection("sensorData")
                    .document(deviceId)
                    .collection("breaths")
                    .orderBy("startedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(100)
                    .get()
                    .await()
                sessions += snapshot.documents.mapNotNull { doc ->
                    parseBreathSession(doc.id, doc.data)
                }
            } else {
                val deviceDocs = firestore.collection("users")
                    .document(userId)
                    .collection("sensorData")
                    .get()
                    .await()
                for (deviceDoc in deviceDocs.documents) {
                    val snapshot = deviceDoc.reference
                        .collection("breaths")
                        .orderBy("startedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                        .limit(100)
                        .get()
                        .await()
                    sessions += snapshot.documents.mapNotNull { doc ->
                        parseBreathSession(doc.id, doc.data)
                    }
                }
            }

            if (sessions.isEmpty()) {
                // Backward compatibility for earlier app versions.
                var query = firestore.collection("users")
                    .document(userId)
                    .collection("sessions")
                    .orderBy("startedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                if (deviceId != null) {
                    query = query.whereEqualTo("deviceId", deviceId)
                }
                val oldSnapshot = query.limit(100).get().await()
                sessions += oldSnapshot.documents.mapNotNull { doc ->
                    parseBreathSession(doc.id, doc.data)
                }
            }

            val sorted = sessions
                .sortedByDescending { it.startedAt }
                .distinctBy { it.sessionId }
            Result.success(sorted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteBreathSession(sessionId: String): Result<Unit> {
        if (!firebaseEnabled) return Result.failure(IllegalStateException("Firebase disabled"))
        return try {
            val userId = authLazy.get().currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            val firestore = firestoreLazy.get()

            // Legacy path cleanup.
            firestore.collection("users")
                .document(userId)
                .collection("sessions")
                .document(sessionId)
                .delete()
                .await()

            // Current nested path cleanup.
            val byGroup = firestore.collectionGroup("breaths")
                .whereEqualTo("userId", userId)
                .whereEqualTo("sessionId", sessionId)
                .get()
                .await()
            byGroup.documents.forEach { it.reference.delete().await() }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }

    private fun parseBreathSession(sessionId: String, data: Map<String, Any>?): BreathSession? {
        data ?: return null
        val qualityMap = (data["flags"] as? Map<*, *>) ?: (data["qualityFlags"] as? Map<*, *>)
        val quality = qualityMap?.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            val value = v as? Boolean ?: return@mapNotNull null
            key to value
        }?.toMap().orEmpty()

        val timestamps = (data["timestamps"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val dataPoints = (data["dataPoints"] as? List<*>)?.mapNotNull { entry ->
            val point = entry as? Map<*, *> ?: return@mapNotNull null
            BreathDataPoint(
                timestamp = point["timestamp"] as? String ?: "",
                coRaw = (point["coRaw"] as? Number)?.toDouble(),
                coPpm = (point["coPpm"] as? Number)?.toDouble(),
                temperatureC = (point["temperatureC"] as? Number)?.toDouble(),
                batteryPercent = (point["batteryPercent"] as? Number)?.toInt()
            )
        }.orEmpty()

        return BreathSession(
            sessionId = sessionId,
            deviceId = data["deviceId"] as? String ?: "",
            userId = data["userId"] as? String ?: "",
            startedAt = (data["startTime"] as? String)
                ?: (data["startedAt"] as? String)
                ?: (data["timestamp"] as? String)
                ?: "",
            durationSeconds = (data["breathDurationSec"] as? Number)?.toInt()
                ?: (data["durationSeconds"] as? Number)?.toInt()
                ?: 0,
            estimatedPpm = (data["estimatedPPM"] as? Number)?.toDouble()
                ?: (data["estimatedPpm"] as? Number)?.toDouble(),
            deltaRComp = (data["deltaRComp"] as? Number)?.toDouble(),
            temperatureRiseC = (data["temperatureRiseC"] as? Number)?.toDouble(),
            baselineCO = (data["baselineCO"] as? Number)?.toDouble(),
            baselineTemperature = (data["baselineTemperature"] as? Number)?.toDouble(),
            baselineVoltage = (data["baselineVoltage"] as? Number)?.toDouble(),
            peakCO = (data["peakCO"] as? Number)?.toDouble(),
            peakTemperature = (data["peakTemperature"] as? Number)?.toDouble(),
            peakVoltage = (data["peakVoltage"] as? Number)?.toDouble(),
            batteryPercent = (data["batteryPercent"] as? Number)?.toInt(),
            qualityFlags = quality,
            calibrationMode = data["calibrationMode"] as? String,
            calibrationSource = data["calibrationSource"] as? String,
            calibrationPath = data["calibrationPath"] as? String,
            calibrationSlopeRawPerPpm = (data["calibrationSlopeRawPerPpm"] as? Number)?.toDouble(),
            calibrationIntercept = (data["calibrationIntercept"] as? Number)?.toDouble(),
            calibrationGainRawPerPpm = (data["calibrationGainRawPerPpm"] as? Number)?.toDouble(),
            calibrationDriftRawPerSec = (data["calibrationDriftRawPerSec"] as? Number)?.toDouble(),
            calibrationTauSec = (data["calibrationTauSec"] as? Number)?.toDouble(),
            calibrationDeadSec = (data["calibrationDeadSec"] as? Number)?.toDouble(),
            calibrationDurationBucketSec = (data["calibrationDurationBucketSec"] as? Number)?.toInt(),
            timestamps = timestamps,
            dataPoints = dataPoints
        )
    }

    private fun inferEndTime(startedAt: String, durationSeconds: Int): String {
        val start = runCatching { dateFormat.parse(startedAt) }.getOrNull()
        if (start == null) return startedAt
        val endMs = start.time + durationSeconds.coerceAtLeast(0) * 1_000L
        return formatTimestamp(endMs)
    }
}
