package com.happyhealth.testapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FrameWriter"
private const val FOLDER_NAME = "BLE_HPY2_DATA"
private const val FRAME_SIZE = 4096L

class FrameWriter(private val context: Context) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var outputFile: File? = null
    var totalFramesWritten: Int = 0
        private set

    val filePath: String? get() = outputFile?.absolutePath

    fun ensureFileOpen(deviceId: String? = null) {
        if (outputFile != null) return

        val baseDir = context.getExternalFilesDir(null) ?: return
        val folder = if (deviceId != null) {
            File(File(baseDir, FOLDER_NAME), deviceId)
        } else {
            File(baseDir, FOLDER_NAME)
        }
        if (!folder.exists()) folder.mkdirs()

        val shortId = (deviceId?.removePrefix("HH_") ?: "unknown").lowercase()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "hh_${shortId}_${timestamp}.hpy2"
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

    fun discardFrames(count: Int) {
        if (count <= 0) return
        val file = outputFile ?: return

        scope.launch {
            val bytesToRemove = count * FRAME_SIZE
            val currentLength = file.length()
            if (bytesToRemove <= currentLength) {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(currentLength - bytesToRemove)
                }
                totalFramesWritten = maxOf(0, totalFramesWritten - count)
                Log.d(TAG, "Discarded $count frames ($bytesToRemove bytes), now $totalFramesWritten frames")
            }
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
