package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K1 behavioral tests: the collection codec, membership rules,
 * fallback chains, load/save resolution (the store decision core),
 * and the global-identity invariants (collections never duplicate,
 * edit, or delete scenes; collection switching never touches the
 * live scene). Pure JVM: the codec is Android-free, and these tests
 * deliberately never import the Activity, shell, or prefs classes.
 */
class SceneCollectionTest {

    private fun scene(id: String, name: String = id): StreamScene =
        StreamScene(id = id, name = name, layers = emptyList())

    private fun sceneSet(vararg ids: String): Set<String> = ids.toSet()

    // --- codec round-trip ---

    @Test fun emptyCollectionListRoundTrips() {
        val encoded = SceneCollectionCodec.encodeCollections(emptyList())
        assertEquals("collections:v1", encoded)
        assertTrue(SceneCollectionCodec.decodeCollections(encoded).isEmpty())
    }

    @Test fun collectionsRoundTripExactly() {
        val input = listOf(
            StreamSceneCollection("collection-1", "Gaming", listOf("a", "b")),
            StreamSceneCollection("collection-2", "Chill", listOf("c"))
        )
        assertEquals(input, SceneCollectionCodec.decodeCollections(SceneCollectionCodec.encodeCollections(input)))
    }

    @Test fun unknownEncodingVersionDecodesToNoCollections() {
        assertTrue(SceneCollectionCodec.decodeCollections("collections:v2\ncollection:a\tb").isEmpty())
    }

    @Test fun nullEncodingDecodesToNoCollections() {
        assertTrue(SceneCollectionCodec.decodeCollections(null).isEmpty())
    }

    @Test fun unknownLinesIgnoredButValidCollectionsSurvive() {
        val encoded = "collections:v1\njunk-line\ncollection:" +
            SceneConfigCodec.escape("collection-1") + "\t" +
            SceneConfigCodec.escape("Gaming") + "\t" +
            SceneConfigCodec.escape("a")
        val decoded = SceneCollectionCodec.decodeCollections(encoded)
        assertEquals(1, decoded.size)
        assertEquals("Gaming", decoded[0].name)
        assertEquals(listOf("a"), decoded[0].sceneIds)
    }

    @Test fun namesAndIdsWithTabsAndBackslashesRoundTrip() {
        val input = listOf(
            StreamSceneCollection("id\\with\ttabs", "na\\me\tv2", listOf("s\\1"))
        )
        assertEquals(input, SceneCollectionCodec.decodeCollections(SceneCollectionCodec.encodeCollections(input)))
    }

    // --- structural repair on decode ---

    @Test fun blankIdsDroppedOnDecode() {
        val encoded = "collections:v1\ncollection:\tName\ta"
        assertTrue(SceneCollectionCodec.decodeCollections(encoded).isEmpty())
    }

    @Test fun reservedAllIdNeverDecodesAsCustom() {
        val encoded = "collections:v1\ncollection:" +
            SceneConfigCodec.escape(ALL_SCENES_COLLECTION_ID) + "\tFake\ta"
        assertTrue(SceneCollectionCodec.decodeCollections(encoded).isEmpty())
    }

    @Test fun duplicateIdsKeepFirstOnDecode() {
        val encoded = "collections:v1\ncollection:c1\tFirst\ta\ncollection:c1\tSecond\tb"
        val decoded = SceneCollectionCodec.decodeCollections(encoded)
        assertEquals(1, decoded.size)
        assertEquals("First", decoded[0].name)
        assertEquals(listOf("a"), decoded[0].sceneIds)
    }

    @Test fun blankNamesRepairedOnDecode() {
        val decoded = SceneCollectionCodec.decodeCollections("collections:v1\ncollection:c1\t\ta")
        assertEquals(listOf(REPAIRED_COLLECTION_NAME), decoded.map { it.name })
    }

    @Test fun overlongNamesTruncatedOnDecode() {
        val long = "n".repeat(MAX_COLLECTION_NAME_CHARS + 10)
        val decoded = SceneCollectionCodec.decodeCollections(
            "collections:v1\ncollection:c1\t" + SceneConfigCodec.escape(long) + "\ta"
        )
        assertEquals(long.take(MAX_COLLECTION_NAME_CHARS), decoded[0].name)
    }

    @Test fun duplicateMembersKeepFirstOnDecode() {
        val decoded = SceneCollectionCodec.decodeCollections("collections:v1\ncollection:c1\tN\ta\tb\ta")
        assertEquals(listOf("a", "b"), decoded[0].sceneIds)
    }

