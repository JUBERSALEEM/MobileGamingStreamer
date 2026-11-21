package com.jubersaleem.mobilegamingstreamer.scene

/**
 * K1 scene collections: pure model, membership rules, and store
 * format v2. Pure Kotlin: no Android, threads, or I/O.
 *
 * Core rule: scenes are GLOBAL entities; collections REFERENCE
 * scene ids. No operation here duplicates, edits, or deletes a
 * scene — membership lists only. The virtual All Scenes collection
 * (`ALL_SCENES_COLLECTION_ID`) is computed, never stored: it always
 * shows every known scene, so new scenes appear and deleted scenes
 * vanish automatically.
 *
 * Store format v2 is ADDITIVE: the `scenes_v1` key keeps the exact
 * v6 scene encoding (byte-identical — existing scene records are
 * never rewritten by migration), while new keys carry the
 * collections encoding plus the active/default collection ids.
 * Repair policy mirrors the scene store: cosmetic faults repaired
 * (blank names), structural faults dropped (blank/id-"all" ids,
 * dangling members, over-cap entries), decode never throws, and a
 * rewrite happens only when the repaired form differs (so load is
 * idempotent and migration is safe to repeat).
 */
internal const val MAX_STORED_COLLECTIONS = 8
internal const val MAX_COLLECTION_NAME_CHARS = 40
internal const val REPAIRED_COLLECTION_NAME = "Untitled collection"

/**
 * Reserved id of the virtual All Scenes collection. Never stored as
 * a custom collection id (decode drops it); always a valid
 * active/default selection (the safe fallback).
 */
internal const val ALL_SCENES_COLLECTION_ID = "all"

/**
 * One named custom collection: an ordered, de-duplicated reference
 * list over global scene ids. Public: UI state crosses public
 * composable boundaries like [StreamScene]; validation stays in
 * the codec ([SceneCollectionCodec.decodeCollections] +
 * [SceneCollectionCodec.resolveStoredCollections]).
 */
data class StreamSceneCollection(
    val id: String,
    val name: String,
    val sceneIds: List<String>
)

internal object SceneCollectionCodec {
    const val VERSION = "collections:v1"
    private const val COLLECTION_PREFIX = "collection:"

    /** First unused `collection-N` id (deterministic, never "all"). */
    fun freshCollectionId(collections: List<StreamSceneCollection>): String {
        val used = collections.map { it.id }.toSet()
        var n = collections.size + 1
        while (used.contains("collection-$n")) n += 1
        return "collection-$n"
    }

    /** First unused "Collection N" display name (deterministic). */
    fun freshCollectionName(collections: List<StreamSceneCollection>): String {
        val used = collections.map { it.name }.toSet()
        var n = collections.size + 1
        while (used.contains("Collection $n")) n += 1
        return "Collection $n"
    }

    /** Blank → repaired; overlong → truncated (scene-store discipline). */
    fun repairedName(name: String): String {
        if (name.isBlank()) return REPAIRED_COLLECTION_NAME
        return name.take(MAX_COLLECTION_NAME_CHARS)
    }

    /**
     * Creates a collection with a fresh id + repaired name, or null
     * at the cap (the caller surfaces the limit, like scenes).
     */
    fun createCollection(
        collections: List<StreamSceneCollection>,
        name: String
    ): List<StreamSceneCollection>? {
        if (collections.size >= MAX_STORED_COLLECTIONS) return null
        return collections + StreamSceneCollection(
            id = freshCollectionId(collections),
            name = repairedName(name),
            sceneIds = emptyList()
        )
    }

    fun encodeCollections(collections: List<StreamSceneCollection>): String {
        val lines = mutableListOf(VERSION)
        for (collection in collections.take(MAX_STORED_COLLECTIONS)) {
            // The reserved id can never be encoded as a custom entry.
            if (collection.id.isBlank() || collection.id == ALL_SCENES_COLLECTION_ID) continue
            val fields = mutableListOf(
                SceneConfigCodec.escape(collection.id),
                SceneConfigCodec.escape(repairedName(collection.name))
            )
            // Ordered members, de-duplicated (first wins); blanks and
            // the reserved id can never be member references.
            val seen = mutableSetOf<String>()
            for (member in collection.sceneIds) {
                if (member.isBlank() || member == ALL_SCENES_COLLECTION_ID) continue
                if (seen.add(member)) fields.add(SceneConfigCodec.escape(member))
            }
            lines.add(COLLECTION_PREFIX + fields.joinToString("\t"))
        }
        return lines.joinToString("\n")
    }

