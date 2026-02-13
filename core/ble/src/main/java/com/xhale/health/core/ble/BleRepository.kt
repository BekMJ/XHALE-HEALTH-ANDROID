package com.xhale.health.core.ble

import kotlinx.coroutines.flow.Flow

interface BleRepository {
    val bluetoothAvailable: Flow<Boolean>
    val isScanning: Flow<Boolean>
    val discoveredDevices: Flow<List<DiscoveredDevice>>
    val connectionState: Flow<ConnectionState>
    val connectedDevice: Flow<DiscoveredDevice?>
    val liveData: Flow<LiveSensorData>
    val baselinePreparation: Flow<BaselinePreparationState>

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(deviceId: String)
    suspend fun disconnect()
    suspend fun writeCommandStartSampling()
    suspend fun writeCommandStopSampling()
}

