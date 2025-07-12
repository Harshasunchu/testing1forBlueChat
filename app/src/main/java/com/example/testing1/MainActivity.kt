package com.example.testing1

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.testing1.ui.theme.Testing1Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.text.style.TextAlign // Import TextAlign

// Configuration object for app-level settings
object BluetoothConfig {
    const val SERVICE_NAME = "BluetoothChat"
    // Standard SPP UUID for Bluetooth serial communication. Both devices must use this UUID.
    val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val CONNECTION_TIMEOUT = 10000L // 10 seconds for connection attempts
    const val MAX_MESSAGES = 500 // Max messages to keep in chat history
    const val DISCOVERY_TIMEOUT = 30000L // 30 seconds for device discovery
    const val RECONNECTION_DELAY = 2000L // 2 seconds delay before attempting reconnection
}

// Enhanced error handling enum for clearer error states
enum class BluetoothError {
    PERMISSION_DENIED,
    BLUETOOTH_DISABLED,
    LOCATION_PERMISSION_REQUIRED,
    CONNECTION_FAILED,
    DISCOVERY_FAILED,
    SEND_FAILED,
    UNKNOWN_ERROR
}

// Data class for a chat message
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(), // Unique ID for each message
    val content: String, // The message text
    val isFromMe: Boolean, // True if sent by this device, false if received
    val timestamp: Long = System.currentTimeMillis(), // Timestamp of the message
    val deviceAddress: String = "" // Address of the device involved in the message
)

// Connection state enum
enum class ConnectionState {
    DISCONNECTED, // Not connected to any device
    CONNECTING,   // Attempting to connect to a device
    CONNECTED,    // Successfully connected to a device
    LISTENING     // Acting as a server, waiting for incoming connections
}

// Connection mode enum to track if the device is acting as server or client
enum class ConnectionMode {
    NONE,   // No active connection role
    SERVER, // Device is acting as a server
    CLIENT  // Device is acting as a client
}

// Interface for Bluetooth operations, enhancing testability and separation of concerns
interface BluetoothRepository {
    val connectionState: StateFlow<ConnectionState> // Current connection status
    val messages: StateFlow<List<ChatMessage>>      // List of chat messages
    val discoveredDevices: StateFlow<List<BluetoothDevice>> // List of discovered Bluetooth devices
    val errorState: StateFlow<BluetoothError?>      // Current error state
    val isDiscovering: StateFlow<Boolean>           // True if device discovery is active

