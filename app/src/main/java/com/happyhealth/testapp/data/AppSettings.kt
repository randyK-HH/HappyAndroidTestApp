package com.happyhealth.testapp.data

import com.happyhealth.bleplatform.api.HpyConfig

data class AppSettings(
    val preferL2capDownload: Boolean = true,
    val minRssi: Int = -80,
    val reconnectMaxAttempts: Int = 64,
    val downloadBatchSize: Int = 64,
    val downloadStallTimeoutMs: Long = 60_000L,
    val downloadFailsafeIntervalMs: Long = 21L * 60 * 1000,
    val skipFingerDetection: Boolean = false,
    val memfaultMinIntervalMs: Long = 0L,
    val autoReconnect: Boolean = true,
    val fwStreamInterBlockDelayMs: Long = 30L,
    val fwStreamDrainDelayMs: Long = 2000L,
) {
    fun toHpyConfig(): HpyConfig = HpyConfig(
        preferL2capDownload = preferL2capDownload,
        minRssi = minRssi,
        reconnectMaxAttempts = reconnectMaxAttempts,
        downloadBatchSize = downloadBatchSize,
        downloadStallTimeoutMs = downloadStallTimeoutMs,
        downloadFailsafeIntervalMs = downloadFailsafeIntervalMs,
        skipFingerDetection = skipFingerDetection,
        memfaultMinIntervalMs = memfaultMinIntervalMs,
        autoReconnect = autoReconnect,
        fwStreamInterBlockDelayMs = fwStreamInterBlockDelayMs,
        fwStreamDrainDelayMs = fwStreamDrainDelayMs,
    )
}
