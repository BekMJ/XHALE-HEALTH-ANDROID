package com.xhale.health.feature.breath

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

@Composable
fun BreathRoute(viewModel: BreathViewModel) {
    val state by viewModel.state.collectAsState()
    BreathScreen(state, onStart = { viewModel.startSampling(it) }, onStop = viewModel::stopSampling)
}

@Composable
fun BreathScreen(state: BreathUiState, onStart: (Int) -> Unit, onStop: () -> Unit) {
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

