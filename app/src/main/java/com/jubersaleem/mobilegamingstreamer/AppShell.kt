package com.jubersaleem.mobilegamingstreamer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jubersaleem.mobilegamingstreamer.scene.SceneOverlayAnchor
import com.jubersaleem.mobilegamingstreamer.scene.StreamScene

/** The clay navigation dock entries. */
private val clayNavItems = listOf(
    ClayNavItem("STUDIO", "Studio", Icons.Rounded.Home),
    ClayNavItem("STREAM", "Stream", Icons.Rounded.PlayArrow),
    ClayNavItem("SCENES", "Scenes", Icons.Rounded.Layers),
    ClayNavItem("AUDIO", "Audio", Icons.Rounded.GraphicEq),
    ClayNavItem("TOOLS", "Tools", Icons.Rounded.Build),
    ClayNavItem("SETTINGS", "Settings", Icons.Rounded.Settings)
)

/** Everything the shell renders, owned by the activity and pushed down. */
data class StudioUiState(
    val page: String = "STUDIO",
    val status: String = "Ready to stream",
    val captureActive: Boolean = false,
    val broadcastActive: Boolean = false,
    val broadcastStarting: Boolean = false,
    val liveBitrate: Long = 0L,
    val notificationsAllowed: Boolean = true,
    val accessibilityMicEnabled: Boolean = false,
    val aecActive: Boolean = false,
    val nsActive: Boolean = false,
    val voiceOptimizationActive: Boolean = false,
    val micSource: Int = AudioRouteLabels.SOURCE_NONE,
    val headsetConnected: Boolean = false,
    val streamAudioMuted: Boolean = false,
    val audioCaptureActive: Boolean = false,
    val floatingControlsAvailable: Boolean = false,
    val server: String = "",
    val key: String = "",
    val rememberStreamKey: Boolean = true,
    val quality: String = "1080p",
    val fps: String = "30",
    val orientation: String = "LANDSCAPE",
    val audioMode: String = "BALANCED",
    val showStreamKeyDialog: Boolean = false,
    val showAccessibilityDisclosure: Boolean = false,
    val scenes: List<StreamScene> = emptyList(),
    val activeSceneId: String? = null,
    val editingSceneId: String? = null,
    val sceneDeleteTargetName: String? = null,
    val sceneError: String? = null
)

/** Callbacks the shell triggers, implemented by the activity. */
data class StudioActions(
    val onPageChange: (String) -> Unit = {},
    val onGoLive: () -> Unit = {},
    val onStopLive: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onStartCaptureTest: () -> Unit = {},
    val onStopCaptureTest: () -> Unit = {},
    val onEnableFloatingControls: () -> Unit = {},
    val onEnableAccessibility: () -> Unit = {},
    val onDismissAccessibility: () -> Unit = {},
    val onConfirmAccessibility: () -> Unit = {},
    val onDismissStreamKey: () -> Unit = {},
    val onConfirmStreamKey: () -> Unit = {},
    val onServerChange: (String) -> Unit = {},
    val onKeyChange: (String) -> Unit = {},
    val onRememberStreamKeyChange: (Boolean) -> Unit = {},
    val onQualityChange: (String) -> Unit = {},
    val onFpsChange: (String) -> Unit = {},
    val onOrientationChange: (String) -> Unit = {},
    val onAudioModeChange: (String) -> Unit = {},
    val onCreateScene: () -> Unit = {},
    val onActiveScene: (String) -> Unit = {},
    val onEditScene: (String?) -> Unit = {},
    val onDuplicateScene: (String) -> Unit = {},
    val onDeleteScene: (String) -> Unit = {},
    val onConfirmDeleteScene: () -> Unit = {},
    val onDismissDeleteScene: () -> Unit = {},
    val onRenameScene: (String, String) -> Unit = { _, _ -> },
    val onAddLayer: (String) -> Unit = {},
    val onAddImageLayer: (String) -> Unit = {},
    val onAddClockLayer: (String) -> Unit = {},
    val onAddTimerLayer: (String) -> Unit = {},
    val onReplaceImageLayer: (String, String) -> Unit = { _, _ -> },
    val onRemoveLayer: (String, String) -> Unit = { _, _ -> },
    val onMoveLayer: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onToggleLayer: (String, String) -> Unit = { _, _ -> },
    val onLayerText: (String, String, String) -> Unit = { _, _, _ -> },
    val onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit = { _, _, _ -> },
    val onLayerMargin: (String, String, Int) -> Unit = { _, _, _ -> },
    val onLayerMaxWidth: (String, String, Int) -> Unit = { _, _, _ -> },
    val onLayerAlpha: (String, String, Float) -> Unit = { _, _, _ -> },
    val onLayerTextColor: (String, String, Int) -> Unit = { _, _, _ -> },
    val onLayerBackground: (String, String, Int?) -> Unit = { _, _, _ -> },
    val onLayerBorderWidth: (String, String, Int) -> Unit = { _, _, _ -> },
    val onLayerBorderColor: (String, String, Int) -> Unit = { _, _, _ -> },
    val onLayerCornerRadius: (String, String, Int) -> Unit = { _, _, _ -> },
    val onLayerShadowOffset: (String, String, Int) -> Unit = { _, _, _ -> },
    val onLayerShadowColor: (String, String, Int) -> Unit = { _, _, _ -> },
    val onDismissSceneError: () -> Unit = {}
)

