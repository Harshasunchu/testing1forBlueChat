// In file: app/src/main/java/com/example/testing1/bluetooth/BluetoothData.kt

package com.example.testing1.bluetooth

import java.util.UUID

object BluetoothConfig {
    const val SERVICE_NAME = "Testing1Chat"
    val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isFromMe: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isSystemMessage: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LISTENING
}

// --- NEW: Add this data class for tracking file transfers ---
data class FileTransferProgress(
    val fileName: String,
    val progress: Int, // A percentage from 0 to 100
    val isSending: Boolean
)