package com.jubersaleem.mobilegamingstreamer.scene

/**
 * H0 scene contracts: the smallest coherent metadata model for future scene
 * composition. Pure Kotlin; no Android, EGL, Surface or frame types appear
 * here or in any H0 public contract.
 *
 * Included, with the concrete H0 benefit of each:
 * - [SceneLayer]: stable layer identity, deterministic ordering key, placement
 *   policy and visibility flag — exactly what an H1 compositor must enumerate.
 * - [SceneComposition]: validated, immutably ordered layer collection, so
 *   invalid scenes (blank/duplicate ids, oversized lists) are unrepresentable
 *   downstream instead of failing at render time.
 * - [SceneCanvas] / [SceneLayoutPolicy] (see SceneGeometry.kt): the immutable
 *   output geometry and per-layer placement contract the compositor will
 *   feed to [SceneLayoutCalculator].
 *
 * Deliberately omitted at H0, and why:
 * - FrameProducer/FrameConsumer: no frame representation exists yet — pixels,
 *   textures and buffers all require Android/GL or allocation-heavy designs.
 *   Any H0 interface would be an empty marker or would leak future runtime
 *   types. H1 defines the handoff once EGL-context and SurfaceTexture
 *   ownership are resolved.
 * - Renderer, frame queues, scheduler: no real-time requirement exists at H0;
 *   premature concurrency would be speculative and untestable here.
 * - Plugin/source-type system: only one source type (screen) exists in the
 *   product; layers already carry an opaque [SceneLayer.sourceKey] so future
 *   camera/media/widget sources slot in without changing this contract.
 * - Mutation builders (add/remove/reorder): no caller needs them at H0; they
 *   arrive with the first feature that does.
 */
internal const val MAX_SCENE_LAYERS = 32

/**
 * Future visual layer, metadata only. [id] is the stable layer identity;
 * [sourceKey] is the opaque stable identity of the feeding source
 * (for example "screen") — opaque so future source kinds need no contract
 * change. Lower [zOrder] draws further back; equal [zOrder] values tie-break
 * by [id], so ordering is always total and deterministic. [enabled] = false
 * hides the layer without removing it.
 */
internal data class SceneLayer(
    val id: String,
    val sourceKey: String,
    val zOrder: Int = 0,
    val layoutPolicy: SceneLayoutPolicy = SceneLayoutPolicy.FIT,
    val enabled: Boolean = true
) {
    /** Blank ids or source keys are caller bugs; compositions reject them. */
    val isValid: Boolean get() = id.isNotBlank() && sourceKey.isNotBlank()
}

/**
 * Validated, immutably ordered layer collection. Draw order is back-to-front
 * (ascending [SceneLayer.zOrder], ties by ascending [SceneLayer.id]).
 * Empty is valid — it simply describes a scene with nothing in it.
 */
internal class SceneComposition private constructor(
    /** Back-to-front snapshot; mutating the input list later cannot affect it. */
    val layersInDrawOrder: List<SceneLayer>
) {
    val size: Int get() = layersInDrawOrder.size

    /** Enabled layers only, still back-to-front. */
    val visibleLayersInDrawOrder: List<SceneLayer>
        get() = layersInDrawOrder.filter { it.enabled }

    fun findById(id: String): SceneLayer? = layersInDrawOrder.firstOrNull { it.id == id }

    companion object {
        /**
         * Validates and orders [layers]. Returns null (never throws) when any
         * layer is invalid, any id repeats, or the list exceeds
         * [MAX_SCENE_LAYERS].
         */
        fun of(layers: List<SceneLayer>): SceneComposition? {
            if (layers.size > MAX_SCENE_LAYERS) return null
            if (layers.any { !it.isValid }) return null
            if (layers.map { it.id }.toSet().size != layers.size) return null
            return SceneComposition(
                layers.sortedWith(compareBy({ it.zOrder }, { it.id }))
            )
        }
    }
}
