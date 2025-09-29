package com.xhale.health.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun TrendsScreen(viewModel: TrendsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Weekly Trends", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Smoke‑free streak: ${state.streakDays} days")
                Spacer(Modifier.height(8.dp))
                val entries = state.daily.mapIndexedNotNull { idx, m ->
                    if (!m.medianPpm.isNaN()) Entry(idx.toFloat(), m.medianPpm.toFloat()) else null
                }
                AndroidView(factory = { ctx ->
                    LineChart(ctx).apply {
                        description.isEnabled = false
                        legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
                        legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
                        xAxis.position = XAxis.XAxisPosition.BOTTOM
                        axisRight.isEnabled = false
                    }
                }, update = { chart ->
                    val set = LineDataSet(entries, "Daily median ppm").apply {
                        setDrawCircles(false); color = 0xFF4CAF50.toInt(); lineWidth = 2f
                    }
                    chart.data = LineData(set)
                    chart.invalidate()
                })
            }
        }
    }
}


