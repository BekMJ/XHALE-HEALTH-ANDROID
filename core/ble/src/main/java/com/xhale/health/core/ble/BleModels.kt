package com.xhale.health.core.ble

import java.util.UUID

data class DiscoveredDevice(
    val deviceId: String,
    val name: String?,
    val macAddress: String?,
    val rssi: Int
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class BaselinePreparationState(
    val isPreparingBaseline: Boolean = false,
    val preparationSecondsLeft: Int = 0,
    val isWarmupComplete: Boolean = false,
    val baselineRawValue: Double? = null,
    val baselineTemperatureC: Double? = null,
    val rawBatteryAdc: Double? = null,
    val batteryVoltage: Double? = null,
    val batteryCapacityMah: Double? = null,
    val calculatedBatteryPercent: Int? = null
)

data class LiveSensorData(
    val coPpm: Double?,
    val temperatureC: Double?,
    val batteryPercent: Int?,
    val serialNumber: String?,
    val firmwareRev: String? = null,
    val coRaw: Double? = null,
    val lastTemperatureUpdateMs: Long? = null
)

data class GattSpec(
    val serviceUuid: UUID,
    val coChar: UUID,
    val tempChar: UUID,
    val batteryChar: UUID?,
    val serialChar: UUID?,
    val commandWriteChar: UUID?
)

