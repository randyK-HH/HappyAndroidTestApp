package com.happyhealth.testapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.happyhealth.bleplatform.api.ConnectionId
import com.happyhealth.testapp.ui.ConnectedScreen
import com.happyhealth.testapp.ui.ScanScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppContent()
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun AppContent(viewModel: TestAppViewModel = viewModel()) {
    var selectedConnId by remember { mutableStateOf<ConnectionId?>(null) }

    // Observe ADB-driven navigation requests
    val adbNavTarget by viewModel.adbNavigateToConn.collectAsState()
    LaunchedEffect(adbNavTarget) {
        adbNavTarget?.let {
            selectedConnId = it
            viewModel.clearAdbNavigation()
        }
    }

    if (selectedConnId != null) {
        ConnectedScreen(
            connId = selectedConnId!!,
            viewModel = viewModel,
            onBack = { selectedConnId = null },
        )
    } else {
        ScanScreen(
            viewModel = viewModel,
            onRingSelected = { connId -> selectedConnId = connId },
        )
    }
}
