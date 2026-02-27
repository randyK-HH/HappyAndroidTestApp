package com.happyhealth.testapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    var showShareDialog by remember { mutableStateOf(false) }

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
                if (ring.downloadTotal > 0) {
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = ring.downloadProgress.toFloat() / ring.downloadTotal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val transportLabel = if (ring.downloadTransport.isNotEmpty()) "  (${ring.downloadTransport})" else ""
                    Text(
                        "${ring.downloadProgress} / ${ring.downloadTotal} frames$transportLabel",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Starting download...", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(4.dp))
            } else if (isWaiting) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Waiting for data...", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
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
                    onClick = { viewModel.startDownload(connId) },
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
            onDismiss = { showStatusDialog = false },
        )
    }

    // ---- DAQ Config Dialog ----
    if (showDaqConfigDialog && ring.daqConfig != null) {
        DaqConfigDialog(
            config = ring.daqConfig!!,
            fingerDetectionOn = ring.fingerDetectionOn,
            onDismiss = { showDaqConfigDialog = false },
        )
    }

    // ---- Share HPY2 File Dialog ----
    if (showShareDialog) {
        ShareHpy2Dialog(
            viewModel = viewModel,
            onDismiss = { showShareDialog = false },
        )
    }
}

@Composable
private fun DeviceStatusDialog(
    status: DeviceStatusData,
    extendedStatus: ResponseParser.ExtendedDeviceStatus?,
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
private fun DaqConfigDialog(config: DaqConfigData, fingerDetectionOn: Boolean?, onDismiss: () -> Unit) {
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
            TextButton(onClick = onDismiss) { Text("Dismiss") }
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
private fun ShareHpy2Dialog(viewModel: TestAppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(viewModel.listHpy2Files()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share / Manage HPY2 Files") },
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
                                    files = viewModel.listHpy2Files()
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
    val fwImage by viewModel.fwImageInfo.collectAsState()
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

    val fwImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val error = viewModel.loadFwImage(uri)
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        }
        showPickerDialog = false
    }

    if (showPickerDialog) {
        AlertDialog(
            onDismissRequest = { showPickerDialog = false },
            title = { Text("Select FW Image") },
            text = {
                Text("Browse for a .img firmware file. The image will be validated before use.",
                    style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(onClick = { fwImageLauncher.launch("*/*") }) {
                    Text("Browse")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPickerDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    val clearAction: (() -> Unit)? = if (fwImage != null && !isFwUpdating) {
        { viewModel.clearFwImage() }
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
                onClick = { viewModel.startFwUpdate(connId) },
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
