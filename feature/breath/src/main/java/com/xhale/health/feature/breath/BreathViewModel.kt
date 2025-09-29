package com.xhale.health.feature.breath

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xhale.health.core.ble.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.xhale.health.core.firebase.FirestoreRepository
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

data class BreathUiState(
    val remainingSec: Int = 0,
    val isSampling: Boolean = false,
    val coPpm: Double? = null,
    val temperatureC: Double? = null,
    val batteryPercent: Int? = null,
    val points: List<Pair<Long, Pair<Double?, Double?>>> = emptyList(),
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
    private var collectJob: Job? = null

    fun startSampling(durationSec: Int) {
        val sessionId = csvExportUtil.generateSessionId()
        _state.update { 
            it.copy(
                isSampling = true, 
                remainingSec = durationSec, 
                points = emptyList(),
                sessionId = sessionId,
                exportResult = null
            ) 
        }
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            ble.liveData.collectLatest { live ->
                val now = System.currentTimeMillis()
                _state.update { s ->
                    s.copy(
                        coPpm = live.coPpm,
                        temperatureC = live.temperatureC,
                        batteryPercent = live.batteryPercent,
                        points = s.points + (now to (live.coPpm to live.temperatureC))
                    )
                }
            }
        }
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (_state.value.remainingSec > 0) {
                delay(1000)
                _state.update { it.copy(remainingSec = it.remainingSec - 1) }
            }
            stopSampling()
        }
    }

    fun stopSampling() {
        tickerJob?.cancel(); collectJob?.cancel()
        _state.update { it.copy(isSampling = false) }

        // Perform analysis when sampling ends if we have enough points
        val points = _state.value.points
        if (points.size >= 5) {
            viewModelScope.launch {
                val window = points.map { (ts, pair) ->
                    WindowPoint(
                        timestampMs = ts,
                        rRaw = (pair.first ?: 0.0),
                        tC = (pair.second ?: 0.0),
                        v = 3.7 // placeholder voltage unless available from BLE
                    )
                }
                val baselines = analyzeBreath.deriveBaselines(window)
                val result = analyzeBreath.execute(window, baselines)
                _state.update { s ->
                    s.copy(
                        exportResult = "PPM: " + String.format("%.2f", result.estimatedPpm) +
                            ", ΔT: " + String.format("%.2f", result.temperatureRiseC) +
                            (if (result.flags.shortDuration) " (short)" else "") +
                            (if (result.flags.smallTemperatureRise) " (low ΔT)" else "") +
                            (if (result.flags.unstableBaseline) " (unstable baseline)" else "")
                    )
                }

                // Save to Firestore if enabled and connected
                val formattedStart = firestore.formatTimestamp(window.first().timestampMs)
                val dataPoints = window.map { wp ->
                    BreathDataPoint(
                        timestamp = firestore.formatTimestamp(wp.timestampMs),
                        coPpm = (wp.rRaw - baselines.rBase - 0.80 * (wp.tC - baselines.tBase)) / 2.55,
                        temperatureC = wp.tC,
                        batteryPercent = _state.value.batteryPercent
                    )
                }
                val session = BreathSession(
                    sessionId = _state.value.sessionId.ifEmpty { csvExportUtil.generateSessionId() },
                    deviceId = _state.value.sessionId, // placeholder; inject actual device id from BLE state later
                    userId = "",
                    startedAt = formattedStart,
                    durationSeconds = result.breathDurationSec,
                    dataPoints = dataPoints
                )
                firestore.saveBreathSession(session)
            }
        }
    }
    
    fun exportToCsv() {
        val currentState = _state.value
        if (currentState.points.isEmpty()) return
        
        _state.update { it.copy(isExporting = true) }
        
        viewModelScope.launch {
            val sampleData = currentState.points.map { (timestamp, data) ->
                BreathSampleData(
                    timestamp = timestamp,
                    coPpm = data.first,
                    temperatureC = data.second,
                    sessionId = currentState.sessionId
                )
            }
            
            val result = csvExportUtil.exportToCsv(sampleData, currentState.sessionId)
            
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
}

