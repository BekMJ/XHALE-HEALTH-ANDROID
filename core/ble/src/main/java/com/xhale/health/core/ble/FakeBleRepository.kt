package com.xhale.health.core.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

class FakeBleRepository : BleRepository {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val warmupDelaySeconds = 20
    private val baselineCaptureDelaySeconds = 7

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
    private val _baselinePreparation = MutableStateFlow(BaselinePreparationState())
    override val baselinePreparation: StateFlow<BaselinePreparationState> = _baselinePreparation.asStateFlow()

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
        startWarmupLifecycle()
        startEmittingLiveData()
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _baselinePreparation.value = BaselinePreparationState()
    }

    override suspend fun writeCommandStartSampling() { /* no-op in fake */ }
    override suspend fun writeCommandStopSampling() { /* no-op in fake */ }

    private fun startWarmupLifecycle() {
        scope.launch {
            for (remaining in warmupDelaySeconds downTo 1) {
                _baselinePreparation.value = BaselinePreparationState(
                    isPreparingBaseline = true,
                    preparationSecondsLeft = remaining,
                    isWarmupComplete = false
                )
                delay(1000)
            }
            _baselinePreparation.value = _baselinePreparation.value.copy(
                isPreparingBaseline = false,
                preparationSecondsLeft = 0,
                isWarmupComplete = true
            )

            delay(baselineCaptureDelaySeconds * 1000L)
            val raw = _liveData.value.coRaw ?: _liveData.value.coPpm ?: return@launch
            val temp = _liveData.value.temperatureC
            val voltage = estimateVoltageFromRaw(raw)
            val percent = estimateCr2032SocPercent(voltage)
            _baselinePreparation.value = _baselinePreparation.value.copy(
                baselineRawValue = raw,
                baselineTemperatureC = temp,
                rawBatteryAdc = raw,
                batteryVoltage = voltage,
                calculatedBatteryPercent = percent,
                batteryCapacityMah = 220.0 * (percent / 100.0)
            )
        }
    }

    private fun startEmittingLiveData() {
        scope.launch {
            var t = 0.0
            while (_connectionState.value == ConnectionState.CONNECTED) {
                val co = 2.0 + 1.5 * kotlin.math.sin(t)
                val temp = 23.0 + 0.5 * kotlin.math.cos(t / 2)
                val battery = (_liveData.value.batteryPercent ?: 100).coerceAtLeast(1)
                val drop = if (Random.nextDouble() < 0.05) 1 else 0
                _liveData.update {
                    it.copy(
                        coPpm = co,
                        coRaw = co,
                        temperatureC = temp,
                        lastTemperatureUpdateMs = System.currentTimeMillis(),
                        batteryPercent = battery - drop
                    )
                }
                delay(200)
                t += 0.2
            }
        }
    }

    private fun estimateVoltageFromRaw(raw: Double): Double = (raw + 4.67) / 150.30

    private fun estimateCr2032SocPercent(voltage: Double): Int {
        val curve = listOf(
            3.00 to 100,
            2.95 to 95,
            2.90 to 88,
            2.85 to 78,
            2.80 to 66,
            2.75 to 52,
            2.70 to 38,
            2.65 to 26,
            2.60 to 16,
            2.55 to 9,
            2.50 to 4,
            2.45 to 2,
            2.40 to 0
        )
        if (voltage >= curve.first().first) return curve.first().second
        if (voltage <= curve.last().first) return curve.last().second

        for (i in 0 until curve.lastIndex) {
            val (vHigh, pHigh) = curve[i]
            val (vLow, pLow) = curve[i + 1]
            if (voltage <= vHigh && voltage >= vLow) {
                val ratio = (voltage - vLow) / (vHigh - vLow)
                return (pLow + (pHigh - pLow) * ratio).toInt().coerceIn(0, 100)
            }
        }
        return 0
    }
}