    suspend fun startServer() // Starts listening for incoming connections
    suspend fun connectToDevice(device: BluetoothDevice) // Initiates connection to a specific device
    suspend fun sendMessage(message: String) // Sends a message to the connected device
    fun startDiscovery() // Starts scanning for nearby Bluetooth devices
    fun stopDiscovery() // Stops the ongoing device discovery
    fun addDiscoveredDevice(device: BluetoothDevice) // Adds a discovered device to the list
    fun clearError() // Clears the current error state
    fun stop() // Stops all Bluetooth operations and cleans up resources
    fun setError(error: BluetoothError) // Sets an error state
    fun getDeviceNameSafe(device: BluetoothDevice?): String // Safely gets the device name
}

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothService: BluetoothRepository? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob()) // Main scope for UI updates

    // BroadcastReceiver for Bluetooth device discovery events
    private val deviceDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    // Get the discovered BluetoothDevice object
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    device?.let {
                        // Check BLUETOOTH_CONNECT permission to access device name
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            // Only add devices with a valid (non-null and non-blank) name for better UX.
                            // This helps filter out some non-chat devices or those not properly advertising.
                            if (it.name != null && it.name.isNotBlank()) {
                                Log.d("BluetoothDiscovery", "Found device: ${bluetoothService?.getDeviceNameSafe(it)} (${it.address})")
                                bluetoothService?.addDiscoveredDevice(it)
                            } else {
                                Log.d("BluetoothDiscovery", "Found unnamed device: (${it.address}) - Skipping for display.")
                            }
                        } else {
                            Log.w("BluetoothDiscovery", "BLUETOOTH_CONNECT permission not granted, cannot get device name. Adding device by address.")
                            bluetoothService?.addDiscoveredDevice(it) // Add by address if name isn't accessible
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d("BluetoothDiscovery", "Discovery finished.")
                    // The BluetoothService will update its `isDiscovering` state internally
                }
            }
        }
    }

    // ActivityResultLauncher for requesting multiple permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d("Permissions", "All required Bluetooth permissions granted.")
            initializeBluetooth() // Initialize Bluetooth components
            // After permissions are granted, if BluetoothService is ready, start server automatically
            bluetoothService?.let { service ->
                scope.launch {
                    // Only start server if currently disconnected, to avoid interrupting existing connections
                    if (service.connectionState.value == ConnectionState.DISCONNECTED) {
                        service.startServer()
                    }
                }
            }
        } else {
            Log.e("Permissions", "Not all Bluetooth permissions granted.")
            // Determine the specific error based on missing permissions
            if (
                (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && permissions[Manifest.permission.ACCESS_FINE_LOCATION] == false) ||
                (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == false)
            )
            {
                bluetoothService?.setError(BluetoothError.LOCATION_PERMISSION_REQUIRED)
            } else {
                bluetoothService?.setError(BluetoothError.PERMISSION_DENIED)
            }
        }
    }

    // ActivityResultLauncher for enabling Bluetooth
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d("Bluetooth", "Bluetooth enabled by user.")
            requestBluetoothPermissions() // Proceed to request other necessary permissions
        } else {
            Log.e("Bluetooth", "Bluetooth not enabled by user.")
            bluetoothService?.setError(BluetoothError.BLUETOOTH_DISABLED) // Inform user about disabled Bluetooth
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
            ?: run {
                // Handle case where Bluetooth is not available on the device
                Log.e("Bluetooth", "Bluetooth adapter not available on this device.")
                setContent {
                    Testing1Theme {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("Bluetooth is not supported on this device.")
                            }
                        }
                    }
                }
                return // Exit onCreate if Bluetooth is not available
            }

        // Initialize the BluetoothService
        bluetoothService = BluetoothService(bluetoothAdapter, applicationContext)

        // Start the permission request flow
        requestBluetoothPermissions()

        setContent {
            Testing1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    bluetoothService?.let { service ->
                        BluetoothChatScreen(service) // Display the chat UI if service is available
                    } ?: run {
                        // Display an error message if BluetoothService somehow isn't initialized
                        Box(contentAlignment = Alignment.Center) {
                            Text("Bluetooth Service not available. Please ensure Bluetooth is enabled and permissions are granted.")
                        }
                    }
                }
            }
            // LaunchedEffect to automatically start server on initial launch if permissions are already granted
            LaunchedEffect(Unit) {
                if (bluetoothService != null && bluetoothService!!.connectionState.value == ConnectionState.DISCONNECTED && hasAllBluetoothPermissions()) {
                    Log.d("MainActivity", "Permissions already granted, attempting to auto-start server.")
                    bluetoothService?.startServer()
                }
            }
        }
    }

    // Function to request necessary Bluetooth permissions
    private fun requestBluetoothPermissions() {
        // First, check if Bluetooth is enabled. If not, request to enable it.
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
            return
        }

        // Define required permissions based on Android version
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            @Suppress("DEPRECATION")
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            @Suppress("DEPRECATION")
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION) // Required for discovery on older Android versions
        }

        // Check for missing permissions
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        // Request missing permissions if any
        if (missingPermissions.isNotEmpty()) {
            Log.d("Permissions", "Requesting missing permissions: $missingPermissions")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d("Permissions", "All required Bluetooth permissions already granted.")
            initializeBluetooth() // Proceed with Bluetooth initialization
            // If permissions are already granted, also attempt to auto-start server
            bluetoothService?.let { service ->
                scope.launch {
                    if (service.connectionState.value == ConnectionState.DISCONNECTED) {
                        service.startServer()
                    }
                }
            }
        }
    }

    // Helper function to check if all necessary Bluetooth permissions are granted
    private fun hasAllBluetoothPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Initializes Bluetooth components and registers the discovery receiver
    private fun initializeBluetooth() {
        Log.d("BluetoothInit", "Bluetooth initialized, registering discovery receiver.")
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Register receiver with appropriate flags for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceDiscoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(deviceDiscoveryReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService?.stop() // Stop all Bluetooth operations and clean up resources
        scope.cancel() // Cancel the coroutine scope
        try {
            unregisterReceiver(deviceDiscoveryReceiver) // Unregister the receiver
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Receiver not registered or already unregistered", e)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun BluetoothChatScreen(service: BluetoothRepository) {
        // Collect states from the BluetoothRepository
        val connectionState by service.connectionState.collectAsState()
        val messages by service.messages.collectAsState()
        val discoveredDevices by service.discoveredDevices.collectAsState()
        val errorState by service.errorState.collectAsState()
        val isDiscovering by service.isDiscovering.collectAsState()

        var currentMessage by remember { mutableStateOf("") } // State for the message input field

        val coroutineScope = rememberCoroutineScope() // Coroutine scope for UI-related suspend functions
        val listState = rememberLazyListState() // State for LazyColumn scrolling

        // Scroll to the latest message when new messages arrive
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Error Display Card
            errorState?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getErrorMessage(error),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = { service.clearError() } // Dismiss button to clear error
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp)) // Increased spacing after error
            }

            // Connection Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        ConnectionState.CONNECTED -> Color(0xFFE8F5E9) // Light Green for Connected
                        ConnectionState.CONNECTING -> Color(0xFFFFFDE7) // Light Yellow for Connecting
                        ConnectionState.DISCONNECTED -> Color(0xFFFFEBEE) // Light Red for Disconnected
                        ConnectionState.LISTENING -> Color(0xFFE3F2FD) // Light Blue for Listening
                    }
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Connection Status",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when (connectionState) {
                            ConnectionState.DISCONNECTED -> "Disconnected. Tap 'Start Server' or 'Scan Devices'."
                            ConnectionState.CONNECTING -> "Connecting..."
                            ConnectionState.CONNECTED -> "Connected!"
                            ConnectionState.LISTENING -> "Listening for connections..."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = when (connectionState) {
                            ConnectionState.CONNECTED -> Color(0xFF2E7D32) // Dark Green
                            ConnectionState.CONNECTING -> Color(0xFFF9A825) // Dark Yellow
                            ConnectionState.DISCONNECTED -> Color(0xFFD32F2F) // Dark Red
                            ConnectionState.LISTENING -> Color(0xFF1976D2) // Dark Blue
                        }
                    )
                    if (connectionState == ConnectionState.CONNECTING) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons for server control (still available for manual override/restart)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    // Only allow starting server if not already listening or connected
                                    if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.CONNECTED) {
                                        service.startServer()
                                    }
                                }
                            },
                            // Enable only if disconnected or already connected (to allow restarting server)
                            enabled = connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.CONNECTED
                        ) {
                            Text("Start Server")
                        }
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    service.stop() // Stop all Bluetooth operations
                                }
                            },
                            enabled = connectionState != ConnectionState.DISCONNECTED // Enable if not already disconnected
                        ) {
                            Text("Stop All")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Device Discovery Section (only visible when not connected)
            if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.LISTENING) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Discover & Connect",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            FilledTonalButton(
                                onClick = {
                                    if (isDiscovering) {
                                        service.stopDiscovery()
                                    } else {
                                        service.startDiscovery()
                                    }
                                },
                                enabled = true // Always allow starting/stopping scan
                            ) {
                                Text(if (isDiscovering) "Stop Scan" else "Scan Devices")
                            }
                        }

                        if (isDiscovering) {
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Scanning for devices...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (discoveredDevices.isNotEmpty()) {
                            Text(
                                text = "Available Devices:",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) { // Increased max height for device list
                                items(discoveredDevices, key = { it.address }) { device ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp), // Increased vertical padding
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = service.getDeviceNameSafe(device),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = device.address,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray
                                            )
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    service.connectToDevice(device)
                                                }
                                            },
                                            // The parent `if` block already ensures this condition is met.
                                            // Thus, this `enabled` condition is always true when this button is visible.
                                            enabled = true
                                        ) {
                                            Text("Connect")
                                        }
                                    }
                                    HorizontalDivider() // Separator between devices
                                }
                            }
                        } else if (!isDiscovering) {
                            Text(
                                text = "No devices found. Tap 'Scan Devices' to search.",
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Messages Area
            Text(
                text = "Messages",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                modifier = Modifier.weight(1f), // Takes remaining vertical space
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (messages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (connectionState == ConnectionState.CONNECTED) "No messages yet. Start typing!" else "Connect to a device to start chatting.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray,
                                textAlign = TextAlign.Center // Removed redundant qualifier
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            reverseLayout = false // Display messages from top to bottom
                        ) {
                            items(
                                items = messages,
                                key = { it.id } // Use message ID as key for efficient recomposition
                            ) { message ->
                                MessageBubble(message)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Message Input Area (only visible when connected)
            if (connectionState == ConnectionState.CONNECTED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = currentMessage,
                        onValueChange = { currentMessage = it },
                        label = { Text("Type your message...") },
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        shape = MaterialTheme.shapes.medium // Apply rounded corners
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = {
                            if (currentMessage.isNotBlank()) {
                                coroutineScope.launch {
                                    service.sendMessage(currentMessage)
                                    currentMessage = "" // Clear input field after sending
                                }
                            }
                        },
                        enabled = currentMessage.isNotBlank() // Enable send button only if message is not blank
                    ) {
                        Text("Send")
                    }
                }
            } else {
                Text(
                    text = "You need to be connected to send messages.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center // Removed redundant qualifier
                )
            }
        }
    }

    // Helper function to get user-friendly error messages
    private fun getErrorMessage(error: BluetoothError): String {
        return when (error) {
            BluetoothError.PERMISSION_DENIED -> "Bluetooth permissions are required for this app to function. Please grant them in settings."
            BluetoothError.BLUETOOTH_DISABLED -> "Bluetooth is disabled. Please enable it to use chat features."
            BluetoothError.LOCATION_PERMISSION_REQUIRED -> "Location permission is required for Bluetooth device discovery on your Android version. Please grant it."
            BluetoothError.CONNECTION_FAILED -> "Failed to connect to the device. Ensure the other device is in server mode or try again."
            BluetoothError.DISCOVERY_FAILED -> "Device discovery failed or no devices were found. Ensure other device is discoverable."
            BluetoothError.SEND_FAILED -> "Failed to send the message. Check connection."
            BluetoothError.UNKNOWN_ERROR -> "An unknown Bluetooth error occurred."
        }
    }

    @Composable
    fun MessageBubble(message: ChatMessage) {
        val alignment = if (message.isFromMe) Alignment.End else Alignment.Start // Align message bubble based on sender
        val backgroundColor = if (message.isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
        val textColor = if (message.isFromMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = backgroundColor),
                modifier = Modifier.widthIn(max = 300.dp), // Max width for message bubble
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), // Added slight elevation
                shape = MaterialTheme.shapes.medium // Apply rounded corners
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = message.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.End) // Align timestamp to the end
                    )
                }
            }
        }
    }
}

