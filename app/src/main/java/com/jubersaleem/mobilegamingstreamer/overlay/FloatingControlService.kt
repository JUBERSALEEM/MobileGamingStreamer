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
import android.util.Log
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
import com.jubersaleem.mobilegamingstreamer.AudioRouteLabels
import com.jubersaleem.mobilegamingstreamer.BroadcastStatusKind
import com.jubersaleem.mobilegamingstreamer.capture.RtmpBroadcastService
import com.jubersaleem.mobilegamingstreamer.classifyBroadcastStatus
import com.jubersaleem.mobilegamingstreamer.isStartingBroadcastMessage
import com.jubersaleem.mobilegamingstreamer.capture.SpeakerMutePolicy
import com.jubersaleem.mobilegamingstreamer.capture.SpeakerMuteState
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
    private var footerLabel: TextView? = null
    private var broadcastActive = false
    private var broadcastStarting = false
    private var broadcastMessage = "Broadcast setup ready"
    private var micSource = AudioRouteLabels.SOURCE_NONE
    private var speakerMuteState = SpeakerMuteState()
    private var streamAudioMuted = false
    private var streamAudioButton: Button? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RtmpBroadcastService.ACTION_STATUS) return
            broadcastActive = intent.getBooleanExtra(RtmpBroadcastService.EXTRA_STATUS_ACTIVE, false)
            broadcastMessage = intent.getStringExtra(RtmpBroadcastService.EXTRA_STATUS_MESSAGE) ?: "Broadcast status updated"
            micSource = intent.getIntExtra(RtmpBroadcastService.EXTRA_STATUS_MIC_SOURCE, AudioRouteLabels.SOURCE_NONE)
            broadcastStarting = !broadcastActive && isStartingBroadcastMessage(broadcastMessage)
            renderBroadcastState()
            renderFooter()
            if (!broadcastActive && !broadcastStarting && bubble == null) stopSelf()
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
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
        adoptSpeakerMuteOwnership()
        streamAudioMuted = getSharedPreferences(RtmpBroadcastService.STATE_PREFS, MODE_PRIVATE).getBoolean(RtmpBroadcastService.KEY_STREAM_AUDIO_MUTED, false)
        renderBroadcastState()
        renderSpeakerState()
        renderStreamAudioState()
        renderFooter()
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
            setImageResource(R.drawable.ic_mgs_clay_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColors(intArrayOf(0xFFFFFCFF.toInt(), 0xFFEDE8FA.toInt()))
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            alpha = 1f
            contentDescription = "Open Mobile Gaming Streamer controls"
        }
        val lp = WindowManager.LayoutParams(dp(48), dp(48), type, secureFlags, PixelFormat.TRANSLUCENT).apply {
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
                        lp.x = (initialX - dx.toInt()).coerceIn(0, (bounds.width() - dp(48)).coerceAtLeast(0))
                        lp.y = (initialY + dy.toInt()).coerceIn(0, (bounds.height() - dp(48)).coerceAtLeast(0))
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
            background = panelSurfaceBackground()
            elevation = dp(18).toFloat()
            clipToOutline = true
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "MGS  STREAM CONTROLS"
            setTextColor(0xFFE9E7F5.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        val minimize = clayOverlayButton("—", 14f).apply {
            contentDescription = "Minimize floating controls"
            setOnClickListener { panel?.visibility = View.GONE; bubble?.visibility = View.VISIBLE; scheduleDim() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(minimize, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(clayOverlayButton("×", 14f).apply { contentDescription = "Hide floating controls"; setOnClickListener { dismissBubbleByDrag() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val status = TextView(this).apply {
            text = "Broadcast setup ready"
            setTextColor(0xFFB9B4CC.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(3), 0, dp(8))
        }
        statusLabel = status
        root.addView(status)
        val label = TextView(this).apply {
            text = "VIDEO BITRATE"
            setTextColor(0xFF9C97B3.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(label)
        val bitrateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(4000, 6000, 8000, 12000, 16000, 18000).forEach { kbps ->
            val button = clayOverlayButton("${kbps / 1000}M", 10f).apply {
                contentDescription = "Request ${kbps} kilobits per second video"
                setPadding(dp(1), 0, dp(1), 0)
                setOnClickListener {
                    RtmpBroadcastService.requestBitrate(this@FloatingControlService, kbps * 1000)
                    status.text = "Requested ${kbps} kbps video"
                }
            }
            bitrateRow.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(bitrateRow)

        val chatLabel = TextView(this).apply {
            text = "YOUTUBE LIVE CHAT"
            setTextColor(0xFF9C97B3.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(9), 0, dp(3))
        }
        root.addView(chatLabel)
        val videoId = EditText(this).apply {
            hint = "Live video ID (until YouTube account linking)"
            setSingleLine(true)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE9E7F5.toInt())
            setHintTextColor(0xFF8E89A3.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(0xFF1B1B25.toInt())
                setStroke(dp(1), 0xFF4A4A5E.toInt())
            }
            setPadding(dp(12), 0, dp(12), 0)
        }
        root.addView(videoId, LinearLayout.LayoutParams(-1, dp(48)))
        val chatButton = clayOverlayButton("OPEN LIVE CHAT", 11f).apply {
            setTextColor(0xFFFFFFFF.toInt())
            background = overlayButtonBackground(0xFFD95F45.toInt())
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
        val speaker = clayOverlayButton("", 11f).apply {
            setOnClickListener { toggleSpeakerMute() }
        }
        speakerButton = speaker
        root.addView(speaker)
        val streamAudio = clayOverlayButton("", 11f).apply {
            setOnClickListener {
                streamAudioMuted = !streamAudioMuted
                RtmpBroadcastService.requestStreamAudioMuted(this@FloatingControlService, streamAudioMuted)
                renderStreamAudioState()
            }
        }
        streamAudioButton = streamAudio
        root.addView(streamAudio)

        val stop = clayOverlayButton("", 12f).apply {
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
        val footer = TextView(this).apply {
            setTextColor(0xFF8E89A3.toInt())
            textSize = 9f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        footerLabel = footer
        root.addView(footer)

        val lp = WindowManager.LayoutParams(dp(320), dp(520), type, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv(), PixelFormat.TRANSLUCENT).apply {
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
                setColor(0xFFD1344C.toInt())
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
        stopButton = null; speakerButton = null; streamAudioButton = null; statusLabel = null; footerLabel = null
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
        broadcastStarting = !broadcastActive && isStartingBroadcastMessage(broadcastMessage)
        renderBroadcastState()
    }

    private fun renderBroadcastState() {
        val errorState = classifyBroadcastStatus(broadcastMessage, broadcastActive, broadcastStarting) == BroadcastStatusKind.ERROR
        statusLabel?.text = when {
            broadcastActive -> "● LIVE  •  $broadcastMessage"
            broadcastStarting -> "◌ CONNECTING  •  $broadcastMessage"
            errorState -> "⚠ ERROR  •  $broadcastMessage"
            else -> "○ SETUP  •  $broadcastMessage"
        }
        statusLabel?.setTextColor(
            when {
                broadcastActive -> 0xFF6FD8E3.toInt()
                errorState -> 0xFFFF8A7A.toInt()
                broadcastStarting -> 0xFFF2C879.toInt()
                else -> 0xFFB9B4CC.toInt()
            }
        )
        stopButton?.apply {
            isEnabled = true
            text = when {
                broadcastActive -> "■  STOP LIVE STREAM"
                broadcastStarting -> "×  CANCEL STARTUP"
                errorState -> "↻  RETRY / OPEN SETUP"
                else -> "↗  OPEN BROADCAST SETUP"
            }
            background = overlayButtonBackground(
                if (broadcastActive || broadcastStarting || errorState) 0xFFD1344C.toInt() else 0xFF5B3DF5.toInt()
            )
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun renderSpeakerState() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val muted = SpeakerMutePolicy.displayMuted(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        speakerButton?.apply {
            text = if (muted) "◖))  LOCAL SPEAKER MUTED  •  TAP TO UNMUTE" else "◖))  LOCAL SPEAKER ON  •  TAP TO MUTE"
            contentDescription = if (muted) {
                "Local speaker is muted. Tap to restore the previous volume. Stream audio is unaffected."
            } else {
                "Local speaker is on. Tap to mute this device only. Stream audio is unaffected."
            }
            background = overlayButtonBackground(if (muted) 0xFFD1344C.toInt() else 0xFF5B3DF5.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
    }

    /**
     * Local speaker mute only moves the device music stream; the outgoing
     * stream mix is controlled separately by the stream-audio button.
     */
    private fun musicVolumes(): Triple<AudioManager, Int, Int> {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return Triple(audio, audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    private fun adoptSpeakerMuteOwnership() {
        val prefs = getSharedPreferences(RtmpBroadcastService.STATE_PREFS, MODE_PRIVATE)
        val (_, current, max) = musicVolumes()
        speakerMuteState = SpeakerMutePolicy.onStart(
            prefs.getBoolean(KEY_SPEAKER_MUTE_OWNED, false),
            prefs.getInt(KEY_SPEAKER_PREVIOUS_VOLUME, 0),
            current,
            max
        )
        persistSpeakerMuteState()
    }

    private fun persistSpeakerMuteState() {
        getSharedPreferences(RtmpBroadcastService.STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_SPEAKER_MUTE_OWNED, speakerMuteState.owned)
            .putInt(KEY_SPEAKER_PREVIOUS_VOLUME, speakerMuteState.savedVolume)
            .apply()
    }

    private fun renderStreamAudioState() {
        streamAudioButton?.apply {
            text = if (streamAudioMuted) "STREAM AUDIO MUTED • TAP TO UNMUTE" else "STREAM AUDIO ON • TAP TO MUTE"
            background = overlayButtonBackground(if (streamAudioMuted) 0xFFD1344C.toInt() else 0xFF0097A7.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        renderFooter()
    }

    /**
     * One-line session footer. It only repeats facts this service actually
     * knows: the local stream-audio toggle and the microphone route the
     * broadcast service published for the running session.
     */
    private fun renderFooter() {
        val audio = if (streamAudioMuted) "STREAM AUDIO OFF" else "STREAM AUDIO ON"
        footerLabel?.text = "$audio • MIC ${AudioRouteLabels.micSourceLabel(micSource)}"
    }

    private fun toggleSpeakerMute() {
        val (audio, current, max) = musicVolumes()
        val result = SpeakerMutePolicy.onToggle(speakerMuteState, current, max)
        result.applyVolume?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        speakerMuteState = result.state
        persistSpeakerMuteState()
        renderSpeakerState()
    }

    private fun togglePanel() {
        val expanded = panel?.visibility == View.VISIBLE
        panel?.visibility = if (expanded) View.GONE else View.VISIBLE
        bubble?.visibility = if (expanded) View.VISIBLE else View.GONE
        // The volume may have moved via hardware buttons while the panel was
        // closed; re-read it so the speaker button never shows a stale state.
        if (!expanded) renderSpeakerState()
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

    /**
     * Dark graphite panel that stays readable over gameplay. The fills mirror
     * the clay tokens from the design-tokens doc; text tints are the
     * lightened variants that hold contrast on the dark surface.
     */
    private fun panelSurfaceBackground(): GradientDrawable {
        val colors = intArrayOf(0xFF262633.toInt(), 0xFF1B1B25.toInt())
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), 0xFF4A4A5E.toInt())
        }
    }

    private fun overlayButtonBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
            setStroke(dp(1), 0xFF4A4A5E.toInt())
        }

    /** Dark clay platform button used by the overlay controls. */
    private fun clayOverlayButton(label: String, textSize: Float = 11f): Button =
        Button(this).apply {
            text = label
            this.textSize = textSize
            isAllCaps = false
            setTextColor(0xFFE9E7F5.toInt())
            backgroundTintList = null
            background = overlayButtonBackground(0xFF353542.toInt())
            minimumHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
        }

    /**
     * Never leave the device silent because the overlay went away: if this
     * service muted the music stream and still owns that mute, restore the
     * recorded volume exactly once. Hardware-button changes win over the
     * recorded value, and failures are logged, never swallowed.
     */
    private fun restoreSpeakerVolumeIfOwned() {
        try {
            val (audio, current, max) = musicVolumes()
            val result = SpeakerMutePolicy.onDestroy(speakerMuteState, current, max)
            result.applyVolume?.let {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
                Log.i(TAG, "Restored local music volume to $it on overlay destroy")
            }
            speakerMuteState = result.state
            persistSpeakerMuteState()
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore local music volume on overlay destroy", error)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW = "com.jubersaleem.mobilegamingstreamer.action.SHOW_FLOATING_CONTROLS"
        const val KEY_BUBBLE_DISMISSED = "floating_bubble_dismissed"
        const val KEY_SPEAKER_MUTE_OWNED = "speaker_mute_owned"
        const val KEY_SPEAKER_PREVIOUS_VOLUME = "speaker_previous_music_volume"
        private const val TAG = "MGS-FloatingControls"
    }

    override fun onDestroy() {
        restoreSpeakerVolumeIfOwned()
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
        footerLabel = null
        super.onDestroy()
    }
}
