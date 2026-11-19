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

/** Video-only RTMP broadcast service. Audio is deliberately not prepared in this milestone. */
class RtmpBroadcastService : Service() {
    private var display: RtmpDisplay? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBroadcast()
            ACTION_START -> startBroadcast(intent)
        }
        return START_NOT_STICKY
    }

    private fun startBroadcast(intent: Intent) {
        if (display != null) stopBroadcast()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = parcelableIntent(intent, EXTRA_RESULT_DATA)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        if (resultData == null || !(streamUrl.startsWith("rtmp://") || streamUrl.startsWith("rtmps://"))) {
            stopSelf()
            return
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                notification("Preparing video encoder…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            val checker = object : ConnectChecker {
                override fun onConnectionStarted(url: String) = update("Connecting to streaming server…")
                override fun onConnectionSuccess() = update("LIVE • Video broadcast connected")
                override fun onConnectionFailed(reason: String) {
                    Log.e(TAG, "RTMP connection failed: $reason")
                    update("Connection failed: $reason")
                    stopBroadcast()
                }
                override fun onDisconnect() = update("Broadcast disconnected")
                override fun onAuthError() {
                    update("Stream authentication failed. Check the stream key.")
                    stopBroadcast()
                }
                override fun onAuthSuccess() = update("Stream key accepted")
                override fun onNewBitrate(bitrate: Long) {
                    update("LIVE • ${bitrate / 1000} kbps")
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

            val rtmp = RtmpDisplay(this, false, checker)
            rtmp.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.HARDWARE)
            rtmp.setIntentResult(resultCode, resultData)
            if (!rtmp.prepareVideo(width, height, fps, bitrate, 0, dpi)) {
                if (fps == 60 && rtmp.prepareVideo(width, height, 30, bitrate, 0, dpi)) {
                    update("60 FPS unavailable; using 30 FPS")
                } else {
                    throw IllegalStateException("This device cannot prepare the selected H.264 stream format")
                }
            }
            display = rtmp
            update("Encoder ready • Connecting…")
            rtmp.startStream(streamUrl)
            Log.i(TAG, "RTMP broadcast requested: ${width}x${height} @ ${fps}fps")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start RTMP broadcast", error)
            update("Unable to start: ${error.message ?: "encoder error"}")
            stopBroadcast()
        }
    }

    private fun stopBroadcast() {
        val active = display
        display = null
        try { active?.stopStream() } catch (error: Exception) {
            Log.w(TAG, "Error while stopping RTMP stream", error)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(message: String) {
        notifications.notify(NOTIFICATION_ID, notification(message))
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
            .setSmallIcon(android.R.drawable.presence_video_online)
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
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jubersaleem.mobilegamingstreamer.action.START_RTMP"
        const val ACTION_STOP = "com.jubersaleem.mobilegamingstreamer.action.STOP_RTMP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FPS = "fps"
        const val EXTRA_ORIENTATION = "orientation"
        private const val TAG = "MGS-RTMP"
        private const val CHANNEL_ID = "live_broadcast"
        private const val NOTIFICATION_ID = 1002
    }
}
