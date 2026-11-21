package com.jubersaleem.mobilegamingstreamer

import android.content.Context
import com.jubersaleem.mobilegamingstreamer.scene.SceneConfigCodec
import com.jubersaleem.mobilegamingstreamer.scene.SceneTransition
import com.jubersaleem.mobilegamingstreamer.scene.SceneTransitions
import com.jubersaleem.mobilegamingstreamer.scene.StreamScene

/**
 * H5 scene persistence: thin Android wrapper over the pure
 * [SceneConfigCodec]. Scenes live in a private SharedPreferences file,
 * written through on every editor mutation, so they survive Activity
 * recreation, app closure, and device restart.
 *
 * Corrupt or missing data never crashes and never blocks streaming: load
 * self-heals to a valid state (dropping bad entries, reseeding the
 * default scene when nothing valid remains) and persists the repaired
 * form. No bitmaps or textures are stored — text + geometry only, with
 * the codec's caps bounding the file.
 */
object ScenePrefsStore {
    const val PREFS = "mgs_scenes"
    const val KEY_SCENES = "scenes_v1"
    const val KEY_ACTIVE = "active_scene_id"
    const val KEY_TRANSITION = "scene_transition_v1"

    /** Loaded state: valid scenes (never empty) + the active scene id. */
    data class Loaded(val scenes: List<StreamScene>, val activeSceneId: String)

    fun load(context: Context): Loaded {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val resolved = SceneConfigCodec.resolveStoredScenes(
                prefs.getString(KEY_SCENES, null),
                prefs.getString(KEY_ACTIVE, null)
            )
            if (resolved.rewriteScenes || resolved.rewriteActive) {
                // Self-heal: persist the repaired/normalized form so a
                // corrupt file cannot linger and surprise a later load.
                prefs.edit().apply {
                    if (resolved.rewriteScenes) putString(KEY_SCENES, resolved.repairedEncoding)
                    putString(KEY_ACTIVE, resolved.activeSceneId)
                }.apply()
            }
            return Loaded(resolved.scenes, resolved.activeSceneId)
        } catch (_: Exception) {
            // Unreadable store (e.g. corrupt prefs XML): fall back to the
            // default scene in memory; the next mutation rewrites the file.
            val seeded = SceneConfigCodec.defaultScene()
            return Loaded(listOf(seeded), seeded.id)
        }
    }

    /**
     * J4 global switch effect: the default transition applied to live
     * scene switches. Corrupt or missing data self-heals to CUT (never
     * crashes, never a half-config) and persists the repaired form.
     */
    fun loadTransition(context: Context): SceneTransition {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_TRANSITION, null)
            val parsed = SceneTransitions.parseExtra(stored)
            if (stored != parsed.encode()) {
                try {
                    prefs.edit().putString(KEY_TRANSITION, parsed.encode()).apply()
                } catch (_: Exception) {
                }
            }
            parsed
        } catch (_: Exception) {
            SceneTransition.DEFAULT
        }
    }

    fun saveTransition(context: Context, transition: SceneTransition) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TRANSITION, transition.encode())
                .apply()
        } catch (_: Exception) {
            // Best effort: in-memory state stays usable, retries on the
            // next mutation (same contract as scene saves).
        }
    }

    fun save(context: Context, scenes: List<StreamScene>, activeSceneId: String) {
        val resolved = SceneConfigCodec.resolveSceneSave(scenes, activeSceneId)
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SCENES, resolved.encoding)
                .putString(KEY_ACTIVE, resolved.activeSceneId)
                .apply()
        } catch (_: Exception) {
            // Best effort: a failed write keeps the in-memory state (the
            // editor stays usable) and retries on the next mutation.
        }
    }
}