    @Test fun reservedAndBlankMembersDroppedOnDecode() {
        val decoded = SceneCollectionCodec.decodeCollections(
            "collections:v1\ncollection:c1\tN\t" + ALL_SCENES_COLLECTION_ID + "\t\ta"
        )
        assertEquals(listOf("a"), decoded[0].sceneIds)
    }

    @Test fun collectionsOverCapTruncatedOnDecode() {
        val lines = (1..(MAX_STORED_COLLECTIONS + 3)).joinToString("\n") { "collection:c$it\tN$it" }
        val decoded = SceneCollectionCodec.decodeCollections("collections:v1\n$lines")
        assertEquals(MAX_STORED_COLLECTIONS, decoded.size)
        assertEquals("c1", decoded.first().id)
        assertEquals("c$MAX_STORED_COLLECTIONS", decoded.last().id)
    }

    @Test fun encodeIsCanonicalForUntrustedInput() {
        val input = listOf(
            StreamSceneCollection("c1", "", listOf("a", "a", ALL_SCENES_COLLECTION_ID, "")),
            StreamSceneCollection(ALL_SCENES_COLLECTION_ID, "Fake", listOf("x"))
        )
        val decoded = SceneCollectionCodec.decodeCollections(SceneCollectionCodec.encodeCollections(input))
        assertEquals(1, decoded.size)
        assertEquals(REPAIRED_COLLECTION_NAME, decoded[0].name)
        assertEquals(listOf("a"), decoded[0].sceneIds)
        // The reserved "all" row is dropped by the canonical encoder,
        // so it can never round-trip back as a custom collection.
    }

    @Test fun malformedCollectionLinesAreSkipped() {
        val encoded = "collections:v1\ncollection:only-one-field\ncollection:\ncollection:c1\tN\ta"
        val decoded = SceneCollectionCodec.decodeCollections(encoded)
        assertEquals(listOf("c1"), decoded.map { it.id })
    }

    // --- creation, naming, ids ---

    @Test fun createCollectionAssignsFreshIdAndName() {
        val created = SceneCollectionCodec.createCollection(emptyList(), "Gaming")
        assertEquals(1, created!!.size)
        assertEquals("Gaming", created[0].name)
        assertTrue(created[0].id.startsWith("collection-"))
        assertTrue(created[0].sceneIds.isEmpty())
    }

    @Test fun createCollectionReturnsNullAtCap() {
        val full = (1..MAX_STORED_COLLECTIONS).map {
            StreamSceneCollection("c$it", "N$it", emptyList())
        }
        assertNull(SceneCollectionCodec.createCollection(full, "Overflow"))
    }

    @Test fun createCollectionRepairsBlankName() {
        val created = SceneCollectionCodec.createCollection(emptyList(), "  ")
        assertEquals(REPAIRED_COLLECTION_NAME, created!![0].name)
    }

    @Test fun freshIdsMirrorSceneDiscipline() {
        // Same rule as SceneConfigCodec.freshSceneId: start after
        // size, then skip taken (deterministic; gaps not backfilled).
        val existing = listOf(
            StreamSceneCollection("collection-1", "A", emptyList()),
            StreamSceneCollection("collection-3", "B", emptyList())
        )
        assertEquals("collection-4", SceneCollectionCodec.freshCollectionId(existing))
        assertEquals("collection-1", SceneCollectionCodec.freshCollectionId(emptyList()))
    }

    @Test fun freshNamesSkipTakenNumbers() {
        val existing = listOf(
            StreamSceneCollection("x", "Collection 1", emptyList()),
            StreamSceneCollection("y", "Collection 2", emptyList())
        )
        assertEquals("Collection 3", SceneCollectionCodec.freshCollectionName(existing))
    }

    @Test fun freshIdsNeverReturnReservedAll() {
        assertFalse(SceneCollectionCodec.freshCollectionId(emptyList()) == ALL_SCENES_COLLECTION_ID)
        val existing = (1..20).map { StreamSceneCollection("collection-$it", "N", emptyList()) }
        assertFalse(SceneCollectionCodec.freshCollectionId(existing) == ALL_SCENES_COLLECTION_ID)
    }

    // --- membership ---

    @Test fun addSceneAppendsUnknownMember() {
        val input = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        val out = SceneCollectionCodec.addSceneToCollection(input, "c1", "b", sceneSet("a", "b"))
        assertEquals(listOf("a", "b"), out[0].sceneIds)
    }

    @Test fun addSceneTwiceNeverDuplicates() {
        val input = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        val once = SceneCollectionCodec.addSceneToCollection(input, "c1", "b", sceneSet("a", "b"))
        val twice = SceneCollectionCodec.addSceneToCollection(once, "c1", "b", sceneSet("a", "b"))
        assertEquals(listOf("a", "b"), twice[0].sceneIds)
    }

