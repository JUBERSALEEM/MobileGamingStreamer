package com.jubersaleem.mobilegamingstreamer.overlay

import android.app.Service
import android.media.AudioManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jubersaleem.mobilegamingstreamer.R
import com.jubersaleem.mobilegamingstreamer.capture.RtmpBroadcastService
import kotlin.math.abs

/** Compact recorder-style bubble. The expanded controls are shown only on user tap. */
class FloatingControlService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: ImageView? = null
    private var panel: View? = null
    private var chat: WebView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dismissTarget: TextView? = null
    private var dismissingBubble = false
    private var panelParams: WindowManager.LayoutParams? = null
    private val dimRunnable = Runnable { bubble?.alpha = 0.38f }
    private var statusReceiverRegistered = false
    private var stopButton: Button? = null
    private var speakerButton: Button? = null
    private var statusLabel: TextView? = null
    private var broadcastActive = false
    private var broadcastStarting = false
    private var broadcastMessage = "Broadcast setup ready"
    private var speakerMuted = false
    private var streamAudioMuted = false
    private var streamAudioButton: Button? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RtmpBroadcastService.ACTION_STATUS) return
            broadcastActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_ACTIVE, false)
            broadcastMessage = intent.getStringExtra(RtmpBroadcastService.EXTRA_STATUS_MESSAGE) ?: "Broadcast status updated"
            broadcastStarting = !broadcastActive && isStartingMessage(broadcastMessage)
            renderBroadcastState()
            if (!broadcastActive && !broadcastStarting && bubble == null) stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(RtmpBroadcastService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(broadcastReceiver, filter)
        statusReceiverRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps first", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences("mgs_preferences", MODE_PRIVATE)
        if (intent?.action == ACTION_SHOW) prefs.edit().putBoolean(KEY_BUBBLE_DISMISSED, false).apply()
        if (prefs.getBoolean(KEY_BUBBLE_DISMISSED, false)) { stopSelf(); return START_NOT_STICKY }
        refreshBroadcastState()
        if (bubble == null) createBubble()
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        speakerMuted = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        streamAudioMuted = getSharedPreferences(RtmpBroadcastService.STATE_PREFS, MODE_PRIVATE).getBoolean(RtmpBroadcastService.KEY_STREAM_AUDIO_MUTED, false)
        renderBroadcastState()
        renderSpeakerState()
        renderStreamAudioState()
        scheduleDim()
        return START_NOT_STICKY
    }

    private fun createBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val secureFlags = WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mgs_bubble)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF101820.toInt())
                setStroke(dp(2), 0xFFC4FF5C.toInt())
            }
            alpha = 1f
            contentDescription = "Open Mobile Gaming Streamer controls"
        }
        val lp = WindowManager.LayoutParams(dp(42), dp(42), type, secureFlags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(180)
        }
        bubbleParams = lp
        bubble = icon
        wm.addView(icon, lp)
        createDismissTarget(type)
        icon.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var initialX = 0
            private var initialY = 0
            private var dragged = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        initialX = lp.x
                        initialY = lp.y
                        dragged = false
                        dismissingBubble = false
                        brighten()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(5) || abs(dy) > dp(5)) { dragged = true; showDismissTarget() }
                        val bounds = android.graphics.Rect()
                        wm.defaultDisplay.getRectSize(bounds)
                        lp.x = (initialX - dx.toInt()).coerceIn(0, (bounds.width() - dp(42)).coerceAtLeast(0))
                        lp.y = (initialY + dy.toInt()).coerceIn(0, (bounds.height() - dp(42)).coerceAtLeast(0))
                        wm.updateViewLayout(icon, lp)
                        dismissTarget?.let { target ->
                            val location = IntArray(2); target.getLocationOnScreen(location)
                            val cx = event.rawX.toInt(); val cy = event.rawY.toInt()
                            dismissingBubble = cx >= location[0] && cx <= location[0] + target.width && cy >= location[1] && cy <= location[1] + target.height
                            target.alpha = if (dismissingBubble) 1f else 0.72f
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val shouldDismiss = dismissingBubble
                        hideDismissTarget()
                        if (dragged && shouldDismiss) dismissBubbleByDrag() else if (!dragged) togglePanel()
                        scheduleDim()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> { hideDismissTarget(); scheduleDim(); return true
                    }
                }
                return false
            }
        })
        createPanel(type, secureFlags)
    }

    private fun createPanel(type: Int, flags: Int) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(0xF018222D.toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "MGS  STREAM CONTROLS"
            setTextColor(0xFFC4FF5C.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        val minimize = Button(this).apply { text = "—"; setOnClickListener { panel?.visibility = View.GONE; bubble?.visibility = View.VISIBLE; scheduleDim() } }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(minimize, LinearLayout.LayoutParams(dp(44), dp(42)))
        header.addView(Button(this).apply { text = "×"; contentDescription = "Hide floating controls"; setOnClickListener { dismissBubbleByDrag() } }, LinearLayout.LayoutParams(dp(44), dp(42)))
        root.addView(header)

        val status = TextView(this).apply {
            text = "Broadcast setup ready"
            setTextColor(0xFFE8EEF5.toInt())
            textSize = 11f
            setPadding(0, dp(3), 0, dp(8))
        }
        statusLabel = status
        root.addView(status)
        val label = TextView(this).apply {
            text = "VIDEO BITRATE"
            setTextColor(0xFF63D8E8.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(label)
        val bitrateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(4000, 6000, 8000, 12000, 16000, 18000).forEach { kbps ->
            val button = Button(this).apply {
                text = "${kbps / 1000}M"
                textSize = 10f
                setPadding(dp(1), 0, dp(1), 0)
                setOnClickListener {
                    RtmpBroadcastService.requestBitrate(this@FloatingControlService, kbps * 1000)
                    status.text = "Requested ${kbps} kbps video"
                }
            }
            bitrateRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(bitrateRow)

        val chatLabel = TextView(this).apply {
            text = "YOUTUBE LIVE CHAT"
            setTextColor(0xFF63D8E8.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(9), 0, dp(3))
        }
        root.addView(chatLabel)
        val videoId = EditText(this).apply {
            hint = "Live video ID (until YouTube account linking)"
            setSingleLine(true)
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF91A0AE.toInt())
        }
        root.addView(videoId, LinearLayout.LayoutParams(-1, dp(44)))
        val chatButton = Button(this).apply {
            text = "OPEN LIVE CHAT"
            setOnClickListener {
                val id = videoId.text.toString().trim()
                if (!id.matches(Regex("[A-Za-z0-9_-]{6,}"))) {
                    Toast.makeText(this@FloatingControlService, "Enter a valid YouTube live video ID", Toast.LENGTH_SHORT).show()
                } else {
                    val web = chat ?: WebView(this@FloatingControlService).also {
                        it.settings.javaScriptEnabled = true
                        it.settings.domStorageEnabled = true
                        it.webChromeClient = WebChromeClient()
                        it.webViewClient = WebViewClient()
                        chat = it
                        root.addView(it, LinearLayout.LayoutParams(-1, dp(220)))
                    }
                    web.visibility = View.VISIBLE
                    web.loadUrl("https://www.youtube.com/live_chat?v=$id&is_popout=1")
                }
            }
        }
        root.addView(chatButton)
        val speaker = Button(this).apply {
            textSize = 11f
            setOnClickListener { toggleSpeakerMute() }
        }
        speakerButton = speaker
        root.addView(speaker)
        val streamAudio = Button(this).apply {
            textSize = 11f
            setOnClickListener {
                streamAudioMuted = !streamAudioMuted
                RtmpBroadcastService.requestStreamAudioMuted(this@FloatingControlService, streamAudioMuted)
                renderStreamAudioState()
            }
        }
        streamAudioButton = streamAudio
        root.addView(streamAudio)

        val stop = Button(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                when {
                    broadcastActive -> {
                        isEnabled = false
                        text = "STOPPING…"
                        RtmpBroadcastService.requestStop(this@FloatingControlService)
                    }
                    broadcastStarting -> {
                        isEnabled = false
                        text = "CANCELLING…"
                        RtmpBroadcastService.requestStop(this@FloatingControlService)
                    }
                    else -> {
                        val launch = Intent(this@FloatingControlService, com.jubersaleem.mobilegamingstreamer.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(launch)
                    }
                }
            }
        }
        stopButton = stop
        root.addView(stop)

        val lp = WindowManager.LayoutParams(dp(320), dp(480), type, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv(), PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(250)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panelParams = lp
        panel = root
        root.visibility = View.GONE
        wm.addView(root, lp)
    }


    private fun createDismissTarget(type: Int) {
        val target = TextView(this).apply {
            text = "DROP TO CLOSE"
            gravity = Gravity.CENTER
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE34D59.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            alpha = 0.82f
        }
        val params = WindowManager.LayoutParams(dp(88), dp(88), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(34) }
        dismissTarget = target
        wm.addView(target, params)
        target.visibility = View.GONE
    }

    private fun showDismissTarget() { dismissTarget?.visibility = View.VISIBLE }
    private fun hideDismissTarget() { dismissTarget?.visibility = View.GONE; dismissingBubble = false }

    private fun dismissBubbleByDrag() {
        getSharedPreferences("mgs_preferences", MODE_PRIVATE).edit().putBoolean(KEY_BUBBLE_DISMISSED, true).apply()
        Toast.makeText(this, "Floating controls hidden. Reopen them from the app.", Toast.LENGTH_SHORT).show()
        if (broadcastActive || broadcastStarting) {
            removeOverlayViews()
        } else {
            stopSelf()
        }
    }

    private fun removeOverlayViews() {
        chat?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        chat = null
        dismissTarget?.let { try { wm.removeView(it) } catch (_: Exception) { } }; dismissTarget = null
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }; panel = null
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) { } }; bubble = null
        stopButton = null; speakerButton = null; streamAudioButton = null; statusLabel = null
    }

    // Removing the app task must not stop an active broadcast. The RTMP
    // foreground service remains the owner until the user explicitly ends it.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun refreshBroadcastState() {
        val prefs = getSharedPreferences(RtmpBroadcastService.STATE_PREFS, MODE_PRIVATE)
        val savedActive = prefs.getBoolean(RtmpBroadcastService.KEY_ACTIVE, false)
        val heartbeat = prefs.getLong(RtmpBroadcastService.KEY_HEARTBEAT, 0L)
        val sessionFresh = heartbeat > 0L && System.currentTimeMillis() - heartbeat <= 8_000L
        broadcastActive = savedActive && sessionFresh
        broadcastMessage = if (savedActive && !sessionFresh) {
            "Previous broadcast session ended unexpectedly"
        } else {
            prefs.getString(RtmpBroadcastService.KEY_MESSAGE, "Broadcast setup ready") ?: "Broadcast setup ready"
        }
        if (savedActive && !sessionFresh) {
            prefs.edit().putBoolean(RtmpBroadcastService.KEY_ACTIVE, false).putLong(RtmpBroadcastService.KEY_BITRATE, 0L).apply()
        }
        if (!prefs.getBoolean(RtmpBroadcastService.KEY_ACTIVE, false) &&
            listOf("stopped", "disconnected").any { broadcastMessage.contains(it, ignoreCase = true) }) {
            broadcastMessage = "Broadcast setup ready"
        }
        broadcastStarting = !broadcastActive && isStartingMessage(broadcastMessage)
        renderBroadcastState()
    }

    private fun isStartingMessage(message: String): Boolean {
        val value = message.lowercase()
        return listOf("preparing", "encoder ready", "connecting", "waiting for", "stream key accepted")
            .any { value.contains(it) }
    }

    private fun renderBroadcastState() {
        val errorState = !broadcastActive && !broadcastStarting &&
            listOf("failed", "error", "invalid", "authentication").any { broadcastMessage.contains(it, ignoreCase = true) }
        statusLabel?.text = when {
            broadcastActive -> "● LIVE  •  $broadcastMessage"
            broadcastStarting -> "◌ CONNECTING  •  $broadcastMessage"
            errorState -> "⚠ ERROR  •  $broadcastMessage"
            else -> "○ SETUP  •  $broadcastMessage"
        }
        stopButton?.apply {
            isEnabled = true
            text = when {
                broadcastActive -> "■  STOP LIVE STREAM"
                broadcastStarting -> "×  CANCEL STARTUP"
                errorState -> "↻  RETRY / OPEN SETUP"
                else -> "↗  OPEN BROADCAST SETUP"
            }
            setBackgroundColor(if (broadcastActive || broadcastStarting || errorState) 0xFFB94D54.toInt() else 0xFFC4FF5C.toInt())
            setTextColor(if (broadcastActive || broadcastStarting || errorState) 0xFFFFFFFF.toInt() else 0xFF11170A.toInt())
        }
    }

    private fun renderSpeakerState() {
        speakerButton?.apply {
            text = if (speakerMuted) "◖))  SPEAKER MUTED  •  TAP TO UNMUTE" else "◖))  SPEAKER ON  •  TAP TO MUTE"
            setBackgroundColor(if (speakerMuted) 0xFFB94D54.toInt() else 0xFF263642.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun renderStreamAudioState() {
        streamAudioButton?.apply {
            text = if (streamAudioMuted) "STREAM AUDIO MUTED • TAP TO UNMUTE" else "STREAM AUDIO ON • TAP TO MUTE"
            setBackgroundColor(if (streamAudioMuted) 0xFFB94D54.toInt() else 0xFF176B63.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleSpeakerMute() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = getSharedPreferences(RtmpBroadcastService.STATE_PREFS, MODE_PRIVATE)
        if (!speakerMuted) {
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            prefs.edit().putInt("speaker_previous_music_volume", current.coerceAtLeast(1)).apply()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            speakerMuted = true
        } else {
            val previous = prefs.getInt("speaker_previous_music_volume", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, previous.coerceIn(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            speakerMuted = false
        }
        renderSpeakerState()
    }

    private fun togglePanel() {
        val expanded = panel?.visibility == View.VISIBLE
        panel?.visibility = if (expanded) View.GONE else View.VISIBLE
        bubble?.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) scheduleDim()
    }

    private fun brighten() {
        handler.removeCallbacks(dimRunnable)
        bubble?.alpha = 1f
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, 3500L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW = "com.jubersaleem.mobilegamingstreamer.action.SHOW_FLOATING_CONTROLS"
        const val KEY_BUBBLE_DISMISSED = "floating_bubble_dismissed"
    }

    override fun onDestroy() {
        if (statusReceiverRegistered) {
            try { unregisterReceiver(broadcastReceiver) } catch (_: IllegalArgumentException) { }
            statusReceiverRegistered = false
        }
        handler.removeCallbacksAndMessages(null)
        chat?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        chat = null
        dismissTarget?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        dismissTarget = null
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
        bubble = null
        stopButton = null
        speakerButton = null
        streamAudioButton = null
        statusLabel = null
        super.onDestroy()
    }
}
