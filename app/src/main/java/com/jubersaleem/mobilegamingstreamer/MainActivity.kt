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

private val Bg = Color(0xFF080C11)
private val Panel = Color(0xFF121A23)
private val Lime = Color(0xFFC4FF5C)
private val Cyan = Color(0xFF63D8E8)
private val Muted = Color(0xFF91A0AE)

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Ready to stream")
    private var liveBitrate by mutableStateOf(0L)
    private var page by mutableStateOf("HOME")
    private var captureActive by mutableStateOf(false)
    private var broadcastActive by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(true)
    private var server by mutableStateOf("rtmp://a.rtmp.youtube.com/live2")
    private var key by mutableStateOf("")
    private var quality by mutableStateOf("1080p")
    private var fps by mutableStateOf("30")
    private var orientation by mutableStateOf("AUTO")
    private var pendingLive by mutableStateOf(false)
    private var waitingForOverlayPermission = false

    private val broadcastStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RtmpBroadcastService.ACTION_STATUS) return
            broadcastActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_ACTIVE, false)
            status = intent.getStringExtra(RtmpBroadcastService.EXTRA_STATUS_MESSAGE) ?: status
            liveBitrate = intent.getLongExtra(RtmpBroadcastService.EXTRA_STATUS_BITRATE, 0L)
        }
    }

    private fun restoreBroadcastState() {
        val prefs = getSharedPreferences(RtmpBroadcastService.STATE_PREFS, MODE_PRIVATE)
        broadcastActive = prefs.getBoolean(RtmpBroadcastService.KEY_ACTIVE, false)
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
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                status = "Overlay permission granted • continuing stream setup"
                requestProjection()
            } else {
                pendingLive = false
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
            status = "Microphone permission is required for a stable stream with voice audio"
        }
    }

    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            status = "Screen sharing permission was not granted"
            pendingLive = false
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
            }
            startForegroundService(liveIntent)
            if (Settings.canDrawOverlays(this)) startService(Intent(this, FloatingControlService::class.java))
            broadcastActive = true
            status = "Connecting to RTMP server"
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Lime, onPrimary = Color(0xFF11170A), background = Bg,
                surface = Panel, onBackground = Color.White, onSurface = Color.White,
                secondary = Cyan
            )) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Header()
                        Hero(status, captureActive, broadcastActive)
                        if (!notificationsAllowed) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF382B1D))) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ENABLE NOTIFICATIONS", color = Lime, fontWeight = FontWeight.Bold)
                                    Text("Android notifications show capture and live-stream status, with a stop control.", color = Muted)
                                    Button(onClick = {
                                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }) { Text("ALLOW NOTIFICATIONS") }
                                }
                            }
                        }
                        if (page == "SETUP") {
                            OutlinedButton(onClick = { page = "HOME" }) { Text("← BACK TO STUDIO") }
                            BroadcastPanel(server, { server = it }, key, { key = it },
                                quality, { quality = it }, fps, { fps = it }, orientation, { orientation = it },
                                broadcastActive,
                                onGoLive = {
                                    if (broadcastActive) status = "Broadcast is already running"
                                    else if (server.isBlank() || key.isBlank()) status = "Enter your server URL and stream key"
                                    else if (!server.startsWith("rtmp://") && !server.startsWith("rtmps://")) status = "Server must begin with rtmp:// or rtmps://"
                                    else {
                                        pendingLive = true
                                        if (!Settings.canDrawOverlays(this@MainActivity)) {
                                            waitingForOverlayPermission = true
                                            status = "Allow Display over other apps to show the floating stream bubble"
                                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
                                        } else {
                                            requestProjection()
                                        }
                                    }
                                },
                                onStopLive = { startService(Intent(this@MainActivity, RtmpBroadcastService::class.java).setAction(RtmpBroadcastService.ACTION_STOP)) }
                            )
                        } else {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("LIVE SESSION", color = Lime, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text(if (broadcastActive) status else "No active broadcast", color = if (broadcastActive) Lime else Muted)
                                    Text(if (broadcastActive && liveBitrate > 0) "${liveBitrate / 1000} kbps" else if (broadcastActive) "Waiting for bitrate…" else "0 kbps", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                                    OutlinedButton(onClick = { enableFloatingControls() }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (Settings.canDrawOverlays(this@MainActivity)) "OPEN FLOATING CONTROLS" else "ENABLE FLOATING CONTROLS")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { page = "SETUP" }, modifier = Modifier.weight(1f)) { Text("BROADCAST SETUP") }
                                        OutlinedButton(onClick = { startService(Intent(this@MainActivity, RtmpBroadcastService::class.java).setAction(RtmpBroadcastService.ACTION_STOP)) }, enabled = broadcastActive, modifier = Modifier.weight(1f)) { Text("STOP LIVE") }
                                    }
                                }
                            }
                        }
                        if (page == "HOME") Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("CAPTURE TEST", color = Lime, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Text("Test the H.264 encoder without connecting to a streaming platform.", color = Muted)
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
                        Text("Screen video + microphone • Internal game audio is next", color = Muted, fontSize = 11.sp)
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
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status = "Allow Display over other apps, then return and tap OPEN FLOATING CONTROLS"
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_launcher_foreground), "Mobile Gaming Streamer", Modifier.size(48.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("MOBILE GAMING", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
            Text("STREAMER", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        }
        Text("DEV 0.5.0", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Hero(status: String, capture: Boolean, live: Boolean) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF1C3035), Panel, Bg))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("YOUR STREAMING STUDIO", color = Muted, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                Text(if (live) "LIVE / CONNECTING" else if (capture) "CAPTURING" else "STANDBY",
                    color = if (live || capture) Lime else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (live) "Going\nlive." else if (capture) "Capture\nis running." else "Ready when\nyou are.",
                fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text(status, color = Muted, fontSize = 12.sp)
            Text("SCREEN CAPTURE  /  H.264  /  RTMP", color = Lime, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
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
    live: Boolean, onGoLive: () -> Unit, onStopLive: () -> Unit
) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("BROADCAST SETUP", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text("Connect to YouTube or another RTMP platform", color = Muted, fontSize = 12.sp)
            OutlinedTextField(
                value = server, onValueChange = onServer, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("RTMP server URL") }
            )
            OutlinedTextField(
                value = key, onValueChange = onKey, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Stream key") },
                visualTransformation = PasswordVisualTransformation()
            )
            Text("VIDEO QUALITY", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("720p", "1080p", "1440p"), quality, onQuality)
            Text("FRAME RATE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("30", "60"), fps, onFps, " FPS")
            Text("ORIENTATION", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ChoiceRow(listOf("AUTO", "LANDSCAPE", "PORTRAIT"), orientation, onOrientation)
            Text("Higher resolutions and 60 FPS depend on encoder support, game orientation, network and device temperature.",
                color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            Button(
                onClick = if (live) onStopLive else onGoLive,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (live) Color(0xFFB94D54) else Lime,
                    contentColor = if (live) Color.White else Color(0xFF11170A))
            ) { Text(if (live) "STOP BROADCAST" else "GO LIVE • SCREEN + MIC", fontWeight = FontWeight.ExtraBold) }
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
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SYSTEM STATUS", color = Lime, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
            StatusRow("Screen capture", if (capture || live) "ACTIVE" else "READY")
            StatusRow("H.264 encoder", if (capture || live) "SESSION STARTED" else "READY")
            StatusRow("RTMP transport", if (live) "CONNECTING" else "READY TO CONNECT")
            StatusRow("Microphone / game audio", "NEXT MILESTONE")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = if (value == "ACTIVE" || value == "SESSION STARTED" || value == "CONNECTING") Lime else Cyan,
            fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
