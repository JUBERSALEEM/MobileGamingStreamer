package com.jubersaleem.mobilegamingstreamer.capture

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

    private fun service(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/capture/RtmpBroadcastService.kt")

    private fun activity(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/MainActivity.kt")

    private fun manifest(): String = source("AndroidManifest.xml")

    private fun sceneSource(): String =
        source("java/com/jubersaleem/mobilegamingstreamer/scene/SceneSource.kt")

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