    @Test fun addSceneToUnknownCollectionIsNoop() {
        val input = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        assertEquals(input, SceneCollectionCodec.addSceneToCollection(input, "nope", "b", sceneSet("a", "b")))
    }

    @Test fun addSceneToAllScenesIsNoop() {
        val input = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        assertEquals(
            input,
            SceneCollectionCodec.addSceneToCollection(input, ALL_SCENES_COLLECTION_ID, "b", sceneSet("a", "b"))
        )
    }

    @Test fun addUnknownSceneIsNoop() {
        val input = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        assertEquals(input, SceneCollectionCodec.addSceneToCollection(input, "c1", "ghost", sceneSet("a")))
    }

    @Test fun removeSceneDropsMember() {
        val input = listOf(StreamSceneCollection("c1", "N", listOf("a", "b")))
        val out = SceneCollectionCodec.removeSceneFromCollection(input, "c1", "a")
        assertEquals(listOf("b"), out[0].sceneIds)
    }

    @Test fun removeAbsentSceneIsNoop() {
        val input = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        assertEquals(input, SceneCollectionCodec.removeSceneFromCollection(input, "c1", "ghost"))
    }

    @Test fun removeSceneFromAllScenesIsNoop() {
        val input = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        assertEquals(input, SceneCollectionCodec.removeSceneFromCollection(input, ALL_SCENES_COLLECTION_ID, "a"))
    }

    @Test fun pruneSceneDropsIdFromEveryCollection() {
        val input = listOf(
            StreamSceneCollection("c1", "A", listOf("a", "b")),
            StreamSceneCollection("c2", "B", listOf("b", "c"))
        )
        val out = SceneCollectionCodec.pruneScene(input, "b")
        assertEquals(listOf("a"), out[0].sceneIds)
        assertEquals(listOf("c"), out[1].sceneIds)
    }

    // --- visibility ---

    @Test fun allScenesShowsEverySceneInStoreOrder() {
        val scenes = listOf(scene("b"), scene("a"), scene("c"))
        assertEquals(scenes, SceneCollectionCodec.scenesInCollection(ALL_SCENES_COLLECTION_ID, scenes, emptyList()))
    }

    @Test fun nullCollectionShowsEveryScene() {
        val scenes = listOf(scene("a"), scene("b"))
        assertEquals(scenes, SceneCollectionCodec.scenesInCollection(null, scenes, emptyList()))
    }

    @Test fun customCollectionShowsMembersInMemberOrder() {
        val scenes = listOf(scene("a"), scene("b"), scene("c"))
        val collections = listOf(StreamSceneCollection("c1", "N", listOf("c", "a")))
        val visible = SceneCollectionCodec.scenesInCollection("c1", scenes, collections)
        assertEquals(listOf("c", "a"), visible.map { it.id })
    }

    @Test fun unknownCollectionFallsBackToAllScenes() {
        val scenes = listOf(scene("a"), scene("b"))
        val collections = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        assertEquals(scenes, SceneCollectionCodec.scenesInCollection("ghost", scenes, collections))
    }

    @Test fun danglingMembersSkippedNeverCrash() {
        val scenes = listOf(scene("a"))
        val collections = listOf(StreamSceneCollection("c1", "N", listOf("ghost", "a")))
        val visible = SceneCollectionCodec.scenesInCollection("c1", scenes, collections)
        assertEquals(listOf("a"), visible.map { it.id })
    }

    @Test fun emptyCollectionShowsNoScenes() {
        val scenes = listOf(scene("a"))
        val collections = listOf(StreamSceneCollection("c1", "N", emptyList()))
        assertTrue(SceneCollectionCodec.scenesInCollection("c1", scenes, collections).isEmpty())
    }

    // --- selection fallback ---

    @Test fun knownActiveWins() {
        val collections = listOf(StreamSceneCollection("c1", "N", emptyList()))
        assertEquals("c1", SceneCollectionCodec.resolveActiveCollection("c1", "c1", collections))
    }

    @Test fun allScenesIsAlwaysKnown() {
        assertEquals(
            ALL_SCENES_COLLECTION_ID,
            SceneCollectionCodec.resolveActiveCollection(ALL_SCENES_COLLECTION_ID, null, emptyList())
        )
    }

    @Test fun unknownActiveFallsBackToKnownDefault() {
        val collections = listOf(StreamSceneCollection("c1", "N", emptyList()))
        assertEquals("c1", SceneCollectionCodec.resolveActiveCollection("ghost", "c1", collections))
    }

