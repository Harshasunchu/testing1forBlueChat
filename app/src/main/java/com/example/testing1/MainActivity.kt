// MainActivity.kt
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

// Configuration object for app-level settings
object BluetoothConfig {
    const val SERVICE_NAME = "BluetoothChat"
    const val CONNECTION_TIMEOUT = 10000L // 10 seconds
    const val MAX_MESSAGES = 500
    const val DISCOVERY_TIMEOUT = 30000L // 30 seconds
    // RECONNECTION_DELAY removed as it's never used.
}

// Enhanced error handling
enum class BluetoothError {
    PERMISSION_DENIED,
    BLUETOOTH_DISABLED,
    CONNECTION_FAILED,
    DISCOVERY_FAILED,
    SEND_FAILED,
    UNKNOWN_ERROR
}

// Enhanced data classes
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isFromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceAddress: String = "" // To identify sender in multi-chat (future)
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING // Server is waiting for incoming connections
}

// Enhanced interface for better testability
interface BluetoothRepository {
    val connectionState: StateFlow<ConnectionState>
    val messages: StateFlow<List<ChatMessage>>
    val discoveredDevices: StateFlow<List<BluetoothDevice>>
    val errorState: StateFlow<BluetoothError?>
    val isDiscovering: StateFlow<Boolean>
    val generatedPairingCode: StateFlow<String?> // Expose pairing code

