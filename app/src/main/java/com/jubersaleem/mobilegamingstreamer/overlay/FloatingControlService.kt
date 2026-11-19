package com.jubersaleem.mobilegamingstreamer.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
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
    private var panelParams: WindowManager.LayoutParams? = null
    private val dimRunnable = Runnable { bubble?.alpha = 0.38f }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps first", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) createBubble()
        scheduleDim()
        return START_STICKY
    }

    private fun createBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val secureFlags = WindowManager.LayoutParams.FLAG_SECURE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            alpha = 1f
            contentDescription = "Open Mobile Gaming Streamer controls"
        }
        val lp = WindowManager.LayoutParams(dp(58), dp(58), type, secureFlags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(180)
        }
        bubbleParams = lp
        bubble = icon
        wm.addView(icon, lp)
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
                        brighten()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                        lp.x = (initialX - dx.toInt()).coerceAtLeast(0)
                        lp.y = (initialY + dy.toInt()).coerceAtLeast(0)
                        wm.updateViewLayout(icon, lp)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) togglePanel()
                        scheduleDim()
                        return true
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
        header.addView(minimize, LinearLayout.LayoutParams(dp(48), dp(42)))
        root.addView(header)

        val status = TextView(this).apply {
            text = "Live controls"
            setTextColor(0xFFE8EEF5.toInt())
            textSize = 11f
            setPadding(0, dp(3), 0, dp(8))
        }
        root.addView(status)
        val label = TextView(this).apply {
            text = "VIDEO BITRATE"
            setTextColor(0xFF63D8E8.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(label)
        val bitrateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1500, 2500, 4000, 6000, 8000).forEach { kbps ->
            val button = Button(this).apply {
                text = "${kbps / 1000}M"
                textSize = 10f
                setPadding(dp(1), 0, dp(1), 0)
                setOnClickListener {
                    startService(Intent(this@FloatingControlService, RtmpBroadcastService::class.java).apply {
                        action = RtmpBroadcastService.ACTION_SET_BITRATE
                        putExtra(RtmpBroadcastService.EXTRA_BITRATE, kbps * 1000)
                    })
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
        val stop = Button(this).apply {
            text = "■  STOP STREAM"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFB94D54.toInt())
            setOnClickListener {
                startService(Intent(this@FloatingControlService, RtmpBroadcastService::class.java).setAction(RtmpBroadcastService.ACTION_STOP))
                status.text = "Stop command sent"
            }
        }
        root.addView(stop)

        val lp = WindowManager.LayoutParams(dp(320), dp(420), type, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv(), PixelFormat.TRANSLUCENT).apply {
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        chat?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        chat = null
        panel?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        panel = null
        bubble = null
        super.onDestroy()
    }
}
