package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H5 user-scene model + versioned text codec. Pure Kotlin: no Android,
 * threads, or I/O. The editor, the prefs store, the service delivery
 * path, and the JVM tests all share this contract.
 *
 * Repair policy (pinned by tests, not caller convention):
 * - Cosmetic faults are repaired: blank names become "Untitled scene",
 *   overlong names truncate to [MAX_SCENE_NAME_CHARS].
 * - Structural faults drop the entry: blank ids, blank/oversized layer
 *   text, unparseable numbers/anchors, out-of-range geometry, non-finite
 *   alpha, layers beyond the H4 cap. A scene whose header is corrupt
 *   decodes to null; a corrupt store entry is skipped.
 * - Decode never throws for any input (null included).
 * - List order is canonical for display; [StreamScene.normalizedZ]
 *   reassigns zOrder 1..N in list order after structural edits so the
 *   stored z values always agree with it.
 */
internal const val MAX_STORED_SCENES = 16
internal const val MAX_SCENE_NAME_CHARS = 40
internal const val MAX_LAYER_MARGIN_PX = 4096
internal const val MAX_LAYER_MAX_WIDTH_PX = 16384
internal const val MAX_LAYER_Z = 63
internal const val MAX_IMAGE_URI_CHARS = 2048
internal const val DEFAULT_SCENE_ID = "default"
internal const val DEFAULT_SCENE_NAME = "Starter"
internal const val DEFAULT_LAYER_ID = "text"
internal const val DEFAULT_LAYER_TEXT = "MGS • LIVE"
internal const val REPAIRED_SCENE_NAME = "Untitled scene"

/** Layer content kind. Public: carried by the public UI-held model. */
enum class SceneLayerKind {
    TEXT,
    IMAGE
}

/**
 * Syntactic image-URI gate shared by the codec and H4 validation:
 * non-blank, bounded, and on the scheme allowlist. Loadability is a
 * runtime property (checked at overlay build), never a pure one.
 */
internal fun isValidImageUri(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    if (uri.length > MAX_IMAGE_URI_CHARS) return false
    return uri.startsWith("content://", ignoreCase = true) ||
        uri.startsWith("file://", ignoreCase = true) ||
        uri.startsWith("android.resource://", ignoreCase = true)
}

/** One editable layer of a user scene (H5 text + H6 image). Public:
 * UI state crosses public composable boundaries; H4 validation stays
 * authoritative via [toOverlaySpec]. */
data class StreamSceneLayer(
    val id: String,
    val text: String,
    val anchor: SceneOverlayAnchor,
    val zOrder: Int,
    val marginPx: Int,
    val maxWidthPx: Int,
    val alpha: Float,
    val enabled: Boolean = true,
    val kind: SceneLayerKind = SceneLayerKind.TEXT,
    val imageUri: String? = null
) {
    /**
     * Validated H4 spec, or null when this layer cannot render. H4
     * validation stays authoritative: the editor and the service map
     * through this, never around it.
     */
    internal fun toOverlaySpec(): SceneOverlaySpec? = SceneOverlaySpec.of(
        id = id,
        text = text,
        anchor = anchor,
        zOrder = zOrder,
        marginPx = marginPx,
        maxWidthPx = maxWidthPx,
        alpha = alpha,
        enabled = enabled,
        kind = kind,
        imageUri = imageUri
    )
}

/** One named user scene: an ordered stack of text layers. Public: see [StreamSceneLayer]. */
data class StreamScene(
    val id: String,
    val name: String,
    val layers: List<StreamSceneLayer>
) {
    /** Display order: list order (kept z-normalized by the editor). */
    fun layersInDisplayOrder(): List<StreamSceneLayer> = layers

    /** Back-to-front draw order for the preview canvas. */
    fun layersInDrawOrder(): List<StreamSceneLayer> =
        layers.sortedWith(compareBy({ it.zOrder }, { it.id }))

    /** Reassigns zOrder 1..N in list order; call after structural edits. */
    fun normalizedZ(): StreamScene =
        copy(layers = layers.mapIndexed { index, layer -> layer.copy(zOrder = index + 1) })

    /**
     * Valid enabled H4 specs for streaming, in list order. Empty means
     * "no scene to stream" — the service then keeps the ScreenSource
     * default. Invalid layers are dropped (never crash, never half-spec).
     */
    internal fun streamableSpecs(): List<SceneOverlaySpec> =
        layers.filter { it.enabled }.mapNotNull { it.toOverlaySpec() }
}

/**
 * Versioned text encoding for scenes and the scene store. Format v2:
 * `scene:<id>\\t<name>` header line, then one `layer:` line per layer
 * with 10 tab-separated fields
 * (id, text, ANCHOR, z, margin, maxWidth, alpha, enabled, KIND,
 * imageUri); store blocks joined by a lone `===` line. Tabs, newlines,
 * and backslashes inside free text are backslash-escaped, so the
 * framing can never be confused by user content. Decoding accepts v1
 * (8-field text-only layers) and v2; encoding always writes v2.
 */
