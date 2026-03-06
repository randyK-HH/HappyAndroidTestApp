package com.happyhealth.testapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.bleplatform.internal.command.ResponseParser
import com.happyhealth.bleplatform.internal.model.DaqConfigData
import com.happyhealth.bleplatform.internal.model.DeviceStatusData
import com.happyhealth.bleplatform.internal.model.FirmwareTier
import com.happyhealth.testapp.TestAppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(
    connId: ConnectionId,
    viewModel: TestAppViewModel,
    onBack: () -> Unit,
) {
    val connectedRings by viewModel.connectedRings.collectAsState()
    val ring = connectedRings[connId.value]

    if (ring == null) {
        // Ring disconnected — go back
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var showStatusDialog by remember { mutableStateOf(false) }
    var showDaqConfigDialog by remember { mutableStateOf(false) }
    var showDaqConfigureDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showSyncFrameDialog by remember { mutableStateOf(false) }
    var showAssertConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ring.name, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.disconnect(connId); onBack() }) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
    ) { paddingValues ->
        val pagerState = rememberPagerState(pageCount = { 2 })

        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = 0.2f,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) { page ->
        if (page == 1) {
            FullScreenEventLog(connId = connId, viewModel = viewModel)
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- State ----
            val stateColor = when (ring.state) {
                HpyConnectionState.READY -> MaterialTheme.colorScheme.primary
                HpyConnectionState.DOWNLOADING -> MaterialTheme.colorScheme.primary
                HpyConnectionState.WAITING -> MaterialTheme.colorScheme.tertiary
                HpyConnectionState.HANDSHAKING, HpyConnectionState.CONNECTING ->
                    MaterialTheme.colorScheme.tertiary
                HpyConnectionState.RECONNECTING -> MaterialTheme.colorScheme.error
                HpyConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val stateText = when {
                ring.state == HpyConnectionState.RECONNECTING && ring.reconnectRetryCount > 0 ->
                    "State: RECONNECTING (${ring.reconnectRetryCount}/64)"
                ring.state == HpyConnectionState.FW_UPDATE_REBOOTING && ring.reconnectRetryCount > 0 ->
                    "State: FW_UPDATE_REBOOTING (${ring.reconnectRetryCount}/64)"
                else -> "State: ${ring.state}"
            }
            Text(
                stateText,
                style = MaterialTheme.typography.titleMedium,
                color = stateColor,
                fontWeight = FontWeight.Bold,
            )

            // ---- Reconnection Banner ----
            if (ring.isReconnecting) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val bannerText = if (ring.state == HpyConnectionState.FW_UPDATE_REBOOTING) {
                            "Reconnecting after FW update..."
                        } else {
                            "Connection lost. Reconnecting..."
                        }
                        Text(
                            bannerText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Attempt ${ring.reconnectRetryCount} of 64",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        @Suppress("DEPRECATION")
                        LinearProgressIndicator(
                            progress = ring.reconnectRetryCount.toFloat() / 64f,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.error,
                            trackColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Device Info ----
            ring.deviceInfo?.let { info ->
                SectionHeader("Device Info")
                InfoRow("Serial Number", info.serialNumber)
                InfoRow("FW Version", info.fwVersion)
                InfoRow("SW Version", info.swVersion)
                InfoRow("Manufacturer", info.manufacturerName)
                InfoRow("Model", info.modelNumber)
                InfoRow("Firmware Tier", info.firmwareTier.name)
                InfoRow("L2CAP Download", if (info.supportsL2capDownload) "Supported" else "Not Available")
                if (ring.ringSize > 0) {
                    InfoRow("Ring Size", ring.ringSize.toString())
                }
                val colorName = when (ring.ringColor) {
                    1 -> "White"
                    2 -> "Black"
                    3 -> "Clay"
                    0 -> "Unknown"
                    else -> "Unknown(${ring.ringColor})"
                }
                InfoRow("Ring Color", colorName)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ---- Last Device Status ----
            ring.lastStatus?.let { status ->
                SectionHeader("Device Status")
                InfoRow("Physical", status.phyString)
                InfoRow("DAQ Mode", status.daqString)
                InfoRow("Battery", "${status.soc}% (${status.batteryVoltage}mV)")
                InfoRow("RSSI", ring.lastRssi?.let { "$it dBm" } ?: "—")
                InfoRow("Unsynced Frames", status.unsyncedFrames.toString())
                InfoRow("Sync Position", status.syncString)
                InfoRow("Clock Rate", status.clockRateString)
                InfoRow("Notif Sender", status.notifSenderString)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ---- DAQ Config ----
            ring.daqConfig?.let { config ->
                SectionHeader("DAQ Config")
                InfoRow("Mode", config.modeString)
                InfoRow("Version", config.version.toString())
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ---- Command Buttons ----
            val gridShape = RoundedCornerShape(4.dp)
            val gridRowHeight = 38.dp
            val gridGap = 2.dp
            val gridContentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            val isReady = ring.state == HpyConnectionState.READY || ring.state == HpyConnectionState.WAITING

            CommandSectionHeader("Commands", ring.commandStatus)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gridGap),
            ) {
                Button(
                    onClick = {
                        viewModel.getDeviceStatus(connId)
                        showStatusDialog = true
                    },
                    enabled = isReady,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) {
                    Text("Dev Status")
                }
                Button(
                    onClick = {
                        viewModel.getDaqConfig(connId)
                        showDaqConfigDialog = true
                    },
                    enabled = isReady,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) {
                    Text("DAQ Config")
                }
            }
            Spacer(modifier = Modifier.height(gridGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gridGap),
            ) {
                Button(
                    onClick = { viewModel.startDaq(connId) },
                    enabled = isReady,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) {
                    Text("Start DAQ")
                }
                Button(
                    onClick = { viewModel.stopDaq(connId) },
                    enabled = isReady,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) {
                    Text("Stop DAQ")
                }
            }
            Spacer(modifier = Modifier.height(gridGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gridGap),
            ) {
                Button(
                    onClick = { viewModel.identify(connId) },
                    enabled = isReady,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) {
                    Text("Identify")
                }
                val fingerLabel = when (ring.fingerDetectionOn) {
                    true -> "Finger Det: ON"
                    false -> "Finger Det: OFF"
                    null -> "Finger Det: ?"
                }
                Button(
                    onClick = { viewModel.toggleFingerDetection(connId) },
                    enabled = isReady,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) {
                    Text(fingerLabel)
                }
            }
            Spacer(modifier = Modifier.height(gridGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gridGap),
            ) {
                Button(
                    onClick = {
                        viewModel.getSyncFrame(connId)
                        showSyncFrameDialog = true
                    },
                    enabled = isReady,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) { Text("Sync Frame") }
                Button(
                    onClick = { showAssertConfirm = true },
                    enabled = isReady,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) { Text("Assert") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- FW Update ----
            FwUpdateSection(
                connId = connId,
                ring = ring,
                viewModel = viewModel,
                isReady = isReady,
                gridShape = gridShape,
                gridRowHeight = gridRowHeight,
                gridGap = gridGap,
                gridContentPadding = gridContentPadding,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Download ----
            val canStartDownload = isReady &&
                (ring.deviceInfo?.firmwareTier ?: FirmwareTier.TIER_0) >= FirmwareTier.TIER_1
            val isDownloading = ring.isDownloading
            val isActivelyDownloading = ring.state == HpyConnectionState.DOWNLOADING
            val isWaiting = ring.state == HpyConnectionState.WAITING

            val shareAction: (() -> Unit)? = if (!isDownloading) {
                { showShareDialog = true }
            } else null
            DownloadSectionHeader("Download", ring.downloadState, shareAction)
            if (isActivelyDownloading) {
                Spacer(modifier = Modifier.height(4.dp))
                if (ring.sessionDownloadTotal > 0) {
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = ring.sessionDownloadProgress.toFloat() / ring.sessionDownloadTotal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val sessionSizeKb = ring.sessionDownloadProgress * 4  // each frame = 4096 bytes = 4 kB
                    val cumulativeSizeKb = ring.downloadProgress * 4
                    val transportLabel = if (ring.downloadTransport.isNotEmpty()) "  (${ring.downloadTransport})" else ""
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${ring.sessionDownloadProgress} frames (${sessionSizeKb}kB)$transportLabel",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${ring.downloadProgress} frames (${cumulativeSizeKb}kB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Starting download...", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(4.dp))
            } else if (isWaiting) {
                Spacer(modifier = Modifier.height(4.dp))
                val sizeKb = ring.downloadProgress * 4
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row {
                        Text(
                            "Waiting for data...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        if (ring.rssiWarningValue != null) {
                            Text(
                                "  (RSSI Low: ${ring.rssiWarningValue} dBm)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Text(
                        "${ring.downloadProgress} frames (${sizeKb}kB)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (ring.totalFramesDownloaded > 0 && !isDownloading) {
                InfoRow("Last Download", "${ring.totalFramesDownloaded} frames")
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gridGap),
            ) {
                Button(
                    onClick = { viewModel.requestStartDownload(connId) },
                    enabled = canStartDownload && !isDownloading,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) {
                    Text("Start Download")
                }
                Button(
                    onClick = { viewModel.stopDownload(connId) },
                    enabled = isDownloading,
                    modifier = Modifier.weight(1f).height(gridRowHeight),
                    shape = gridShape,
                    contentPadding = gridContentPadding,
                ) {
                    Text("Stop Download")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Event Log ----
            EventLogSection(connId = connId, viewModel = viewModel)

            Spacer(modifier = Modifier.height(16.dp))
        } // Column
        } // else (page 0)
        } // HorizontalPager
    }

    // ---- Device Status Dialog ----
    if (showStatusDialog && ring.lastStatus != null) {
        DeviceStatusDialog(
            status = ring.lastStatus!!,
            extendedStatus = ring.extendedStatus,
            lastRssi = ring.lastRssi,
            onDismiss = { showStatusDialog = false },
        )
    }

    // ---- DAQ Config Dialog ----
    if (showDaqConfigDialog && ring.daqConfig != null) {
        DaqConfigDialog(
            config = ring.daqConfig!!,
            fingerDetectionOn = ring.fingerDetectionOn,
            onDismiss = { showDaqConfigDialog = false },
            onConfigure = {
                showDaqConfigDialog = false
                showDaqConfigureDialog = true
            },
        )
    }

    // ---- DAQ Configure Dialog ----
    if (showDaqConfigureDialog && ring.daqConfig != null) {
        DaqConfigureDialog(
            config = ring.daqConfig!!,
            fwVersion = ring.deviceInfo?.fwVersion,
            onUpdate = { config, applyImmediately ->
                viewModel.setDaqConfig(connId, config, applyImmediately)
                showDaqConfigureDialog = false
            },
            onDismiss = { showDaqConfigureDialog = false },
        )
    }

    // ---- Share HPY2 File Dialog ----
    if (showShareDialog) {
        ShareHpy2Dialog(
            viewModel = viewModel,
            deviceId = ring.name,
            onDismiss = { showShareDialog = false },
        )
    }

    // ---- Sync Frame Dialog ----
    if (showSyncFrameDialog) {
        SyncFrameDialog(
            frameCount = ring.syncFrameCount,
            reboots = ring.syncFrameReboots,
            onCommit = { fc, rb ->
                viewModel.setSyncFrame(connId, fc, rb)
                showSyncFrameDialog = false
            },
            onDismiss = { showSyncFrameDialog = false },
        )
    }

    // ---- Assert Confirmation Dialog ----
    if (showAssertConfirm) {
        AlertDialog(
            onDismissRequest = { showAssertConfirm = false },
            title = { Text("Confirm Assert") },
            text = { Text("This will trigger a firmware assert on the ring. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showAssertConfirm = false
                    viewModel.assert(connId)
                }) { Text("Assert") }
            },
            dismissButton = {
                TextButton(onClick = { showAssertConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // ---- RSSI Alert Dialog ----
    val rssiAlertConnId by viewModel.rssiAlertConnId.collectAsState()
    val rssiAlertValue by viewModel.rssiAlertValue.collectAsState()
    if (rssiAlertConnId != null && rssiAlertConnId == connId) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRssiAlert() },
            title = { Text("Signal Too Weak") },
            text = {
                Text("RSSI is $rssiAlertValue dBm. Move the ring closer to the phone and try again.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRssiAlert() }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun SyncFrameDialog(
    frameCount: UInt,
    reboots: UInt,
    onCommit: (UInt, UInt) -> Unit,
    onDismiss: () -> Unit,
) {
    var frameCountText by remember(frameCount) { mutableStateOf(frameCount.toString()) }
    var rebootsText by remember(reboots) { mutableStateOf(reboots.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync Frame") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rebootsText,
                    onValueChange = { rebootsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Reboots") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = frameCountText,
                    onValueChange = { frameCountText = it.filter { c -> c.isDigit() } },
                    label = { Text("Frame Count") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    frameCountText = "0"
                    rebootsText = "0"
                }) { Text("Clear") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fc = frameCountText.toUIntOrNull() ?: 0u
                val rb = rebootsText.toUIntOrNull() ?: 0u
                onCommit(fc, rb)
            }) { Text("Commit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeviceStatusDialog(
    status: DeviceStatusData,
    extendedStatus: ResponseParser.ExtendedDeviceStatus?,
    lastRssi: Int?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val small = MaterialTheme.typography.bodySmall
                Text("Physical: ${status.phyString}", style = small)
                Text("Charger: ${status.chargerStateString}", style = small)
                Text("Charging: ${status.chargingStateString}", style = small)
                Text("Charging Mode: ${status.chargingModeString}", style = small)
                Text("Charge Blocked: ${status.chargerBlockedReasonString}", style = small)
                Text("Charger Rev ID: ${status.chargerRevId}", style = small)
                Text("Charger Status: ${status.chargerStatusString}", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Battery: ${status.soc}% (${status.batteryVoltage} mV)", style = small)
                Text("RSSI: ${lastRssi?.let { "$it dBm" } ?: "—"}", style = small)
                Text("DAQ: ${status.daqString}", style = small)
                Text("Unsynced Frames: ${status.unsyncedFrames}", style = small)
                Text("Sync: ${status.syncString}", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Opportunistic: ${status.opportunisticSamplingStateString}", style = small)
                Text("Opportunistic Time: ${status.opportunisticStateTime}s", style = small)
                Text("Ship Mode: ${status.shipModeStatusString}", style = small)
                Text("Sleep State: ${status.sleepStateString}", style = small)
                Text("Pseudo Ring: ${status.pseudoRingOnOffString}", style = small)
                Text("Boot Handshake: ${status.bootHandshakeFlagString}", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("SendUTC Flags: 0x${status.sendUtcFlags.toString(16).uppercase().padStart(2, '0')}", style = small)
                Text("Notif Sender: ${status.notifSenderString}", style = small)
                Text("BLE CI: ${status.bleCiValue} ms (inprog=${status.bleCiUpdateInProgress})", style = small)
                Text("Clock Rate: ${status.clockRateString}", style = small)
                if (extendedStatus != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Extended", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("BP State: ${extendedStatus.bpStateString}", style = small)
                    Text("BP Time Left: ${extendedStatus.bpTimeLeftSec}s", style = small)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}

@Composable
private fun DaqConfigDialog(config: DaqConfigData, fingerDetectionOn: Boolean?, onDismiss: () -> Unit, onConfigure: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DAQ Configuration") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val small = MaterialTheme.typography.bodySmall
                Text("Version: ${config.version}", style = small)
                Text("Mode: ${config.modeString} (${config.mode})", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Ambient Light: ${if (config.ambientLightEn) "ON" else "OFF"}, ${config.ambientLightPeriodMs} ms", style = small)
                Text("Ambient Temp: ${if (config.ambientTempEn) "ON" else "OFF"}, ${config.ambientTempPeriodMs} ms", style = small)
                Text("Skin Temp: ${if (config.skinTempEn) "ON" else "OFF"}, ${config.skinTempPeriodMs} ms", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("PPG Cycle Time: ${config.ppgCycleTimeMs} ms", style = small)
                Text("PPG Interval Time: ${config.ppgIntervalTimeMs} ms", style = small)
                Text("PPG On During Sleep: ${if (config.ppgOnDuringSleepEn) "ON" else "OFF"}", style = small)
                Text("PPG FSR: ${config.ppgFsr}", style = small)
                Text("PPG Stop Config: ${config.ppgStopConfig}", style = small)
                Text("PPG AGC Channel Config: ${config.ppgAgcChannelConfig}", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Compressed Sensing: ${if (config.compressedSensingEn) "ON" else "OFF"}", style = small)
                Text("CS Mode: ${config.csModeString} (${config.csMode})", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Multi-Spectral: ${if (config.multiSpectralEn) "ON" else "OFF"}, ${config.multiSpectralPeriodMs} ms", style = small)
                Text("SF Max Latency: ${config.sfMaxLatencyMs} ms", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("EDA Sweep: ${if (config.edaSweepEn) "ON" else "OFF"}, ${config.edaSweepPeriodMs} ms", style = small)
                Text("EDA Sweep Param Cfg: ${config.edaSweepParamCfg}", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Acc ULP: ${config.accUlpEn}", style = small)
                Text("Acc 2G During Sleep: ${if (config.acc2gDuringSleepEn) "ON" else "OFF"}", style = small)
                Text("Acc Inactivity Config: ${config.accInactivityConfig}", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Opp Sample: ${if (config.oppSampleEn) "ON" else "OFF"}, ${config.oppSamplePeriodMs} ms", style = small)
                Text("Opp Sample On-Time: ${config.oppSampleOnTimeMs} ms", style = small)
                Text("Opp Sample Alt Mode: ${config.oppSampleAltMode}", style = small)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Memfault Config: ${config.memfaultConfig}", style = small)
                Text("Sleep Thresh Config: ${config.sleepThreshConfig}", style = small)
                Text("Reset Ring Cfg: ${config.resetRingCfg}", style = small)
                Text("Daily DAQ Mode Cfg: ${config.dailyDaqModeCfg}", style = small)
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                val fingerStr = when (fingerDetectionOn) {
                    true -> "ON"
                    false -> "OFF"
                    null -> "Unknown"
                }
                Text("Finger Detection: $fingerStr", style = small)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfigure) { Text("Configure") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}

private fun fwBuildAtLeast(fwVersion: String?, minBuild: Int): Boolean {
    if (fwVersion == null) return false
    val parts = fwVersion.split(".")
    if (parts.size < 4) return false
    val project = parts[0].toIntOrNull() ?: return false
    val major = parts[1].toIntOrNull() ?: return false
    val minor = parts[2].toIntOrNull() ?: return false
    val build = parts[3].substringBefore('-').toIntOrNull() ?: return false
    if (project > 2 || (project == 2 && major > 5)) return true
    if (project == 2 && major == 5 && minor > 0) return true
    return project == 2 && major == 5 && minor == 0 && build >= minBuild
}

private fun modeMinBuild(mode: Int): Int = when (mode) {
    0 -> Int.MAX_VALUE // not available
    in 1..7 -> 12
    in 8..11 -> 15
    in 12..13 -> 16
    in 14..16 -> 29
    in 17..18 -> 22
    in 19..20 -> 25
    21 -> 32
    in 22..23 -> 33
    24 -> 35
    in 25..26 -> 52
    else -> Int.MAX_VALUE
}

@Composable
private fun DaqConfigureDialog(
    config: DaqConfigData,
    fwVersion: String?,
    onUpdate: (DaqConfigData, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Editable state for all fields
    var applyImmediately by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(config.mode.toString()) }
    var ambientLightEn by remember { mutableStateOf(config.ambientLightEn) }
    var ambientLightPeriodMs by remember { mutableStateOf(config.ambientLightPeriodMs.toString()) }
    var ambientTempEn by remember { mutableStateOf(config.ambientTempEn) }
    var skinTempEn by remember { mutableStateOf(config.skinTempEn) }
    var skinTempPeriodMs by remember { mutableStateOf(config.skinTempPeriodMs.toString()) }
    var ppgCycleTimeMs by remember { mutableStateOf(config.ppgCycleTimeMs.toString()) }
    var ppgIntervalTimeMs by remember { mutableStateOf(config.ppgIntervalTimeMs.toString()) }
    var ppgOnDuringSleepEn by remember { mutableStateOf(config.ppgOnDuringSleepEn) }
    var ppgFsr by remember { mutableStateOf(config.ppgFsr.toString()) }
    var ppgStopConfig by remember { mutableStateOf(config.ppgStopConfig.toString()) }
    var ppgAgcChannelConfig by remember { mutableStateOf(config.ppgAgcChannelConfig.toString()) }
    var compressedSensingEn by remember { mutableStateOf(config.compressedSensingEn) }
    var csMode by remember { mutableStateOf(config.csMode.toString()) }
    var multiSpectralEn by remember { mutableStateOf(config.multiSpectralEn) }
    var multiSpectralPeriodMs by remember { mutableStateOf(config.multiSpectralPeriodMs.toString()) }
    var sfMaxLatencyMs by remember { mutableStateOf(config.sfMaxLatencyMs.toString()) }
    var edaSweepEn by remember { mutableStateOf(config.edaSweepEn) }
    var edaSweepPeriodMs by remember { mutableStateOf(config.edaSweepPeriodMs.toString()) }
    var edaSweepParamCfg by remember { mutableStateOf(config.edaSweepParamCfg.toString()) }
    var accUlpEn by remember { mutableStateOf(config.accUlpEn.toString()) }
    var acc2gDuringSleepEn by remember { mutableStateOf(config.acc2gDuringSleepEn) }
    var accInactivityConfig by remember { mutableStateOf(config.accInactivityConfig.toString()) }
    var oppSampleEn by remember { mutableStateOf(config.oppSampleEn) }
    var oppSamplePeriodMs by remember { mutableStateOf(config.oppSamplePeriodMs.toString()) }
    var oppSampleOnTimeMs by remember { mutableStateOf(config.oppSampleOnTimeMs.toString()) }
    var oppSampleAltMode by remember { mutableStateOf(config.oppSampleAltMode.toString()) }
    var memfaultConfig by remember { mutableStateOf(config.memfaultConfig.toString()) }
    var sleepThreshConfig by remember { mutableStateOf(config.sleepThreshConfig.toString()) }
    var resetRingCfg by remember { mutableStateOf(config.resetRingCfg.toString()) }
    var dailyDaqModeCfg by remember { mutableStateOf(config.dailyDaqModeCfg.toString()) }

    val small = MaterialTheme.typography.bodySmall
    val numKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    val disabledAlpha = 0.38f

    // Helper composables
    @Composable
    fun SectionLabel(text: String) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))
    }

    @Composable
    fun ConfigSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else disabledAlpha),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = small)
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    @Composable
    fun ConfigTextField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.filter { c -> c.isDigit() }) },
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else disabledAlpha),
            singleLine = true,
            keyboardOptions = numKeyboard,
            textStyle = small,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure DAQ") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // ---- General ----
                SectionLabel("General")
                ConfigSwitch("Apply Immediately", applyImmediately, { applyImmediately = it }, enabled = true)

                // Mode dropdown
                val modeEnabled = fwBuildAtLeast(fwVersion, 12)
                var modeExpanded by remember { mutableStateOf(false) }
                val currentMode = mode.toIntOrNull() ?: 0
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (modeEnabled) 1f else disabledAlpha)) {
                    OutlinedTextField(
                        value = "$currentMode - ${config.copy(mode = currentMode).modeString}",
                        onValueChange = {},
                        label = { Text("Mode", style = MaterialTheme.typography.labelSmall) },
                        readOnly = true,
                        enabled = modeEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = small,
                    )
                    // Invisible click overlay to open dropdown
                    if (modeEnabled) {
                        Box(modifier = Modifier
                            .matchParentSize()
                            .alpha(0f)
                            .let { mod ->
                                mod // clickable via DropdownMenu anchor
                            })
                        DropdownMenu(
                            expanded = modeExpanded,
                            onDismissRequest = { modeExpanded = false },
                        ) {
                            (1..26).forEach { m ->
                                val minBuild = modeMinBuild(m)
                                val available = fwBuildAtLeast(fwVersion, minBuild)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "$m - ${config.copy(mode = m).modeString}",
                                            style = small,
                                            color = if (available) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha),
                                        )
                                    },
                                    onClick = {
                                        mode = m.toString()
                                        modeExpanded = false
                                    },
                                    enabled = available,
                                )
                            }
                        }
                    }
                    // Tap area
                    if (modeEnabled) {
                        @Suppress("DEPRECATION")
                        Box(modifier = Modifier
                            .matchParentSize()
                            .padding(0.dp)
                            .let { it }) {
                            TextButton(
                                onClick = { modeExpanded = true },
                                modifier = Modifier.matchParentSize(),
                            ) {}
                        }
                    }
                }

                // ---- Ambient Light ----
                SectionLabel("Ambient Light")
                ConfigSwitch("Enable", ambientLightEn, { ambientLightEn = it }, fwBuildAtLeast(fwVersion, 12))
                ConfigTextField("Period (ms)", ambientLightPeriodMs, { ambientLightPeriodMs = it }, fwBuildAtLeast(fwVersion, 12))

                // ---- Temperature ----
                SectionLabel("Temperature")
                ConfigSwitch("Ambient Temp Enable", ambientTempEn, { ambientTempEn = it }, fwBuildAtLeast(fwVersion, 12))
                // Ambient temp period is always disabled (tied to EDA)
                OutlinedTextField(
                    value = config.ambientTempPeriodMs.toString(),
                    onValueChange = {},
                    label = { Text("Ambient Temp Period (tied to EDA)", style = MaterialTheme.typography.labelSmall) },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().alpha(disabledAlpha),
                    singleLine = true,
                    textStyle = small,
                )
                ConfigSwitch("Skin Temp Enable", skinTempEn, { skinTempEn = it }, fwBuildAtLeast(fwVersion, 12))
                ConfigTextField("Skin Temp Period (ms)", skinTempPeriodMs, { skinTempPeriodMs = it }, fwBuildAtLeast(fwVersion, 12))

                // ---- PPG ----
                SectionLabel("PPG")
                ConfigTextField("Cycle Time (ms)", ppgCycleTimeMs, { ppgCycleTimeMs = it }, fwBuildAtLeast(fwVersion, 12))
                ConfigTextField("Interval Time (ms)", ppgIntervalTimeMs, { ppgIntervalTimeMs = it }, fwBuildAtLeast(fwVersion, 12))
                ConfigSwitch("On During Sleep", ppgOnDuringSleepEn, { ppgOnDuringSleepEn = it }, fwBuildAtLeast(fwVersion, 12))
                ConfigTextField("FSR (0-5)", ppgFsr, { ppgFsr = it }, fwBuildAtLeast(fwVersion, 16))
                ConfigTextField("Stop Config (0-255)", ppgStopConfig, { ppgStopConfig = it }, fwBuildAtLeast(fwVersion, 41))
                ConfigTextField("AGC Channel Config (0-255)", ppgAgcChannelConfig, { ppgAgcChannelConfig = it }, fwBuildAtLeast(fwVersion, 44))

                // ---- Compressed Sensing ----
                SectionLabel("Compressed Sensing")
                ConfigSwitch("Enable", compressedSensingEn, { compressedSensingEn = it }, fwBuildAtLeast(fwVersion, 12))
                ConfigTextField("CS Mode (0-3)", csMode, { csMode = it }, fwBuildAtLeast(fwVersion, 55))

                // ---- Multi-Spectral ----
                SectionLabel("Multi-Spectral")
                ConfigSwitch("Enable", multiSpectralEn, { multiSpectralEn = it }, fwBuildAtLeast(fwVersion, 12))
                ConfigTextField("Period (ms)", multiSpectralPeriodMs, { multiSpectralPeriodMs = it }, fwBuildAtLeast(fwVersion, 16))

                // ---- Superframe ----
                SectionLabel("Superframe")
                ConfigTextField("Max Latency (ms)", sfMaxLatencyMs, { sfMaxLatencyMs = it }, fwBuildAtLeast(fwVersion, 16))

                // ---- EDA Sweep ----
                SectionLabel("EDA Sweep")
                ConfigSwitch("Enable", edaSweepEn, { edaSweepEn = it }, fwBuildAtLeast(fwVersion, 28))
                ConfigTextField("Period (ms)", edaSweepPeriodMs, { edaSweepPeriodMs = it }, fwBuildAtLeast(fwVersion, 28))
                ConfigTextField("Param Config (0-255)", edaSweepParamCfg, { edaSweepParamCfg = it }, fwBuildAtLeast(fwVersion, 69))

                // ---- Accelerometer ----
                SectionLabel("Accelerometer")
                ConfigTextField("ULP Config (0-255)", accUlpEn, { accUlpEn = it }, fwBuildAtLeast(fwVersion, 22))
                ConfigSwitch("2G During Sleep", acc2gDuringSleepEn, { acc2gDuringSleepEn = it }, fwBuildAtLeast(fwVersion, 36))
                ConfigTextField("Inactivity Config (0-200)", accInactivityConfig, { accInactivityConfig = it }, fwBuildAtLeast(fwVersion, 40))

                // ---- Opportunistic Sampling ----
                SectionLabel("Opportunistic Sampling")
                ConfigSwitch("Enable", oppSampleEn, { oppSampleEn = it }, fwBuildAtLeast(fwVersion, 30))
                ConfigTextField("Period (ms)", oppSamplePeriodMs, { oppSamplePeriodMs = it }, fwBuildAtLeast(fwVersion, 30))
                ConfigTextField("On-Time (ms)", oppSampleOnTimeMs, { oppSampleOnTimeMs = it }, fwBuildAtLeast(fwVersion, 31))
                ConfigTextField("Alt Mode (0-20)", oppSampleAltMode, { oppSampleAltMode = it }, fwBuildAtLeast(fwVersion, 30))

                // ---- Memfault ----
                SectionLabel("Memfault")
                ConfigTextField("Config (0-255)", memfaultConfig, { memfaultConfig = it }, fwBuildAtLeast(fwVersion, 30))

                // ---- Sleep ----
                SectionLabel("Sleep")
                ConfigTextField("Threshold Config (0-255)", sleepThreshConfig, { sleepThreshConfig = it }, fwBuildAtLeast(fwVersion, 45))

                // ---- Reset ----
                SectionLabel("Reset")
                ConfigTextField("Reset Ring Cfg (0-50)", resetRingCfg, { resetRingCfg = it }, fwBuildAtLeast(fwVersion, 60))

                // ---- Daily DAQ Mode ----
                SectionLabel("Daily DAQ Mode")
                ConfigTextField("Config (0-255)", dailyDaqModeCfg, { dailyDaqModeCfg = it }, fwBuildAtLeast(fwVersion, 76))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Build config with clamped values
                fun Int.clamp(min: Int, max: Int) = this.coerceIn(min, max)
                fun UInt.clamp(min: UInt, max: UInt) = this.coerceIn(min, max)

                val newConfig = DaqConfigData(
                    version = config.version,
                    mode = (mode.toIntOrNull() ?: config.mode).clamp(1, 26),
                    ambientLightEn = ambientLightEn,
                    ambientLightPeriodMs = (ambientLightPeriodMs.toUIntOrNull() ?: config.ambientLightPeriodMs).clamp(1000u, 60000u),
                    ambientTempEn = ambientTempEn,
                    ambientTempPeriodMs = config.ambientTempPeriodMs, // read-only, tied to EDA
                    skinTempEn = skinTempEn,
                    skinTempPeriodMs = (skinTempPeriodMs.toUIntOrNull() ?: config.skinTempPeriodMs).clamp(1000u, 60000u),
                    ppgCycleTimeMs = (ppgCycleTimeMs.toUIntOrNull() ?: config.ppgCycleTimeMs).clamp(1000u, 3600000u),
                    ppgIntervalTimeMs = (ppgIntervalTimeMs.toUIntOrNull() ?: config.ppgIntervalTimeMs).clamp(1000u, 3600000u),
                    ppgOnDuringSleepEn = ppgOnDuringSleepEn,
                    compressedSensingEn = compressedSensingEn,
                    multiSpectralEn = multiSpectralEn,
                    multiSpectralPeriodMs = (multiSpectralPeriodMs.toUIntOrNull() ?: config.multiSpectralPeriodMs),
                    sfMaxLatencyMs = (sfMaxLatencyMs.toUIntOrNull() ?: config.sfMaxLatencyMs),
                    ppgFsr = (ppgFsr.toIntOrNull() ?: config.ppgFsr).clamp(0, 5),
                    edaSweepEn = edaSweepEn,
                    edaSweepPeriodMs = (edaSweepPeriodMs.toUIntOrNull() ?: config.edaSweepPeriodMs),
                    accUlpEn = (accUlpEn.toIntOrNull() ?: config.accUlpEn).clamp(0, 255),
                    oppSampleEn = oppSampleEn,
                    oppSamplePeriodMs = (oppSamplePeriodMs.toUIntOrNull() ?: config.oppSamplePeriodMs),
                    oppSampleAltMode = (oppSampleAltMode.toIntOrNull() ?: config.oppSampleAltMode).clamp(0, 20),
                    memfaultConfig = (memfaultConfig.toIntOrNull() ?: config.memfaultConfig).clamp(0, 255),
                    oppSampleOnTimeMs = (oppSampleOnTimeMs.toUIntOrNull() ?: config.oppSampleOnTimeMs),
                    acc2gDuringSleepEn = acc2gDuringSleepEn,
                    accInactivityConfig = (accInactivityConfig.toIntOrNull() ?: config.accInactivityConfig).clamp(0, 200),
                    ppgStopConfig = (ppgStopConfig.toIntOrNull() ?: config.ppgStopConfig).clamp(0, 255),
                    ppgAgcChannelConfig = (ppgAgcChannelConfig.toIntOrNull() ?: config.ppgAgcChannelConfig).clamp(0, 255),
                    sleepThreshConfig = (sleepThreshConfig.toIntOrNull() ?: config.sleepThreshConfig).clamp(0, 255),
                    csMode = (csMode.toIntOrNull() ?: config.csMode).clamp(0, 3),
                    resetRingCfg = (resetRingCfg.toIntOrNull() ?: config.resetRingCfg).clamp(0, 50),
                    edaSweepParamCfg = (edaSweepParamCfg.toIntOrNull() ?: config.edaSweepParamCfg).clamp(0, 255),
                    dailyDaqModeCfg = (dailyDaqModeCfg.toIntOrNull() ?: config.dailyDaqModeCfg).clamp(0, 255),
                )
                onUpdate(newConfig, applyImmediately)
            }) { Text("Update") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CommandSectionHeader(title: String, commandStatus: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (commandStatus != null) {
            val statusColor = when {
                commandStatus.contains("Success") -> MaterialTheme.colorScheme.primary
                commandStatus.contains("Timeout") -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
            Text(
                commandStatus,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor,
            )
        }
    }
    HorizontalDivider()
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun DownloadSectionHeader(title: String, downloadState: String?, onShare: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Bottom),
        )
        if (downloadState != null) {
            val stateColor = when (downloadState) {
                "Downloading" -> MaterialTheme.colorScheme.primary
                "Waiting" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                downloadState,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = stateColor,
            )
        } else if (onShare != null) {
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share / Manage Files",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    HorizontalDivider()
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun ShareHpy2Dialog(viewModel: TestAppViewModel, deviceId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(viewModel.listHpy2Files(deviceId)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HPY2 Files (${deviceId.lowercase()})") },
        text = {
            if (files.isEmpty()) {
                Text("No .hpy2 files found.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    files.forEach { file ->
                        val sizeKb = file.length() / 1024
                        val frames = file.length() / 4096
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    val intent = viewModel.shareHpy2File(file.absolutePath)
                                    if (intent != null) {
                                        context.startActivity(Intent.createChooser(intent, "Share HPY2 file"))
                                    }
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "${frames} frames (${sizeKb} KB)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    file.delete()
                                    files = viewModel.listHpy2Files(deviceId)
                                },
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FwUpdateSectionHeader(title: String, fwUpdateState: String?, onClear: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Bottom),
        )
        if (fwUpdateState != null) {
            val stateColor = when {
                fwUpdateState == "Uploading" -> MaterialTheme.colorScheme.primary
                fwUpdateState == "Finalizing" -> MaterialTheme.colorScheme.tertiary
                fwUpdateState?.contains("Abort") == true -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                fwUpdateState,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = stateColor,
            )
        } else if (onClear != null) {
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("CLEAR", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
    HorizontalDivider()
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    HorizontalDivider()
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FwUpdateSection(
    connId: ConnectionId,
    ring: com.happyhealth.testapp.ConnectedRingInfo,
    viewModel: TestAppViewModel,
    isReady: Boolean,
    gridShape: RoundedCornerShape,
    gridRowHeight: androidx.compose.ui.unit.Dp,
    gridGap: androidx.compose.ui.unit.Dp,
    gridContentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val fwImageMap by viewModel.fwImageInfoMap.collectAsState()
    val fwImage = fwImageMap[connId.value]
    val isFwUpdating = ring.isFwUpdating ||
        ring.state == HpyConnectionState.FW_UPDATING ||
        ring.state == HpyConnectionState.FW_UPDATE_REBOOTING

    // Elapsed-time counter for FW update
    var fwElapsedSeconds by remember { mutableIntStateOf(0) }
    var fwUploadSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isFwUpdating) {
        if (isFwUpdating) {
            fwElapsedSeconds = 0
            fwUploadSeconds = 0
            while (true) {
                delay(1000L)
                fwElapsedSeconds++
            }
        }
    }
    // Capture upload time when image transfer completes
    LaunchedEffect(ring.fwUpdateState) {
        if (ring.fwUpdateState != null && ring.fwUpdateState != "Uploading" && fwUploadSeconds == 0 && fwElapsedSeconds > 0) {
            fwUploadSeconds = fwElapsedSeconds
        }
    }

    var showPickerDialog by remember { mutableStateOf(false) }
    var showMemfaultDialog by remember { mutableStateOf(false) }

    val fwImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val error = viewModel.loadFwImage(uri, connId)
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        }
        showPickerDialog = false
    }

    // Source choice dialog
    if (showPickerDialog) {
        AlertDialog(
            onDismissRequest = { showPickerDialog = false },
            title = { Text("Select FW Image") },
            text = {
                Text("Choose a firmware image source.",
                    style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(onClick = {
                    showPickerDialog = false
                    showMemfaultDialog = true
                    viewModel.fetchMemfaultReleases()
                }) {
                    Text("Memfault")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showPickerDialog = false }) {
                        Text("Cancel")
                    }
                    TextButton(onClick = { fwImageLauncher.launch("*/*") }) {
                        Text("Browse Files")
                    }
                }
            },
        )
    }

    // Memfault releases dialog
    if (showMemfaultDialog) {
        val releases by viewModel.memfaultReleases.collectAsState()
        val memfaultLoading by viewModel.memfaultLoading.collectAsState()
        val memfaultHasMore by viewModel.memfaultHasMore.collectAsState()
        val memfaultError by viewModel.memfaultError.collectAsState()
        val memfaultDownloadingConnId by viewModel.memfaultDownloadingConnId.collectAsState()
        val memfaultDownloading = memfaultDownloadingConnId == connId
        val memfaultDownloadVersion by viewModel.memfaultDownloadVersion.collectAsState()
        val memfaultDownloadProgress by viewModel.memfaultDownloadProgress.collectAsState()
        val memfaultDownloadError by viewModel.memfaultDownloadError.collectAsState()

        MemfaultReleasesDialog(
            releases = releases,
            isLoading = memfaultLoading,
            hasMore = memfaultHasMore,
            error = memfaultError,
            isDownloading = memfaultDownloading,
            downloadingVersion = memfaultDownloadVersion,
            downloadProgress = memfaultDownloadProgress,
            downloadError = memfaultDownloadError,
            onLoadMore = { viewModel.loadMoreMemfaultReleases() },
            onSelectRelease = { release ->
                viewModel.downloadMemfaultRelease(release.version, connId)
            },
            onCancelDownload = { viewModel.cancelMemfaultDownload() },
            onDismiss = { showMemfaultDialog = false },
        )

        // Auto-dismiss when a Memfault download completes successfully
        var wasDownloading by remember { mutableStateOf(false) }
        LaunchedEffect(memfaultDownloading) {
            if (memfaultDownloading) {
                wasDownloading = true
            } else if (wasDownloading && fwImage != null) {
                showMemfaultDialog = false
                wasDownloading = false
            }
        }
    }

    val clearAction: (() -> Unit)? = if (fwImage != null && !isFwUpdating) {
        { viewModel.clearFwImage(connId) }
    } else null
    FwUpdateSectionHeader("FW Update", ring.fwUpdateState, clearAction)

    if (fwImage != null) {
        InfoRow("Image", fwImage!!.fileName)
        InfoRow("Size", "${fwImage!!.fileSize / 1024} KB")
    }

    if (isFwUpdating && ring.fwBlocksTotal > 0) {
        Spacer(modifier = Modifier.height(4.dp))
        @Suppress("DEPRECATION")
        LinearProgressIndicator(
            progress = ring.fwBlocksSent.toFloat() / ring.fwBlocksTotal,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${ring.fwBlocksSent} / ${ring.fwBlocksTotal} blocks (${ring.fwUpdateState ?: ""})",
                style = MaterialTheme.typography.bodySmall,
            )
            val timerText = if (fwUploadSeconds > 0) {
                "(${fwUploadSeconds}s) ${fwElapsedSeconds}s"
            } else {
                "${fwElapsedSeconds}s"
            }
            Text(
                timerText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    } else if (ring.state == HpyConnectionState.FW_UPDATE_REBOOTING) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Ring rebooting...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(modifier = Modifier.height(4.dp))
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gridGap),
    ) {
        Button(
            onClick = { showPickerDialog = true },
            enabled = !isFwUpdating,
            modifier = Modifier.weight(1f).height(gridRowHeight),
            shape = gridShape,
            contentPadding = gridContentPadding,
        ) {
            Text("Select Image")
        }
        if (isFwUpdating) {
            Button(
                onClick = { viewModel.cancelFwUpdate(connId) },
                enabled = ring.state == HpyConnectionState.FW_UPDATING,
                modifier = Modifier.weight(1f).height(gridRowHeight),
                shape = gridShape,
                contentPadding = gridContentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Cancel FW")
            }
        } else {
            Button(
                onClick = { viewModel.requestStartFwUpdate(connId) },
                enabled = fwImage != null && isReady,
                modifier = Modifier.weight(1f).height(gridRowHeight),
                shape = gridShape,
                contentPadding = gridContentPadding,
            ) {
                Text("Update")
            }
        }
    }
}
