package com.xhale.health.feature.breath

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xhale.health.core.ble.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
)

@HiltViewModel
class BreathViewModel @Inject constructor(
    private val ble: BleRepository,
    private val csvExportUtil: CsvExportUtil
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
                        onSuccess = { "Exported to Downloads: $it" },
                        onFailure = { "Export failed: ${it.message}" }
                    )
                )
            }
        }
    }
    
    fun clearExportResult() {
        _state.update { it.copy(exportResult = null) }
    }
}

