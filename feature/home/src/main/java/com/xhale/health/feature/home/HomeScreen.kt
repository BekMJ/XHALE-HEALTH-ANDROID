package com.xhale.health.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xhale.health.core.ui.XHTheme
import com.xhale.health.core.ui.BatteryEstimator
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavController
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings

@Composable
fun HomeRoute(viewModel: HomeViewModel, onNavigateToBreath: () -> Unit, onNavigateToTrends: () -> Unit, onSignOut: () -> Unit) {
    val state by viewModel.state.collectAsState()
    HomeScreen(
        state = state,
        onScanToggle = viewModel::onScanToggle,
        onConnect = viewModel::onConnect,
        onDisconnect = viewModel::onDisconnect,
        onNavigateToBreath = onNavigateToBreath,
        onNavigateToTrends = onNavigateToTrends,
        onSignOut = onSignOut,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onScanToggle: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToBreath: () -> Unit,
    onNavigateToTrends: () -> Unit,
    onSignOut: () -> Unit,
) {
    XHTheme {
        val permissions = mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // ≤ Android 11: location often required for BLE scans
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        var asked by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!asked) {
                asked = true
                launcher.launch(permissions.toTypedArray())
            }
        }
        val context = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(listOf(Color(0xFF2196F3), Color(0xFF9C27B0)))
                )
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                    Text("XHale Health", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Button(
                        onClick = onSignOut,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.2f)
                        )
                    ) {
                        Text("Sign Out", color = Color.White)
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (state.devices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (state.isScanning) "Scanning…" else "Not Scanning",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(12.dp))

                            if (!state.bluetoothAvailable) {
                                Button(onClick = {
                                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                }) { Text("Enable Bluetooth") }
                                Spacer(Modifier.height(8.dp))
                            }

                            Button(onClick = onScanToggle) {
                                Text(if (state.isScanning) "Stop Scan" else "Start Scan")
                            }

                            Spacer(Modifier.height(12.dp))

                            if (!state.isScanning) {
                                Text(
                                    "No devices found. Tap 'Start Scan' to discover devices.",
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (state.isScanning) "Scanning…" else "Not Scanning",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )

                    Spacer(Modifier.height(8.dp))

                    if (!state.bluetoothAvailable) {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }) { Text("Enable Bluetooth") }
                        Spacer(Modifier.height(8.dp))
                    }

                    Button(onClick = onScanToggle) {
                        Text(if (state.isScanning) "Stop Scan" else "Start Scan")
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyColumn {
                        items(state.devices) { device ->
                            Card(modifier = Modifier.padding(vertical = 4.dp).clickable { onConnect(device.deviceId) }) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.weight(1f))
                                        if (state.connectedDeviceId == device.deviceId) {
                                            Text("Connected", color = Color(0xFF4CAF50))
                                        }
                                    }
                                    Text("MAC: ${device.macAddress ?: "N/A"}")
                                    if (state.connectedDeviceId == device.deviceId) {
                                        Spacer(Modifier.height(4.dp))
                                        Row {
                                            Text("CO: ${state.coPpm?.let { String.format("%.2f", it) } ?: "--"} ppm")
                                            Spacer(Modifier.size(12.dp))
                                            Text("Temp: ${state.temperatureC?.let { String.format("%.2f", it) } ?: "--"} °C")
                                            Spacer(Modifier.size(12.dp))
                                            val est = BatteryEstimator.estimate(state.batteryPercent)
                                            Text("Battery: ${state.batteryPercent?.toString() ?: "--"}%${est?.let { ", ~" + String.format("%.0f", it.hoursRemaining) + "h" } ?: ""}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (state.connectedDeviceId != null && state.isPreparingBaseline) {
                    Text(
                        text = "Preparing baseline... ${state.preparationSecondsLeft}s",
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.connectedDeviceId != null) {
                        Button(onClick = onDisconnect) { Text("Disconnect") }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onNavigateToBreath,
                            enabled = !state.isPreparingBaseline
                        ) { Text("Take Sample") }
                        Spacer(Modifier.width(12.dp))
                    }
                    Button(onClick = onNavigateToTrends) { Text("Weekly Trends") }
                }
            }
        }
    }
}

