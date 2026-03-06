package com.happyhealth.testapp.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.testapp.LogEntry
import com.happyhealth.testapp.TestAppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventLogSection(
    connId: ConnectionId,
    viewModel: TestAppViewModel,
    modifier: Modifier = Modifier,
) {
    val connectionLogs by viewModel.connectionLogs.collectAsState()
    val logs = connectionLogs[connId.value] ?: emptyList()
    val faultCounts by viewModel.faultCounts.collectAsState()
    val faultCount = faultCounts[connId.value] ?: 0
    val ncfCounts by viewModel.ncfCounts.collectAsState()
    val ncfCount = ncfCounts[connId.value] ?: 0
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new entries arrive (keyed on last entry id,
    // not list size, because takeLast() caps the list at a fixed size)
    val lastId = logs.lastOrNull()?.id
    LaunchedEffect(lastId) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(modifier = modifier) {
        Text(
            "Event Log",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (faultCount > 0 || ncfCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        ) {
            if (logs.isEmpty()) {
                Text(
                    "No events yet",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                ) {
                    items(logs, key = { it.id }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
fun FullScreenEventLog(
    connId: ConnectionId,
    viewModel: TestAppViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val connectionLogs by viewModel.connectionLogs.collectAsState()
    val logs = connectionLogs[connId.value] ?: emptyList()
    val faultCounts by viewModel.faultCounts.collectAsState()
    val faultCount = faultCounts[connId.value] ?: 0
    val ncfCounts by viewModel.ncfCounts.collectAsState()
    val ncfCount = ncfCounts[connId.value] ?: 0
    val retryCounts by viewModel.retryCounts.collectAsState()
    val retryCount = retryCounts[connId.value] ?: 0
    val reconnectionCounts by viewModel.reconnectionCounts.collectAsState()
    val reconCount = reconnectionCounts[connId.value] ?: 0
    val listState = rememberLazyListState()
    var showShareDialog by remember { mutableStateOf(false) }

    val lastId = logs.lastOrNull()?.id
    LaunchedEffect(lastId) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Event Log",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (faultCount > 0 || ncfCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Row {
                IconButton(
                    onClick = {
                        val path = viewModel.saveEventLog(connId)
                        if (path != null) {
                            Toast.makeText(context, "Log saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "No log entries to save", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = "Save Log",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { showShareDialog = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share / Manage Logs",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (faultCount > 0 || ncfCount > 0 || retryCount > 0 || reconCount > 0) {
            val parts = buildList {
                if (faultCount > 0) add("ERR: $faultCount")
                if (ncfCount > 0) add("NCF: $ncfCount")
                if (retryCount > 0) add("RETRY: $retryCount")
                if (reconCount > 0) add("RECON: $reconCount")
            }
            Text(
                text = "[${parts.joinToString(", ")}]",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))

        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        ) {
            if (logs.isEmpty()) {
                Text(
                    "No events yet",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                ) {
                    items(logs, key = { it.id }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }

    if (showShareDialog) {
        val deviceId = viewModel.connectedRings.collectAsState().value[connId.value]?.name
        ShareEventLogDialog(
            viewModel = viewModel,
            deviceId = deviceId,
            onDismiss = { showShareDialog = false },
        )
    }
}

@Composable
private fun ShareEventLogDialog(viewModel: TestAppViewModel, deviceId: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(viewModel.listEventLogFiles(deviceId)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Event Logs${deviceId?.let { " (${it.lowercase()})" } ?: ""}") },
        text = {
            if (files.isEmpty()) {
                Text("No saved event logs found.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    files.forEach { file ->
                        val sizeKb = file.length() / 1024
                        val lines = file.readLines().size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    val intent = viewModel.shareEventLogFile(file.absolutePath)
                                    if (intent != null) {
                                        context.startActivity(Intent.createChooser(intent, "Share event log"))
                                    }
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "$lines entries ($sizeKb KB)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    file.delete()
                                    files = viewModel.listEventLogFiles(deviceId)
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
internal fun LogEntryRow(entry: LogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val timeStr = timeFormat.format(Date(entry.timestamp))

    val isError = entry.message.startsWith("ERROR")
    val color = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = "$timeStr  ${entry.message}",
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        ),
        color = color,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