    suspend fun startServer()
    suspend fun connectToDevice(device: BluetoothDevice)
    suspend fun sendMessage(message: String)
    fun startDiscovery()
    fun stopDiscovery()
    fun addDiscoveredDevice(device: BluetoothDevice) // Should be internal to service ideally
    fun clearError()
    fun stop()
    fun resolveCode(code: String): String? // Add to interface
    fun setError(error: BluetoothError) // Add to interface for activity to set errors like permission
}

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothService: BluetoothRepository? = null // Use interface type
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val deviceDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    // SDK_INT >= 35, so TIRAMISU (33) is always true.
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

                    device?.let {
                        // Basic check to avoid adding devices without names, can be more sophisticated
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity, // Redundant qualifier `this@MainActivity` removed by linter review, but keeping for clarity here.
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            if (it.name != null) {
                                Log.d("BluetoothDiscovery", "Found device: ${getDeviceName(it)} (${it.address})")
                                bluetoothService?.addDiscoveredDevice(it)
                            } else {
                                Log.d("BluetoothDiscovery", "Found device without name: (${it.address})")
                            }
                        } else {
                            Log.w("BluetoothDiscovery", "BLUETOOTH_CONNECT permission not granted, cannot get device name.")
                            // Still add device, but name might be missing
                            bluetoothService?.addDiscoveredDevice(it)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d("BluetoothDiscovery", "Discovery finished.")
                    // isDiscovering state should be managed by the BluetoothService
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            initializeBluetooth()
        } else {
            Log.e("Permissions", "Not all Bluetooth permissions granted.")
            bluetoothService?.setError(BluetoothError.PERMISSION_DENIED)
            // Show a more user-friendly message or guide them to settings
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            initializeBluetooth()
        } else {
            Log.e("Bluetooth", "Bluetooth not enabled by user.")
            bluetoothService?.setError(BluetoothError.BLUETOOTH_DISABLED)
            // Show a message indicating Bluetooth is required
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
            ?: run {
                Log.e("Bluetooth", "Bluetooth adapter not available on this device.")
                // Handle case where Bluetooth is not supported at all
                setContent {
                    Testing1Theme {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("Bluetooth is not supported on this device.")
                            }
                        }
                    }
                }
                return
            }

        bluetoothService = BluetoothService(bluetoothAdapter, applicationContext)

        requestBluetoothPermissions() // Request permissions first

        setContent {
            Testing1Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pass the service instance, ensuring it's not null
                    bluetoothService?.let { service ->
                        BluetoothChatScreen(service)
                    } ?: run {
                        // Fallback UI if service couldn't be initialized (e.g. no BT adapter)
                        Box(contentAlignment = Alignment.Center) {
                            Text("Bluetooth Service not available. Please ensure Bluetooth is enabled and permissions are granted.")
                        }
                    }
                }
            }
        }
    }

    private fun requestBluetoothPermissions() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
            return // Wait for Bluetooth to be enabled before checking permissions
        }

        val requiredPermissions = mutableListOf<String>()
        // SDK_INT is always >= 35, so Build.VERSION_CODES.S (31) is always true.
        requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)


        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d("Permissions", "Requesting missing permissions: $missingPermissions")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d("Permissions", "All required Bluetooth permissions already granted.")
            initializeBluetooth()
        }
    }

    // Function "checkBluetoothAvailability" is never used, removed.

    private fun initializeBluetooth() {
        Log.d("BluetoothInit", "Bluetooth initialized, registering discovery receiver.")
        // Register for device discovery
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) // Good to listen for this too
        }
        // SDK_INT >= 35, so TIRAMISU (33) is always true.
        registerReceiver(deviceDiscoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        // At this point, Bluetooth is enabled and permissions are granted.
        // You can trigger any initial Bluetooth operations if needed, e.g., start server if applicable.
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService?.stop()
        scope.cancel()
        try {
            unregisterReceiver(deviceDiscoveryReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Receiver not registered or already unregistered", e)
        }
    }

    @Composable
    fun BluetoothChatScreen(service: BluetoothRepository) { // Use interface
        val connectionState by service.connectionState.collectAsState()
        val messages by service.messages.collectAsState()
        val discoveredDevices by service.discoveredDevices.collectAsState()
        val errorState by service.errorState.collectAsState()
        val isDiscovering by service.isDiscovering.collectAsState()
        val generatedPairingCode by service.generatedPairingCode.collectAsState()

        var currentMessage by remember { mutableStateOf("") }

        val coroutineScope = rememberCoroutineScope() // Use rememberCoroutineScope for Composable-tied scope
        val listState = rememberLazyListState()

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
            // Error Display
            errorState?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getErrorMessage(error),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { service.clearError() }
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Connection Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        ConnectionState.CONNECTED -> Color.Green.copy(alpha = 0.2f)
                        ConnectionState.CONNECTING -> Color.Yellow.copy(alpha = 0.2f)
                        ConnectionState.DISCONNECTED -> Color.Red.copy(alpha = 0.2f)
                        ConnectionState.LISTENING -> Color.Blue.copy(alpha = 0.2f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Status: ${connectionState.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (connectionState == ConnectionState.CONNECTING) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            )
                        }
                        generatedPairingCode?.let { code ->
                            if (connectionState == ConnectionState.LISTENING) {
                                Text(
                                    "Your Pairing Code: $code",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.LISTENING) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    if (connectionState == ConnectionState.LISTENING) {
                                        service.stop() // Stop current server before starting new
                                        service.startServer()
                                    } else {
                                        service.startServer()
                                    }
                                }
                            },
                            // Simplified condition as it's always true when the button is visible
                            enabled = true
                        ) {
                            Text(if (connectionState == ConnectionState.LISTENING) "Restart Server" else "Start Server")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Device Discovery and Connection via Code
            if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.LISTENING) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Discover & Connect",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Button(
                                onClick = {
                                    if (isDiscovering) {
                                        service.stopDiscovery()
                                    } else {
                                        service.startDiscovery()
                                    }
                                },
                                // Simplified condition as it's always true when this section is visible
                                enabled = true
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
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scanning for devices...")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (discoveredDevices.isNotEmpty()) {
                            Text(
                                text = "Available Devices:",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) { // Limit height
                                items(discoveredDevices) { device ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = getDeviceName(device) + " (${device.address})")
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    service.connectToDevice(device)
                                                }
                                            },
                                            enabled = true // Simplified condition
                                        ) {
                                            Text("Connect")
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        } else if (!isDiscovering) {
                            Text(
                                text = "No devices found. Try scanning.",
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        Text(
                            text = "Connect via Pairing Code",
                            style = MaterialTheme.typography.titleMedium // Changed from headlineSmall
                        )

                        var inputCode by remember { mutableStateOf("") }
                        var codeError by remember { mutableStateOf<String?>(null) }

                        OutlinedTextField(
                            value = inputCode,
                            onValueChange = {
                                inputCode = it
                                codeError = null
                            },
                            label = { Text("Enter Partner's Code") },
                            isError = codeError != null,
                            supportingText = { if (codeError != null) Text(codeError!!) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (inputCode.isBlank()) {
                                    codeError = "Code cannot be empty."
                                    return@Button
                                }
                                // Removed unused variable targetDeviceId
                                // Removed unused variable

                                codeError = "Connecting via code is complex. Please select a device from the list above for now."
                            },
                            modifier = Modifier.align(Alignment.End),
                            enabled = inputCode.isNotBlank() // Simplified condition
                        ) {
                            Text("Connect by Code")
                        }
                        if (codeError != null) {
                            Text(codeError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Messages Area
            Text(
                text = "Messages",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                modifier = Modifier.weight(1f) // Takes remaining space
            ) {
                Column(modifier = Modifier.fillMaxSize()) { // Fill the card
                    if (messages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f), // Ensure it takes space to center content
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (connectionState == ConnectionState.CONNECTED) "No messages yet." else "Connect to a device to chat.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            reverseLayout = false // Typically true for chat, but let's stick to current
                        ) {
                            items(
                                items = messages,
                                key = { it.id }
                            ) { message ->
                                MessageBubble(message)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Message Input
            if (connectionState == ConnectionState.CONNECTED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = currentMessage,
                        onValueChange = { currentMessage = it },
                        label = { Text("Enter message") },
                        modifier = Modifier.weight(1f),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (currentMessage.isNotBlank()) {
                                coroutineScope.launch {
                                    service.sendMessage(currentMessage)
                                    currentMessage = "" // Clear after sending
                                }
                            }
                        },
                        enabled = currentMessage.isNotBlank()
                    ) {
                        Text("Send")
                    }
                }
            } else {
                Text(
                    text = "You need to be connected to send messages.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    private fun getErrorMessage(error: BluetoothError): String {
        return when (error) {
            BluetoothError.PERMISSION_DENIED -> "Bluetooth permissions are required for this app to function. Please grant them in settings."
            BluetoothError.BLUETOOTH_DISABLED -> "Bluetooth is disabled. Please enable it to use chat features."
            BluetoothError.CONNECTION_FAILED -> "Failed to connect to the device. Please try again."
            BluetoothError.DISCOVERY_FAILED -> "Device discovery failed or no devices were found. Ensure other device is discoverable."
            BluetoothError.SEND_FAILED -> "Failed to send the message. Check connection."
            BluetoothError.UNKNOWN_ERROR -> "An unknown Bluetooth error occurred."
        }
    }

    private fun getDeviceName(device: BluetoothDevice): String {
        // SDK_INT is always >= 35, so Build.VERSION_CODES.S (31) is always true.
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("DeviceName", "BLUETOOTH_CONNECT permission not granted for getting device name.")
            return device.address // Fallback to address
        }
        return try {
            val name = device.name
            if (!name.isNullOrBlank()) {
                name
            } else {
                "Unknown Device (${device.address})" // More descriptive fallback
            }
        } catch (e: SecurityException) {
            Log.e("DeviceName", "SecurityException getting device name for ${device.address}", e)
            device.address // Fallback to address
        }
    }

    @Composable
    fun MessageBubble(message: ChatMessage) {
        val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
        val backgroundColor = if (message.isFromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
        val textColor = if (message.isFromMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = alignment
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = backgroundColor),
                modifier = Modifier.widthIn(max = 300.dp) // Max width for bubble
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
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

class BluetoothService(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context // Use application context if possible to avoid leaks
) : BluetoothRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // IO for blocking calls
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null // Renamed for clarity
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var currentDeviceAddress: String? = null // Address of the connected device

    // Well-known UUID for SPP (Serial Port Profile)
    private val appUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

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

    private val _generatedPairingCode = MutableStateFlow<String?>(null)
    override val generatedPairingCode: StateFlow<String?> = _generatedPairingCode.asStateFlow()

    // Map to store generated code and the unique ID of this server instance
    private val codeToIdentifierMap = mutableMapOf<String, String>()

    private var discoveryTimeoutJob: Job? = null
    private var reconnectionJob: Job? = null

    private fun generateNewPairingCode(): String {
        val code = (1000..9999).random().toString() // Simple 4-digit code
        val serverInstanceId = UUID.randomUUID().toString() // Unique ID for this server session
        codeToIdentifierMap.clear() // Only one active code at a time for this server
        codeToIdentifierMap[code] = serverInstanceId
        Log.d("BluetoothService", "Generated pairing code: $code for server instance ID: $serverInstanceId")
        _generatedPairingCode.value = code
        return code
    }

    // This function is for the server to resolve its own code to its instance ID.
    // A client would need a different mechanism to use a code for connection.
    override fun resolveCode(code: String): String? {
        Log.d("BluetoothService", "Resolving code: $code. Mapped ID: ${codeToIdentifierMap[code]}")
        return codeToIdentifierMap[code]
    }

    override suspend fun startServer() = withContext(Dispatchers.IO) {
        if (_connectionState.value == ConnectionState.LISTENING || _connectionState.value == ConnectionState.CONNECTING) {
            Log.w("BluetoothService", "Server start requested while already listening or connecting.")
            return@withContext
        }
        stopCurrentConnection() // Ensure any previous connection/server is stopped
        try {
            _connectionState.value = ConnectionState.LISTENING
            clearError() // Clear previous errors

            // SDK_INT is always >= 35, so Build.VERSION_CODES.S (31) is always true.
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BluetoothService", "BLUETOOTH_CONNECT permission missing for server.")
                setError(BluetoothError.PERMISSION_DENIED)
                _connectionState.value = ConnectionState.DISCONNECTED
                return@withContext
            }

            generateNewPairingCode() // Generate and expose the pairing code

            Log.d("BluetoothService", "Starting server. Service Name: ${BluetoothConfig.SERVICE_NAME}, UUID: $appUuid. Pairing code: ${_generatedPairingCode.value}")

            serverSocket?.close() // Close any existing server socket just in case
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                BluetoothConfig.SERVICE_NAME,
                appUuid
            )

            Log.d("BluetoothService", "Server socket listening. Waiting for a connection...")

            // This is a blocking call, ensure it's handled in Dispatchers.IO
            val acceptedSocket = serverSocket?.accept(BluetoothConfig.CONNECTION_TIMEOUT.toInt()) // Timeout for accept

            acceptedSocket?.let { socket ->
                Log.d("BluetoothService", "Connection accepted from: ${socket.remoteDevice.address}")
                this@BluetoothService.clientSocket = socket // Store the connected client socket
                currentDeviceAddress = socket.remoteDevice.address
                setupStreams(socket)
                _connectionState.value = ConnectionState.CONNECTED
                _generatedPairingCode.value = null // Clear pairing code once connection is established
                startMessageListener() // Start listening for messages from the connected client
                serverSocket?.close() // Close server socket as we only handle one connection
                serverSocket = null
            } ?: run {
                // This block can be reached if accept() times out or returns null
                if (_connectionState.value == ConnectionState.LISTENING) { // Check if still in listening state (i.e., no connection made)
                    Log.d("BluetoothService", "Server socket accept timed out or returned null, no connection made.")
                }
            }
        } catch (e: SecurityException) {
            Log.e("BluetoothService", "SecurityException during server start. Check BLUETOOTH_CONNECT (Android 12+) or BLUETOOTH permissions.", e)
            setError(BluetoothError.PERMISSION_DENIED)
            _connectionState.value = ConnectionState.DISCONNECTED
            _generatedPairingCode.value = null
        } catch (e: IOException) {
            Log.e("BluetoothService", "IOException during server start (e.g., port in use, BT off): ${e.message}", e)
            if (_connectionState.value != ConnectionState.DISCONNECTED) {
                setError(BluetoothError.CONNECTION_FAILED)
            }
            _connectionState.value = ConnectionState.DISCONNECTED
            _generatedPairingCode.value = null
        } finally {
            if (_connectionState.value != ConnectionState.CONNECTED && _connectionState.value != ConnectionState.LISTENING) {
                try {
                    serverSocket?.close()
                    serverSocket = null
                } catch (e: IOException) {
                    Log.e("BluetoothService", "Could not close server socket in finally block", e)
                }
            } else if (_connectionState.value == ConnectionState.LISTENING && serverSocket == null) {
            }
        }
    }

    override suspend fun connectToDevice(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            Log.w("BluetoothService", "Connection attempt ignored, already connecting or connected.")
            return@withContext
        }
        stopCurrentConnection() // Stop any previous server or connection attempts
        try {
            _connectionState.value = ConnectionState.CONNECTING
            _discoveredDevices.value = emptyList() // Clear discovered devices on connection attempt
            clearError()
            Log.d("BluetoothService", "Attempting to connect to device: ${device.address} - ${getDeviceNameSafe(device)}")

            stopDiscovery() // Cancel discovery as it can interfere with connection

            // SDK_INT is always >= 35, so Build.VERSION_CODES.S (31) is always true.
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BluetoothService", "BLUETOOTH_CONNECT permission missing for connect.")
                setError(BluetoothError.PERMISSION_DENIED)
                _connectionState.value = ConnectionState.DISCONNECTED
                return@withContext
            }

            // Create a new socket for the outgoing connection.
            val socket = device.createRfcommSocketToServiceRecord(appUuid)
            this@BluetoothService.clientSocket = socket // Assign to class member

            withTimeout(BluetoothConfig.CONNECTION_TIMEOUT) {
                socket.connect() // Blocking call
            }

            Log.d("BluetoothService", "Successfully connected to ${device.address}")
            currentDeviceAddress = device.address
            setupStreams(socket)
            _connectionState.value = ConnectionState.CONNECTED
            startMessageListener() // Start listening for incoming messages

        } catch (e: SecurityException) {
            Log.e("BluetoothService", "SecurityException during connect for ${getDeviceNameSafe(device)}: ${e.message}", e)
            setError(BluetoothError.PERMISSION_DENIED)
            _connectionState.value = ConnectionState.DISCONNECTED
        } catch (e: TimeoutCancellationException) {
            Log.e("BluetoothService", "Connection timeout for ${getDeviceNameSafe(device)}", e)
            setError(BluetoothError.CONNECTION_FAILED)
            _connectionState.value = ConnectionState.DISCONNECTED
            clientSocket?.close() // Ensure socket is closed on timeout
        } catch (e: IOException) {
            Log.e("BluetoothService", "IOException during connect for ${getDeviceNameSafe(device)}: ${e.message}", e)
            setError(BluetoothError.CONNECTION_FAILED)
            _connectionState.value = ConnectionState.DISCONNECTED
            clientSocket?.close() // Ensure socket is closed on other IOExceptions
        }
    }

    private fun getDeviceNameSafe(device: BluetoothDevice?): String {
        device ?: return "Unknown Device"
        return try {
            // SDK_INT is always >= 35, so Build.VERSION_CODES.S (31) is always true.
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return device.address
            }
            val name = device.name
            if (name.isNullOrBlank()) device.address else name
        } catch (e: SecurityException) {
            device.address
        }
    }


    private fun setupStreams(socket: BluetoothSocket) {
        try {
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            Log.d("BluetoothService", "Streams obtained successfully.")
        } catch (e: IOException) {
            Log.e("BluetoothService", "Error obtaining streams", e)
            setError(BluetoothError.CONNECTION_FAILED) // Or a more specific stream error
            stopCurrentConnection() // This will set state to DISCONNECTED
        }
    }

    private fun startMessageListener() {
        scope.launch(Dispatchers.IO) { // Ensure this runs on an IO thread
            val buffer = ByteArray(1024)
            var numBytes: Int
            Log.d("BluetoothService", "Message listener started for socket: ${clientSocket?.remoteDevice?.address}")

            while (clientSocket?.isConnected == true && isActive) { // Check isActive for coroutine cancellation
                try {
                    inputStream?.let { stream ->
                        numBytes = stream.read(buffer)
                        if (numBytes > 0) {
                            val receivedMessage = String(buffer, 0, numBytes)
                            Log.d("BluetoothService", "Received: $receivedMessage from ${clientSocket?.remoteDevice?.address}")
                            addMessage(
                                ChatMessage(
                                    content = receivedMessage,
                                    isFromMe = false,
                                    deviceAddress = clientSocket?.remoteDevice?.address ?: "Unknown"
                                )
                            )
                        } else if (numBytes == -1) { // End of stream reached, usually means remote disconnected
                            Log.d("BluetoothService", "Input stream ended (read -1), remote likely disconnected.")
                            setError(BluetoothError.CONNECTION_FAILED) // Or a specific "disconnected" state
                            stopCurrentConnection()
                            break // Exit loop
                        }
                    } ?: run {
                        // inputStream is null, something went wrong with setupStreams or socket closed
                        Log.e("BluetoothService", "InputStream is null, cannot listen for messages.")
                        setError(BluetoothError.UNKNOWN_ERROR) // Or connection failed
                        stopCurrentConnection()
                        break
                    }
                } catch (e: IOException) {
                    // Catch read errors, often indicates disconnection
                    if (isActive) { // Only process if coroutine is still active
                        Log.e("BluetoothService", "IOException while reading from input stream: ${e.message}. Connection likely lost.", e)
                        setError(BluetoothError.CONNECTION_FAILED)
                        stopCurrentConnection() // Clean up and set disconnected state
                    }
                    break // Exit loop
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


    override suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w("BluetoothService", "Cannot send message: Not connected.")
            setError(BluetoothError.SEND_FAILED)
            return@withContext
        }
        try {
            outputStream?.let { stream ->
                val bytes = message.toByteArray()
                stream.write(bytes)
                Log.d("BluetoothService", "Sent: $message to $currentDeviceAddress") // Redundant curly braces removed
                addMessage(
                    ChatMessage(
                        content = message,
                        isFromMe = true,
                        deviceAddress = currentDeviceAddress ?: "Unknown"
                    )
                )
            } ?: run {
                Log.e("BluetoothService", "OutputStream is null, cannot send message.")
                setError(BluetoothError.SEND_FAILED)
                stopCurrentConnection()
            }
        } catch (e: IOException) {
            Log.e("BluetoothService", "IOException during message send: ${e.message}", e)
            setError(BluetoothError.SEND_FAILED)
            stopCurrentConnection() // Connection likely lost
        } catch (e: Exception) {
            Log.e("BluetoothService", "Generic exception during message send: ${e.message}", e)
            setError(BluetoothError.UNKNOWN_ERROR)
        }
    }

    private fun addMessage(message: ChatMessage) {
        _messages.update { currentMessages ->
            val newMessages = (currentMessages + message).takeLast(BluetoothConfig.MAX_MESSAGES)
            newMessages
        }
    }

    override fun startDiscovery() {
        if (bluetoothAdapter.isDiscovering) {
            Log.d("BluetoothService", "Discovery already in progress.")
            return
        }

        // SDK_INT is always >= 35, so Build.VERSION_CODES.S (31) is always true.
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BluetoothService", "BLUETOOTH_SCAN permission missing for discovery.")
            setError(BluetoothError.PERMISSION_DENIED)
            return
        }

        clearError()
        _discoveredDevices.value = emptyList() // Clear previous discovery results
        val started = bluetoothAdapter.startDiscovery()
        _isDiscovering.value = started
        if (started) {
            Log.d("BluetoothService", "Bluetooth discovery started.")
            // Start a timeout for discovery
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = scope.launch {
                delay(BluetoothConfig.DISCOVERY_TIMEOUT)
                if (_isDiscovering.value) {
                    stopDiscovery()
                    Log.d("BluetoothService", "Bluetooth discovery timed out.")
                    if (_discoveredDevices.value.isEmpty()) {
                        setError(BluetoothError.DISCOVERY_FAILED)
                    }
                }
            }
        } else {
            Log.e("BluetoothService", "Failed to start Bluetooth discovery.")
            setError(BluetoothError.DISCOVERY_FAILED)
        }
    }

    override fun stopDiscovery() {
        if (bluetoothAdapter.isDiscovering) {
            // SDK_INT is always >= 35, so Build.VERSION_CODES.S (31) is always true.
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BluetoothService", "BLUETOOTH_SCAN permission missing to stop discovery.")
            } else {
                bluetoothAdapter.cancelDiscovery()
                Log.d("BluetoothService", "Bluetooth discovery stopped.")
            }
        }
        _isDiscovering.value = false
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
    }

    override fun addDiscoveredDevice(device: BluetoothDevice) {
        _discoveredDevices.update { currentDevices ->
            // Add only if not already present based on address
            if (currentDevices.none { it.address == device.address }) {
                currentDevices + device
            } else {
                currentDevices
            }
        }
    }

    override fun setError(error: BluetoothError) {
        _errorState.value = error
    }

    override fun clearError() {
        _errorState.value = null
    }

    private fun stopCurrentConnection() {
        try {
            // Close streams first
            inputStream?.close()
            outputStream?.close()
            inputStream = null
            outputStream = null

            // Close the client socket
            clientSocket?.close()
            clientSocket = null

            // Close the server socket if it's open (only one should be active at a time)
            serverSocket?.close()
            serverSocket = null

            currentDeviceAddress = null
            _connectionState.value = ConnectionState.DISCONNECTED
            reconnectionJob?.cancel()
            reconnectionJob = null
            Log.d("BluetoothService", "Current connection resources stopped and cleaned.")
        } catch (e: IOException) {
            Log.e("BluetoothService", "Error closing Bluetooth resources", e)
        }
    }

    override fun stop() {
        scope.cancel() // Cancel all coroutines launched by this scope
        discoveryTimeoutJob?.cancel()
        reconnectionJob?.cancel()

        // Explicitly stop discovery and connections
        stopDiscovery() // Ensure discovery is stopped
        stopCurrentConnection() // Clean up all connection-related resources

        // Clear out states
        _messages.value = emptyList() // Clear messages on full stop
        _discoveredDevices.value = emptyList() // Clear discovered devices
        _isDiscovering.value = false // Ensure discovery state is false
        clearError()
        Log.d("BluetoothService", "BluetoothService fully stopped and resources cleaned.")
    }
}