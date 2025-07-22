// In file: app/src/main/java/com/example/testing1/ui/screens/SettingsScreen.kt

package com.example.testing1.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

// We define keys for our settings
const val PREFS_NAME = "app_prefs"
const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
const val KEY_APP_THEME = "app_theme"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: String,
    onThemeChanged: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var isBiometricEnabled by remember {
        mutableStateOf(prefs.getBoolean(KEY_BIOMETRIC_LOCK, false))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Biometric Lock Setting
            SettingsItem(
                title = "Biometric Lock",
                subtitle = "Secure the app with your fingerprint or face.",
                control = {
                    Switch(
                        checked = isBiometricEnabled,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                val biometricManager = BiometricManager.from(context)
                                when (biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
                                    BiometricManager.BIOMETRIC_SUCCESS -> {
                                        isBiometricEnabled = true
                                        prefs.edit { putBoolean(KEY_BIOMETRIC_LOCK, true) }
                                    }
                                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                                        Toast.makeText(context, "Please enroll a fingerprint or face in your device settings.", Toast.LENGTH_LONG).show()
                                    }
                                    else -> {
                                        Toast.makeText(context, "Biometric authentication is not available on this device.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                isBiometricEnabled = false
                                prefs.edit { putBoolean(KEY_BIOMETRIC_LOCK, false) }
                            }
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // App Theme Setting
            Text("App Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            ThemeSelector(
                selectedTheme = currentTheme,
                onThemeSelected = { newTheme ->
                    onThemeChanged(newTheme)
                    prefs.edit { putString(KEY_APP_THEME, newTheme) }
                }
            )
        }
    }
}

@Composable
fun SettingsItem(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(16.dp))
        control()
    }
}

@Composable
fun ThemeSelector(selectedTheme: String, onThemeSelected: (String) -> Unit) {
    val themes = listOf("Light", "Dark", "System")
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            themes.forEach { theme ->
                Row(
                    Modifier
                        .selectable(
                            selected = (theme == selectedTheme),
                            onClick = { onThemeSelected(theme) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (theme == selectedTheme),
                        onClick = { onThemeSelected(theme) }
                    )
                    Text(
                        text = theme,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}