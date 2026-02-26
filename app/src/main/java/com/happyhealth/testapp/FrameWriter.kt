package com.happyhealth.testapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FrameWriter"
private const val FOLDER_NAME = "BLE_HPY2_DATA"

class FrameWriter(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var outputFile: File? = null
    var totalFramesWritten: Int = 0
        private set

    val filePath: String? get() = outputFile?.absolutePath

    fun ensureFileOpen() {
        if (outputFile != null) return

        val baseDir = context.getExternalFilesDir(null) ?: return
        val folder = File(baseDir, FOLDER_NAME)
        if (!folder.exists()) folder.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "data_${timestamp}.hpy2"
        outputFile = File(folder, fileName)
        totalFramesWritten = 0

        Log.d(TAG, "Opened file: ${outputFile?.absolutePath}")
    }

    fun writeFrame(frameData: ByteArray) {
        ensureFileOpen()
        val file = outputFile ?: return

        scope.launch {
            file.appendBytes(frameData)
            totalFramesWritten++
        }
    }

    fun closeFile() {
        val file = outputFile ?: return
        Log.d(TAG, "Closed file: ${file.absolutePath} ($totalFramesWritten frames)")
        outputFile = null
    }

    fun destroy() {
        closeFile()
        scope.cancel()
    }
}
