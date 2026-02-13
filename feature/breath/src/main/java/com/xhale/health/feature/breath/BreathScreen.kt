package com.xhale.health.feature.breath

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.xhale.health.core.ble.ConnectionState
import com.xhale.health.core.ui.BatteryEstimator
import kotlin.math.max

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
    val scrollState = rememberScrollState()
    val isConnected =
        state.connectionState == ConnectionState.CONNECTED && state.connectedDeviceId != null
    val warmupBlocked = state.isPreparingBaseline

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Breath Sampling", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isConnected) "Device connected" else "Device not connected",
            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        if (!isConnected) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Go to Home, connect your XHale device, then return to start sampling.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (isConnected && warmupBlocked) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Warming up sensor... ${state.preparationSecondsLeft}s",
                color = MaterialTheme.colorScheme.secondary
            )
        }
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
            if (!state.isSampling) {
                Button(onClick = { onStart(duration) }, enabled = isConnected && !warmupBlocked) { Text("Start") }
            } else {
                Button(onClick = onStop) { Text("Stop") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("CO raw: ${state.coRaw?.let { String.format("%.2f", it) } ?: "--"}")
        Text("Temp: ${state.temperatureC?.let { String.format("%.2f", it) } ?: "--"} °C")
        Text("Humidity: ${state.humidityPercent?.let { String.format("%.2f", it) } ?: "--"} %")
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

        val co = state.points.mapIndexedNotNull { idx, p -> p.coRaw?.let { Entry(idx.toFloat(), it.toFloat()) } }
        val t = state.points.mapIndexedNotNull { idx, p -> p.temperatureC?.let { Entry(idx.toFloat(), it.toFloat()) } }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                factory = { ctx ->
                    LineChart(ctx).apply {
                        description.isEnabled = false
                        setNoDataText("Start sampling to see live chart")
                        setNoDataTextColor(0xFF777777.toInt())
                        setDrawGridBackground(false)
                        setDrawBorders(false)
                        setTouchEnabled(true)
                        isDragEnabled = true
                        setScaleEnabled(true)
                        setPinchZoom(true)
                        isDoubleTapToZoomEnabled = false
                        isHighlightPerTapEnabled = true
                        isHighlightPerDragEnabled = true

                        legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
                        legend.horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
                        legend.orientation = Legend.LegendOrientation.HORIZONTAL
                        legend.form = Legend.LegendForm.LINE
                        legend.textSize = 11f
                        legend.isWordWrapEnabled = true

                        xAxis.position = XAxis.XAxisPosition.BOTTOM
                        xAxis.granularity = 1f
                        xAxis.setDrawGridLines(true)
                        xAxis.textSize = 10f

                        axisLeft.setDrawGridLines(true)
                        axisLeft.setDrawAxisLine(true)
                        axisLeft.textSize = 10f

                        axisRight.isEnabled = true
                        axisRight.setDrawGridLines(false)
                        axisRight.setDrawAxisLine(true)
                        axisRight.textSize = 10f
                        axisRight.textColor = 0xFF2196F3.toInt()

                        setExtraOffsets(6f, 6f, 6f, 6f)
                    }
                },
                update = { chart ->
                    val coSet = LineDataSet(co, "CO raw").apply {
                        setDrawValues(false)
                        setDrawCircles(co.size <= 80)
                        circleRadius = 2.2f
                        color = 0xFFE91E63.toInt()
                        setCircleColor(0xFFE91E63.toInt())
                        lineWidth = 2.2f
                        mode = LineDataSet.Mode.LINEAR
                    }
                    val tSet = LineDataSet(t, "Temp °C").apply {
                        setDrawValues(false)
                        setDrawCircles(false)
                        color = 0xFF2196F3.toInt()
                        lineWidth = 2.2f
                        axisDependency = YAxis.AxisDependency.RIGHT
                        mode = LineDataSet.Mode.LINEAR
                    }

                    if (co.isNotEmpty()) {
                        val minCo = co.minOf { it.y }
                        val maxCo = co.maxOf { it.y }
                        val padCo = ((maxCo - minCo) * 0.15f).coerceAtLeast(5f)
                        chart.axisLeft.axisMinimum = minCo - padCo
                        chart.axisLeft.axisMaximum = maxCo + padCo
                    }
                    if (t.isNotEmpty()) {
                        val minT = t.minOf { it.y }
                        val maxT = t.maxOf { it.y }
                        val padT = ((maxT - minT) * 0.20f).coerceAtLeast(0.6f)
                        chart.axisRight.axisMinimum = minT - padT
                        chart.axisRight.axisMaximum = maxT + padT
                    }

                    chart.data = LineData(coSet, tSet)
                    if (co.isNotEmpty()) {
                        chart.xAxis.axisMinimum = max(0f, co.last().x - 120f)
                        chart.moveViewToX(co.last().x)
                    }
                    chart.notifyDataSetChanged()
                    chart.invalidate()
                }
            )
        }
    }
}