/**
 * The whole clay application shell: header, page content, dialogs and the
 * bottom navigation dock. Every surface is sculpted with the shared clay
 * tokens from [AppTheme].
 */
@Composable
fun StudioShell(state: StudioUiState, actions: StudioActions) {
    val palette = LocalMgsPalette.current
    if (state.showAccessibilityDisclosure) {
        ClayDialog(
            title = "Microphone compatibility permission",
            onDismiss = actions.onDismissAccessibility,
            confirmLabel = "CONTINUE TO ANDROID SETTINGS",
            onConfirm = actions.onConfirmAccessibility
        ) {
            Text(
                text = "Mobile Gaming Streamer uses Android's Accessibility Service to help capture your microphone while BGMI is using voice chat. The service listens for window-state changes only. It does not read screen text, retrieve window content, perform gestures or control other apps. Your microphone audio is included in the live stream you start. You can decline or disable this service at any time in Android settings.",
                color = palette.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
    if (state.sceneDeleteTargetName != null) {
        ClayDialog(
            title = "Delete scene",
            onDismiss = actions.onDismissDeleteScene,
            confirmLabel = "DELETE SCENE",
            onConfirm = actions.onConfirmDeleteScene
        ) {
            Text(
                text = "Delete \"" + state.sceneDeleteTargetName + "\" and all its layers? This cannot be undone.",
                color = palette.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
    if (state.showStreamKeyDialog) {
        ClayDialog(
            title = "Stream key required",
            onDismiss = actions.onDismissStreamKey,
            confirmLabel = "SAVE KEY & CONTINUE",
            confirmEnabled = state.key.isNotBlank(),
            onConfirm = actions.onConfirmStreamKey
        ) {
            Text(
                text = "Enter your RTMP stream key to start broadcasting. Your key stays private and is not shown on screen.",
                color = palette.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            ClayTextField(
                value = state.key,
                onValueChange = actions.onKeyChange,
                label = "Stream key",
                password = true,
                mono = true
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.background, palette.surfaceDeep)))
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ClayTopBar(
                    onSettings = { actions.onPageChange("SETTINGS") },
                    statusPill = when (classifyBroadcastStatus(state.status, state.broadcastActive, state.broadcastStarting)) {
                        BroadcastStatusKind.LIVE -> "ON AIR" to ClayTone.Live
                        BroadcastStatusKind.STARTING -> "CONNECTING" to ClayTone.Active
                        BroadcastStatusKind.ERROR -> "ERROR" to ClayTone.Alert
                        BroadcastStatusKind.STANDBY -> null
                    }
                )
                when (state.page) {
                    "SETTINGS" -> SettingsPage(
                        onTerms = { actions.onPageChange("TERMS") },
                        onPrivacy = { actions.onPageChange("PRIVACY") },
                        onAbout = { actions.onPageChange("ABOUT") }
                    )
                    "ABOUT" -> AboutPage(
                        onTerms = { actions.onPageChange("TERMS") },
                        onPrivacy = { actions.onPageChange("PRIVACY") }
                    )
                    "TERMS" -> LegalPage(
                        title = "Terms & Conditions",
                        body = TermsText,
                        onBack = { actions.onPageChange("SETTINGS") }
                    )
                    "PRIVACY" -> LegalPage(
                        title = "Privacy Policy",
                        body = PrivacyText,
                        onBack = { actions.onPageChange("SETTINGS") }
                    )
                    "STREAM" -> StreamWorkspacePage(
                        server = state.server,
                        onServer = actions.onServerChange,
                        key = state.key,
                        onKey = actions.onKeyChange,
                        rememberStreamKey = state.rememberStreamKey,
                        onRememberStreamKey = actions.onRememberStreamKeyChange,
                        quality = state.quality,
                        onQuality = actions.onQualityChange,
                        fps = state.fps,
                        onFps = actions.onFpsChange,
                        orientation = state.orientation,
                        onOrientation = actions.onOrientationChange,
                        audioMode = state.audioMode,
                        broadcastActive = state.broadcastActive,
                        broadcastStarting = state.broadcastStarting,
                        onGoLive = actions.onGoLive,
                        onStopLive = actions.onStopLive
                    )
                    "AUDIO" -> AudioMixerPage(
                        audioMode = state.audioMode,
                        onAudioMode = actions.onAudioModeChange,
                        accessibilityMicEnabled = state.accessibilityMicEnabled,
                        aecActive = state.aecActive,
                        nsActive = state.nsActive,
                        voiceOptimizationActive = state.voiceOptimizationActive,
                        micSource = state.micSource,
                        headsetConnected = state.headsetConnected,
                        streamAudioMuted = state.streamAudioMuted,
                        audioCaptureActive = state.audioCaptureActive,
                        onEnableAccessibility = actions.onEnableAccessibility
                    )
                    "SCENES" -> SceneEditorPage(
                        scenes = state.scenes,
                        activeSceneId = state.activeSceneId,
                        editingSceneId = state.editingSceneId,
                        sceneError = state.sceneError,
                        onCreateScene = actions.onCreateScene,
                        onActiveScene = actions.onActiveScene,
                        onEditScene = actions.onEditScene,
                        onDuplicateScene = actions.onDuplicateScene,
                        onDeleteScene = actions.onDeleteScene,
                        onRenameScene = actions.onRenameScene,
                        onAddLayer = actions.onAddLayer,
                        onAddImageLayer = actions.onAddImageLayer,
                        onAddClockLayer = actions.onAddClockLayer,
                        onAddTimerLayer = actions.onAddTimerLayer,
                        onReplaceImageLayer = actions.onReplaceImageLayer,
                        onRemoveLayer = actions.onRemoveLayer,
                        onMoveLayer = actions.onMoveLayer,
                        onToggleLayer = actions.onToggleLayer,
                        onLayerText = actions.onLayerText,
                        onLayerAnchor = actions.onLayerAnchor,
                        onLayerMargin = actions.onLayerMargin,
                        onLayerMaxWidth = actions.onLayerMaxWidth,
                        onLayerAlpha = actions.onLayerAlpha,
                        onLayerTextColor = actions.onLayerTextColor,
                        onLayerBackground = actions.onLayerBackground,
                        onLayerBorderWidth = actions.onLayerBorderWidth,
                        onLayerBorderColor = actions.onLayerBorderColor,
                        onLayerCornerRadius = actions.onLayerCornerRadius,
                        onLayerShadowOffset = actions.onLayerShadowOffset,
                        onLayerShadowColor = actions.onLayerShadowColor,
                        onDismissSceneError = actions.onDismissSceneError
                    )
                    "TOOLS" -> ToolsPage(
                        captureActive = state.captureActive,
                        broadcastActive = state.broadcastActive,
                        broadcastStarting = state.broadcastStarting,
                        floatingControlsAvailable = state.floatingControlsAvailable,
                        onEnableFloatingControls = actions.onEnableFloatingControls,
                        onStartCaptureTest = actions.onStartCaptureTest,
                        onStopCaptureTest = actions.onStopCaptureTest
                    )
                    else -> StudioHomePage(
                        status = state.status,
                        captureActive = state.captureActive,
                        broadcastActive = state.broadcastActive,
                        broadcastStarting = state.broadcastStarting,
                        liveBitrate = state.liveBitrate,
                        notificationsAllowed = state.notificationsAllowed,
                        onAllowNotifications = actions.onAllowNotifications,
                        onGoLive = actions.onGoLive,
                        onStopLive = actions.onStopLive,
                        onOpenStream = { actions.onPageChange("STREAM") }
                    )
                }
            }
            ClayNavDock(
                items = clayNavItems,
                selected = state.page,
                onSelect = actions.onPageChange,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    }
}

/**
 * Sculpted clay header: studio identity, a live broadcast pill only while a
 * session is getting started, running or faulted, the build badge and the
 * settings shortcut.
 */
@Composable
private fun ClayTopBar(onSettings: () -> Unit, statusPill: Pair<String, ClayTone>?) {
    val palette = LocalMgsPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clayRaisedModifier(RoundedCornerShape(28.dp), ClayDepth.Medium))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .then(clayInsetModifier(RoundedCornerShape(18.dp))),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_mgs_clay_mark),
                contentDescription = "Mobile Gaming Streamer",
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "MOBILE GAMING STREAMER",
                color = palette.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.2.sp
            )
            Text(
                text = "YOUR MOBILE STUDIO",
                color = palette.muted,
                fontSize = 9.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        if (statusPill != null) {
            ClayStatusPill(label = statusPill.first, tone = statusPill.second)
            Spacer(Modifier.width(10.dp))
        }
        ClayBuildBadge(MgsVersionName)
        Spacer(Modifier.width(10.dp))
        ClayIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = "Open settings",
            onClick = onSettings
        )
    }
}

/** Recessed clay badge that shows the current build. */
@Composable
private fun ClayBuildBadge(build: String) {
    val palette = LocalMgsPalette.current
    Column(
        modifier = Modifier
            .then(clayInsetModifier(RoundedCornerShape(14.dp)))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = "BUILD",
            color = palette.muted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = build,
            color = palette.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
