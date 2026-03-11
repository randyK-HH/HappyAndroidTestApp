package com.happyhealth.testapp.data

import android.content.Context
import android.content.SharedPreferences

class SettingsRepository(context: Context) {

    private val globalPrefs: SharedPreferences =
        context.getSharedPreferences("ble_settings_global", Context.MODE_PRIVATE)

    private val ringPrefs: SharedPreferences =
        context.getSharedPreferences("ble_settings_ring", Context.MODE_PRIVATE)

    fun loadGlobalSettings(): AppSettings = loadFrom(globalPrefs, "")

    fun saveGlobalSettings(settings: AppSettings) = saveTo(globalPrefs, "", settings)

    fun loadRingOverrides(address: String): AppSettings? {
        val key = ringKey(address)
        return if (ringPrefs.contains("${key}preferL2cap")) loadFrom(ringPrefs, key) else null
    }

    fun saveRingOverrides(address: String, settings: AppSettings) =
        saveTo(ringPrefs, ringKey(address), settings)

    fun clearRingOverrides(address: String) {
        val key = ringKey(address)
        ringPrefs.edit().apply {
            ALL_KEYS.forEach { remove("$key$it") }
            apply()
        }
    }

    private fun ringKey(address: String): String = "${address}_"

    private fun loadFrom(prefs: SharedPreferences, prefix: String): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            preferL2capDownload = prefs.getBoolean("${prefix}preferL2cap", defaults.preferL2capDownload),
            use96MHzClock = prefs.getBoolean("${prefix}use96MHz", defaults.use96MHzClock),
            minRssi = prefs.getInt("${prefix}minRssi", defaults.minRssi),
            reconnectMaxAttempts = prefs.getInt("${prefix}reconnectMax", defaults.reconnectMaxAttempts),
            downloadBatchSize = prefs.getInt("${prefix}batchSize", defaults.downloadBatchSize),
            downloadStallTimeoutMs = prefs.getLong("${prefix}stallTimeout", defaults.downloadStallTimeoutMs),
            downloadFailsafeIntervalMs = prefs.getLong("${prefix}failsafeInterval", defaults.downloadFailsafeIntervalMs),
            skipFingerDetection = prefs.getBoolean("${prefix}skipFingerDet", defaults.skipFingerDetection),
            memfaultMinIntervalMs = prefs.getLong("${prefix}memfaultInterval", defaults.memfaultMinIntervalMs),
            autoReconnect = prefs.getBoolean("${prefix}autoReconnect", defaults.autoReconnect),
            fwStreamInterBlockDelayMs = prefs.getLong("${prefix}fwInterBlock", defaults.fwStreamInterBlockDelayMs),
            fwStreamDrainDelayMs = prefs.getLong("${prefix}fwDrainDelay", defaults.fwStreamDrainDelayMs),
        )
    }

    private fun saveTo(prefs: SharedPreferences, prefix: String, settings: AppSettings) {
        prefs.edit().apply {
            putBoolean("${prefix}preferL2cap", settings.preferL2capDownload)
            putBoolean("${prefix}use96MHz", settings.use96MHzClock)
            putInt("${prefix}minRssi", settings.minRssi)
            putInt("${prefix}reconnectMax", settings.reconnectMaxAttempts)
            putInt("${prefix}batchSize", settings.downloadBatchSize)
            putLong("${prefix}stallTimeout", settings.downloadStallTimeoutMs)
            putLong("${prefix}failsafeInterval", settings.downloadFailsafeIntervalMs)
            putBoolean("${prefix}skipFingerDet", settings.skipFingerDetection)
            putLong("${prefix}memfaultInterval", settings.memfaultMinIntervalMs)
            putBoolean("${prefix}autoReconnect", settings.autoReconnect)
            putLong("${prefix}fwInterBlock", settings.fwStreamInterBlockDelayMs)
            putLong("${prefix}fwDrainDelay", settings.fwStreamDrainDelayMs)
            apply()
        }
    }

    companion object {
        private val ALL_KEYS = listOf(
            "preferL2cap", "use96MHz", "minRssi", "reconnectMax", "batchSize",
            "stallTimeout", "failsafeInterval", "skipFingerDet",
            "memfaultInterval", "autoReconnect", "fwInterBlock", "fwDrainDelay",
        )
    }
}
