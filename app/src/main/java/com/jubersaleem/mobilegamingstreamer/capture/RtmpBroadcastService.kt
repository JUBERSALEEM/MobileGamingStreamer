package com.jubersaleem.mobilegamingstreamer.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.IBinder
import android.app.Service
import android.util.DisplayMetrics
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.util.Log
import android.view.WindowManager
import com.jubersaleem.mobilegamingstreamer.MainActivity
import com.pedro.common.ConnectChecker
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/** RTMP broadcast service with microphone audio and screen video. */
class RtmpBroadcastService : Service() {
    private data class VideoProfile(
        val quality: String,
        val fps: Int,
        val width: Int,
        val height: Int,
        val bitrate: Int
    ) {
        val label: String get() = "$quality @ ${fps}fps"
    }

    private var display: GenericStream? = null
    private var activeAudioSource: AudioSource? = null
    private var activeProjection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post { if (display != null) stopBroadcast("Screen capture permission ended") }
        }
    }
    private var streamingConfirmed = false
    private var lastBitrate = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val startupTimeout = Runnable {
        if (display != null && !streamingConfirmed) {
            stopBroadcast("No outgoing video bitrate after 25 seconds. Encoder or RTMP startup failed.")
        }
    }
    private val sessionHeartbeat = object : Runnable {
        override fun run() {
            if (display != null) {
                getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
                handler.postDelayed(this, 2_000L)
            }
        }
    }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var commandReceiverRegistered = false
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START -> startBroadcast(intent)
                ACTION_STOP -> stopBroadcast()
                ACTION_SET_BITRATE -> setBitrate(intent)
                ACTION_SET_STREAM_AUDIO_MUTED -> setStreamAudioMuted(intent.getBooleanExtra(EXTRA_STREAM_AUDIO_MUTED, false))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_STOP)
            addAction(ACTION_SET_BITRATE)
            addAction(ACTION_SET_STREAM_AUDIO_MUTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(commandReceiver, filter)
        commandReceiverRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent?.let { startBroadcast(it) }
            ACTION_STOP -> stopBroadcast()
            ACTION_SET_BITRATE -> intent?.let { setBitrate(it) }
            ACTION_SET_STREAM_AUDIO_MUTED -> intent?.let { setStreamAudioMuted(it.getBooleanExtra(EXTRA_STREAM_AUDIO_MUTED, false)) }
        }
        return START_NOT_STICKY
    }

    private fun setBitrate(intent: Intent) {
        val requested = intent.getIntExtra(EXTRA_BITRATE, 4_000_000).coerceIn(500_000, 18_000_000)
        val active = display
        if (active != null) {
            active.setVideoBitrateOnFly(requested)
            update("LIVE • Target bitrate " + (requested / 1000) + " kbps")
        } else {
            publishState(false, "No active stream to adjust", 0L)
        }
    }

    private fun startBroadcast(intent: Intent) {
        handler.removeCallbacks(startupTimeout)
        streamingConfirmed = false
        lastBitrate = 0L
        display?.let { previous ->
            try { previous.release() } catch (_: Exception) { }
            display = null
        }
        releaseProjection()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = parcelableIntent(intent, EXTRA_RESULT_DATA)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        if (resultData == null || !(streamUrl.startsWith("rtmp://") || streamUrl.startsWith("rtmps://"))) {
            publishState(false, "Invalid stream setup", 0L)
            stopSelf()
            return
        }
        val audioMode = intent.getStringExtra(EXTRA_AUDIO_MODE) ?: AUDIO_MODE_BALANCED
        val usesMicrophone = audioMode != AUDIO_MODE_GAME
        publishState(false, "Preparing video encoder…", 0L)
        try {
            startForeground(
                NOTIFICATION_ID,
                notification("Preparing video encoder…"),
                if (Build.VERSION.SDK_INT >= 29) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        (if (usesMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            )
            var activeProfileLabel = "Preparing video profile"
        val checker = object : ConnectChecker {
                override fun onConnectionStarted(url: String) = update("Connecting to streaming server…")
                override fun onConnectionSuccess() = update("RTMP connected • Waiting for video frames")
                override fun onConnectionFailed(reason: String) {
                    Log.e(TAG, "RTMP connection failed: $reason")
                    stopBroadcast("Connection failed: $reason. Open the app and tap GO LIVE to grant a fresh screen-capture session and retry.")
                }
                override fun onDisconnect() {
                    handler.removeCallbacks(startupTimeout)
                    handler.removeCallbacks(sessionHeartbeat)
                    streamingConfirmed = false
                    lastBitrate = 0L
                    val disconnected = display
                    display = null
                    try { disconnected?.release() } catch (error: Exception) {
                        Log.w(TAG, "Error releasing disconnected stream", error)
                    }
                    releaseProjection()
                    publishState(false, "Broadcast disconnected. Tap GO LIVE to start a fresh capture session.", 0L)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onAuthError() {
                    stopBroadcast("Stream authentication failed. Check the stream key.")
                }
                override fun onAuthSuccess() = update("Stream key accepted")
                override fun onNewBitrate(bitrate: Long) {
                    if (bitrate > 0L) {
                        val firstConfirmation = !streamingConfirmed
                        streamingConfirmed = true
                        lastBitrate = bitrate
                        handler.removeCallbacks(startupTimeout)
                        update("LIVE • ${bitrate / 1000} kbps", bitrate)
                        if (firstConfirmation && Settings.canDrawOverlays(this@RtmpBroadcastService) &&
                            !getSharedPreferences("mgs_preferences", MODE_PRIVATE).getBoolean(com.jubersaleem.mobilegamingstreamer.overlay.FloatingControlService.KEY_BUBBLE_DISMISSED, false)) {
                            startService(Intent(this@RtmpBroadcastService, com.jubersaleem.mobilegamingstreamer.overlay.FloatingControlService::class.java))
                        }
                    } else {
                        lastBitrate = 0L
                        update("Connected • Waiting for outgoing video", 0L)
                    }
                }
            }

            val (screenWidth, screenHeight, dpi) = screenMetrics()
            val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "1080p"
            val orientation = intent.getStringExtra(EXTRA_ORIENTATION) ?: "AUTO"
            val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(30, 60)
            // Portrait is a real vertical canvas, not a rotated landscape resolution.
            val portrait = when (orientation) {
                "PORTRAIT" -> true
                "LANDSCAPE" -> false
                else -> screenHeight > screenWidth
            }
            val profileCandidates = buildVideoProfiles(quality, fps, portrait)

            // RtmpDisplay.prepareAudio() captures ONLY the microphone. It never configures
            // AudioPlaybackCapture, which is why BGMI gunshots/game effects were absent.
            // GenericStream with MixAudioSource uses the same MediaProjection token for
            // screen video + internal playback + microphone, matching RootEncoder's
            // supported screen-stream architecture.
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Android did not provide a MediaProjection session")
            activeProjection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, handler)

            val micSource = MediaRecorder.AudioSource.DEFAULT
            val preferredInput = choosePreferredMicrophoneDevice()
            val audioSource: AudioSource = when (audioMode) {
                AUDIO_MODE_MIC -> MicrophoneSource(audioSource = micSource).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setPreferredDevice(preferredInput)
                }
                AUDIO_MODE_GAME -> InternalAudioSource(mediaProjection)
                AUDIO_MODE_NORMAL -> MixAudioSource(mediaProjection, null, micSource).apply {
                    setPreferredDevice(preferredInput)
                    microphoneVolume = 1.0f
                    internalVolume = 1.0f
                }
                else -> MixAudioSource(mediaProjection, null, micSource).apply {
                    setPreferredDevice(preferredInput)
                    // Balanced preset gives voice a little more headroom than game playback.
                    microphoneVolume = 1.6f
                    internalVolume = 1.0f
                }
            }

            activeAudioSource = audioSource
            val streamMuted = getSharedPreferences(STATE_PREFS, MODE_PRIVATE).getBoolean(KEY_STREAM_AUDIO_MUTED, false)
            if (streamMuted) setAudioSourceMuted(audioSource, true)

            val stream = GenericStream(
                this,
                checker,
                ScreenSource(this, mediaProjection),
                audioSource
            )
            stream.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)

            var selectedProfile: VideoProfile? = null
            for (candidate in profileCandidates) {
                val prepared = try {
                    stream.prepareVideo(candidate.width, candidate.height, candidate.bitrate, candidate.fps, 2, 0)
                } catch (error: Exception) {
                    Log.w(TAG, "Encoder rejected ${candidate.label}; trying next profile", error)
                    false
                }
                if (prepared) {
                    selectedProfile = candidate
                    break
                }
                Log.w(TAG, "Hardware H.264 encoder cannot prepare ${candidate.label}; trying fallback")
            }
            val activeProfile = selectedProfile
                ?: throw IllegalStateException("No supported hardware H.264 profile found. Try 720p30 or check device encoder support.")
            activeProfileLabel = activeProfile.label
            val echoCancellation = audioMode != AUDIO_MODE_GAME
            val noiseSuppression = audioMode != AUDIO_MODE_GAME
            Log.i(TAG, "Audio effects requested: AEC=$echoCancellation, NS=$noiseSuppression; microphone source=DEFAULT")
            val audioPrepared = stream.prepareAudio(44_100, true, 128_000, echoCancellation, noiseSuppression)
            if (!audioPrepared) {
                throw IllegalStateException("Audio source preparation failed for mode: $audioMode")
            }
            // Apply the requested output orientation to the encoder renderer.
            // This is a single-source scene for now; custom top/bottom scene layers
            // require the separate scene-compositor milestone.
            stream.getGlInterface().setStreamIsPortrait(portrait)
            stream.getGlInterface().setForceRender(true, 15)
            display = stream
            stream.setFpsListener { outputFps ->
                handler.post {
                    if (display === stream && streamingConfirmed) {
                        update("LIVE • $activeProfileLabel • ${outputFps} FPS • ${lastBitrate / 1000} kbps", lastBitrate)
                    }
                }
            }
            handler.removeCallbacks(sessionHeartbeat)
            handler.post(sessionHeartbeat)
            publishState(false, "Encoder ready • $activeProfileLabel • Connecting…", 0L)
            update("Encoder ready • $activeProfileLabel • Connecting…")
            stream.startStream(streamUrl)
            handler.postDelayed(startupTimeout, 25_000L)
            Log.i(TAG, "RTMP broadcast requested: $activeProfileLabel, orientation=${if (portrait) "PORTRAIT" else "LANDSCAPE"}, audio=$audioMode, dpi=$dpi")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start RTMP broadcast", error)
            stopBroadcast("Unable to start: ${error.message ?: "encoder error"}")
        }
    }

    private fun buildVideoProfiles(quality: String, requestedFps: Int, portrait: Boolean): List<VideoProfile> {
        val qualityOrder = when (quality) {
            "1440p" -> listOf("1440p", "1080p", "720p")
            "720p" -> listOf("720p")
            else -> listOf("1080p", "720p")
        }
        return buildList {
            qualityOrder.forEachIndexed { index, candidateQuality ->
                val fpsOptions = if (index == 0) {
                    listOf(requestedFps, 30).distinct()
                } else if (requestedFps >= 60) {
                    listOf(60, 30)
                } else {
                    listOf(30)
                }
                fpsOptions.forEach { candidateFps ->
                    val longEdge = when (candidateQuality) {
                        "720p" -> 1280
                        "1440p" -> 2560
                        else -> 1920
                    }
                    val shortEdge = ((longEdge * 9f / 16f).toInt() / 2) * 2
                    val width = if (portrait) shortEdge else longEdge
                    val height = if (portrait) longEdge else shortEdge
                    val bitrate = when (candidateQuality) {
                        "720p" -> if (candidateFps >= 60) 6_000_000 else 4_000_000
                        "1440p" -> if (candidateFps >= 60) 18_000_000 else 14_000_000
                        else -> if (candidateFps >= 60) 12_000_000 else 8_000_000
                    }
                    add(VideoProfile(candidateQuality, candidateFps, width, height, bitrate))
                }
            }
        }
    }

    private fun stopBroadcast(message: String = "Broadcast stopped") {
        handler.removeCallbacks(startupTimeout)
        handler.removeCallbacks(sessionHeartbeat)
        streamingConfirmed = false
        lastBitrate = 0L
        val active = display
        display = null
        activeAudioSource = null
        try { active?.release() } catch (error: Exception) {
            Log.w(TAG, "Error while releasing RTMP stream", error)
        }
        releaseProjection()
        publishState(false, message, 0L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * DEFAULT allows Android's platform AEC/NS effects to be attached by RootEncoder.
     * VOICE_RECOGNITION commonly bypasses or disables those effects.
     */
    private fun choosePreferredMicrophoneDevice(): AudioDeviceInfo? {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    private fun setAudioSourceMuted(source: AudioSource?, muted: Boolean) {
        when (source) {
            is MicrophoneSource -> if (muted) source.mute() else source.unMute()
            is InternalAudioSource -> if (muted) source.mute() else source.unMute()
            is MixAudioSource -> if (muted) source.mute() else source.unMute()
        }
    }

    private fun setStreamAudioMuted(muted: Boolean) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STREAM_AUDIO_MUTED, muted).apply()
        setAudioSourceMuted(activeAudioSource, muted)
        update(if (muted) "Stream audio muted" else "Stream audio unmuted")
    }

    private fun releaseProjection() {
        val projection = activeProjection ?: return
        activeProjection = null
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) { }
        try { projection.stop() } catch (_: Exception) { }
    }

    private fun update(message: String, bitrate: Long = 0L) {
        publishState(streamingConfirmed && lastBitrate > 0L, message, if (bitrate > 0L) bitrate else lastBitrate)
        notifications.notify(NOTIFICATION_ID, notification(message))
    }

    private fun publishState(active: Boolean, message: String, bitrate: Long) {
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, active)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_BITRATE, bitrate)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATUS_ACTIVE, active)
            putExtra(EXTRA_STATUS_MESSAGE, message)
            putExtra(EXTRA_STATUS_BITRATE, bitrate)
        })
    }

    private fun notification(message: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.jubersaleem.mobilegamingstreamer.R.drawable.ic_stat_mgs)
            .setContentTitle("Mobile Gaming Streamer")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop broadcast", stop).build())
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live broadcast", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Use physical display metrics, not this app window's bounds. GO LIVE may be
        // launched from portrait while the selected capture app (e.g. BGMI) is landscape.
        wm.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    @Suppress("DEPRECATION")
    private fun parcelableIntent(intent: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Intent::class.java)
        else intent.getParcelableExtra(key)

    // Do not end a live broadcast when the launcher removes the Activity task.
    // The foreground service owns the broadcast until explicit STOP or projection revocation.

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (commandReceiverRegistered) {
            try { unregisterReceiver(commandReceiver) } catch (_: IllegalArgumentException) { }
            commandReceiverRegistered = false
        }
        streamingConfirmed = false
        lastBitrate = 0L
        try { display?.release() } catch (_: Exception) { }
        display = null
        activeAudioSource = null
        releaseProjection()
        publishState(false, "Broadcast service stopped", 0L)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jubersaleem.mobilegamingstreamer.action.START_RTMP"
        const val ACTION_STOP = "com.jubersaleem.mobilegamingstreamer.action.STOP_RTMP"
        const val ACTION_SET_BITRATE = "com.jubersaleem.mobilegamingstreamer.action.SET_BITRATE"
        const val ACTION_SET_STREAM_AUDIO_MUTED = "com.jubersaleem.mobilegamingstreamer.action.SET_STREAM_AUDIO_MUTED"
        const val EXTRA_STREAM_AUDIO_MUTED = "stream_audio_muted"
        const val KEY_STREAM_AUDIO_MUTED = "stream_audio_muted"

        /** Shared stop entry point used by the app UI, notification and floating controls. */
        fun requestStop(context: Context) {
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
        }
        fun requestBitrate(context: Context, bitrate: Int) {
            context.sendBroadcast(Intent(ACTION_SET_BITRATE).setPackage(context.packageName).putExtra(EXTRA_BITRATE, bitrate))
        }
        fun requestStreamAudioMuted(context: Context, muted: Boolean) {
            context.sendBroadcast(Intent(ACTION_SET_STREAM_AUDIO_MUTED).setPackage(context.packageName)
                .putExtra(EXTRA_STREAM_AUDIO_MUTED, muted))
        }
        const val EXTRA_BITRATE = "video_bitrate"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FPS = "fps"
        const val EXTRA_ORIENTATION = "orientation"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val AUDIO_MODE_MIC = "MIC"
        const val AUDIO_MODE_GAME = "GAME"
        const val AUDIO_MODE_BOTH = "BOTH"
        const val AUDIO_MODE_NORMAL = "NORMAL"
        const val AUDIO_MODE_BALANCED = "BALANCED"
        const val ACTION_STATUS = "com.jubersaleem.mobilegamingstreamer.action.RTMP_STATUS"
        const val EXTRA_STATUS_ACTIVE = "status_active"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_STATUS_BITRATE = "status_bitrate"
        const val STATE_PREFS = "broadcast_state"
        const val KEY_ACTIVE = "active"
        const val KEY_MESSAGE = "message"
        const val KEY_BITRATE = "bitrate"
        const val KEY_HEARTBEAT = "service_heartbeat"
        private const val TAG = "MGS-RTMP"
        private const val CHANNEL_ID = "live_broadcast"
        private const val NOTIFICATION_ID = 1002
    }
}
