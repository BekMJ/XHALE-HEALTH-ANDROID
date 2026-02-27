package com.xhale.health.feature.breath

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xhale.health.core.ble.BleRepository
import com.xhale.health.core.ble.ConnectionState
import com.xhale.health.core.firebase.BreathDataPoint
import com.xhale.health.core.firebase.BreathSession
import com.xhale.health.core.firebase.DeviceCalibration
import com.xhale.health.core.firebase.FirestoreRepository
import com.xhale.health.core.ui.NetworkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import javax.inject.Inject

data class BreathSamplePoint(
    val timestampMs: Long,
    val coRaw: Double?,
    val temperatureC: Double?,
    val voltageV: Double?,
    val batteryPercent: Int?
)

data class BreathUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isPreparingBaseline: Boolean = false,
    val preparationSecondsLeft: Int = 0,
    val isWarmupComplete: Boolean = false,
    val warmupBaselineRaw: Double? = null,
    val warmupBatteryVoltage: Double? = null,
    val remainingSec: Int = 0,
    val isSampling: Boolean = false,
    val coRaw: Double? = null,
    val temperatureC: Double? = null,
    val batteryPercent: Int? = null,
    val lastTemperatureUpdateMs: Long? = null,
    val serialNumber: String? = null,
    val connectedDeviceId: String? = null,
    val points: List<BreathSamplePoint> = emptyList(),
    val sessionId: String = "",
    val predictedPpm: Double? = null,
    val isExporting: Boolean = false,
    val exportResult: String? = null,
    val lastExportUri: android.net.Uri? = null,
    val isNetworkConnected: Boolean = false,
    val showSensorDamagedDialog: Boolean = false
)

