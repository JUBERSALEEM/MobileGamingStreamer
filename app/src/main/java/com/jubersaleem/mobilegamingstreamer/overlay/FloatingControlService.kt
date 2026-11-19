package com.jubersaleem.mobilegamingstreamer.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.IBinder
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jubersaleem.mobilegamingstreamer.capture.RtmpBroadcastService
import kotlin.math.abs

/** User-invoked, movable controls shown above other apps while gaming. */
class FloatingControlService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private var chatView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps first", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (panel == null) showPanel()
        return START_STICKY
    }

    private fun showPanel() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(0xF018222D.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "●  MGS  LIVE CONTROLS"
            setTextColor(0xFFC4FF5C.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(12), 0)
        }
        val close = Button(this).apply {
            text = "×"
            setOnClickListener { stopSelf() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(42)))
        root.addView(header)

        val status = TextView(this).apply {
            text = "Streaming controls • move this panel anywhere"
            setTextColor(0xFFE8EEF5.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(status)

        val bitrateLabel = TextView(this).apply {
            text = "LIVE VIDEO BITRATE  (changes immediately)"
            setTextColor(0xFF63D8E8.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(bitrateLabel)
        val bitrateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1500, 2500, 4000, 6000, 8000).forEach { kbps ->
            val b = Button(this).apply {
                text = if (kbps >= 1000) "${kbps / 1000}M" else "${kbps}K"
                textSize = 10f
                setPadding(dp(2), 0, dp(2), 0)
                setOnClickListener {
                    startService(Intent(this@FloatingControlService, RtmpBroadcastService::class.java).apply {
                        action = RtmpBroadcastService.ACTION_SET_BITRATE
                        putExtra(RtmpBroadcastService.EXTRA_BITRATE, kbps * 1000)
                    })
                    Toast.makeText(this@FloatingControlService, "Bitrate set to ${kbps} kbps", Toast.LENGTH_SHORT).show()
                }
            }
            bitrateRow.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(bitrateRow)

        val chatLabel = TextView(this).apply {
            text = "YOUTUBE LIVE CHAT"
            setTextColor(0xFF63D8E8.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(chatLabel)
        val videoId = EditText(this).apply {
            hint = "YouTube live video ID"
            setSingleLine(true)
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF91A0AE.toInt())
        }
        root.addView(videoId, LinearLayout.LayoutParams(-1, dp(46)))
        val chatButton = Button(this).apply {
            text = "SHOW LIVE CHAT"
            setOnClickListener {
                val id = videoId.text.toString().trim()
                if (id.isBlank() || !id.matches(Regex("[A-Za-z0-9_-]{6,}"))) {
                    Toast.makeText(this@FloatingControlService, "Enter the YouTube live video ID", Toast.LENGTH_SHORT).show()
                } else {
                    val web = chatView ?: WebView(this@FloatingControlService).also { created ->
                        created.settings.javaScriptEnabled = true
                        created.settings.domStorageEnabled = true
                        created.settings.loadsImagesAutomatically = true
                        created.webChromeClient = WebChromeClient()
                        created.webViewClient = WebViewClient()
                        chatView = created
                        root.addView(created, LinearLayout.LayoutParams(-1, dp(250)))
                    }
                    web.visibility = View.VISIBLE
                    web.loadUrl("https://www.youtube.com/live_chat?v=$id&is_popout=1")
                    text = "REFRESH LIVE CHAT"
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

        val scroll = ScrollView(this).apply { addView(root) }
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            dp(320), dp(460), windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(110)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        params = lp
        panel = scroll
        windowManager.addView(scroll, lp)

        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y
                        touchX = event.rawX; touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (startX - (event.rawX - touchX).toInt()).coerceAtLeast(0)
                        lp.y = (startY + (event.rawY - touchY).toInt()).coerceAtLeast(0)
                        windowManager.updateViewLayout(scroll, lp)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        chatView?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        chatView = null
        panel?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        panel = null
        super.onDestroy()
    }
}
