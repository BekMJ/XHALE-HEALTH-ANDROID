package com.xhale.health.core.ble

import java.util.UUID

data class DiscoveredDevice(
    val deviceId: String,
    val name: String?,
    val macAddress: String?,
    val rssi: Int
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class LiveSensorData(
    val coPpm: Double?,
    val temperatureC: Double?,
    val batteryPercent: Int?,
    val serialNumber: String?,
    val humidityPercent: Double? = null,
    val firmwareRev: String? = null
)

data class GattSpec(
    val serviceUuid: UUID,
    val coChar: UUID,
    val tempChar: UUID,
    val batteryChar: UUID?,
    val serialChar: UUID?,
    val commandWriteChar: UUID?
)

