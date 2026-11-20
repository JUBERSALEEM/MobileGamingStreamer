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

/** The clay navigation dock entries. */
private val clayNavItems = listOf(
    ClayNavItem("STUDIO", "Studio", Icons.Rounded.Home),
    ClayNavItem("STREAM", "Stream", Icons.Rounded.PlayArrow),
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
    val floatingControlsAvailable: Boolean = false,
    val server: String = "",
    val key: String = "",
    val rememberStreamKey: Boolean = true,
    val quality: String = "1080p",
    val fps: String = "30",
    val orientation: String = "LANDSCAPE",
    val audioMode: String = "BALANCED",
    val showStreamKeyDialog: Boolean = false,
    val showAccessibilityDisclosure: Boolean = false
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
    val onAudioModeChange: (String) -> Unit = {}
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
                password = true
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
                ClayTopBar(onSettings = { actions.onPageChange("SETTINGS") })
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
                        onEnableAccessibility = actions.onEnableAccessibility
                    )
                    "TOOLS" -> ToolsPage(
                        captureActive = state.captureActive,
                        broadcastActive = state.broadcastActive,
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

/** Sculpted clay header with the studio mark, build badge and settings shortcut. */
@Composable
private fun ClayTopBar(onSettings: () -> Unit) {
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
        ClayBuildBadge("0.5.22")
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
