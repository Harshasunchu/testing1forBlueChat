// In file: app/src/main/java/com/example/testing1/bluetooth/BluetoothService.kt

package com.example.testing1.bluetooth

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.testing1.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair

@SuppressLint("MissingPermission")
class BluetoothService : Service() {

    private val binder = BluetoothBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var keyPair: KeyPair? = null
    private var sharedSecret: ByteArray? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering = _isDiscovering.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _fileTransferProgress = MutableStateFlow<FileTransferProgress?>(null)
    val fileTransferProgress = _fileTransferProgress.asStateFlow()

    private val discoveryResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = getDeviceFromIntent(intent)
                    device?.let {
                        if (it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE) {
                            _discoveredDevices.update { devices ->
                                if (devices.none { d -> d.address == it.address }) devices + it else devices
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _isDiscovering.value = false
                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device: BluetoothDevice? = getDeviceFromIntent(intent)
                    device?.let { updatedDevice ->
                        _discoveredDevices.update { devices ->
                            devices.map { if (it.address == updatedDevice.address) updatedDevice else it }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        startForegroundService()
        registerReceivers()
    }

    fun startServer() = scope.launch {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return@launch
        _connectionState.value = ConnectionState.LISTENING
        try {
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(BluetoothConfig.SERVICE_NAME, BluetoothConfig.APP_UUID)
            val socket = serverSocket?.accept()
            handleConnection(socket)
        } catch (e: IOException) {
            if (isActive) {
                Log.e("BluetoothService", "Server socket failed: ${e.message}")
                _error.value = "Couldn't start server. Is another device already listening?"
                disconnect()
            }
        }
    }

    suspend fun connectToDevice(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return@withContext
        stopDiscovery()
        _connectionState.value = ConnectionState.CONNECTING
        try {
            val socket = device.createRfcommSocketToServiceRecord(BluetoothConfig.APP_UUID)
            socket.connect()
            handleConnection(socket)
        } catch (e: IOException) {
            Log.e("BluetoothService", "Failed to connect to device: ${e.message}")
            _error.value = "Connection failed. Make sure the other device is discoverable."
            disconnect()
        }
    }

    private suspend fun handleConnection(socket: BluetoothSocket?) {
        socket ?: run {
            disconnect()
            return
        }
        serverSocket?.close()
        clientSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream

        val keyExchangeSuccess = performKeyExchange()

        if (keyExchangeSuccess) {
            _connectionState.value = ConnectionState.CONNECTED
            listenForMessages()
        } else {
            disconnect()
        }
    }

    private suspend fun performKeyExchange(): Boolean = withContext(Dispatchers.IO) {
        try {
            keyPair = SecurityHelper.generateKeyPair()
            val ownPublicKeyString = SecurityHelper.publicKeyToString(keyPair!!.public)
            outputStream?.write(ownPublicKeyString.toByteArray())
            Log.d("Security", "Sent public key.")

            val buffer = ByteArray(1024)
            val byteCount = inputStream?.read(buffer) ?: -1
            if (byteCount > 0) {
                val otherPublicKeyString = String(buffer, 0, byteCount)
                val otherPublicKey = SecurityHelper.stringToPublicKey(otherPublicKeyString)
                Log.d("Security", "Received public key.")
                sharedSecret = SecurityHelper.generateSharedSecret(keyPair!!.private, otherPublicKey)
                Log.d("Security", "Shared secret established successfully.")
                return@withContext true
            } else {
                throw IOException("Key exchange failed: No data received.")
            }
        } catch (e: Exception) {
            Log.e("Security", "Key exchange failed: ${e.message}")
            _error.value = "Could not create a secure connection."
            return@withContext false
        }
    }

    private fun listenForMessages() = scope.launch {
        val buffer = ByteArray(4096)
        while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
            try {
                val byteCount = inputStream?.read(buffer) ?: -1
                if (byteCount > 0) {
                    if (sharedSecret == null) continue

                    val decryptedContent = try {
                        val encryptedMessage = String(buffer, 0, byteCount)
                        SecurityHelper.decrypt(encryptedMessage, sharedSecret!!)
                    } catch (e: Exception) {
                        Log.e("Listen", "Could not decrypt message, assuming it's not text.", e)
                        null
                    }

                    if (decryptedContent != null) {
                        try {
                            val json = JSONObject(decryptedContent)
                            if (json.optString("type") == "file") {
                                val fileName = json.getString("name")
                                val fileSize = json.getLong("size")
                                handleFileReception(fileName, fileSize)
                            } else {
                                val message = ChatMessage(content = decryptedContent, isFromMe = false)
                                _messages.update { it + message }
                            }
                        } catch (_: Exception) {
                            val message = ChatMessage(content = decryptedContent, isFromMe = false)
                            _messages.update { it + message }
                        }
                    }
                } else {
                    throw IOException("Stream ended")
                }
            } catch (_: IOException) {
                _error.value = "Connection was lost."
                disconnect()
                break
            } catch (e: Exception) {
                Log.e("Security", "Decryption or message handling failed: ${e.message}")
                _error.value = "A security error occurred."
                disconnect()
                break
            }
        }
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        if (sharedSecret == null) {
            _error.value = "Cannot send message: Secure connection not established."
            return@withContext
        }
        try {
            val encryptedMessage = SecurityHelper.encrypt(message, sharedSecret!!)
            outputStream?.write(encryptedMessage.toByteArray())
            _messages.update { it + ChatMessage(content = message, isFromMe = true) }
        } catch (_: IOException) {
            _error.value = "Failed to send message."
            disconnect()
        } catch (e: Exception) {
            Log.e("Security", "Encryption failed: ${e.message}")
        }
    }

    suspend fun sendFile(uri: Uri) = withContext(Dispatchers.IO) {
        if (sharedSecret == null) {
            _error.value = "Cannot send file: Secure connection not established."
            return@withContext
        }

        var fileName = "unknown_file"
        var fileSize = 0L

        _fileTransferProgress.value = FileTransferProgress(fileName = "Preparing...", progress = 0, isSending = true)

        try {
            applicationContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                fileName = cursor.getString(nameIndex)
                fileSize = cursor.getLong(sizeIndex)
            }

            _fileTransferProgress.value = FileTransferProgress(fileName = fileName, progress = 0, isSending = true)

            val header = JSONObject().apply {
                put("type", "file")
                put("name", fileName)
                put("size", fileSize)
            }.toString()
            val encryptedHeader = SecurityHelper.encrypt(header, sharedSecret!!)
            outputStream?.write(encryptedHeader.toByteArray())
            outputStream?.flush()

            applicationContext.contentResolver.openInputStream(uri)?.use { fileInputStream ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                var totalBytesSent = 0L
                while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream?.write(buffer, 0, bytesRead)
                    totalBytesSent += bytesRead
                    val progress = ((totalBytesSent * 100) / fileSize).toInt()
                    _fileTransferProgress.value = _fileTransferProgress.value?.copy(progress = progress)
                }
                outputStream?.flush()
            }
            _messages.update { it + ChatMessage(content = "You sent a file: $fileName", isFromMe = true, isSystemMessage = true) }
        } catch(e: Exception) {
            Log.e("FileTransfer", "Failed to send file", e)
            _error.value = "Failed to send the file."
        } finally {
            _fileTransferProgress.value = null
        }
    }

    private fun handleFileReception(fileName: String, fileSize: Long) = scope.launch {
        withContext(Dispatchers.Main) {
            _messages.update { it + ChatMessage(content = "Receiving file: $fileName...", isFromMe = false, isSystemMessage = true) }
            _fileTransferProgress.value = FileTransferProgress(fileName = fileName, progress = 0, isSending = false)
        }

        try {
            val file = File(externalCacheDir, fileName)
            val fileOutputStream = FileOutputStream(file)
            val buffer = ByteArray(4096)
            var totalBytesRead = 0L

            while (totalBytesRead < fileSize) {
                val bytesToRead = if (fileSize - totalBytesRead < buffer.size) {
                    (fileSize - totalBytesRead).toInt()
                } else {
                    buffer.size
                }
                val bytesRead = inputStream?.read(buffer, 0, bytesToRead) ?: -1
                if (bytesRead == -1) break

                fileOutputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                val progress = ((totalBytesRead * 100) / fileSize).toInt()
                withContext(Dispatchers.Main) {
                    _fileTransferProgress.value = _fileTransferProgress.value?.copy(progress = progress)
                }
            }
            fileOutputStream.close()
            withContext(Dispatchers.Main) {
                _messages.update { it + ChatMessage(content = "File received: $fileName", isFromMe = false, isSystemMessage = true) }
            }
        } catch(e: Exception) {
            Log.e("FileTransfer", "Failed to receive file", e)
            _error.value = "Failed to receive the file."
        } finally {
            withContext(Dispatchers.Main) {
                _fileTransferProgress.value = null
            }
        }
    }

    fun startDiscovery() {
        if (_isDiscovering.value) return
        _discoveredDevices.value = emptyList()
        bluetoothAdapter.startDiscovery()
        _isDiscovering.value = true
    }

    fun stopDiscovery() {
        if (!_isDiscovering.value) return
        bluetoothAdapter.cancelDiscovery()
        _isDiscovering.value = false
    }

    private fun disconnect() {
        keyPair = null
        sharedSecret = null
        try {
            clientSocket?.close()
            inputStream?.close()
            outputStream?.close()
        } catch (_: IOException) {}
        if (_connectionState.value == ConnectionState.CONNECTED) {
            _messages.update { it + ChatMessage(content="-- Disconnected --", isFromMe = false, isSystemMessage = true)}
        }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun startForegroundService() {
        val channelId = "bt_service_channel"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Testing1 Active")
            .setContentText("Ready to connect.")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .build()
        startForeground(1, notification)
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        registerReceiver(discoveryResultReceiver, filter)
    }

    private fun getDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    fun getDeviceNameSafe(device: BluetoothDevice?): String {
        return device?.name ?: device?.address ?: "Unknown Device"
    }

    fun clearError() {
        _error.value = null
    }

    override fun onBind(intent: Intent): IBinder = binder

    inner class BluetoothBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        unregisterReceiver(discoveryResultReceiver)
        disconnect()
        serverSocket?.close()
    }
}