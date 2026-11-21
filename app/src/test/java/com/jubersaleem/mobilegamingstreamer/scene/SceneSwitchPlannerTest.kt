package com.jubersaleem.mobilegamingstreamer.scene

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * H8 decision-matrix contract: every (current source, requested
 * scene) pair resolves to exactly one action. Invalid/empty scenes
 * always resolve to "none" through the streamable gate — no action
 * can ever target a broken state.
 */
class SceneSwitchPlannerTest {

    private val sceneA = SceneConfigCodec.encodeScene(SceneConfigCodec.defaultScene())
    private val sceneB = SceneConfigCodec.encodeScene(
        SceneConfigCodec.defaultScene().copy(
            id = "other",
            name = "Other",
            layers = listOf(
                StreamSceneLayer(
                    id = "text",
                    text = "OTHER",
                    anchor = SceneOverlayAnchor.BOTTOM_RIGHT,
                    zOrder = 1,
                    marginPx = 8,
                    maxWidthPx = 1920,
                    alpha = 1f,
                    enabled = true
                )
            )
        )
    )
    private val emptyScene = SceneConfigCodec.encodeScene(
        SceneConfigCodec.defaultScene().copy(
            layers = SceneConfigCodec.defaultScene().layers.map { it.copy(enabled = false) }
        )
    )

    @Test
    fun screenToSceneSwaps() {
        assertEquals(
            SwitchAction.SWAP_TO_SCENE,
            SceneSwitchPlanner.resolve(SwitchCurrent.Screen, sceneA)
        )
    }

    @Test
    fun screenToNoneIsNoop() {
        assertEquals(SwitchAction.NOOP, SceneSwitchPlanner.resolve(SwitchCurrent.Screen, null))
        assertEquals(SwitchAction.NOOP, SceneSwitchPlanner.resolve(SwitchCurrent.Screen, "garbage"))
        assertEquals(SwitchAction.NOOP, SceneSwitchPlanner.resolve(SwitchCurrent.Screen, emptyScene))
    }

    @Test
    fun legacyToSceneSwaps() {
        assertEquals(
            SwitchAction.SWAP_TO_SCENE,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneLegacy, sceneA)
        )
    }

    @Test
    fun legacyToNoneSwapsHome() {
        assertEquals(
            SwitchAction.SWAP_TO_SCREEN,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneLegacy, null)
        )
        assertEquals(
            SwitchAction.SWAP_TO_SCREEN,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneLegacy, "v9\nscene:x\ty")
        )
    }

    @Test
    fun sameConfigIsNoop() {
        assertEquals(
            SwitchAction.NOOP,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(sceneA), sceneA)
        )
    }

    @Test
    fun otherSceneRebuildsInPlace() {
        assertEquals(
            SwitchAction.IN_PLACE,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(sceneA), sceneB)
        )
    }

    @Test
    fun configToNoneSwapsHome() {
        assertEquals(
            SwitchAction.SWAP_TO_SCREEN,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(sceneA), null)
        )
        assertEquals(
            SwitchAction.SWAP_TO_SCREEN,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(sceneA), "garbage")
        )
        assertEquals(
            SwitchAction.SWAP_TO_SCREEN,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(sceneA), emptyScene)
        )
    }

    @Test
    fun portraitEncodingsSwitchNormally() {
        // Phase I: v4 portrait encodings flow through the matrix like
        // any scene — orientation never gates a switch.
        val portrait = SceneConfigCodec.encodeScene(
            SceneConfigCodec.defaultScene().copy(orientation = SceneDesignOrientation.PORTRAIT)
        )
        val landscape = SceneConfigCodec.encodeScene(SceneConfigCodec.defaultScene())
        assertEquals(
            SwitchAction.IN_PLACE,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(landscape), portrait)
        )
        assertEquals(
            SwitchAction.NOOP,
            SceneSwitchPlanner.resolve(SwitchCurrent.SceneConfig(portrait), portrait)
        )
        assertEquals(
            SwitchAction.SWAP_TO_SCENE,
            SceneSwitchPlanner.resolve(SwitchCurrent.Screen, portrait)
        )
    }
}
