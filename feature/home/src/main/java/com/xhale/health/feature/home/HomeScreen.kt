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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.delay

private const val TEMP_STALE_TIMEOUT_MS = 8_000L

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
        val context = LocalContext.current
        val permissions = remember {
            mutableListOf<String>().apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    // ≤ Android 11: location often required for BLE scans
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
        var showBluetoothAlert by remember { mutableStateOf(false) }
        var bluetoothAlertMessage by remember { mutableStateOf("") }
        var showOpenAppSettingsAction by remember { mutableStateOf(false) }
        var asked by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grantResults ->
            val allGranted = grantResults.values.all { it }
            if (!allGranted) {
                bluetoothAlertMessage =
                    "Bluetooth permission is denied. Please enable Bluetooth permissions in App Settings to scan and connect to your XHale device."
                showOpenAppSettingsAction = true
                showBluetoothAlert = true
            }
        }

        fun hasRequiredBlePermissions(): Boolean {
            return permissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun ensureBluetoothReady(): Boolean {
            if (!state.bluetoothAvailable) {
                bluetoothAlertMessage =
                    "Bluetooth is turned off. Please enable Bluetooth to scan and connect to your XHale device."
                showOpenAppSettingsAction = false
                showBluetoothAlert = true
                return false
            }
            if (!hasRequiredBlePermissions()) {
                launcher.launch(permissions.toTypedArray())
                bluetoothAlertMessage =
                    "Bluetooth permission is required. Allow Bluetooth access in App Settings to continue."
                showOpenAppSettingsAction = true
                showBluetoothAlert = true
                return false
            }
            return true
        }

        LaunchedEffect(Unit) {
            if (!asked) {
                asked = true
                launcher.launch(permissions.toTypedArray())
            }
        }

        val nowMs by produceState(initialValue = System.currentTimeMillis()) {
            while (true) {
                delay(1_000)
                value = System.currentTimeMillis()
            }
        }
        val tempAgeMs = state.lastTemperatureUpdateMs?.let { (nowMs - it).coerceAtLeast(0L) }
        val showNoTempWarning =
            state.connectedDeviceId != null &&
                !state.isPreparingBaseline &&
                (state.lastTemperatureUpdateMs == null ||
                    (tempAgeMs ?: Long.MAX_VALUE) > TEMP_STALE_TIMEOUT_MS)
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

                // Network connectivity warning
                if (!state.isNetworkConnected) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFF5722).copy(alpha = 0.9f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "⚠️ No Internet Connection",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Please connect to WiFi or mobile data to use the app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

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

                            Button(
                                onClick = {
                                    if (!ensureBluetoothReady()) return@Button
                                    onScanToggle()
                                },
                                enabled = state.isNetworkConnected
                            ) {
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

                    Button(
                        onClick = {
                            if (!ensureBluetoothReady()) return@Button
                            onScanToggle()
                        },
                        enabled = state.isNetworkConnected
                    ) {
                        Text(if (state.isScanning) "Stop Scan" else "Start Scan")
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyColumn {
                        items(state.devices) { device ->
                            Card(
                                modifier = Modifier.padding(vertical = 4.dp).clickable(
                                    enabled = state.isNetworkConnected,
                                    onClick = {
                                        if (!ensureBluetoothReady()) return@clickable
                                        onConnect(device.deviceId)
                                    }
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (state.isNetworkConnected) 
                                        MaterialTheme.colorScheme.surface 
                                    else 
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.weight(1f))
                                        if (state.connectedDeviceId == device.deviceId) {
                                            Text("Connected", color = Color(0xFF4CAF50))
                                        }
                                    }
                                    if (state.connectedDeviceId == device.deviceId) {
                                        Text("Serial: ${state.serialNumber ?: "--"}")
                                        Text("FW: ${state.firmwareRev ?: "--"}")
                                        Text("MAC: ${device.macAddress ?: "N/A"}")
                                        Spacer(Modifier.height(4.dp))
                                        Row {
                                            Text("CO: ${state.coPpm?.let { String.format("%.2f", it) } ?: "--"} ppm")
                                            Spacer(Modifier.size(12.dp))
                                            Text("Temp: ${state.temperatureC?.let { String.format("%.2f", it) } ?: "--"} °C")
                                            Spacer(Modifier.size(12.dp))
                                            val est = BatteryEstimator.estimate(state.batteryPercent)
                                            Text("Battery: ${state.batteryPercent?.toString() ?: "--"}%${est?.let { ", ~" + String.format("%.0f", it.hoursRemaining) + "h" } ?: ""}")
                                        }
                                    } else {
                                        Text("MAC: ${device.macAddress ?: "N/A"}")
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

                if (showNoTempWarning) {
                    val warning = if (state.lastTemperatureUpdateMs == null) {
                        "No temp data yet. Keep device connected and wait for sensor updates."
                    } else {
                        val seconds = ((tempAgeMs ?: 0L) / 1_000L).coerceAtLeast(1L)
                        "Temp has not updated for ${seconds}s. Keep the device connected."
                    }
                    Text(
                        text = warning,
                        color = Color(0xFFFFF176),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.connectedDeviceId != null) {
                        Button(onClick = onDisconnect) { Text("Disconnect") }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onNavigateToBreath,
                            enabled = !state.isPreparingBaseline && state.isNetworkConnected
                        ) { Text("Take Sample") }
                        Spacer(Modifier.width(12.dp))
                    }
                    Button(onClick = onNavigateToTrends) { Text("Weekly Trends") }
                }
            }

            if (showBluetoothAlert) {
                AlertDialog(
                    onDismissRequest = { showBluetoothAlert = false },
                    title = { Text("Bluetooth Required") },
                    text = { Text(bluetoothAlertMessage) },
                    confirmButton = {
                        TextButton(onClick = { showBluetoothAlert = false }) {
                            Text("OK")
                        }
                    },
                    dismissButton = {
                        if (showOpenAppSettingsAction) {
                            TextButton(
                                onClick = {
                                    val appSettingsIntent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                    context.startActivity(appSettingsIntent)
                                    showBluetoothAlert = false
                                }
                            ) {
                                Text("Open Settings")
                            }
                        }
                    }
                )
            }
        }
    }
}

