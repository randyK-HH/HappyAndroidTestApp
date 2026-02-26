package com.happyhealth.testapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.bleplatform.api.HpyConnectionState
import com.happyhealth.testapp.ConnectedRingInfo
import com.happyhealth.testapp.TestAppViewModel

@Composable
fun ScanScreen(
    viewModel: TestAppViewModel,
    onRingSelected: (ConnectionId) -> Unit,
) {
    val isScanning by viewModel.isScanning.collectAsState()
    val discovered by viewModel.discoveredDevices.collectAsState()
    val connectedRings by viewModel.connectedRings.collectAsState()

    // Filter out devices that are already connected
    val connectedAddresses = connectedRings.values.map { it.address }.toSet()
    val unconnectedDevices = discovered.filter { it.address !in connectedAddresses }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Scan toggle button
        Button(
            onClick = { viewModel.toggleScan() },
            modifier = Modifier.fillMaxWidth(),
            colors = if (isScanning) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(if (isScanning) "Stop Scanning" else "Start Scanning")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ---- Connected Rings Section ----
            if (connectedRings.isNotEmpty()) {
                item {
                    Text(
                        "Connected Rings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(connectedRings.values.toList(), key = { it.connId.value }) { ring ->
                    ConnectedRingCard(ring = ring, onClick = { onRingSelected(ring.connId) })
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // ---- Discovered (Non-Connected) Rings Section ----
            item {
                Text(
                    "Discovered Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (unconnectedDevices.isEmpty() && !isScanning) {
                item {
                    Text(
                        "No devices found. Tap 'Start Scanning' to search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            if (isScanning && unconnectedDevices.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Scanning...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            items(unconnectedDevices, key = { it.address }) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.connect(device) },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(device.name, fontWeight = FontWeight.Medium)
                            Text(
                                device.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "${device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedRingCard(ring: ConnectedRingInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(ring.name, fontWeight = FontWeight.Bold)
                Text(
                    ring.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val stateText = when (ring.state) {
                    HpyConnectionState.CONNECTING -> "Connecting..."
                    HpyConnectionState.HANDSHAKING -> "Handshaking..."
                    HpyConnectionState.READY -> "Ready"
                    HpyConnectionState.CONNECTED_LIMITED -> "Limited"
                    HpyConnectionState.DOWNLOADING -> "Downloading"
                    HpyConnectionState.FW_UPDATING -> "FW Updating"
                    HpyConnectionState.RECONNECTING -> "Reconnecting..."
                    else -> ring.state.name
                }
                val stateColor = when (ring.state) {
                    HpyConnectionState.READY -> MaterialTheme.colorScheme.primary
                    HpyConnectionState.CONNECTING, HpyConnectionState.HANDSHAKING ->
                        MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(stateText, color = stateColor, fontWeight = FontWeight.Medium)
                if (ring.deviceInfo != null) {
                    Text(
                        "FW ${ring.deviceInfo!!.fwVersion}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
