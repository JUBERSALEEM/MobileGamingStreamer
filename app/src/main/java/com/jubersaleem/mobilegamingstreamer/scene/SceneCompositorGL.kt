package com.jubersaleem.mobilegamingstreamer.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * H2 Android compositor: separate EGL context that draws screen frames into
 * the RootEncoder pipeline. Device-gated (no JVM coverage): EGL, GL, and
 * SurfaceTexture cannot execute on the JVM. Verified by Android compilation,
 * lint, CI packaging, and the S25 passthrough gate in
 * docs/H2_SCENE_ARCHITECTURE.md.
 *
 * Frame path (all on [workerThread]): input SurfaceTexture arrival →
 * `updateTexImage` → capture timestamp → controller offer → fullscreen OES
 * passthrough draw (+ optional H4 overlay quads) → presentation timestamp
 * → swap into the library ST.
 *
 * Ownership (see the architecture doc for the full map):
 * - Owns: GL thread + handler, EGL display/context/surface/program, OES
 *   texture, input SurfaceTexture + input Surface.
 * - Borrows: [outputSurface] (wraps the library ST; created and released by
 *   `SceneSource`), [controller] (decisions only).
 * - No EGL context sharing: the library exposes no context to share with,
 *   so producer/consumer BufferQueues link the contexts (standard pattern).
 *
 * Every public method documents its thread. GL/EGL calls happen on the GL
 * thread only; cross-thread setup/teardown rendezvous use bounded latches
 * ([GL_INIT_TIMEOUT_MS]/[GL_TEARDOWN_TIMEOUT_MS]) so a wedged driver fails
 * loudly instead of hanging the service. All teardown is best-effort and
 * exception-isolated; teardown steps never throw outward.
 */
internal const val GL_INIT_TIMEOUT_MS = 5_000L
internal const val GL_TEARDOWN_TIMEOUT_MS = 5_000L
/** J2 per-player stop/release rendezvous inside slot teardown. */
internal const val VIDEO_RELEASE_TIMEOUT_MS = 3_000L

/**
 * One static overlay's GL resources. Slots are preallocated once (fixed
 * array, never resized); only the used prefix is live between init and
 * EGL teardown.
 */
private class OverlaySlot {
    var textureId: Int = 0
    var quadBuffer: FloatBuffer? = null
    var alpha: Float = 1f
    var bitmap: Bitmap? = null
    // H9 dynamic-slot state: owner spec, glyph origin, last value.
    var spec: SceneOverlaySpec? = null
    var offsetLeft: Int = 0
    var offsetTop: Int = 0
    var lastContent: String? = null
    // J2 video-slot state: decoder + OES texture + ST + Surface.
    // `textureId`/`bitmap` carry the static style underlay; the video
    // quad uses `videoTextureId` with `videoNdcBuffer`/`videoUvBuffer`.
    var isVideo: Boolean = false
    var videoTextureId: Int = 0
    var videoSt: SurfaceTexture? = null
    var videoSurface: Surface? = null
    var videoPlayer: SceneVideoPlayer? = null
    var videoNdcBuffer: FloatBuffer? = null
    var videoUvBuffer: FloatBuffer? = null
    var videoStMatrix: FloatArray = FloatArray(16)
    var videoPendingFrame: Boolean = false
    var videoHasFrame: Boolean = false
    val videoController: SceneVideoPlaybackController = SceneVideoPlaybackController()
}

/** Init-latch scratch: one requested overlay with its measurement. */
private data class MeasuredOverlay(
    val spec: SceneOverlaySpec,
    val content: String,
    val measured: SceneSizePx,
    val offsetLeft: Int,
    val offsetTop: Int,
    val decodedBitmap: Bitmap? = null,
    val videoSize: SceneSizePx? = null
)

