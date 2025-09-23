package com.xhale.health.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xhale.health.core.ble.BleRepository
import com.xhale.health.core.ble.ConnectionState
import com.xhale.health.core.ble.DiscoveredDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isScanning: Boolean = false,
    val bluetoothAvailable: Boolean = true,
    val devices: List<DiscoveredDevice> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedDeviceId: String? = null,
    val coPpm: Double? = null,
    val temperatureC: Double? = null,
    val batteryPercent: Int? = null,
    val serialNumber: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(private val ble: BleRepository) : ViewModel() {
    private fun <T1, T2, T3, T4, T5, T6, R> combine6(
        f1: Flow<T1>, f2: Flow<T2>, f3: Flow<T3>, f4: Flow<T4>, f5: Flow<T5>, f6: Flow<T6>,
        transform: suspend (T1, T2, T3, T4, T5, T6) -> R
    ): Flow<R> = combine(arrayOf(f1, f2, f3, f4, f5, f6).toList()) { arr ->
        @Suppress("UNCHECKED_CAST")
        transform(
            arr[0] as T1,
            arr[1] as T2,
            arr[2] as T3,
            arr[3] as T4,
            arr[4] as T5,
            arr[5] as T6
        )
    }

    val state: StateFlow<HomeUiState> = combine6(
        ble.isScanning,
        ble.bluetoothAvailable,
        ble.discoveredDevices,
        ble.connectionState,
        ble.connectedDevice,
        ble.liveData
    ) { scanning, bt, devices, conn, connected, live ->
        HomeUiState(
            isScanning = scanning,
            bluetoothAvailable = bt,
            devices = devices,
            connectionState = conn,
            connectedDeviceId = connected?.deviceId,
            coPpm = live.coPpm,
            temperatureC = live.temperatureC,
            batteryPercent = live.batteryPercent,
            serialNumber = live.serialNumber
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onScanToggle() {
        viewModelScope.launch {
            if (state.value.isScanning) ble.stopScan() else ble.startScan()
        }
    }

    fun onConnect(deviceId: String) {
        viewModelScope.launch { ble.connect(deviceId) }
    }

    fun onDisconnect() {
        viewModelScope.launch { ble.disconnect() }
    }
}

