package com.jubersaleem.mobilegamingstreamer.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource

/**
 * H2 Android scene source: `VideoSource` seam implementation that feeds the
 * compositor into the RootEncoder pipeline. Device-gated (no JVM coverage):
 * VD, ST, and EGL cannot execute on the JVM. Verified by Android
 * compilation, lint, CI packaging, and the S25 passthrough gate.
 *
 * This class is a thin shell: every decision (may we start? may this frame
 * draw? in what order do resources die?) comes from [SceneCompositorController]
 * (unit-tested); GL work lives in [SceneCompositorGL]. It mirrors
 * `ScreenSource` where the seam demands parity:
 * - `create()` enforces the even-dimension rule with the identical exception.
 * - `start()` is idempotent when already started; VD sized/created the same
 *   way; own `MediaProjection.Callback` registered on the source thread.
 * - `isRunning()` = VD != null (ground truth of "producing").
 * - `getOrientationConfig()` uses the identical portrait formula.
 *
 * Differences from `ScreenSource` (all deliberate, all documented):
 * - Owns a [SceneCompositorGL] (separate EGL context + input ST) instead of
 *   pointing the VD directly at the library ST.
 * - Does NOT touch the library's static `MediaProjectionHandler` holder
 *   (only `ScreenSource` reads it); the ctor-held projection avoids
 *   clobbering global state.
 * - Own projection callback flips the REVOKED guard only — teardown
 *   authority stays with the service/library calls (`stop()`/`release()`).
 * - `stop()` always runs teardown (even from REVOKED, where a live VD may
 *   exist) while preserving the recorded state; guards keep it exactly-once.
 *
 * Ownership: VD, output `Surface(library ST)`, projection callback. The
 * compositor owns input ST/Surface, EGL, and the GL thread. The
 * MediaProjection session stays service-owned and is never stopped here.
 */