internal class SceneCompositorGL(
    private val controller: SceneCompositorController,
    private val outputSurface: Surface,
    private val layout: SceneSourceLayout,
    private val textContent: String?,
    private val subtextContent: String?,
    private val sceneLayers: List<SceneOverlaySpec>?,
    private val imageProvider: ((String) -> Bitmap?)? = null,
    private val sessionStartElapsedMs: Long = 0L,
    private val videoProbe: ((String) -> SceneSizePx?)? = null,
    private val videoPlayerFactory: ((String) -> SceneVideoPlayer)? = null
) : SurfaceTexture.OnFrameAvailableListener {

    private var workerThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program: Int = 0
    private var oesTextureId: Int = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var glReady = false
    private var frameSequence = 0L

    private var aPositionHandle = -1
    private var aTextureHandle = -1
    private var uMvpHandle = -1
    private var uStMatrixHandle = -1
    private var overlayProgram: Int = 0
    private var overlayPositionHandle = -1
    private var overlayCoordHandle = -1
    private var uSamplerHandle = -1
    private var uAlphaHandle = -1
    private var videoProgram: Int = 0
    private var videoPositionHandle = -1
    private var videoCoordHandle = -1
    private var videoMvpHandle = -1
    private var videoStMatrixHandle = -1
    private var videoSamplerHandle = -1
    private var videoAlphaHandle = -1
    private var lastInputTimestampNs: Long = 0L
    private var videoRedrawPosted: Boolean = false
    private val videoRedrawRunnable = Runnable { onVideoRedrawLocked() }
    private val overlaySlots = Array(MAX_STATIC_OVERLAY_LAYERS) { OverlaySlot() }
    private var overlaySlotCount = 0
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val dynamicTickRunnable = Runnable { onDynamicTickLocked() }
    /**
     * J2 pause flag for the service badge (revoke freeze). Written on
     * the GL thread, read from any thread; cleared on slot release.
     */
    @Volatile
    var videosPaused: Boolean = false
        private set

    /**
     * Starts the GL thread and initializes EGL + input producer endpoint.
     * Caller thread; blocks up to [GL_INIT_TIMEOUT_MS]. Returns the input
     * Surface the VD must target. Throws (after best-effort cleanup) when
     * init fails or times out — the caller maps this to start failure.
     */
    fun start(): Surface {
        val thread = HandlerThread(GL_THREAD_NAME).also { it.start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler
        var result: Surface? = null
        var failure: Throwable? = null
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result = initGlLocked()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(GL_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            failStart(RuntimeException("compositor GL init timed out after ${GL_INIT_TIMEOUT_MS}ms"))
        }
        failure?.let { failStart(it) }
        return result ?: failStart(IllegalStateException("compositor GL init produced no input surface"))
    }

    private fun failStart(cause: Throwable): Nothing {
        // Best-effort cleanup of partial GL state, then quit and rethrow the
        // original cause (never wrapped, never swallowed — fatals included).
        // The input endpoint dies first when init failed after creating it
        // (e.g. the overlay build threw): the thread quits below, so a
        // later releaseInput could no longer rendezvous to free it (H10).
        // No VD can be live here — start throws before VD creation.
        runOnGlThread(GL_TEARDOWN_TIMEOUT_MS) {
            releaseInputLocked()
            releaseEglLocked()
        }
        try {
            workerThread?.quitSafely()
        } catch (_: Exception) {
        }
        workerThread = null
        glHandler = null
        throw cause
    }

    /**
     * Releases the input endpoint (listener, queued callbacks, ST, Surface).
     * Any thread; rendezvous to the GL thread with timeout. Never throws.
     */
    fun releaseInput() {
        runOnGlThread(GL_TEARDOWN_TIMEOUT_MS) { releaseInputLocked() }
        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "input Surface release failed", e)
        }
        inputSurface = null
    }

    /**
     * Releases the input endpoint on the GL thread (listener, queued
     * callbacks, ST). Shared by [releaseInput] and [failStart]; the
     * caller-thread input Surface release stays in [releaseInput].
     */
    private fun releaseInputLocked() {
        try {
            inputSurfaceTexture?.setOnFrameAvailableListener(null)
        } catch (_: Exception) {
        }
        try {
            glHandler?.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }
        try {
            inputSurfaceTexture?.release()
        } catch (e: Exception) {
            Log.w(TAG, "input SurfaceTexture release failed", e)
        }
        inputSurfaceTexture = null
        glReady = false
    }

    /**
     * Destroys GL objects + EGL. Any thread; runs on the GL thread with
     * timeout. Never throws. Must run after [releaseInput] (the ST holds a
     * texture in this context) and before [quitThread].
     */
    fun releaseEgl() {
        runOnGlThread(GL_TEARDOWN_TIMEOUT_MS) { releaseEglLocked() }
    }

    /**
     * Quits the GL thread. Any thread; never throws. Must run last: the EGL
     * step above needs the thread alive.
     */
    fun quitThread() {
        try {
            glHandler?.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }
        try {
            workerThread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "GL thread quit failed", e)
        }
        workerThread = null
        glHandler = null
    }

    /**
     * H8 live rebuild: replaces the overlay stack with [specs] on the
     * running GL thread. Any thread; rendezvous with timeout. Returns
     * false (never throws) when the thread is gone, the rendezvous
     * times out, or the build fails — frames and rebuilds share the GL
     * handler queue, so no frame can draw a half-built stack. On build
     * failure the old slots are already released (clean passthrough);
     * the caller best-effort restores the previous stack.
     */
    fun rebuildOverlays(specs: List<SceneOverlaySpec>): Boolean {
        var ok = false
        val ran = runOnGlThread(GL_TEARDOWN_TIMEOUT_MS) {
            ok = try {
                rebuildOverlaysLocked(specs)
                true
            } catch (_: Throwable) {
                false
            }
        }
        return ran && ok
    }

    /**
     * J2 video pause/resume (any thread; async post, never throws).
     * Best-effort fast path for the revoke freeze — teardown's
     * stop/release rendezvous is the hard guarantee. Transitions run
     * through the per-slot controller, so a stale call (no live
     * video, already paused) is a no-op, never an error.
     */
    fun setVideosPaused(paused: Boolean) {
        videosPaused = paused
        try {
            glHandler?.post {
                for (index in 0 until overlaySlotCount) {
                    val slot = overlaySlots[index]
                    if (!slot.isVideo) continue
                    val player = slot.videoPlayer ?: continue
                    try {
                        if (paused) {
                            if (slot.videoController.onPaused()) player.pause()
                        } else {
                            if (slot.videoController.onPlaying()) player.resume()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Exposes the GL handler for VD/projection callbacks (cf. ScreenSource). */
    val handlerForCallbacks: Handler?
        get() = glHandler

    // ---- Frame path (GL thread only) ----

    override fun onFrameAvailable(ignored: SurfaceTexture?) {
        // Already on the GL thread: the listener was registered with glHandler,
        // and teardown nulls the listener + drains the queue, so no callback
        // can run concurrently with (or after) releaseInput on this thread.
        if (!controller.isAcceptingFrames) return
        val surfaceTexture = inputSurfaceTexture
        if (surfaceTexture == null || !glReady) return
        val metadata: SceneFrameMetadata
        try {
            surfaceTexture.updateTexImage()
            metadata = SceneFrameMetadata(frameSequence++, surfaceTexture.timestamp)
            if (metadata.sequence == 0L) {
                Log.i(TAG, "first frame latched ts=${metadata.timestampNs}")
            }
        } catch (error: RuntimeException) {
            failFrame("compositor input latch failed", error)
            return
        }
        val handle = controller.onFrameArrival(metadata)
        if (handle == null) return // Counted drop/reject: skip the draw.
        lastInputTimestampNs = metadata.timestampNs
        try {
            drawFrameLocked(surfaceTexture)
            drawSlotsLocked()
            // EGL_ANDROID_presentation_time: present-time is timestamp currency
            // for EGL producers (per the SurfaceTexture contract), so the
            // captured monotonic-nanoseconds timestamp keeps its clock domain
            // across the compositor hop. EGLExt is API 18+ (minSdk 29), so no
            // availability guard is possible or needed; the EGLBoolean result
            // is checked and a failure is loud (frame still swaps in queue
            // order on arrival-time fallback, matching swap handling below).
            if (!EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, metadata.timestampNs)) {
                Log.e(TAG, "eglPresentationTimeANDROID failed for ts=${metadata.timestampNs}")
            }
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                // Library-lenient (SurfaceManager.swapBuffers only logs):
                // keep the cadence; a dead output freezes like a direct-path
                // VD stall rather than inventing recovery.
                Log.e(TAG, "eglSwapBuffers failed")
            }
        } catch (error: RuntimeException) {
            failFrame("compositor draw failed", error)
        } finally {
            controller.onFrameComplete(handle)
        }
    }

    private fun failFrame(message: String, error: RuntimeException) {
        if (controller.isAcceptingFrames) {
            Log.e(TAG, message, error)
            controller.onRenderFailure()
        } else {
            Log.w(TAG, "$message (teardown race, expected): ${error.message}")
        }
    }

    // ---- GL internals (GL thread only unless noted) ----

    /**
     * Runs [block] on the GL thread, bounded. Returns false (no throw, no
     * hang) when the thread is gone or the post/await fails — teardown
     * callers treat false as "nothing left to run".
     */
    private fun runOnGlThread(timeoutMs: Long, block: () -> Unit): Boolean {
        val handler = glHandler ?: return false
        val thread = workerThread
        if (thread == null || !thread.isAlive) return false
        val latch = CountDownLatch(1)
        val posted = try {
            handler.post {
                try {
                    block()
                } catch (_: Exception) {
                    // Teardown steps are best-effort; frame-path blocks report
                    // through their own failFrame handling.
                } finally {
                    latch.countDown()
                }
            }
        } catch (_: Exception) {
            false
        }
        if (!posted) return false
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun initGlLocked(): Surface {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("eglInitialize failed")
        }
        val config = chooseConfig(eglDisplay)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        checkEgl("eglCreateContext")
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        checkEgl("eglCreateWindowSurface")
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
        program = buildProgram()
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uStMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        Matrix.setIdentityM(mvpMatrix, 0)
        vertexBuffer = floatBufferOf(QUAD_COORDS)
        texCoordBuffer = floatBufferOf(QUAD_TEX_COORDS)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGl("glGenTextures")
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        checkGl("oes texture setup")
        val surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture.setDefaultBufferSize(layout.displayWidth, layout.displayHeight)
        surfaceTexture.setOnFrameAvailableListener(this, glHandler)
        inputSurfaceTexture = surfaceTexture
        val surface = Surface(surfaceTexture)
        inputSurface = surface
        initOverlayStackLocked()
        glReady = true
        Log.i(TAG, "compositor ready: input=${layout.displayWidth}x${layout.displayHeight} " +
            "output=${layout.outputWidth}x${layout.outputHeight}")
        return surface
    }

    /**
     * Draws every claimed slot (GL thread): static slots through the
     * 2D blend program, video slots through the style quad + OES
     * video quad. Shared by input-driven frames and on-demand video
     * redraws.
     */
    private fun drawSlotsLocked() {
        for (index in 0 until overlaySlotCount) {
            val slot = overlaySlots[index]
            if (slot.isVideo) drawVideoSlotLocked(slot) else drawOverlaySlotLocked(slot)
        }
    }

    /**
     * J2 coalesced redraw request from a video ST arrival (GL thread).
     * The screen VD emits only on content change, so motion must
     * re-present itself on static screens. At most one redraw is
     * queued per video frame; before the first input latch the
     * pending video frame simply waits for the first screen draw.
     */
    private fun requestVideoRedrawLocked() {
        if (videoRedrawPosted) return
        if (!glReady || frameSequence == 0L) return
        videoRedrawPosted = true
        try {
            glHandler?.post(videoRedrawRunnable)
        } catch (_: Exception) {
            videoRedrawPosted = false
        }
    }

    /**
     * J2 on-demand redraw (GL thread): re-presents the last latched
     * input frame with fresh video latches. Not a controller arrival
     * (no counting — this is re-presentation, not a new frame) and
     * reuses the last input timestamp (arrival-order fallback, same
     * rationale as the swap handling in the input path).
     */
    private fun onVideoRedrawLocked() {
        videoRedrawPosted = false
        if (!controller.isAcceptingFrames) return
        val surfaceTexture = inputSurfaceTexture
        if (surfaceTexture == null || !glReady || frameSequence == 0L) return
        try {
            drawFrameLocked(surfaceTexture)
            drawSlotsLocked()
            if (!EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, lastInputTimestampNs)) {
                Log.e(TAG, "video redraw eglPresentationTimeANDROID failed")
            }
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.e(TAG, "video redraw eglSwapBuffers failed")
            }
        } catch (error: RuntimeException) {
            failFrame("video redraw draw failed", error)
        }
    }

    private fun drawFrameLocked(surfaceTexture: SurfaceTexture) {
        surfaceTexture.getTransformMatrix(stMatrix)
        GLES20.glViewport(0, 0, layout.outputWidth, layout.outputHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val positions = vertexBuffer
        val texCoords = texCoordBuffer
        if (positions == null || texCoords == null) throw RuntimeException("compositor buffers missing")
        positions.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aTextureHandle)
        GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureHandle)
        checkGl("compositor draw")
    }

    /**
     * J2 video ST arrival (GL thread — the listener was registered
     * with glHandler, so this serializes with frames, rebuilds, and
     * teardown exactly like the input path). Marks the frame pending
     * and asks for a coalesced re-presentation; the latch itself
     * happens at draw time.
     */
    private fun onVideoFrameLocked(slot: OverlaySlot) {
        slot.videoPendingFrame = true
        requestVideoRedrawLocked()
    }

    private fun releaseEglLocked() {
        glReady = false
        // Overlay slots die first (reverse of creation), inside the same
        // EGL step that owns the OES program — see the teardown-order
        // contract pinned in SceneCompositorControllerTest.
        releaseOverlaySlotsLocked()
        try {
            if (program != 0) GLES20.glDeleteProgram(program)
        } catch (_: Exception) {
        }
        program = 0
        try {
            if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
        } catch (_: Exception) {
        }
        oesTextureId = 0
        vertexBuffer = null
        texCoordBuffer = null
        val display = eglDisplay
        if (display != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            } catch (_: Exception) {
            }
            try {
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            } catch (_: Exception) {
            }
            try {
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            } catch (_: Exception) {
            }
            try {
                EGL14.eglReleaseThread()
            } catch (_: Exception) {
            }
            try {
                EGL14.eglTerminate(display)
            } catch (_: Exception) {
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            Log.i(TAG, "compositor EGL released")
        }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        // RGBA8888 + ES2 + recordable window config (mirrors the library
        // SurfaceManager window attribs; universally available for window
        // surfaces on API 18+).
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            throw RuntimeException("eglChooseConfig found no RGBA8888 ES2 recordable config")
        }
        return configs[0]!!
    }

    private fun buildProgram(): Int {
        // trimIndent: keep every line (notably #extension) free of leading
        // whitespace for pedantic drivers, regardless of code formatting.
        return buildProgramFor(VERTEX_SHADER.trimIndent(), FRAGMENT_SHADER.trimIndent())
    }

    /**
     * H4 overlay-stack setup (GL thread, inside init latch): builds the
     * requested specs (H5 editor list or the legacy fixed two-string
     * mapping) then delegates to [buildSlotsLocked]. No-op when no
     * overlay was requested (passthrough). Throws loudly on any failure
     * — a half-built stack never reaches the frame path.
     */
    private fun initOverlayStackLocked() {
        val editorSpecs = sceneLayers
        if (editorSpecs != null) {
            // H5 scene path: validated specs from the editor
            // (service-mapped through H4 `of()`); filtering, resolve,
            // and upload all live in buildSlotsLocked, shared with H8
            // live rebuild.
            buildSlotsLocked(editorSpecs)
            return
        }
        // Legacy H4 path (fixed two-string mapping): primary text
        // top-left above passthrough, second text bottom-left above that.
        val outW = layout.outputWidth
        val outH = layout.outputHeight
        val margin = SceneTextLayerLayout.marginPxFor(outH)
        val requests = mutableListOf<SceneOverlaySpec>()
        fun request(id: String, content: String?, anchor: SceneOverlayAnchor, z: Int) {
            if (content.isNullOrBlank()) return
            requests.add(
                SceneOverlaySpec.of(
                    id = id,
                    text = content,
                    anchor = anchor,
                    zOrder = z,
                    marginPx = margin,
                    maxWidthPx = outW,
                    alpha = 1f
                ) ?: throw RuntimeException("overlay spec invalid: $id")
            )
        }
        request(TEXT_LAYER_ID, textContent, SceneOverlayAnchor.TOP_LEFT, 1)
        request(SUBTEXT_LAYER_ID, subtextContent, SceneOverlayAnchor.BOTTOM_LEFT, 2)
        if (requests.isEmpty()) return
        buildSlotsLocked(requests)
    }

    /**
     * H8 live rebuild core (GL thread only): drops every overlay slot,
     * then builds [specs] from scratch. Frames and rebuilds share the
     * GL handler queue, so no frame can draw a half-built stack. Throws
     * on build failure — the caller maps this to false, leaving clean
     * passthrough (slots already released) until the next rebuild.
     */
    private fun rebuildOverlaysLocked(specs: List<SceneOverlaySpec>) {
        releaseOverlaySlotsLocked()
        buildSlotsLocked(specs)
    }

    /**
     * Deletes overlay textures/bitmaps and the shared blend program
     * (GL thread only). Best-effort and exception-isolated: shared by
     * EGL teardown and H8 live rebuild.
     */
    private fun releaseOverlaySlotsLocked() {
        // H9: exactly one ticker iff a complete stack holds a dynamic
        // slot — release cancels, build re-arms.
        try {
            glHandler?.removeCallbacks(dynamicTickRunnable)
        } catch (_: Exception) {
        }
        // J2: video redraws cancel the same way; the pause flag dies
        // with the slots (no badge without live video).
        try {
            glHandler?.removeCallbacks(videoRedrawRunnable)
        } catch (_: Exception) {
        }
        videoRedrawPosted = false
        videosPaused = false
        // J2: decoders stop BEFORE their STs die (a player rendering
        // into a released ST would error loudly but harmlessly; clean
        // order avoids even that). Bounded rendezvous per player.
        for (index in 0 until overlaySlotCount) {
            val slot = overlaySlots[index]
            val player = slot.videoPlayer
            slot.videoPlayer = null
            if (player != null) {
                try {
                    if (!player.stopAndRelease(VIDEO_RELEASE_TIMEOUT_MS)) {
                        Log.w(TAG, "video release timed out: ${slot.spec?.id}")
                    }
                } catch (_: Exception) {
                }
            }
        }
        for (index in 0 until overlaySlotCount) {
            val slot = overlaySlots[index]
            try {
                if (slot.textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(slot.textureId), 0)
            } catch (_: Exception) {
            }
            slot.textureId = 0
            try {
                slot.bitmap?.recycle()
            } catch (_: Exception) {
            }
            slot.bitmap = null
            slot.quadBuffer = null
            slot.spec = null
            slot.offsetLeft = 0
            slot.offsetTop = 0
            slot.lastContent = null
            releaseVideoSlotLocked(slot)
        }
        overlaySlotCount = 0
        try {
            if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram)
        } catch (_: Exception) {
        }
        overlayProgram = 0
        try {
            if (videoProgram != 0) GLES20.glDeleteProgram(videoProgram)
        } catch (_: Exception) {
        }
        videoProgram = 0
    }

    /**
     * J2 video-slot resource release (GL thread only): ST listener
     * nulled (no post-release callbacks on this queue), ST + Surface
     * released, OES texture deleted, video fields cleared, controller
     * reset for slot reuse. The player is already stopped above.
     * Best-effort and exception-isolated; never throws.
     */
    private fun releaseVideoSlotLocked(slot: OverlaySlot) {
        try {
            slot.videoSt?.setOnFrameAvailableListener(null)
        } catch (_: Exception) {
        }
        try {
            slot.videoSt?.release()
        } catch (e: Exception) {
            Log.w(TAG, "video SurfaceTexture release failed", e)
        }
        slot.videoSt = null
        try {
            slot.videoSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "video Surface release failed", e)
        }
        slot.videoSurface = null
        try {
            if (slot.videoTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(slot.videoTextureId), 0)
            }
        } catch (_: Exception) {
        }
        slot.videoTextureId = 0
        slot.videoNdcBuffer = null
        slot.videoUvBuffer = null
        slot.videoPendingFrame = false
        slot.videoHasFrame = false
        slot.isVideo = false
        try {
            slot.videoController.onRebuild()
        } catch (_: Exception) {
        }
    }

    /**
     * Measures, resolves, rasters once and uploads once per layer, and
     * builds the shared blend program (GL thread only). Disabled layers
     * occupy no slot; blank TEXT layers carry no glyphs and are
     * skipped. IMAGE layers carry empty text by construction and are
     * never filtered here — a `text.isBlank()` gate would drop every
     * image (the H6 GL path always skipped images through exactly that
     * gate; fixed here for both init and rebuild). No-op when nothing
     * remains (passthrough). Throws loudly on any failure.
     */
    private fun buildSlotsLocked(specs: List<SceneOverlaySpec>) {
        val outW = layout.outputWidth
        val outH = layout.outputHeight
        val textSizePx = SceneTextLayerLayout.textSizePxFor(outH)
        val requests = specs.filter { it.enabled && (it.kind != SceneLayerKind.TEXT || it.text.isNotBlank()) }
        if (requests.isEmpty()) return
        // J2: decoder budget decided once per build (deterministic —
        // same input, same decoders, every rebuild).
        val videoBudget = SceneVideoBudget.assign(requests)
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = textSizePx.toFloat()
        }
        if (paint.textSize <= 0f) throw RuntimeException("overlay text size invalid for height=$outH")
        val bounds = Rect()
        val decodedPending = mutableListOf<Bitmap>()
        val buildWallMs = System.currentTimeMillis()
        val buildElapsedMs = SystemClock.elapsedRealtime()
        try {
            val measured = requests.mapNotNull { spec ->
                if (spec.kind == SceneLayerKind.IMAGE) {
                    measureImageLocked(spec, outW, outH, decodedPending)
                } else if (spec.kind == SceneLayerKind.VIDEO) {
                    measureVideoLocked(spec, outW, outH, videoBudget)
                } else {
                    // H9 widgets measure the worst-case box (stable
                    // placement forever) but raster the current value.
                    val measureContent = when (spec.kind) {
                        SceneLayerKind.CLOCK -> SceneClockWidgets.WALL_CLOCK_WORST_CASE
                        SceneLayerKind.TIMER -> SceneClockWidgets.TIMER_WORST_CASE
                        else -> spec.text
                    }
                    paint.getTextBounds(measureContent, 0, measureContent.length, bounds)
                    val styled = SceneLayerStyle.styledSize(
                        SceneSizePx(bounds.width(), bounds.height()),
                        spec.borderWidthPx, spec.shadowOffsetPx
                    ) ?: throw RuntimeException("overlay styled size invalid: ${spec.id}")
                    MeasuredOverlay(
                        spec = spec,
                        content = liveWidgetContent(spec.kind, buildWallMs, buildElapsedMs)
                            ?: spec.text,
                        measured = styled,
                        offsetLeft = bounds.left,
                        offsetTop = bounds.top
                    )
                }
            }
            val entries = SceneOverlayStack.resolve(
                SceneCanvas(outW, outH),
                measured.map { SceneOverlayLayerInput(it.spec, it.measured) }
            ) ?: throw RuntimeException("overlay stack invalid for ${outW}x${outH}")
            if (entries.isEmpty()) return // Defensive: requests non-empty and all enabled.
            overlayProgram = buildProgramFor(
                OVERLAY_VERTEX_SHADER.trimIndent(), OVERLAY_FRAGMENT_SHADER.trimIndent()
            )
            overlayPositionHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
            overlayCoordHandle = GLES20.glGetAttribLocation(overlayProgram, "aTextureCoord")
            uSamplerHandle = GLES20.glGetUniformLocation(overlayProgram, "uSampler")
            uAlphaHandle = GLES20.glGetUniformLocation(overlayProgram, "uAlpha")
            val byId = measured.associateBy { it.spec.id }
            val textures = IntArray(1)
            for (entry in entries) {
                val layer = byId.getValue(entry.id)
                val rect = entry.rectPx
                if (layer.spec.kind == SceneLayerKind.VIDEO) {
                    // Best-effort runtime media: a failed video slot
                    // skips loudly and later slots stay dense — never
                    // a hole, never a dead stream for a bad video.
                    if (!buildVideoSlotLocked(layer, entry)) {
                        Log.w(TAG, "overlay video slot skipped: ${entry.id}")
                    }
                    continue
                }
                val slot = overlaySlots[overlaySlotCount]
                // Count first: if any step below throws, teardown deletes and
                // recycles exactly the slots claimed so far — no leak, no
                // half-built slot on the frame path.
                overlaySlotCount += 1
                val bitmap = if (layer.decodedBitmap != null) {
                    composeImageBitmapLocked(layer.decodedBitmap, rect, layer.spec, decodedPending)
                } else {
                    composeTextBitmapLocked(layer, rect, paint)
                }
                slot.bitmap = bitmap
                GLES20.glGenTextures(1, textures, 0)
                checkGl("glGenTextures(overlay)")
                slot.textureId = textures[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.textureId)
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
                )
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                checkGl("overlay texture upload")
                slot.quadBuffer = floatBufferOf(entry.ndcQuad)
                slot.alpha = entry.alpha
                slot.spec = layer.spec
                slot.offsetLeft = layer.offsetLeft
                slot.offsetTop = layer.offsetTop
                slot.lastContent = layer.content
                // Sanitized: layer id + kind/length only, never content.
                val detail = if (layer.spec.kind == SceneLayerKind.IMAGE) "image" else "${layer.content.length} chars"
                // (Video slots log their own ready line in buildVideoSlotLocked.)
                Log.i(TAG, "overlay ready: ${entry.id} $detail " +
                    "${rect.width}x${rect.height}")
            }
            armDynamicTickLocked()
        } finally {
            for (pending in decodedPending) {
                try {
                    pending.recycle()
                } catch (_: Throwable) {
                }
            }
            decodedPending.clear()
        }
    }

    /**
     * Current rendered value for a widget kind, or null for static
     * content. GL thread only (reads the latched session start).
     */
    private fun liveWidgetContent(kind: SceneLayerKind, wallMs: Long, elapsedMs: Long): String? =
        when (kind) {
            SceneLayerKind.CLOCK -> SceneClockWidgets.formatWallClock(wallMs, zoneId)
            SceneLayerKind.TIMER -> SceneClockWidgets.formatElapsed(sessionStartElapsedMs, elapsedMs)
            else -> null
        }

    /**
     * H9 ticker arm (GL thread only): schedules the single delayed
     * tick when the built stack holds a dynamic slot. Idempotent
     * (cancels first); cadence is the fastest needed, aligned to the
     * next boundary. No dynamic slot → no ticker, zero overhead.
     */
    private fun armDynamicTickLocked() {
        try {
            glHandler?.removeCallbacks(dynamicTickRunnable)
        } catch (_: Exception) {
        }
        var periodMs = 0L
        for (index in 0 until overlaySlotCount) {
            when (overlaySlots[index].spec?.kind) {
                SceneLayerKind.TIMER -> {
                    periodMs = SceneClockWidgets.TIMER_TICK_PERIOD_MS
                    break
                }
                SceneLayerKind.CLOCK -> periodMs = SceneClockWidgets.CLOCK_TICK_PERIOD_MS
                else -> { }
            }
        }
        if (periodMs <= 0L) return
        // Anchor the delay to the ticking clock: wall for CLOCK-only,
        // monotonic for any TIMER.
        val nowMs = if (periodMs == SceneClockWidgets.TIMER_TICK_PERIOD_MS) {
            SystemClock.elapsedRealtime()
        } else {
            System.currentTimeMillis()
        }
        val delayMs = SceneClockWidgets.tickDelayMs(nowMs, periodMs) ?: return
        try {
            glHandler?.postDelayed(dynamicTickRunnable, delayMs)
        } catch (_: Exception) {
        }
    }

    /**
     * H9 tick (GL thread only): re-renders every dynamic slot whose
     * value changed, then re-arms. Same queue as frames, so ticks
     * never interleave a draw. A failing slot logs loudly and retries
     * next tick — a wedged tick never kills the GL thread.
     */
    private fun onDynamicTickLocked() {
        if (!glReady) return
        val wallMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        for (index in 0 until overlaySlotCount) {
            val slot = overlaySlots[index]
            val spec = slot.spec ?: continue
            val content = liveWidgetContent(spec.kind, wallMs, elapsedMs) ?: continue
            try {
                refreshTextSlotLocked(slot, spec, content)
            } catch (e: RuntimeException) {
                Log.e(TAG, "dynamic overlay refresh failed: ${spec.id}", e)
            }
        }
        armDynamicTickLocked()
    }

    /**
     * Re-renders one text slot in place (GL thread only): same fixed
     * style order and offsets as the initial compose, then re-uploads
     * into the live texture. Skips unchanged content. The bitmap is
     * reused (erase + redraw) — zero allocation per tick.
     */
    private fun refreshTextSlotLocked(slot: OverlaySlot, spec: SceneOverlaySpec, content: String) {
        if (slot.lastContent == content) return
        val bitmap = slot.bitmap ?: return
        if (slot.textureId == 0) return
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        drawStyleUnderlayLocked(canvas, bitmap.width, bitmap.height, spec)
        val paint = Paint().apply {
            isAntiAlias = true
            color = spec.textColor
            textSize = SceneTextLayerLayout.textSizePxFor(layout.outputHeight).toFloat()
        }
        canvas.drawText(
            content,
            -slot.offsetLeft.toFloat() + spec.borderWidthPx,
            -slot.offsetTop.toFloat() + spec.borderWidthPx,
            paint
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        checkGl("dynamic overlay re-upload")
        slot.lastContent = content
    }

    /**
     * H6 image measurement (GL thread, overlay build only, never per
     * frame): decode once via the provider, aspect-fit to the spec
     * bounds. Null = skip loudly; the decoded bitmap (if any) stays in
     * [decodedPending] for the build's finally-recycle.
     */
    private fun measureImageLocked(
        spec: SceneOverlaySpec,
        outW: Int,
        outH: Int,
        decodedPending: MutableList<Bitmap>
    ): MeasuredOverlay? {
        val uri = spec.imageUri
        if (uri == null) {
            Log.w(TAG, "overlay image layer without uri, skipping: ${spec.id}")
            return null
        }
        val bitmap = try {
            imageProvider?.invoke(uri)
        } catch (_: Throwable) {
            null
        }
        if (bitmap == null) {
            Log.w(TAG, "overlay image unavailable, skipping: ${spec.id}")
            return null
        }
        decodedPending.add(bitmap)
        // J1 FILL measures the full bounds frame (cover-crop at
        // compose); FIT keeps the H6 aspect-fit measurement.
        val size = if (spec.scaleMode == SceneScaleMode.FILL) {
            SceneImageLayer.fillSize(
                spec.maxWidthPx, outH - spec.marginPx * 2,
                outW - spec.marginPx * 2, outH - spec.marginPx * 2
            )
        } else {
            SceneImageLayer.measuredSize(
                bitmap.width, bitmap.height, spec.maxWidthPx, outH - spec.marginPx * 2
            )
        }
        if (size == null) {
            Log.w(TAG, "overlay image unmeasurable, skipping: ${spec.id}")
            return null
        }
        val styled = SceneLayerStyle.styledSize(size, spec.borderWidthPx, spec.shadowOffsetPx)
            ?: throw RuntimeException("overlay styled size invalid: ${spec.id}")
        return MeasuredOverlay(spec, "", styled, 0, 0, bitmap)
    }

    /**
     * J2 video measurement (GL thread, overlay build only, never per
     * frame): probe once via the provider, FIT/FILL through the
     * shared J1 math. Null = skip loudly (over budget, unprobable,
     * or unmeasurable). The probed dims ride along for UV mapping.
     */
    private fun measureVideoLocked(
        spec: SceneOverlaySpec,
        outW: Int,
        outH: Int,
        videoBudget: Set<String>
    ): MeasuredOverlay? {
        val uri = spec.imageUri
        if (uri == null) {
            Log.w(TAG, "overlay video layer without uri, skipping: ${spec.id}")
            return null
        }
        if (!videoBudget.contains(spec.id)) {
            Log.w(TAG, "overlay video over decoder budget, skipping: ${spec.id}")
            return null
        }
        val dims = try {
            videoProbe?.invoke(uri)
        } catch (_: Throwable) {
            null
        }
        if (dims == null) {
            Log.w(TAG, "overlay video unprobable, skipping: ${spec.id}")
            return null
        }
        // Same shared math as images: FIT aspect-fit, FILL full frame.
        val size = if (spec.scaleMode == SceneScaleMode.FILL) {
            SceneImageLayer.fillSize(
                spec.maxWidthPx, outH - spec.marginPx * 2,
                outW - spec.marginPx * 2, outH - spec.marginPx * 2
            )
        } else {
            SceneImageLayer.measuredSize(
                dims.widthPx, dims.heightPx, spec.maxWidthPx, outH - spec.marginPx * 2
            )
        }
        if (size == null) {
            Log.w(TAG, "overlay video unmeasurable, skipping: ${spec.id}")
            return null
        }
        val styled = SceneLayerStyle.styledSize(size, spec.borderWidthPx, spec.shadowOffsetPx)
            ?: throw RuntimeException("overlay styled size invalid: ${spec.id}")
        return MeasuredOverlay(spec, "", styled, 0, 0, null, dims)
    }

    /**
     * H7 text slot bitmap: style underlay (shadow → background → border)
     * then glyphs in the layer text color at the padding offset. The
     * resolved rect may clip oversized content (same capping as H3–H6).
     */
    private fun composeTextBitmapLocked(
        layer: MeasuredOverlay,
        rect: SceneRectPx,
        paint: Paint
    ): Bitmap {
        val spec = layer.spec
        val bitmap = Bitmap.createBitmap(rect.width, rect.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawStyleUnderlayLocked(canvas, rect.width, rect.height, spec)
        paint.color = spec.textColor
        canvas.drawText(
            layer.content,
            -layer.offsetLeft.toFloat() + spec.borderWidthPx,
            -layer.offsetTop.toFloat() + spec.borderWidthPx,
            paint
        )
        return bitmap
    }

    /**
     * H7 image slot bitmap: style underlay, then the decoded image scaled
     * to the content box (rect minus padding, at least 1px; the canvas
     * clips any overflow from resolve capping). Ownership of [src]
     * transfers here: it leaves [decodedPending] and is recycled after
     * drawing (the scaler temp too) — the composed bitmap is the only
     * survivor, recycled by slot teardown.
     */
    private fun composeImageBitmapLocked(
        src: Bitmap,
        rect: SceneRectPx,
        spec: SceneOverlaySpec,
        decodedPending: MutableList<Bitmap>
    ): Bitmap {
        decodedPending.remove(src)
        val contentW = (rect.width - spec.borderWidthPx * 2 - spec.shadowOffsetPx).coerceAtLeast(1)
        val contentH = (rect.height - spec.borderWidthPx * 2 - spec.shadowOffsetPx).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(rect.width, rect.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawStyleUnderlayLocked(canvas, rect.width, rect.height, spec)
        if (spec.scaleMode == SceneScaleMode.FILL) {
            // J1: cover-crop first, then one direct src->dst draw (no
            // scaler temp). A null crop is logic-impossible (same
            // inputs measured); fall back to the full bitmap loudly.
            val crop = SceneImageLayer.fillCrop(src.width, src.height, contentW, contentH)
            if (crop == null) Log.w(TAG, "overlay fill crop failed, drawing full: ${spec.id}")
            val srcRect = if (crop == null) null else Rect(crop.left, crop.top, crop.right, crop.bottom)
            val dst = RectF(
                spec.borderWidthPx.toFloat(), spec.borderWidthPx.toFloat(),
                (spec.borderWidthPx + contentW).toFloat(), (spec.borderWidthPx + contentH).toFloat()
            )
            canvas.drawBitmap(src, srcRect, dst, null)
            try {
                src.recycle()
            } catch (_: Throwable) {
            }
            return bitmap
        }
        val scaled = Bitmap.createScaledBitmap(src, contentW, contentH, true)
        canvas.drawBitmap(scaled, spec.borderWidthPx.toFloat(), spec.borderWidthPx.toFloat(), null)
        try {
            scaled.recycle()
        } catch (_: Throwable) {
        }
        if (scaled !== src) {
            try {
                src.recycle()
            } catch (_: Throwable) {
            }
        }
        return bitmap
    }

    /**
     * Fixed style draw order (shared with the preview): offset shadow,
     * background fill, border stroke. Zero-cost when unstyled (all
     * widths zero, no background). Radius clamps to half the box.
     */
    private fun drawStyleUnderlayLocked(canvas: Canvas, width: Int, height: Int, spec: SceneOverlaySpec) {
        val border = spec.borderWidthPx
        val shadow = spec.shadowOffsetPx
        if (shadow <= 0 && spec.backgroundColor == null && border <= 0) return
        val radius = spec.cornerRadiusPx.toFloat().coerceAtMost(minOf(width, height).toFloat() / 2f)
        val fill = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        if (shadow > 0) {
            fill.color = spec.shadowColor
            canvas.drawRoundRect(
                shadow.toFloat(), shadow.toFloat(), width.toFloat(), height.toFloat(),
                radius, radius, fill
            )
        }
        if (spec.backgroundColor != null) {
            fill.color = spec.backgroundColor
            canvas.drawRoundRect(
                0f, 0f, (width - shadow).toFloat(), (height - shadow).toFloat(),
                radius, radius, fill
            )
        }
        if (border > 0) {
            val half = border.toFloat() / 2f
            val stroke = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = border.toFloat()
                color = spec.borderColor
            }
            canvas.drawRoundRect(
                half, half, (width - shadow).toFloat() - half, (height - shadow).toFloat() - half,
                radius, radius, stroke
            )
        }
    }

    /**
     * J2 video slot build (GL thread only): static style-underlay 2D
     * texture + OES video texture + SurfaceTexture + Surface +
     * decoder, with content-inset NDC and scale-mode UVs. Returns
     * false (never throws) when anything fails — the caller skips
     * the slot loudly and the stack stays dense. A partial build is
     * always unwound by [abandonVideoSlotLocked]: no leaked texture,
     * ST, Surface, or player thread.
     */
    private fun buildVideoSlotLocked(layer: MeasuredOverlay, entry: SceneOverlayDrawEntry): Boolean {
        val spec = layer.spec
        val dims = layer.videoSize ?: return false
        val uri = spec.imageUri ?: return false
        val factory = videoPlayerFactory ?: return false
        val rect = entry.rectPx
        val slot = overlaySlots[overlaySlotCount]
        var success = false
        try {
            if (videoProgram == 0) buildVideoProgramLocked()
            // Style-only 2D texture: underlay over transparent content.
            val style = Bitmap.createBitmap(rect.width, rect.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(style)
            drawStyleUnderlayLocked(canvas, rect.width, rect.height, spec)
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            checkGl("glGenTextures(video style)")
            slot.textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.textureId)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, style, 0)
            checkGl("video style upload")
            slot.bitmap = style
            // OES video texture + SurfaceTexture + Surface. Created on
            // the GL thread with this context current, consumed on
            // the same thread — no attach/detach dance needed.
            GLES20.glGenTextures(1, textures, 0)
            checkGl("glGenTextures(video oes)")
            slot.videoTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, slot.videoTextureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            checkGl("video oes texture setup")
            val surfaceTexture = SurfaceTexture(slot.videoTextureId)
            surfaceTexture.setDefaultBufferSize(dims.widthPx, dims.heightPx)
            slot.videoSt = surfaceTexture
            slot.videoSurface = Surface(surfaceTexture)
            val content = contentRect(rect, spec)
            val ndc = SceneTextLayerLayout.ndcQuad(content, layout.outputWidth, layout.outputHeight)
                ?: throw RuntimeException("video content ndc invalid: ${spec.id}")
            slot.videoNdcBuffer = floatBufferOf(ndc)
            slot.videoUvBuffer = floatBufferOf(videoUvFor(spec, dims, content))
            slot.quadBuffer = floatBufferOf(entry.ndcQuad)
            slot.alpha = entry.alpha
            slot.spec = spec
            slot.isVideo = true
            Matrix.setIdentityM(slot.videoStMatrix, 0)
            slot.videoController.onRebuild()
            if (!slot.videoController.onSlotAssigned()) {
                throw RuntimeException("video controller stuck: ${spec.id}")
            }
            surfaceTexture.setOnFrameAvailableListener(
                SurfaceTexture.OnFrameAvailableListener { onVideoFrameLocked(slot) },
                glHandler
            )
            val player = factory(spec.id)
            slot.videoPlayer = player
            val surface = slot.videoSurface ?: throw RuntimeException("video surface missing")
            player.prepare(uri, surface, playerListener(slot, player))
            overlaySlotCount += 1
            Log.i(TAG, "overlay ready: ${entry.id} video ${rect.width}x${rect.height}")
            success = true
            return true
        } catch (t: Exception) {
            Log.w(TAG, "overlay video build failed: ${spec.id}", t)
            return false
        } finally {
            if (!success) abandonVideoSlotLocked(slot)
        }
    }

    /**
     * J2 player callbacks for one slot (called on the player thread;
     * re-posted to GL). Identity-guarded: a callback from a retired
     * player (rebuild won the race) is ignored, so an old decoder can
     * never steer the reused slot.
     */
    private fun playerListener(slot: OverlaySlot, player: SceneVideoPlayer): SceneVideoPlayer.Listener =
        object : SceneVideoPlayer.Listener {
            override fun onPrepared(videoWidth: Int, videoHeight: Int) {
                postToGl {
                    if (slot.videoPlayer !== player) return@postToGl
                    slot.videoController.onPlaying()
                    Log.i(TAG, "video playing: ${slot.spec?.id} ${videoWidth}x${videoHeight}")
                }
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                Log.i(TAG, "video size: ${slot.spec?.id} ${width}x${height}")
            }

            override fun onError(what: Int, extra: Int) {
                // Release on the player thread (already there — no
                // rendezvous, no GL block, no deadlock); state moves
                // on GL. The slot keeps its style quad, draws no
                // video, and the stream continues.
                try {
                    player.releaseAsync()
                } catch (_: Exception) {
                }
                postToGl {
                    if (slot.videoPlayer !== player) return@postToGl
                    slot.videoPlayer = null
                    slot.videoPendingFrame = false
                    slot.videoHasFrame = false
                    slot.videoController.onError("decoder $what/$extra")
                    Log.e(TAG, "video failed: ${slot.spec?.id} what=$what extra=$extra")
                }
            }

            override fun onCompletion() {
                Log.i(TAG, "video completed (looping): ${slot.spec?.id}")
            }
        }

    /**
     * J2 partial-build unwind (GL thread only): releases everything
     * [buildVideoSlotLocked] may have claimed. Best-effort and
     * exception-isolated; never throws.
     */
    private fun abandonVideoSlotLocked(slot: OverlaySlot) {
        val player = slot.videoPlayer
        slot.videoPlayer = null
        if (player != null) {
            try {
                player.releaseAsync()
            } catch (_: Exception) {
            }
        }
        releaseVideoSlotLocked(slot)
        try {
            if (slot.textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(slot.textureId), 0)
        } catch (_: Exception) {
        }
        slot.textureId = 0
        try {
            slot.bitmap?.recycle()
        } catch (_: Exception) {
        }
        slot.bitmap = null
        slot.quadBuffer = null
        slot.spec = null
    }

    /** Posts [block] to the GL queue (any thread; drops when gone). Never throws. */
    private fun postToGl(block: () -> Unit) {
        try {
            glHandler?.post {
                try {
                    block()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * J2 content rect: the resolved rect minus style padding (border
     * + shadow), at least 1px — the same content box the image path
     * draws into. Pure geometry on resolved values.
     */
    private fun contentRect(rect: SceneRectPx, spec: SceneOverlaySpec): SceneRectPx {
        val left = rect.left + spec.borderWidthPx
        val top = rect.top + spec.borderWidthPx
        val w = (rect.width - spec.borderWidthPx * 2 - spec.shadowOffsetPx).coerceAtLeast(1)
        val h = (rect.height - spec.borderWidthPx * 2 - spec.shadowOffsetPx).coerceAtLeast(1)
        return SceneRectPx(left, top, left + w, top + h)
    }

    /**
     * J2 video UVs in triangle-strip order: full texture for FIT, the
     * shared cover-crop mapped to 0..1 UVs for FILL. A null crop is
     * logic-impossible (same inputs measured); fall back to full
     * loudly like the image path.
     */
    private fun videoUvFor(spec: SceneOverlaySpec, dims: SceneSizePx, content: SceneRectPx): FloatArray {
        if (spec.scaleMode != SceneScaleMode.FILL) return QUAD_TEX_COORDS
        val crop = SceneImageLayer.fillCrop(dims.widthPx, dims.heightPx, content.width, content.height)
        if (crop == null) {
            Log.w(TAG, "overlay video fill crop failed, drawing full: ${spec.id}")
            return QUAD_TEX_COORDS
        }
        val u0 = crop.left.toFloat() / dims.widthPx
        val u1 = crop.right.toFloat() / dims.widthPx
        val v0 = crop.top.toFloat() / dims.heightPx
        val v1 = crop.bottom.toFloat() / dims.heightPx
        return floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1)
    }

    /** J2 video program: OES sampler + ST matrix + alpha. Built lazily on first video slot. */
    private fun buildVideoProgramLocked() {
        videoProgram = buildProgramFor(VERTEX_SHADER.trimIndent(), VIDEO_FRAGMENT_SHADER.trimIndent())
        videoPositionHandle = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        videoCoordHandle = GLES20.glGetAttribLocation(videoProgram, "aTextureCoord")
        videoMvpHandle = GLES20.glGetUniformLocation(videoProgram, "uMVPMatrix")
        videoStMatrixHandle = GLES20.glGetUniformLocation(videoProgram, "uSTMatrix")
        videoSamplerHandle = GLES20.glGetUniformLocation(videoProgram, "uSampler")
        videoAlphaHandle = GLES20.glGetUniformLocation(videoProgram, "uAlpha")
    }

    /**
     * J2 video slot draw (GL thread): style quad first (always — the
     * underlay is static), then the OES video quad at the content
     * inset when a frame has latched. Before the first frame the slot
     * is style-only (video fades in; no poster in-stream — honest
     * motion-or-nothing). A latch failure fails the slot loudly and
     * skips the video quad; the style quad already drew and the
     * stream continues.
     */
    private fun drawVideoSlotLocked(slot: OverlaySlot) {
        drawOverlaySlotLocked(slot)
        val surfaceTexture = slot.videoSt ?: return
        if (slot.videoPendingFrame) {
            slot.videoPendingFrame = false
            try {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(slot.videoStMatrix)
                slot.videoHasFrame = true
            } catch (e: RuntimeException) {
                Log.e(TAG, "video latch failed: ${slot.spec?.id}", e)
                failVideoSlotLocked(slot, "latch failed")
                return
            }
        }
        if (!slot.videoHasFrame) return
        val ndc = slot.videoNdcBuffer
        val uv = slot.videoUvBuffer
        if (ndc == null || uv == null || videoProgram == 0 || slot.videoTextureId == 0) {
            throw RuntimeException("video slot state missing")
        }
        GLES20.glUseProgram(videoProgram)
        ndc.position(0)
        GLES20.glVertexAttribPointer(videoPositionHandle, 2, GLES20.GL_FLOAT, false, 0, ndc)
        GLES20.glEnableVertexAttribArray(videoPositionHandle)
        uv.position(0)
        GLES20.glVertexAttribPointer(videoCoordHandle, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(videoCoordHandle)
        GLES20.glUniformMatrix4fv(videoMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(videoStMatrixHandle, 1, false, slot.videoStMatrix, 0)
        GLES20.glUniform1f(videoAlphaHandle, slot.alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, slot.videoTextureId)
        GLES20.glUniform1i(videoSamplerHandle, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(videoPositionHandle)
        GLES20.glDisableVertexAttribArray(videoCoordHandle)
        checkGl("video overlay draw")
    }

    /**
     * J2 runtime video failure (GL thread): marks the slot FAILED and
     * drops its decoder (async — no GL block). The ST survives for
     * style-only rendering. Never throws.
     */
    private fun failVideoSlotLocked(slot: OverlaySlot, reason: String) {
        try {
            slot.videoController.onError(reason)
        } catch (_: Exception) {
        }
        val player = slot.videoPlayer
        slot.videoPlayer = null
        slot.videoPendingFrame = false
        slot.videoHasFrame = false
        if (player != null) {
            try {
                player.releaseAsync()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Draws one static overlay quad over the passthrough frame (GL thread).
     * Blend is enabled for this draw only; per-draw state (program,
     * buffers, texture unit) is fully re-specified, so the passthrough
     * draw and sibling slots are unaffected.
     */
    private fun drawOverlaySlotLocked(slot: OverlaySlot) {
        val quad = slot.quadBuffer
        val texCoords = texCoordBuffer
        if (quad == null || texCoords == null || overlayProgram == 0 || slot.textureId == 0) {
            throw RuntimeException("overlay slot state missing")
        }
        GLES20.glUseProgram(overlayProgram)
        quad.position(0)
        GLES20.glVertexAttribPointer(overlayPositionHandle, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(overlayPositionHandle)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(overlayCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(overlayCoordHandle)
        GLES20.glUniform1f(uAlphaHandle, slot.alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.textureId)
        GLES20.glUniform1i(uSamplerHandle, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(overlayPositionHandle)
        GLES20.glDisableVertexAttribArray(overlayCoordHandle)
        checkGl("overlay draw")
    }

    private fun buildProgramFor(vertexSource: String, fragmentSource: String): Int {
        val vertex = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val programId = GLES20.glCreateProgram()
        checkGl("glCreateProgram")
        if (programId == 0) throw RuntimeException("glCreateProgram failed")
        GLES20.glAttachShader(programId, vertex)
        GLES20.glAttachShader(programId, fragment)
        GLES20.glLinkProgram(programId)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            throw RuntimeException("Could not link compositor program: $log")
        }
        return programId
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        checkGl("glCreateShader type=$type")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == GLES20.GL_FALSE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile compositor shader $type: $log")
        }
        return shader
    }

    private fun checkEgl(op: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) throw RuntimeException("$op failed. EGL error: $error")
    }

    private fun checkGl(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) throw RuntimeException("$op failed. GL error: $error")
    }

    private fun floatBufferOf(coords: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(coords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply {
                put(coords)
                position(0)
            }

    companion object {
        private const val TAG = "MGS-SceneGL"
        private const val GL_THREAD_NAME = "MGS-SceneGL"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
        )
        private val QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        // Textbook OES passthrough pair (mirrors the CameraRender approach:
        // identity MVP, ST transform matrix, external sampler). Device-gated:
        // shader compile is proven on the S25, and failures throw loudly.
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            void main() {
              gl_Position = uMVPMatrix * aPosition;
              vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uSampler;
            void main() {
              gl_FragColor = texture2D(uSampler, vTextureCoord);
            }
        """
        private const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uSampler;
            uniform float uAlpha;
            void main() {
              vec4 c = texture2D(uSampler, vTextureCoord);
              gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """
        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
              gl_Position = aPosition;
              vTextureCoord = aTextureCoord;
            }
        """
        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D uSampler;
            uniform float uAlpha;
            void main() {
              vec4 c = texture2D(uSampler, vTextureCoord);
              gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """
    }
}
