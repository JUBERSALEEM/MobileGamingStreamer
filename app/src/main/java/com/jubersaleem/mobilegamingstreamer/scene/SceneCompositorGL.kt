package com.jubersaleem.mobilegamingstreamer.scene

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
 * passthrough draw → presentation timestamp → swap into the library ST.
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

internal class SceneCompositorGL(
    private val controller: SceneCompositorController,
    private val outputSurface: Surface,
    private val layout: SceneSourceLayout
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
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

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
        runOnGlThread(GL_TEARDOWN_TIMEOUT_MS) { releaseEglLocked() }
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
        runOnGlThread(GL_TEARDOWN_TIMEOUT_MS) {
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
        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "input Surface release failed", e)
        }
        inputSurface = null
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
        } catch (error: RuntimeException) {
            failFrame("compositor input latch failed", error)
            return
        }
        val handle = controller.onFrameArrival(metadata)
        if (handle == null) return // Counted drop/reject: skip the draw.
        try {
            drawFrameLocked(surfaceTexture)
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
        glReady = true
        Log.i(TAG, "compositor ready: input=${layout.displayWidth}x${layout.displayHeight} " +
            "output=${layout.outputWidth}x${layout.outputHeight}")
        return surface
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

    private fun releaseEglLocked() {
        glReady = false
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
        val vertex = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER.trimIndent())
        val fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER.trimIndent())
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
    }
}
