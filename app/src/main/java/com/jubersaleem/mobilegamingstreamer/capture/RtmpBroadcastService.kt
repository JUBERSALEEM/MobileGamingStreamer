package com.jubersaleem.mobilegamingstreamer.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
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
import android.util.DisplayMetrics
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.media.MediaRecorder
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
class RtmpBroadcastService : AccessibilityService() {
    private var display: GenericStream? = null
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
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var commandReceiverRegistered = false
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START -> startBroadcast(intent)
                ACTION_STOP -> stopBroadcast()
                ACTION_SET_BITRATE -> setBitrate(intent)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            packageNames = null
            flags = 0
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
            addAction(ACTION_SET_BITRATE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(commandReceiver, filter)
        commandReceiverRegistered = true
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACCESSIBILITY_READY, true).apply()
        Log.i(TAG, "Accessibility microphone-concurrency service connected and command receiver ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() { }

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

        publishState(false, "Preparing video encoder…", 0L)
        try {
            startForeground(
                NOTIFICATION_ID,
                notification("Preparing video encoder…"),
                if (Build.VERSION.SDK_INT >= 30) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            )
            val checker = object : ConnectChecker {
                override fun onConnectionStarted(url: String) = update("Connecting to streaming server…")
                override fun onConnectionSuccess() = update("RTMP connected • Waiting for video frames")
                override fun onConnectionFailed(reason: String) {
                    Log.e(TAG, "RTMP connection failed: $reason")
                    stopBroadcast("Connection failed: $reason")
                }
                override fun onDisconnect() {
                    handler.removeCallbacks(startupTimeout)
                    streamingConfirmed = false
                    lastBitrate = 0L
                    val disconnected = display
                    display = null
                    try { disconnected?.release() } catch (error: Exception) {
                        Log.w(TAG, "Error releasing disconnected stream", error)
                    }
                    releaseProjection()
                    publishState(false, "Broadcast disconnected", 0L)
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
                        if (firstConfirmation && Settings.canDrawOverlays(this@RtmpBroadcastService)) {
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
            val maxLongEdge = when (quality) {
                "720p" -> 1280
                "1440p" -> 2560
                else -> 1920
            }

            // Use standard 16:9 landscape or 9:16 portrait output dimensions.
            // Portrait is a real vertical canvas, not a rotated landscape resolution.
            val portrait = when (orientation) {
                "PORTRAIT" -> true
                "LANDSCAPE" -> false
                else -> screenHeight > screenWidth
            }
            val width: Int
            val height: Int
            if (portrait) {
                width = ((maxLongEdge * 9f / 16f).toInt() / 2) * 2
                height = maxLongEdge
            } else {
                width = maxLongEdge
                height = ((maxLongEdge * 9f / 16f).toInt() / 2) * 2
            }
            val bitrate = when (quality) {
                "720p" -> if (fps >= 60) 6_000_000 else 4_000_000
                "1440p" -> if (fps >= 60) 18_000_000 else 14_000_000
                else -> if (fps >= 60) 12_000_000 else 8_000_000
            }

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

            val audioMode = intent.getStringExtra(EXTRA_AUDIO_MODE) ?: AUDIO_MODE_BALANCED
            val audioSource: AudioSource = when (audioMode) {
                AUDIO_MODE_MIC -> MicrophoneSource(audioSource = MediaRecorder.AudioSource.MIC)
                AUDIO_MODE_GAME -> InternalAudioSource(mediaProjection)
                AUDIO_MODE_NORMAL -> MixAudioSource(mediaProjection, null, MediaRecorder.AudioSource.MIC).apply {
                    microphoneVolume = 1.0f
                    internalVolume = 1.0f
                }
                else -> MixAudioSource(mediaProjection, null, MediaRecorder.AudioSource.MIC).apply {
                    // Balanced preset gives voice a little more headroom than game playback.
                    microphoneVolume = 1.6f
                    internalVolume = 1.0f
                }
            }

            val stream = GenericStream(
                this,
                checker,
                ScreenSource(this, mediaProjection),
                audioSource
            )
            stream.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)

            val videoPrepared = stream.prepareVideo(width, height, bitrate, fps, 2, 0)
            if (!videoPrepared) {
                throw IllegalStateException("H.264 video encoder preparation failed for ${width}x${height} @ ${fps} FPS")
            }
            val echoCancellation = audioMode != AUDIO_MODE_GAME
            val noiseSuppression = audioMode != AUDIO_MODE_GAME
            val audioPrepared = stream.prepareAudio(44_100, true, 128_000, echoCancellation, noiseSuppression)
            if (!audioPrepared) {
                throw IllegalStateException("Audio source preparation failed for mode: $audioMode")
            }
            // Portrait Shorts/Reels canvas: keep landscape gameplay centered in a
            // 16:9 viewport. RootEncoder clears unused upper/lower canvas to black,
            // reserving those bands for the overlay/template compositor milestone.
            stream.getGlInterface().setStreamIsPortrait(portrait)
            stream.getGlInterface().setForceRender(true, 15)
            display = stream
            publishState(false, "Encoder ready • Connecting…", 0L)
            update("Encoder ready • Connecting…")
            stream.startStream(streamUrl)
            handler.postDelayed(startupTimeout, 25_000L)
            Log.i(TAG, "RTMP broadcast requested: ${width}x${height} @ ${fps}fps, orientation=${if (portrait) "PORTRAIT" else "LANDSCAPE"}, audio=$audioMode, dpi=$dpi")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start RTMP broadcast", error)
            stopBroadcast("Unable to start: ${error.message ?: "encoder error"}")
        }
    }

    private fun stopBroadcast(message: String = "Broadcast stopped") {
        handler.removeCallbacks(startupTimeout)
        streamingConfirmed = false
        lastBitrate = 0L
        val active = display
        display = null
        try { active?.release() } catch (error: Exception) {
            Log.w(TAG, "Error while releasing RTMP stream", error)
        }
        releaseProjection()
        publishState(false, message, 0L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        releaseProjection()
        publishState(false, "Broadcast service stopped", 0L)
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACCESSIBILITY_READY, false).apply()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jubersaleem.mobilegamingstreamer.action.START_RTMP"
        const val ACTION_STOP = "com.jubersaleem.mobilegamingstreamer.action.STOP_RTMP"
        const val ACTION_SET_BITRATE = "com.jubersaleem.mobilegamingstreamer.action.SET_BITRATE"

        /** Shared stop entry point used by the app UI, notification and floating controls. */
        fun requestStop(context: Context) {
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
        }
        fun requestBitrate(context: Context, bitrate: Int) {
            context.sendBroadcast(Intent(ACTION_SET_BITRATE).setPackage(context.packageName).putExtra(EXTRA_BITRATE, bitrate))
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
        const val KEY_ACCESSIBILITY_READY = "accessibility_ready"
        private const val TAG = "MGS-RTMP"
        private const val CHANNEL_ID = "live_broadcast"
        private const val NOTIFICATION_ID = 1002
    }
}
