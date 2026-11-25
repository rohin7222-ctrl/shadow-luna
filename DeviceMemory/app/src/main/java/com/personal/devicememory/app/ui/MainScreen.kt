package com.personal.devicememory.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.devicememory.R
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.pipeline.sync.LunaUpdateItemDto

// ─── 2026 Spatial Obsidian Glass Palette ───
private val ObsidianBg = Color(0xFF06050B)
private val GlassSurface = Color(0xFF10101C)
private val GlassElevated = Color(0xFF171728)
private val CyanAccent = Color(0xFF38BDF8)
private val VioletAccent = Color(0xFF7C3AED)
private val MintSuccess = Color(0xFF10B981)
private val CoralDanger = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFF4F6FB)
private val TextMuted = Color(0xFF94A3B8)
private val HairlineBorder = Color.White.copy(alpha = 0.08f)

private val PrimaryGradBrush = Brush.linearGradient(listOf(CyanAccent, VioletAccent))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onNavigate: (ActiveScreen) -> Unit,
    onSendOfflineMessage: (String) -> Unit,
    onConnectToServer: () -> Unit,
    onCancelHandshake: () -> Unit,
    onDismissHandshake: () -> Unit,
    onSaveServerUrl: (String) -> Result<String>,
    onCheckUpdates: () -> Unit,
    onInstallUpdate: (LunaUpdateItemDto) -> Unit,
    onToggleSource: (SourceType, Boolean) -> Unit,
    onCollectOfflineNow: () -> Unit,
    onDeleteAll: () -> Unit,
    onShowDeleteConfirmation: () -> Unit,
    onDismissDeleteConfirmation: () -> Unit,
    onRequestPermissionsAndRefresh: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.activeScreen != ActiveScreen.MAIN_CHAT) {
        onNavigate(ActiveScreen.MAIN_CHAT)
    }

    Scaffold(
        containerColor = ObsidianBg,
        modifier = Modifier
            .imePadding()
            .navigationBarsPadding(),
        topBar = {
            Column(modifier = Modifier.background(ObsidianBg)) {
                when (uiState.activeScreen) {
                    ActiveScreen.MAIN_CHAT -> {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = ObsidianBg,
                                titleContentColor = TextPrimary
                            ),
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.shadow_brand_mark),
                                        contentDescription = "Shadow Logo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .border(0.8.dp, CyanAccent.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                "Shadow",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = CyanAccent.copy(alpha = 0.12f),
                                                border = BorderStroke(0.6.dp, CyanAccent.copy(alpha = 0.30f))
                                            ) {
                                                Text(
                                                    if (uiState.totalRecords == 0) "Blank Slate"
                                                    else "${uiState.totalRecords} learned",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = CyanAccent,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (uiState.totalRecords > 0 && uiState.simulationPasses > 0) {
                                                "Pass #${uiState.simulationPasses} • ${uiState.neuralVectorsCount} vecs • GRPO ${uiState.selfPlayAccuracyPct}%"
                                            } else {
                                                "Luna • Offline"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (uiState.simulationRunning && uiState.totalRecords > 0) CyanAccent else TextMuted
                                        )
                                    }
                                }
                            },
                            actions = {
                                Box(modifier = Modifier.padding(end = 6.dp)) {
                                    Surface(
                                        onClick = { menuExpanded = true },
                                        shape = CircleShape,
                                        color = GlassSurface,
                                        border = BorderStroke(0.8.dp, HairlineBorder),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            BadgedBox(
                                                badge = {
                                                    if (uiState.hasPendingUpdate) {
                                                        Badge(containerColor = CyanAccent)
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.MoreVert,
                                                    contentDescription = "Menu",
                                                    tint = TextPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        shape = RoundedCornerShape(18.dp),
                                        containerColor = GlassElevated,
                                        border = BorderStroke(0.8.dp, HairlineBorder)
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text("Updates", color = TextPrimary, fontWeight = FontWeight.Medium)
                                                    if (uiState.hasPendingUpdate) {
                                                        Surface(
                                                            shape = RoundedCornerShape(999.dp),
                                                            color = CyanAccent.copy(alpha = 0.18f)
                                                        ) {
                                                            Text(
                                                                "NEW",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = CyanAccent,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.SystemUpdate,
                                                    contentDescription = null,
                                                    tint = CyanAccent
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                onNavigate(ActiveScreen.UPDATES)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "Learning Permission",
                                                    color = TextPrimary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Tune,
                                                    contentDescription = null,
                                                    tint = VioletAccent
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                onNavigate(ActiveScreen.LEARNING_PERMISSION)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        "Connect to Server",
                                                        color = TextPrimary,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    if (uiState.unsyncedRecords > 0) {
                                                        Surface(
                                                            shape = RoundedCornerShape(999.dp),
                                                            color = MintSuccess.copy(alpha = 0.20f)
                                                        ) {
                                                            Text(
                                                                "${uiState.unsyncedRecords}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MintSuccess,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Sync,
                                                    contentDescription = null,
                                                    tint = MintSuccess
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                onConnectToServer()
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }

                    ActiveScreen.LEARNING_PERMISSION -> {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = ObsidianBg,
                                titleContentColor = TextPrimary
                            ),
                            navigationIcon = {
                                IconButton(onClick = { onNavigate(ActiveScreen.MAIN_CHAT) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = TextPrimary
                                    )
                                }
                            },
                            title = {
                                Column {
                                    Text("Learning Permission", fontWeight = FontWeight.Bold)
                                    Text(
                                        "${uiState.totalRecords} memories • ${uiState.totalTokens} tokens on phone",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = onRequestPermissionsAndRefresh) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = CyanAccent)
                                }
                            }
                        )
                    }

                    ActiveScreen.UPDATES -> {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = ObsidianBg,
                                titleContentColor = TextPrimary
                            ),
                            navigationIcon = {
                                IconButton(onClick = { onNavigate(ActiveScreen.MAIN_CHAT) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = TextPrimary
                                    )
                                }
                            },
                            title = {
                                Column {
                                    Text("Updates", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Installed: v${uiState.installedVersion}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanAccent
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = onCheckUpdates) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Check Updates", tint = CyanAccent)
                                }
                            }
                        )
                    }
                }
                HorizontalDivider(thickness = 0.6.dp, color = HairlineBorder)
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = uiState.activeScreen,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                val forward = targetState != ActiveScreen.MAIN_CHAT
                if (forward) {
                    (slideInHorizontally(tween(260)) { it } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(260)) { -it / 4 } + fadeOut(tween(180)))
                } else {
                    (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(260)) { it } + fadeOut(tween(180)))
                }
            },
            label = "ScreenSlideTransition"
        ) { screen ->
            when (screen) {
                ActiveScreen.MAIN_CHAT -> {
                    OfflineChatScreen(
                        messages = uiState.messages,
                        totalRecords = uiState.totalRecords,
                        simulationPasses = uiState.simulationPasses,
                        consolidatedLinks = uiState.consolidatedLinks,
                        neuralVectorsCount = uiState.neuralVectorsCount,
                        selfPlayAccuracyPct = uiState.selfPlayAccuracyPct,
                        topPolicyStrategy = uiState.topPolicyStrategy,
                        topTopics = uiState.topSimulatedTopics,
                        onSendMessage = onSendOfflineMessage
                    )
                }

                ActiveScreen.LEARNING_PERMISSION -> {
                    LearningPermissionSlide(
                        uiState = uiState,
                        onSaveServerUrl = onSaveServerUrl,
                        onToggleSource = onToggleSource,
                        onCollectOfflineNow = onCollectOfflineNow,
                        onShowDeleteConfirmation = onShowDeleteConfirmation,
                        onRequestRuntimePermissions = onRequestPermissionsAndRefresh
                    )
                }

                ActiveScreen.UPDATES -> {
                    UpdatesSlide(
                        uiState = uiState,
                        onCheckUpdates = onCheckUpdates,
                        onInstallUpdate = onInstallUpdate
                    )
                }
            }
        }
    }

    // 2-Way Handshake Loading / Uploading Dialog ("Connect to Server")
    if (uiState.handshake.phase != HandshakePhase.IDLE) {
        ConnectToServerDialog(
            handshake = uiState.handshake,
            serverUrl = uiState.serverUrl,
            onSaveServerUrl = onSaveServerUrl,
            onRetry = onConnectToServer,
            onCancel = onCancelHandshake,
            onDismiss = onDismissHandshake
        )
    }

    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            containerColor = GlassElevated,
            shape = RoundedCornerShape(24.dp),
            onDismissRequest = onDismissDeleteConfirmation,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = CoralDanger) },
            title = { Text("Reset Offline AI & Vault?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This wipes all collected phone records and learned tokens back to 0.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissDeleteConfirmation()
                        onDeleteAll()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = CoralDanger)
                ) {
                    Text("Reset Everything", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteConfirmation) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

@Composable
private fun OfflineChatScreen(
    messages: List<OfflineChatMessage>,
    totalRecords: Int,
    simulationPasses: Int,
    consolidatedLinks: Int,
    neuralVectorsCount: Int,
    selfPlayAccuracyPct: Int,
    topPolicyStrategy: String,
    topTopics: List<String>,
    onSendMessage: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(VioletAccent.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.25f, size.height * 0.18f),
                        radius = size.maxDimension * 0.45f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(CyanAccent.copy(alpha = 0.09f), Color.Transparent),
                        center = Offset(size.width * 0.80f, size.height * 0.55f),
                        radius = size.maxDimension * 0.42f
                    )
                )
            }
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                // Intentionally clean empty canvas — zero pre-built greetings or canned replies
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .drawBehind {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            CyanAccent.copy(alpha = 0.22f),
                                            VioletAccent.copy(alpha = 0.14f),
                                            Color.Transparent
                                        )
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.shadow_brand_mark),
                            contentDescription = "Shadow Emblem",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(74.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .border(0.8.dp, CyanAccent.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                        )
                    }
                    Text(
                        "Luna",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        if (totalRecords == 0) {
                            "Blank-slate on-device AI. Ask anything below to verify she speaks only from what she learns."
                        } else {
                            val policyNote = if (topPolicyStrategy.isNotBlank()) " • Policy: $topPolicyStrategy" else ""
                            "Titans + TTT + GRPO Self-Play active over $totalRecords memories ($neuralVectorsCount neural vecs • $consolidatedLinks links • ${selfPlayAccuracyPct}% acc$policyNote)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                    if (topTopics.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = GlassSurface,
                            border = BorderStroke(0.7.dp, HairlineBorder)
                        ) {
                            Text(
                                text = "Simulated topics: ${topTopics.take(4).joinToString(" • ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                        ) {
                            val bubbleShape = RoundedCornerShape(
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                bottomStart = if (msg.isUser) 20.dp else 6.dp,
                                bottomEnd = if (msg.isUser) 6.dp else 20.dp
                            )
                            if (msg.isUser) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 300.dp)
                                        .clip(bubbleShape)
                                        .background(PrimaryGradBrush)
                                        .padding(horizontal = 16.dp, vertical = 11.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            } else {
                                Surface(
                                    shape = bubbleShape,
                                    color = GlassSurface,
                                    border = BorderStroke(
                                        0.9.dp,
                                        if (msg.learned) CyanAccent.copy(alpha = 0.40f) else HairlineBorder
                                    ),
                                    modifier = Modifier.widthIn(max = 315.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = (if (msg.learned) CyanAccent else TextMuted).copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = if (msg.learned) "AI Synthesis" else "Untrained",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (msg.learned) CyanAccent else TextMuted,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            text = msg.text,
                                            color = TextPrimary,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2026 Floating Frosted Glass Composer Pill
        Surface(
            color = GlassSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(
                1.dp,
                if (inputText.isNotBlank()) CyanAccent.copy(alpha = 0.50f) else HairlineBorder
            ),
            shadowElevation = 10.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (inputText.isEmpty()) {
                        Text(
                            "Type here to test if Luna learned...",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                        cursorBrush = SolidColor(CyanAccent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank()) {
                                onSendMessage(inputText)
                                inputText = ""
                            }
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                val canSend = inputText.isNotBlank()
                IconButton(
                    onClick = {
                        if (canSend) {
                            onSendMessage(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (canSend) PrimaryGradBrush else SolidColor(GlassElevated))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) Color.White else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectToServerDialog(
    handshake: HandshakeUiState,
    serverUrl: String,
    onSaveServerUrl: (String) -> Result<String>,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var urlInput by remember(serverUrl) { mutableStateOf(serverUrl) }

    val pulseTransition = rememberInfiniteTransition(label = "HandshakeRadarPulse")
    val ringScale by pulseTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RingScale"
    )
    val ringAlpha by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RingAlpha"
    )

    Dialog(
        onDismissRequest = {
            if (handshake.phase == HandshakePhase.COMPLETED || handshake.phase == HandshakePhase.ERROR) {
                onDismiss()
            }
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = GlassSurface,
            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.38f)),
            shadowElevation = 24.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (handshake.phase) {
                    HandshakePhase.WAITING_FOR_LAPTOP -> {
                        Box(
                            modifier = Modifier.size(90.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .scale(ringScale)
                                    .alpha(ringAlpha)
                                    .border(1.5.dp, CyanAccent, CircleShape)
                            )
                            Image(
                                painter = painterResource(R.drawable.shadow_brand_mark),
                                contentDescription = "Shadow",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                        Text(
                            if (handshake.laptopConnected) "Waiting for Laptop..." else "Searching Wi-Fi...",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = (if (handshake.laptopConnected) MintSuccess else Color(0xFFF59E0B)).copy(alpha = 0.14f),
                            border = BorderStroke(
                                0.8.dp,
                                (if (handshake.laptopConnected) MintSuccess else Color(0xFFF59E0B)).copy(alpha = 0.40f)
                            )
                        ) {
                            Text(
                                text = if (handshake.laptopConnected) "Laptop Reached ✓" else "Searching / Firewall Check",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (handshake.laptopConnected) MintSuccess else Color(0xFFFBBF24),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            handshake.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                        if (handshake.diagnosticError != null) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFF59E0B).copy(alpha = 0.10f),
                                border = BorderStroke(0.8.dp, Color(0xFFF59E0B).copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = handshake.diagnosticError,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFDE68A),
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("Laptop Address") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanAccent,
                                unfocusedBorderColor = HairlineBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onSaveServerUrl(urlInput) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Update URL", color = TextPrimary)
                            }
                            Button(
                                onClick = onCancel,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2B1826),
                                    contentColor = CoralDanger
                                )
                            ) {
                                Text("Cancel")
                            }
                        }
                    }

                    HandshakePhase.UPLOADING -> {
                        Image(
                            painter = painterResource(R.drawable.shadow_brand_mark),
                            contentDescription = "Uploading",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Text(
                            "Uploading learning progress...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        val progress = if (handshake.totalRecords > 0) {
                            (handshake.uploadedRecords.toFloat() / handshake.totalRecords.toFloat()).coerceIn(0.06f, 1f)
                        } else {
                            0.65f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(GlassElevated)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(PrimaryGradBrush)
                            )
                        }
                        Text(
                            "${handshake.uploadedRecords} / ${handshake.totalRecords} memories • ${handshake.uploadedTokens} tokens",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanAccent
                        )
                    }

                    HandshakePhase.COMPLETED -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MintSuccess,
                            modifier = Modifier.size(50.dp)
                        )
                        Text(
                            "Saved to Laptop",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            handshake.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VioletAccent)
                        ) {
                            Text("Done", fontWeight = FontWeight.Bold)
                        }
                    }

                    HandshakePhase.ERROR -> {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = CoralDanger,
                            modifier = Modifier.size(46.dp)
                        )
                        Text(
                            "Connection Interrupted",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            handshake.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Close")
                            }
                            Button(
                                onClick = onRetry,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = VioletAccent),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Retry")
                            }
                        }
                    }

                    HandshakePhase.IDLE -> Unit
                }
            }
        }
    }
}

