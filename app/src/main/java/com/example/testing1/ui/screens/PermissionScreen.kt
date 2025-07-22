package com.example.testing1.ui.screens

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Renamed for clarity
private enum class OnboardingStep {
    REQUESTING_PERMISSIONS,
    ENABLING_BLUETOOTH,
    FINISHED
}

@Composable
fun PermissionScreen(onPermissionsResult: (Boolean) -> Unit) {
    val context = LocalContext.current
    val bluetoothAdapter: BluetoothAdapter? = remember {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // The flow now starts by requesting permissions
    var currentStep by remember { mutableStateOf(OnboardingStep.REQUESTING_PERMISSIONS) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Permissions are granted, now let's check if Bluetooth is enabled.
            currentStep = OnboardingStep.ENABLING_BLUETOOTH
        } else {
            // Permissions were denied, the flow fails.
            onPermissionsResult(false)
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            currentStep = OnboardingStep.FINISHED
            onPermissionsResult(true) // Success!
        } else {
            // User did not enable Bluetooth.
            onPermissionsResult(false)
        }
    }

    LaunchedEffect(currentStep) {
        when (currentStep) {
            OnboardingStep.REQUESTING_PERMISSIONS -> {
                // This step is now just for showing the rationale.
                // The button click will trigger the permission launch.
            }
            OnboardingStep.ENABLING_BLUETOOTH -> {
                if (bluetoothAdapter?.isEnabled == true) {
                    // If Bluetooth is already on, we're done.
                    currentStep = OnboardingStep.FINISHED
                    onPermissionsResult(true)
                }
                // Otherwise, the UI will be displayed to ask the user.
            }
            OnboardingStep.FINISHED -> {
                // The success callback has already been called.
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(targetState = currentStep, label = "OnboardingStepAnimation") { step ->
            when (step) {
                OnboardingStep.REQUESTING_PERMISSIONS -> {
                    PermissionRationaleStep {
                        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            mutableListOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_ADVERTISE,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ).apply {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        } else {
                            listOf(
                                Manifest.permission.BLUETOOTH,
                                Manifest.permission.BLUETOOTH_ADMIN,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        }
                        permissionLauncher.launch(requiredPermissions.toTypedArray())
                    }
                }
                OnboardingStep.ENABLING_BLUETOOTH -> {
                    BluetoothEnableStep {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                }
                OnboardingStep.FINISHED -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

// New composable for the permission rationale step
@Composable
private fun PermissionRationaleStep(onRequestClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Shield, "Permissions Icon", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Permissions Required", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "To discover and connect to nearby devices, this app needs Bluetooth and Location permissions.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRequestClick) {
            Text("Grant Permissions")
        }
    }
}

// Renamed the original BluetoothStateStep
@Composable
private fun BluetoothEnableStep(onEnableClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Bluetooth, "Bluetooth Icon", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Enable Bluetooth", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "This app requires Bluetooth to be enabled to connect with other devices.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onEnableClick) {
            Text("Enable Bluetooth")
        }
    }
}