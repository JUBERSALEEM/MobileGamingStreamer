package com.jubersaleem.mobilegamingstreamer

import android.Manifest
import android.content.Intent
import android.content.ComponentName
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
import com.jubersaleem.mobilegamingstreamer.capture.RtmpBroadcastService
import com.jubersaleem.mobilegamingstreamer.capture.ScreenCaptureService
import com.jubersaleem.mobilegamingstreamer.overlay.FloatingControlService

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Ready to stream")
    private var liveBitrate by mutableStateOf(0L)
    private var page by mutableStateOf("SETUP")
    private var themeMode by mutableStateOf("CLASSIC")
    private var showAccessibilityDisclosure by mutableStateOf(false)
    private var accessibilityDisclosureAccepted by mutableStateOf(false)
    private var captureActive by mutableStateOf(false)
    private var broadcastActive by mutableStateOf(false)
    private var broadcastStarting by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(true)
    private var accessibilityEnabled by mutableStateOf(false)
    private var server by mutableStateOf("rtmp://a.rtmp.youtube.com/live2")
    private var key by mutableStateOf("")
    private var quality by mutableStateOf("1080p")
    private var fps by mutableStateOf("30")
    private var orientation by mutableStateOf("LANDSCAPE")
    private var audioMode by mutableStateOf("BALANCED")
    private var pendingLive by mutableStateOf(false)
    private var waitingForOverlayPermission = false
    private var waitingForAccessibility = false

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
        broadcastActive = prefs.getBoolean(RtmpBroadcastService.KEY_ACTIVE, false)
        broadcastStarting = false
        status = prefs.getString(RtmpBroadcastService.KEY_MESSAGE, "Ready to stream") ?: "Ready to stream"
        liveBitrate = prefs.getLong(RtmpBroadcastService.KEY_BITRATE, 0L)
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
        accessibilityEnabled = isAccessibilityServiceEnabled()
        if (waitingForAccessibility) {
            waitingForAccessibility = false
            if (accessibilityEnabled) {
                status = "Microphone concurrency enabled • continue screen capture setup"
                if (pendingLive) requestProjection()
            } else {
                pendingLive = false
                broadcastStarting = false
                status = "Accessibility microphone access was not enabled"
            }
        }
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, com.jubersaleem.mobilegamingstreamer.capture.MicCompatibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchProjectionConsent()
        else {
            pendingLive = false
            broadcastStarting = false
            status = "Microphone permission is required for voice audio"
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
        accessibilityDisclosureAccepted = appPreferences.getBoolean("accessibility_disclosure_accepted", false)
        setContent {
            MgsTheme(mode = themeMode) {
                val palette = LocalMgsPalette.current
                if (showAccessibilityDisclosure) {
                    AlertDialog(
                        onDismissRequest = { showAccessibilityDisclosure = false; pendingLive = false; broadcastStarting = false },
                        title = { Text("Microphone compatibility service") },
                        text = {
                            Text("Mobile Gaming Streamer uses Android Accessibility Service only to help keep microphone capture available while a game such as BGMI is using voice chat. The service does not subscribe to accessibility events, read screen text or content, inspect UI controls, click, type, or automate gameplay. When you start a stream, the microphone and any capturable game audio are sent to the RTMP destination you enter. You can turn this service off at any time in Android Settings.")
                        },
                        confirmButton = {
                            Button(onClick = {
                                showAccessibilityDisclosure = false
                                accessibilityDisclosureAccepted = true
                                getSharedPreferences("mgs_preferences", MODE_PRIVATE).edit().putBoolean("accessibility_disclosure_accepted", true).apply()
                                if (isAccessibilityServiceEnabled()) {
                                    waitingForAccessibility = false
                                    if (pendingLive) requestProjection()
                                } else {
                                    waitingForAccessibility = true
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }) { Text("I AGREE • CONTINUE") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAccessibilityDisclosure = false; pendingLive = false; broadcastStarting = false }) { Text("NOT NOW") }
                        }
                    )
                }
                Surface(Modifier.fillMaxSize(), color = palette.background) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Header()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf("SETUP" to "Studio", "SETTINGS" to "Settings", "ABOUT" to "About").forEach { (target, label) ->
                                FilterChip(selected = page == target, onClick = { page = target }, label = { Text(label, fontSize = 11.sp) }, modifier = Modifier.weight(1f))
                            }
                        }
                        if (page == "SETTINGS") SettingsPage(themeMode, { selected ->
                            themeMode = selected
                            getSharedPreferences("mgs_preferences", MODE_PRIVATE).edit().putString("theme_mode", selected).apply()
                        }, onTerms = { page = "TERMS" }, onPrivacy = { page = "PRIVACY" })
                        else if (page == "ABOUT") AboutPage(onTerms = { page = "TERMS" }, onPrivacy = { page = "PRIVACY" })
                        else if (page == "TERMS") LegalPage(title = "Terms & Conditions", body = TermsText, onBack = { page = "SETTINGS" })
                        else if (page == "PRIVACY") LegalPage(title = "Privacy Policy", body = PrivacyText, onBack = { page = "SETTINGS" })
                        if (page != "SETTINGS" && page != "ABOUT" && page != "TERMS" && page != "PRIVACY") {
                        Hero(status, captureActive, broadcastActive, broadcastStarting)
                        if (!notificationsAllowed) {
                            Card(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ENABLE NOTIFICATIONS", color = palette.primary, fontWeight = FontWeight.Bold)
                                    Text("Android notifications show capture and live-stream status, with a stop control.", color = palette.muted)
                                    Button(onClick = {
                                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }) { Text("ALLOW NOTIFICATIONS") }
                                }
                            }
                        }
                        if (!accessibilityEnabled) {
                            Card(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ENABLE IN-GAME MIC COMPATIBILITY", color = palette.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text("Android can silence a normal recorder while BGMI is using voice chat. Enable this app's Accessibility service so the stream can capture your microphone concurrently. This service does not read screen content.", color = palette.muted, fontSize = 11.sp, lineHeight = 15.sp)
                                    Button(onClick = { showAccessibilityDisclosure = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text("OPEN ACCESSIBILITY SETTINGS")
                                    }
                                }
                            }
                        }
                        if (page == "SETUP") {
                            BroadcastPanel(server, { server = it }, key, { key = it },
                                quality, { quality = it }, fps, { fps = it }, orientation, { orientation = it },
                                audioMode, { audioMode = it },
                                broadcastActive || broadcastStarting,
                                onGoLive = {
                                    if (broadcastActive) status = "Broadcast is already running"
                                    else if (server.isBlank() || key.isBlank()) status = "Enter your server URL and stream key"
                                    else if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) status = "Server must begin with rtmp:// or rtmps://"
                                    else {
                                        pendingLive = true
                                        broadcastStarting = true
                                        if (!accessibilityDisclosureAccepted) {
                                            showAccessibilityDisclosure = true
                                            status = "Review the microphone compatibility disclosure to continue"
                                        } else if (!isAccessibilityServiceEnabled()) {
                                            waitingForAccessibility = true
                                            status = "Enable microphone compatibility in Android Accessibility settings"
                                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        } else {
                                            status = "Requesting screen/app selection permission…"
                                            requestProjection()
                                        }
                                    }
                                },
                                onStopLive = { RtmpBroadcastService.requestStop(this@MainActivity) }
                            )
                            Text("FLOATING CONTROLS", color = palette.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("Optional: allow Display over other apps. Streaming does not depend on this permission; the small control bubble appears after video is actually being sent.", color = palette.muted, fontSize = 11.sp)
                            OutlinedButton(onClick = { enableFloatingControls() }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (Settings.canDrawOverlays(this@MainActivity)) "SHOW FLOATING CONTROLS" else "ENABLE FLOATING CONTROLS")
                            }
                        } else {
                            Card(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("LIVE SESSION", color = palette.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text(if (broadcastActive) status else "No active broadcast", color = if (broadcastActive) palette.primary else palette.muted)
                                    Text(if (broadcastActive && liveBitrate > 0) "${liveBitrate / 1000} kbps" else if (broadcastActive) "Waiting for bitrate…" else "0 kbps", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                                    OutlinedButton(onClick = { enableFloatingControls() }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (Settings.canDrawOverlays(this@MainActivity)) "OPEN FLOATING CONTROLS" else "ENABLE FLOATING CONTROLS")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { page = "SETUP" }, modifier = Modifier.weight(1f)) { Text("BROADCAST SETUP") }
                                        OutlinedButton(onClick = { RtmpBroadcastService.requestStop(this@MainActivity) }, enabled = broadcastActive || broadcastStarting, modifier = Modifier.weight(1f)) { Text("STOP LIVE") }
                                    }
                                }
                            }
                        }
                        if (page == "HOME") Card(colors = CardDefaults.cardColors(containerColor = palette.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("CAPTURE TEST", color = palette.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Text("Test the H.264 encoder without connecting to a streaming platform.", color = palette.muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { pendingLive = false; requestProjection() },
                                        enabled = !captureActive && !broadcastActive,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("START TEST") }
                                    OutlinedButton(
                                        onClick = {
                                            startService(Intent(this@MainActivity, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
                                            captureActive = false
                                            status = "Capture stopped"
                                        },
                                        enabled = captureActive,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("STOP TEST") }
                                }
                            }
                        }
                        if (page == "HOME") ModuleStatus(captureActive, broadcastActive)
                        Text("Screen video • Microphone / game audio selectable", color = palette.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    private fun enableFloatingControls() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingControlService::class.java))
            status = "Floating controls are visible over other apps"
        } else {
            waitingForOverlayPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status = "Allow Display over other apps, then return to enable the floating bubble"
        }
    }

    private fun requestProjection() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
