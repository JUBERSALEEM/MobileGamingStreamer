package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the H5 scene model + codec: round-trips, repair/drop policy,
 * bounds, corrupt-input safety, delivery helpers, and id generation.
 */
class SceneConfigTest {

    private fun layer(
        id: String = "text",
        text: String = "MGS",
        anchor: SceneOverlayAnchor = SceneOverlayAnchor.TOP_LEFT,
        zOrder: Int = 1,
        marginPx: Int = 27,
        maxWidthPx: Int = 1920,
        alpha: Float = 1f,
        enabled: Boolean = true
    ) = StreamSceneLayer(id, text, anchor, zOrder, marginPx, maxWidthPx, alpha, enabled)

    private fun scene(
        id: String = "s1",
        name: String = "Starter",
        layers: List<StreamSceneLayer> = listOf(layer())
    ) = StreamScene(id, name, layers)

    // ---- Round-trip ----

    @Test
    fun sceneRoundTripPreservesEverything() {
        val original = scene(
            layers = listOf(
                layer(id = "a", text = "Top", anchor = SceneOverlayAnchor.TOP_RIGHT, zOrder = 1),
                layer(
                    id = "b", text = "Hi\tTab\nNL\\BS", anchor = SceneOverlayAnchor.BOTTOM_LEFT,
                    zOrder = 2, marginPx = 10, maxWidthPx = 500, alpha = 0.5f, enabled = false
                )
            )
        )
        assertEquals(original, SceneConfigCodec.decodeScene(SceneConfigCodec.encodeScene(original)))
    }

    @Test
    fun storeRoundTripPreservesScenes() {
        val scenes = listOf(scene(id = "s1"), scene(id = "s2", name = "Second", layers = emptyList()))
        assertEquals(scenes, SceneConfigCodec.decodeStore(SceneConfigCodec.encodeStore(scenes)))
    }

    @Test
    fun decodeNeverThrowsOnGarbage() {
        val garbage = listOf(
            null, "", "   ", "v3\nscene:a\tb", "v1", "v1\nbogus", "v2", "v2\nbogus",
            "v1\nscene:onlyonefield", "v1\nscene:\t", "===",
            "\n===\n", "v1\nscene:a\tb\\"
        )
        for (input in garbage) {
            assertTrue("scene <$input>", SceneConfigCodec.decodeScene(input) == null)
            assertTrue(
                "store <$input>",
                SceneConfigCodec.decodeStore(input).isEmpty()
            )
        }
        assertTrue(SceneConfigCodec.streamableSpecs("v9\nnope").isEmpty())
        assertTrue(SceneConfigCodec.streamableSpecs(null).isEmpty())
    }

    @Test
    fun partiallyCorruptScenesDecodeWithLayersDropped() {
        // Valid header + corrupt layer lines: the scene survives with the
        // bad layers dropped (repair policy), never null, never throwing.
        val inputs = listOf(
            "v1\nscene:a\tb",
            "v1\nscene:a\tb\nlayer:short",
            "v1\nscene:a\tb\nlayer:f1\tf2\tf3\tf4\tf5\tf6\tf7",
            "v1\nscene:a\tb\nlayer:x\\qy\tok\tTOP_LEFT\t1\t8\t100\t1\ttrue"
        )
        for (input in inputs) {
            val decoded = SceneConfigCodec.decodeScene(input)
            assertTrue("scene <$input>", decoded != null)
            assertTrue("layers <$input>", decoded!!.layers.isEmpty())
            assertEquals(1, SceneConfigCodec.decodeStore(input).size)
        }
    }

    // ---- Repair vs drop ----

    @Test
    fun blankNameRepairedAndLongNameTruncated() {
        assertEquals(
            REPAIRED_SCENE_NAME,
            SceneConfigCodec.decodeScene("v1\nscene:s1\t")!!.name
        )
        val long = "n".repeat(MAX_SCENE_NAME_CHARS + 10)
        val decoded = SceneConfigCodec.decodeScene("v1\nscene:s1\t$long")!!
        assertEquals(MAX_SCENE_NAME_CHARS, decoded.name.length)
    }