    /**
     * Structural decode: valid custom collections (unknown version →
     * none — scenes stay safe; unknown lines ignored; duplicate ids
     * keep-first; reserved "all" never decodes as custom). Dangling
     * member refs survive here and are pruned by
     * [resolveStoredCollections], which knows the scene ids.
     */
    fun decodeCollections(encoded: String?): List<StreamSceneCollection> {
        if (encoded == null) return emptyList()
        val lines = encoded.split("\n")
        if (lines.isEmpty() || lines[0] != VERSION) return emptyList()
        val collections = mutableListOf<StreamSceneCollection>()
        val seenIds = mutableSetOf<String>()
        for (line in lines.drop(1)) {
            if (!line.startsWith(COLLECTION_PREFIX)) continue
            val fields = line.removePrefix(COLLECTION_PREFIX).split("\t")
            if (fields.size < 2) continue
            val id = SceneConfigCodec.unescape(fields[0]) ?: continue
            if (id.isBlank() || id == ALL_SCENES_COLLECTION_ID) continue
            if (!seenIds.add(id)) continue
            val name = repairedName(SceneConfigCodec.unescape(fields[1]) ?: continue)
            val members = mutableListOf<String>()
            val seenMembers = mutableSetOf<String>()
            for (raw in fields.drop(2)) {
                val member = SceneConfigCodec.unescape(raw) ?: continue
                if (member.isBlank() || member == ALL_SCENES_COLLECTION_ID) continue
                if (seenMembers.add(member)) members.add(member)
            }
            collections.add(StreamSceneCollection(id, name, members))
            if (collections.size >= MAX_STORED_COLLECTIONS) break
        }
        return collections
    }

    /** Pure load resolution: the prefs-store decision core, JVM-tested. */
    data class ResolvedCollectionStore(
        val collections: List<StreamSceneCollection>,
        val activeCollectionId: String,
        val defaultCollectionId: String,
        val repairedEncoding: String,
        val rewriteCollections: Boolean,
        val rewriteActive: Boolean,
        val rewriteDefault: Boolean
    )

    /**
     * Resolves stored collection metadata against [knownSceneIds]:
     * dangling members pruned, active/default fallen back (stored
     * active → stored default → All Scenes). Never throws, never
     * empty of a valid selection (All Scenes always stands).
     */
    fun resolveStoredCollections(
        storedCollections: String?,
        storedActive: String?,
        storedDefault: String?,
        knownSceneIds: Set<String>
    ): ResolvedCollectionStore {
        val decoded = decodeCollections(storedCollections)
            .map { it.copy(sceneIds = it.sceneIds.filter { id -> knownSceneIds.contains(id) }) }
        val active = resolveActiveCollection(storedActive, storedDefault, decoded)
        val default = if (isKnownCollection(storedDefault, decoded)) storedDefault!!
            else ALL_SCENES_COLLECTION_ID
        val repaired = encodeCollections(decoded)
        val rewriteCollections = repaired != (storedCollections ?: "")
        return ResolvedCollectionStore(
            collections = decoded,
            activeCollectionId = active,
            defaultCollectionId = default,
            repairedEncoding = repaired,
            rewriteCollections = rewriteCollections,
            rewriteActive = rewriteCollections || storedActive != active,
            rewriteDefault = rewriteCollections || storedDefault != default
        )
    }

    /** Pure save resolution: validated customs + validated selections. */
    data class ResolvedCollectionSave(
        val collections: List<StreamSceneCollection>,
        val activeCollectionId: String,
        val defaultCollectionId: String,
        val encoding: String
    )