private fun Header() {
    val palette = LocalMgsPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_launcher_foreground), "Mobile Gaming Streamer", Modifier.size(48.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("MOBILE GAMING", color = palette.muted, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
            Text("STREAMER", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        }
        Text("DEV 0.5.6", color = palette.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Hero(status: String, capture: Boolean, live: Boolean, starting: Boolean) {
    val palette = LocalMgsPalette.current
    Card(shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge), elevation = CardDefaults.cardElevation(defaultElevation = 10.dp), colors = CardDefaults.cardColors(containerColor = palette.surface)) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(palette.highlight, palette.surface, palette.background))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("YOUR STREAMING STUDIO", color = palette.muted, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                Text(if (live) "LIVE" else if (starting) "CONNECTING" else if (capture) "CAPTURING" else "STANDBY",
                    color = if (live || starting || capture) palette.primary else palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (live) "You're\nlive." else if (starting) "Starting\nstream." else if (capture) "Capture\nis running." else "Ready when\nyou are.",
                fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text(status, color = palette.muted, fontSize = 12.sp)
            Text("SCREEN CAPTURE  /  H.264  /  RTMP", color = palette.primary, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BroadcastPanel(
    server: String, onServer: (String) -> Unit,
    key: String, onKey: (String) -> Unit,
    quality: String, onQuality: (String) -> Unit,
    fps: String, onFps: (String) -> Unit,
    orientation: String, onOrientation: (String) -> Unit,
    audioMode: String, onAudioMode: (String) -> Unit,
    live: Boolean, onGoLive: () -> Unit, onStopLive: () -> Unit
) {
    val palette = LocalMgsPalette.current
    Card(shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge), elevation = CardDefaults.cardElevation(defaultElevation = 10.dp), colors = CardDefaults.cardColors(containerColor = palette.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("BROADCAST SETUP", color = palette.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text("Connect to YouTube or another RTMP platform", color = palette.muted, fontSize = 12.sp)
            OutlinedTextField(
                value = server, onValueChange = onServer, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("RTMP server URL") }
            )
            OutlinedTextField(
                value = key, onValueChange = onKey, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Stream key") },
                visualTransformation = PasswordVisualTransformation()
            )
            Text("VIDEO QUALITY", color = palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("720p", "1080p", "1440p"), quality, onQuality)
            Text("FRAME RATE", color = palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("30", "60"), fps, onFps, " FPS")
            Text("ORIENTATION", color = palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("AUTO", "LANDSCAPE", "PORTRAIT"), orientation, onOrientation)
            Text("AUDIO SOURCE", color = palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("MIC", "GAME", "NORMAL", "BALANCED"), audioMode, onAudioMode)
            Text(when (audioMode) { "MIC" -> "Microphone only"; "GAME" -> "Internal game/device playback only"; "NORMAL" -> "Game playback + microphone at equal level"; else -> "Balanced mix: game playback + microphone, with microphone level boosted" }, color = palette.muted, fontSize = 10.sp, lineHeight = 14.sp)
            Text("Higher resolutions and 60 FPS depend on encoder support, game orientation, network and device temperature.",
                color = palette.muted, fontSize = 10.sp, lineHeight = 14.sp)
            Button(
                onClick = if (live) onStopLive else onGoLive,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (live) Color(0xFFB94D54) else palette.primary,
                    contentColor = if (live) Color.White else Color(0xFF11170A))
            ) { Text(if (live) "STOP BROADCAST" else "GO LIVE • SELECT APP", fontWeight = FontWeight.ExtraBold) }
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
    Card(border = androidx.compose.foundation.BorderStroke(1.dp, palette.edge), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp), colors = CardDefaults.cardColors(containerColor = palette.surface)) {
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