    @Test
    fun structurallyBadLayersDroppedSceneKept() {
        val encoded = "v1\nscene:s1\tS\n" + listOf(
            "layer:good\tOK\tTOP_LEFT\t1\t8\t100\t1\ttrue",
            "layer:\tblank-id\tTOP_LEFT\t1\t8\t100\t1\ttrue",
            "layer:blank-text\t \tTOP_LEFT\t1\t8\t100\t1\ttrue",
            "layer:bad-anchor\tOK\tMIDDLE\t1\t8\t100\t1\ttrue",
            "layer:bad-z\tOK\tTOP_LEFT\t-1\t8\t100\t1\ttrue",
            "layer:bad-margin\tOK\tTOP_LEFT\t1\t-5\t100\t1\ttrue",
            "layer:bad-maxw\tOK\tTOP_LEFT\t1\t8\t0\t1\ttrue",
            "layer:bad-alpha\tOK\tTOP_LEFT\t1\t8\t100\tNaN\ttrue",
            "layer:bad-enabled\tOK\tTOP_LEFT\t1\t8\t100\t1\tyes",
            "notalayer:ignored\tline"
        ).joinToString("\n")
        val decoded = SceneConfigCodec.decodeScene(encoded)!!
        assertEquals(listOf("good"), decoded.layers.map { it.id })
    }

    @Test
    fun duplicateLayerIdsKeepFirst() {
        val encoded = "v1\nscene:s\tS\n" + listOf(
            "layer:dup\tFirst\tTOP_LEFT\t1\t8\t100\t1\ttrue",
            "layer:dup\tSecond\tBOTTOM_RIGHT\t2\t8\t100\t1\ttrue",
            "layer:other\tOK\tTOP_LEFT\t3\t8\t100\t1\ttrue"
        ).joinToString("\n")
        val decoded = SceneConfigCodec.decodeScene(encoded)!!
        assertEquals(listOf("dup", "other"), decoded.layers.map { it.id })
        assertEquals("First", decoded.layers.first().text)
    }

    @Test
    fun corruptStoreEntriesSkippedAndDuplicatesCollapsed() {
        val good = SceneConfigCodec.encodeScene(scene(id = "s1"))
        val store = listOf("corrupt-block", good, good, "v1\nscene:\tblank").joinToString("\n===\n")
        val decoded = SceneConfigCodec.decodeStore(store)
        assertEquals(listOf("s1"), decoded.map { it.id })
    }

    // ---- Bounds ----

    @Test
    fun layerCapDropsExtrasDeterministically() {
        val many = (0 until MAX_STATIC_OVERLAY_LAYERS + 3).map { i -> layer(id = "t$i", zOrder = i + 1) }
        val decoded = SceneConfigCodec.decodeScene(SceneConfigCodec.encodeScene(scene(layers = many)))!!
        assertEquals(MAX_STATIC_OVERLAY_LAYERS, decoded.layers.size)
        assertEquals(listOf("t0", "t1", "t2", "t3"), decoded.layers.map { it.id })
        // A hand-built over-cap encoding is repaired the same way.
        val raw = "v1\nscene:s\tS\n" + many.joinToString("\n") {
            "layer:${it.id}\tMGS\tTOP_LEFT\t${it.zOrder}\t8\t1920\t1\ttrue"
        }
        assertEquals(
            MAX_STATIC_OVERLAY_LAYERS,
            SceneConfigCodec.decodeScene(raw)!!.layers.size
        )
    }

    @Test
    fun storeCapKeepsFirstSixteen() {
        val many = (0 until MAX_STORED_SCENES + 4).map { i -> scene(id = "s$i") }
        val decoded = SceneConfigCodec.decodeStore(SceneConfigCodec.encodeStore(many))
        assertEquals(MAX_STORED_SCENES, decoded.size)
        assertEquals("s0", decoded.first().id)
        assertEquals("s15", decoded.last().id)
    }

