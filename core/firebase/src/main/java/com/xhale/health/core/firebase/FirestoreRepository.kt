package com.xhale.health.core.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class BreathSession(
    val sessionId: String,
    val deviceId: String,
    val userId: String,
    val startedAt: String,
    val durationSeconds: Int,
    val dataPoints: List<BreathDataPoint>
)

data class BreathDataPoint(
    val timestamp: String,
    val coPpm: Double?,
    val temperatureC: Double?,
    val batteryPercent: Int?
)

@Singleton
class FirestoreRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun saveBreathSession(session: BreathSession): Result<Unit> {
        return try {
            val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            
            val sessionData = mapOf(
                "sessionId" to session.sessionId,
                "deviceId" to session.deviceId,
                "userId" to userId,
                "startedAt" to session.startedAt,
                "durationSeconds" to session.durationSeconds,
                "dataPoints" to session.dataPoints.map { point ->
                    mapOf(
                        "timestamp" to point.timestamp,
                        "coPpm" to point.coPpm,
                        "temperatureC" to point.temperatureC,
                        "batteryPercent" to point.batteryPercent
                    )
                }
            )
            
            firestore.collection("users")
                .document(userId)
                .collection("sessions")
                .document(session.sessionId)
                .set(sessionData)
                .await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBreathSessions(deviceId: String? = null): Result<List<BreathSession>> {
        return try {
            val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            
            var query = firestore.collection("users")
                .document(userId)
                .collection("sessions")
                .orderBy("startedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            
            if (deviceId != null) {
                query = query.whereEqualTo("deviceId", deviceId)
            }
            
            val snapshot = query.limit(50).get().await()
            
            val sessions = snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                BreathSession(
                    sessionId = doc.id,
                    deviceId = data["deviceId"] as? String ?: "",
                    userId = data["userId"] as? String ?: "",
                    startedAt = data["startedAt"] as? String ?: "",
                    durationSeconds = (data["durationSeconds"] as? Number)?.toInt() ?: 0,
                    dataPoints = (data["dataPoints"] as? List<Map<String, Any>>)?.map { point ->
                        BreathDataPoint(
                            timestamp = point["timestamp"] as? String ?: "",
                            coPpm = (point["coPpm"] as? Number)?.toDouble(),
                            temperatureC = (point["temperatureC"] as? Number)?.toDouble(),
                            batteryPercent = (point["batteryPercent"] as? Number)?.toInt()
                        )
                    } ?: emptyList()
                )
            }
            
            Result.success(sessions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteBreathSession(sessionId: String): Result<Unit> {
        return try {
            val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not authenticated"))
            
            firestore.collection("users")
                .document(userId)
                .collection("sessions")
                .document(sessionId)
                .delete()
                .await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }
}
