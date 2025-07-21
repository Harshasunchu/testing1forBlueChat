package com.example.testing1

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
        val isOnboardingCompleted = prefs.getBoolean("onboarding_completed", false)

        setContent {
            Testing1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val allPermissionsGranted by viewModel.allPermissionsGranted.collectAsState()

                    val startDestination = if (isOnboardingCompleted && allPermissionsGranted) {
                        "chat_screen"
                    } else {
                        "intro_screen"
                    }

                    NavHost(navController = navController, startDestination = startDestination) {
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
                                        prefs.edit { putBoolean("onboarding_completed", true) }
                                        viewModel.setPermissionsGranted(true)
                                        Intent(applicationContext, BluetoothService::class.java).also {
                                            startService(it)
                                        }
                                        navController.navigate("chat_screen") {
                                            popUpTo("intro_screen") { inclusive = true }
                                        }
                                    } else {
                                        navController.popBackStack()
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