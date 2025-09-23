package com.xhale.health.core.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

class FakeBleRepository : BleRepository {
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _bluetoothAvailable = MutableStateFlow(true)
    override val bluetoothAvailable: StateFlow<Boolean> = _bluetoothAvailable.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<DiscoveredDevice?>(null)
    override val connectedDevice: StateFlow<DiscoveredDevice?> = _connectedDevice.asStateFlow()

    private val _liveData = MutableStateFlow(LiveSensorData(null, null, 100, "ABC123"))
    override val liveData: StateFlow<LiveSensorData> = _liveData.asStateFlow()

    override suspend fun startScan() {
        _isScanning.value = true
        scope.launch {
            delay(500)
            _discoveredDevices.value = listOf(
                DiscoveredDevice("dev-1", "XHale Health", "00:11:22:33:44:55", -42),
                DiscoveredDevice("dev-2", "Env Sensor", "11:22:33:44:55:66", -60)
            )
        }
    }

    override suspend fun stopScan() {
        _isScanning.value = false
    }

    override suspend fun connect(deviceId: String) {
        val device = _discoveredDevices.value.firstOrNull { it.deviceId == deviceId } ?: return
        _connectionState.value = ConnectionState.CONNECTING
        delay(600)
        _connectedDevice.value = device
        _connectionState.value = ConnectionState.CONNECTED
        startEmittingLiveData()
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
    }

    override suspend fun writeCommandStartSampling() { /* no-op in fake */ }
    override suspend fun writeCommandStopSampling() { /* no-op in fake */ }

    private fun startEmittingLiveData() {
        scope.launch {
            var t = 0.0
            while (_connectionState.value == ConnectionState.CONNECTED) {
                val co = 2.0 + 1.5 * kotlin.math.sin(t)
                val temp = 23.0 + 0.5 * kotlin.math.cos(t / 2)
                val battery = (_liveData.value.batteryPercent ?: 100).coerceAtLeast(1)
                val drop = if (Random.nextDouble() < 0.05) 1 else 0
                _liveData.update { it.copy(coPpm = co, temperatureC = temp, batteryPercent = battery - drop) }
                delay(200)
                t += 0.2
            }
        }
    }
}