    @Test
    fun infiniteAlphaAndOutOfRangeGeometryDropped() {
        val encoded = "v1\nscene:s\tS\n" + listOf(
            "layer:inf\tOK\tTOP_LEFT\t1\t8\t100\tInfinity\ttrue",
            "layer:bigmargin\tOK\tTOP_LEFT\t1\t${MAX_LAYER_MARGIN_PX + 1}\t100\t1\ttrue",
            "layer:bigmaxw\tOK\tTOP_LEFT\t1\t8\t${MAX_LAYER_MAX_WIDTH_PX + 1}\t1\ttrue",
            "layer:bigz\tOK\tTOP_LEFT\t${MAX_LAYER_Z + 1}\t8\t100\t1\ttrue",
            "layer:ok\tOK\tTOP_LEFT\t1\t8\t100\t0.25\ttrue"
        ).joinToString("\n")
        assertEquals(listOf("ok"), SceneConfigCodec.decodeScene(encoded)!!.layers.map { it.id })
    }

    // ---- Model behavior ----

    @Test
    fun normalizedZReassignsInListOrder() {
        val reordered = scene(
            layers = listOf(layer(id = "b", zOrder = 9), layer(id = "a", zOrder = 3))
        ).normalizedZ()
        assertEquals(listOf("b", "a"), reordered.layers.map { it.id })
        assertEquals(listOf(1, 2), reordered.layers.map { it.zOrder })
    }

    @Test
    fun streamableSpecsDropsDisabledAndInvalid() {
        val specs = scene(
            layers = listOf(
                layer(id = "on"),
                layer(id = "off", enabled = false),
                // Direct construction bypasses validation; mapping must not.
                StreamSceneLayer("bad", "", SceneOverlayAnchor.TOP_LEFT, 3, 8, 100, 1f)
            )
        ).streamableSpecs()
        assertEquals(listOf("on"), specs.map { it.id })
    }

    @Test
    fun defaultSceneStreamsOneValidLayer() {
        val def = SceneConfigCodec.defaultScene()
        assertEquals(DEFAULT_SCENE_ID, def.id)
        assertEquals(1, def.streamableSpecs().size)
        assertEquals(
            def,
            SceneConfigCodec.decodeScene(SceneConfigCodec.encodeScene(def))
        )
    }

    @Test
    fun freshIdsAndNamesAvoidCollisions() {
        val scenes = listOf(scene(id = "scene-1", name = "Scene 1"))
        assertEquals("scene-2", SceneConfigCodec.freshSceneId(scenes))
        assertEquals("Scene 2", SceneConfigCodec.freshSceneName(scenes))
        assertEquals("Starter (copy)", SceneConfigCodec.duplicateName(scenes, "Starter"))
        assertEquals(
            "Starter (copy 2)",
            SceneConfigCodec.duplicateName(scenes + scene(id = "x", name = "Starter (copy)"), "Starter")
        )
        assertEquals("layer-2", SceneConfigCodec.freshLayerId(scene()))
    }

    @Test
    fun activeSceneEncodingGatesOnStreamableLayers() {
        val scenes = listOf(
            scene(id = "full"),
            scene(id = "empty", layers = emptyList()),
            scene(id = "off", layers = listOf(layer(enabled = false)))
        )
        val encoded = SceneConfigCodec.activeSceneEncoding(scenes, "full")
        assertTrue(encoded != null)
        assertEquals("full", SceneConfigCodec.decodeScene(encoded)!!.id)
        assertTrue(SceneConfigCodec.activeSceneEncoding(scenes, "empty") == null)
        assertTrue(SceneConfigCodec.activeSceneEncoding(scenes, "off") == null)
        assertTrue(SceneConfigCodec.activeSceneEncoding(scenes, "missing") == null)
        assertTrue(SceneConfigCodec.activeSceneEncoding(scenes, null) == null)
    }

    // ---- H6 image layers + codec v2 ----

    private fun imageLayer(
        id: String = "logo",
        uri: String? = "content://media/logo",
        enabled: Boolean = true
    ) = StreamSceneLayer(
        id, "", SceneOverlayAnchor.BOTTOM_RIGHT, 2, 27, 640, 0.8f, enabled,
        SceneLayerKind.IMAGE, uri
    )

