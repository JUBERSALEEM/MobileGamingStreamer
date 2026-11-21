package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic tests for the H0 scene contracts. Every test drives the real
 * [SceneComposition.of] validation/ordering — no Android, no sleeps.
 */
class SceneContractsTest {
    @Test
    fun compositionOrdersByZAscending() {
        val composition = SceneComposition.of(
            listOf(
                SceneLayer("top", "screen", zOrder = 10),
                SceneLayer("back", "screen", zOrder = -5),
                SceneLayer("mid", "screen", zOrder = 0)
            )
        )
        assertNotNull(composition)
        assertEquals(
            listOf("back", "mid", "top"),
            composition!!.layersInDrawOrder.map { it.id }
        )
    }

    @Test
    fun compositionTieBreaksEqualZById() {
        val composition = SceneComposition.of(
            listOf(
                SceneLayer("b-layer", "screen", zOrder = 0),
                SceneLayer("a-layer", "screen", zOrder = 0),
                SceneLayer("c-layer", "screen", zOrder = 0)
            )
        )
        assertNotNull(composition)
        assertEquals(
            listOf("a-layer", "b-layer", "c-layer"),
            composition!!.layersInDrawOrder.map { it.id }
        )
    }

    @Test
    fun compositionDrawOrderDeterministicAcrossInputOrders() {
        val layers = listOf(
            SceneLayer("a", "screen", zOrder = 2),
            SceneLayer("b", "screen", zOrder = 2),
            SceneLayer("c", "screen", zOrder = 1)
        )
        val forward = SceneComposition.of(layers)!!.layersInDrawOrder.map { it.id }
        val reversed = SceneComposition.of(layers.reversed())!!.layersInDrawOrder.map { it.id }
        assertEquals(listOf("c", "a", "b"), forward)
        assertEquals(forward, reversed)
    }

    @Test
    fun compositionVisibleFiltersDisabledPreservingOrder() {
        val composition = SceneComposition.of(
            listOf(
                SceneLayer("back", "screen", zOrder = 0),
                SceneLayer("hidden", "screen", zOrder = 1, enabled = false),
                SceneLayer("top", "screen", zOrder = 2)
            )
        )
        assertNotNull(composition)
        assertEquals(3, composition!!.size)
        assertEquals(
            listOf("back", "top"),
            composition.visibleLayersInDrawOrder.map { it.id }
        )
    }

    @Test
    fun compositionFindById() {
        val layer = SceneLayer("hud", "screen", zOrder = 5)
        val composition = SceneComposition.of(
            listOf(SceneLayer("bg", "screen"), layer)
        )!!
        assertEquals(layer, composition.findById("hud"))
        assertNull(composition.findById("missing"))
    }

    @Test
    fun compositionRejectsDuplicateIds() {
        assertNull(
            SceneComposition.of(
                listOf(
                    SceneLayer("same", "screen", zOrder = 0),
                    SceneLayer("same", "screen", zOrder = 1)
                )
            )
        )
    }

    @Test
    fun compositionRejectsBlankId() {
        assertNull(SceneComposition.of(listOf(SceneLayer("", "screen"))))
        assertNull(SceneComposition.of(listOf(SceneLayer("   ", "screen"))))
    }

    @Test
    fun compositionRejectsBlankSourceKey() {
        assertNull(SceneComposition.of(listOf(SceneLayer("a", ""))))
        assertNull(SceneComposition.of(listOf(SceneLayer("a", "  "))))
    }

    @Test
    fun compositionRejectsAnyInvalidLayer() {
        val layers = listOf(
            SceneLayer("ok-1", "screen"),
            SceneLayer("", "screen"),
            SceneLayer("ok-2", "screen")
        )
        assertNull(SceneComposition.of(layers))
    }

    @Test
    fun compositionRejectsOverMaxLayers() {
        val layers = (1..MAX_SCENE_LAYERS + 1).map { SceneLayer("layer-$it", "screen") }
        assertNull(SceneComposition.of(layers))
    }

    @Test
    fun compositionAcceptsMaxLayers() {
        val layers = (1..MAX_SCENE_LAYERS).map { SceneLayer("layer-$it", "screen") }
        val composition = SceneComposition.of(layers)
        assertNotNull(composition)
        assertEquals(MAX_SCENE_LAYERS, composition!!.size)
    }

