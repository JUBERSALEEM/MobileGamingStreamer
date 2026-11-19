package com.jubersaleem.mobilegamingstreamer.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.jubersaleem.mobilegamingstreamer.MainActivity

/**
 * Capture validation milestone: obtains a user-approved MediaProjection session,
 * drains ImageReader frames safely, and logs capture dimensions and frame cadence.
 * Frames are not encoded, saved, or broadcast.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var reportStartedAtMs = 0L
    private var framesSinceReport = 0L
    private var lastFrameTimestampNs = 0L
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        // Avoid leaving an earlier capture session attached if Start is tapped again.
        releaseCaptureResources()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = getResultData(intent) ?: run {
            Log.e(TAG, "Capture start aborted: missing MediaProjection permission result")
            stopSelf()
            return
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped or revoked by the system/user")
                    releaseCaptureResources()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, mainHandler)

            val (width, height, density) = displaySize()
            reportStartedAtMs = SystemClock.elapsedRealtime()
            framesSinceReport = 0L
            lastFrameTimestampNs = 0L
            lastFrameWidth = width
            lastFrameHeight = height

            imageReader = ImageReader.newInstance(
                width,
                height,
                android.graphics.PixelFormat.RGBA_8888,
                3
            ).also { reader ->
                reader.setOnImageAvailableListener({ source ->
                    drainLatestFrame(source)
                }, mainHandler)
            }

            virtualDisplay = projection?.createVirtualDisplay(
                "MobileGamingStreamerCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )

            if (virtualDisplay == null) {
                throw IllegalStateException("Android did not create the capture virtual display")
            }
            Log.i(TAG, "Capture started: ${width}x${height}, densityDpi=$density")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start screen capture", error)
            stopCapture()
        }
    }

    private fun drainLatestFrame(source: ImageReader) {
        var frame: Image? = null
        try {
            frame = source.acquireLatestImage() ?: return
            framesSinceReport++
            lastFrameWidth = frame.width
            lastFrameHeight = frame.height

            val nowMs = SystemClock.elapsedRealtime()
            val elapsedMs = nowMs - reportStartedAtMs
            if (elapsedMs >= REPORT_INTERVAL_MS) {
                val averageFps = framesSinceReport * 1000.0 / elapsedMs
                val cadenceMs = if (lastFrameTimestampNs > 0L) {
                    (frame.timestamp - lastFrameTimestampNs) / 1_000_000.0
                } else {
                    0.0
                }
                Log.i(
                    TAG,
                    "Capture stats: ${lastFrameWidth}x${lastFrameHeight}, " +
                        "averageFps=${"%.1f".format(java.util.Locale.US, averageFps)}, " +
                        "lastFrameIntervalMs=${"%.1f".format(java.util.Locale.US, cadenceMs)}"
                )
                reportStartedAtMs = nowMs
                framesSinceReport = 0L
            }
            lastFrameTimestampNs = frame.timestamp
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed while consuming a captured frame", error)
        } finally {
            frame?.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun getResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    @Suppress("DEPRECATION")
    private fun displaySize(): Triple<Int, Int, Int> {
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

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen capture",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mobile Gaming Streamer")
            .setContentText("Screen capture is active. Nothing is being broadcast.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop capture", stopIntent).build())
            .build()
    }

    private fun stopCapture() {
        val activeProjection = projection
        projection = null
        releaseCaptureResources()
        activeProjection?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCaptureResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        projection = null
    }

    override fun onDestroy() {
        releaseCaptureResources()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jubersaleem.mobilegamingstreamer.action.START_CAPTURE"
        const val ACTION_STOP = "com.jubersaleem.mobilegamingstreamer.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "MGS-Capture"
        private const val REPORT_INTERVAL_MS = 5_000L
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
    }
}
