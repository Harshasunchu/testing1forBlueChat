package com.example.testing1

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.testing1.bluetooth.BluetoothService
import com.example.testing1.ui.screens.ChatScreen
import com.example.testing1.ui.screens.IntroScreen
import com.example.testing1.ui.screens.PermissionScreen
import com.example.testing1.ui.theme.Testing1Theme
import com.example.testing1.viewmodels.BluetoothViewModel
import com.example.testing1.viewmodels.ViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: BluetoothViewModel by viewModels {
        ViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        setContent {
            Testing1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var startDestination by remember { mutableStateOf<String?>(null) }

                    // This LaunchedEffect will run once and determine the correct starting screen
                    LaunchedEffect(Unit) {
                        val isOnboardingCompleted = prefs.getBoolean("onboarding_completed", false)
                        val allPermissionsGranted = checkAllPermissions()

                        startDestination = if (!isOnboardingCompleted) {
                            "intro_screen"
                        } else if (!allPermissionsGranted) {
                            "permission_screen"
                        } else {
                            "chat_screen"
                        }
                    }

                    // The NavHost is only composed once startDestination is determined
                    if (startDestination != null) {
                        NavHost(navController = navController, startDestination = startDestination!!) {
                            composable("intro_screen") {
                                IntroScreen(
                                    onGetStartedClick = {
                                        navController.navigate("permission_screen")
                                    }
                                )
                            }
                            composable("permission_screen") {
                                PermissionScreen(
                                    onPermissionsResult = { isGranted ->
                                        if (isGranted) {
                                            // Set onboarding as complete ONLY after permissions are granted
                                            prefs.edit { putBoolean("onboarding_completed", true) }
                                            viewModel.setPermissionsGranted(true)
                                            Intent(applicationContext, BluetoothService::class.java).also {
                                                startService(it)
                                            }
                                            navController.navigate("chat_screen") {
                                                // Clear the back stack so the user can't go back to the intro/permission screens
                                                popUpTo("intro_screen") { inclusive = true }
                                                popUpTo("permission_screen") { inclusive = true }
                                            }
                                        } else {
                                            // Handle the case where permissions are denied
                                            // You could show a dialog explaining why the app needs permissions,
                                            // or simply close the app.
                                            finish() // For now, we'll just close the app
                                        }
                                    }
                                )
                            }
                            composable("chat_screen") {
                                ChatScreen(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAllPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mutableListOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            listOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}