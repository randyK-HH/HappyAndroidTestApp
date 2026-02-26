package com.happyhealth.testapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ring.name) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- State ----
            val stateColor = when (ring.state) {
                HpyConnectionState.READY -> MaterialTheme.colorScheme.primary
                HpyConnectionState.HANDSHAKING, HpyConnectionState.CONNECTING ->
                    MaterialTheme.colorScheme.tertiary
                HpyConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                "State: ${ring.state}",
                style = MaterialTheme.typography.titleMedium,
                color = stateColor,
                fontWeight = FontWeight.Bold,
            )

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
            val isReady = ring.state == HpyConnectionState.READY

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
                    Text("Status")
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

            // ---- Download ----
            val canStartDownload = isReady &&
                (ring.deviceInfo?.firmwareTier ?: FirmwareTier.TIER_0) >= FirmwareTier.TIER_1
            val isDownloading = ring.isDownloading

            SectionHeader("Download")
            if (isDownloading) {
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
        }
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
                Text("BLE CI: ${status.bleCi} ms", style = small)
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