    @Test fun unknownActiveAndDefaultFallBackToAllScenes() {
        val collections = listOf(StreamSceneCollection("c1", "N", emptyList()))
        assertEquals(
            ALL_SCENES_COLLECTION_ID,
            SceneCollectionCodec.resolveActiveCollection("ghost", "also-ghost", collections)
        )
    }

    @Test fun nullActiveAndDefaultFallBackToAllScenes() {
        assertEquals(
            ALL_SCENES_COLLECTION_ID,
            SceneCollectionCodec.resolveActiveCollection(null, null, emptyList())
        )
    }

    // --- load resolution ---

    @Test fun freshStoreResolvesToAllScenesAndPersistsDefaults() {
        // Mirror of the scene store self-heal: a fresh store resolves
        // to All Scenes AND persists the default form on first load.
        val resolved = SceneCollectionCodec.resolveStoredCollections(null, null, null, sceneSet("a"))
        assertTrue(resolved.collections.isEmpty())
        assertEquals(ALL_SCENES_COLLECTION_ID, resolved.activeCollectionId)
        assertEquals(ALL_SCENES_COLLECTION_ID, resolved.defaultCollectionId)
        assertTrue(resolved.rewriteCollections)
        assertTrue(resolved.rewriteActive)
        assertTrue(resolved.rewriteDefault)
    }

    @Test fun storedRoundTripDoesNotRewrite() {
        val collections = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        val stored = SceneCollectionCodec.encodeCollections(collections)
        val resolved = SceneCollectionCodec.resolveStoredCollections(stored, "c1", "c1", sceneSet("a"))
        assertEquals(collections, resolved.collections)
        assertEquals("c1", resolved.activeCollectionId)
        assertEquals("c1", resolved.defaultCollectionId)
        assertFalse(resolved.rewriteCollections)
        assertFalse(resolved.rewriteActive)
        assertFalse(resolved.rewriteDefault)
    }

    @Test fun danglingMembersPrunedAndRewrittenOnLoad() {
        val stored = "collections:v1\ncollection:c1\tN\ta\tghost"
        val resolved = SceneCollectionCodec.resolveStoredCollections(stored, "c1", "c1", sceneSet("a"))
        assertEquals(listOf("a"), resolved.collections[0].sceneIds)
        assertTrue(resolved.rewriteCollections)
    }

    @Test fun unknownStoredActiveFallsBackAndRewrites() {
        val collections = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        val stored = SceneCollectionCodec.encodeCollections(collections)
        val resolved = SceneCollectionCodec.resolveStoredCollections(stored, "ghost", "c1", sceneSet("a"))
        assertEquals("c1", resolved.activeCollectionId)
        assertTrue(resolved.rewriteActive)
        assertFalse(resolved.rewriteDefault)
        assertFalse(resolved.rewriteCollections)
    }

    @Test fun unknownStoredDefaultFallsBackToAllScenesAndRewrites() {
        val collections = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        val stored = SceneCollectionCodec.encodeCollections(collections)
        val resolved = SceneCollectionCodec.resolveStoredCollections(stored, "c1", "ghost", sceneSet("a"))
        assertEquals(ALL_SCENES_COLLECTION_ID, resolved.defaultCollectionId)
        assertEquals("c1", resolved.activeCollectionId)
        assertTrue(resolved.rewriteDefault)
    }

    @Test fun corruptEncodingLoadsNoCollectionsButKeepsKnownSceneIds() {
        val known = sceneSet("a", "b")
        val resolved = SceneCollectionCodec.resolveStoredCollections("not-a-store", "ghost", "ghost", known)
        assertTrue(resolved.collections.isEmpty())
        assertEquals(ALL_SCENES_COLLECTION_ID, resolved.activeCollectionId)
        // Corrupt metadata degrades the VIEW, never the scene set:
        // every known scene stays visible via All Scenes.
        val scenes = listOf(scene("a"), scene("b"))
        assertEquals(
            scenes,
            SceneCollectionCodec.scenesInCollection(resolved.activeCollectionId, scenes, resolved.collections)
        )
    }

    // --- save resolution ---

    @Test fun saveResolvesUnknownSelectionsToFallbacks() {
        val collections = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        val saved = SceneCollectionCodec.resolveCollectionSave(collections, "ghost", "also-ghost", sceneSet("a"))
        assertEquals(ALL_SCENES_COLLECTION_ID, saved.activeCollectionId)
        assertEquals(ALL_SCENES_COLLECTION_ID, saved.defaultCollectionId)
        assertEquals(collections, saved.collections)
    }

