package com.jubersaleem.mobilegamingstreamer.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated regression tests for streaming-lifecycle OWNERSHIP properties
 * that JVM unit tests cannot reach by instantiation (Activity/Service are
 * Android classes and this repo runs no instrumentation suite in CI).
 *
 * Each test asserts a structural property of the shipped sources: if a
 * refactor reintroduces an Activity-coupled stop, task-removal stop, or
 * session-ordering violation, the suite fails instead of silently
 * regressing. Sources resolve under both runners: Gradle unit tests run
 * with the module dir as working directory (`src/main/...`), the local
 * harness runs from the repo root (`app/src/main/...`).
 */
class StreamingLifecycleStructureTest {

    private fun source(relativeMainPath: String): String {
        val candidates = listOf(
            File("src/main/$relativeMainPath"),
            File("app/src/main/$relativeMainPath")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "lifecycle structure test cannot find $relativeMainPath " +
                    "(tried ${candidates.joinToString()}); never silently pass"
            )
        return file.readText()
    }

    /**
     * Module-level files (Gradle runs with the module dir as CWD, the
     * local harness from the repo root). Never silently passes.
     */
    private fun moduleFile(name: String): String {
        val candidates = listOf(File("app/$name"), File(name))
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "lifecycle structure test cannot find $name " +
                    "(tried ${candidates.joinToString()}); never silently pass"
            )
        return file.readText()
    }

    private fun service(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/capture/RtmpBroadcastService.kt")

    private fun activity(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/MainActivity.kt")

    private fun manifest(): String = source("AndroidManifest.xml")

    private fun sceneSource(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/scene/SceneSource.kt")

    private fun sceneEditor(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/SceneEditorPage.kt")

    private fun compositorGl(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/scene/SceneCompositorGL.kt")

    private fun imageLoader(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/scene/SceneImageLoader.kt")

    /** Body of the first function whose declaration contains [marker]. */
    private fun functionBody(src: String, marker: String): String {
        val decl = src.indexOf(marker)
        assertTrue("missing $marker", decl >= 0)
        val open = src.indexOf('{', decl)
        assertTrue("malformed $marker", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i += 1
        }
        throw AssertionError("unbalanced braces in $marker")
    }

    @Test
    fun taskRemovalCannotStopService() {
        // No onTaskRemoved override: the default (keep running) applies, and
        // the manifest must not opt into stopWithTask.
        assertFalse(service().contains("fun onTaskRemoved"))
        val name = "android:name=\".capture.RtmpBroadcastService\""
        val manifestText = manifest()
        val at = manifestText.indexOf(name)
        assertTrue("service missing from manifest", at >= 0)
        val open = manifestText.lastIndexOf("<service", at)
        val close = manifestText.indexOf("/>", at)
        assertTrue("malformed service block", open >= 0 && close > at)
        val block = manifestText.substring(open, close)
        assertFalse(block.contains("stopWithTask"))
        assertTrue(block.contains("foregroundServiceType"))
        assertTrue(block.contains("mediaProjection"))
    }

    @Test
    fun serviceNeverAutoRestarts() {
        assertTrue(functionBody(service(), "override fun onStartCommand").contains("START_NOT_STICKY"))
    }

    @Test
    fun serviceHasNoBindLifetimeCoupling() {
        val decl = "override fun onBind(intent: Intent?): IBinder? = null"
        assertTrue(service().contains(decl))
    }

    @Test
    fun activityStopNeverStopsStream() {
        // No onDestroy at all: destruction cannot reach the session.
        assertFalse(activity().contains("override fun onDestroy"))
        val onStop = functionBody(activity(), "override fun onStop()")
        assertTrue(onStop.contains("unregisterReceiver"))
        assertFalse(onStop.contains("ACTION_STOP"))
        assertFalse(onStop.contains("stopBroadcast"))
        assertFalse(onStop.contains("stopService"))
        assertFalse(onStop.contains("STOP_RTMP"))
    }

    @Test
    fun returningActivityReconnectsInsteadOfDuplicating() {
        // onStart restores persisted session state; it never starts a stream.
        val onStart = functionBody(activity(), "override fun onStart()")
        assertTrue(onStart.contains("restoreBroadcastState"))
        assertFalse(onStart.contains("ACTION_START"))
        assertFalse(onStart.contains("startForegroundService"))
        assertFalse(onStart.contains("START_RTMP"))
    }

    @Test
    fun duplicateStartInvalidatesBeforeReleasing() {
        // beginSession (token invalidation) must precede the previous
        // stream's release, whose async onDisconnect is then stale by
        // construction instead of killing the new session.
        val body = functionBody(service(), "private fun startBroadcast(intent: Intent)")
        val invalidate = body.indexOf("beginSession()")
        val release = body.indexOf("previous.release()")
        assertTrue(invalidate >= 0 && release > invalidate)
    }

    @Test
    fun stopReleasesStreamAndProjection() {
        val body = functionBody(service(), "private fun stopBroadcast(")
        assertTrue(body.contains("active?.release()"))
        assertTrue(body.contains("releaseProjection()"))
    }

    @Test
    fun streamRunsAsForegroundService() {
        val body = functionBody(service(), "private fun startBroadcast(intent: Intent)")
        assertTrue(body.contains("startForeground("))
    }

    @Test
    fun explicitStopPathExists() {
        assertTrue(service().contains("ACTION_STOP -> stopBroadcast()"))
        assertTrue(service().contains("Stop broadcast"))
    }

    @Test
    fun sceneSeamStaysDefaultOff() {
        assertTrue(service().contains("getBooleanExtra(EXTRA_SCENE_SOURCE, false)"))
        assertTrue(activity().contains("EXTRA_SCENE_SOURCE, false"))
    }

    @Test
    fun sceneText2SeamForwardedBothEnds() {
        // H4 second overlay: null-absent by default, forwarded activity ->
        // service -> SceneSource like the H3 text seam.
        assertTrue(service().contains("getStringExtra(EXTRA_SCENE_TEXT2)"))
        assertTrue(activity().contains("EXTRA_SCENE_TEXT2"))
        assertTrue(sceneSource().contains("subtextContent"))
    }

    @Test
    fun fallbackRetryPreservesSceneExtras() {
        // The one-shot fallback retries with a COPY of the start intent, so
        // scene_text/scene_text2 (and all other extras) survive; only the
        // source selection flips. A rebuilt-from-scratch intent would drop
        // them and silently change the session.
        val body = functionBody(service(), "private fun startBroadcast(intent: Intent)")
        assertTrue(body.contains("startBroadcast(Intent(intent)"))
        assertTrue(body.contains("EXTRA_SCENE_SOURCE, false"))
        assertTrue(body.contains("EXTRA_SCENE_FALLBACK_DONE, true"))
    }

    @Test
    fun sceneConfigDeliveryToService() {
        // H5 editor -> service contract: the GO LIVE intent carries the
        // encoded scene; the service parses it through the pure codec and
        // H4 validation (never around it) into the SceneSource layer list.
        assertTrue(activity().contains("EXTRA_SCENE_CONFIG"))
        assertTrue(service().contains("getStringExtra(EXTRA_SCENE_CONFIG)"))
        assertTrue(service().contains("SceneConfigCodec.streamableSpecs"))
        assertTrue(service().contains("sceneConfig = intent.getStringExtra(EXTRA_SCENE_CONFIG)"))
        assertTrue(sceneSource().contains("SceneConfigCodec.streamableSpecs(sceneConfig)"))
    }

    @Test
    fun sceneActivationKeepsDefaultWithoutValidScene() {
        // SceneSource activates on the explicit ADB extra (legacy) or a
        // non-empty validated spec list — never on a present-but-invalid
        // config, which falls through to the ScreenSource default.
        val body = functionBody(service(), "private fun startBroadcast(intent: Intent)")
        assertTrue(body.contains("explicitSceneExtra || sceneSpecs.isNotEmpty()"))
        assertTrue(body.contains("scene invalid, defaulting"))
        assertTrue(body.contains("removeExtra(EXTRA_SCENE_CONFIG)"))
        assertTrue(body.contains("ScreenSource(this, mediaProjection)"))
    }

    @Test
    fun layerSlidersClampStoredValuesToUiRange() {
        // Stored layers may carry codec-valid values outside the narrower
        // UI slider ranges (foreign/corrupt prefs); every slider input
        // coerces. Structural pin: the JVM suite cannot render Compose.
        val editor = sceneEditor()
        assertTrue(editor.contains("layer.marginPx.toFloat().coerceIn(0f, SCENE_LAYER_MAX_MARGIN_UI_PX.toFloat())"))
        assertTrue(editor.contains("layer.maxWidthPx.toFloat().coerceIn("))
        assertTrue(editor.contains("layer.alpha.coerceIn(0f, 1f)"))
    }

    @Test
    fun imageLayersDecodeOnceAtBuildNeverPerFrame() {
        // H6: the provider decodes during overlay build only; the
        // per-frame draw path performs no decode, scale, or load.
        val gl = compositorGl()
        assertTrue(gl.contains("imageProvider?.invoke(uri)"))
        assertTrue(gl.contains("measureImageLocked"))
        val draw = functionBody(gl, "private fun drawOverlaySlotLocked(slot: OverlaySlot)")
        assertTrue(!draw.contains("decode"))
        assertTrue(!draw.contains("imageProvider"))
        assertTrue(!draw.contains("createScaledBitmap"))
        assertTrue(!draw.contains("SceneImageLoader"))
    }

    @Test
    fun sceneSourceWiresNonThrowingImageProvider() {
        val src = sceneSource()
        assertTrue(src.contains("SceneImageLoader.decodeBitmap(appContext.contentResolver, uri)"))
        assertTrue(src.contains("val imageProvider: (String) -> Bitmap?"))
    }

    @Test
    fun imageLoaderNeverThrowsAndDownsamples() {
        val loader = imageLoader()
        assertTrue(loader.contains("catch (_: Throwable)"))
        assertTrue(loader.contains("inSampleSize"))
        assertTrue(loader.contains("MAX_IMAGE_BITMAP_PX"))
    }

    @Test
    fun styledCompositionSharesPaddingRules() {
        // H7: renderer and preview measure styled boxes through the same
        // pure function and draw the same fixed order.
        assertTrue(compositorGl().contains("SceneLayerStyle.styledSize"))
        assertTrue(sceneEditor().contains("SceneLayerStyle.styledSize"))
        assertTrue(compositorGl().contains("drawStyleUnderlayLocked"))
    }

    @Test
    fun styleWidthsCoercedAtEntry() {
        val act = activity()
        assertTrue(act.contains("width.coerceIn(0, MAX_STYLE_BORDER_PX)"))
        assertTrue(act.contains("radius.coerceIn(0, MAX_STYLE_RADIUS_PX)"))
        assertTrue(act.contains("offset.coerceIn(0, MAX_STYLE_SHADOW_PX)"))
    }

    @Test
    fun liveSwitchRetainsAndDispatchesVideoSource() {
        // H8: the service retains the running video source beside the
        // stream (set together, nulled at all four display-null sites),
        // plans through the pure matrix, and swaps via the library.
        val svc = service()
        assertTrue(svc.contains("activeVideoSource = videoSource"))
        assertEquals(4, svc.split("activeVideoSource = null").size - 1)
        assertTrue(svc.contains("ACTION_UPDATE_SCENE"))
        assertTrue(svc.contains("SceneSwitchPlanner.resolve"))
        val swap = functionBody(svc, "fun swapVideoSource")
        assertTrue(swap.contains("changeVideoSource(replacement)"))
        assertTrue(swap.contains("activeVideoSource = replacement"))
        assertTrue(swap.contains("replacement.release()"))
    }

    @Test
    fun sceneSourceUpdateRebuildsWithRollback() {
        // H8: only live config-started sources update in place, through
        // the GL rebuild; failure best-effort restores the last stack.
        // J4: switches route through the transition entry (CUT default
        // = the historical plain rebuild); rollback stays plain.
        val src = sceneSource()
        assertTrue(src.contains("fun updateScene"))
        assertTrue(src.contains("controller.state != SceneSourceState.STARTED"))
        assertTrue(src.contains("transition: SceneTransition = SceneTransition.DEFAULT"))
        assertTrue(src.contains("gl.transitionToScene(specs, transition)"))
        assertTrue(src.contains("gl.rebuildOverlays(previous)"))
    }

    @Test
    fun rebuildReleasesBeforeBuilding() {
        // H8: rebuild = release + build on the GL thread; the public
        // entry maps build throws to false, never outward.
        val gl = compositorGl()
        val core = functionBody(gl, "fun rebuildOverlaysLocked")
        val release = core.indexOf("releaseOverlaySlotsLocked()")
        val build = core.indexOf("buildSlotsLocked(")
        assertTrue(release >= 0 && build > release)
        val entry = functionBody(gl, "fun rebuildOverlays(")
        assertTrue(entry.contains("rebuildOverlaysLocked(specs)"))
        assertTrue(entry.contains("catch (_: Throwable)"))
    }

    @Test
    fun imageLayersSurviveBuildFilter() {
        // H6 GL init dropped every image layer through a text.isBlank()
        // gate (image specs carry empty text by construction); the H8
        // shared build filters TEXT blankness only.
        val gl = compositorGl()
        assertFalse(gl.contains("spec.text.isBlank()) continue"))
        val build = functionBody(gl, "fun buildSlotsLocked")
        assertTrue(build.contains("it.kind != SceneLayerKind.TEXT || it.text.isNotBlank()"))
    }

    @Test
    fun useWhileStreamingPushesSceneUpdate() {
        // H8: USE persists and, while streaming, pushes the fresh active
        // encoding (or none) to the service update action.
        val body = functionBody(activity(), "onActiveScene")
        assertTrue(body.contains("requestSceneUpdate"))
        assertTrue(body.contains("broadcastActive || broadcastStarting"))
        assertTrue(body.contains("activeSceneEncoding(scenes, activeSceneId)"))
    }

    @Test
    fun dynamicTickerRefreshesWidgetsInPlace() {
        // H9: one GL-thread ticker re-renders changed widgets into the
        // live textures (erase + re-upload, bitmap reused); release
        // cancels, build re-arms — exactly one ticker per live stack.
        val gl = compositorGl()
        assertTrue(gl.contains("postDelayed(dynamicTickRunnable"))
        assertTrue(gl.contains("removeCallbacks(dynamicTickRunnable)"))
        val refresh = functionBody(gl, "fun refreshTextSlotLocked")
        assertTrue(refresh.contains("slot.lastContent == content"))
        assertTrue(refresh.contains("eraseColor"))
        assertTrue(refresh.contains("texImage2D"))
        assertTrue(refresh.contains("slot.lastContent = content"))
    }

    @Test
    fun widgetTimeContractShared() {
        // H9: renderer and preview format/measure widgets through the
        // same pure contract (same minute, same boxes).
        assertTrue(compositorGl().contains("SceneClockWidgets.formatWallClock"))
        assertTrue(sceneEditor().contains("SceneClockWidgets.formatWallClock"))
        assertTrue(compositorGl().contains("SceneClockWidgets.WALL_CLOCK_WORST_CASE"))
        assertTrue(sceneEditor().contains("SceneClockWidgets.WALL_CLOCK_WORST_CASE"))
    }

    @Test
    fun timerEpochThreadsFromSession() {
        // H9: the service session start (monotonic) is the TIMER epoch
        // end to end, so swaps preserve true session uptime.
        assertTrue(service().contains("sessionStartElapsedMs = SystemClock.elapsedRealtime()"))
        assertTrue(sceneSource().contains("timerEpochMs"))
        assertTrue(compositorGl().contains("sessionStartElapsedMs"))
    }

    @Test
    fun widgetAddActionsThreaded() {
        // H9: ADD CLOCK / ADD TIMER reach dedicated add fns.
        val act = activity()
        assertTrue(act.contains("fun addClockLayer"))
        assertTrue(act.contains("fun addTimerLayer"))
        assertTrue(sceneEditor().contains("ADD CLOCK"))
        assertTrue(sceneEditor().contains("ADD TIMER"))
    }

    @Test
    fun previewTicksHonestly() {
        // H9: the preview recomposes each second only with a dynamic
        // layer, formatting through the shared contract.
        val preview = functionBody(sceneEditor(), "fun ScenePreviewCanvas")
        assertTrue(preview.contains("hasDynamicWidgets"))
        assertTrue(preview.contains("LaunchedEffect"))
        assertTrue(preview.contains("delay(1_000L)"))
        assertTrue(preview.contains("SceneClockWidgets.formatElapsed"))
    }

    @Test
    fun failStartReleasesInputEndpoint() {
        // H10: init failure after input setup frees the input ST before
        // EGL (the quitting thread strands anything later); the normal
        // release path shares the same locked step.
        val gl = compositorGl()
        val fail = functionBody(gl, "fun failStart")
        val input = fail.indexOf("releaseInputLocked()")
        val egl = fail.indexOf("releaseEglLocked()")
        assertTrue(input >= 0 && egl > input)
        assertTrue(functionBody(gl, "fun releaseInput(").contains("releaseInputLocked()"))
    }

    @Test
    fun framePathAllocatesNothingHeavy() {
        // H10: per-frame code creates no bitmaps/canvases/paints/rects/
        // buffers and uploads no textures (uploads happen at build and
        // on the 1s widget tick only). The tiny frame metadata + the
        // controller handle are the accepted per-frame allocs.
        val gl = compositorGl()
        for (marker in listOf("fun onFrameAvailable(", "fun drawFrameLocked(", "fun drawOverlaySlotLocked(")) {
            val body = functionBody(gl, marker)
            assertFalse(body.contains("Bitmap."))
            assertFalse(body.contains("Canvas("))
            assertFalse(body.contains("Paint("))
            assertFalse(body.contains("Rect("))
            assertFalse(body.contains("floatBufferOf("))
            assertFalse(body.contains("texImage2D"))
        }
    }

    @Test
    fun previewThumbnailsNeverReloadPerTick() {
        // H10: thumbnail bitmaps reload only when the measured set
        // changes — the 1s widget tick recomposes glyphs, never bytes.
        assertTrue(sceneEditor().contains("val imageById = remember(measured)"))
    }

    @Test
    fun compatFloorStaysAt29() {
        // H10: minSdk 29 is the floor java.time (26+) and
        // ContentResolver.loadThumbnail (29) rely on.
        assertTrue(moduleFile("build.gradle.kts").contains("minSdk = 29"))
    }

    @Test
    fun apiGuardsPresent() {
        // H10: the two SDK-33-gated call sites keep their guards
        // (exported-receiver flag, typed parcelable overload).
        assertEquals(2, service().split("SDK_INT >= 33").size - 1)
        assertTrue(service().contains("RECEIVER_NOT_EXPORTED"))
    }

    @Test
    fun sceneOrientationSelectorThreaded() {
        // Phase I (UI-only; behavior is covered by pure tests): the
        // canvas selector, 9:16 portrait preview, and profile-driven
        // reference dims are wired activity -> shell -> editor.
        val editor = sceneEditor()
        assertTrue(editor.contains("title = \"Canvas\""))
        assertTrue(editor.contains("9f / 16f"))
        assertTrue(editor.contains("SceneCanvasProfile.referenceCanvas"))
        assertTrue(editor.contains("onSceneOrientation"))
        assertTrue(activity().contains("onSceneOrientation"))
    }

    @Test
    fun serviceLogsSceneOrientation() {
        // Phase I: start path logs the design orientation beside the
        // layer count (sanitized: enum + counts only).
        assertTrue(service().contains("decodedOrientation"))
    }

    @Test
    fun projectionRevocationFlipsGuardWithoutStopping() {
        // Revocation flips the frame guard; teardown authority stays with
        // the service/library stop path — the source never stops anything.
        val src = sceneSource()
        assertTrue(src.contains("registerCallback"))
        assertTrue(src.contains("onProjectionRevoked"))
        assertFalse(src.contains("stopSelf"))
        assertFalse(src.contains("ACTION_STOP"))
        // Scoped to the revocation callback itself (file-level text also
        // matches design comments): guard flip only, no teardown trigger.
        val onStop = functionBody(src, "override fun onStop()")
        assertTrue(onStop.contains("onProjectionRevoked"))
        assertFalse(onStop.contains("requestRelease"))
        assertFalse(onStop.contains("performTeardown"))
        assertFalse(onStop.contains("teardown()"))
    }
}
