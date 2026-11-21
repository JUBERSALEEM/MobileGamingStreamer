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
import com.jubersaleem.mobilegamingstreamer.scene.MAX_SCENE_NAME_CHARS
import com.jubersaleem.mobilegamingstreamer.scene.MAX_STATIC_OVERLAY_LAYERS
import com.jubersaleem.mobilegamingstreamer.scene.MAX_STORED_SCENES
import com.jubersaleem.mobilegamingstreamer.scene.MAX_STYLE_BORDER_PX
import com.jubersaleem.mobilegamingstreamer.scene.MAX_STYLE_RADIUS_PX
import com.jubersaleem.mobilegamingstreamer.scene.MAX_STYLE_SHADOW_PX
import com.jubersaleem.mobilegamingstreamer.scene.MAX_TEXT_LAYER_CHARS
import com.jubersaleem.mobilegamingstreamer.scene.SceneConfigCodec
import com.jubersaleem.mobilegamingstreamer.scene.SceneLayerKind
import com.jubersaleem.mobilegamingstreamer.scene.SceneOverlayAnchor
import com.jubersaleem.mobilegamingstreamer.scene.SceneTextLayerLayout
import com.jubersaleem.mobilegamingstreamer.scene.StreamScene
import com.jubersaleem.mobilegamingstreamer.scene.StreamSceneLayer

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
    private var aecActive by mutableStateOf(false)
    private var nsActive by mutableStateOf(false)
    private var voiceOptimizationActive by mutableStateOf(false)
    private var micSource by mutableStateOf(AudioRouteLabels.SOURCE_NONE)
    private var headsetConnected by mutableStateOf(false)
    private var streamAudioMuted by mutableStateOf(false)
    private var audioCaptureActive by mutableStateOf(false)
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
    private var lastAnnouncedKind = BroadcastStatusKind.STANDBY
    private var scenes by mutableStateOf(listOf<StreamScene>())
    private var activeSceneId by mutableStateOf<String?>(null)
    private var editingSceneId by mutableStateOf<String?>(null)
    private var sceneDeleteTargetId by mutableStateOf<String?>(null)
    private var sceneError by mutableStateOf<String?>(null)
    private var pendingImageSceneId: String? = null
    private var pendingImageReplaceLayerId: String? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onImagePicked(uri)
    }

    private val broadcastStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RtmpBroadcastService.ACTION_STATUS) return
            broadcastActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_ACTIVE, false)
            status = intent.getStringExtra(RtmpBroadcastService.EXTRA_STATUS_MESSAGE) ?: status
            liveBitrate = intent.getLongExtra(RtmpBroadcastService.EXTRA_STATUS_BITRATE, 0L)
            aecActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_AEC, false)
            nsActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_NS, false)
            voiceOptimizationActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_VOICE, false)
            micSource = intent.getIntExtra(RtmpBroadcastService.EXTRA_STATUS_MIC_SOURCE, AudioRouteLabels.SOURCE_NONE)
            headsetConnected = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_HEADSET, false)
            streamAudioMuted = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_STREAM_MUTED, false)
            audioCaptureActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_CAPTURE, false)
            broadcastStarting = !broadcastActive && isStartingBroadcastMessage(status)
            val kind = classifyBroadcastStatus(status, broadcastActive, broadcastStarting)
            if (kind != lastAnnouncedKind) {
                lastAnnouncedKind = kind
                window.decorView?.announceForAccessibility(broadcastAnnouncement(kind, status))
            }
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
        // Seed the announcement tracker silently so a cold start never speaks.
        lastAnnouncedKind = classifyBroadcastStatus(status, broadcastActive, broadcastStarting)
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
                // H2 scene seam: an explicit launch-intent extra (default
                // false) selects the compositor for device validation via
                // `am start ... --ez scene_source true`. The H5 editor path
                // below activates the same seam only with a valid scene;
                // normal launches omit both, keeping the ScreenSource
                // production path identical.
                val adbSceneSource =
                    getIntent().getBooleanExtra(RtmpBroadcastService.EXTRA_SCENE_SOURCE, false)
                val sceneConfig = SceneConfigCodec.activeSceneEncoding(scenes, activeSceneId)
                putExtra(
                    RtmpBroadcastService.EXTRA_SCENE_SOURCE,
                    adbSceneSource || sceneConfig != null
                )
                // H3 text layer: forwarded content (absent/null = no overlay).
                putExtra(RtmpBroadcastService.EXTRA_SCENE_TEXT,
                    getIntent().getStringExtra(RtmpBroadcastService.EXTRA_SCENE_TEXT))
                // H4 second overlay: forwarded content (absent/null = no layer).
                putExtra(RtmpBroadcastService.EXTRA_SCENE_TEXT2,
                    getIntent().getStringExtra(RtmpBroadcastService.EXTRA_SCENE_TEXT2))
                // H5 scene delivery: encoded active scene for the service
                // (parsed through the pure codec + H4 validation there).
                // Omitted on the legacy ADB path, which keeps H2/H3/H4
                // extras behavior as the validation override.
                if (!adbSceneSource && sceneConfig != null) {
                    putExtra(RtmpBroadcastService.EXTRA_SCENE_CONFIG, sceneConfig)
                }
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
        // H5 scenes: persisted source of truth, reloaded on every create
        // (including Activity recreation); mutations write through.
        val stored = ScenePrefsStore.load(this)
        scenes = stored.scenes
        activeSceneId = stored.activeSceneId
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
                        aecActive = aecActive,
                        nsActive = nsActive,
                        voiceOptimizationActive = voiceOptimizationActive,
                        micSource = micSource,
                        headsetConnected = headsetConnected,
                        streamAudioMuted = streamAudioMuted,
                        audioCaptureActive = audioCaptureActive,
                        floatingControlsAvailable = Settings.canDrawOverlays(this@MainActivity),
                        server = server,
                        key = key,
                        rememberStreamKey = rememberStreamKey,
                        quality = quality,
                        fps = fps,
                        orientation = orientation,
                        audioMode = audioMode,
                        showStreamKeyDialog = showStreamKeyDialog,
                        showAccessibilityDisclosure = showAccessibilityDisclosure,
                        scenes = scenes,
                        activeSceneId = activeSceneId,
                        editingSceneId = editingSceneId,
                        sceneDeleteTargetName = scenes.firstOrNull { it.id == sceneDeleteTargetId }?.name,
                        sceneError = sceneError
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
                        onAudioModeChange = { audioMode = it },
                        onCreateScene = { createScene() },
                        onActiveScene = { id ->
                            activeSceneId = id
                            editingSceneId = id
                            persistScenes()
                            // H8 live switch: USE while streaming also
                            // pushes the fresh active encoding (or none).
                            // Courtesy gate — the service no-session
                            // guard is authoritative.
                            if (broadcastActive || broadcastStarting) {
                                RtmpBroadcastService.requestSceneUpdate(
                                    this@MainActivity,
                                    SceneConfigCodec.activeSceneEncoding(scenes, activeSceneId)
                                )
                            }
                        },
                        onEditScene = { id -> editingSceneId = id },
                        onDuplicateScene = { id -> duplicateScene(id) },
                        onDeleteScene = { id -> sceneDeleteTargetId = id },
                        onConfirmDeleteScene = { confirmDeleteScene() },
                        onDismissDeleteScene = { sceneDeleteTargetId = null },
                        onRenameScene = { id, name -> renameScene(id, name) },
                        onAddLayer = { id -> addLayer(id) },
                        onAddImageLayer = { id -> launchImagePicker(id, null) },
                        onReplaceImageLayer = { sceneId, layerId -> launchImagePicker(sceneId, layerId) },
                        onRemoveLayer = { sceneId, layerId -> removeLayer(sceneId, layerId) },
                        onMoveLayer = { sceneId, layerId, towardFront ->
                            moveLayer(sceneId, layerId, towardFront)
                        },
                        onToggleLayer = { sceneId, layerId -> toggleLayer(sceneId, layerId) },
                        onLayerText = { sceneId, layerId, text ->
                            updateLayerText(sceneId, layerId, text)
                        },
                        onLayerAnchor = { sceneId, layerId, anchor ->
                            updateLayer(sceneId, layerId) { it.copy(anchor = anchor) }
                        },
                        onLayerMargin = { sceneId, layerId, margin ->
                            updateLayer(sceneId, layerId) {
                                it.copy(marginPx = margin.coerceIn(0, SCENE_LAYER_MAX_MARGIN_UI_PX))
                            }
                        },
                        onLayerMaxWidth = { sceneId, layerId, maxWidth ->
                            updateLayer(sceneId, layerId) {
                                it.copy(
                                    maxWidthPx = maxWidth.coerceIn(
                                        SCENE_LAYER_MIN_MAX_WIDTH_UI_PX,
                                        SCENE_LAYER_MAX_MAX_WIDTH_UI_PX
                                    )
                                )
                            }
                        },
                        onLayerAlpha = { sceneId, layerId, alpha ->
                            updateLayer(sceneId, layerId) {
                                it.copy(alpha = alpha.coerceIn(0f, 1f))
                            }
                        },
                        onLayerTextColor = { sceneId, layerId, color ->
                            updateLayer(sceneId, layerId) { it.copy(textColor = color) }
                        },
                        onLayerBackground = { sceneId, layerId, color ->
                            updateLayer(sceneId, layerId) { it.copy(backgroundColor = color) }
                        },
                        onLayerBorderWidth = { sceneId, layerId, width ->
                            updateLayer(sceneId, layerId) {
                                it.copy(borderWidthPx = width.coerceIn(0, MAX_STYLE_BORDER_PX))
                            }
                        },
                        onLayerBorderColor = { sceneId, layerId, color ->
                            updateLayer(sceneId, layerId) { it.copy(borderColor = color) }
                        },
                        onLayerCornerRadius = { sceneId, layerId, radius ->
                            updateLayer(sceneId, layerId) {
                                it.copy(cornerRadiusPx = radius.coerceIn(0, MAX_STYLE_RADIUS_PX))
                            }
                        },
                        onLayerShadowOffset = { sceneId, layerId, offset ->
                            updateLayer(sceneId, layerId) {
                                it.copy(shadowOffsetPx = offset.coerceIn(0, MAX_STYLE_SHADOW_PX))
                            }
                        },
                        onLayerShadowColor = { sceneId, layerId, color ->
                            updateLayer(sceneId, layerId) { it.copy(shadowColor = color) }
                        },
                        onDismissSceneError = { sceneError = null }
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

    // ---- H5 scene editor mutations (write-through to prefs) ----

    private fun persistScenes() {
        ScenePrefsStore.save(this, scenes, activeSceneId ?: "")
    }

    private fun updateScene(id: String, transform: (StreamScene) -> StreamScene) {
        scenes = scenes.map { if (it.id == id) transform(it).normalizedZ() else it }
        persistScenes()
    }

    private fun updateLayer(
        sceneId: String,
        layerId: String,
        transform: (StreamSceneLayer) -> StreamSceneLayer
    ) {
        updateScene(sceneId) { current ->
            current.copy(
                layers = current.layers.map { if (it.id == layerId) transform(it) else it }
            )
        }
    }

    private fun newLayerText(text: String, id: String, zOrder: Int) = StreamSceneLayer(
        id = id,
        text = text,
        anchor = SceneOverlayAnchor.TOP_LEFT,
        zOrder = zOrder,
        marginPx = SceneTextLayerLayout.marginPxFor(1080),
        maxWidthPx = 1920,
        alpha = 1f
    )

    private fun createScene() {
        if (scenes.size >= MAX_STORED_SCENES) {
            sceneError = "Scene limit reached ($MAX_STORED_SCENES). Delete a scene to add another."
            return
        }
        val scene = StreamScene(
            id = SceneConfigCodec.freshSceneId(scenes),
            name = SceneConfigCodec.freshSceneName(scenes),
            layers = listOf(newLayerText("New text", "layer-1", 1))
        )
        scenes = scenes + scene
        editingSceneId = scene.id
        sceneError = null
        persistScenes()
    }

    private fun duplicateScene(id: String) {
        val source = scenes.firstOrNull { it.id == id } ?: return
        if (scenes.size >= MAX_STORED_SCENES) {
            sceneError = "Scene limit reached ($MAX_STORED_SCENES). Delete a scene to add another."
            return
        }
        val copy = source.copy(
            id = SceneConfigCodec.freshSceneId(scenes),
            name = SceneConfigCodec.duplicateName(scenes, source.name)
        )
        scenes = scenes + copy
        editingSceneId = copy.id
        sceneError = null
        persistScenes()
    }

    private fun confirmDeleteScene() {
        val id = sceneDeleteTargetId ?: return
        sceneDeleteTargetId = null
        var remaining = scenes.filterNot { it.id == id }
        if (remaining.isEmpty()) remaining = listOf(SceneConfigCodec.defaultScene())
        scenes = remaining
        if (remaining.none { it.id == activeSceneId }) activeSceneId = remaining.first().id
        if (editingSceneId == id) editingSceneId = null
        persistScenes()
    }

    private fun renameScene(id: String, name: String) {
        // Raw transient text (blank allowed while typing); the codec
        // repairs names on persist/delivery, so stored form is valid.
        scenes = scenes.map {
            if (it.id == id) it.copy(name = name.take(MAX_SCENE_NAME_CHARS)) else it
        }
        persistScenes()
    }

    private fun addLayer(sceneId: String) {
        val scene = scenes.firstOrNull { it.id == sceneId } ?: return
        if (scene.layers.size >= MAX_STATIC_OVERLAY_LAYERS) {
            sceneError = "Layer limit reached ($MAX_STATIC_OVERLAY_LAYERS per scene)."
            return
        }
        updateScene(sceneId) { current ->
            current.copy(
                layers = current.layers + newLayerText(
                    "New text",
                    SceneConfigCodec.freshLayerId(current),
                    current.layers.size + 1
                )
            )
        }
        sceneError = null
    }

    private fun launchImagePicker(sceneId: String, replaceLayerId: String?) {
        pendingImageSceneId = sceneId
        pendingImageReplaceLayerId = replaceLayerId
        imagePicker.launch(arrayOf("image/*"))
    }

    private fun onImagePicked(uri: Uri?) {
        val sceneId = pendingImageSceneId
        pendingImageSceneId = null
        val replaceLayerId = pendingImageReplaceLayerId
        pendingImageReplaceLayerId = null
        if (uri == null || sceneId == null) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            sceneError = "Couldn't keep access to that image. Pick it again."
            return
        }
        if (replaceLayerId != null) {
            updateLayer(sceneId, replaceLayerId) { it.copy(imageUri = uri.toString()) }
        } else {
            addImageLayer(sceneId, uri.toString())
        }
        sceneError = null
    }

    private fun addImageLayer(sceneId: String, imageUri: String) {
        val scene = scenes.firstOrNull { it.id == sceneId } ?: return
        if (scene.layers.size >= MAX_STATIC_OVERLAY_LAYERS) {
            sceneError = "Layer limit reached ($MAX_STATIC_OVERLAY_LAYERS per scene)."
            return
        }
        updateScene(sceneId) { current ->
            current.copy(
                layers = current.layers + StreamSceneLayer(
                    id = SceneConfigCodec.freshLayerId(current),
                    text = "",
                    anchor = SceneOverlayAnchor.TOP_LEFT,
                    zOrder = current.layers.size + 1,
                    marginPx = SceneTextLayerLayout.marginPxFor(1080),
                    maxWidthPx = SCENE_IMAGE_DEFAULT_MAX_WIDTH_PX,
                    alpha = 1f,
                    kind = SceneLayerKind.IMAGE,
                    imageUri = imageUri
                )
            )
        }
        sceneError = null
    }

    private fun removeLayer(sceneId: String, layerId: String) {
        updateScene(sceneId) { current ->
            current.copy(layers = current.layers.filterNot { it.id == layerId })
        }
    }

    private fun moveLayer(sceneId: String, layerId: String, towardFront: Boolean) {
        val scene = scenes.firstOrNull { it.id == sceneId } ?: return
        val index = scene.layers.indexOfFirst { it.id == layerId }
        if (index < 0) return
        // List order is back-to-front; towardFront moves later in the list.
        val swapWith = if (towardFront) index + 1 else index - 1
        if (swapWith !in scene.layers.indices) return // Edge: ignore, never crash.
        updateScene(sceneId) { current ->
            val reordered = current.layers.toMutableList()
            val moved = reordered.removeAt(index)
            reordered.add(swapWith, moved)
            current.copy(layers = reordered)
        }
    }

    private fun toggleLayer(sceneId: String, layerId: String) {
        updateLayer(sceneId, layerId) { it.copy(enabled = !it.enabled) }
    }

    private fun updateLayerText(sceneId: String, layerId: String, text: String) {
        // Raw transient text (blank allowed while typing); blank/invalid
        // layers are dropped from streaming and flagged in the preview.
        updateLayer(sceneId, layerId) { it.copy(text = text.take(MAX_TEXT_LAYER_CHARS)) }
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
