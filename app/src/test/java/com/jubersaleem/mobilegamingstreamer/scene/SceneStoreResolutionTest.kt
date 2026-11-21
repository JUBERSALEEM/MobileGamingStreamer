package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral tests for the scene-store decision core: load resolution
 * (seeding, corrupt-entry repair, active-id fallback, rewrite flags)
 * and save resolution (never-empty store, active validation). These pin
 * the exact rules [ScenePrefsStore] executes around its Android glue,
 * which the JVM suite cannot instantiate.
 */
class SceneStoreResolutionTest {

    private fun layer(
        id: String = "text",
        text: String = "MGS"
    ) = StreamSceneLayer(id, text, SceneOverlayAnchor.TOP_LEFT, 1, 27, 1920, 1f, true)

    private fun scene(
        id: String = "s1",
        name: String = "Starter"
    ) = StreamScene(id, name, listOf(layer()))

    // ---- Load: seeding ----

    @Test
    fun nullStoreSeedsDefaultAndRewrites() {
        val resolved = SceneConfigCodec.resolveStoredScenes(null, null)
        assertEquals(listOf(DEFAULT_SCENE_ID), resolved.scenes.map { it.id })
        assertEquals(DEFAULT_SCENE_ID, resolved.activeSceneId)
        assertTrue(resolved.rewriteScenes)
        assertTrue(resolved.rewriteActive)
    }

    @Test
    fun blankStoreSeedsDefaultAndRewrites() {
        val resolved = SceneConfigCodec.resolveStoredScenes("   ", "s1")
        assertEquals(listOf(DEFAULT_SCENE_ID), resolved.scenes.map { it.id })
        assertEquals(DEFAULT_SCENE_ID, resolved.activeSceneId)
        assertTrue(resolved.rewriteScenes)
    }

    // ---- Load: valid store is stable (no rewrite churn) ----

    @Test
    fun validStoreLoadsWithoutRewrite() {
        val scenes = listOf(scene(id = "s1"), scene(id = "s2", name = "Second"))
        val stored = SceneConfigCodec.encodeStore(scenes)
        val resolved = SceneConfigCodec.resolveStoredScenes(stored, "s2")
        assertEquals(scenes, resolved.scenes)
        assertEquals("s2", resolved.activeSceneId)
        assertEquals(stored, resolved.repairedEncoding)
        assertTrue(!resolved.rewriteScenes)
        assertTrue(!resolved.rewriteActive)
    }

    // ---- Load: repair ----

    @Test
    fun corruptBlocksDroppedAndStoreRewritten() {
        val good = SceneConfigCodec.encodeScene(scene(id = "s1"))
        val stored = listOf("corrupt-block", good, "v1\nscene:\tblank").joinToString("\n===\n")
        val resolved = SceneConfigCodec.resolveStoredScenes(stored, "s1")
        assertEquals(listOf("s1"), resolved.scenes.map { it.id })
        assertEquals("s1", resolved.activeSceneId)
        assertTrue(resolved.rewriteScenes)
        // The repaired encoding is a stable fixed point: reloading it
        // rewrites nothing.
        val reloaded = SceneConfigCodec.resolveStoredScenes(resolved.repairedEncoding, "s1")
        assertTrue(!reloaded.rewriteScenes)
        assertTrue(!reloaded.rewriteActive)
    }

    @Test
    fun duplicateSceneIdsCollapsedKeepFirst() {
        val first = SceneConfigCodec.encodeScene(scene(id = "s1", name = "First"))
        val second = SceneConfigCodec.encodeScene(scene(id = "s1", name = "Second"))
        val resolved = SceneConfigCodec.resolveStoredScenes("$first\n===\n$second", "s1")
        assertEquals(listOf("First"), resolved.scenes.map { it.name })
        assertTrue(resolved.rewriteScenes)
    }

    @Test
    fun cosmeticNameRepairTriggersRewrite() {
        val resolved = SceneConfigCodec.resolveStoredScenes("v1\nscene:s1\t", "s1")
        assertEquals(REPAIRED_SCENE_NAME, resolved.scenes.single().name)
        assertTrue(resolved.rewriteScenes)
    }

    // ---- Load: active-id fallback ----

    @Test
    fun unknownActiveFallsBackToFirstSceneOnly() {
        val stored = SceneConfigCodec.encodeStore(listOf(scene(id = "s1"), scene(id = "s2")))
        val resolved = SceneConfigCodec.resolveStoredScenes(stored, "ghost")
        assertEquals("s1", resolved.activeSceneId)
        assertTrue(!resolved.rewriteScenes)
        assertTrue(resolved.rewriteActive)
    }

    @Test
    fun nullActiveFallsBackToFirst() {
        val stored = SceneConfigCodec.encodeStore(listOf(scene(id = "s1")))
        val resolved = SceneConfigCodec.resolveStoredScenes(stored, null)
        assertEquals("s1", resolved.activeSceneId)
        assertTrue(resolved.rewriteActive)
    }

    // ---- Save ----

    @Test
    fun saveEmptySeedsDefault() {
        val resolved = SceneConfigCodec.resolveSceneSave(emptyList(), "anything")
        assertEquals(listOf(DEFAULT_SCENE_ID), resolved.scenes.map { it.id })
        assertEquals(DEFAULT_SCENE_ID, resolved.activeSceneId)
        assertEquals(
            SceneConfigCodec.encodeStore(resolved.scenes),
            resolved.encoding
        )
    }

    @Test
    fun saveFixesUnknownActiveAndKeepsValidUnchanged() {
        val scenes = listOf(scene(id = "s1"), scene(id = "s2"))
        val fixed = SceneConfigCodec.resolveSceneSave(scenes, "ghost")
        assertEquals(scenes, fixed.scenes)
        assertEquals("s1", fixed.activeSceneId)
        val kept = SceneConfigCodec.resolveSceneSave(scenes, "s2")
        assertEquals(scenes, kept.scenes)
        assertEquals("s2", kept.activeSceneId)
        assertEquals(SceneConfigCodec.encodeStore(scenes), kept.encoding)
    }
}
