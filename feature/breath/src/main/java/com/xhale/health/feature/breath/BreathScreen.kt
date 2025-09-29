package com.xhale.health.feature.breath

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.xhale.health.core.ui.BatteryEstimator

@Composable
fun BreathRoute(viewModel: BreathViewModel) {
    val state by viewModel.state.collectAsState()
    BreathScreen(
        state = state, 
        onStart = { viewModel.startSampling(it) }, 
        onStop = viewModel::stopSampling,
        onExport = viewModel::exportToCsv,
        onClearResult = viewModel::clearExportResult
    )
}

@Composable
fun BreathScreen(
    state: BreathUiState, 
    onStart: (Int) -> Unit, 
    onStop: () -> Unit,
    onExport: () -> Unit,
    onClearResult: () -> Unit
) {
    val context = LocalContext.current
    var duration by remember { mutableStateOf(15) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Breath Sampling", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Duration (s):")
            Spacer(Modifier.width(8.dp))
            Slider(value = duration.toFloat(), onValueChange = { duration = it.toInt() }, valueRange = 5f..60f)
            Text("${duration}s")
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remaining: ${state.remainingSec}s")
            Spacer(Modifier.width(16.dp))
            if (!state.isSampling) Button(onClick = { onStart(duration) }) { Text("Start") } else Button(onClick = onStop) { Text("Stop") }
        }
        Spacer(Modifier.height(12.dp))
        Text("CO: ${state.coPpm?.let { String.format("%.2f", it) } ?: "--"} ppm")
        Text("Temp: ${state.temperatureC?.let { String.format("%.2f", it) } ?: "--"} °C")
        val est = BatteryEstimator.estimate(state.batteryPercent)
        Text("Battery: ${state.batteryPercent?.toString() ?: "--"}%${est?.let { ", ~" + String.format("%.0f", it.hoursRemaining) + "h" } ?: ""}")
        
        // Export section
        if (state.points.isNotEmpty() && !state.isSampling) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onExport,
                    enabled = !state.isExporting
                ) {
                    Text(if (state.isExporting) "Exporting..." else "Export CSV")
                }
                if (state.sessionId.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text("Session: ${state.sessionId}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        // Export / Analysis result
        state.exportResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (result.startsWith("Exported")) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.lastExportUri != null && result.startsWith("Exported")) {
                        TextButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, state.lastExportUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share CSV"))
                        }) {
                            Text("Share")
                        }
                    }
                    TextButton(onClick = onClearResult) {
                        Text("Dismiss")
                    }
                }
            }
        }
        
        Spacer(Modifier.height(12.dp))

        val co = state.points.mapIndexedNotNull { idx, p -> p.second.first?.let { Entry(idx.toFloat(), it.toFloat()) } }
        val t = state.points.mapIndexedNotNull { idx, p -> p.second.second?.let { Entry(idx.toFloat(), it.toFloat()) } }
        AndroidView(factory = { ctx ->
            LineChart(ctx).apply {
                description.isEnabled = false
                legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
                legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                axisRight.isEnabled = false
            }
        }, update = { chart ->
            val coSet = LineDataSet(co, "CO ppm").apply {
                setDrawCircles(false); color = 0xFFE91E63.toInt(); lineWidth = 2f
            }
            val tSet = LineDataSet(t, "Temp °C").apply {
                setDrawCircles(false); color = 0xFF2196F3.toInt(); lineWidth = 2f
                axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.RIGHT
            }
            if (chart.axisRight != null) chart.axisRight.isEnabled = true
            chart.data = LineData(listOf(coSet, tSet))
            chart.invalidate()
        })
    }
}

