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
import android.os.SystemClock
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
import com.jubersaleem.mobilegamingstreamer.AudioRouteLabels
import com.jubersaleem.mobilegamingstreamer.MainActivity
import com.jubersaleem.mobilegamingstreamer.StreamUrlValidator
import com.pedro.common.ConnectChecker
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/** RTMP broadcast service with microphone audio and screen video. */
class RtmpBroadcastService : Service() {
    private var display: GenericStream? = null
    private var activeAudioSource: AudioSource? = null
    private var activeMicSource: Int = AudioRouteLabels.SOURCE_NONE
    private var activeHeadset: Boolean = false
    private var activeProjection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post { if (display != null) stopBroadcast("Screen capture permission ended") }
        }
    }
    private var streamingConfirmed = false
    private var lastBitrate = 0L
    private var lastPositiveBitrateAt = 0L
    private var lastPositiveFpsAt = 0L
    private var stopping = false
    private val healthMonitor = StreamHealthMonitor()
    private val handler = Handler(Looper.getMainLooper())
    private val startupTimeout = Runnable {
        if (display != null && recoveryDriver.isStartupBoundActive(streamingConfirmed)) {
            stopBroadcast("No outgoing video bitrate after 25 seconds. Encoder or RTMP startup failed.")
        }
    }
    // Socket-only recovery: re-establishes the RTMP connection through
    // RootEncoder's retry API. Capture, encoders, mixer and the
    // MediaProjection session are never touched on this path.
    private val reconnectRunnable: Runnable = Runnable {
        when (val effect = recoveryDriver.onRetryTimerFired(stopping, display != null, SystemClock.elapsedRealtime())) {
            is RecoveryEffect.Stop -> stopBroadcast(effect.message)
            else -> { }
        }
    }
    // Explicit types break the declaration-inference cycle between the
    // runnable, the timer seam and the driver (they reference each other).
    private val recoveryTimer: RecoveryTimer = object : RecoveryTimer {
        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(reconnectRunnable)
            handler.postDelayed(reconnectRunnable, delayMs)
        }
        override fun cancel() {
            handler.removeCallbacks(reconnectRunnable)
        }
    }
    private val socketRetrier: SocketRetrier = SocketRetrier { reason ->
        display?.getStreamClient()?.reTry(0L, reason) == true
    }
    private val recoveryDriver: StreamRecoveryDriver = StreamRecoveryDriver(recoveryTimer, socketRetrier)
    private val sessionHeartbeat = object : Runnable {
        override fun run() {
            if (display != null) {
                getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
                handler.postDelayed(this, 2_000L)
            }
        }
    }
    // A live stream can silently stall after the startup watchdog has been cancelled.
    // Stop a stalled session with an actionable message; a new attempt needs fresh Android consent.
    // While a reconnect episode is open, bitrate silence is expected: only the
    // overall episode bound can stop the session, never the stall rule.
    private val streamHealthWatchdog = object : Runnable {
        override fun run() {
            if (display == null || stopping) return
            // Sample-before-decide on the existing tick (no new loop): the
            // observational monitor shares the 20-second stall window, so it
            // must see the final sample before the authoritative decision
            // below can stop on the critical tick. The decision itself is
            // unchanged and remains the sole termination authority.
            when (runWatchdogCycle(::sampleStreamHealth) {
                recoveryDriver.onWatchdogTick(
                    streamingConfirmed, lastPositiveBitrateAt, SystemClock.elapsedRealtime()
                )
            }) {
                WatchdogAction.STOP_STALLED -> {
                    stopBroadcast("Stream output stalled for 20 seconds. Check your network, then tap GO LIVE and approve a fresh screen-capture session.")
                    return
                }
                WatchdogAction.STOP_RECONNECT_TIMEOUT -> {
                    stopBroadcast(StreamConnectionPolicy.exhaustedMessage())
                    return
                }
                WatchdogAction.NONE -> { }
            }
            handler.postDelayed(this, STREAM_HEALTH_CHECK_INTERVAL_MS)
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

    @Suppress("UnspecifiedRegisterReceiverFlag")
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
        handler.removeCallbacks(streamHealthWatchdog)
        handler.removeCallbacks(sessionHeartbeat)
        recoveryDriver.startSession()
        healthMonitor.startSession(SystemClock.elapsedRealtime())
        // Suppress callbacks from a previous stream while its resources are released.
        stopping = true
        streamingConfirmed = false
        lastBitrate = 0L
        lastPositiveBitrateAt = 0L
        lastPositiveFpsAt = 0L
        display?.let { previous ->
            try { previous.release() } catch (_: Exception) { }
            display = null
        }
        releaseProjection()
        stopping = false
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = parcelableIntent(intent, EXTRA_RESULT_DATA)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        if (resultData == null || !StreamUrlValidator.isValidRtmpServerUrl(streamUrl)) {
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
                override fun onConnectionStarted(url: String) {
                    if (stopping) return
                    update("Connecting to streaming server…")
                }
                override fun onConnectionSuccess() {
                    if (stopping) return
                    when (recoveryDriver.onConnectionSuccess(SystemClock.elapsedRealtime())) {
                        SuccessOutcome.RECOVERED -> {
                            // Restore the library retry budget consumed by the episode
                            // just ended, so a later independent failure gets full
                            // attempts again. RootEncoder only resets its counter on
                            // explicit disconnect, never on success.
                            try {
                                display?.getStreamClient()?.setReTries(StreamConnectionPolicy.MAX_ATTEMPTS)
                            } catch (_: Exception) { }
                            if (!streamingConfirmed) {
                                // A reconnected socket still has to produce frames;
                                // re-arm the startup bound that scheduling the retry
                                // had removed. Untouched on a plain first connect.
                                handler.removeCallbacks(startupTimeout)
                                handler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS)
                            }
                            update("Reconnected • Waiting for video frames")
                        }
                        SuccessOutcome.CONNECTED -> update("RTMP connected • Waiting for video frames")
                        // Strict deadline: a late success revives nothing. The
                        // stop path below tears down even the late-connected
                        // socket; no LIVE is published and no budget is reset.
                        SuccessOutcome.EXPIRED -> stopBroadcast(StreamConnectionPolicy.exhaustedMessage())
                        SuccessOutcome.IGNORED -> { }
                    }
                }
                override fun onConnectionFailed(reason: String) {
                    Log.e(TAG, "RTMP connection failed: $reason")
                    when (val effect = recoveryDriver.onConnectionFailed(
                        reason, stopping, display != null, activeProjection != null,
                        SystemClock.elapsedRealtime()
                    )) {
                        is RecoveryEffect.Publish -> {
                            lastBitrate = 0L
                            lastPositiveBitrateAt = 0L
                            handler.removeCallbacks(startupTimeout)
                            handler.removeCallbacks(streamHealthWatchdog)
                            handler.postDelayed(streamHealthWatchdog, STREAM_HEALTH_CHECK_INTERVAL_MS)
                            update(effect.message, 0L)
                        }
                        is RecoveryEffect.Stop -> stopBroadcast(effect.message)
                        RecoveryEffect.None -> { }
                    }
                }
                override fun onDisconnect() {
                    if (stopping) return
                    stopping = true
                    handler.removeCallbacks(startupTimeout)
                    handler.removeCallbacks(streamHealthWatchdog)
                    handler.removeCallbacks(sessionHeartbeat)
                    recoveryDriver.onStop()
                    streamingConfirmed = false
                    lastBitrate = 0L
                    val disconnected = display
                    display = null
                    activeAudioSource = null
                    activeMicSource = AudioRouteLabels.SOURCE_NONE
                    activeHeadset = false
                    try { disconnected?.release() } catch (error: Exception) {
                        Log.w(TAG, "Error releasing disconnected stream", error)
                    }
                    releaseProjection()
                    publishState(false, "Broadcast disconnected. Tap GO LIVE to start a fresh capture session.", 0L)
                    // Halt only after the final status event so it preserves
                    // the terminal health verdict instead of UNKNOWN.
                    healthMonitor.onStopped()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                override fun onAuthError() {
                    stopBroadcast("Stream authentication failed. Check the stream key.")
                }
                override fun onAuthSuccess() {
                    if (stopping) return
                    update("Stream key accepted")
                }
                override fun onNewBitrate(bitrate: Long) {
                    if (stopping) return
                    // Bitrate ticks during a reconnect episode carry no signal:
                    // the sender is stopped while the socket is down, and any
                    // stray tick must neither flap the status message nor fake
                    // a LIVE line while the episode is open. Only a connection
                    // success may end the episode.
                    if (recoveryDriver.state.phase == StreamPhase.RECONNECTING) return
                    if (bitrate > 0L) {
                        recoveryDriver.onPositiveBitrate()
                        val firstConfirmation = !streamingConfirmed
                        streamingConfirmed = true
                        lastBitrate = bitrate
                        lastPositiveBitrateAt = SystemClock.elapsedRealtime()
                        handler.removeCallbacks(startupTimeout)
                        if (firstConfirmation) {
                            handler.removeCallbacks(streamHealthWatchdog)
                            handler.postDelayed(streamHealthWatchdog, STREAM_HEALTH_CHECK_INTERVAL_MS)
                        }
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
            val profileCandidates = VideoProfileSelector.build(quality, fps, portrait)

            // RtmpDisplay.prepareAudio() captures ONLY the microphone. It never configures
            // AudioPlaybackCapture, which is why BGMI gunshots/game effects were absent.
            // Independent microphone and playback recorders are mixed after mic-only DSP.
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Android did not provide a MediaProjection session")
            activeProjection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, handler)

            val preferredInput = choosePreferredMicrophoneDevice()
            val accessibilityMicConnected = MgsMicAccessibilityService.instance != null &&
                MgsMicAccessibilityService.isEnabled(this)
            // Glip's observed source-selection pattern: DEFAULT with accessibility enabled
            // and the phone mic; VOICE_RECOGNITION for an external headset; MIC as fallback.
            val micSource = MicrophoneAudioSourcePolicy.select(
                accessibilityConnected = accessibilityMicConnected,
                externalInputConnected = preferredInput != null
            )
            Log.i(TAG, "Microphone route: accessibility=" + accessibilityMicConnected +
                " externalInput=" + (preferredInput != null) + " audioSource=" + micSource)
            // The audio route is fixed when the session starts; a headset
            // connected or removed mid-session takes effect on the next GO LIVE.
            activeMicSource = if (audioMode == AUDIO_MODE_GAME) AudioRouteLabels.SOURCE_NONE else micSource
            activeHeadset = preferredInput != null
            val audioSource: AudioSource = when (audioMode) {
                AUDIO_MODE_MIC -> ProcessedMixAudioSource(mediaProjection, false, micSource).apply { setPreferredDevice(preferredInput); setMicrophoneGain(1.0f) }
                AUDIO_MODE_GAME -> InternalAudioSource(mediaProjection)
                AUDIO_MODE_NORMAL, AUDIO_MODE_BOTH -> ProcessedMixAudioSource(mediaProjection, true, micSource).apply { setPreferredDevice(preferredInput); setMicrophoneGain(1.0f); setPlaybackGain(1.0f) }
                else -> ProcessedMixAudioSource(mediaProjection, true, micSource).apply { setPreferredDevice(preferredInput); setMicrophoneGain(1.6f); setPlaybackGain(1.0f) }
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
            Log.i(TAG, "Audio effects requested: AEC=$echoCancellation, NS=$noiseSuppression; microphone source=$micSource; accessibility=$accessibilityMicConnected")
            val audioPrepared = stream.prepareAudio(48_000, true, 128_000, echoCancellation, noiseSuppression)
            if (!audioPrepared) {
                throw IllegalStateException("Audio source preparation failed for mode: $audioMode")
            }
            // Apply the requested output orientation to the encoder renderer.
            // This is a single-source scene for now; custom top/bottom scene layers
            // require the separate scene-compositor milestone.
            stream.getGlInterface().setStreamIsPortrait(portrait)
            // Keep static-screen fallback frames on the same cadence as the selected stream profile.
            stream.getGlInterface().setForceRender(true, activeProfile.fps)
            display = stream
            stream.setFpsListener { outputFps ->
                handler.post {
                    // Encoder-output cadence for health sampling, tracked in
                    // every phase (including reconnecting) for the stalled
                    // encoder-versus-transmission distinction.
                    if (display === stream && outputFps > 0) {
                        lastPositiveFpsAt = SystemClock.elapsedRealtime()
                    }
                    if (display === stream && streamingConfirmed && recoveryDriver.state.phase != StreamPhase.RECONNECTING) {
                        update("LIVE • $activeProfileLabel • ${outputFps} FPS • ${lastBitrate / 1000} kbps", lastBitrate)
                    }
                }
            }
            handler.removeCallbacks(sessionHeartbeat)
            handler.post(sessionHeartbeat)
            publishState(false, "Encoder ready • $activeProfileLabel • Connecting…", 0L)
            update("Encoder ready • $activeProfileLabel • Connecting…")
            // Library-level backstop for the policy above: RootEncoder refuses
            // further reTry calls once this counter is consumed, so the two
            // bounds always agree even if a callback arrives unexpectedly.
            stream.getStreamClient().setReTries(StreamConnectionPolicy.MAX_ATTEMPTS)
            stream.startStream(streamUrl)
            handler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MS)
            Log.i(TAG, "RTMP broadcast requested: $activeProfileLabel, orientation=${if (portrait) "PORTRAIT" else "LANDSCAPE"}, audio=$audioMode, dpi=$dpi")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start RTMP broadcast", error)
            stopBroadcast("Unable to start: ${error.message ?: "encoder error"}")
        }
    }

    /**
     * Feed one observational health sample from the live pipeline. Runs only
     * on the existing watchdog tick, before the authoritative driver
     * decision; a sampling failure skips the tick and never affects the
     * session. Health transitions are logged for device debugging and ride
     * along the next status broadcast as an extra.
     */
    private fun sampleStreamHealth() {
        val stream = display ?: return
        val previous = healthMonitor.state
        val sample = try {
            val client = stream.getStreamClient()
            StreamHealthSample(
                nowMs = SystemClock.elapsedRealtime(),
                bitrateBps = lastBitrate,
                bytesSendTotal = client.getBytesSend(),
                sentVideoTotal = client.getSentVideoFrames(),
                sentAudioTotal = client.getSentAudioFrames(),
                droppedVideoTotal = client.getDroppedVideoFrames(),
                droppedAudioTotal = client.getDroppedAudioFrames(),
                queueItems = client.getItemsInCache(),
                queueBytesOut = client.getQueueBytesOut(),
                congested = client.hasCongestion(),
                lastPositiveBitrateMs = lastPositiveBitrateAt,
                lastEncoderFpsMs = lastPositiveFpsAt,
                recovering = recoveryDriver.state.phase == StreamPhase.RECONNECTING
            )
        } catch (_: Exception) {
            return
        }
        val next = healthMonitor.onSample(sample)
        if (next != previous) Log.i(TAG, "Stream health: $previous -> $next")
    }

    private fun stopBroadcast(message: String = "Broadcast stopped") {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(startupTimeout)
        handler.removeCallbacks(streamHealthWatchdog)
        handler.removeCallbacks(sessionHeartbeat)
        recoveryDriver.onStop()
        streamingConfirmed = false
        lastBitrate = 0L
        val active = display
        display = null
        activeAudioSource = null
        activeMicSource = AudioRouteLabels.SOURCE_NONE
        activeHeadset = false
        try { active?.release() } catch (error: Exception) {
            Log.w(TAG, "Error while releasing RTMP stream", error)
        }
        releaseProjection()
        publishState(false, message, 0L)
        // Halt only after the final status event so it preserves the
        // terminal health verdict (e.g. STALLED) instead of UNKNOWN.
        healthMonitor.onStopped()
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
            is ProcessedMixAudioSource -> if (muted) source.mute() else source.unMute()
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
        // A session whose socket dropped is never reported live, even though
        // the confirmation flag from before the outage is retained.
        val live = streamingConfirmed && lastBitrate > 0L && recoveryDriver.state.phase != StreamPhase.RECONNECTING
        publishState(live, message, if (bitrate > 0L) bitrate else lastBitrate)
        notifications.notify(NOTIFICATION_ID, notification(message))
    }

    private fun publishState(active: Boolean, message: String, bitrate: Long) {
        val voice = activeAudioSource as? ProcessedMixAudioSource
        val aec = voice?.aecAttached == true
        val ns = voice?.nsAttached == true
        val voiceOptimized = voice?.voiceOptimizerActive == true
        val captureRunning = activeAudioSource?.isRunning() == true
        val streamMuted = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STREAM_AUDIO_MUTED, false)
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, active)
            .putString(KEY_MESSAGE, message)
            .putLong(KEY_BITRATE, bitrate)
            .putString(KEY_HEALTH, healthMonitor.state.name)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATUS_ACTIVE, active)
            putExtra(EXTRA_STATUS_MESSAGE, message)
            putExtra(EXTRA_STATUS_BITRATE, bitrate)
            putExtra(EXTRA_STATUS_HEALTH, healthMonitor.state.name)
            putExtra(EXTRA_STATUS_AEC, aec)
            putExtra(EXTRA_STATUS_NS, ns)
            putExtra(EXTRA_STATUS_VOICE, voiceOptimized)
            putExtra(EXTRA_STATUS_MIC_SOURCE, activeMicSource)
            putExtra(EXTRA_STATUS_HEADSET, activeHeadset)
            putExtra(EXTRA_STATUS_STREAM_MUTED, streamMuted)
            putExtra(EXTRA_STATUS_CAPTURE, captureRunning)
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
        recoveryDriver.onDestroyed()
        healthMonitor.onStopped()
        if (commandReceiverRegistered) {
            try { unregisterReceiver(commandReceiver) } catch (_: IllegalArgumentException) { }
            commandReceiverRegistered = false
        }
        streamingConfirmed = false
        lastBitrate = 0L
        try { display?.release() } catch (_: Exception) { }
        display = null
        activeAudioSource = null
        activeMicSource = AudioRouteLabels.SOURCE_NONE
        activeHeadset = false
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
        const val EXTRA_STATUS_AEC = "status_aec"
        const val EXTRA_STATUS_NS = "status_ns"
        const val EXTRA_STATUS_VOICE = "status_voice_optimized"
        const val EXTRA_STATUS_MIC_SOURCE = "status_mic_source"
        const val EXTRA_STATUS_HEADSET = "status_headset"
        const val EXTRA_STATUS_STREAM_MUTED = "status_stream_muted"
        const val EXTRA_STATUS_CAPTURE = "status_capture_running"
        const val EXTRA_STATUS_HEALTH = "status_health"
        const val STATE_PREFS = "broadcast_state"
        const val KEY_ACTIVE = "active"
        const val KEY_MESSAGE = "message"
        const val KEY_BITRATE = "bitrate"
        const val KEY_HEALTH = "health"
        const val KEY_HEARTBEAT = "service_heartbeat"
        private const val STREAM_HEALTH_CHECK_INTERVAL_MS = 5_000L
        private const val STARTUP_TIMEOUT_MS = 25_000L
        private const val TAG = "MGS-RTMP"
        private const val CHANNEL_ID = "live_broadcast"
        private const val NOTIFICATION_ID = 1002
    }
}
