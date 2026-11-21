package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * H10 seeded robustness: fixed seeds make every mutation identical
 * on JVM and CI — stress without flakiness. Fuzzing proves the
 * never-throw + fixed-point contracts; the bound test proves prefs
 * headroom; determinism proves stable stack resolution.
 */
class SceneCodecStressTest {

    private fun baseEncodings(): List<String> = listOf(
        SceneConfigCodec.encodeScene(SceneConfigCodec.defaultScene()),
        SceneConfigCodec.encodeScene(
            StreamScene(
                "s",
                "Stress",
                listOf(
                    StreamSceneLayer(
                        "t", "Hi • live", SceneOverlayAnchor.TOP_LEFT, 1, 8, 1920,
                        0.9f, true, SceneLayerKind.TEXT, null,
                        0xFFFF0000.toInt(), 0x80000000.toInt(), 4,
                        0xFF00FF00.toInt(), 12, 6, 0x66000000.toInt()
                    ),
                    StreamSceneLayer(
                        "i", "", SceneOverlayAnchor.BOTTOM_RIGHT, 2, 8, 640,
                        1f, true, SceneLayerKind.IMAGE, "content://media/1"
                    ),
                    StreamSceneLayer(
                        "c", "", SceneOverlayAnchor.TOP_RIGHT, 3, 8, 1920,
                        1f, true, SceneLayerKind.CLOCK, null
                    ),
                    StreamSceneLayer(
                        "u", "", SceneOverlayAnchor.CENTER, 4, 8, 1920,
                        1f, false, SceneLayerKind.TIMER, null
                    )
                )
            )
        ),
        "v1\nscene:s\tS\nlayer:t\tHi\tTOP_LEFT\t1\t8\t1920\t1\ttrue",
        "v2\nscene:s\tS\nlayer:i\t\tTOP_LEFT\t2\t8\t640\t1\ttrue\tIMAGE\tcontent://media/ok"
    )

    private fun randomChar(random: Random): Char {
        val pool = "\t\n\\:=.-_ FFff019AzZ•é"
        return pool[random.nextInt(pool.length)]
    }

    private fun mutate(random: Random, input: String): String {
        if (input.isEmpty()) return "\n"
        if (random.nextInt(8) == 0) return input.substring(0, random.nextInt(input.length + 1))
        if (random.nextInt(8) == 0) return input + "\n===\n" + input
        val chars = input.toMutableList()
        repeat(1 + random.nextInt(3)) {
            when (random.nextInt(3)) {
                0 -> chars.add(random.nextInt(chars.size + 1), randomChar(random))
                1 -> if (chars.isNotEmpty()) chars.removeAt(random.nextInt(chars.size))
                else -> if (chars.isEmpty()) chars.add(randomChar(random))
                else chars[random.nextInt(chars.size)] = randomChar(random)
            }
        }
        return chars.joinToString("")
    }

    @Test
    fun fuzzDecodeNeverThrowsAndFixpoints() {
        val random = Random(0x1010)
        val bases = baseEncodings()
        repeat(400) { i ->
            val input = mutate(random, bases[i % bases.size])
            val scene = try {
                SceneConfigCodec.decodeScene(input)
            } catch (t: Throwable) {
                throw AssertionError("decodeScene threw on fuzz #$i: ${t.javaClass.simpleName}")
            }
            // Re-encode stability: decode is a fixed point.
            if (scene != null) {
                assertEquals(scene, SceneConfigCodec.decodeScene(SceneConfigCodec.encodeScene(scene)))
            }
            // Store + specs entry points share the never-throw contract.
            try {
                SceneConfigCodec.decodeStore(input)
                SceneConfigCodec.streamableSpecs(input)
            } catch (t: Throwable) {
                throw AssertionError("store/specs threw on fuzz #$i: ${t.javaClass.simpleName}")
            }
        }
    }

    @Test
    fun storeResolutionIsIdempotent() {
        val random = Random(987654321L)
        val bases = baseEncodings() + listOf("", "garbage", "v3", "v3\nscene:\t")
        repeat(50) { i ->
            val input = mutate(random, bases[i % bases.size])
            val active = if (i % 2 == 0) "nope" else null
            val once = SceneConfigCodec.resolveStoredScenes(input, active)
            val twice = SceneConfigCodec.resolveStoredScenes(once.repairedEncoding, once.activeSceneId)
            assertEquals(once.scenes, twice.scenes)
            assertEquals(once.activeSceneId, twice.activeSceneId)
            assertEquals(once.repairedEncoding, twice.repairedEncoding)
            assertTrue(!twice.rewriteScenes)
            assertTrue(!twice.rewriteActive)
        }
    }

    @Test
    fun maxStoreStaysBounded() {
        // Worst case: 16 scenes x 4 layers with max-length fields.
        val longUri = "content://" + "a".repeat(MAX_IMAGE_URI_CHARS - "content://".length)
        val longText = "x".repeat(MAX_TEXT_LAYER_CHARS)
        val scenes = (1..MAX_STORED_SCENES).map { s ->
            StreamScene(
                "scene-$s",
                "n".repeat(MAX_SCENE_NAME_CHARS),
                (1..MAX_STATIC_OVERLAY_LAYERS).map { l ->
                    StreamSceneLayer(
                        "layer-$l", longText, SceneOverlayAnchor.CENTER, l,
                        MAX_LAYER_MARGIN_PX, MAX_LAYER_MAX_WIDTH_PX, 1f, true,
                        SceneLayerKind.IMAGE, longUri,
                        0x12345678, 0x12345678.toInt(), MAX_STYLE_BORDER_PX,
                        0x12345678.toInt(), MAX_STYLE_RADIUS_PX, MAX_STYLE_SHADOW_PX,
                        0x12345678.toInt()
                    )
                }
            )
        }
        val encoded = SceneConfigCodec.encodeStore(scenes)
        // ~144KB worst case; far from prefs transaction limits.
        assertTrue(encoded.length < 256 * 1024)
        val decoded = SceneConfigCodec.decodeStore(encoded)
        assertEquals(MAX_STORED_SCENES, decoded.size)
        assertEquals(scenes, decoded)
    }

    @Test
    fun stackResolveIsDeterministic() {
        // Every anchor x every kind (capped) at an 8K canvas, resolved
        // repeatedly: identical draw order, rects, and alpha every time.
        val canvas = SceneCanvas(7680, 4320)
        val inputs = mutableListOf<SceneOverlayLayerInput>()
        var n = 0
        for (kind in SceneLayerKind.values()) {
            for (anchor in SceneOverlayAnchor.values()) {
                if (inputs.size >= MAX_STATIC_OVERLAY_LAYERS) break
                val spec = SceneOverlaySpec.of(
                    "l$n",
                    if (kind == SceneLayerKind.TEXT) "Determinism sieve" else "",
                    anchor, n + 1, 8, 7680, 1f, true, kind,
                    if (kind == SceneLayerKind.IMAGE) "content://x/$n" else null
                ) ?: continue
                inputs.add(
                    SceneOverlayLayerInput(spec, SceneSizePx(400 + n * 37, 120 + n * 11))
                )
                n += 1
            }
        }
        val first = SceneOverlayStack.resolve(canvas, inputs)
        assertTrue(first != null)
        repeat(5) {
            val next = SceneOverlayStack.resolve(canvas, inputs)!!
            assertEquals(first!!.map { it.id }, next.map { it.id })
            assertEquals(first.map { it.rectPx }, next.map { it.rectPx })
            assertEquals(first.map { it.alpha }, next.map { it.alpha })
        }
    }
}
