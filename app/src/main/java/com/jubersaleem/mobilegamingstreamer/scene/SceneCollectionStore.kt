package com.jubersaleem.mobilegamingstreamer.scene

import android.content.SharedPreferences

/**
 * K1 scene-collection prefs wrapper (store format v2): collections,
 * active collection, and default collection live on their own keys.
 * The scene record keys are untouched by this class — corrupt or
 * missing collection metadata degrades to All Scenes and never
 * destroys scenes.
 *
 * Scene-code coupling (mirror of ScenePrefsStore.load):
 *   - The caller loads scenes FIRST (ScenePrefsStore.load), so the
 *     stored scene set and the live scene id already exist before
 *     this load runs; this class never changes them.
 *   - When collections keys are ABSENT (fresh install or upgrade
 *     migration floor), the selection falls back to All Scenes —
 *     every known scene stays visible and the live scene is
 *     untouched — regardless of whether scene data is default or
 *     custom. No collections are auto-created: custom collections
 *     are user-driven, and All Scenes already shows everything.
 *   - When collections keys EXIST but the stored active selection
 *     is unknown, fallback = stored default collection → All
 *     Scenes (pure resolution, no scene consultation).
 *   - Collection-decode corruption does NOT reset scenes;
 *     conversely scene failure leaves collections alone.
 */
class SceneCollectionStore(private val prefs: SharedPreferences) {

    companion object {
        const val KEY_COLLECTIONS = "scene_collections_v1"
        const val KEY_ACTIVE_COLLECTION = "active_scene_collection"
        const val KEY_DEFAULT_COLLECTION = "default_scene_collection"
    }

    /** What [load] returns: validated state plus store decisions. */
    data class LoadedCollectionState(
        val collections: List<StreamSceneCollection>,
        val activeCollectionId: String,
        val defaultCollectionId: String,
        val migrated: Boolean
    )

    /**
     * Loads and validates collection metadata. The full resolution
     * core ([SceneCollectionCodec.resolveStoredCollections]) is pure
     * and JVM-tested; this method adds the prefs plumbing plus the
     * Scene.liveActive migration floor (K1 scope floor per the class
     * KDoc: only when collections keys are ABSENT).
     */
    fun load(knownSceneIds: Set<String>): LoadedCollectionState {
        val hasKeys = prefs.contains(KEY_COLLECTIONS) ||
            prefs.contains(KEY_ACTIVE_COLLECTION) ||
            prefs.contains(KEY_DEFAULT_COLLECTION)
        var resolution = SceneCollectionCodec.resolveStoredCollections(
            storedCollections = prefs.getString(KEY_COLLECTIONS, null),
            storedActive = prefs.getString(KEY_ACTIVE_COLLECTION, null),
            storedDefault = prefs.getString(KEY_DEFAULT_COLLECTION, null),
            knownSceneIds = knownSceneIds
        )
        var rewriteActive = resolution.rewriteActive
        var rewriteDefault = resolution.rewriteDefault
        // LIVE migration floor (keys absent only): source the stored
        // default Scene LIVE id so an unmigrated device still lands
        // on a coherent starting view instead of bare All Scenes.
        if (!hasKeys) {
            val sceneDefaults = runCatching { ScenePrefsStore(prefs).load() }.getOrNull()
            val migrated = sceneDefaults?.activeSceneId.takeUnless { it.isNullOrBlank() }
            if (migrated != null) {
                resolution = resolution.copy(defaultCollectionId = migrated)
                rewriteDefault = true
                if (resolution.activeCollectionId == ALL_SCENES_COLLECTION_ID) {
                    resolution = resolution.copy(activeCollectionId = migrated)
                    rewriteActive = true
                }
            }
        }
        if (resolution.rewriteCollections || rewriteActive || rewriteDefault) {
            prefs.edit()
                .putString(KEY_COLLECTIONS, resolution.repairedEncoding)
                .putString(KEY_ACTIVE_COLLECTION, resolution.activeCollectionId)
                .putString(KEY_DEFAULT_COLLECTION, resolution.defaultCollectionId)
                .apply()
        }
        return LoadedCollectionState(
            collections = resolution.collections,
            activeCollectionId = resolution.activeCollectionId,
            defaultCollectionId = resolution.defaultCollectionId,
            migrated = !hasKeys
        )
    }

    /** Validates and persists the full collection state (see [load]). */
    fun save(
        collections: List<StreamSceneCollection>,
        activeCollectionId: String,
        defaultCollectionId: String,
        knownSceneIds: Set<String>
    ) {
        val resolved = SceneCollectionCodec.resolveCollectionSave(
            collections, activeCollectionId, defaultCollectionId, knownSceneIds
        )
        prefs.edit()
            .putString(KEY_COLLECTIONS, resolved.encoding)
            .putString(KEY_ACTIVE_COLLECTION, resolved.activeCollectionId)
            .putString(KEY_DEFAULT_COLLECTION, resolved.defaultCollectionId)
            .apply()
    }

    /**
     * Resets ONLY collection metadata (never scenes). Destroys no
     * scene records by construction — the destructors target only
     * the three collection keys.
     */
    fun reset() {
        prefs.edit()
            .remove(KEY_COLLECTIONS)
            .remove(KEY_ACTIVE_COLLECTION)
            .remove(KEY_DEFAULT_COLLECTION)
            .apply()
    }
}
