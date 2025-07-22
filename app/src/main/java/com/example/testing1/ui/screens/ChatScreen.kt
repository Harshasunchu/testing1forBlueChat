// In file: app/src/main/java/com/example/testing1/ui/screens/ChatScreen.kt

package com.example.testing1.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.testing1.bluetooth.ChatMessage
import com.example.testing1.bluetooth.ConnectionState
import com.example.testing1.bluetooth.FileTransferProgress
import com.example.testing1.viewmodels.BluetoothViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: BluetoothViewModel,
    onNavigateToSettings: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val error by viewModel.error.collectAsState()
    val fileProgress by viewModel.fileTransferProgress.collectAsState()

    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendFile(it) }
    }

    // --- FINAL, ROBUST VERSION of automatic discovery ---
    val isConnectionScreenVisible = connectionState != ConnectionState.CONNECTED
    LaunchedEffect(isConnectionScreenVisible) {
        if (isConnectionScreenVisible) {
            viewModel.startDiscovery()
        } else {
            // Stop discovery when we are connected
            viewModel.stopDiscovery()
        }
    }

    // Ensure discovery is stopped when the user leaves the screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopDiscovery()
        }
    }
    // --------------------------------------------------

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(message = it, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(index = messages.size - 1, scrollOffset = 0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth Chat") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            EnhancedConnectionHeader(connectionState = connectionState)

            Box(modifier = Modifier.weight(1f)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = connectionState == ConnectionState.CONNECTED,
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300, easing = EaseOutCubic)) + fadeIn(animationSpec = tween(300)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300, easing = EaseInCubic)) + fadeOut(animationSpec = tween(300))
                ) {
                    ChatMessagesList(messages = messages, listState = listState)
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = connectionState != ConnectionState.CONNECTED,
                    enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(400, easing = EaseOutCubic)) + fadeIn(animationSpec = tween(400)),
                    exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(400, easing = EaseInCubic)) + fadeOut(animationSpec = tween(400)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    EnhancedDeviceDiscoverySheet(
                        isDiscovering = isDiscovering,
                        discoveredDevices = discoveredDevices,
                        onScanClick = { if (isDiscovering) viewModel.stopDiscovery() else viewModel.startDiscovery() },
                        onConnectClick = { device -> coroutineScope.launch { viewModel.connectToDevice(device) } },
                        getDeviceName = { device -> viewModel.getDeviceName(device) },
                        onBecomeDiscoverableClick = { viewModel.startServer() }
                    )
                }
            }

            AnimatedVisibility(
                visible = fileProgress != null,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                fileProgress?.let {
                    FileTransferProgressBar(progressData = it)
                }
            }

            EnhancedMessageInput(
                text = messageText,
                onTextChange = { messageText = it },
                onSendClick = {
                    if (messageText.isNotBlank()) {
                        coroutineScope.launch {
                            viewModel.sendMessage(messageText)
                            messageText = ""
                        }
                    }
                },
                isEnabled = connectionState == ConnectionState.CONNECTED && fileProgress == null,
                onAttachFileClick = {
                    filePickerLauncher.launch("*/*")
                }
            )
        }
    }
}

@Composable
fun FileTransferProgressBar(progressData: FileTransferProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = if (progressData.isSending) "Sending: ${progressData.fileName}" else "Receiving: ${progressData.fileName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progressData.progress / 100f },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun EnhancedConnectionHeader(connectionState: ConnectionState) {
    val (text, color, icon) = when (connectionState) {
        ConnectionState.CONNECTED -> Triple("Connected", Color(0xFF4CAF50), Icons.Filled.Check)
        ConnectionState.CONNECTING -> Triple("Connecting...", Color(0xFFFF9800), null)
        ConnectionState.LISTENING -> Triple("Waiting for connection...", MaterialTheme.colorScheme.primary, null)
        else -> Triple("Disconnected", MaterialTheme.colorScheme.error, Icons.Filled.Close)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "connection_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.LISTENING) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier
                        .scale(pulseScale)
                        .size(24.dp)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .scale(pulseScale),
                    strokeWidth = 2.dp,
                    color = color
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessagesList(
    messages: List<ChatMessage>,
    listState: LazyListState
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = messages,
            key = { message -> message.id }
        ) { message ->
            EnhancedMessageBubble(
                message = message,
                Modifier.animateItem(
                    placementSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            )
        }
    }
}

@Composable
fun EnhancedDeviceDiscoverySheet(
    isDiscovering: Boolean,
    discoveredDevices: List<android.bluetooth.BluetoothDevice>,
    onScanClick: () -> Unit,
    onConnectClick: (android.bluetooth.BluetoothDevice) -> Unit,
    getDeviceName: (android.bluetooth.BluetoothDevice) -> String,
    onBecomeDiscoverableClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedButton(
                    onClick = onScanClick,
                    modifier = Modifier.weight(1f),
                    enabled = true
                ) {
                    if (isDiscovering) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Text("Scanning...")
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Scan Devices")
                        }
                    }
                }
                AnimatedButton(
                    onClick = onBecomeDiscoverableClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Be Discoverable")
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            AnimatedContent(
                targetState = discoveredDevices.isEmpty() && !isDiscovering,
                transitionSpec = {
                    slideInVertically { it } + fadeIn() togetherWith
                            slideOutVertically { -it } + fadeOut()
                },
                label = "device_list_content"
            ) { isEmpty ->
                if (isEmpty) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No devices found.\nEnsure Bluetooth is enabled and try scanning.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        items(
                            items = discoveredDevices,
                            key = { device -> device.address }
                        ) { device ->
                            DeviceCard(
                                device = device,
                                deviceName = getDeviceName(device),
                                onConnectClick = { onConnectClick(device) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: android.bluetooth.BluetoothDevice,
    deviceName: String,
    onConnectClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "device_card_scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onConnectClick()
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Connect",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun EnhancedMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    if (message.isSystemMessage) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        return
    }

    val isFromMe = message.isFromMe
    val shape = if (isFromMe) {
        RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
    } else {
        RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    }
    val backgroundColor = if (isFromMe) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isFromMe) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = shape,
            color = backgroundColor,
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun EnhancedMessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isEnabled: Boolean,
    onAttachFileClick: () -> Unit
) {
    val sendButtonEnabled = isEnabled && text.isNotBlank()
    val sendButtonScale by animateFloatAsState(
        targetValue = if (sendButtonEnabled) 1f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "send_button_scale"
    )

    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onAttachFileClick,
                enabled = isEnabled
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach File",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Type a message...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(24.dp),
                enabled = isEnabled,
                singleLine = true
            )
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(
                onClick = onSendClick,
                enabled = sendButtonEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .scale(sendButtonScale)
                    .clip(CircleShape)
                    .background(
                        if (sendButtonEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    tint = if (sendButtonEnabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

@Composable
fun AnimatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "button_scale"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.scale(scale),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        )
    ) {
        content()
    }
}