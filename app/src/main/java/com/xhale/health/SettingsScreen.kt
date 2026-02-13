package com.xhale.health.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        val state by viewModel.state.collectAsState()

        ListItem(
            headlineContent = { Text("Sample duration") },
            supportingContent = { Text("${state.sampleDurationSec} seconds") },
            trailingContent = { Text("Edit") }
        )
        Spacer(Modifier.height(8.dp))
        var temp by remember { mutableStateOf(state.sampleDurationSec) }
        androidx.compose.material3.Slider(
            value = temp.toFloat(),
            onValueChange = { temp = it.toInt() },
            valueRange = 5f..60f
        )
        Text("${temp}s")
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.Button(onClick = { viewModel.setSampleDuration(temp) }) { Text("Save") }
        Divider()

        Spacer(Modifier.height(12.dp))
        Text("Battery diagnostics", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        ListItem(
            headlineContent = { Text("Firmware battery (BAS)") },
            supportingContent = { Text("${state.firmwareBatteryPercent?.toString() ?: "--"}%") }
        )
        ListItem(
            headlineContent = { Text("Calculated battery") },
            supportingContent = { Text("${state.calculatedBatteryPercent?.toString() ?: "--"}%") }
        )
        ListItem(
            headlineContent = { Text("Battery voltage") },
            supportingContent = {
                Text(
                    state.batteryVoltage?.let { String.format("%.3f V", it) } ?: "--"
                )
            }
        )
        ListItem(
            headlineContent = { Text("Battery capacity") },
            supportingContent = {
                Text(
                    state.batteryCapacityMah?.let { String.format("%.1f mAh", it) } ?: "--"
                )
            }
        )
        ListItem(
            headlineContent = { Text("Raw battery ADC") },
            supportingContent = {
                Text(
                    state.rawBatteryAdc?.let { String.format("%.2f", it) } ?: "--"
                )
            }
        )
        Divider()
    }
}


