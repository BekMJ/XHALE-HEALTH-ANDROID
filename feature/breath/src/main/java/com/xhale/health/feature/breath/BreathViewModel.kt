package com.xhale.health.feature.breath

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xhale.health.core.ble.BleRepository
import com.xhale.health.core.ble.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xhale.health.core.firebase.FirestoreRepository
import com.xhale.health.core.firebase.DeviceCalibration
import com.xhale.health.core.firebase.BreathSession
import com.xhale.health.core.firebase.BreathDataPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BreathSamplePoint(
    val timestampMs: Long,
    val coRaw: Double?,
    val temperatureC: Double?,
    val humidityPercent: Double?,
    val voltageV: Double?,
    val batteryPercent: Int?
)

data class BreathUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isPreparingBaseline: Boolean = false,
    val preparationSecondsLeft: Int = 0,
    val isWarmupComplete: Boolean = false,
    val warmupBaselineRaw: Double? = null,
    val remainingSec: Int = 0,
    val isSampling: Boolean = false,
    val coRaw: Double? = null,
    val temperatureC: Double? = null,
    val humidityPercent: Double? = null,
    val batteryPercent: Int? = null,
    val serialNumber: String? = null,
    val connectedDeviceId: String? = null,
    val points: List<BreathSamplePoint> = emptyList(),
    val sessionId: String = "",
    val isExporting: Boolean = false,
    val exportResult: String? = null,
    val lastExportUri: android.net.Uri? = null,
)

