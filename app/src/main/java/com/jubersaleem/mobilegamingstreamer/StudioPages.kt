package com.jubersaleem.mobilegamingstreamer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun audioModeLabel(mode: String): String = when (mode) {
    "MIC" -> "Microphone only"
    "GAME" -> "Game audio only"
    "NORMAL" -> "Game + mic, equal level"
    else -> "Balanced mix, mic boosted"
}

private fun audioModeDetail(mode: String): String = when (mode) {
    "MIC" -> "Microphone only"
    "GAME" -> "Internal game/device playback only"
    "NORMAL" -> "Game playback + microphone at equal level"
    else -> "Balanced mix: game playback + microphone, with microphone level boosted"
}

/** Studio landing page: hero control centre, live session and quick actions. */
@Composable
fun StudioHomePage(
    status: String,
    captureActive: Boolean,
    broadcastActive: Boolean,
    broadcastStarting: Boolean,
    liveBitrate: Long,
    notificationsAllowed: Boolean,
    onAllowNotifications: () -> Unit,
    onGoLive: () -> Unit,
    onStopLive: () -> Unit,
    onOpenStream: () -> Unit
) {
    val p = LocalMgsPalette.current
    val active = broadcastActive || broadcastStarting || captureActive
    val tone = when {
        broadcastActive -> ClayTone.Live
        broadcastStarting || captureActive -> ClayTone.Active
        else -> ClayTone.Idle
    }
    val stateLabel = when {
        broadcastActive -> "ON AIR"
        broadcastStarting -> "CONNECTING"
        captureActive -> "CAPTURING"
        else -> "STANDBY"
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ClaySurface(shape = RoundedCornerShape(34.dp), depth = ClayDepth.Deep) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFFFFCFF), Color(0xFFEDE8FA), Color(0xFFE6F1EC))
                        )
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "STREAMING CONTROL CENTRE",
                            color = p.muted,
                            fontSize = 9.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Your game. Your stage.",
                            color = p.text,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    ClayStatusPill(stateLabel, tone)
                }
                Text(
                    text = when {
                        broadcastActive -> "YOU'RE LIVE"
                        broadcastStarting -> "GETTING YOU ON AIR"
                        captureActive -> "CAPTURE SESSION ACTIVE"
                        else -> "READY WHEN YOU ARE"
                    },
                    color = p.secondary,
                    fontSize = 29.sp,
                    lineHeight = 33.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
                AnimatedContent(targetState = status, label = "broadcast-status") { animatedStatus ->
                    Text(
                        text = animatedStatus,
                        color = p.muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ClayStat("Screen", "Capture", active, Modifier.weight(1f))
                    ClayStat("Video", "H.264", active, Modifier.weight(1f))
                    ClayStat("Output", "RTMP", broadcastActive || broadcastStarting, Modifier.weight(1f))
                }
            }
        }

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ClaySectionLabel("Live session")
                        Text(
                            text = if (broadcastActive || broadcastStarting) status else "No active broadcast",
                            color = if (broadcastActive) p.primary else p.muted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = when {
                            broadcastActive && liveBitrate > 0 -> "${liveBitrate / 1000} kbps"
                            broadcastActive -> "Waiting…"
                            else -> "0 kbps"
                        },
                        color = p.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                ClayGroove()
                ClayButton(
                    label = "OPEN STREAM SETTINGS",
                    onClick = onOpenStream,
                    style = ClayButtonStyle.Raised,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClayButton(
                        label = "GO LIVE",
                        onClick = onGoLive,
                        enabled = !broadcastActive && !broadcastStarting,
                        icon = Icons.Rounded.PlayArrow,
                        modifier = Modifier.weight(1f)
                    )
                    ClayButton(
                        label = "STOP LIVE",
                        onClick = onStopLive,
                        enabled = broadcastActive || broadcastStarting,
                        style = ClayButtonStyle.Raised,
                        icon = Icons.Rounded.PowerSettingsNew,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (!notificationsAllowed) {
            ClaySurface(shape = RoundedCornerShape(26.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ClaySectionLabel("Enable notifications")
                    Text(
                        text = "Android notifications show capture and live-stream status, with a stop control.",
                        color = p.muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    ClayButton(
                        label = "ALLOW NOTIFICATIONS",
                        onClick = onAllowNotifications,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Text(
            text = "Made for mobile gameplay • Screen video • Microphone / game audio",
            color = p.muted,
            fontSize = 10.sp
        )
    }
}

/** Broadcast workspace: destination, video profile and transport controls. */
@Composable
fun StreamWorkspacePage(
    server: String,
    onServer: (String) -> Unit,
    key: String,
    onKey: (String) -> Unit,
    rememberStreamKey: Boolean,
    onRememberStreamKey: (Boolean) -> Unit,
    quality: String,
    onQuality: (String) -> Unit,
    fps: String,
    onFps: (String) -> Unit,
    orientation: String,
    onOrientation: (String) -> Unit,
    audioMode: String,
    broadcastActive: Boolean,
    broadcastStarting: Boolean,
    onGoLive: () -> Unit,
    onStopLive: () -> Unit
) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ClayPageHeader(
            eyebrow = "Broadcast workspace",
            title = "Configure your stream",
            subtitle = "Set the destination, pick a video profile and go live."
        )

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        ClaySectionLabel("Destination")
                        Text(
                            text = "Where the stream goes",
                            color = p.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    ClayStatusPill(
                        label = if (rememberStreamKey) "KEY SAVED" else "KEY IN MEMORY",
                        tone = if (rememberStreamKey) ClayTone.Active else ClayTone.Idle
                    )
                }
                ClayTextField(
                    value = server,
                    onValueChange = onServer,
                    label = "RTMP server URL",
                    leadingIcon = Icons.Rounded.Videocam
                )
                ClayTextField(
                    value = key,
                    onValueChange = onKey,
                    label = "Private stream key",
                    password = true,
                    leadingIcon = Icons.Rounded.Lock
                )
                ClayToggleRow(
                    title = "Remember key securely on this device",
                    description = if (rememberStreamKey) {
                        "Encrypted with Android Keystore. Never stored as plain text."
                    } else {
                        "Key stays in memory only and is cleared when the app process ends."
                    },
                    checked = rememberStreamKey,
                    onCheckedChange = onRememberStreamKey
                )
            }
        }

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        ClaySectionLabel("Video profile")
                        Text(
                            text = "Resolution, frame rate and rotation",
                            color = p.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = p.secondary
                    )
                }
                ClayOptionGroup("Resolution", listOf("720p", "1080p", "1440p"), quality, onQuality)
                ClayOptionGroup("Frame rate", listOf("30", "60"), fps, onFps, suffix = " FPS")
                ClayOptionGroup("Orientation", listOf("AUTO", "LANDSCAPE", "PORTRAIT"), orientation, onOrientation)
                Text(
                    text = "Higher resolutions and 60 FPS depend on encoder support, game orientation, network and device temperature.",
                    color = p.muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ClaySectionLabel("Audio source")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(clayInsetModifier(RoundedCornerShape(20.dp)))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = p.primary
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = audioModeLabel(audioMode),
                            color = p.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = audioModeDetail(audioMode),
                            color = p.muted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ScreenRotation,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = p.muted
                    )
                }
                Text(
                    text = "Change the mix in the Audio tab.",
                    color = p.muted,
                    fontSize = 10.sp
                )
            }
        }

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ClaySectionLabel("Transport")
                Text(
                    text = if (broadcastActive) {
                        "The broadcast is running. Stop it before starting a new session."
                    } else {
                        "Going live asks Android for screen or app capture consent."
                    },
                    color = p.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClayButton(
                        label = "GO LIVE",
                        onClick = onGoLive,
                        enabled = !broadcastActive && !broadcastStarting,
                        icon = Icons.Rounded.PlayArrow,
                        modifier = Modifier.weight(1f)
                    )
                    ClayButton(
                        label = "STOP LIVE",
                        onClick = onStopLive,
                        enabled = broadcastActive || broadcastStarting,
                        style = ClayButtonStyle.Raised,
                        icon = Icons.Rounded.PowerSettingsNew,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Audio mixer page: source selection and the microphone compatibility bridge. */
@Composable
fun AudioMixerPage(
    audioMode: String,
    onAudioMode: (String) -> Unit,
    accessibilityMicEnabled: Boolean,
    aecActive: Boolean,
    nsActive: Boolean,
    voiceOptimizationActive: Boolean,
    onEnableAccessibility: () -> Unit
) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ClayPageHeader(
            eyebrow = "Audio mixer",
            title = "Choose what your stream hears",
            subtitle = "Balance game playback and your microphone before you go live."
        )

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ClayOptionGroup(
                    title = "Audio source",
                    options = listOf("MIC", "GAME", "NORMAL", "BALANCED"),
                    selected = audioMode,
                    onSelect = onAudioMode
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(clayInsetModifier(RoundedCornerShape(20.dp)))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = audioModeLabel(audioMode),
                        color = p.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = audioModeDetail(audioMode),
                        color = p.muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
                Text(
                    text = "Actual playback capture depends on Android, the game and device support.",
                    color = p.muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        ClaySectionLabel("Voice optimization")
                        Text(
                            text = "Studio voice chain",
                            color = p.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    ClayStatusPill(
                        label = if (voiceOptimizationActive) "ACTIVE" else "STANDBY",
                        tone = if (voiceOptimizationActive) ClayTone.Active else ClayTone.Idle
                    )
                }
                Text(
                    text = "Your microphone is shaped like a broadcast voice: background hum is gated out, quiet commentary is lifted to a steady level, shouts are evened out and speech clarity is lifted over game audio.",
                    color = p.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ClayStatusPill(
                        label = if (aecActive) "ECHO CANCELLED" else "ECHO: DEVICE",
                        tone = if (aecActive) ClayTone.Active else ClayTone.Idle,
                        modifier = Modifier.weight(1f)
                    )
                    ClayStatusPill(
                        label = if (nsActive) "NOISE CUT" else "NOISE: BASIC",
                        tone = if (nsActive) ClayTone.Active else ClayTone.Idle,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "Echo cancellation and noise suppression attach automatically when Android exposes them on the microphone. Live status appears here while broadcasting with a microphone mode.",
                    color = p.muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        ClaySectionLabel("BGMI voice chat compatibility")
                        Text(
                            text = "Microphone bridge",
                            color = p.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    ClayStatusPill(
                        label = if (accessibilityMicEnabled) "ENABLED" else "OFF",
                        tone = if (accessibilityMicEnabled) ClayTone.Active else ClayTone.Idle
                    )
                }
                Text(
                    text = if (accessibilityMicEnabled) {
                        "ACCESSIBILITY MICROPHONE BRIDGE ENABLED • Android microphone compatibility is available."
                    } else {
                        "Enable this Android service to let the stream capture your microphone while BGMI is using voice chat. It does not read screen content or perform gestures."
                    },
                    color = if (accessibilityMicEnabled) p.primary else p.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                ClayButton(
                    label = if (accessibilityMicEnabled) {
                        "OPEN ACCESSIBILITY SETTINGS"
                    } else {
                        "ENABLE MICROPHONE COMPATIBILITY"
                    },
                    onClick = onEnableAccessibility,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Tools page: overlay controls, encoder test and system status. */
@Composable
fun ToolsPage(
    captureActive: Boolean,
    broadcastActive: Boolean,
    floatingControlsAvailable: Boolean,
    onEnableFloatingControls: () -> Unit,
    onStartCaptureTest: () -> Unit,
    onStopCaptureTest: () -> Unit
) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ClayPageHeader(
            eyebrow = "Tools",
            title = "Studio utilities",
            subtitle = "Overlay controls, encoder testing and live system status."
        )

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        ClaySectionLabel("Floating controls")
                        Text(
                            text = "Broadcast overlay",
                            color = p.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    ClayStatusPill(
                        label = if (floatingControlsAvailable) "READY" else "PERMISSION NEEDED",
                        tone = if (floatingControlsAvailable) ClayTone.Active else ClayTone.Idle
                    )
                }
                Text(
                    text = "Optional: allow Display over other apps. Streaming does not depend on this permission; the small control bubble appears after video is actually being sent.",
                    color = p.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                ClayButton(
                    label = if (floatingControlsAvailable) {
                        "SHOW FLOATING CONTROLS"
                    } else {
                        "ENABLE FLOATING CONTROLS"
                    },
                    onClick = onEnableFloatingControls,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        ClaySectionLabel("Capture test")
                        Text(
                            text = "H.264 encoder only",
                            color = p.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = p.secondary
                    )
                }
                Text(
                    text = "Test the H.264 encoder without connecting to a streaming platform.",
                    color = p.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClayButton(
                        label = "START TEST",
                        onClick = onStartCaptureTest,
                        enabled = !captureActive && !broadcastActive,
                        icon = Icons.Rounded.PlayArrow,
                        modifier = Modifier.weight(1f)
                    )
                    ClayButton(
                        label = "STOP TEST",
                        onClick = onStopCaptureTest,
                        enabled = captureActive,
                        style = ClayButtonStyle.Raised,
                        icon = Icons.Rounded.PowerSettingsNew,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        ClaySurface(shape = RoundedCornerShape(30.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ClaySectionLabel("System status")
                Spacer(Modifier.height(2.dp))
                ClayKeyValueRow("Screen capture", if (captureActive || broadcastActive) "ACTIVE" else "READY", captureActive || broadcastActive)
                ClayKeyValueRow("H.264 encoder", if (captureActive || broadcastActive) "SESSION STARTED" else "READY", captureActive || broadcastActive)
                ClayKeyValueRow("RTMP transport", if (broadcastActive) "CONNECTING" else "READY TO CONNECT", broadcastActive)
                ClayKeyValueRow("Microphone / game audio", "DEVICE DEPENDENT")
            }
        }
    }
}