    fun resolveCollectionSave(
        collections: List<StreamSceneCollection>,
        activeCollectionId: String,
        defaultCollectionId: String,
        knownSceneIds: Set<String>
    ): ResolvedCollectionSave {
        // Round-trip through the codec so saves are always canonical
        // (repaired names, de-duplicated/pruned members, capped).
        val canonical = decodeCollections(encodeCollections(collections))
            .map { it.copy(sceneIds = it.sceneIds.filter { id -> knownSceneIds.contains(id) }) }
        val active = resolveActiveCollection(activeCollectionId, defaultCollectionId, canonical)
        val default = if (isKnownCollection(defaultCollectionId, canonical)) defaultCollectionId
            else ALL_SCENES_COLLECTION_ID
        return ResolvedCollectionSave(canonical, active, default, encodeCollections(canonical))
    }

    /** True for All Scenes or a stored custom id (never throws). */
    fun isKnownCollection(id: String?, collections: List<StreamSceneCollection>): Boolean {
        if (id == null) return false
        if (id == ALL_SCENES_COLLECTION_ID) return true
        return collections.any { it.id == id }
    }

    /**
     * Active-selection fallback chain: stored active when known,
     * else stored default when known, else All Scenes. Collection
     * switching only changes the viewed set — never the live scene
     * (the activity performs no stream operation here).
     */
    fun resolveActiveCollection(
        activeId: String?,
        defaultId: String?,
        collections: List<StreamSceneCollection>
    ): String {
        if (isKnownCollection(activeId, collections)) return activeId!!
        if (isKnownCollection(defaultId, collections)) return defaultId!!
        return ALL_SCENES_COLLECTION_ID
    }

    /**
     * Scenes visible under [collectionId]: All Scenes shows every
     * scene in store order; a custom collection shows its members in
     * member order (unknown ids skipped — never crash, never holes).
     * Unknown collection ids show All Scenes (safe fallback).
     */
    fun scenesInCollection(
        collectionId: String?,
        scenes: List<StreamScene>,
        collections: List<StreamSceneCollection>
    ): List<StreamScene> {
        if (collectionId == null || collectionId == ALL_SCENES_COLLECTION_ID) return scenes
        val collection = collections.firstOrNull { it.id == collectionId } ?: return scenes
        val byId = scenes.associateBy { it.id }
        return collection.sceneIds.mapNotNull { byId[it] }
    }

    /**
     * Adds [sceneId] to a custom collection: unknown collections,
     * the virtual All Scenes, unknown scenes, and duplicates are all
     * no-ops (never throw, never duplicate). Scenes untouched.
     */
    fun addSceneToCollection(
        collections: List<StreamSceneCollection>,
        collectionId: String,
        sceneId: String,
        knownSceneIds: Set<String>
    ): List<StreamSceneCollection> {
        if (collectionId == ALL_SCENES_COLLECTION_ID) return collections
        if (!knownSceneIds.contains(sceneId)) return collections
        return collections.map { collection ->
            if (collection.id != collectionId) return@map collection
            if (collection.sceneIds.contains(sceneId)) return@map collection
            collection.copy(sceneIds = collection.sceneIds + sceneId)
        }
    }

    /** Removes [sceneId] from one custom collection (no-op if absent). */
    fun removeSceneFromCollection(
        collections: List<StreamSceneCollection>,
        collectionId: String,
        sceneId: String
    ): List<StreamSceneCollection> {
        if (collectionId == ALL_SCENES_COLLECTION_ID) return collections
        return collections.map { collection ->
            if (collection.id != collectionId) return@map collection
            collection.copy(sceneIds = collection.sceneIds.filterNot { it == sceneId })
        }
    }

    /** Drops [sceneId] from every custom collection (scene deletion). */
    fun pruneScene(
        collections: List<StreamSceneCollection>,
        sceneId: String
    ): List<StreamSceneCollection> {
        if (collections.none { it.sceneIds.contains(sceneId) }) return collections
        return collections.map { collection ->
            collection.copy(sceneIds = collection.sceneIds.filterNot { it == sceneId })
        }
    }
}
