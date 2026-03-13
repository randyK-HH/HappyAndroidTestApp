package com.happyhealth.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.happyhealth.testapp.R
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
    val scanErrorMessage by viewModel.scanErrorMessage.collectAsState()
    val globalSettings by viewModel.globalSettings.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    // Filter out devices that are already connected
    val connectedAddresses = connectedRings.values.map { it.address }.toSet()
    val unconnectedDevices = discovered.filter { it.address !in connectedAddresses }

    // Throttled sort order — controls card ordering only, not the data displayed.
    // Re-sorts every 3s tick or immediately when items are added/removed.
    var sortTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            sortTick++
        }
    }
    val connectedSortOrder = remember(connectedRings.size, sortTick) {
        connectedRings.values
            .sortedByDescending { it.lastRssi ?: Int.MIN_VALUE }
            .map { it.connId.value }
    }
    val discoveredSortOrder = remember(unconnectedDevices.size, sortTick) {
        unconnectedDevices
            .sortedByDescending { it.rssi }
            .map { it.address }
    }
    val discoveredByAddress = unconnectedDevices.associateBy { it.address }

    Box(modifier = Modifier.fillMaxSize()) {
        // Watermark background — logo + text centered
        val watermarkColor = androidx.compose.ui.graphics.Color.Gray
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Image(
                painter = painterResource(R.drawable.happy_watermark),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.70f)
                    .alpha(0.25f),
                contentScale = ContentScale.Fit,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Happy Health",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = watermarkColor.copy(alpha = 0.35f),
                textAlign = TextAlign.Center,
            )
            Text(
                "Android Platform Test App",
                fontSize = 20.sp,
                color = watermarkColor.copy(alpha = 0.35f),
                textAlign = TextAlign.Center,
            )
            Text(
                "v${com.happyhealth.testapp.BuildConfig.VERSION_NAME}",
                fontSize = 13.sp,
                color = watermarkColor.copy(alpha = 0.30f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(48.dp))
        }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Scan toggle + Disconnect All buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Button(
                onClick = { viewModel.toggleScan() },
                modifier = Modifier.weight(1f).height(38.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = if (isScanning) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (isScanning) "Stop Scanning" else "Start Scanning")
            }
            Button(
                onClick = { viewModel.disconnectAll() },
                enabled = connectedRings.isNotEmpty(),
                modifier = Modifier.weight(1f).height(38.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("Disconnect All")
            }
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ---- Connected Rings Section ----
            if (connectedRings.isNotEmpty()) {
                item {
                    Text(
                        "Connected Rings (${connectedRings.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(connectedSortOrder, key = { it }) { connIdValue ->
                    val ring = connectedRings[connIdValue] ?: return@items
                    ConnectedRingCard(ring = ring, onClick = {
                        if (isScanning) viewModel.toggleScan()
                        onRingSelected(ring.connId)
                    })
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // ---- Discovered (Non-Connected) Rings Section ----
            item {
                Text(
                    "Discovered Devices (${unconnectedDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (scanErrorMessage != null) {
                item {
                    Text(
                        scanErrorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
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

            items(discoveredSortOrder, key = { it }) { address ->
                val device = discoveredByAddress[address] ?: return@items
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

    if (showSettings) {
        SettingsBottomSheet(
            settings = globalSettings,
            isPerRing = false,
            onSave = { viewModel.updateGlobalSettings(it) },
            onResetToGlobal = null,
            onDismiss = { showSettings = false },
        )
    }
    } // Box
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
