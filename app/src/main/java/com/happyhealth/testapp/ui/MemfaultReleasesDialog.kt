package com.happyhealth.testapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happyhealth.testapp.data.MemfaultRelease
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun MemfaultReleasesDialog(
    releases: List<MemfaultRelease>,
    isLoading: Boolean,
    hasMore: Boolean,
    error: String?,
    isDownloading: Boolean,
    downloadingVersion: String?,
    onLoadMore: () -> Unit,
    onSelectRelease: (MemfaultRelease) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text("Memfault Releases") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
            ) {
                if (releases.isEmpty() && isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (releases.isEmpty() && error != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()

                    // Auto-pagination: trigger load when near end
                    val shouldLoadMore by remember {
                        derivedStateOf {
                            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= releases.size - 5 && hasMore && !isLoading
                        }
                    }
                    LaunchedEffect(shouldLoadMore) {
                        if (shouldLoadMore) onLoadMore()
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(releases) { _, release ->
                            val isThisDownloading = isDownloading && downloadingVersion == release.version
                            Card(
                                onClick = {
                                    if (!isDownloading) onSelectRelease(release)
                                },
                                enabled = !isDownloading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            release.version,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            formatDate(release.createdDate),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isThisDownloading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            }
                        }

                        // Loading more indicator
                        if (isLoading && releases.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }

                        // Error at bottom of list
                        if (error != null && releases.isNotEmpty()) {
                            item {
                                Text(
                                    error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }

                        // End of list caption
                        if (!hasMore && !isLoading && releases.isNotEmpty()) {
                            item {
                                Text(
                                    "${releases.size} releases loaded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDownloading,
            ) {
                Text("Cancel")
            }
        },
        confirmButton = {},
    )
}

private fun formatDate(isoDate: String): String {
    if (isoDate.isBlank()) return ""
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        // Strip fractional seconds and timezone suffix for parsing
        val trimmed = isoDate.replace(Regex("\\.[0-9]+.*"), "")
        val date = parser.parse(trimmed) ?: return isoDate.take(10)
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)
    } catch (_: Exception) {
        isoDate.take(10)
    }
}