    @Test
    fun imageLayerRoundTripPreservesKindAndUri() {
        val original = scene(layers = listOf(layer(id = "t"), imageLayer()))
        val encoded = SceneConfigCodec.encodeScene(original)
        assertTrue(encoded.startsWith("v2\n"))
        assertEquals(original, SceneConfigCodec.decodeScene(encoded))
    }

    @Test
    fun v1StoresDecodeAsTextLayers() {
        // H5 backward compatibility: v1 lines carry no kind/uri fields.
        val decoded = SceneConfigCodec.decodeScene(
            "v1\nscene:s1\tS\nlayer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue"
        )!!
        assertEquals(1, decoded.layers.size)
        assertEquals(SceneLayerKind.TEXT, decoded.layers[0].kind)
        assertTrue(decoded.layers[0].imageUri == null)
        assertEquals("Hi", decoded.layers[0].text)
    }

    @Test
    fun mixedVersionStoreDecodes() {
        val v1block = "v1\nscene:old\tOld\nlayer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue"
        val v2block = SceneConfigCodec.encodeScene(scene(id = "new"))
        val decoded = SceneConfigCodec.decodeStore("$v1block\n===\n$v2block")
        assertEquals(listOf("old", "new"), decoded.map { it.id })
        assertEquals(SceneLayerKind.TEXT, decoded[0].layers[0].kind)
    }

    @Test
    fun badKindOrUriDropsImageLayer() {
        val encoded = "v2\nscene:s1\tS\n" + listOf(
            "layer:good\t\tBOTTOM_RIGHT\t1\t8\t640\t1\ttrue\tIMAGE\tcontent://media/ok",
            "layer:badkind\t\tTOP_LEFT\t2\t8\t640\t1\ttrue\tVIDEO\tcontent://media/ok",
            "layer:badscheme\t\tTOP_LEFT\t3\t8\t640\t1\ttrue\tIMAGE\thttp://evil/x.png",
            "layer:emptyuri\t\tTOP_LEFT\t4\t8\t640\t1\ttrue\tIMAGE\t",
            "layer:toolong\t\tTOP_LEFT\t5\t8\t640\t1\ttrue\tIMAGE\tcontent://" + "x".repeat(MAX_IMAGE_URI_CHARS),
            "layer:textblank\t\tTOP_LEFT\t6\t8\t640\t1\ttrue\tTEXT\t"
        ).joinToString("\n")
        val decoded = SceneConfigCodec.decodeScene(encoded)!!
        assertEquals(listOf("good"), decoded.layers.map { it.id })
    }

    @Test
    fun imageLayersAreStreamableWhenUriValid() {
        val specs = scene(
            layers = listOf(
                imageLayer(id = "img"),
                imageLayer(id = "bad", uri = "notauri"),
                imageLayer(id = "off", enabled = false)
            )
        ).streamableSpecs()
        assertEquals(listOf("img"), specs.map { it.id })
        assertEquals(SceneLayerKind.IMAGE, specs[0].kind)
    }

    @Test
    fun imageUriSchemeAllowlist() {
        assertTrue(isValidImageUri("content://media/logo"))
        assertTrue(isValidImageUri("file:///sdcard/logo.png"))
        assertTrue(isValidImageUri("android.resource://com.example/123"))
        assertTrue(isValidImageUri("CONTENT://upper/case"))
        assertTrue(!isValidImageUri("https://example.com/x.png"))
        assertTrue(!isValidImageUri("notauri"))
        assertTrue(!isValidImageUri(""))
        assertTrue(!isValidImageUri(null))
    }

    @Test
    fun drawOrderFollowsZThenId() {
        val ordered = scene(
            layers = listOf(layer(id = "b", zOrder = 1), layer(id = "a", zOrder = 1))
        ).layersInDrawOrder()
        assertEquals(listOf("a", "b"), ordered.map { it.id })
    }
}
