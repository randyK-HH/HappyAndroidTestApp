package com.happyhealth.testapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.happyhealth.bleplatform.api.ConnectionId

class TestCommandReceiver(
    private val viewModel: TestAppViewModel,
) : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.happyhealth.testapp.TEST_COMMAND"
    }

    private fun reply(msg: String) {
        resultCode = Activity.RESULT_OK
        resultData = msg
    }

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command") ?: run {
            viewModel.addLog(ConnectionId(-1), "[ADB] ERROR: missing 'command' extra")
            reply("ERROR: missing 'command' extra")
            return
        }

        val connIdValue = intent.getIntExtra("conn_id", 0)
        val connId = ConnectionId(connIdValue)

        when (command) {
            "scan_start" -> {
                viewModel.addLog(ConnectionId(-1), "[ADB] scan_start")
                if (!viewModel.isScanning.value) {
                    viewModel.toggleScan()
                }
                reply("OK scan_start")
            }

            "scan_stop" -> {
                viewModel.addLog(ConnectionId(-1), "[ADB] scan_stop")
                if (viewModel.isScanning.value) {
                    viewModel.toggleScan()
                }
                reply("OK scan_stop")
            }

            "connect" -> {
                val deviceName = intent.getStringExtra("device") ?: run {
                    viewModel.addLog(ConnectionId(-1), "[ADB] ERROR: connect requires 'device' extra")
                    reply("ERROR: connect requires 'device' extra")
                    return
                }
                viewModel.addLog(ConnectionId(-1), "[ADB] connect device=$deviceName")
                viewModel.connectByName(deviceName)
                reply("OK connect device=$deviceName")
            }

            "disconnect" -> {
                viewModel.addLog(connId, "[ADB] disconnect conn_id=$connIdValue")
                viewModel.disconnect(connId)
                reply("OK disconnect conn_id=$connIdValue")
            }

            "disconnect_all" -> {
                viewModel.addLog(ConnectionId(-1), "[ADB] disconnect_all")
                viewModel.disconnectAll()
                reply("OK disconnect_all")
            }

            "start_download" -> {
                viewModel.addLog(connId, "[ADB] start_download conn_id=$connIdValue")
                viewModel.startDownload(connId)
                reply("OK start_download conn_id=$connIdValue")
            }

            "stop_download" -> {
                viewModel.addLog(connId, "[ADB] stop_download conn_id=$connIdValue")
                viewModel.stopDownload(connId)
                reply("OK stop_download conn_id=$connIdValue")
            }

            "load_fw_image" -> {
                val path = intent.getStringExtra("path") ?: run {
                    viewModel.addLog(connId, "[ADB] ERROR: load_fw_image requires 'path' extra")
                    reply("ERROR: load_fw_image requires 'path' extra")
                    return
                }
                viewModel.addLog(connId, "[ADB] load_fw_image path=$path conn_id=$connIdValue")
                viewModel.loadFwImageFromPath(path, connId)
                reply("OK load_fw_image path=$path conn_id=$connIdValue")
            }

            "start_fw_update" -> {
                viewModel.addLog(connId, "[ADB] start_fw_update conn_id=$connIdValue")
                viewModel.startFwUpdate(connId)
                reply("OK start_fw_update conn_id=$connIdValue")
            }

            "cancel_fw_update" -> {
                viewModel.addLog(connId, "[ADB] cancel_fw_update conn_id=$connIdValue")
                viewModel.cancelFwUpdate(connId)
                reply("OK cancel_fw_update conn_id=$connIdValue")
            }

            "identify" -> {
                viewModel.addLog(connId, "[ADB] identify conn_id=$connIdValue")
                viewModel.identify(connId)
                reply("OK identify conn_id=$connIdValue")
            }

            "get_status" -> {
                viewModel.addLog(connId, "[ADB] get_status conn_id=$connIdValue")
                viewModel.getDeviceStatus(connId)
                reply("OK get_status conn_id=$connIdValue")
            }

            "assert" -> {
                viewModel.addLog(connId, "[ADB] assert conn_id=$connIdValue")
                viewModel.assert(connId)
                reply("OK assert conn_id=$connIdValue")
            }

            "start_daq" -> {
                viewModel.addLog(connId, "[ADB] start_daq conn_id=$connIdValue")
                viewModel.startDaq(connId)
                reply("OK start_daq conn_id=$connIdValue")
            }

            "stop_daq" -> {
                viewModel.addLog(connId, "[ADB] stop_daq conn_id=$connIdValue")
                viewModel.stopDaq(connId)
                reply("OK stop_daq conn_id=$connIdValue")
            }

            "get_daq_config" -> {
                viewModel.addLog(connId, "[ADB] get_daq_config conn_id=$connIdValue")
                viewModel.getDaqConfig(connId)
                reply("OK get_daq_config conn_id=$connIdValue")
            }

            "get_sync_frame" -> {
                viewModel.addLog(connId, "[ADB] get_sync_frame conn_id=$connIdValue")
                viewModel.getSyncFrame(connId)
                reply("OK get_sync_frame conn_id=$connIdValue")
            }

            "get_state" -> {
                val ring = viewModel.connectedRings.value[connIdValue]
                if (ring != null) {
                    val msg = buildString {
                        append("state=${ring.state}")
                        append(" name=${ring.name} addr=${ring.address}")
                        if (ring.isDownloading) append(" downloading=${ring.downloadProgress}/${ring.downloadTotal}")
                        if (ring.isFwUpdating) append(" fwUpdate=${ring.fwBlocksSent}/${ring.fwBlocksTotal}")
                    }
                    viewModel.addLog(connId, "[ADB] get_state conn_id=$connIdValue → $msg")
                    reply(msg)
                } else {
                    viewModel.addLog(connId, "[ADB] get_state conn_id=$connIdValue → no connection")
                    reply("no connection")
                }
            }

            "list_connections" -> {
                val rings = viewModel.connectedRings.value
                val msg = buildString {
                    append("${rings.size} connection(s)")
                    for ((id, ring) in rings) {
                        append(" | conn_id=$id name=${ring.name} state=${ring.state}")
                    }
                }
                viewModel.addLog(ConnectionId(-1), "[ADB] list_connections → $msg")
                reply(msg)
            }

            else -> {
                viewModel.addLog(ConnectionId(-1), "[ADB] ERROR: unknown command '$command'")
                reply("ERROR: unknown command '$command'")
            }
        }
    }
}
