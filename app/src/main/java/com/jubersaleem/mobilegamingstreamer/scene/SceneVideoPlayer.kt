package com.jubersaleem.mobilegamingstreamer.scene

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * J2 single-video decoder shell: one framework `MediaPlayer` rendering
 * into a caller-provided `Surface` (backed by a compositor-owned
 * SurfaceTexture). Device-gated (no JVM coverage): MediaPlayer cannot
 * execute on the JVM. Verified by Android compilation, lint, and CI
 * packaging.
 *
 * Threading: the player owns one `HandlerThread`; construction, every
 * op, and every MediaPlayer callback run on it (MediaPlayer delivers
 * callbacks on its creating thread's Looper). The GL thread never
 * touches the player synchronously except through [stopAndRelease]'s
 * bounded rendezvous — decode can never block a frame.
 *
 * Single-use per overlay slot: [prepare] once, then pause/resume,
 * then [stopAndRelease]. Every public method never throws; failures
 * report through [Listener.onError] so the slot fails loudly without
 * touching the stream.
 */
internal class SceneVideoPlayer(
    private val appContext: Context,
    tag: String
) {
    interface Listener {
        /** Decoder prepared and playback started. Player thread. */
        fun onPrepared(videoWidth: Int, videoHeight: Int)

        /** Coded size changed mid-stream (informational). Player thread. */
        fun onVideoSizeChanged(width: Int, height: Int)

        /** Terminal failure (prepare error, runtime error, op error). Player thread. */
        fun onError(what: Int, extra: Int)

        /** Playback reached the end (looping makes this a sanity log). Player thread. */
        fun onCompletion()
    }

    private val thread = HandlerThread("$TAG-$tag").also { it.start() }
    private val handler = Handler(thread.looper)
    private var player: MediaPlayer? = null
    private var prepared = false

    /**
     * Binds [uriString] to [surface] and prepares asynchronously; the
     * player auto-starts (muted, looping) when preparation completes.
     * Any thread; the work runs on the player thread. Ignored when
     * already prepared/preparing. Never throws.
     */
    fun prepare(uriString: String, surface: Surface, listener: Listener) {
        try {
            handler.post {
                if (player != null) return@post
                lastListener = listener
                val mp = MediaPlayer()
                player = mp
                try {
                    val uri = Uri.parse(uriString)
                    if (uri == null) {
                        reportError(listener, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                        return@post
                    }
                    mp.setDataSource(appContext, uri)
                    mp.setSurface(surface)
                    mp.setLooping(true)
                    mp.setVolume(0f, 0f)
                    mp.setOnPreparedListener { ready ->
                        try {
                            ready.start()
                            prepared = true
                            listener.onPrepared(ready.videoWidth, ready.videoHeight)
                        } catch (t: Throwable) {
                            reportError(listener, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                            Log.w(TAG, "video auto-start failed", t)
                        }
                    }
                    mp.setOnVideoSizeChangedListener { _, width, height ->
                        listener.onVideoSizeChanged(width, height)
                    }
                    mp.setOnCompletionListener {
                        listener.onCompletion()
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        // True: handled — the slot tears this player
                        // down and fails loudly; the stream continues.
                        reportError(listener, what, extra)
                        true
                    }
                    mp.prepareAsync()
                } catch (t: Throwable) {
                    reportError(listener, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                    Log.w(TAG, "video prepare failed", t)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Stalls the decoder, holding the last frame. Any thread. Never throws. */
    fun pause() {
        postOp { mp, listener ->
            try {
                if (mp.isPlaying) mp.pause()
            } catch (t: Throwable) {
                reportError(listener, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                Log.w(TAG, "video pause failed", t)
            }
        }
    }

    /** Continues a paused decoder. Any thread. Never throws. */
    fun resume() {
        postOp { mp, listener ->
            try {
                mp.start()
            } catch (t: Throwable) {
                reportError(listener, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
                Log.w(TAG, "video resume failed", t)
            }
        }
    }

    /**
     * Stops + releases the decoder and quits the player thread. Any
     * thread; blocks up to [timeoutMs] for the release op. Returns
     * whether the release rendezvous completed — false only on
     * timeout, and even then the thread self-cleans (the release op
     * is queued ahead of quit, so a wedged call still dies with its
     * thread). Never throws.
     */
    fun stopAndRelease(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val posted = try {
            handler.post {
                try {
                    val mp = player
                    player = null
                    prepared = false
                    try {
                        mp?.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        mp?.release()
                    } catch (_: Throwable) {
                    }
                } finally {
                    latch.countDown()
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
        try {
            thread.quitSafely()
        } catch (_: Throwable) {
        }
        if (!posted) return false
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Queues stop + release + thread quit without waiting (any
     * thread, never throws). For player-thread callers (error
     * callbacks) and GL failure paths where a rendezvous would block
     * a frame or deadlock. The thread always quits after draining.
     */
    fun releaseAsync() {
        try {
            handler.post {
                val mp = player
                player = null
                prepared = false
                try {
                    mp?.stop()
                } catch (_: Throwable) {
                }
                try {
                    mp?.release()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        try {
            thread.quitSafely()
        } catch (_: Throwable) {
        }
    }

    private var lastListener: Listener? = null

    private fun postOp(op: (MediaPlayer, Listener?) -> Unit) {
        try {
            handler.post {
                val mp = player ?: return@post
                try {
                    op(mp, lastListener)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun reportError(listener: Listener?, what: Int, extra: Int) {
        try {
            (listener ?: lastListener)?.onError(what, extra)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "MGS-Video"
    }
}