@HiltViewModel
class BreathViewModel @Inject constructor(
    private val ble: BleRepository,
    private val csvExportUtil: CsvExportUtil,
    private val analyzeBreath: AnalyzeBreathUseCase,
    private val firestore: FirestoreRepository
) : ViewModel() {
    private val _state = MutableStateFlow(BreathUiState())
    val state: StateFlow<BreathUiState> = _state

    private var tickerJob: Job? = null
    private val calibrationCache = mutableMapOf<String, DeviceCalibration?>()
    private val calibrationFetchInFlight = mutableSetOf<String>()

    init {
        observeBleDevice()
        observeBleConnectionState()
        observeBaselinePreparation()
        observeBleLiveData()
    }

    private fun observeBleDevice() {
        viewModelScope.launch {
            ble.connectedDevice.collectLatest { device ->
                _state.update { it.copy(connectedDeviceId = device?.deviceId) }
            }
        }
    }

    private fun observeBleConnectionState() {
        viewModelScope.launch {
            ble.connectionState.collectLatest { connection ->
                var shouldForceStop = false
                _state.update { current ->
                    shouldForceStop =
                        current.isSampling && connection != ConnectionState.CONNECTED
                    current.copy(connectionState = connection)
                }
                if (shouldForceStop) {
                    stopSampling(
                        shouldAnalyze = false,
                        reason = "Device disconnected. Reconnect on Home before sampling."
                    )
                }
            }
        }
    }

    private fun observeBaselinePreparation() {
        viewModelScope.launch {
            ble.baselinePreparation.collectLatest { prep ->
                _state.update {
                    it.copy(
                        isPreparingBaseline = prep.isPreparingBaseline,
                        preparationSecondsLeft = prep.preparationSecondsLeft,
                        isWarmupComplete = prep.isWarmupComplete,
                        warmupBaselineRaw = prep.baselineRawValue
                    )
                }
            }
        }
    }

    private fun observeBleLiveData() {
        viewModelScope.launch {
            ble.liveData.collectLatest { live ->
                val now = System.currentTimeMillis()
                val coRaw = live.coRaw ?: live.coPpm
                val voltage = coRaw?.let { estimateVoltageFromRaw(it) }
                _state.update { s ->
                    val points = if (s.isSampling) {
                        s.points + BreathSamplePoint(
                            timestampMs = now,
                            coRaw = coRaw,
                            temperatureC = live.temperatureC,
                            humidityPercent = live.humidityPercent,
                            voltageV = voltage,
                            batteryPercent = live.batteryPercent ?: s.batteryPercent
                        )
                    } else {
                        s.points
                    }
                    s.copy(
                        coRaw = coRaw,
                        temperatureC = live.temperatureC,
                        humidityPercent = live.humidityPercent,
                        batteryPercent = live.batteryPercent ?: s.batteryPercent,
                        serialNumber = live.serialNumber ?: s.serialNumber,
                        points = points
                    )
                }
                ensureCalibrationFetched(live.serialNumber)
            }
        }
    }

    fun startSampling(durationSec: Int) {
        if (_state.value.connectionState != ConnectionState.CONNECTED || _state.value.connectedDeviceId == null) {
            _state.update {
                it.copy(exportResult = "Connect to your XHale device first (Home screen).")
            }
            return
        }
        if (_state.value.isPreparingBaseline) {
            _state.update {
                it.copy(exportResult = "Warmup in progress. Please wait ${it.preparationSecondsLeft}s.")
            }
            return
        }
        if (_state.value.isSampling) return
        val sessionId = csvExportUtil.generateSessionId()
        _state.update {
            it.copy(
                isSampling = true,
                remainingSec = durationSec,
                points = emptyList(),
                sessionId = sessionId,
                exportResult = null,
                lastExportUri = null
            ) 
        }
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (_state.value.isSampling && _state.value.remainingSec > 0) {
                delay(1000)
                _state.update { it.copy(remainingSec = it.remainingSec - 1) }
            }
            if (_state.value.isSampling) {
                stopSampling(shouldAnalyze = true)
            }
        }
    }

    fun stopSampling() {
        stopSampling(shouldAnalyze = true)
    }

    private fun stopSampling(shouldAnalyze: Boolean, reason: String? = null) {
        if (!_state.value.isSampling) return
        tickerJob?.cancel()
        _state.update {
            it.copy(
                isSampling = false,
                exportResult = reason ?: it.exportResult
            )
        }

        // Perform analysis when sampling ends if we have enough points
        val points = _state.value.points
        if (shouldAnalyze && points.size >= 5) {
            viewModelScope.launch {
                val window = points.mapNotNull { point ->
                    val raw = point.coRaw ?: return@mapNotNull null
                    val temp = point.temperatureC ?: return@mapNotNull null
                    WindowPoint(
                        timestampMs = point.timestampMs,
                        rRaw = raw,
                        tC = temp,
                        hPct = point.humidityPercent ?: 0.0,
                        v = point.voltageV ?: estimateVoltageFromRaw(raw)
                    )
                }
                if (window.size < 5) return@launch

                val serial = _state.value.serialNumber
                val calibration = serial?.let { calibrationCache[normalizedSerialPrefix(it)] }?.toGasFitCoefficients()
                val result = analyzeBreath.execute(
                    window = window,
                    serialNumber = serial,
                    warmupBaselineRaw = _state.value.warmupBaselineRaw,
                    cloudGasFitCoefficients = calibration
                )
                _state.update { s ->
                    s.copy(
                        exportResult = "PPM: " + String.format("%.2f", result.estimatedPpm) +
                            ", ΔT: " + String.format("%.2f", result.temperatureRiseC) +
                            (if (result.flags.shortDuration) " (short)" else "") +
                            (if (result.flags.smallTemperatureRise) " (low ΔT)" else "") +
                            (if (result.flags.unstableBaseline) " (unstable baseline)" else "")
                    )
                }

                // Save to Firestore automatically only when device serial is available.
                val serialForUpload = _state.value.serialNumber?.takeIf { it.isNotBlank() }
                if (serialForUpload != null) {
                    val formattedStart = firestore.formatTimestamp(window.first().timestampMs)
                    val session = BreathSession(
                        sessionId = _state.value.sessionId.ifEmpty { csvExportUtil.generateSessionId() },
                        deviceId = serialForUpload,
                        userId = "",
                        startedAt = formattedStart,
                        durationSeconds = result.breathDurationSec,
                        estimatedPpm = result.estimatedPpm,
                        deltaRComp = result.deltaRComp,
                        temperatureRiseC = result.temperatureRiseC,
                        baselineCO = result.baselineCO,
                        baselineTemperature = result.baselineTemperature,
                        baselineVoltage = result.baselineVoltage,
                        peakCO = result.peakCO,
                        peakTemperature = result.peakTemperature,
                        peakVoltage = result.peakVoltage,
                        batteryPercent = _state.value.batteryPercent,
                        qualityFlags = mapOf(
                            "shortDuration" to result.flags.shortDuration,
                            "smallTemperatureRise" to result.flags.smallTemperatureRise,
                            "unstableBaseline" to result.flags.unstableBaseline
                        ),
                        timestamps = listOf(
                            firestore.formatTimestamp(window.first().timestampMs),
                            firestore.formatTimestamp(window.last().timestampMs)
                        ),
                        dataPoints = emptyList()
                    )
                    firestore.saveBreathSession(session)
                }
            }
        }
    }
    
    fun exportToCsv() {
        val currentState = _state.value
        if (currentState.points.isEmpty()) return
        
        _state.update { it.copy(isExporting = true) }
        
        viewModelScope.launch {
            val sampleData = currentState.points.map { point ->
                BreathSampleData(
                    timestamp = point.timestampMs,
                    coRaw = point.coRaw,
                    temperatureC = point.temperatureC,
                    humidityPercent = point.humidityPercent,
                    deviceSerial = currentState.serialNumber,
                    sessionId = currentState.sessionId
                )
            }
            
            val result = csvExportUtil.exportToCsv(sampleData, currentState.serialNumber)
            
            _state.update { 
                it.copy(
                    isExporting = false,
                    exportResult = result.fold(
                        onSuccess = { "Exported to Downloads: ${it.first}" },
                        onFailure = { "Export failed: ${it.message}" }
                    ),
                    lastExportUri = result.getOrNull()?.second
                )
            }
        }
    }
    
    fun clearExportResult() {
        _state.update { it.copy(exportResult = null) }
    }

    private fun estimateVoltageFromRaw(raw: Double): Double = (raw + 4.67) / 150.30

    private fun normalizedSerialPrefix(serial: String): String {
        return serial.filter { it.isLetterOrDigit() }.uppercase().take(8)
    }

    private fun ensureCalibrationFetched(serial: String?) {
        val prefix = serial
            ?.let { normalizedSerialPrefix(it) }
            ?.takeIf { it.length == 8 }
            ?: return
        if (calibrationCache.containsKey(prefix) || calibrationFetchInFlight.contains(prefix)) return
        calibrationFetchInFlight += prefix
        viewModelScope.launch {
            try {
                val result = firestore.getDeviceCalibration(prefix)
                calibrationCache[prefix] = result.getOrNull()
            } finally {
                calibrationFetchInFlight -= prefix
            }
        }
    }

    private fun DeviceCalibration.toGasFitCoefficients(): GasFitCoefficients {
        return GasFitCoefficients(
            drift_raw_per_s = driftRawPerSec,
            gain_raw_per_ppm = gainRawPerPpm,
            tauSec = tauSec,
            deadSec = deadSec
        )
    }
}