internal object SceneConfigCodec {
    const val VERSION = "v2"
    private const val VERSION_V1 = "v1"
    private const val SCENE_PREFIX = "scene:"
    private const val LAYER_PREFIX = "layer:"
    private const val BLOCK_SEPARATOR = "\n===\n"
    private const val MAX_Z = MAX_LAYER_Z

    fun defaultScene(): StreamScene = StreamScene(
        id = DEFAULT_SCENE_ID,
        name = DEFAULT_SCENE_NAME,
        layers = listOf(
            StreamSceneLayer(
                id = DEFAULT_LAYER_ID,
                text = DEFAULT_LAYER_TEXT,
                anchor = SceneOverlayAnchor.TOP_LEFT,
                zOrder = 1,
                marginPx = SceneTextLayerLayout.marginPxFor(1080),
                maxWidthPx = 1920,
                alpha = 1f,
                enabled = true
            )
        )
    )

    /** First unused `layer-N` id within [scene] (deterministic). */
    fun freshLayerId(scene: StreamScene): String {
        val used = scene.layers.map { it.id }.toSet()
        var n = scene.layers.size + 1
        while (used.contains("layer-$n")) n += 1
        return "layer-$n"
    }

    /** First unused `scene-N` id within [scenes] (deterministic). */
    fun freshSceneId(scenes: List<StreamScene>): String {
        val used = scenes.map { it.id }.toSet()
        var n = scenes.size + 1
        while (used.contains("scene-$n")) n += 1
        return "scene-$n"
    }

    /** First unused "Scene N" display name (deterministic). */
    fun freshSceneName(scenes: List<StreamScene>): String {
        val used = scenes.map { it.name }.toSet()
        var n = scenes.size + 1
        while (used.contains("Scene $n")) n += 1
        return "Scene $n"
    }

    /** Duplicate name that does not collide (`X (copy)`, `X (copy 2)`…). */
    fun duplicateName(scenes: List<StreamScene>, base: String): String {
        val used = scenes.map { it.name }.toSet()
        val stem = if (base.isBlank()) REPAIRED_SCENE_NAME else base.take(MAX_SCENE_NAME_CHARS)
        if (!used.contains("$stem (copy)")) return "$stem (copy)"
        var n = 2
        while (used.contains("$stem (copy $n)")) n += 1
        return "$stem (copy $n)"
    }

    fun encodeScene(scene: StreamScene): String {
        val lines = mutableListOf(SCENE_PREFIX + escape(scene.id) + "\t" + escape(repairedName(scene.name)))
        for (layer in scene.layers.take(MAX_STATIC_OVERLAY_LAYERS)) {
            lines.add(
                LAYER_PREFIX + escape(layer.id) + "\t" + escape(layer.text) + "\t" +
                    layer.anchor.name + "\t" + layer.zOrder + "\t" + layer.marginPx + "\t" +
                    layer.maxWidthPx + "\t" + layer.alpha + "\t" + layer.enabled + "\t" +
                    layer.kind.name + "\t" + escape(layer.imageUri ?: "")
            )
        }
        return VERSION + "\n" + lines.joinToString("\n")
    }

    fun decodeScene(encoded: String?): StreamScene? {
        if (encoded == null) return null
        val lines = encoded.split("\n")
        if (lines.isEmpty()) return null
        val legacy = lines[0] == VERSION_V1
        if (!legacy && lines[0] != VERSION) return null
        if (lines.size < 2 || !lines[1].startsWith(SCENE_PREFIX)) return null
        val header = lines[1].removePrefix(SCENE_PREFIX).split("\t")
        if (header.size != 2) return null
        val id = unescape(header[0]) ?: return null
        if (id.isBlank()) return null
        val name = repairedName(unescape(header[1]) ?: return null)
        val layers = mutableListOf<StreamSceneLayer>()
        val seenLayerIds = mutableSetOf<String>()
        for (line in lines.drop(2)) {
            if (!line.startsWith(LAYER_PREFIX)) continue // Forward-compatible: ignore unknown lines.
            val layer = decodeLayer(line.removePrefix(LAYER_PREFIX), legacy) ?: continue
            // Duplicate layer ids would fail H4 resolution; keep the first.
            if (!seenLayerIds.add(layer.id)) continue
            if (layers.size < MAX_STATIC_OVERLAY_LAYERS) layers.add(layer)
        }
        return StreamScene(id = id, name = name, layers = layers)
    }

    fun encodeStore(scenes: List<StreamScene>): String =
        scenes.take(MAX_STORED_SCENES).joinToString(BLOCK_SEPARATOR) { encodeScene(it) }

    fun decodeStore(encoded: String?): List<StreamScene> {
        if (encoded.isNullOrBlank()) return emptyList()
        val scenes = mutableListOf<StreamScene>()
        val seen = mutableSetOf<String>()
        for (block in encoded.split(BLOCK_SEPARATOR)) {
            val scene = decodeScene(block) ?: continue
            if (seen.add(scene.id)) scenes.add(scene)
            if (scenes.size >= MAX_STORED_SCENES) break
        }
        return scenes
    }

