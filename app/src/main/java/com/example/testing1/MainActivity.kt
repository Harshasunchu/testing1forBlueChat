// In file: app/src/main/java/com/example/testing1/MainActivity.kt

package com.example.testing1

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.testing1.bluetooth.BluetoothService
import com.example.testing1.ui.screens.*
import com.example.testing1.ui.theme.Testing1Theme
import com.example.testing1.viewmodels.BluetoothViewModel
import com.example.testing1.viewmodels.ViewModelFactory

class MainActivity : FragmentActivity() {

    private val viewModel: BluetoothViewModel by viewModels {
        ViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setContent {
            var themePreference by remember {
                mutableStateOf(prefs.getString(KEY_APP_THEME, "System") ?: "System")
            }

            Testing1Theme(themePreference = themePreference) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var appState by remember { mutableStateOf<AppState>(AppState.Loading) }

                    LaunchedEffect(Unit) {
                        val isBiometricLockEnabled = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
                        val biometricManager = BiometricManager.from(this@MainActivity)
                        val canAuth = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

                        appState = if (isBiometricLockEnabled && canAuth) {
                            AppState.NeedsAuthentication
                        } else {
                            AppState.Ready
                        }
                    }

                    when (appState) {
                        AppState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Loading...")
                            }
                        }
                        AppState.NeedsAuthentication -> {
                            BiometricAuthHandler(
                                activity = this@MainActivity,
                                onAuthSuccess = { appState = AppState.Ready },
                                onAuthFailed = {
                                    Toast.makeText(applicationContext, "Authentication failed.", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            )
                        }
                        AppState.Ready -> {
                            AppNavigation(
                                currentTheme = themePreference,
                                onThemeChanged = { newTheme -> themePreference = newTheme }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppNavigation(
        currentTheme: String,
        onThemeChanged: (String) -> Unit
    ) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val navController = rememberNavController()
        var startDestination by remember { mutableStateOf<String?>(null) }

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

        if (startDestination != null) {
            NavHost(navController = navController, startDestination = startDestination!!) {
                composable("intro_screen") {
                    IntroScreen(onGetStartedClick = { navController.navigate("permission_screen") })
                }
                composable("permission_screen") {
                    PermissionScreen(
                        onPermissionsResult = { isGranted ->
                            if (isGranted) {
                                prefs.edit { putBoolean("onboarding_completed", true) }
                                Intent(applicationContext, BluetoothService::class.java).also { startService(it) }
                                navController.navigate("chat_screen") {
                                    popUpTo("intro_screen") { inclusive = true }
                                    popUpTo("permission_screen") { inclusive = true }
                                }
                            } else {
                                finish()
                            }
                        }
                    )
                }
                composable("chat_screen") {
                    ChatScreen(
                        viewModel = viewModel,
                        onNavigateToSettings = { navController.navigate("settings_screen") }
                    )
                }
                composable("settings_screen") {
                    SettingsScreen(
                        currentTheme = currentTheme,
                        onThemeChanged = onThemeChanged,
                        onNavigateBack = { navController.popBackStack() }
                    )
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

@Composable
fun BiometricAuthHandler(
    activity: FragmentActivity,
    onAuthSuccess: () -> Unit,
    onAuthFailed: () -> Unit
) {
    LaunchedEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onAuthSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onAuthFailed()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Lock")
            .setSubtitle("Unlock to access your chat")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Please authenticate to continue")
    }
}

sealed class AppState {
    data object Loading : AppState()
    data object NeedsAuthentication : AppState()
    data object Ready : AppState()
}