    @Test
    fun compositionEmptyIsValid() {
        val composition = SceneComposition.of(emptyList())
        assertNotNull(composition)
        assertEquals(0, composition!!.size)
        assertTrue(composition.layersInDrawOrder.isEmpty())
        assertTrue(composition.visibleLayersInDrawOrder.isEmpty())
    }

    @Test
    fun compositionSnapshotImmuneToInputMutation() {
        val input = mutableListOf(
            SceneLayer("a", "screen", zOrder = 1),
            SceneLayer("b", "screen", zOrder = 0)
        )
        val composition = SceneComposition.of(input)!!
        input.clear()
        input.add(SceneLayer("intruder", "screen", zOrder = -100))
        assertEquals(2, composition.size)
        assertEquals(listOf("b", "a"), composition.layersInDrawOrder.map { it.id })
        assertNotNull(composition.findById("a"))
        assertNull(composition.findById("intruder"))
    }

    @Test
    fun exposedDrawOrderRejectsClearThroughMutableCast() {
        val composition = SceneComposition.of(
            listOf(
                SceneLayer("b", "screen", zOrder = 1),
                SceneLayer("a", "screen", zOrder = 0)
            )
        )!!
        try {
            (composition.layersInDrawOrder as MutableList<SceneLayer>).clear()
            fail("clear() on the exposed draw order must throw")
        } catch (expected: UnsupportedOperationException) {
            // Required: mutation is rejected, never applied.
        }
        assertEquals(2, composition.size)
        assertEquals(listOf("a", "b"), composition.layersInDrawOrder.map { it.id })
    }

    @Test
    fun exposedDrawOrderRejectsAddAndSetThroughMutableCast() {
        // Two layers: without the unmodifiable wrapper the runtime list is a
        // fixed-size Arrays$ArrayList, where add() throws but set() succeeds
        // and silently replaces composition state. The set() attempt below is
        // the genuine regression test for that hole.
        val composition = SceneComposition.of(
            listOf(
                SceneLayer("a", "screen", zOrder = 1),
                SceneLayer("b", "screen", zOrder = 0)
            )
        )!!
        try {
            (composition.layersInDrawOrder as MutableList<SceneLayer>)
                .add(SceneLayer("intruder", "screen"))
            fail("add() on the exposed draw order must throw")
        } catch (expected: UnsupportedOperationException) {
            // Required.
        }
        try {
            (composition.layersInDrawOrder as MutableList<SceneLayer>)[0] =
                SceneLayer("replaced", "screen")
            fail("set() on the exposed draw order must throw")
        } catch (expected: UnsupportedOperationException) {
            // Required: without the wrapper this silently corrupts state.
        }
        assertEquals(2, composition.size)
        assertEquals(listOf("b", "a"), composition.layersInDrawOrder.map { it.id })
        assertNull(composition.findById("intruder"))
        assertNull(composition.findById("replaced"))
    }

    @Test
    fun mutatingVisibleListSnapshotCannotAffectComposition() {
        // visibleLayersInDrawOrder builds a fresh list per access: mutating
        // the returned snapshot must leave composition state untouched.
        val composition = SceneComposition.of(
            listOf(
                SceneLayer("a", "screen", zOrder = 0),
                SceneLayer("b", "screen", zOrder = 1)
            )
        )!!
        val snapshot = composition.visibleLayersInDrawOrder as MutableList<SceneLayer>
        snapshot.clear()
        assertEquals(2, composition.size)
        assertEquals(listOf("a", "b"), composition.visibleLayersInDrawOrder.map { it.id })
    }

    @Test
    fun layerDefaults() {
        // Layers are visible, back-ordered and FIT-placed unless stated.
        val layer = SceneLayer("a", "screen")
        assertEquals(0, layer.zOrder)
        assertEquals(SceneLayoutPolicy.FIT, layer.layoutPolicy)
        assertTrue(layer.enabled)
        assertTrue(layer.isValid)
    }

    @Test
    fun layerValidity() {
        assertTrue(SceneLayer("a", "screen").isValid)
        assertFalse(SceneLayer("", "screen").isValid)
        assertFalse(SceneLayer("a", "").isValid)
        assertFalse(SceneLayer("  ", "screen").isValid)
        assertFalse(SceneLayer("a", " ").isValid)
    }
}