    /**
     * Valid enabled H4 specs from an encoded scene extra (service path).
     * Never throws, never partial-spec: empty means "stream the default".
     */
    fun streamableSpecs(encodedScene: String?): List<SceneOverlaySpec> =
        decodeScene(encodedScene)?.streamableSpecs().orEmpty()

    /**
     * Encoded active scene for the GO LIVE intent (activity path): the
     * active scene's encoding when it has at least one streamable layer,
     * else null (no seam activation — the ScreenSource default holds).
     */
    fun activeSceneEncoding(scenes: List<StreamScene>, activeSceneId: String?): String? {
        val active = scenes.firstOrNull { it.id == activeSceneId } ?: return null
        if (active.streamableSpecs().isEmpty()) return null
        return encodeScene(active)
    }

    /**
     * Pure load resolution: the [ScenePrefsStore] decision core, JVM-tested.
     * Returns valid scenes (never empty), the active id, the repaired
     * encoding, and which keys need rewriting. Mirrors the store's
     * self-heal rules exactly: corrupt entries are dropped, the default
     * scene seeds an empty store, an unknown active id falls back to the
     * first scene, and a rewrite happens only when the repaired form
     * differs from what is stored.
     */
    data class ResolvedStore(
        val scenes: List<StreamScene>,
        val activeSceneId: String,
        val repairedEncoding: String,
        val rewriteScenes: Boolean,
        val rewriteActive: Boolean
    )

    fun resolveStoredScenes(storedScenes: String?, storedActive: String?): ResolvedStore {
        var scenes = decodeStore(storedScenes)
        if (scenes.isEmpty()) scenes = listOf(defaultScene())
        var activeId = storedActive
        if (activeId == null || scenes.none { it.id == activeId }) activeId = scenes.first().id
        val repaired = encodeStore(scenes)
        val rewriteScenes = repaired != (storedScenes ?: "")
        return ResolvedStore(
            scenes = scenes,
            activeSceneId = activeId,
            repairedEncoding = repaired,
            rewriteScenes = rewriteScenes,
            rewriteActive = rewriteScenes || storedActive != activeId
        )
    }

    /** Pure save resolution: never-empty valid scenes + validated active id. */
    data class ResolvedSave(
        val scenes: List<StreamScene>,
        val activeSceneId: String,
        val encoding: String
    )

    fun resolveSceneSave(scenes: List<StreamScene>, activeSceneId: String): ResolvedSave {
        val valid = scenes.ifEmpty { listOf(defaultScene()) }
        val active = if (valid.any { it.id == activeSceneId }) activeSceneId else valid.first().id
        return ResolvedSave(valid, active, encodeStore(valid))
    }

    private fun decodeLayer(body: String, legacy: Boolean): StreamSceneLayer? {
        val fields = body.split("\t")
        if (fields.size != if (legacy) 8 else 10) return null
        val id = unescape(fields[0]) ?: return null
        if (id.isBlank()) return null
        val text = unescape(fields[1]) ?: return null
        val kind = if (legacy) {
            SceneLayerKind.TEXT
        } else {
            try {
                SceneLayerKind.valueOf(fields[8])
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        val imageUri = if (legacy) {
            null
        } else {
            val raw = unescape(fields[9]) ?: return null
            if (raw.isBlank()) null else raw
        }
        if (kind == SceneLayerKind.TEXT) {
            if (text.isBlank() || text.length > MAX_TEXT_LAYER_CHARS) return null
        } else {
            if (!isValidImageUri(imageUri)) return null
        }
        val anchor = SceneOverlayAnchor.values().firstOrNull { it.name == fields[2] } ?: return null
        val z = fields[3].toIntOrNull() ?: return null
        if (z < 0 || z > MAX_Z) return null
        val margin = fields[4].toIntOrNull() ?: return null
        if (margin < 0 || margin > MAX_LAYER_MARGIN_PX) return null
        val maxWidth = fields[5].toIntOrNull() ?: return null
        if (maxWidth <= 0 || maxWidth > MAX_LAYER_MAX_WIDTH_PX) return null
        val alpha = fields[6].toFloatOrNull() ?: return null
        if (!alpha.isFinite()) return null
        val enabled = when (fields[7]) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        return StreamSceneLayer(id, text, anchor, z, margin, maxWidth, alpha, enabled, kind, imageUri)
    }

    private fun repairedName(name: String): String {
        if (name.isBlank()) return REPAIRED_SCENE_NAME
        return name.take(MAX_SCENE_NAME_CHARS)
    }

    private fun escape(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun unescape(escaped: String): String? {
        val out = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val ch = escaped[i]
            if (ch != '\\') {
                out.append(ch)
                i += 1
                continue
            }
            if (i + 1 >= escaped.length) return null // Trailing backslash: corrupt.
            when (escaped[i + 1]) {
                '\\' -> out.append('\\')
                'n' -> out.append('\n')
                't' -> out.append('\t')
                else -> return null // Unknown escape: corrupt.
            }
            i += 2
        }
        return out.toString()
    }
}
