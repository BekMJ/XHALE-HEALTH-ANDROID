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
    val points: List<Pair<Long, Pair<Double?, Double?>>> = emptyList(),
)

@HiltViewModel
class BreathViewModel @Inject constructor(private val ble: BleRepository) : ViewModel() {
    private val _state = MutableStateFlow(BreathUiState())
    val state: StateFlow<BreathUiState> = _state

    private var tickerJob: Job? = null
    private var collectJob: Job? = null

    fun startSampling(durationSec: Int) {
        _state.update { it.copy(isSampling = true, remainingSec = durationSec, points = emptyList()) }
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            ble.liveData.collectLatest { live ->
                val now = System.currentTimeMillis()
                _state.update { s ->
                    s.copy(
                        coPpm = live.coPpm,
                        temperatureC = live.temperatureC,
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
}