@Composable
private fun UpdatesSlide(
    uiState: MainUiState,
    onCheckUpdates: () -> Unit,
    onInstallUpdate: (LunaUpdateItemDto) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (uiState.isCheckingUpdates) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp)),
                    color = CyanAccent,
                    trackColor = GlassElevated
                )
            }
        }

        if (uiState.updateCheckError != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1A121C),
                    border = BorderStroke(0.9.dp, CoralDanger.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Laptop unreachable right now",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF8A80)
                        )
                        Text(
                            "Shadow continues running 100% offline. Make sure Shadow desktop is open on the same Wi-Fi to check updates.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        OutlinedButton(
                            onClick = onCheckUpdates,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, HairlineBorder)
                        ) {
                            Text("Check Again", color = TextPrimary)
                        }
                    }
                }
            }
        }

        val latest = uiState.latestUpdate
        if (uiState.hasPendingUpdate && latest != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = GlassSurface,
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = CyanAccent.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    "NEW UPDATE • v${latest.version}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CyanAccent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                "~${latest.estimated_seconds}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }

                        Text(
                            latest.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Text(
                            "Arrived: ${latest.released_at_display}",
                            style = MaterialTheme.typography.labelSmall,
                            color = VioletAccent
                        )

                        Text(
                            latest.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD0D2E6)
                        )

                        Spacer(Modifier.height(2.dp))

                        Button(
                            onClick = { onInstallUpdate(latest) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VioletAccent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Install Update", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else if (uiState.updateCheckError == null && !uiState.isCheckingUpdates) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = GlassSurface,
                    border = BorderStroke(0.8.dp, HairlineBorder),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MintSuccess)
                        Column {
                            Text("Shadow is up to date", fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(
                                "Running v${uiState.installedVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }

        if (uiState.updates.isNotEmpty()) {
            item {
                Text(
                    "Update Timeline",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            items(uiState.updates, key = { it.version }) { item ->
                val cmp = compareVersions(item.version, uiState.installedVersion)
                val statusLabel = when {
                    cmp == 0 -> "Current ✓"
                    cmp < 0 -> "Applied ✓"
                    else -> "Available"
                }
                val statusColor = if (cmp <= 0) MintSuccess else CyanAccent
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = GlassSurface,
                    border = BorderStroke(0.8.dp, HairlineBorder),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "v${item.version} — ${item.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
                        }
                        Text(
                            "Arrived: ${item.released_at_display}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            item.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB4B6D0)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LearningPermissionSlide(
    uiState: MainUiState,
    onSaveServerUrl: (String) -> Result<String>,
    onToggleSource: (SourceType, Boolean) -> Unit,
    onCollectOfflineNow: () -> Unit,
    onShowDeleteConfirmation: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
) {
    val context = LocalContext.current
    var urlInput by remember(uiState.serverUrl) { mutableStateOf(uiState.serverUrl) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Offline Scan & Reset Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCollectOfflineNow,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = VioletAccent)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Collect Offline", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onShowDeleteConfirmation,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(0.9.dp, CoralDanger.copy(alpha = 0.45f)),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralDanger)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset AI", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (uiState.statusMessage != null) {
            item {
                Text(
                    uiState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanAccent
                )
            }
        }

        // Auto-Open After Background Update Permission
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = GlassSurface,
                border = BorderStroke(
                    0.9.dp,
                    if (uiState.canDrawOverlays) MintSuccess.copy(alpha = 0.30f)
                    else CyanAccent.copy(alpha = 0.35f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "Auto-Open After Update",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            if (uiState.canDrawOverlays) {
                                "Granted • Updates auto-open Shadow smoothly"
                            } else {
                                "Allow 'Display over other apps' so updates auto-open Shadow cleanly"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    if (!uiState.canDrawOverlays) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } catch (_: Throwable) {}
                        }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Allow", color = CyanAccent, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MintSuccess)
                    }
                }
            }
        }

        // Laptop Address Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = GlassSurface,
                border = BorderStroke(0.8.dp, HairlineBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Laptop Sync Target (Manual Connect Only)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Laptop Local Wi-Fi URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = HairlineBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ready to upload: ${uiState.unsyncedRecords}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        Button(
                            onClick = { onSaveServerUrl(urlInput) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VioletAccent)
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }

        // Encryption Vault Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = GlassSurface,
                border = BorderStroke(0.8.dp, HairlineBorder)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CyanAccent.copy(alpha = 0.14f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Private On-Device Vault", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(
                            "Encrypted locally • Lightweight metadata only",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }

        // Source cards
        items(uiState.sources, key = { it.sourceType.name }) { source ->
            SourceCard(
                source = source,
                onToggle = { enabled -> onToggleSource(source.sourceType, enabled) },
                onGrantPermission = {
                    CollectorStatusHelper.openPermissionOrRequest(
                        context = context,
                        sourceType = source.sourceType,
                        onRequestRuntimePermissions = onRequestRuntimePermissions
                    )
                }
            )
        }
    }
}

@Composable
fun SourceCard(
    source: SourceUiState,
    onToggle: (Boolean) -> Unit,
    onGrantPermission: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = GlassSurface,
        border = BorderStroke(0.8.dp, if (source.enabled) CyanAccent.copy(alpha = 0.28f) else HairlineBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = (if (source.enabled) CyanAccent else VioletAccent).copy(alpha = 0.14f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = source.sourceType.icon(),
                                contentDescription = null,
                                tint = if (source.enabled) CyanAccent else VioletAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            source.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            "${source.recordCount} memories",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggle,
                    enabled = source.permissionGranted,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = CyanAccent,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = GlassElevated
                    )
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val (permColor, permText) = if (source.permissionGranted) {
                    MintSuccess to "Permission active"
                } else {
                    CoralDanger to "Permission required"
                }
                Icon(
                    if (source.permissionGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = permColor,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(permText, style = MaterialTheme.typography.bodySmall, color = permColor)
                if (!source.permissionGranted) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onGrantPermission,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Grant", style = MaterialTheme.typography.bodySmall, color = CyanAccent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Last run: ${source.lastRunAt?.let { TimestampUtil.formatForDisplay(it) } ?: "Never"}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )

            if (source.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    source.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = CoralDanger
                )
            }
        }
    }
}

private fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val pb = b.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val va = pa.getOrElse(i) { 0 }
        val vb = pb.getOrElse(i) { 0 }
        if (va != vb) return va.compareTo(vb)
    }
    return 0
}

private fun SourceType.icon(): ImageVector = when (this) {
    SourceType.APP_USAGE -> Icons.Default.Apps
    SourceType.NOTIFICATION -> Icons.Default.Notifications
    SourceType.PHOTO -> Icons.Default.Photo
    SourceType.VIDEO -> Icons.Default.Videocam
    SourceType.AUDIO -> Icons.Default.Audiotrack
    SourceType.LOCATION -> Icons.Default.LocationOn
    SourceType.ACTIVITY -> Icons.AutoMirrored.Filled.DirectionsRun
    SourceType.CONTACT -> Icons.Default.Contacts
    SourceType.CALENDAR_EVENT -> Icons.Default.CalendarMonth
}
