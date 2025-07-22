// In file: app/src/main/java/com/example/testing1/viewmodels/BluetoothViewModel.kt

package com.example.testing1.viewmodels

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.testing1.bluetooth.BluetoothService
import com.example.testing1.bluetooth.BluetoothService.BluetoothBinder
import com.example.testing1.bluetooth.ChatMessage
import com.example.testing1.bluetooth.ConnectionState
import com.example.testing1.bluetooth.FileTransferProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    private val _bluetoothService = MutableStateFlow<BluetoothService?>(null)

    val connectionState: StateFlow<ConnectionState> = _bluetoothService.flatMapLatest {
        it?.connectionState ?: flowOf(ConnectionState.DISCONNECTED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.DISCONNECTED)

    val messages: StateFlow<List<ChatMessage>> = _bluetoothService.flatMapLatest {
        it?.messages ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _bluetoothService.flatMapLatest {
        it?.discoveredDevices ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isDiscovering: StateFlow<Boolean> = _bluetoothService.flatMapLatest {
        it?.isDiscovering ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val error: StateFlow<String?> = _bluetoothService.flatMapLatest {
        it?.error ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- NEW: Add a StateFlow to observe file transfer progress ---
    val fileTransferProgress: StateFlow<FileTransferProgress?> = _bluetoothService.flatMapLatest {
        it?.fileTransferProgress ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothBinder
            _bluetoothService.value = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _bluetoothService.value = null
        }
    }

    init {
        Intent(getApplication(), BluetoothService::class.java).also { intent ->
            getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun startServer() = _bluetoothService.value?.startServer()
    fun startDiscovery() = _bluetoothService.value?.startDiscovery()
    fun stopDiscovery() = _bluetoothService.value?.stopDiscovery()
    fun connectToDevice(device: BluetoothDevice) = viewModelScope.launch {
        _bluetoothService.value?.connectToDevice(device)
    }
    fun sendMessage(message: String) = viewModelScope.launch {
        _bluetoothService.value?.sendMessage(message)
    }

    fun sendFile(uri: Uri) = viewModelScope.launch {
        _bluetoothService.value?.sendFile(uri)
    }

    fun getDeviceName(device: BluetoothDevice?) = _bluetoothService.value?.getDeviceNameSafe(device) ?: "Unknown"
    fun clearError() = _bluetoothService.value?.clearError()

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unbindService(serviceConnection)
    }
}

class ViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BluetoothViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BluetoothViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}