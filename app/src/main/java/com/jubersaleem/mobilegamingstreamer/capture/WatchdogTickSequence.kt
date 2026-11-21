package com.jubersaleem.mobilegamingstreamer.capture

/**
 * Executes one watchdog cycle with the observational health sample running
 * before the authoritative driver decision.
 *
 * The monitor shares the watchdog's 20-second stall window, so sampling
 * first is the only way a terminal STALLED verdict can be recorded before
 * the existing stop path executes on the critical tick. The sample's result
 * never influences the returned decision: the driver remains the sole
 * termination authority, its rules are evaluated on the same tick with the
 * same inputs, and a throwing sampler still falls through to the decision.
 * No loops, handlers or lifecycle effects live here.
 */
internal fun runWatchdogCycle(sampleHealth: () -> Unit, decide: () -> WatchdogAction): WatchdogAction {
    try {
        sampleHealth()
    } catch (_: Exception) {
        // Observational sampling must never suppress or delay termination.
    }
    return decide()
}
