package com.happyhealth.testapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happyhealth.testapp.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    settings: AppSettings,
    isPerRing: Boolean,
    onSave: (AppSettings) -> Unit,
    onResetToGlobal: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var current by remember(settings) { mutableStateOf(settings) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                if (isPerRing) "Ring Settings" else "Global Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // ---- Transport ----
            SettingsSectionHeader("Transport")
            SettingsSwitch(
                label = "Transport Preference",
                description = if (current.preferL2capDownload) "L2CAP" else "GATT",
                checked = current.preferL2capDownload,
                onCheckedChange = { current = current.copy(preferL2capDownload = it) },
            )
            SettingsSwitch(
                label = "L2CAP Clock",
                description = if (current.use96MHzClock) "96 MHz" else "48 MHz",
                checked = current.use96MHzClock,
                onCheckedChange = { current = current.copy(use96MHzClock = it) },
            )

            // ---- Scan / Connection ----
            SettingsSectionHeader("Scan / Connection")
            SettingsDropdown(
                label = "Min RSSI",
                value = current.minRssi,
                options = listOf(-60, -70, -75, -80, -85, -90, -95, -100),
                format = { "$it dBm" },
                onSelect = { current = current.copy(minRssi = it) },
            )
            SettingsSwitch(
                label = "Auto-Reconnect",
                checked = current.autoReconnect,
                onCheckedChange = { current = current.copy(autoReconnect = it) },
            )
            SettingsDropdown(
                label = "Max Reconnect Retries",
                value = current.reconnectMaxAttempts,
                options = listOf(8, 16, 32, 64, Int.MAX_VALUE),
                format = { if (it == Int.MAX_VALUE) "Unlimited" else it.toString() },
                onSelect = { current = current.copy(reconnectMaxAttempts = it) },
            )

            // ---- Download ----
            SettingsSectionHeader("Download")
            SettingsDropdown(
                label = "Batch Size",
                value = current.downloadBatchSize,
                options = listOf(8, 16, 32, 64, 128),
                format = { it.toString() },
                onSelect = { current = current.copy(downloadBatchSize = it) },
            )
            SettingsDropdown(
                label = "Stall Timeout",
                value = current.downloadStallTimeoutMs,
                options = listOf(15_000L, 30_000L, 45_000L, 60_000L, 90_000L, 120_000L),
                format = { "${it / 1000}s" },
                onSelect = { current = current.copy(downloadStallTimeoutMs = it) },
            )
            SettingsDropdown(
                label = "Failsafe Timer Interval",
                value = current.downloadFailsafeIntervalMs,
                options = listOf(
                    6L * 60_000, 11L * 60_000, 16L * 60_000, 21L * 60_000,
                    26L * 60_000, 31L * 60_000, 41L * 60_000, 51L * 60_000, 61L * 60_000,
                ),
                format = { "${it / 60_000} min" },
                onSelect = { current = current.copy(downloadFailsafeIntervalMs = it) },
            )

            // ---- Handshake ----
            SettingsSectionHeader("Handshake")
            SettingsSwitch(
                label = "Set Finger Detection on Connect",
                checked = !current.skipFingerDetection,
                onCheckedChange = { current = current.copy(skipFingerDetection = !it) },
            )
            SettingsDropdown(
                label = "Memfault Chunk Interval",
                value = current.memfaultMinIntervalMs,
                options = listOf(0L, 10L * 60_000, 20L * 60_000, 30L * 60_000, 60L * 60_000, Long.MAX_VALUE),
                format = { when (it) { 0L -> "Every connection"; Long.MAX_VALUE -> "Never"; else -> "${it / 60_000} min" } },
                onSelect = { current = current.copy(memfaultMinIntervalMs = it) },
            )

            // ---- FW Update ----
            SettingsSectionHeader("FW Update")
            SettingsDropdown(
                label = "Inter-Block Delay",
                value = current.fwStreamInterBlockDelayMs,
                options = listOf(10L, 15L, 20L, 25L, 30L, 35L, 40L, 45L, 50L, 55L, 60L),
                format = { "${it}ms" },
                onSelect = { current = current.copy(fwStreamInterBlockDelayMs = it) },
            )
            SettingsDropdown(
                label = "Drain Delay",
                value = current.fwStreamDrainDelayMs,
                options = listOf(1000L, 2000L, 3000L, 4000L, 5000L, 6000L, 7000L),
                format = { "${it}ms" },
                onSelect = { current = current.copy(fwStreamDrainDelayMs = it) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Action Buttons ----
            Button(
                onClick = { onSave(current); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            if (isPerRing && onResetToGlobal != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onResetToGlobal(); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset to Global Defaults")
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdown(
    label: String,
    value: T,
    options: List<T>,
    format: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = format(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(format(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
