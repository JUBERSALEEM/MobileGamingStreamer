package com.jubersaleem.mobilegamingstreamer.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.jubersaleem.mobilegamingstreamer.MainActivity
import com.pedro.common.ConnectChecker
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.rtmp.RtmpDisplay

/** RTMP broadcast service with microphone audio and screen video. */
class RtmpBroadcastService : Service() {
    private var display: RtmpDisplay? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBroadcast()
            ACTION_START -> startBroadcast(intent)
            ACTION_SET_BITRATE -> {
                val requested = intent.getIntExtra(EXTRA_BITRATE, 4_000_000).coerceIn(500_000, 12_000_000)
                val active = display
                if (active != null) {
                    active.setVideoBitrateOnFly(requested)
                    update("LIVE • Target bitrate ${requested / 1000} kbps")
                } else {
                    publishState(false, "No active stream to adjust", 0L)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startBroadcast(intent: Intent) {
        display?.let { previous ->
            try { previous.stopStream() } catch (_: Exception) { }
            display = null
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = parcelableIntent(intent, EXTRA_RESULT_DATA)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        if (resultData == null || !(streamUrl.startsWith("rtmp://") || streamUrl.startsWith("rtmps://"))) {
            publishState(false, "Invalid stream setup", 0L)
            stopSelf()
            return
        }

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
                override fun onConnectionSuccess() = update("RTMP connected • Waiting for video bitrate")
                override fun onConnectionFailed(reason: String) {
                    Log.e(TAG, "RTMP connection failed: $reason")
                    stopBroadcast("Connection failed: $reason")
                }
                override fun onDisconnect() {
                    display = null
                    publishState(false, "Broadcast disconnected", 0L)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onAuthError() {
                    stopBroadcast("Stream authentication failed. Check the stream key.")
                }
                override fun onAuthSuccess() = update("Stream key accepted")
                override fun onNewBitrate(bitrate: Long) {
                    update("LIVE • ${bitrate / 1000} kbps", bitrate)
                }
            }

            val (screenWidth, screenHeight, dpi) = screenMetrics()
            val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "1080p"
            val orientation = intent.getStringExtra(EXTRA_ORIENTATION) ?: "AUTO"
            val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(30, 60)
            val maxEdge = when (quality) {
                "720p" -> 1280
                "1440p" -> 2560
                else -> 1920
            }
            val sourceLong = maxOf(screenWidth, screenHeight).toFloat()
            val sourceShort = minOf(screenWidth, screenHeight).toFloat()
            val aspect = sourceLong / sourceShort
            val longEdge = minOf(maxEdge, sourceLong.toInt())
            val shortEdge = (longEdge / aspect).toInt()
            var width = if (orientation == "PORTRAIT") shortEdge else longEdge
            var height = if (orientation == "PORTRAIT") longEdge else shortEdge
            if (orientation == "AUTO") {
                width = if (screenWidth >= screenHeight) longEdge else shortEdge
                height = if (screenWidth >= screenHeight) shortEdge else longEdge
            }
            width = (width / 2) * 2
            height = (height / 2) * 2
            val bitrate = when (quality) {
                "720p" -> 4_000_000
                "1440p" -> 12_000_000
                else -> 8_000_000
            }

            // OpenGL is required for MediaProjection display input; force rendering keeps static screens producing frames.
            val rtmp = RtmpDisplay(this, true, checker)
            rtmp.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.HARDWARE)
            rtmp.setIntentResult(resultCode, resultData)
            val prepared = rtmp.prepareVideo(width, height, fps, bitrate, 0, dpi)
            if (!prepared) throw IllegalStateException("H.264 video encoder preparation failed for ${width}x${height} at ${fps} FPS")
            // A valid audio source is initialized before streaming. RootEncoder documents screen-only frame starvation on some devices.
            val audioPrepared = rtmp.prepareAudio(128_000, 44_100, true, false, false)
            if (!audioPrepared) throw IllegalStateException("Microphone/AAC preparation failed. Check microphone permission and device audio encoder.")
            val actualFps = fps /* fallback below is no longer needed after successful prepareVideo */
            /* if (prepared) fps else if (
                fps == 60 && rtmp.prepareVideo(width, height, 30, bitrate, 0, dpi)
            ) {
                update("60 FPS unavailable; using 30 FPS")
                30
            } else {
                throw IllegalStateException("This device cannot prepare the selected H.264 stream format")
            } */
            rtmp.glInterface.setForceRender(true)
            display = rtmp
            publishState(true, "Encoder ready • Connecting…", 0L)
            update("Encoder ready • Connecting…")
            rtmp.startStream(streamUrl)
            Log.i(TAG, "RTMP broadcast requested: ${width}x${height} @ ${actualFps}fps")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start RTMP broadcast", error)
            stopBroadcast("Unable to start: ${error.message ?: "encoder error"}")
        }
    }

    private fun stopBroadcast(message: String = "Broadcast stopped") {
        val active = display
        display = null
        try { active?.stopStream() } catch (error: Exception) {
            Log.w(TAG, "Error while stopping RTMP stream", error)
        }
        publishState(false, message, 0L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(message: String, bitrate: Long = 0L) {
        publishState(true, message, bitrate)
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
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RtmpBroadcastService::class.java).setAction(ACTION_STOP),
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    @Suppress("DEPRECATION")
    private fun parcelableIntent(intent: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, Intent::class.java)
        else intent.getParcelableExtra(key)

    override fun onDestroy() {
        try { display?.stopStream() } catch (_: Exception) { }
        display = null
        publishState(false, "Broadcast service stopped", 0L)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jubersaleem.mobilegamingstreamer.action.START_RTMP"
        const val ACTION_STOP = "com.jubersaleem.mobilegamingstreamer.action.STOP_RTMP"
        const val ACTION_SET_BITRATE = "com.jubersaleem.mobilegamingstreamer.action.SET_BITRATE"
        const val EXTRA_BITRATE = "video_bitrate"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FPS = "fps"
        const val EXTRA_ORIENTATION = "orientation"
        const val ACTION_STATUS = "com.jubersaleem.mobilegamingstreamer.action.RTMP_STATUS"
        const val EXTRA_STATUS_ACTIVE = "status_active"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_STATUS_BITRATE = "status_bitrate"
        const val STATE_PREFS = "broadcast_state"
        const val KEY_ACTIVE = "active"
        const val KEY_MESSAGE = "message"
        const val KEY_BITRATE = "bitrate"
        private const val TAG = "MGS-RTMP"
        private const val CHANNEL_ID = "live_broadcast"
        private const val NOTIFICATION_ID = 1002
    }
}
