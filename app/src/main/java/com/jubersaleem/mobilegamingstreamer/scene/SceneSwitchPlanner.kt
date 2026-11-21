package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H8 live-switch decision matrix. Pure Kotlin: no Android, threads,
 * or I/O. The service executes the resolved action; every decision
 * (noop vs in-place rebuild vs source swap) lives here, unit-tested.
 *
 * Inputs: the currently running video source and the newly requested
 * scene encoding (`null`/absent = "no scene"). The requested scene
 * resolves to "none" unless it carries at least one streamable layer
 * through the existing [SceneConfigCodec.streamableSpecs] gate — an
 * invalid or empty scene can never produce a broken state.
 *
 * Actions:
 * - [SwitchAction.NOOP]: nothing to do (same config, or no scene on
 *   either side).
 * - [SwitchAction.IN_PLACE]: same config-started source, different
 *   scene — rebuild overlays inside the running source (no swap).
 * - [SwitchAction.SWAP_TO_SCENE]: replace the running source with a
 *   new config-started scene source via the library swap.
 * - [SwitchAction.SWAP_TO_SCREEN]: replace the running scene source
 *   with the default screen source via the library swap.
 */
internal sealed interface SwitchCurrent {
    /** Default direct passthrough (`ScreenSource`). */
    data object Screen : SwitchCurrent

    /** Config-started scene source, carrying its encoded scene. */
    data class SceneConfig(val config: String) : SwitchCurrent

    /** Legacy ADB-started scene source (fixed two-string mapping). */
    data object SceneLegacy : SwitchCurrent
}

internal enum class SwitchAction {
    NOOP,
    IN_PLACE,
    SWAP_TO_SCENE,
    SWAP_TO_SCREEN
}

internal object SceneSwitchPlanner {
    fun resolve(current: SwitchCurrent, newConfig: String?): SwitchAction {
        val hasScene = SceneConfigCodec.streamableSpecs(newConfig).isNotEmpty()
        if (!hasScene) {
            // Any -> none: leave passthrough alone, otherwise swap home.
            return when (current) {
                is SwitchCurrent.Screen -> SwitchAction.NOOP
                is SwitchCurrent.SceneConfig,
                is SwitchCurrent.SceneLegacy -> SwitchAction.SWAP_TO_SCREEN
            }
        }
        return when (current) {
            is SwitchCurrent.Screen,
            is SwitchCurrent.SceneLegacy -> SwitchAction.SWAP_TO_SCENE
            // Same encoding = same scene: no work. A re-encoding that
            // differs textually but means the same scene rebuilds
            // harmlessly (full state transition, still correct).
            is SwitchCurrent.SceneConfig ->
                if (current.config == newConfig) SwitchAction.NOOP else SwitchAction.IN_PLACE
        }
    }
}
