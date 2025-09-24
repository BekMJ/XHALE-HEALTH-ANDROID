package com.xhale.health.feature.breath

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

data class BreathSampleData(
    val timestamp: Long,
    val coPpm: Double?,
    val temperatureC: Double?,
    val sessionId: String
)

class CsvExportUtil(private val context: Context) {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    suspend fun exportToCsv(
        data: List<BreathSampleData>,
        sessionId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = "breath_sample_${sessionId}_${System.currentTimeMillis()}.csv"
            
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(Exception("Failed to create file"))
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                writeCsvData(outputStream, data)
            }
            
            Result.success(fileName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun writeCsvData(outputStream: OutputStream, data: List<BreathSampleData>) {
        val writer = outputStream.bufferedWriter()
        
        // Write CSV header
        writer.write("timestamp_iso8601,co_ppm,temperature_c,session_id\n")
        
        // Write data rows
        data.forEach { sample ->
            val timestamp = dateFormat.format(Date(sample.timestamp))
            val coPpm = sample.coPpm?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            val temperatureC = sample.temperatureC?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            
            writer.write("$timestamp,$coPpm,$temperatureC,${sample.sessionId}\n")
        }
        
        writer.flush()
    }
    
    fun generateSessionId(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val time = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date())
        return "session-$date-$time"
    }
}
