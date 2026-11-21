package com.jubersaleem.mobilegamingstreamer

import android.content.Context
import com.jubersaleem.mobilegamingstreamer.scene.SceneConfigCodec
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

    /** Loaded state: valid scenes (never empty) + the active scene id. */
    data class Loaded(val scenes: List<StreamScene>, val activeSceneId: String)

    fun load(context: Context): Loaded {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_SCENES, null)
            var scenes = SceneConfigCodec.decodeStore(stored)
            if (scenes.isEmpty()) scenes = listOf(SceneConfigCodec.defaultScene())
            var activeId = prefs.getString(KEY_ACTIVE, null)
            if (activeId == null || scenes.none { it.id == activeId }) {
                activeId = scenes.first().id
            }
            val repaired = SceneConfigCodec.encodeStore(scenes)
            if (repaired != (stored ?: "")) {
                // Self-heal: persist the repaired/normalized form so a
                // corrupt file cannot linger and surprise a later load.
                prefs.edit()
                    .putString(KEY_SCENES, repaired)
                    .putString(KEY_ACTIVE, activeId)
                    .apply()
            } else if (prefs.getString(KEY_ACTIVE, null) != activeId) {
                prefs.edit().putString(KEY_ACTIVE, activeId).apply()
            }
            return Loaded(scenes, activeId)
        } catch (_: Exception) {
            // Unreadable store (e.g. corrupt prefs XML): fall back to the
            // default scene in memory; the next mutation rewrites the file.
            val seeded = SceneConfigCodec.defaultScene()
            return Loaded(listOf(seeded), seeded.id)
        }
    }

    fun save(context: Context, scenes: List<StreamScene>, activeSceneId: String) {
        val valid = scenes.ifEmpty { listOf(SceneConfigCodec.defaultScene()) }
        val active = if (valid.any { it.id == activeSceneId }) activeSceneId else valid.first().id
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SCENES, SceneConfigCodec.encodeStore(valid))
                .putString(KEY_ACTIVE, activeId)
                .apply()
        } catch (_: Exception) {
            // Best effort: a failed write keeps the in-memory state (the
            // editor stays usable) and retries on the next mutation.
        }
    }
}
