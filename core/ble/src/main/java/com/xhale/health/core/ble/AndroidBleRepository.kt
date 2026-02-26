package com.xhale.health.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class AndroidBleRepository(private val context: Context) : BleRepository {
    private val TAG = "AndroidBleRepo"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val warmupDelaySeconds = 20
    private val baselineCaptureDelaySeconds = 7
    private val warmupVoltageOffset = 4.67
    private val warmupVoltageScale = 150.30
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var connected: BluetoothDevice? = null
    private val devicesById = mutableMapOf<String, BluetoothDevice>()
    private val notificationQueue: ArrayDeque<Pair<java.util.UUID, java.util.UUID>> = ArrayDeque()
    private val lastUpdateMs = mutableMapOf<java.util.UUID, Long>()
    private val notifyRecoveryAttempts = mutableMapOf<java.util.UUID, Int>()
    private val maxNotifyRecoveryAttempts = 2
    private var warmupJob: Job? = null
    private var baselineCaptureJob: Job? = null
    private var sensorPollingJob: Job? = null
    @Volatile private var sensorStreamingStarted = false
    @Volatile private var servicesDiscovered = false
    @Volatile private var discoveryAttempts = 0

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

    private val _liveData = MutableStateFlow(LiveSensorData(null, null, null, null, null, null, null))
    override val liveData: StateFlow<LiveSensorData> = _liveData.asStateFlow()
    private val _baselinePreparation = MutableStateFlow(BaselinePreparationState())
    override val baselinePreparation: StateFlow<BaselinePreparationState> = _baselinePreparation.asStateFlow()

    private val notifyDescriptorUuid = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    // CO: firmware sends a 16-bit big-endian integer (raw ADC).

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    override suspend fun startScan() {
        if (adapter?.isEnabled != true) {
            _bluetoothAvailable.value = false
            return
        }
        _bluetoothAvailable.value = true
        _isScanning.value = true
        scanner = adapter?.bluetoothLeScanner
        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(filters, settings, scanCallback)
        // Stop after 10s
        scope.launch { delay(10_000); stopScan() }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopScan() {
        _isScanning.value = false
        scanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val advertisedName = result.scanRecord?.deviceName
            val safeName = advertisedName ?: run {
                if (hasConnectPermission()) device.name else null
            }
            val hasEnvService = result.scanRecord?.serviceUuids?.any { it.uuid == BleUuids.ENVIRONMENTAL_SENSING_SERVICE } == true
            if (safeName == null && !hasEnvService) return
            if (safeName != null && !safeName.startsWith("XHale")) return

            val mac = if (hasConnectPermission()) device.address else null
            val id = mac ?: safeName ?: "unknown-${System.identityHashCode(device)}"
            devicesById[id] = device
            val entry = DiscoveredDevice(deviceId = id, name = safeName, macAddress = mac, rssi = result.rssi)
            _discoveredDevices.update { list ->
                val existing = list.indexOfFirst { it.deviceId == id }
                if (existing >= 0) list.toMutableList().also { it[existing] = entry } else list + entry
            }

            // Parse manufacturer data for serial/firmware fallback
            result.scanRecord?.manufacturerSpecificData?.let { msd ->
                for (idx in 0 until msd.size()) {
                    val bytes = msd.valueAt(idx) ?: continue
                    if (bytes.size >= 8) {
                        val serialBytes = bytes.sliceArray(0 until 8)
                        val serialHex = serialBytes.joinToString("") { b -> "%02X".format(b) }
                        var fw: String? = null
                        if (bytes.size > 8) {
                            val tail = bytes.sliceArray(8 until bytes.size)
                            fw = runCatching { String(tail, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } }.getOrNull()
                        }
                        _liveData.update { current ->
                            current.copy(
                                serialNumber = current.serialNumber ?: serialHex,
                                firmwareRev = current.firmwareRev ?: fw
                            )
                        }
                        break
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceId: String) {
        if (!hasConnectPermission()) return
        val device = devicesById[deviceId] ?: run {
            val looksLikeMac = deviceId.contains(":") && deviceId.length >= 17
            if (looksLikeMac) adapter?.getRemoteDevice(deviceId) else null
        } ?: return
        _connectionState.value = ConnectionState.CONNECTING
        connected = device
        warmupJob?.cancel()
        baselineCaptureJob?.cancel()
        sensorPollingJob?.cancel()
        sensorStreamingStarted = false
        _baselinePreparation.value = BaselinePreparationState()
        _liveData.value = LiveSensorData(
            coPpm = null,
            temperatureC = null,
            batteryPercent = null,
            serialNumber = null,
            firmwareRev = null,
            coRaw = null
        )
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connected = null
        notifyRecoveryAttempts.clear()
        warmupJob?.cancel()
        baselineCaptureJob?.cancel()
        sensorPollingJob?.cancel()
        sensorStreamingStarted = false
        _baselinePreparation.value = BaselinePreparationState()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _liveData.value = LiveSensorData(
            coPpm = null,
            temperatureC = null,
            batteryPercent = null,
            serialNumber = null,
            firmwareRev = null,
            coRaw = null
        )
    }

    override suspend fun writeCommandStartSampling() { /* not required */ }
    override suspend fun writeCommandStopSampling() { /* not required */ }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.CONNECTED
                _connectedDevice.value = DiscoveredDevice(
                    deviceId = (if (hasConnectPermission()) gatt.device.address else null)
                        ?: (if (hasConnectPermission()) gatt.device.name else null)
                        ?: "unknown",
                    name = if (hasConnectPermission()) gatt.device.name else null,
                    macAddress = if (hasConnectPermission()) gatt.device.address else null,
                    rssi = 0
                )
                servicesDiscovered = false
                discoveryAttempts = 0
                startWarmupLifecycle()
                if (Build.VERSION.SDK_INT >= 21) {
                    @Suppress("MissingPermission")
                    run { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                }
                gatt.requestMtu(185)
                // Fallback in case onMtuChanged is delayed or not called
                scope.launch {
                    delay(600)
                    if (!servicesDiscovered) {
                        Log.d(TAG, "MTU callback not received yet; attempting discoverServices() fallback…")
                        @Suppress("MissingPermission")
                        run { gatt.discoverServices() }
                    }
                }
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                warmupJob?.cancel()
                baselineCaptureJob?.cancel()
                sensorPollingJob?.cancel()
                sensorStreamingStarted = false
                _baselinePreparation.value = BaselinePreparationState()
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedDevice.value = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status; starting discoverServices()")
            @Suppress("MissingPermission")
            run { gatt.discoverServices() }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS && discoveryAttempts < 2) {
                discoveryAttempts++
                scope.launch {
                    delay(300)
                    Log.w(TAG, "Retrying discoverServices() attempt=$discoveryAttempts")
                    @Suppress("MissingPermission")
                    run { gatt.discoverServices() }
                }
                return
            }
            servicesDiscovered = true
            // Enable BAS first, then start sensor streaming immediately.
            notifyRecoveryAttempts.clear()
            notificationQueue.clear()
            enqueueNotifyIfPresent(gatt, BleUuids.BATTERY_LEVEL_CHAR)
            Log.d(TAG, "Services discovered. Enabling notify for ${notificationQueue.size} chars…")
            // Small delay before starting CCCD writes improves reliability on some devices
            scope.launch {
                delay(300)
                processNextNotification(gatt)
            }
            // Read static info (try standard services, else any service containing the char)
            readCharacteristicAnywhere(gatt, BleUuids.SERIAL_NUMBER_CHAR)
            readCharacteristicAnywhere(gatt, BleUuids.FIRMWARE_REV_CHAR)
            scope.launch {
                // Initial BAS read is delayed to avoid stale early values.
                delay(10_000)
                readCharacteristicAnywhere(gatt, BleUuids.BATTERY_LEVEL_CHAR)
            }
            // Start CO/temp notifications as soon as connection is ready.
            startSensorStreaming()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handleCharacteristic(characteristic)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Proceed to next CCCD write
            val ch = descriptor.characteristic
            Log.d(TAG, "CCCD written for ${ch.uuid}, status=$status. Reading once to prime UI…")
            if (hasConnectPermission()) gatt.readCharacteristic(ch)
            val targetUuid = ch.uuid
            if (targetUuid == BleUuids.BATTERY_LEVEL_CHAR) {
                // BAS often doesn't push notifications; rely on the delayed read path instead.
                processNextNotification(gatt)
                return
            }

            // Fallback: if no updates in 5s, try re-enable + read again (bounded retries)
            val serviceUuid = ch.service?.uuid ?: BleUuids.ENVIRONMENTAL_SENSING_SERVICE
            scope.launch {
                delay(5000)
                val last = lastUpdateMs[targetUuid] ?: 0L
                if (System.currentTimeMillis() - last >= 5000) {
                    val attempts = notifyRecoveryAttempts[targetUuid] ?: 0
                    if (attempts >= maxNotifyRecoveryAttempts) return@launch
                    notifyRecoveryAttempts[targetUuid] = attempts + 1
                    Log.w(
                        TAG,
                        "No updates for $targetUuid after 5s. Re-enabling notify and re-reading (attempt ${attempts + 1}/$maxNotifyRecoveryAttempts)…"
                    )
                    enableNotifications(gatt, serviceUuid, targetUuid)
                    delay(150)
                    if (hasConnectPermission()) gatt.readCharacteristic(ch)
                }
            }
            processNextNotification(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, serviceUuid: java.util.UUID, charUuid: java.util.UUID): Boolean {
        val ch = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        val supportsNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val supportsIndicate = (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        if (!supportsNotify && !supportsIndicate) return false
        gatt.setCharacteristicNotification(ch, true)
        val desc = ch.getDescriptor(notifyDescriptorUuid) ?: return false
        val value = if (supportsIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(desc, value)
        } else {
            @Suppress("DEPRECATION")
            run {
                desc.value = value
                gatt.writeDescriptor(desc)
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun processNextNotification(gatt: BluetoothGatt) {
        val next = notificationQueue.removeFirstOrNull() ?: return
        scope.launch {
            delay(150)
            val started = enableNotifications(gatt, next.first, next.second)
            if (!started) {
                // Move to next if the characteristic has no CCCD/notify support.
                processNextNotification(gatt)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readIfPresent(gatt: BluetoothGatt, serviceUuid: java.util.UUID, charUuid: java.util.UUID) {
        if (!hasConnectPermission()) return
        gatt.getService(serviceUuid)?.getCharacteristic(charUuid)?.let { gatt.readCharacteristic(it) }
    }

    private fun readCharacteristicAnywhere(gatt: BluetoothGatt, charUuid: UUID) {
        if (!hasConnectPermission()) return
        val svc = findServiceUuidForCharacteristic(gatt, charUuid) ?: return
        gatt.getService(svc)?.getCharacteristic(charUuid)?.let { gatt.readCharacteristic(it) }
    }

    private fun enqueueNotifyIfPresent(gatt: BluetoothGatt, charUuid: UUID) {
        val svcUuid = findServiceUuidForCharacteristic(gatt, charUuid) ?: return
        notificationQueue.addLast(svcUuid to charUuid)
    }

    private fun findServiceUuidForCharacteristic(gatt: BluetoothGatt, charUuid: UUID): UUID? {
        val services = try { gatt.services } catch (t: Throwable) { null } ?: return null
        for (svc in services) {
            if (svc.getCharacteristic(charUuid) != null) return svc.uuid
        }
        return null
    }

    private fun handleCharacteristic(ch: BluetoothGattCharacteristic) {
        when (ch.uuid) {
            BleUuids.TEMPERATURE_CHAR -> {
                // int16 LE centi-°C
                val data = ch.getValue() ?: return
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val raw = if (bb.remaining() >= 2) bb.short.toInt() else return
                val tempC = raw / 100.0
                lastUpdateMs[ch.uuid] = System.currentTimeMillis()
                notifyRecoveryAttempts[ch.uuid] = 0
                _liveData.update {
                    it.copy(
                        temperatureC = tempC,
                        lastTemperatureUpdateMs = System.currentTimeMillis()
                    )
                }
            }
            BleUuids.CO_CHAR -> {
                // uint16 BE raw ADC; keep raw and mirrored display value.
                val data = ch.getValue() ?: return
                val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                val raw = if (bb.remaining() >= 2) bb.short.toInt() and 0xFFFF else return
                val rawValue = raw.toDouble()
                lastUpdateMs[ch.uuid] = System.currentTimeMillis()
                notifyRecoveryAttempts[ch.uuid] = 0
                _liveData.update { it.copy(coPpm = rawValue, coRaw = rawValue) }
            }
            BleUuids.SERIAL_NUMBER_CHAR -> {
                val data = ch.getValue() ?: return
                val sn = runCatching { String(data, Charsets.UTF_8).trim() }.getOrNull()
                _liveData.update { it.copy(serialNumber = sn) }
            }
            BleUuids.FIRMWARE_REV_CHAR -> {
                val data = ch.getValue() ?: return
                val fw = runCatching { String(data, Charsets.UTF_8).trim() }.getOrNull()
                _liveData.update { it.copy(firmwareRev = fw) }
            }
            BleUuids.BATTERY_LEVEL_CHAR -> {
                val data = ch.getValue()
                val level = data?.firstOrNull()?.toInt()?.coerceIn(0, 100)
                if (level != null && level <= 10) {
                    Log.w(TAG, "Low battery level reported by BAS: $level%")
                }
                _liveData.update { it.copy(batteryPercent = level) }
            }
        }
    }

    private fun startWarmupLifecycle() {
        warmupJob?.cancel()
        baselineCaptureJob?.cancel()
        warmupJob = scope.launch {
            for (remaining in warmupDelaySeconds downTo 1) {
                _baselinePreparation.value = BaselinePreparationState(
                    isPreparingBaseline = true,
                    preparationSecondsLeft = remaining,
                    isWarmupComplete = false,
                    baselineRawValue = _baselinePreparation.value.baselineRawValue,
                    baselineTemperatureC = _baselinePreparation.value.baselineTemperatureC,
                    rawBatteryAdc = _baselinePreparation.value.rawBatteryAdc,
                    batteryVoltage = _baselinePreparation.value.batteryVoltage,
                    batteryCapacityMah = _baselinePreparation.value.batteryCapacityMah,
                    calculatedBatteryPercent = _baselinePreparation.value.calculatedBatteryPercent
                )
                delay(1_000)
            }
            _baselinePreparation.update {
                it.copy(
                    isPreparingBaseline = false,
                    preparationSecondsLeft = 0,
                    isWarmupComplete = true
                )
            }

            // Keep warmup lifecycle for baseline timing.
            writeCommandStartSampling()

            baselineCaptureJob = launch {
                delay(baselineCaptureDelaySeconds * 1_000L)
                captureWarmupBaseline()
            }
        }
    }

    private fun captureWarmupBaseline() {
        val raw = _liveData.value.coRaw ?: _liveData.value.coPpm ?: return
        val temp = _liveData.value.temperatureC
        val voltage = (raw + warmupVoltageOffset) / warmupVoltageScale
        val percent = estimateCr2032SocPercent(voltage)
        val capacityMah = 220.0 * (percent / 100.0)
        _baselinePreparation.update {
            it.copy(
                baselineRawValue = raw,
                baselineTemperatureC = temp,
                rawBatteryAdc = raw,
                batteryVoltage = voltage,
                batteryCapacityMah = capacityMah,
                calculatedBatteryPercent = percent
            )
        }
    }

    private fun startSensorStreaming() {
        val activeGatt = gatt ?: return
        if (!servicesDiscovered) {
            Log.d(TAG, "Services not discovered yet; deferring sensor stream start")
            return
        }

        if (!sensorStreamingStarted) {
            sensorStreamingStarted = true
            notificationQueue.clear()
            enqueueNotifyIfPresent(activeGatt, BleUuids.CO_CHAR)
            enqueueNotifyIfPresent(activeGatt, BleUuids.TEMPERATURE_CHAR)
            Log.d(TAG, "Enabling sensor notifications for ${notificationQueue.size} chars…")
            scope.launch {
                delay(200)
                processNextNotification(activeGatt)
            }
        }

        // Prime initial values.
        scope.launch {
            delay(200)
            readCharacteristicAnywhere(activeGatt, BleUuids.CO_CHAR)
            readCharacteristicAnywhere(activeGatt, BleUuids.TEMPERATURE_CHAR)
        }

        // iOS parity: keep polling temperature as a safety fallback.
        sensorPollingJob?.cancel()
        sensorPollingJob = scope.launch {
            while (_connectionState.value == ConnectionState.CONNECTED) {
                val currentGatt = gatt ?: break
                readCharacteristicAnywhere(currentGatt, BleUuids.TEMPERATURE_CHAR)
                delay(1_000)
            }
        }
    }

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