// BluetoothService implementation handling all Bluetooth logic
class BluetoothService(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : BluetoothRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // IO scope for Bluetooth operations
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var currentDeviceAddress: String? = null // Stores the address of the currently connected device

    // Use the predefined APP_UUID from BluetoothConfig
    private val appUuid: UUID = BluetoothConfig.APP_UUID

    // StateFlows to expose Bluetooth states to the UI
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _errorState = MutableStateFlow<BluetoothError?>(null)
    override val errorState: StateFlow<BluetoothError?> = _errorState.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    // Jobs for managing coroutine lifecycles
    private var discoveryTimeoutJob: Job? = null
    private var reconnectionJob: Job? = null
    private var messageListenerJob: Job? = null

    private var connectionMode: ConnectionMode = ConnectionMode.NONE // Tracks current connection role
    // private val pendingMessages = mutableMapOf<String, ChatMessage>() // Removed, as messages are added to UI immediately

    override suspend fun startServer() = withContext(Dispatchers.IO) {
        // Prevent starting server if already in a connection state
        if (_connectionState.value == ConnectionState.LISTENING || _connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING) {
            Log.w("BluetoothService", "Server start requested while already listening, connecting, or connected. Current state: ${_connectionState.value}")
            return@withContext
        }
        stopCurrentConnection() // Ensure any previous connection/server is stopped cleanly
        stopDiscovery() // Stop discovery before starting server to avoid conflicts

        connectionMode = ConnectionMode.SERVER // Set role to server
        updateConnectionState(ConnectionState.LISTENING) // Update UI state
        clearError() // Clear any previous errors

        // Check for necessary permissions
        if (!hasRequiredPermissions()) {
            Log.e("BluetoothService", "BLUETOOTH_CONNECT permission missing for server.")
            setError(BluetoothError.PERMISSION_DENIED)
            updateConnectionState(ConnectionState.DISCONNECTED)
            return@withContext
        }

        Log.d("BluetoothService", "Starting server. Service Name: ${BluetoothConfig.SERVICE_NAME}, UUID: $appUuid.")

        try {
            serverSocket?.close() // Close any existing server socket just in case
            // Create a listening RFCOMM Bluetooth socket
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                BluetoothConfig.SERVICE_NAME,
                appUuid
            )

            Log.d("BluetoothService", "Server socket listening. Waiting for a connection...")

            // Launch a new coroutine to accept connections to avoid blocking the calling thread.
            // This allows startServer to return quickly while the server waits in the background.
            scope.launch(Dispatchers.IO) {
                try {
                    // Blocking call that waits for a client to connect or times out
                    val acceptedSocket = serverSocket?.accept(BluetoothConfig.CONNECTION_TIMEOUT.toInt())

                    acceptedSocket?.let { socket ->
                        Log.d("BluetoothService", "Connection accepted from: ${socket.remoteDevice.address}")
                        this@BluetoothService.clientSocket = socket // Store the client socket
                        currentDeviceAddress = socket.remoteDevice.address
                        setupStreams(socket) // Set up input/output streams
                        updateConnectionState(ConnectionState.CONNECTED) // Update UI state to connected
                        startMessageListener() // Start listening for messages from the connected client
                    } ?: run {
                        Log.d("BluetoothService", "Server socket accept timed out or returned null, no connection made.")
                        // If no connection was made and we were still listening, set connection failed
                        if (_connectionState.value == ConnectionState.LISTENING) {
                            setError(BluetoothError.CONNECTION_FAILED)
                            updateConnectionState(ConnectionState.DISCONNECTED) // Revert state if no connection
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e("BluetoothService", "SecurityException during server accept. Check BLUETOOTH_CONNECT (Android 12+) or BLUETOOTH permissions.", e)
                    setError(BluetoothError.PERMISSION_DENIED)
                    updateConnectionState(ConnectionState.DISCONNECTED)
                } catch (e: IOException) {
                    // This can happen if the socket is closed from another thread (e.g., stopCurrentConnection)
                    // or if Bluetooth is turned off. Check if the coroutine is still active to distinguish
                    // between expected shutdown and unexpected error.
                    if (isActive) {
                        Log.e("BluetoothService", "IOException during server accept (e.g., port in use, BT off, or socket closed): ${e.message}", e)
                        setError(BluetoothError.CONNECTION_FAILED)
                        updateConnectionState(ConnectionState.DISCONNECTED)
                    } else {
                        Log.d("BluetoothService", "IOException during server accept, but coroutine is inactive. Likely normal shutdown.")
                    }
                } catch (e: Exception) {
                    Log.e("BluetoothService", "Unexpected exception during server accept: ${e.message}", e)
                    setError(BluetoothError.UNKNOWN_ERROR)
                    updateConnectionState(ConnectionState.DISCONNECTED)
                } finally {
                    // Only close server socket if not connected. If a connection was made, the server socket
                    // is typically no longer needed and can be closed, as only one client is handled at a time.
                    if (_connectionState.value != ConnectionState.CONNECTED) {
                        try {
                            serverSocket?.close()
                            serverSocket = null
                            Log.d("BluetoothService", "Server socket closed in finally block (no connection made).")
                        } catch (e: IOException) {
                            Log.e("BluetoothService", "Error closing server socket in finally block: ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("BluetoothService", "SecurityException creating server socket. Check BLUETOOTH_CONNECT (Android 12+) or BLUETOOTH permissions.", e)
            setError(BluetoothError.PERMISSION_DENIED)
            updateConnectionState(ConnectionState.DISCONNECTED)
        } catch (e: IOException) {
            Log.e("BluetoothService", "IOException creating server socket (e.g., Bluetooth turned off or already listening): ${e.message}", e)
            setError(BluetoothError.CONNECTION_FAILED)
            updateConnectionState(ConnectionState.DISCONNECTED)
        }
    }

    override suspend fun connectToDevice(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        // Prevent connection attempt if already in a connection state
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.LISTENING) {
            Log.w("BluetoothService", "Connection attempt ignored, already connecting, connected, or listening. Current state: ${_connectionState.value}")
            return@withContext
        }
        stopCurrentConnection() // Stop any previous server or connection attempts
        stopDiscovery() // Always stop discovery first before connecting
        delay(100) // Small delay to ensure discovery is fully stopped

        connectionMode = ConnectionMode.CLIENT // Set role to client
        updateConnectionState(ConnectionState.CONNECTING) // Update UI state
        _discoveredDevices.value = emptyList() // Clear discovered devices on connection attempt
        clearError() // Clear any previous errors
        Log.d("BluetoothService", "Attempting to connect to device: ${device.address} - ${getDeviceNameSafe(device)}")

        // Check for necessary permissions
        if (!hasRequiredPermissions()) {
            Log.e("BluetoothService", "BLUETOOTH_CONNECT permission missing for connect.")
            setError(BluetoothError.PERMISSION_DENIED)
            stopCurrentConnection() // Ensure state is reset
            return@withContext
        }

        try {
            // Create an RFCOMM Bluetooth socket to connect to the service
            val socket = device.createRfcommSocketToServiceRecord(appUuid)
            this@BluetoothService.clientSocket = socket // Store the client socket

            // Attempt to connect with a timeout
            withTimeout(BluetoothConfig.CONNECTION_TIMEOUT) {
                socket.connect() // Blocking call, connects to the remote device
            }

            Log.d("BluetoothService", "Successfully connected to ${device.address}")
            currentDeviceAddress = device.address
            setupStreams(socket) // Set up input/output streams
            updateConnectionState(ConnectionState.CONNECTED) // Update UI state to connected
            startMessageListener() // Start listening for incoming messages
        } catch (e: SecurityException) {
            Log.e("BluetoothService", "SecurityException during connect for ${getDeviceNameSafe(device)}: ${e.message}", e)
            setError(BluetoothError.PERMISSION_DENIED)
            stopCurrentConnection()
        } catch (e: TimeoutCancellationException) {
            Log.e("BluetoothService", "Connection timeout for ${getDeviceNameSafe(device)}", e)
            setError(BluetoothError.CONNECTION_FAILED)
            stopCurrentConnection()
        } catch (e: IOException) {
            Log.e("BluetoothService", "IOException during connect for ${getDeviceNameSafe(device)}: ${e.message}", e)
            setError(BluetoothError.CONNECTION_FAILED)
            stopCurrentConnection()
        } catch (e: Exception) {
            Log.e("BluetoothService", "Unexpected exception during connect for ${getDeviceNameSafe(device)}: ${e.message}", e)
            setError(BluetoothError.UNKNOWN_ERROR)
            stopCurrentConnection()
        }
    }

    // Safely retrieves the device name, handling permissions and null/blank names
    override fun getDeviceNameSafe(device: BluetoothDevice?): String {
        device ?: return "Unknown Device" // Return "Unknown Device" if device is null
        return try {
            // Check BLUETOOTH_CONNECT permission before accessing device.name
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return device.address // Return address if permission is not granted
            }
            val name = device.name
            if (name.isNullOrBlank()) device.address else name // Return name if available, else address
        } catch (e: SecurityException) {
            Log.e("BluetoothService", "SecurityException accessing device name: ${e.message}", e) // Log the exception
            device.address // Fallback to address if SecurityException occurs
        }
    }

    // Checks if all required Bluetooth permissions are granted
    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Sets up input and output streams for the connected socket
    private fun setupStreams(socket: BluetoothSocket) {
        try {
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            Log.d("BluetoothService", "Streams obtained successfully.")
        } catch (e: IOException) {
            Log.e("BluetoothService", "Error obtaining streams", e)
            setError(BluetoothError.CONNECTION_FAILED)
            stopCurrentConnection() // Stop connection if streams cannot be obtained
        }
    }

    // Starts a coroutine to continuously listen for incoming messages
    private fun startMessageListener() {
        messageListenerJob?.cancel() // Cancel any existing listener before starting a new one
        messageListenerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024) // Buffer for reading incoming bytes
            var numBytes: Int
            Log.d("BluetoothService", "Message listener started for socket: ${clientSocket?.remoteDevice?.address}")

            // Loop while the coroutine is active and connection is established
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                try {
                    val socket = clientSocket
                    // Break loop if socket is null or disconnected
                    if (socket == null || !socket.isConnected) {
                        Log.d("BluetoothService", "Socket disconnected, stopping listener")
                        break
                    }

                    numBytes = inputStream?.read(buffer) ?: -1 // Read bytes from input stream
                    if (numBytes > 0) {
                        val receivedMessage = String(buffer, 0, numBytes) // Convert bytes to string
                        Log.d("BluetoothService", "Received: $receivedMessage from ${clientSocket?.remoteDevice?.address}")
                        addMessage( // Add received message to the chat history
                            ChatMessage(
                                content = receivedMessage,
                                isFromMe = false, // Message is from the other device
                                deviceAddress = clientSocket?.remoteDevice?.address ?: "Unknown"
                            )
                        )
                    } else if (numBytes == -1) {
                        // -1 indicates end of stream, usually meaning the remote device disconnected
                        Log.d("BluetoothService", "Input stream ended (read -1), remote likely disconnected.")
                        handleConnectionLoss() // Handle connection loss
                        break
                    }
                } catch (e: IOException) {
                    // IOException during read usually means connection loss
                    if (isActive) {
                        Log.e("BluetoothService", "IOException while reading from input stream: ${e.message}. Connection likely lost.", e)
                        handleConnectionLoss()
                    }
                    break
                } catch (e: Exception) {
                    Log.e("BluetoothService", "Generic exception in message listener: ${e.message}", e)
                    setError(BluetoothError.UNKNOWN_ERROR)
                    stopCurrentConnection()
                    break
                }
            }
            Log.d("BluetoothService", "Message listener stopped.")
        }
    }

    // Handles connection loss, attempting reconnection or server restart
    private fun handleConnectionLoss() {
        scope.launch(Dispatchers.Main) { // Switch to Main dispatcher for UI updates
            setError(BluetoothError.CONNECTION_FAILED) // Set connection failed error
            stopCurrentConnection() // Clean up current connection resources

            // If client, attempt reconnection after a delay
            if (connectionMode == ConnectionMode.CLIENT && currentDeviceAddress != null) {
                reconnectionJob?.cancel() // Cancel any previous reconnection attempt
                reconnectionJob = scope.launch {
                    Log.d("BluetoothService", "Attempting reconnection in ${BluetoothConfig.RECONNECTION_DELAY / 1000} seconds...")
                    delay(BluetoothConfig.RECONNECTION_DELAY) // Wait before retry
                    // Find the last connected device in the discovered list
                    val lastDevice = _discoveredDevices.value.find { it.address == currentDeviceAddress }
                    lastDevice?.let { device ->
                        Log.d("BluetoothService", "Retrying connection to ${device.address}")
                        connectToDevice(device) // Attempt to reconnect
                    } ?: run {
                        Log.w("BluetoothService", "Could not find last connected device in discovered list for reconnection.")
                    }
                }
            }
            // If server, automatically restart listening for new connections
            else if (connectionMode == ConnectionMode.SERVER) {
                Log.d("BluetoothService", "Server connection lost, attempting to restart server...")
                scope.launch {
                    startServer() // Automatically restart server
                }
            }
        }
    }

    // Sends a message to the connected device
    override suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        // Ensure there is an active connection before sending
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w("BluetoothService", "Cannot send message: Not connected.")
            setError(BluetoothError.SEND_FAILED)
            return@withContext
        }

        val chatMessage = ChatMessage(
            content = message,
            isFromMe = true, // Message is from this device
            deviceAddress = currentDeviceAddress ?: "Unknown"
        )

        try {
            outputStream?.let { stream ->
                val bytes = message.toByteArray()
                stream.write(bytes) // Write message bytes to output stream
                stream.flush() // Ensure message is sent immediately

                addMessage(chatMessage) // Add the sent message to the UI immediately for good UX

                Log.d("BluetoothService", "Sent: $message to $currentDeviceAddress")
            } ?: run {
                Log.e("BluetoothService", "OutputStream is null, cannot send message.")
                setError(BluetoothError.SEND_FAILED)
                stopCurrentConnection()
            }
        } catch (e: IOException) {
            Log.e("BluetoothService", "IOException during message send: ${e.message}", e)
            setError(BluetoothError.SEND_FAILED)
            handleConnectionLoss() // Connection likely lost
        } catch (e: Exception) {
            Log.e("BluetoothService", "Generic exception during message send: ${e.message}", e)
            setError(BluetoothError.UNKNOWN_ERROR)
        }
    }

    // Adds a chat message to the messages list (thread-safe update)
    private fun addMessage(message: ChatMessage) {
        scope.launch(Dispatchers.Main) { // Update UI on Main dispatcher
            _messages.update { currentMessages ->
                // Add new message and keep only the latest MAX_MESSAGES
                (currentMessages + message).takeLast(BluetoothConfig.MAX_MESSAGES)
            }
        }
    }

    // Updates the connection state (thread-safe update)
    private fun updateConnectionState(newState: ConnectionState) {
        scope.launch(Dispatchers.Main) { // Update UI on Main dispatcher
            _connectionState.value = newState
        }
    }

    // Starts Bluetooth device discovery
    override fun startDiscovery() {
        // Prevent discovery if already connected or connecting
        if (_connectionState.value in listOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING)) {
            Log.w("BluetoothService", "Cannot start discovery while connected/connecting")
            return
        }

        try {
            if (bluetoothAdapter.isDiscovering) {
                Log.d("BluetoothService", "Discovery already in progress.")
                return
            }
        } catch (e: SecurityException) {
            Log.e("BluetoothService", "SecurityException checking isDiscovering: ${e.message}", e)
            setError(BluetoothError.PERMISSION_DENIED)
            return
        }

        // Check for necessary permissions for discovery
        if (!hasRequiredPermissions()) {
            Log.e("BluetoothService", "Missing BLUETOOTH_SCAN or Location permission for discovery.")
            setError(BluetoothError.LOCATION_PERMISSION_REQUIRED)
            return
        }

        clearError() // Clear previous errors
        _discoveredDevices.value = emptyList() // Clear previous discovery results

        val started: Boolean
        try {
            started = bluetoothAdapter.startDiscovery() // Start the discovery process
        } catch (e: SecurityException) {
            Log.e("BluetoothService", "SecurityException starting discovery: ${e.message}", e)
            setError(BluetoothError.PERMISSION_DENIED)
            _isDiscovering.value = false
            return
        }

        _isDiscovering.value = started // Update discovery state
        if (started) {
            Log.d("BluetoothService", "Bluetooth discovery started.")
            discoveryTimeoutJob?.cancel() // Cancel any existing timeout job
            // Start a new timeout job for discovery
            discoveryTimeoutJob = scope.launch {
                delay(BluetoothConfig.DISCOVERY_TIMEOUT) // Wait for discovery timeout
                if (_isDiscovering.value) {
                    stopDiscovery() // Stop discovery if it's still active after timeout
                    Log.d("BluetoothService", "Bluetooth discovery timed out.")
                    if (_discoveredDevices.value.isEmpty()) {
                        setError(BluetoothError.DISCOVERY_FAILED) // Set error if no devices found
                    }
                }
            }
        } else {
            Log.e("BluetoothService", "Failed to start Bluetooth discovery.")
            setError(BluetoothError.DISCOVERY_FAILED)
        }
    }

    // Stops Bluetooth device discovery
    override fun stopDiscovery() {
        try {
            if (bluetoothAdapter.isDiscovering) {
                // Check BLUETOOTH_SCAN permission for stopping discovery on Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        Log.e("BluetoothService", "BLUETOOTH_SCAN permission missing to stop discovery.")
                        return
                    }
                }
                bluetoothAdapter.cancelDiscovery() // Cancel the discovery process
                Log.d("BluetoothService", "Bluetooth discovery stopped.")
            }
        } catch (e: SecurityException) {
            Log.e("BluetoothService", "SecurityException stopping discovery: ${e.message}", e)
            // Do not set error here, as it might be a transient issue or already handled.
        }
        _isDiscovering.value = false // Update discovery state
        discoveryTimeoutJob?.cancel() // Cancel any active timeout job
        discoveryTimeoutJob = null
    }

    // Adds a newly discovered device to the list of discovered devices
    override fun addDiscoveredDevice(device: BluetoothDevice) {
        _discoveredDevices.update { currentDevices ->
            // Add device only if it's not already in the list (based on address)
            if (currentDevices.none { it.address == device.address }) {
                currentDevices + device
            } else {
                currentDevices
            }
        }
    }

    // Sets the current error state
    override fun setError(error: BluetoothError) {
        _errorState.value = error
    }

    // Clears the current error state
    override fun clearError() {
        _errorState.value = null
    }

    // Stops the current Bluetooth connection and cleans up resources
    private fun stopCurrentConnection() {
        try {
            messageListenerJob?.cancel() // Cancel message listener
            messageListenerJob = null

            inputStream?.close() // Close input stream
            outputStream?.close() // Close output stream
            inputStream = null
            outputStream = null

            // Close sockets based on the current connection mode
            when (connectionMode) {
                ConnectionMode.SERVER -> {
                    serverSocket?.close() // Close server socket if still open
                    serverSocket = null
                    clientSocket?.close() // Also close client socket if server accepted a connection
                    clientSocket = null
                }
                ConnectionMode.CLIENT -> {
                    clientSocket?.close() // Close client socket
                    clientSocket = null
                }
                ConnectionMode.NONE -> {
                    // If no specific mode, close both just in case
                    serverSocket?.close()
                    clientSocket?.close()
                    serverSocket = null
                    clientSocket = null
                }
            }

            currentDeviceAddress = null // Clear current device address
            updateConnectionState(ConnectionState.DISCONNECTED) // Update UI state to disconnected
            reconnectionJob?.cancel() // Cancel any active reconnection attempt
            reconnectionJob = null
            // pendingMessages.clear() // Clear any pending messages (though now unused for simple send)
            Log.d("BluetoothService", "Current connection resources stopped and cleaned.")
        } catch (e: IOException) {
            Log.e("BluetoothService", "Error closing Bluetooth resources: ${e.message}", e)
        }
    }

    // Stops all Bluetooth operations and cleans up all service-level resources
    override fun stop() {
        messageListenerJob?.cancel() // Cancel message listener
        discoveryTimeoutJob?.cancel() // Cancel discovery timeout
        reconnectionJob?.cancel() // Cancel reconnection job

        stopDiscovery() // Ensure discovery is stopped
        runBlocking { // Use runBlocking to ensure stopCurrentConnection completes before scope is cancelled
            withContext(Dispatchers.IO) {
                stopCurrentConnection() // Stop any active connection
            }
        }
        scope.cancel() // Cancel the main service coroutine scope

        // Reset all MutableStateFlows to their initial empty/disconnected states
        _messages.value = emptyList()
        _discoveredDevices.value = emptyList()
        _isDiscovering.value = false
        clearError()
        connectionMode = ConnectionMode.NONE // Reset connection mode to none
        Log.d("BluetoothService", "BluetoothService fully stopped and resources cleaned.")
    }
}