class SceneSource(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val textContent: String? = null,
    private val subtextContent: String? = null,
    private val sceneConfig: String? = null,
    private val sessionStartElapsedMs: Long = 0L
) : VideoSource() {

    // Mirrors ScreenSource: dpi snapshot from context. Only the application
    // context is retained (process-lifetime, leak-safe) for H6 image
    // decoding; the caller's context is never kept.
    private val densityDpi = context.resources.displayMetrics.densityDpi
    private val appContext = context.applicationContext
    private val controller = SceneCompositorController()
    private var layout: SceneSourceLayout? = null
    private var compositor: SceneCompositorGL? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputSurface: Surface? = null
    private var projectionCallback: MediaProjection.Callback? = null
    // H8 live-switch state: only config-started sources update in place.
    // `currentSceneConfig` is the last applied encoding (service compares
    // through the planner); `lastSpecs` is the last good overlay list for
    // best-effort rollback.
    var currentSceneConfig: String? = null
        private set
    /** J2: true while the running stack holds paused video (service badge). */
    val isVideoPaused: Boolean
        get() = try {
            compositor?.videosPaused == true
        } catch (_: Exception) {
            false
        }
    private var configStarted = false
    private var lastSpecs: List<SceneOverlaySpec> = emptyList()

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("width and height must be positive")
        }
        layout = SceneSourceLayoutCalculator.layout(width, height, rotation)
            ?: throw IllegalArgumentException("width and height values must be divisible by 2")
        return true
    }

    override fun start(librarySurfaceTexture: SurfaceTexture) {
        this.surfaceTexture = librarySurfaceTexture // Mirror ScreenSource.
        if (controller.state == SceneSourceState.STARTED) return // Mirror idempotence.
        if (!controller.requestStart()) {
            throw IllegalStateException("SceneSource cannot start from ${controller.state}")
        }
        val activeLayout = layout
        if (activeLayout == null) {
            controller.onStartFailure()
            throw IllegalStateException("SceneSource started before create()")
        }
        try {
            val output = Surface(librarySurfaceTexture)
            outputSurface = output
            // H5 scene path: decode the validated editor configuration
            // (service-selected only when non-empty; decode is
            // deterministic so this agrees with the service decision).
            val editorSpecs = SceneConfigCodec.streamableSpecs(sceneConfig)
            // H6 image loading: decode-once provider (GL thread, overlay
            // build only, never per frame). Never throws: a null return
            // drops the layer loudly inside the compositor.
            val imageProvider: (String) -> Bitmap? = { uri ->
                try {
                    SceneImageLoader.decodeBitmap(appContext.contentResolver, uri)
                } catch (_: Throwable) {
                    null
                }
            }
            // H9 TIMER epoch: the service session start when provided
            // (true uptime across swaps); otherwise latched here so a
            // legacy-started source counts from its first frame.
            val timerEpochMs = if (sessionStartElapsedMs > 0L) {
                sessionStartElapsedMs
            } else {
                SystemClock.elapsedRealtime()
            }
            // J2 video: probe-once provider (GL thread, overlay build
            // only) + per-slot decoder factory. Both never throw.
            val videoProbe: (String) -> SceneSizePx? = { uri ->
                try {
                    SceneVideoProbe.probeSize(appContext, uri)
                } catch (_: Throwable) {
                    null
                }
            }
            val videoPlayerFactory: (String) -> SceneVideoPlayer = { tag ->
                SceneVideoPlayer(appContext, tag)
            }
            val gl = SceneCompositorGL(
                controller, output, activeLayout, textContent, subtextContent,
                if (editorSpecs.isEmpty()) null else editorSpecs,
                imageProvider,
                timerEpochMs,
                videoProbe,
                videoPlayerFactory
            )
            compositor = gl
            val input = gl.start() // Latch-inits EGL + input endpoint; throws on failure.
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // Guard flip only: frames stop immediately; teardown
                    // arrives via the service's stopBroadcast → release path.
                    Log.i(TAG, "projection revoked; rejecting scene frames")
                    controller.onProjectionRevoked()
                    // J2: freeze live video now (pre-teardown); the
                    // release path stops decoders authoritatively.
                    try {
                        gl.setVideosPaused(true)
                    } catch (_: Exception) {
                    }
                }
            }
            projectionCallback = callback
            mediaProjection.registerCallback(callback, gl.handlerForCallbacks)
            val display = mediaProjection.createVirtualDisplay(
                TAG,
                activeLayout.displayWidth, activeLayout.displayHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                input, null, gl.handlerForCallbacks
            ) ?: throw IllegalArgumentException("Failed to create scene virtual display")
            virtualDisplay = display
            if (sceneConfig != null && editorSpecs.isNotEmpty()) {
                configStarted = true
                currentSceneConfig = sceneConfig
                lastSpecs = editorSpecs
            }
            // Sanitized: text length only, never content.
            Log.i(TAG, "started: ${activeLayout.displayWidth}x${activeLayout.displayHeight} dpi=$densityDpi" +
                if (textContent.isNullOrBlank()) "" else " text=${textContent.length} chars" +
                if (subtextContent.isNullOrBlank()) "" else " text2=${subtextContent.length} chars" +
                if (sceneConfig.isNullOrBlank()) "" else " sceneLayers=${editorSpecs.size}")
        } catch (error: Throwable) {
            // Sanitized: framework error text only, never URLs or keys.
            Log.e(TAG, "SceneSource start failed (${error.message ?: error.javaClass.simpleName}); torn down", error)
            controller.onStartFailure()
            teardown()
            throw error
        }
    }

    /**
     * H8 in-place scene update: rebuilds overlays inside the running
     * source (capture/encoder/RTMP/audio untouched). Service calls this
     * only for the planner's IN_PLACE action; every gate below also
     * rejects defensively. Returns false (never throws) when this
     * source was not config-started, is not live, carries no live GL
     * endpoint, or the new config has no streamable layer. On rebuild
     * failure the previous stack is best-effort restored; if that also
     * fails the source stays live on clean passthrough — never a
     * half-built stack, never a dead source.
     *
     * J4: [transition] blends from the outgoing scene (CUT default =
     * the historical instant switch). Rollback stays a plain CUT
     * rebuild, which cancels any in-flight blend deterministically.
     */
    fun updateScene(newConfig: String?, transition: SceneTransition = SceneTransition.DEFAULT): Boolean {
        if (!configStarted) return false
        if (controller.state != SceneSourceState.STARTED) return false
        val specs = SceneConfigCodec.streamableSpecs(newConfig)
        if (specs.isEmpty()) return false
        val gl = compositor ?: return false
        if (gl.transitionToScene(specs, transition)) {
            currentSceneConfig = newConfig
            lastSpecs = specs
            Log.i(TAG, "scene updated: ${specs.size} layers")
            return true
        }
        val previous = lastSpecs
        if (previous.isNotEmpty()) gl.rebuildOverlays(previous)
        return false
    }

    override fun stop() {
        controller.requestStop() // State move; teardown below runs regardless.
        teardown()
        Log.i(
            TAG, "stopped: accepted=${controller.acceptedFrames} " +
                "dropped=${controller.droppedFrames} rejected=${controller.rejectedFrames}"
        )
    }

    override fun release() {
        controller.requestRelease() // Total from every state, idempotent.
        teardown() // No-op after stop(); cleans up after release-while-active.
        Log.i(TAG, "released: fullyReleased=${controller.isFullyReleased}")
    }

    override fun isRunning(): Boolean = virtualDisplay != null

    override fun getOrientationConfig(): OrientationConfig {
        return OrientationConfig(
            cameraOrientation = 0,
            isPortrait = SceneSourceLayoutCalculator.isPortraitStream(width, height, rotation),
            forced = OrientationForced.NONE
        )
    }

    /**
     * Controller-ordered, exactly-once teardown. Every action is null-safe
     * and exception-isolated (service-teardown style) so teardown always
     * runs to completion; never throws outward.
     */
    private fun teardown() {
        controller.performTeardown(
            SceneTeardownSteps(
                releaseVirtualDisplay = {
                    try {
                        virtualDisplay?.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "VirtualDisplay release failed", e)
                    }
                    virtualDisplay = null
                },
                releaseOutputSurface = {
                    try {
                        outputSurface?.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "output Surface release failed", e)
                    }
                    outputSurface = null
                },
                releaseInputSurface = {
                    try {
                        compositor?.releaseInput()
                    } catch (e: Exception) {
                        Log.w(TAG, "compositor input release failed", e)
                    }
                },
                releaseEgl = {
                    try {
                        compositor?.releaseEgl()
                    } catch (e: Exception) {
                        Log.w(TAG, "compositor EGL release failed", e)
                    }
                },
                releaseProjectionCallback = {
                    val callback = projectionCallback
                    projectionCallback = null
                    if (callback != null) {
                        try {
                            mediaProjection.unregisterCallback(callback)
                        } catch (e: Exception) {
                            Log.w(TAG, "projection callback unregister failed", e)
                        }
                    }
                },
                quitGlThread = {
                    try {
                        compositor?.quitThread()
                    } catch (e: Exception) {
                        Log.w(TAG, "GL thread quit failed", e)
                    }
                    compositor = null
                }
            )
        )
    }

    companion object {
        private const val TAG = "MGS-SceneSource"
    }
}
