package com.happyhealth.testapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothDevice
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.FileProvider
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
import com.happyhealth.testapp.data.FwImageInfo
import com.happyhealth.testapp.data.FwImageReader
import com.happyhealth.testapp.data.FwImageStatus
import com.happyhealth.testapp.data.MemfaultClient
import com.happyhealth.testapp.data.MemfaultRelease
import kotlinx.coroutines.Dispatchers
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
    val downloadProgressOffset: Int = 0,
    val downloadRawProgress: Int = 0,
    val sessionDownloadProgress: Int = 0,
    val sessionDownloadTotal: Int = 0,
    val downloadTransport: String = "",
    val totalFramesDownloaded: Int = 0,
    val batchStartMs: Long = 0L,
    val commandStatus: String? = null,
    val downloadState: String? = null,
    val isFwUpdating: Boolean = false,
    val fwUpdateState: String? = null,
    val fwBlocksSent: Int = 0,
    val fwBlocksTotal: Int = 0,
    val fwStartMs: Long = 0L,
    val fwUploadDoneMs: Long = 0L,
    val isReconnecting: Boolean = false,
    val reconnectRetryCount: Int = 0,
    val ringSize: Int = 0,
    val ringColor: Int = 0,
    val syncFrameCount: UInt = 0u,
    val syncFrameReboots: UInt = 0u,
    val rssiWarningValue: Int? = null,
    val lastRssi: Int? = null,
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

    companion object {
        const val MIN_RSSI = -80
    }

    private val shim = AndroidBleShim(application)
    private val api: HappyPlatformApi

    private val _connectedRings = MutableStateFlow<Map<Int, ConnectedRingInfo>>(emptyMap())
    val connectedRings: StateFlow<Map<Int, ConnectedRingInfo>> = _connectedRings

    private val _eventLog = MutableStateFlow<List<LogEntry>>(emptyList())
    val eventLog: StateFlow<List<LogEntry>> = _eventLog

    // Per-connection event logs
    private val _connectionLogs = MutableStateFlow<Map<Int, List<LogEntry>>>(emptyMap())
    val connectionLogs: StateFlow<Map<Int, List<LogEntry>>> = _connectionLogs

    // RSSI pre-flight check
    private val pendingRssiAction = mutableMapOf<Int, String>()
    private val _rssiAlertConnId = MutableStateFlow<ConnectionId?>(null)
    val rssiAlertConnId: StateFlow<ConnectionId?> = _rssiAlertConnId
    private val _rssiAlertValue = MutableStateFlow(0)
    val rssiAlertValue: StateFlow<Int> = _rssiAlertValue

    // RSSI polling timers (10s interval per connection)
    private val rssiPollingJobs = mutableMapOf<Int, Job>()
    private val lastLoggedRssi = mutableMapOf<Int, Int>()

    // Auto-clear timers for command status
    private val statusClearJobs = mutableMapOf<Int, Job>()

    // Per-connection frame writers for HPY2 file output
    private val frameWriters = mutableMapOf<Int, FrameWriter>()

    // Wake lock to keep CPU alive during downloads (prevents Doze stalls)
    private var downloadWakeLock: PowerManager.WakeLock? = null

    private fun acquireDownloadWakeLock() {
        if (downloadWakeLock?.isHeld == true) return
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        downloadWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HappyTestApp:Download")
        downloadWakeLock?.acquire(60 * 60 * 1000L) // 60-min safety timeout
    }

    private fun releaseDownloadWakeLock() {
        downloadWakeLock?.let { if (it.isHeld) it.release() }
        downloadWakeLock = null
    }

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
        val connId = api.connect(device.deviceHandle)
        if (connId != ConnectionId.INVALID) {
            _connectedRings.value = _connectedRings.value + (connId.value to ConnectedRingInfo(
                connId = connId,
                name = device.name,
                address = device.address,
                state = HpyConnectionState.CONNECTING,
                ringSize = device.ringSize,
                ringColor = device.ringColor,
                lastRssi = device.rssi,
            ))
        }
    }

    fun disconnect(connId: ConnectionId) {
        stopRssiPolling(connId)
        lastLoggedRssi.remove(connId.value)
        frameWriters.remove(connId.value)?.destroy()
        api.disconnect(connId)
        _connectedRings.value = _connectedRings.value - connId.value
    }

    fun disconnectAll() {
        val rings = _connectedRings.value.keys.toList()
        for (id in rings) {
            disconnect(ConnectionId(id))
        }
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

    fun setDaqConfig(connId: ConnectionId, config: DaqConfigData, applyImmediately: Boolean) {
        clearCommandStatus(connId)
        api.setDaqConfig(connId, config, applyImmediately)
        // Auto-refresh after a short delay to confirm changes
        viewModelScope.launch {
            delay(500)
            api.getDaqConfig(connId)
        }
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

    fun getSyncFrame(connId: ConnectionId) {
        clearCommandStatus(connId)
        api.getSyncFrame(connId)
    }

    fun setSyncFrame(connId: ConnectionId, frameCount: UInt, reboots: UInt) {
        clearCommandStatus(connId)
        api.setSyncFrame(connId, frameCount, reboots)
    }

    fun assert(connId: ConnectionId) {
        clearCommandStatus(connId)
        api.assert(connId)
    }

    fun requestStartDownload(connId: ConnectionId) {
        updateRing(connId) { it.copy(rssiWarningValue = null) }
        pendingRssiAction[connId.value] = "download"
        api.readRssi(connId)
    }

    private fun proceedStartDownload(connId: ConnectionId) {
        val ring = _connectedRings.value[connId.value]
        val deviceId = ring?.name
        val writer = FrameWriter(getApplication())
        writer.ensureFileOpen(deviceId)
        frameWriters[connId.value] = writer
        updateRing(connId) {
            it.copy(downloadProgress = 0, downloadTotal = 0, downloadProgressOffset = 0, downloadRawProgress = 0)
        }
        addLog(connId, "HPY2 file: ${writer.filePath}")
        acquireDownloadWakeLock()
        api.startDownload(connId)
    }

    fun startDownload(connId: ConnectionId) {
        proceedStartDownload(connId)
    }

    fun stopDownload(connId: ConnectionId) {
        api.stopDownload(connId)
        val writer = frameWriters.remove(connId.value)
        if (writer != null) {
            addLog(connId, "HPY2 file closed: ${writer.totalFramesWritten} frames written")
            writer.destroy()
        }
        updateRing(connId) { it.copy(rssiWarningValue = null) }
        releaseDownloadWakeLock()
    }

    // ---- FW Update ----

    private val _fwImageInfo = MutableStateFlow<FwImageInfo?>(null)
    val fwImageInfo: StateFlow<FwImageInfo?> = _fwImageInfo
    private var fwImageBytes: ByteArray? = null

    fun loadFwImage(uri: Uri): String? {
        val reader = FwImageReader()
        val status = reader.readAndValidate(getApplication<Application>().contentResolver, uri)
        if (status != FwImageStatus.OK) return "Image validation failed: $status"
        fwImageBytes = reader.imageBytes
        _fwImageInfo.value = reader.imageInfo
        return null
    }

    fun clearFwImage() {
        fwImageBytes = null
        _fwImageInfo.value = null
    }

    fun requestStartFwUpdate(connId: ConnectionId) {
        pendingRssiAction[connId.value] = "fwUpdate"
        api.readRssi(connId)
    }

    private fun proceedStartFwUpdate(connId: ConnectionId) {
        val bytes = fwImageBytes ?: return
        acquireDownloadWakeLock()
        api.startFwUpdate(connId, bytes)
    }

    fun startFwUpdate(connId: ConnectionId) {
        proceedStartFwUpdate(connId)
    }

    fun dismissRssiAlert() {
        _rssiAlertConnId.value = null
    }

    fun cancelFwUpdate(connId: ConnectionId) {
        api.cancelFwUpdate(connId)
        releaseDownloadWakeLock()
        updateRing(connId) {
            it.copy(isFwUpdating = false, fwUpdateState = "Aborted/Recovering...", fwBlocksSent = 0, fwBlocksTotal = 0)
        }
    }

    // ---- Memfault FW Images ----

    private val memfaultClient = MemfaultClient()
    private val _memfaultReleases = MutableStateFlow<List<MemfaultRelease>>(emptyList())
    val memfaultReleases: StateFlow<List<MemfaultRelease>> = _memfaultReleases
    private val _memfaultLoading = MutableStateFlow(false)
    val memfaultLoading: StateFlow<Boolean> = _memfaultLoading
    private val _memfaultError = MutableStateFlow<String?>(null)
    val memfaultError: StateFlow<String?> = _memfaultError
    private val _memfaultHasMore = MutableStateFlow(true)
    val memfaultHasMore: StateFlow<Boolean> = _memfaultHasMore
    private var memfaultNextPage = 1
    private val _memfaultDownloading = MutableStateFlow(false)
    val memfaultDownloading: StateFlow<Boolean> = _memfaultDownloading
    private val _memfaultDownloadVersion = MutableStateFlow<String?>(null)
    val memfaultDownloadVersion: StateFlow<String?> = _memfaultDownloadVersion
    private val _memfaultDownloadProgress = MutableStateFlow(0f)
    val memfaultDownloadProgress: StateFlow<Float> = _memfaultDownloadProgress
    private val _memfaultDownloadError = MutableStateFlow<String?>(null)
    val memfaultDownloadError: StateFlow<String?> = _memfaultDownloadError
    private var memfaultDownloadJob: Job? = null

    /** Reset state and fetch page 1. Called when Memfault dialog opens. */
    fun fetchMemfaultReleases() {
        _memfaultReleases.value = emptyList()
        _memfaultError.value = null
        _memfaultHasMore.value = true
        memfaultNextPage = 1
        loadMoreMemfaultReleases()
    }

    /** Load next page (pagination). No-op if already loading or no more pages. */
    fun loadMoreMemfaultReleases() {
        if (_memfaultLoading.value || !_memfaultHasMore.value) return
        _memfaultLoading.value = true
        _memfaultError.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = memfaultClient.fetchReleases(memfaultNextPage)
                _memfaultReleases.value = _memfaultReleases.value + response.releases
                _memfaultHasMore.value = memfaultNextPage < response.paging.pageCount
                memfaultNextPage++
            } catch (e: Exception) {
                _memfaultError.value = e.message ?: "Failed to fetch releases"
            } finally {
                _memfaultLoading.value = false
            }
        }
    }

    /** Download a version's artifact, validate, and set fwImageBytes/fwImageInfo. */
    fun downloadMemfaultRelease(version: String, connId: ConnectionId) {
        _memfaultDownloading.value = true
        _memfaultDownloadVersion.value = version
        _memfaultDownloadProgress.value = 0f
        _memfaultDownloadError.value = null

        memfaultDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            try {
                addLog(connId, "Memfault: fetching artifact URL for $version...")
                val artifactUrl = memfaultClient.fetchArtifactUrl(version)
                val fetchMs = System.currentTimeMillis() - startMs
                addLog(connId, "Memfault: artifact URL fetched in ${fetchMs}ms")

                addLog(connId, "Memfault: downloading $version...")
                val dlStartMs = System.currentTimeMillis()
                val bytes = memfaultClient.downloadArtifact(artifactUrl) { bytesRead, totalBytes ->
                    if (totalBytes > 0) {
                        _memfaultDownloadProgress.value = bytesRead.toFloat() / totalBytes.toFloat()
                    } else {
                        // No Content-Length: use estimated size (~300KB typical FW image)
                        _memfaultDownloadProgress.value = (bytesRead.toFloat() / 300_000f).coerceAtMost(0.95f)
                    }
                }
                val dlMs = System.currentTimeMillis() - dlStartMs
                val totalMs = System.currentTimeMillis() - startMs
                addLog(connId, "Memfault: downloaded ${bytes.size} bytes in ${dlMs}ms (total: ${totalMs}ms)")

                addLog(connId, "Memfault: validating $version (${bytes.size} bytes)...")
                val reader = FwImageReader()
                val status = reader.readAndValidateBytes(bytes, "$version.img")
                if (status != FwImageStatus.OK) {
                    _memfaultDownloadError.value = "Image validation failed: $status"
                    addLog(connId, "Memfault: validation failed for $version: $status")
                    return@launch
                }

                fwImageBytes = reader.imageBytes
                _fwImageInfo.value = reader.imageInfo
                addLog(connId, "Memfault FW image: ${reader.imageInfo?.fileName}, " +
                    "version=${reader.imageInfo?.version}, ${reader.imageInfo?.fileSize} bytes")
            } catch (e: Exception) {
                val totalMs = System.currentTimeMillis() - startMs
                _memfaultDownloadError.value = e.message ?: "Download failed"
                addLog(connId, "Memfault: error after ${totalMs}ms downloading $version: ${e.message}")
            } finally {
                _memfaultDownloading.value = false
                _memfaultDownloadVersion.value = null
            }
        }
    }

    fun cancelMemfaultDownload() {
        memfaultDownloadJob?.cancel()
        memfaultDownloadJob = null
        _memfaultDownloading.value = false
        _memfaultDownloadVersion.value = null
    }

    fun listHpy2Files(deviceId: String? = null): List<java.io.File> {
        val baseDir = getApplication<Application>().getExternalFilesDir(null) ?: return emptyList()
        val folder = if (deviceId != null) {
            java.io.File(java.io.File(baseDir, "BLE_HPY2_DATA"), deviceId)
        } else {
            java.io.File(baseDir, "BLE_HPY2_DATA")
        }
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles { f -> f.extension == "hpy2" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun shareHpy2File(path: String): Intent? {
        val file = java.io.File(path)
        if (!file.exists()) return null

        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ---- Event Log Files ----

    fun saveEventLog(connId: ConnectionId): String? {
        val logs = _connectionLogs.value[connId.value] ?: return null
        if (logs.isEmpty()) return null

        val deviceId = _connectedRings.value[connId.value]?.name
        val baseDir = getApplication<Application>().getExternalFilesDir(null) ?: return null
        val folder = if (deviceId != null) {
            java.io.File(java.io.File(baseDir, "BLE_EVENT_LOGS"), deviceId)
        } else {
            java.io.File(baseDir, "BLE_EVENT_LOGS")
        }
        if (!folder.exists()) folder.mkdirs()

        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val fileTimestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val fileName = "event_log_${fileTimestamp}.txt"
        val file = java.io.File(folder, fileName)

        file.bufferedWriter().use { writer ->
            for (entry in logs) {
                val time = timeFormat.format(java.util.Date(entry.timestamp))
                writer.write("$time  ${entry.message}")
                writer.newLine()
            }
        }

        addLog(connId, "Event log saved: $fileName (${logs.size} entries)")
        return file.absolutePath
    }

    fun listEventLogFiles(deviceId: String? = null): List<java.io.File> {
        val baseDir = getApplication<Application>().getExternalFilesDir(null) ?: return emptyList()
        val folder = if (deviceId != null) {
            java.io.File(java.io.File(baseDir, "BLE_EVENT_LOGS"), deviceId)
        } else {
            java.io.File(baseDir, "BLE_EVENT_LOGS")
        }
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles { f -> f.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun shareEventLogFile(path: String): Intent? {
        val file = java.io.File(path)
        if (!file.exists()) return null

        val context = getApplication<Application>()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun handleEvent(event: HpyEvent) {
        when (event) {
            is HpyEvent.StateChanged -> {
                val wasDownloading = _connectedRings.value[event.connId.value]?.isDownloading == true
                val wasFwUpdating = _connectedRings.value[event.connId.value]?.isFwUpdating == true
                val dlState = when (event.state) {
                    HpyConnectionState.DOWNLOADING -> "Downloading"
                    HpyConnectionState.WAITING -> "Waiting"
                    else -> null
                }
                val isNowDownloading = event.state == HpyConnectionState.DOWNLOADING ||
                    event.state == HpyConnectionState.WAITING
                val reconnecting = event.state == HpyConnectionState.RECONNECTING ||
                    (event.state == HpyConnectionState.FW_UPDATE_REBOOTING && event.retryCount > 0)
                val clearRssiWarning = event.state == HpyConnectionState.DOWNLOADING
                updateRing(event.connId) {
                    it.copy(
                        state = event.state,
                        isDownloading = event.state == HpyConnectionState.DOWNLOADING ||
                            event.state == HpyConnectionState.WAITING,
                        isFwUpdating = event.state == HpyConnectionState.FW_UPDATING ||
                            event.state == HpyConnectionState.FW_UPDATE_REBOOTING,
                        batchStartMs = if (event.state == HpyConnectionState.DOWNLOADING)
                            System.currentTimeMillis() else it.batchStartMs,
                        downloadState = dlState,
                        fwUpdateState = if (event.state == HpyConnectionState.READY) null else it.fwUpdateState,
                        isReconnecting = reconnecting,
                        reconnectRetryCount = event.retryCount,
                        rssiWarningValue = if (clearRssiWarning) null else it.rssiWarningValue,
                    )
                }
                val isNowFwUpdating = event.state == HpyConnectionState.FW_UPDATING ||
                    event.state == HpyConnectionState.FW_UPDATE_REBOOTING
                if ((wasDownloading && !isNowDownloading) || (wasFwUpdating && !isNowFwUpdating)) {
                    releaseDownloadWakeLock()
                }
                if (event.state == HpyConnectionState.READY) {
                    startRssiPolling(event.connId)
                }
                val retryStr = if (event.retryCount > 0) " (retry ${event.retryCount}/64)" else ""
                addLog(event.connId, "State -> ${event.state}$retryStr")
                // Auto-remove ring after reconnection failure
                if (event.state == HpyConnectionState.DISCONNECTED) {
                    stopRssiPolling(event.connId)
                    viewModelScope.launch {
                        delay(2000)
                        _connectedRings.value = _connectedRings.value - event.connId.value
                    }
                }
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
                addLog(event.connId, "DevStatus: ${s.phyString}, DAQ=${s.daqString}, SOC=${s.soc}%, unsynced=${s.unsyncedFrames}, sync=${s.syncString}, notif=${s.notifSenderString}, CI=${s.bleCiValue}ms, inprog=${s.bleCiUpdateInProgress}")
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
            is HpyEvent.SyncFrame -> {
                setCommandStatus(event.connId, "(${cmdHex(CommandId.GET_SYNC_FRAME)}) Success")
                updateRing(event.connId) {
                    it.copy(syncFrameCount = event.frameCount, syncFrameReboots = event.reboots)
                }
                addLog(event.connId, "SyncFrame: boot${event.reboots}:frame${event.frameCount}")
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
                // Clear FW update UI on FW errors so "Finalizing" doesn't persist
                if (event.code == HpyErrorCode.FW_TRANSFER_FAIL) {
                    updateRing(event.connId) {
                        it.copy(isFwUpdating = false, fwUpdateState = null, fwBlocksSent = 0, fwBlocksTotal = 0)
                    }
                }
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
                val rssiStr = if (event.rssi != null) ", RSSI=${event.rssi}" else ""
                val retryStr = if (event.retryCount > 0) ", retries=${event.retryCount}" else ""
                val ncfStr = if (event.ncfCount > 0) ", NCF=${event.ncfCount}" else ""
                addLog(event.connId, "DownloadBatch: ${event.framesInBatch} frames, CRC=${event.crcValid}, $throughput, ${event.transport}$rssiStr$retryStr$ncfStr")
            }
            is HpyEvent.DownloadProgress -> {
                updateRing(event.connId) {
                    val offset = if (event.framesDownloaded < it.downloadRawProgress) {
                        it.downloadProgressOffset + it.downloadRawProgress
                    } else {
                        it.downloadProgressOffset
                    }
                    it.copy(
                        downloadProgress = offset + event.framesDownloaded,
                        downloadTotal = offset + event.framesTotal,
                        downloadProgressOffset = offset,
                        downloadRawProgress = event.framesDownloaded,
                        sessionDownloadProgress = event.sessionFramesDownloaded,
                        sessionDownloadTotal = event.sessionFramesTotal,
                        downloadTransport = event.transport,
                    )
                }
                if (event.sessionFramesDownloaded % 8 == 0 || event.sessionFramesDownloaded == event.sessionFramesTotal) {
                    addLog(event.connId, "DownloadProgress: ${event.sessionFramesDownloaded}/${event.sessionFramesTotal} (${event.transport})")
                }
            }
            is HpyEvent.DownloadFrame -> {
                frameWriters[event.connId.value]?.writeFrame(event.frameData)
            }
            is HpyEvent.DownloadInterrupted -> {
                val writer = frameWriters[event.connId.value]
                if (writer != null && event.framesToDiscard > 0) {
                    writer.discardFrames(event.framesToDiscard)
                    addLog(event.connId, "Download interrupted: discarding ${event.framesToDiscard} partial-batch frames")
                }
            }
            is HpyEvent.DownloadComplete -> {
                addLog(event.connId, "DownloadComplete: ${event.sessionFrames} frames")
                val cumulative = _connectedRings.value[event.connId.value]?.downloadProgress ?: 0
                addLog(event.connId, "Cumulative: $cumulative frames")
            }
            is HpyEvent.FwUpdateProgress -> {
                val fwState = when {
                    event.bytesWritten < event.totalBytes -> "Uploading"
                    else -> "Finalizing"
                }
                val now = System.currentTimeMillis()
                updateRing(event.connId) {
                    it.copy(
                        isFwUpdating = true,
                        fwUpdateState = fwState,
                        fwBlocksSent = event.bytesWritten / 240,
                        fwBlocksTotal = event.totalBytes / 240,
                        fwStartMs = if (it.fwStartMs == 0L) now else it.fwStartMs,
                        fwUploadDoneMs = if (fwState == "Finalizing" && it.fwUploadDoneMs == 0L) now else it.fwUploadDoneMs,
                    )
                }
                if (event.bytesWritten % (240 * 25) == 0 || event.bytesWritten == event.totalBytes) {
                    addLog(event.connId, "FW: ${event.bytesWritten / 240}/${event.totalBytes / 240} blocks")
                }
            }
            is HpyEvent.FwUpdateComplete -> {
                val ring = _connectedRings.value[event.connId.value]
                val now = System.currentTimeMillis()
                val uploadSec = if (ring != null && ring.fwStartMs > 0 && ring.fwUploadDoneMs > 0)
                    (ring.fwUploadDoneMs - ring.fwStartMs) / 1000 else 0L
                val totalSec = if (ring != null && ring.fwStartMs > 0)
                    (now - ring.fwStartMs) / 1000 else 0L
                updateRing(event.connId) {
                    it.copy(isFwUpdating = false, fwUpdateState = null, fwBlocksSent = 0, fwBlocksTotal = 0,
                        fwStartMs = 0L, fwUploadDoneMs = 0L)
                }
                addLog(event.connId, "FW update complete: ${event.newFwVersion} (upload: ${uploadSec}s, total: ${totalSec}s)")
            }
            is HpyEvent.MemfaultComplete -> {
                addLog(event.connId, "Memfault drain complete: ${event.chunksDownloaded} new chunks")
                val ring = _connectedRings.value[event.connId.value]
                val serial = ring?.deviceInfo?.serialNumber
                if (serial != null) {
                    uploadMemfaultChunks(event.connId, serial)
                }
            }
            is HpyEvent.RssiRead -> {
                updateRing(event.connId) { it.copy(lastRssi = event.rssi) }
                val action = pendingRssiAction.remove(event.connId.value)
                if (action == "download") {
                    addLog(event.connId, "RSSI: ${event.rssi} dBm")
                    lastLoggedRssi[event.connId.value] = event.rssi
                    if (event.rssi > MIN_RSSI) {
                        proceedStartDownload(event.connId)
                    } else {
                        _rssiAlertConnId.value = event.connId
                        _rssiAlertValue.value = event.rssi
                    }
                } else if (action == "fwUpdate") {
                    addLog(event.connId, "RSSI: ${event.rssi} dBm")
                    lastLoggedRssi[event.connId.value] = event.rssi
                    if (event.rssi > MIN_RSSI) {
                        proceedStartFwUpdate(event.connId)
                    } else {
                        _rssiAlertConnId.value = event.connId
                        _rssiAlertValue.value = event.rssi
                    }
                } else {
                    // From library auto-check or 10s poll
                    val ring = _connectedRings.value[event.connId.value]
                    if (ring?.state == HpyConnectionState.WAITING && event.rssi <= MIN_RSSI) {
                        updateRing(event.connId) { it.copy(rssiWarningValue = event.rssi) }
                    } else {
                        updateRing(event.connId) { it.copy(rssiWarningValue = null) }
                    }
                    val prev = lastLoggedRssi[event.connId.value]
                    val crossedBelow = prev != null && prev > MIN_RSSI && event.rssi <= MIN_RSSI
                    val crossedAbove = prev != null && prev <= MIN_RSSI && event.rssi > MIN_RSSI
                    val bigDelta = prev == null || kotlin.math.abs(event.rssi - prev) >= 10
                    if (crossedBelow || crossedAbove || bigDelta) {
                        val suffix = when {
                            crossedBelow -> " (below threshold $MIN_RSSI dBm)"
                            crossedAbove -> " (above threshold $MIN_RSSI dBm)"
                            else -> ""
                        }
                        addLog(event.connId, "RSSI: ${event.rssi} dBm$suffix")
                        lastLoggedRssi[event.connId.value] = event.rssi
                    }
                }
            }
            is HpyEvent.DeviceDiscovered -> { /* Handled via discoveredDevices StateFlow */ }
            else -> { /* Future events */ }
        }
    }

    private fun uploadMemfaultChunks(connId: ConnectionId, serial: String) {
        val chunks = api.getMemfaultChunks(connId)
        if (chunks.isEmpty()) return
        val totalBytes = chunks.sumOf { it.size }
        addLog(connId, "Memfault: uploading ${chunks.size} chunks ($totalBytes bytes)...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val code = memfaultClient.uploadChunks(serial, chunks)
                if (code == 202) {
                    api.markMemfaultChunksUploaded(connId)
                    addLog(connId, "Memfault: upload complete (HTTP 202)")
                } else {
                    addLog(connId, "Memfault: upload failed (HTTP $code), chunks retained")
                }
            } catch (e: Exception) {
                addLog(connId, "Memfault: upload failed: ${e.message}")
            }
        }
    }

    private fun startRssiPolling(connId: ConnectionId) {
        rssiPollingJobs[connId.value]?.cancel()
        rssiPollingJobs[connId.value] = viewModelScope.launch {
            while (true) {
                delay(10_000)
                api.readRssi(connId)
            }
        }
    }

    private fun stopRssiPolling(connId: ConnectionId) {
        rssiPollingJobs.remove(connId.value)?.cancel()
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
        _eventLog.value = (_eventLog.value + entry).takeLast(10000)

        // Per-connection log
        if (connId.value >= 0) {
            val logs = _connectionLogs.value.toMutableMap()
            val connLogs = (logs[connId.value] ?: emptyList()) + entry
            logs[connId.value] = connLogs.takeLast(1000)
            _connectionLogs.value = logs
        }
    }

    override fun onCleared() {
        super.onCleared()
        rssiPollingJobs.values.forEach { it.cancel() }
        rssiPollingJobs.clear()
        releaseDownloadWakeLock()
        frameWriters.values.forEach { it.destroy() }
        frameWriters.clear()
        api.destroy()
    }
}
