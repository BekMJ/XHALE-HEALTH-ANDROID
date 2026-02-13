package com.xhale.health.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xhale.health.core.ble.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val sampleDurationSec: Int = 15,
    val firmwareBatteryPercent: Int? = null,
    val calculatedBatteryPercent: Int? = null,
    val batteryVoltage: Double? = null,
    val batteryCapacityMah: Double? = null,
    val rawBatteryAdc: Double? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val ble: BleRepository
) : ViewModel() {
    val state: StateFlow<SettingsState> = combine(
        repo.sampleDuration,
        ble.liveData,
        ble.baselinePreparation
    ) { duration, live, prep ->
        SettingsState(
            sampleDurationSec = duration,
            firmwareBatteryPercent = live.batteryPercent,
            calculatedBatteryPercent = prep.calculatedBatteryPercent,
            batteryVoltage = prep.batteryVoltage,
            batteryCapacityMah = prep.batteryCapacityMah,
            rawBatteryAdc = prep.rawBatteryAdc
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setSampleDuration(seconds: Int) {
        viewModelScope.launch { repo.setSampleDuration(seconds) }
    }
}


