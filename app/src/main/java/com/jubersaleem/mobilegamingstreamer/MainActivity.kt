package com.jubersaleem.mobilegamingstreamer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jubersaleem.mobilegamingstreamer.capture.MgsMicAccessibilityService
import com.jubersaleem.mobilegamingstreamer.capture.RtmpBroadcastService
import com.jubersaleem.mobilegamingstreamer.capture.ScreenCaptureService
import com.jubersaleem.mobilegamingstreamer.overlay.FloatingControlService

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Ready to stream")
    private var liveBitrate by mutableStateOf(0L)
    private var page by mutableStateOf("STUDIO")
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
        rememberStreamKey = appPreferences.getBoolean("remember_stream_key", true)
        key = if (rememberStreamKey) StreamKeyVault.load(this).orEmpty() else ""
        setContent {
            MgsTheme {
                BackHandler(enabled = page != "STUDIO") {
                    page = when (page) {
                        "ABOUT", "TERMS", "PRIVACY" -> "SETTINGS"
                        else -> "STUDIO"
                    }
                }
                StudioShell(
                    state = StudioUiState(
                        page = page,
                        status = status,
                        captureActive = captureActive,
                        broadcastActive = broadcastActive,
                        broadcastStarting = broadcastStarting,
                        liveBitrate = liveBitrate,
                        notificationsAllowed = notificationsAllowed,
                        accessibilityMicEnabled = accessibilityMicEnabled,
                        floatingControlsAvailable = Settings.canDrawOverlays(this@MainActivity),
                        server = server,
                        key = key,
                        rememberStreamKey = rememberStreamKey,
                        quality = quality,
                        fps = fps,
                        orientation = orientation,
                        audioMode = audioMode,
                        showStreamKeyDialog = showStreamKeyDialog,
                        showAccessibilityDisclosure = showAccessibilityDisclosure
                    ),
                    actions = StudioActions(
                        onPageChange = { target -> page = target },
                        onGoLive = { startBroadcastFlow() },
                        onStopLive = { RtmpBroadcastService.requestStop(this@MainActivity) },
                        onAllowNotifications = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onStartCaptureTest = {
                            pendingLive = false
                            requestProjection()
                        },
                        onStopCaptureTest = {
                            startService(
                                Intent(this@MainActivity, ScreenCaptureService::class.java)
                                    .setAction(ScreenCaptureService.ACTION_STOP)
                            )
                            captureActive = false
                            status = "Capture stopped"
                        },
                        onEnableFloatingControls = { enableFloatingControls() },
                        onEnableAccessibility = { showAccessibilityDisclosure = true },
                        onDismissAccessibility = { showAccessibilityDisclosure = false },
                        onConfirmAccessibility = {
                            showAccessibilityDisclosure = false
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onDismissStreamKey = { showStreamKeyDialog = false },
                        onConfirmStreamKey = {
                            showStreamKeyDialog = false
                            startBroadcastFlow()
                        },
                        onServerChange = { server = it },
                        onKeyChange = { key = it },
                        onRememberStreamKeyChange = { enabled ->
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
                        onQualityChange = { quality = it },
                        onFpsChange = { fps = it },
                        onOrientationChange = { orientation = it },
                        onAudioModeChange = { audioMode = it }
                    )
                )
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
