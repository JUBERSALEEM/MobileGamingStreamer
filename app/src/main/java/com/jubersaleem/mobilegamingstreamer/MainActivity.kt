package com.jubersaleem.mobilegamingstreamer

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jubersaleem.mobilegamingstreamer.capture.MgsMicAccessibilityService
import com.jubersaleem.mobilegamingstreamer.capture.RtmpBroadcastService
import com.jubersaleem.mobilegamingstreamer.capture.ScreenCaptureService
import com.jubersaleem.mobilegamingstreamer.overlay.FloatingControlService

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Ready to stream")
    private var liveBitrate by mutableStateOf(0L)
    private var page by mutableStateOf("STUDIO")
    private var themeMode by mutableStateOf("CLASSIC")
    private var showStreamKeyDialog by mutableStateOf(false)
    private var captureActive by mutableStateOf(false)
    private var broadcastActive by mutableStateOf(false)
    private var broadcastStarting by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(true)
    private var accessibilityMicEnabled by mutableStateOf(false)
    private var showAccessibilityDisclosure by mutableStateOf(false)
    private var server by mutableStateOf("rtmp://a.rtmp.youtube.com/live2")
    private var key by mutableStateOf("")
    private var rememberStreamKey by mutableStateOf(true)
    private var quality by mutableStateOf("1080p")
    private var fps by mutableStateOf("30")
    private var orientation by mutableStateOf("LANDSCAPE")
    private var audioMode by mutableStateOf("BALANCED")
    private var pendingLive by mutableStateOf(false)
    private var waitingForOverlayPermission = false

    private val broadcastStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RtmpBroadcastService.ACTION_STATUS) return
            broadcastActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_ACTIVE, false)
            status = intent.getStringExtra(RtmpBroadcastService.EXTRA_STATUS_MESSAGE) ?: status
            liveBitrate = intent.getLongExtra(RtmpBroadcastService.EXTRA_STATUS_BITRATE, 0L)
            broadcastStarting = !broadcastActive && listOf("Preparing", "Encoder ready", "Connecting", "RTMP connected", "Waiting for", "Stream key accepted", "60 FPS unavailable").any { status.contains(it, ignoreCase = true) }
        }
    }

    private fun restoreBroadcastState() {
        val prefs = getSharedPreferences(RtmpBroadcastService.STATE_PREFS, MODE_PRIVATE)
        val savedActive = prefs.getBoolean(RtmpBroadcastService.KEY_ACTIVE, false)
        val heartbeat = prefs.getLong(RtmpBroadcastService.KEY_HEARTBEAT, 0L)
        val sessionFresh = heartbeat > 0L && System.currentTimeMillis() - heartbeat <= 8_000L
        broadcastActive = savedActive && sessionFresh
        broadcastStarting = false
        status = if (savedActive && !sessionFresh) {
            prefs.edit().putBoolean(RtmpBroadcastService.KEY_ACTIVE, false).putLong(RtmpBroadcastService.KEY_BITRATE, 0L).apply()
            "Previous broadcast ended unexpectedly"
        } else {
            prefs.getString(RtmpBroadcastService.KEY_MESSAGE, "Ready to stream") ?: "Ready to stream"
        }
        liveBitrate = if (broadcastActive) prefs.getLong(RtmpBroadcastService.KEY_BITRATE, 0L) else 0L
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        restoreBroadcastState()
        val filter = IntentFilter(RtmpBroadcastService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(broadcastStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(broadcastStatusReceiver, filter)
    }

    override fun onStop() {
        try { unregisterReceiver(broadcastStatusReceiver) } catch (_: IllegalArgumentException) { }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        accessibilityMicEnabled = MgsMicAccessibilityService.isEnabled(this)
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, FloatingControlService::class.java).setAction(FloatingControlService.ACTION_SHOW))
                status = "Floating controls enabled"
            } else {
                status = "Floating bubble permission was not granted"
            }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchProjectionConsent()
        else {
            pendingLive = false
            broadcastStarting = false
            status = "Audio permission is required for microphone or eligible game-audio capture"
        }
    }

    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            status = "Screen sharing permission was not granted"
            pendingLive = false
            broadcastStarting = false
            return@registerForActivityResult
        }
        if (pendingLive) {
            val liveIntent = Intent(this, RtmpBroadcastService::class.java).apply {
                action = RtmpBroadcastService.ACTION_START
                putExtra(RtmpBroadcastService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RtmpBroadcastService.EXTRA_RESULT_DATA, data)
                putExtra(RtmpBroadcastService.EXTRA_STREAM_URL, server.trimEnd('/') + "/" + key.trim())
                putExtra(RtmpBroadcastService.EXTRA_QUALITY, quality)
                putExtra(RtmpBroadcastService.EXTRA_FPS, fps.toInt())
                putExtra(RtmpBroadcastService.EXTRA_ORIENTATION, orientation)
                putExtra(RtmpBroadcastService.EXTRA_AUDIO_MODE, audioMode)
            }
            try {
                startForegroundService(liveIntent)
                // A user-initiated GO LIVE must always restore the floating controls,
                // including during encoder preparation or RTMP connection failures.
                // ACTION_SHOW clears any previous drag-to-dismiss preference.
                if (Settings.canDrawOverlays(this)) {
                    startService(
                        Intent(this, FloatingControlService::class.java)
                            .setAction(FloatingControlService.ACTION_SHOW)
                    )
                }
                broadcastActive = false
                broadcastStarting = true
                status = "Starting stream service • preparing encoders"
            } catch (error: Exception) {
                broadcastStarting = false
                status = "Could not start streaming service: ${error.message ?: error.javaClass.simpleName}"
            }
            pendingLive = false
        } else {
            val testIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(testIntent)
            captureActive = true
            status = "H.264 capture test running"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val appPreferences = getSharedPreferences("mgs_preferences", MODE_PRIVATE)
        themeMode = appPreferences.getString("theme_mode", "CLASSIC") ?: "CLASSIC"
        rememberStreamKey = appPreferences.getBoolean("remember_stream_key", true)
        key = if (rememberStreamKey) StreamKeyVault.load(this).orEmpty() else ""
        setContent {
            MgsTheme(mode = themeMode) {
                val palette = LocalMgsPalette.current
                if (showAccessibilityDisclosure) {
                    AlertDialog(
                        onDismissRequest = { showAccessibilityDisclosure = false },
                        title = { Text("Microphone compatibility permission") },
                        text = {
                            Text(
                                "Mobile Gaming Streamer uses Android's Accessibility Service to help capture your microphone while BGMI is using voice chat. The service listens for window-state changes only. It does not read screen text, retrieve window content, perform gestures or control other apps. Your microphone audio is included in the live stream you start. You can decline or disable this service at any time in Android settings."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showAccessibilityDisclosure = false
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("CONTINUE TO ANDROID SETTINGS") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAccessibilityDisclosure = false }) { Text("NOT NOW") }
                        }
                    )
                }
                if (showStreamKeyDialog) {
                    AlertDialog(
                        onDismissRequest = { showStreamKeyDialog = false },
                        title = { Text("Stream key required") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Enter your RTMP stream key to start broadcasting. Your key stays private and is not shown on screen.")
                                OutlinedTextField(
                                    value = key,
                                    onValueChange = { key = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Stream key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showStreamKeyDialog = false
                                    startBroadcastFlow()
                                },
                                enabled = key.isNotBlank()
                            ) { Text("SAVE KEY & CONTINUE") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showStreamKeyDialog = false }) { Text("CANCEL") }
                        }
                    )
                }
                Surface(Modifier.fillMaxSize(), color = palette.background) {
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().safeDrawingPadding()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Header(onSettings = { page = "SETTINGS" })
                            when (page) {
                                "SETTINGS" -> SettingsPage(
                                    themeMode,
                                    { selected ->
                                        themeMode = selected
                                        getSharedPreferences("mgs_preferences", MODE_PRIVATE).edit().putString("theme_mode", selected).apply()
                                    },
                                    onTerms = { page = "TERMS" },
                                    onPrivacy = { page = "PRIVACY" },
                                    onAbout = { page = "ABOUT" }
                                )
                                "ABOUT" -> AboutPage(onTerms = { page = "TERMS" }, onPrivacy = { page = "PRIVACY" })
                                "TERMS" -> LegalPage(title = "Terms & Conditions", body = TermsText, onBack = { page = "SETTINGS" })
                                "PRIVACY" -> LegalPage(title = "Privacy Policy", body = PrivacyText, onBack = { page = "SETTINGS" })
                                "STREAM" -> BroadcastPanel(
                                    server, { server = it }, key, { key = it },
                                    rememberStreamKey, { enabled ->
                                        rememberStreamKey = enabled
                                        appPreferences.edit().putBoolean("remember_stream_key", enabled).apply()
                                        if (!enabled) {
                                            StreamKeyVault.clear(this@MainActivity)
                                            status = "Saved stream key removed from this device"
                                        } else if (key.isNotBlank()) {
                                            runCatching { StreamKeyVault.save(this@MainActivity, key.trim()) }
                                                .onFailure { status = "Could not securely save the stream key" }
                                        }
                                    },
                                    quality, { quality = it }, fps, { fps = it }, orientation, { orientation = it },
                                    audioMode, { audioMode = it }, broadcastActive || broadcastStarting,
                                    onGoLive = { startBroadcastFlow() },
                                    onStopLive = { RtmpBroadcastService.requestStop(this@MainActivity) }
                                )
                                "AUDIO" -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    MgsCard(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("AUDIO MIXER", color = palette.secondary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp)
                                            Text("Choose what your stream hears", color = palette.text, fontSize = 19.sp, fontWeight = FontWeight.Black)
                                            ClayOptionGroup("AUDIO SOURCE", listOf("MIC", "GAME", "NORMAL", "BALANCED"), audioMode, { audioMode = it })
                                            Text(
                                                when (audioMode) {
                                                    "MIC" -> "Microphone only"
                                                    "GAME" -> "Internal game/device playback only"
                                                    "NORMAL" -> "Game playback + microphone at equal level"
                                                    else -> "Balanced mix: game playback + microphone, with microphone level boosted"
                                                },
                                                color = palette.muted, fontSize = 11.sp, lineHeight = 16.sp
                                            )
                                            Text("Actual playback capture depends on Android, the game and device support.", color = palette.muted, fontSize = 10.sp)
                                        }
                                    }
                                    MgsCard(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Text("BGMI VOICE CHAT COMPATIBILITY", color = palette.primary, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                            Text(
                                                if (accessibilityMicEnabled) "ACCESSIBILITY MICROPHONE BRIDGE ENABLED • Android microphone compatibility is available."
                                                else "Enable this Android service to let the stream capture your microphone while BGMI is using voice chat. It does not read screen content or perform gestures.",
                                                color = if (accessibilityMicEnabled) palette.primary else palette.muted,
                                                fontSize = 11.sp, lineHeight = 16.sp
                                            )
                                            MgsButton(onClick = { showAccessibilityDisclosure = true }, modifier = Modifier.fillMaxWidth()) {
                                                Text(if (accessibilityMicEnabled) "OPEN ACCESSIBILITY SETTINGS" else "ENABLE MICROPHONE COMPATIBILITY")
                                            }
                                        }
                                    }
                                }
                                "TOOLS" -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    MgsCard(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Text("FLOATING CONTROLS", color = palette.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                            Text("Optional: allow Display over other apps. Streaming does not depend on this permission; the small control bubble appears after video is actually being sent.", color = palette.muted, fontSize = 11.sp)
                                            MgsButton(onClick = { enableFloatingControls() }, modifier = Modifier.fillMaxWidth()) {
                                                Text(if (Settings.canDrawOverlays(this@MainActivity)) "SHOW FLOATING CONTROLS" else "ENABLE FLOATING CONTROLS")
                                            }
                                        }
                                    }
                                    MgsCard(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Text("CAPTURE TEST", color = palette.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                            Text("Test the H.264 encoder without connecting to a streaming platform.", color = palette.muted)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                MgsButton(onClick = { pendingLive = false; requestProjection() }, enabled = !captureActive && !broadcastActive && !broadcastStarting, modifier = Modifier.weight(1f)) { Text("START TEST") }
                                                OutlinedButton(
                                                    onClick = {
                                                        startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                                        captureActive = false
                                                        status = "Capture stopped"
                                                    },
                                                    enabled = captureActive, modifier = Modifier.weight(1f)
                                                ) { Text("STOP TEST") }
                                            }
                                        }
                                    }
                                    ModuleStatus(captureActive, broadcastActive)
                                }
                                else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Hero(status, captureActive, broadcastActive, broadcastStarting)
                                    if (!notificationsAllowed) {
                                        MgsCard(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("ENABLE NOTIFICATIONS", color = palette.primary, fontWeight = FontWeight.Bold)
                                                Text("Android notifications show capture and live-stream status, with a stop control.", color = palette.muted)
                                                MgsButton(onClick = {
                                                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                }) { Text("ALLOW NOTIFICATIONS") }
                                            }
                                        }
                                    }
                                    MgsCard(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("LIVE SESSION", color = palette.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                            Text(if (broadcastActive || broadcastStarting) status else "No active broadcast", color = if (broadcastActive) palette.primary else palette.muted)
                                            Text(if (broadcastActive && liveBitrate > 0) "${liveBitrate / 1000} kbps" else if (broadcastActive) "Waiting for bitrate…" else "0 kbps", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                                            MgsButton(onClick = { page = "STREAM" }, modifier = Modifier.fillMaxWidth()) { Text("OPEN STREAM SETTINGS") }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                MgsButton(onClick = { startBroadcastFlow() }, enabled = !broadcastActive && !broadcastStarting, modifier = Modifier.weight(1f)) { Text("GO LIVE") }
                                                OutlinedButton(onClick = { RtmpBroadcastService.requestStop(this@MainActivity) }, enabled = broadcastActive || broadcastStarting, modifier = Modifier.weight(1f)) { Text("STOP LIVE") }
                                            }
                                        }
                                    }
                                    Text("Made for mobile gameplay • Screen video • Microphone / game audio", color = palette.muted, fontSize = 10.sp)
                                }
                            }
                        }
                        NavigationBar(containerColor = palette.surface, contentColor = palette.text, tonalElevation = if (palette.isClay) 0.dp else 3.dp) {
                            listOf(
                                Triple("STUDIO", "Studio", "⌂"),
                                Triple("STREAM", "Stream", "▶"),
                                Triple("AUDIO", "Audio", "♫"),
                                Triple("TOOLS", "Tools", "⚙"),
                                Triple("SETTINGS", "Settings", "☷")
                            ).forEach { (target, label, glyph) ->
                                NavigationBarItem(
                                    selected = page == target || (target == "SETTINGS" && page in listOf("ABOUT", "TERMS", "PRIVACY")),
                                    onClick = { page = target },
                                    icon = { Text(glyph, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                                    label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                                    alwaysShowLabel = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startBroadcastFlow() {
        if (broadcastActive || broadcastStarting) { status = "Broadcast is already running or starting"; return }
        val normalizedServer = server.trim()
        if (normalizedServer.isBlank()) { status = "Enter your RTMP server URL before going live"; return }
        if (key.isBlank()) { showStreamKeyDialog = true; status = "Stream key required"; return }
        if (!StreamUrlValidator.isValidRtmpServerUrl(normalizedServer)) {
            status = "Enter a valid RTMP server URL, such as rtmp://server.example/live"; return
        }
        server = normalizedServer
        key = key.trim()
        if (rememberStreamKey) {
            try {
                StreamKeyVault.save(this, key)
            } catch (error: Exception) {
                status = "Could not securely save the stream key: " + (error.message ?: "Android Keystore unavailable")
                return
            }
        } else {
            StreamKeyVault.clear(this)
        }
        pendingLive = true
        broadcastStarting = true
        status = "Requesting screen/app selection permission…"
        requestProjection()
    }

    private fun enableFloatingControls() {
        if (Settings.canDrawOverlays(this)) {
            startService(
                Intent(this, FloatingControlService::class.java)
                    .setAction(FloatingControlService.ACTION_SHOW)
            )
            status = "Floating controls are visible over other apps"
        } else {
            waitingForOverlayPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status = "Allow Display over other apps, then return to enable the floating bubble"
        }
    }

    private fun requestProjection() {
        // Android requires RECORD_AUDIO for AudioPlaybackCapture as well as microphone input.
        // GAME mode does not start the microphone foreground-service type.
        if (pendingLive && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        status = "Choose the screen or app window to share, then confirm Android screen capture"
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    val palette = LocalMgsPalette.current
    MgsCard(
        shape = RoundedCornerShape(if (palette.isClay) 26.dp else 18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge),
        elevation = CardDefaults.cardElevation(defaultElevation = if (palette.isClay) 15.dp else 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), "Mobile Gaming Streamer", Modifier.size(48.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("YOUR MOBILE STUDIO", color = palette.muted, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.ExtraBold)
                Text("MOBILE GAMING STREAMER", color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 0.2.sp)
            }
            TextButton(onClick = onSettings) { Text("⚙", color = palette.primary, fontSize = 23.sp) }
            Column(horizontalAlignment = Alignment.End) {
                Text("BUILD", color = palette.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("0.5.25", color = palette.primary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun Hero(status: String, capture: Boolean, live: Boolean, starting: Boolean) {
    val palette = LocalMgsPalette.current
    val active = live || starting || capture
    val stateLabel = when { live -> "ON AIR"; starting -> "CONNECTING"; capture -> "CAPTURING"; else -> "STANDBY" }
    MgsCard(
        shape = RoundedCornerShape(if (palette.isClay) 34.dp else 24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge),
        elevation = CardDefaults.cardElevation(defaultElevation = if (palette.isClay) 25.dp else 10.dp)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        if (palette.isClay) listOf(Color(0xFFFFFBFF), Color(0xFFEDE8FA), Color(0xFFE6F3EF))
                        else listOf(palette.surface, palette.highlight)
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("STREAMING CONTROL CENTRE", color = palette.muted, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Your game. Your stage.", color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.Black)
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (live) Color(0xFFFFE0DE) else if (active) Color(0xFFE2F3E9) else palette.inset
                ) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).background(if (live) Color(0xFFD94E58) else if (active) Color(0xFF319B70) else palette.muted, CircleShape))
                        Text(stateLabel, color = if (live) Color(0xFFB63F4A) else if (active) Color(0xFF287B59) else palette.muted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                    }
                }
            }
            Text(
                when { live -> "YOU'RE LIVE"; starting -> "GETTING YOU ON AIR"; capture -> "CAPTURE SESSION ACTIVE"; else -> "READY WHEN YOU ARE" },
                color = palette.secondary, fontSize = 29.sp, lineHeight = 33.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
            )
            AnimatedContent(targetState = status, label = "broadcast-status") { animatedStatus ->
            Text(animatedStatus, color = palette.muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroPill("SCREEN", "Capture", active, Modifier.weight(1f))
                HeroPill("VIDEO", "H.264", active, Modifier.weight(1f))
                HeroPill("OUTPUT", "RTMP", live || starting, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroPill(label: String, value: String, active: Boolean, modifier: Modifier = Modifier) {
    val p = LocalMgsPalette.current
    Column(
        modifier.then(clayInsetModifier(RoundedCornerShape(17.dp))).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = p.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).background(if (active) Color(0xFF43A77A) else p.muted.copy(alpha = 0.55f), CircleShape))
            Text(value, color = p.text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
@Composable
private fun BroadcastPanel(
    server: String, onServer: (String) -> Unit,
    key: String, onKey: (String) -> Unit,
    rememberStreamKey: Boolean, onRememberStreamKey: (Boolean) -> Unit,
    quality: String, onQuality: (String) -> Unit,
    fps: String, onFps: (String) -> Unit,
    orientation: String, onOrientation: (String) -> Unit,
    audioMode: String, onAudioMode: (String) -> Unit,
    live: Boolean, onGoLive: () -> Unit, onStopLive: () -> Unit
) {
    val palette = LocalMgsPalette.current
    MgsCard(
        shape = RoundedCornerShape(if (palette.isClay) 32.dp else 22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge),
        elevation = CardDefaults.cardElevation(defaultElevation = if (palette.isClay) 23.dp else 9.dp)
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("BROADCAST WORKSPACE", color = palette.secondary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp)
                    Text("Configure your stream", color = palette.text, fontSize = 19.sp, fontWeight = FontWeight.Black)
                }
                Surface(shape = CircleShape, color = palette.inset) {
                    Text("01", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = palette.secondary, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
            Text("DESTINATION", color = palette.muted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
            OutlinedTextField(
                value = server, onValueChange = onServer,
                modifier = Modifier.fillMaxWidth().then(clayInsetModifier(RoundedCornerShape(17.dp))),
                singleLine = true, label = { Text("RTMP server URL") },
                shape = RoundedCornerShape(17.dp)
            )
            OutlinedTextField(
                value = key, onValueChange = onKey,
                modifier = Modifier.fillMaxWidth().then(clayInsetModifier(RoundedCornerShape(17.dp))),
                singleLine = true, label = { Text("Private stream key") },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(17.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = rememberStreamKey, onCheckedChange = onRememberStreamKey)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Remember key securely on this device", color = palette.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (rememberStreamKey) "Encrypted with Android Keystore. Never stored as plain text." else "Key stays in memory only and is cleared when the app process ends.",
                        color = palette.muted, fontSize = 9.sp, lineHeight = 13.sp
                    )
                }
            }
            Text("VIDEO PROFILE", color = palette.muted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
            ClayOptionGroup("RESOLUTION", listOf("720p", "1080p", "1440p"), quality, onQuality)
            ClayOptionGroup("FRAME RATE", listOf("30", "60"), fps, onFps, " FPS")
            ClayOptionGroup("ORIENTATION", listOf("AUTO", "LANDSCAPE", "PORTRAIT"), orientation, onOrientation)
            Text("Higher resolutions and 60 FPS depend on encoder support, game orientation, network and device temperature.", color = palette.muted, fontSize = 10.sp, lineHeight = 14.sp)

        }
    }
}

@Composable
private fun ClayOptionGroup(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    suffix: String = ""
) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = p.muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Row(
            Modifier.fillMaxWidth().then(clayInsetModifier(RoundedCornerShape(17.dp))).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { item ->
                val chosen = selected == item
                Surface(
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = if (chosen) p.highlight else Color.Transparent,
                    shadowElevation = if (chosen && p.isClay) 4.dp else 0.dp,
                    border = if (chosen) androidx.compose.foundation.BorderStroke(1.dp, p.edge) else null
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 3.dp, vertical = 8.dp)) {
                        Text(item + suffix, color = if (chosen) p.primary else p.muted, fontSize = 10.sp, fontWeight = if (chosen) FontWeight.ExtraBold else FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
@Composable
private fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit, suffix: String = "") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { item ->
            FilterChip(selected == item, onClick = { onSelect(item) }, label = { Text(item + suffix, fontSize = 10.sp) },
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModuleStatus(capture: Boolean, live: Boolean) {
    val palette = LocalMgsPalette.current
    MgsCard(shape = RoundedCornerShape(if (palette.isClay) 30.dp else 12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge), elevation = CardDefaults.cardElevation(defaultElevation = if (palette.isClay) 22.dp else 8.dp), colors = CardDefaults.cardColors(containerColor = palette.surface)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SYSTEM STATUS", color = palette.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
            StatusRow("Screen capture", if (capture || live) "ACTIVE" else "READY")
            StatusRow("H.264 encoder", if (capture || live) "SESSION STARTED" else "READY")
            StatusRow("RTMP transport", if (live) "CONNECTING" else "READY TO CONNECT")
            StatusRow("Microphone / game audio", "DEVICE DEPENDENT")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    val palette = LocalMgsPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = palette.muted, fontSize = 12.sp)
        Text(value, color = if (value == "ACTIVE" || value == "SESSION STARTED" || value == "CONNECTING") palette.primary else palette.secondary,
            fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