    @Test fun saveCanonicalizesMembersAndNames() {
        val saved = SceneCollectionCodec.resolveCollectionSave(
            listOf(StreamSceneCollection("c1", "", listOf("a", "ghost", "a"))),
            "c1", "c1", sceneSet("a")
        )
        assertEquals(REPAIRED_COLLECTION_NAME, saved.collections[0].name)
        assertEquals(listOf("a"), saved.collections[0].sceneIds)
        assertEquals("c1", saved.activeCollectionId)
    }

    // --- global-identity invariants ---

    @Test fun collectionOperationsNeverTouchSceneRecords() {
        val scenes = listOf(scene("a", "Alpha"), scene("b", "Beta"))
        val before = scenes.map { it.copy() }
        var collections = listOf(StreamSceneCollection("c1", "N", listOf("a")))
        collections = SceneCollectionCodec.addSceneToCollection(collections, "c1", "b", sceneSet("a", "b"))
        collections = SceneCollectionCodec.removeSceneFromCollection(collections, "c1", "a")
        collections = SceneCollectionCodec.pruneScene(collections, "b")
        // The scene records are untouched value objects: membership
        // changed only; assert byte-level equality to prove it.
        assertEquals(before, scenes)
        assertEquals(1, collections.size)
        assertTrue(collections[0].sceneIds.isEmpty())
    }

    @Test fun sceneVisibleInMultipleCollectionsSharesOneRecord() {
        val shared = scene("a", "Shared")
        val scenes = listOf(shared, scene("b"))
        val collections = listOf(
            StreamSceneCollection("c1", "One", listOf("a")),
            StreamSceneCollection("c2", "Two", listOf("a", "b"))
        )
        val first = SceneCollectionCodec.scenesInCollection("c1", scenes, collections)
        val second = SceneCollectionCodec.scenesInCollection("c2", scenes, collections)
        assertEquals(listOf(shared), first)
        assertEquals("Shared", second[0].name)
        assertTrue(second[0] === shared)
    }

    @Test fun migrationFixtureKeepsV6ScenesIntact() {
        // Upgrade fixture: a real v6 scene-store string with NO
        // collection keys (pre-K1 device). Collection load resolves
        // against the decoded scene ids; the scene records — ids,
        // names, layers, order — come back identical.
        val starter = SceneConfigCodec.defaultScene()
        val scenes = listOf(starter, starter.copy(id = "scene-2", name = "Second"))
        val storedScenesV6 = SceneConfigCodec.encodeStore(scenes)
        val knownIds = SceneConfigCodec.decodeStore(storedScenesV6).map { it.id }.toSet()
        val loaded = SceneCollectionCodec.resolveStoredCollections(null, null, null, knownIds)
        assertTrue(loaded.collections.isEmpty())
        assertEquals(ALL_SCENES_COLLECTION_ID, loaded.activeCollectionId)
        assertEquals(ALL_SCENES_COLLECTION_ID, loaded.defaultCollectionId)
        assertEquals(scenes, SceneConfigCodec.decodeStore(storedScenesV6))
        // Later collection activity references — never rewrites — the
        // scene store: the v6 bytes stay the current bytes.
        val created = SceneCollectionCodec.createCollection(emptyList(), "Gaming")!!
        val withMember = SceneCollectionCodec.addSceneToCollection(created, created[0].id, "scene-2", knownIds)
        val saved = SceneCollectionCodec.resolveCollectionSave(withMember, withMember[0].id, withMember[0].id, knownIds)
        assertEquals(listOf("scene-2"), saved.collections[0].sceneIds)
        assertEquals(scenes, SceneConfigCodec.decodeStore(storedScenesV6))
    }

    @Test fun collectionSwitchNeverChangesLiveScene() {
        // The codec exposes no live-scene input at all: switching the
        // viewed collection is a pure filter over the scene list, so
        // the active (live) scene id cannot be an output. Prove it by
        // resolving two different views and showing the scene list —
        // and hence the live selection the Activity holds — unchanged.
        val scenes = listOf(scene("a"), scene("b"))
        val collections = listOf(StreamSceneCollection("c1", "N", listOf("b")))
        val viewAll = SceneCollectionCodec.scenesInCollection(ALL_SCENES_COLLECTION_ID, scenes, collections)
        val viewCustom = SceneCollectionCodec.scenesInCollection("c1", scenes, collections)
        assertEquals(listOf("a", "b"), viewAll.map { it.id })
        assertEquals(listOf("b"), viewCustom.map { it.id })
        assertEquals(listOf("a", "b"), scenes.map { it.id })
    }
}
