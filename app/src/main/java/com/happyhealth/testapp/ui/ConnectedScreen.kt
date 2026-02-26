package com.happyhealth.testapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyConnectionState
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
            SectionHeader("Commands")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.identify(connId) },
                    enabled = ring.state == HpyConnectionState.READY,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Identify")
                }
                Button(
                    onClick = { viewModel.getDeviceStatus(connId) },
                    enabled = ring.state == HpyConnectionState.READY,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Status")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.getDaqConfig(connId) },
                    enabled = ring.state == HpyConnectionState.READY,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("DAQ Config")
                }
                Button(
                    onClick = { viewModel.startDaq(connId) },
                    enabled = ring.state == HpyConnectionState.READY,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start DAQ")
                }
                Button(
                    onClick = { viewModel.stopDaq(connId) },
                    enabled = ring.state == HpyConnectionState.READY,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stop DAQ")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Event Log ----
            EventLogSection(connId = connId, viewModel = viewModel)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
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