@HiltViewModel
class BreathViewModel @Inject constructor(
    private val ble: BleRepository,
    private val csvExportUtil: CsvExportUtil,
    private val analyzeBreath: AnalyzeBreathUseCase,
    private val firestore: FirestoreRepository,
    private val network: NetworkRepository
) : ViewModel() {
    private data class TimedSample(val timestampMs: Long, val value: Double)

    private val _state = MutableStateFlow(BreathUiState())
    val state: StateFlow<BreathUiState> = _state

    private var tickerJob: Job? = null
    private var activeSampleDurationSec: Int? = null
    private var activeSessionSerialForCalibration: String? = null
    private var activeSessionCloudCalibration: GasFitCoefficients? = null
    private val calibrationCache = mutableMapOf<String, DeviceCalibration?>()
    private val calibrationFetchInFlight = mutableSetOf<String>()

    private val coSamples = mutableListOf<TimedSample>()
    private val tempSamples = mutableListOf<TimedSample>()
    private var lastObservedCoUpdateMs: Long? = null
    private var lastObservedTempUpdateMs: Long? = null

    init {
        observeBleDevice()
        observeBleConnectionState()
        observeBaselinePreparation()
        observeBleLiveData()
        observeNetworkConnectivity()
    }

    private fun observeNetworkConnectivity() {
        viewModelScope.launch {
            network.isConnected.collectLatest { connected ->
                _state.update { it.copy(isNetworkConnected = connected) }
                if (!connected && _state.value.isSampling) {
                    stopSampling(
                        shouldAnalyze = false,
                        reason = "Internet connection lost. Please reconnect to WiFi or mobile data to continue."
                    )
                }
            }
        }
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
                    shouldForceStop = current.isSampling && connection != ConnectionState.CONNECTED
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
                        warmupBaselineRaw = prep.baselineRawValue,
                        warmupBatteryVoltage = prep.batteryVoltage
                    )
                }
            }
        }
    }

    private fun observeBleLiveData() {
        viewModelScope.launch {
            ble.liveData.collectLatest { live ->
                val coRaw = live.coRaw ?: live.coPpm
                val coUpdateMs = live.lastCoUpdateMs
                val tempUpdateMs = live.lastTemperatureUpdateMs
                val isSampling = _state.value.isSampling

                if (isSampling && tempUpdateMs != null && tempUpdateMs != lastObservedTempUpdateMs) {
                    live.temperatureC?.let { temp ->
                        tempSamples += TimedSample(timestampMs = tempUpdateMs, value = temp)
                        lastObservedTempUpdateMs = tempUpdateMs
                    }
                }

                var newPoint: BreathSamplePoint? = null
                if (isSampling && coUpdateMs != null && coUpdateMs != lastObservedCoUpdateMs) {
                    coRaw?.let { raw ->
                        coSamples += TimedSample(timestampMs = coUpdateMs, value = raw)
                        lastObservedCoUpdateMs = coUpdateMs
                        val currentState = _state.value
                        val fixedVoltage = currentState.warmupBatteryVoltage
                            ?: currentState.warmupBaselineRaw?.let { estimateVoltageFromRaw(it) }
                        newPoint = BreathSamplePoint(
                            timestampMs = coUpdateMs,
                            coRaw = raw,
                            temperatureC = nearestSampleValue(tempSamples, coUpdateMs) ?: live.temperatureC,
                            voltageV = fixedVoltage,
                            batteryPercent = live.batteryPercent ?: currentState.batteryPercent
                        )
                    }
                }

                _state.update { s ->
                    val points = newPoint?.let { s.points + it } ?: s.points
                    s.copy(
                        coRaw = coRaw,
                        temperatureC = live.temperatureC,
                        batteryPercent = live.batteryPercent ?: s.batteryPercent,
                        lastTemperatureUpdateMs = live.lastTemperatureUpdateMs,
                        serialNumber = live.serialNumber ?: s.serialNumber,
                        points = points
                    )
                }
                ensureCalibrationFetched(live.serialNumber)
            }
        }
    }

    fun startSampling(durationSec: Int) {
        if (!_state.value.isNetworkConnected) {
            _state.update {
                it.copy(exportResult = "Internet connection required. Please connect to WiFi or mobile data.")
            }
            return
        }
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

        coSamples.clear()
        tempSamples.clear()
        lastObservedCoUpdateMs = null
        lastObservedTempUpdateMs = null

        val sessionId = csvExportUtil.generateSessionId()
        val sessionSerialSnapshot = _state.value.serialNumber
        val sessionCloudCalibrationSnapshot = sessionSerialSnapshot
            ?.let { normalizedSerialPrefix(it) }
            ?.takeIf { it.length == 8 }
            ?.let { calibrationCache[it] }
            ?.toGasFitCoefficients()
        _state.update {
            it.copy(
                isSampling = true,
                remainingSec = durationSec,
                points = emptyList(),
                sessionId = sessionId,
                predictedPpm = null,
                exportResult = null,
                lastExportUri = null,
                showSensorDamagedDialog = false
            )
        }
        activeSampleDurationSec = durationSec
        activeSessionSerialForCalibration = sessionSerialSnapshot
        activeSessionCloudCalibration = sessionCloudCalibrationSnapshot
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
        val configuredSampleDurationSec = activeSampleDurationSec
        val configuredSessionSerial = activeSessionSerialForCalibration
        val configuredSessionCloudCalibration = activeSessionCloudCalibration
        activeSampleDurationSec = null
        activeSessionSerialForCalibration = null
        activeSessionCloudCalibration = null
        _state.update {
            it.copy(
                isSampling = false,
                exportResult = reason ?: it.exportResult
            )
        }

        val pointSnapshot = _state.value.points
        val coSnapshot = coSamples.toList()
        val tempSnapshot = tempSamples.toList()

        if (shouldAnalyze && coSnapshot.size >= 5) {
            viewModelScope.launch {
                val fixedVoltage = _state.value.warmupBatteryVoltage
                    ?: _state.value.warmupBaselineRaw?.let { estimateVoltageFromRaw(it) }

                val window = coSnapshot.mapNotNull { coSample ->
                    val temp = nearestSampleValue(tempSnapshot, coSample.timestampMs) ?: return@mapNotNull null
                    WindowPoint(
                        timestampMs = coSample.timestampMs,
                        rRaw = coSample.value,
                        tC = temp,
                        v = fixedVoltage
                    )
                }

                if (window.size < 5) {
                    _state.update {
                        it.copy(
                            predictedPpm = null,
                            exportResult = "Not enough temperature/CO data to estimate PPM. Keep the device connected and try again."
                        )
                    }
                    return@launch
                }

                val result = analyzeBreath.execute(
                    window = window,
                    serialNumber = configuredSessionSerial,
                    warmupBaselineRaw = _state.value.warmupBaselineRaw,
                    cloudGasFitCoefficients = configuredSessionCloudCalibration,
                    sampleDurationSec = configuredSampleDurationSec
                )

                val suspiciousLowThreshold = 200.0
                val sensorSuspicious =
                    result.baselineCO < suspiciousLowThreshold && result.peakCO < suspiciousLowThreshold

                _state.update { s ->
                    s.copy(
                        predictedPpm = result.estimatedPpm,
                        exportResult = "PPM: " + String.format("%.2f", result.estimatedPpm) +
                            ", dT: " + String.format("%.2f", result.temperatureRiseC) +
                            (if (result.flags.shortDuration) " (short)" else "") +
                            (if (result.flags.smallTemperatureRise) " (low dT)" else "") +
                            (if (result.flags.unstableBaseline) " (unstable baseline)" else ""),
                        showSensorDamagedDialog = sensorSuspicious
                    )
                }

                val serialForUpload = (configuredSessionSerial ?: _state.value.serialNumber)
                    ?.takeIf { it.isNotBlank() }
                if (serialForUpload != null) {
                    val formattedStart = firestore.formatTimestamp(window.first().timestampMs)
                    val dataPoints = pointSnapshot.map { sample ->
                        BreathDataPoint(
                            timestamp = firestore.formatTimestamp(sample.timestampMs),
                            coRaw = sample.coRaw,
                            temperatureC = sample.temperatureC,
                            batteryPercent = sample.batteryPercent,
                            coPpm = null
                        )
                    }
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
                        calibrationMode = result.calibrationMode,
                        calibrationSource = result.calibrationSource,
                        calibrationPath = result.calibrationPath.name,
                        calibrationSlopeRawPerPpm = result.calibrationSlopeRawPerPpm,
                        calibrationIntercept = result.calibrationIntercept,
                        calibrationGainRawPerPpm = result.calibrationGainRawPerPpm,
                        calibrationDriftRawPerSec = result.calibrationDriftRawPerSec,
                        calibrationTauSec = result.calibrationTauSec,
                        calibrationDeadSec = result.calibrationDeadSec,
                        calibrationDurationBucketSec = result.calibrationDurationBucketSec,
                        timestamps = listOf(
                            firestore.formatTimestamp(window.first().timestampMs),
                            firestore.formatTimestamp(window.last().timestampMs)
                        ),
                        dataPoints = dataPoints
                    )
                    firestore.saveBreathSession(session)
                }
            }
        } else if (shouldAnalyze) {
            _state.update {
                it.copy(
                    predictedPpm = null,
                    exportResult = "Not enough sample points captured to estimate PPM."
                )
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

    fun dismissSensorDamagedDialog() {
        _state.update { it.copy(showSensorDamagedDialog = false) }
    }

    private fun estimateVoltageFromRaw(raw: Double): Double = (raw + 4.67) / 150.30

    private fun nearestSampleValue(samples: List<TimedSample>, targetTimestampMs: Long): Double? {
        if (samples.isEmpty()) return null
        var best = samples.first()
        var bestDelta = abs(best.timestampMs - targetTimestampMs)
        for (i in 1 until samples.size) {
            val candidate = samples[i]
            val delta = abs(candidate.timestampMs - targetTimestampMs)
            if (delta < bestDelta) {
                best = candidate
                bestDelta = delta
            }
        }
        return best.value
    }

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
                if (result.isSuccess) {
                    calibrationCache[prefix] = result.getOrNull()
                }
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
