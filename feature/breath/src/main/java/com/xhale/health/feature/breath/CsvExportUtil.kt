package com.xhale.health.feature.breath

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

data class BreathSampleData(
    val timestamp: Long,
    val coRaw: Double?,
    val temperatureC: Double?,
    val humidityPercent: Double?,
    val deviceSerial: String?,
    val sessionId: String
)

class CsvExportUtil(private val context: Context) {

    suspend fun exportToCsv(
        data: List<BreathSampleData>,
        deviceSerial: String?
    ): Result<Pair<String, Uri>> = withContext(Dispatchers.IO) {
        try {
            val serialPrefix = sanitizedSerialPrefix(deviceSerial)
            val fileName = "${serialPrefix}_BreathSample.csv"
            
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(Exception("Failed to create file"))
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                writeCsvData(outputStream, data, deviceSerial)
            }
            
            Result.success(fileName to uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun writeCsvData(outputStream: OutputStream, data: List<BreathSampleData>, deviceSerial: String?) {
        val writer = outputStream.bufferedWriter()

        writer.write("DeviceSerial,${deviceSerial ?: ""}\n")
        writer.write("Index,Temperature,Humidity,CO\n")

        data.forEachIndexed { index, sample ->
            val temperatureC = sample.temperatureC?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
            val humidity = sample.humidityPercent?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
            val coRaw = sample.coRaw?.let { String.format(Locale.US, "%.2f", it) } ?: "0"
            writer.write("${index + 1},$temperatureC,$humidity,$coRaw\n")
        }

        writer.flush()
    }

    private fun sanitizedSerialPrefix(serial: String?): String {
        val cleaned = serial?.filter { it.isLetterOrDigit() }?.uppercase().orEmpty()
        return if (cleaned.length >= 8) cleaned.take(8) else "BreathSample"
    }
    
    fun generateSessionId(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val time = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())
        return "session-$date-$time"
    }
}
