package com.happyhealth.testapp

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.happyhealth.bleplatform.api.*
import com.happyhealth.bleplatform.internal.model.DeviceInfoData
import com.happyhealth.bleplatform.internal.model.DeviceStatusData
import com.happyhealth.bleplatform.internal.model.DaqConfigData
import com.happyhealth.bleplatform.internal.model.ScannedDeviceInfo
import com.happyhealth.bleplatform.internal.shim.AndroidBleShim
import com.happyhealth.bleplatform.internal.shim.AndroidTimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ConnectedRingInfo(
    val connId: ConnectionId,
    val name: String,
    val address: String,
    val state: HpyConnectionState,
    val deviceInfo: DeviceInfoData? = null,
    val lastStatus: DeviceStatusData? = null,
    val daqConfig: DaqConfigData? = null,
)

data class LogEntry(
    val id: Long = logIdCounter++,
    val connId: ConnectionId,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        private var logIdCounter = 0L
    }
}

class TestAppViewModel(application: Application) : AndroidViewModel(application) {

    private val shim = AndroidBleShim(application)
    private val api: HappyPlatformApi

    private val _connectedRings = MutableStateFlow<Map<Int, ConnectedRingInfo>>(emptyMap())
    val connectedRings: StateFlow<Map<Int, ConnectedRingInfo>> = _connectedRings

    private val _eventLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val eventLog: StateFlow<List<LogEntry>> = _eventLog

    // Per-connection event logs
    private val _connectionLogs = MutableStateFlow<Map<Int, List<LogEntry>>>(emptyMap())
    val connectionLogs: StateFlow<Map<Int, List<LogEntry>>> = _connectionLogs

    val discoveredDevices: StateFlow<List<ScannedDeviceInfo>>
    val isScanning: StateFlow<Boolean>

    init {
        api = createHappyPlatformApi(shim, AndroidTimeSource())
        shim.callback = api.shimCallback

        discoveredDevices = api.discoveredDevices
        isScanning = api.isScanning

        viewModelScope.launch {
            api.events.collect { event -> handleEvent(event) }
        }
    }

    fun toggleScan() {
        if (isScanning.value) {
            api.scanStop()
        } else {
            api.scanStart()
        }
    }

    fun connect(device: ScannedDeviceInfo) {
        api.scanStop()
        val connId = api.connect(device.deviceHandle)
        if (connId != ConnectionId.INVALID) {
            _connectedRings.value = _connectedRings.value + (connId.value to ConnectedRingInfo(
                connId = connId,
                name = device.name,
                address = device.address,
                state = HpyConnectionState.CONNECTING,
            ))
        }
    }

    fun disconnect(connId: ConnectionId) {
        api.disconnect(connId)
        _connectedRings.value = _connectedRings.value - connId.value
    }

    fun identify(connId: ConnectionId) {
        api.identify(connId)
    }

    fun getDeviceStatus(connId: ConnectionId) {
        api.getDeviceStatus(connId)
    }

    fun getDaqConfig(connId: ConnectionId) {
        api.getDaqConfig(connId)
    }

    fun startDaq(connId: ConnectionId) {
        api.startDaq(connId)
    }

    fun stopDaq(connId: ConnectionId) {
        api.stopDaq(connId)
    }

    private fun handleEvent(event: HpyEvent) {
        when (event) {
            is HpyEvent.StateChanged -> {
                updateRing(event.connId) { it.copy(state = event.state) }
                addLog(event.connId, "State -> ${event.state}")
            }
            is HpyEvent.DeviceInfo -> {
                updateRing(event.connId) {
                    it.copy(
                        deviceInfo = event.info,
                        name = event.info.serialNumber.ifEmpty { it.name },
                    )
                }
                addLog(event.connId, "DeviceInfo: serial=${event.info.serialNumber}, fw=${event.info.fwVersion}, model=${event.info.modelNumber}")
            }
            is HpyEvent.DeviceStatus -> {
                updateRing(event.connId) { it.copy(lastStatus = event.status) }
                val s = event.status
                addLog(event.connId, "DevStatus: ${s.phyString}, DAQ=${s.daqString}, SOC=${s.soc}%, unsynced=${s.unsyncedFrames}, sync=${s.syncString}")
            }
            is HpyEvent.DaqConfig -> {
                updateRing(event.connId) { it.copy(daqConfig = event.config) }
                addLog(event.connId, "DaqConfig: mode=${event.config.modeString}, version=${event.config.version}")
            }
            is HpyEvent.CommandResult -> {
                addLog(event.connId, "CMD 0x${event.commandId.toUByte().toString(16).uppercase()} response [${event.rawBytes.size}b]")
            }
            is HpyEvent.DebugMessage -> {
                addLog(event.connId, "DEBUG: ${event.message.decodeToString()}")
            }
            is HpyEvent.Error -> {
                addLog(event.connId, "ERROR [${event.code}]: ${event.message}")
            }
            is HpyEvent.Log -> {
                addLog(event.connId, event.message)
            }
            is HpyEvent.DeviceDiscovered -> { /* Handled via discoveredDevices StateFlow */ }
            else -> { /* Future events */ }
        }
    }

    private fun updateRing(connId: ConnectionId, transform: (ConnectedRingInfo) -> ConnectedRingInfo) {
        val current = _connectedRings.value
        val existing = current[connId.value] ?: return
        _connectedRings.value = current + (connId.value to transform(existing))
    }

    private fun addLog(connId: ConnectionId, message: String) {
        val entry = LogEntry(connId = connId, message = message)

        // Global log
        _eventLog.value = (_eventLog.value + entry).takeLast(500)

        // Per-connection log
        if (connId.value >= 0) {
            val logs = _connectionLogs.value.toMutableMap()
            val connLogs = (logs[connId.value] ?: emptyList()) + entry
            logs[connId.value] = connLogs.takeLast(200)
            _connectionLogs.value = logs
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.destroy()
    }
}
