package com.happyhealth.testapp

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.happyhealth.bleplatform.api.*
import com.happyhealth.bleplatform.internal.command.CommandId
import com.happyhealth.bleplatform.internal.command.ResponseParser
import com.happyhealth.bleplatform.internal.model.DeviceInfoData
import com.happyhealth.bleplatform.internal.model.DeviceStatusData
import com.happyhealth.bleplatform.internal.model.DaqConfigData
import com.happyhealth.bleplatform.internal.model.ScannedDeviceInfo
import com.happyhealth.bleplatform.internal.shim.AndroidBleShim
import com.happyhealth.bleplatform.internal.shim.AndroidTimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val extendedStatus: ResponseParser.ExtendedDeviceStatus? = null,
    val daqConfig: DaqConfigData? = null,
    val fingerDetectionOn: Boolean? = true,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadTotal: Int = 0,
    val downloadTransport: String = "",
    val totalFramesDownloaded: Int = 0,
    val batchStartMs: Long = 0L,
    val commandStatus: String? = null,
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

    // Auto-clear timers for command status
    private val statusClearJobs = mutableMapOf<Int, Job>()

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
        clearCommandStatus(connId)
        api.identify(connId)
    }

    fun getDeviceStatus(connId: ConnectionId) {
        clearCommandStatus(connId)
        api.getDeviceStatus(connId)
        api.getExtendedDeviceStatus(connId)
    }

    fun getDaqConfig(connId: ConnectionId) {
        clearCommandStatus(connId)
        api.getDaqConfig(connId)
        api.getDeviceStatus(connId) // refresh finger detection state
    }

    fun startDaq(connId: ConnectionId) {
        clearCommandStatus(connId)
        api.startDaq(connId)
    }

    fun stopDaq(connId: ConnectionId) {
        clearCommandStatus(connId)
        api.stopDaq(connId)
    }

    fun toggleFingerDetection(connId: ConnectionId) {
        clearCommandStatus(connId)
        val ring = _connectedRings.value[connId.value] ?: return
        val currentlyOn = ring.fingerDetectionOn ?: false
        api.setFingerDetection(connId, !currentlyOn)
        updateRing(connId) { it.copy(fingerDetectionOn = !currentlyOn) }
    }

    fun startDownload(connId: ConnectionId) {
        api.startDownload(connId)
    }

    fun stopDownload(connId: ConnectionId) {
        api.stopDownload(connId)
    }

    private fun handleEvent(event: HpyEvent) {
        when (event) {
            is HpyEvent.StateChanged -> {
                updateRing(event.connId) {
                    it.copy(
                        state = event.state,
                        isDownloading = event.state == HpyConnectionState.DOWNLOADING,
                        batchStartMs = if (event.state == HpyConnectionState.DOWNLOADING)
                            System.currentTimeMillis() else 0L,
                    )
                }
                addLog(event.connId, "State -> ${event.state}")
            }
            is HpyEvent.DeviceInfo -> {
                updateRing(event.connId) {
                    it.copy(deviceInfo = event.info)
                }
                addLog(event.connId, "DeviceInfo: serial=${event.info.serialNumber}, fw=${event.info.fwVersion}, model=${event.info.modelNumber}")
            }
            is HpyEvent.DeviceStatus -> {
                setCommandStatus(event.connId, "(${cmdHex(CommandId.GET_DEVICE_STATUS)}) Success")
                updateRing(event.connId) {
                    it.copy(
                        lastStatus = event.status,
                        fingerDetectionOn = !event.status.needsSetFingerDetection,
                    )
                }
                val s = event.status
                addLog(event.connId, "DevStatus: ${s.phyString}, DAQ=${s.daqString}, SOC=${s.soc}%, unsynced=${s.unsyncedFrames}, sync=${s.syncString}")
            }
            is HpyEvent.ExtendedDeviceStatus -> {
                setCommandStatus(event.connId, "(${cmdHex(CommandId.GET_EXTENDED_DEVICE_STATUS)}) Success")
                updateRing(event.connId) { it.copy(extendedStatus = event.extStatus) }
                val ext = event.extStatus
                addLog(event.connId, "ExtDevStatus: bp=${ext.bpStateString}, timeLeft=${ext.bpTimeLeftSec}s")
            }
            is HpyEvent.DaqConfig -> {
                setCommandStatus(event.connId, "(${cmdHex(CommandId.GET_DAQ_CONFIG)}) Success")
                updateRing(event.connId) { it.copy(daqConfig = event.config) }
                addLog(event.connId, "DaqConfig: mode=${event.config.modeString}, version=${event.config.version}")
            }
            is HpyEvent.CommandResult -> {
                setCommandStatus(event.connId, "(${cmdHex(event.commandId)}) Success")
                addLog(event.connId, "CMD 0x${event.commandId.toUByte().toString(16).uppercase()} response [${event.rawBytes.size}b]")
            }
            is HpyEvent.DebugMessage -> {
                addLog(event.connId, "DEBUG: ${event.message.decodeToString()}")
            }
            is HpyEvent.Error -> {
                val status = if (event.code == HpyErrorCode.COMMAND_TIMEOUT) "Timeout" else "Error"
                setCommandStatus(event.connId, status)
                addLog(event.connId, "ERROR [${event.code}]: ${event.message}")
            }
            is HpyEvent.Log -> {
                addLog(event.connId, event.message)
            }
            is HpyEvent.DownloadBatch -> {
                val ring = _connectedRings.value[event.connId.value]
                val startMs = ring?.batchStartMs ?: 0L
                val elapsedMs = if (startMs > 0) System.currentTimeMillis() - startMs else 0L
                val throughput = if (elapsedMs > 0) {
                    val bytes = event.framesInBatch.toLong() * 4096
                    val kbPerSec = bytes * 1000.0 / elapsedMs / 1024.0
                    String.format("%.1f KB/s", kbPerSec)
                } else "N/A"
                updateRing(event.connId) {
                    it.copy(
                        totalFramesDownloaded = event.totalFramesDownloaded,
                        batchStartMs = System.currentTimeMillis(), // reset for next batch
                    )
                }
                addLog(event.connId, "DownloadBatch: ${event.framesInBatch} frames, total=${event.totalFramesDownloaded}, CRC=${event.crcValid}, $throughput")
            }
            is HpyEvent.DownloadProgress -> {
                updateRing(event.connId) {
                    it.copy(
                        downloadProgress = event.framesDownloaded,
                        downloadTotal = event.framesTotal,
                        downloadTransport = event.transport,
                    )
                }
                if (event.framesDownloaded % 8 == 0 || event.framesDownloaded == event.framesTotal) {
                    addLog(event.connId, "DownloadProgress: ${event.framesDownloaded}/${event.framesTotal} (${event.transport})")
                }
            }
            is HpyEvent.DownloadComplete -> {
                updateRing(event.connId) { it.copy(isDownloading = false) }
                addLog(event.connId, "DownloadComplete: ${event.totalFrames} frames")
            }
            is HpyEvent.DeviceDiscovered -> { /* Handled via discoveredDevices StateFlow */ }
            else -> { /* Future events */ }
        }
    }

    private fun cmdHex(cmd: Byte): String =
        cmd.toUByte().toString(16).uppercase().padStart(2, '0')

    private fun clearCommandStatus(connId: ConnectionId) {
        statusClearJobs[connId.value]?.cancel()
        statusClearJobs.remove(connId.value)
        updateRing(connId) { it.copy(commandStatus = null) }
    }

    private fun setCommandStatus(connId: ConnectionId, status: String) {
        updateRing(connId) { it.copy(commandStatus = status) }
        statusClearJobs[connId.value]?.cancel()
        statusClearJobs[connId.value] = viewModelScope.launch {
            delay(10_000)
            updateRing(connId) { it.copy(commandStatus = null) }
            statusClearJobs.remove(connId.value)
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
