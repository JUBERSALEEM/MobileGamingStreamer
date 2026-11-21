package com.jubersaleem.mobilegamingstreamer

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jubersaleem.mobilegamingstreamer.scene.MAX_STATIC_OVERLAY_LAYERS
import com.jubersaleem.mobilegamingstreamer.scene.MAX_TEXT_LAYER_CHARS
import com.jubersaleem.mobilegamingstreamer.scene.SceneOverlayAnchor
import com.jubersaleem.mobilegamingstreamer.scene.SceneOverlayLayerInput
import com.jubersaleem.mobilegamingstreamer.scene.ScenePreviewBoxes
import com.jubersaleem.mobilegamingstreamer.scene.SceneSizePx
import com.jubersaleem.mobilegamingstreamer.scene.SceneTextLayerLayout
import com.jubersaleem.mobilegamingstreamer.scene.StreamScene
import com.jubersaleem.mobilegamingstreamer.scene.StreamSceneLayer
import kotlin.math.roundToInt

/** 1080p reference canvas for the editor preview (labeled in the UI). */
internal const val SCENE_PREVIEW_WIDTH_PX = 1920
internal const val SCENE_PREVIEW_HEIGHT_PX = 1080

/** UI slider bounds (the pure codec/H4 validation backstop stays authoritative). */
internal const val SCENE_LAYER_MAX_MARGIN_UI_PX = 256
internal const val SCENE_LAYER_MIN_MAX_WIDTH_UI_PX = 320
internal const val SCENE_LAYER_MAX_MAX_WIDTH_UI_PX = 1920

private val anchorLabels = mapOf(
    SceneOverlayAnchor.TOP_LEFT to "Top left",
    SceneOverlayAnchor.TOP_RIGHT to "Top right",
    SceneOverlayAnchor.BOTTOM_LEFT to "Bottom left",
    SceneOverlayAnchor.BOTTOM_RIGHT to "Bottom right",
    SceneOverlayAnchor.CENTER to "Center"
)

/**
 * H5 SCENES page: scene management (create/select/duplicate/delete/
 * rename) with an inline layer editor and a 16:9 composition preview.
 * Stateless layout: all state is activity-owned and pushed down; every
 * control maps to a real renderer capability (H4 stack).
 */
@Composable
fun SceneEditorPage(
    scenes: List<StreamScene>,
    activeSceneId: String?,
    editingSceneId: String?,
    sceneError: String?,
    onCreateScene: () -> Unit,
    onActiveScene: (String) -> Unit,
    onEditScene: (String?) -> Unit,
    onDuplicateScene: (String) -> Unit,
    onDeleteScene: (String) -> Unit,
    onRenameScene: (String, String) -> Unit,
    onAddLayer: (String) -> Unit,
    onRemoveLayer: (String, String) -> Unit,
    onMoveLayer: (String, String, Boolean) -> Unit,
    onToggleLayer: (String, String) -> Unit,
    onLayerText: (String, String, String) -> Unit,
    onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit,
    onLayerMargin: (String, String, Int) -> Unit,
    onLayerMaxWidth: (String, String, Int) -> Unit,
    onLayerAlpha: (String, String, Float) -> Unit,
    onDismissSceneError: () -> Unit
) {
    val p = LocalMgsPalette.current
    var expandedLayers by remember { mutableStateOf(setOf<String>()) }
    fun layerKey(sceneId: String, layerId: String) = "$sceneId/$layerId"
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ClayPageHeader(
            eyebrow = "Scenes",
            title = "Scene editor",
            subtitle = "Text overlays for your live stream. The active scene is used on your next GO LIVE."
        )
        if (sceneError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(p.warning.copy(alpha = 0.16f))
                    .border(1.dp, p.warning.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = sceneError,
                    color = p.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                ClayIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Dismiss message",
                    onClick = onDismissSceneError
                )
            }
        }
        ClayButton(
            label = "CREATE NEW SCENE",
            onClick = onCreateScene,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Add
        )
        scenes.forEach { scene ->
            SceneCard(
                scene = scene,
                isActive = scene.id == activeSceneId,
                isEditing = scene.id == editingSceneId,
                expandedLayers = expandedLayers,
                onToggleExpand = { key ->
                    expandedLayers = if (expandedLayers.contains(key)) {
                        expandedLayers - key
                    } else {
                        expandedLayers + key
                    }
                },
                layerKey = ::layerKey,
                onActiveScene = onActiveScene,
                onEditScene = onEditScene,
                onDuplicateScene = onDuplicateScene,
                onDeleteScene = onDeleteScene,
                onRenameScene = onRenameScene,
                onAddLayer = onAddLayer,
                onRemoveLayer = onRemoveLayer,
                onMoveLayer = onMoveLayer,
                onToggleLayer = onToggleLayer,
                onLayerText = onLayerText,
                onLayerAnchor = onLayerAnchor,
                onLayerMargin = onLayerMargin,
                onLayerMaxWidth = onLayerMaxWidth,
                onLayerAlpha = onLayerAlpha
            )
        }
        Text(
            text = "Scenes apply the next time you go live. The preview is a local 1080p-reference layout, not the live stream.",
            color = p.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun SceneCard(
    scene: StreamScene,
    isActive: Boolean,
    isEditing: Boolean,
    expandedLayers: Set<String>,
    onToggleExpand: (String) -> Unit,
    layerKey: (String, String) -> String,
    onActiveScene: (String) -> Unit,
    onEditScene: (String?) -> Unit,
    onDuplicateScene: (String) -> Unit,
    onDeleteScene: (String) -> Unit,
    onRenameScene: (String, String) -> Unit,
    onAddLayer: (String) -> Unit,
    onRemoveLayer: (String, String) -> Unit,
    onMoveLayer: (String, String, Boolean) -> Unit,
    onToggleLayer: (String, String) -> Unit,
    onLayerText: (String, String, String) -> Unit,
    onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit,
    onLayerMargin: (String, String, Int) -> Unit,
    onLayerMaxWidth: (String, String, Int) -> Unit,
    onLayerAlpha: (String, String, Float) -> Unit
) {
    val p = LocalMgsPalette.current
    val visibleCount = scene.layers.count { it.enabled }
    ClaySurface(shape = RoundedCornerShape(30.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    ClaySectionLabel(if (isActive) "Active scene" else "Scene")
                    if (isEditing) {
                        ClayTextField(
                            value = scene.name,
                            onValueChange = { onRenameScene(scene.id, it) },
                            label = "Scene name"
                        )
                    } else {
                        Text(
                            text = scene.name,
                            color = p.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Text(
                        text = "${scene.layers.size} layers • $visibleCount visible",
                        color = p.muted,
                        fontSize = 11.sp
                    )
                }
                if (isActive) {
                    ClayStatusPill(label = "ACTIVE", tone = ClayTone.Live)
                }
            }
            if (isEditing) {
                ScenePreviewCanvas(scene = scene)
                LayerListEditor(
                    scene = scene,
                    expandedLayers = expandedLayers,
                    onToggleExpand = onToggleExpand,
                    layerKey = layerKey,
                    onAddLayer = onAddLayer,
                    onRemoveLayer = onRemoveLayer,
                    onMoveLayer = onMoveLayer,
                    onToggleLayer = onToggleLayer,
                    onLayerText = onLayerText,
                    onLayerAnchor = onLayerAnchor,
                    onLayerMargin = onLayerMargin,
                    onLayerMaxWidth = onLayerMaxWidth,
                    onLayerAlpha = onLayerAlpha
                )
            }
            ClayButtonRow {
                if (!isActive) {
                    ClayButton(
                        label = "USE",
                        onClick = { onActiveScene(scene.id) },
                        modifier = Modifier.weight(1f),
                        style = ClayButtonStyle.Raised,
                        icon = Icons.Rounded.Check
                    )
                }
                ClayButton(
                    label = if (isEditing) "DONE" else "EDIT",
                    onClick = { onEditScene(if (isEditing) null else scene.id) },
                    modifier = Modifier.weight(1f),
                    style = if (isEditing) ClayButtonStyle.Filled else ClayButtonStyle.Raised,
                    icon = Icons.Rounded.Edit
                )
                ClayButton(
                    label = "COPY",
                    onClick = { onDuplicateScene(scene.id) },
                    modifier = Modifier.weight(1f),
                    style = ClayButtonStyle.Raised,
                    icon = Icons.Rounded.ContentCopy
                )
                ClayButton(
                    label = "DELETE",
                    onClick = { onDeleteScene(scene.id) },
                    modifier = Modifier.weight(1f),
                    style = ClayButtonStyle.Raised,
                    icon = Icons.Rounded.Delete
                )
            }
        }
    }
}

@Composable
private fun LayerListEditor(
    scene: StreamScene,
    expandedLayers: Set<String>,
    onToggleExpand: (String) -> Unit,
    layerKey: (String, String) -> String,
    onAddLayer: (String) -> Unit,
    onRemoveLayer: (String, String) -> Unit,
    onMoveLayer: (String, String, Boolean) -> Unit,
    onToggleLayer: (String, String) -> Unit,
    onLayerText: (String, String, String) -> Unit,
    onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit,
    onLayerMargin: (String, String, Int) -> Unit,
    onLayerMaxWidth: (String, String, Int) -> Unit,
    onLayerAlpha: (String, String, Float) -> Unit
) {
    val p = LocalMgsPalette.current
    val atCap = scene.layers.size >= MAX_STATIC_OVERLAY_LAYERS
    // Front-to-back display (top of the list draws last, like a layers
    // panel); each row carries its real z badge so the order is explicit.
    val frontFirst = scene.layersInDrawOrder().asReversed()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "LAYERS — TOP IS FRONT",
                color = p.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.1.sp,
                modifier = Modifier.weight(1f)
            )
            ClayButton(
                label = "ADD LAYER",
                onClick = { onAddLayer(scene.id) },
                enabled = !atCap,
                style = ClayButtonStyle.Raised,
                icon = Icons.Rounded.Add
            )
        }
        if (atCap) {
            Text(
                text = "Layer limit reached ($MAX_STATIC_OVERLAY_LAYERS per scene). Remove a layer to add another.",
                color = p.muted,
                fontSize = 11.sp
            )
        }
        if (frontFirst.isEmpty()) {
            Text(
                text = "No layers yet. Add a text layer to start composing.",
                color = p.muted,
                fontSize = 11.sp
            )
        }
        frontFirst.forEachIndexed { displayIndex, layer ->
            LayerRow(
                sceneId = scene.id,
                layer = layer,
                expanded = expandedLayers.contains(layerKey(scene.id, layer.id)),
                onToggleExpand = { onToggleExpand(layerKey(scene.id, layer.id)) },
                canMoveFront = displayIndex > 0,
                canMoveBack = displayIndex < frontFirst.size - 1,
                onRemoveLayer = onRemoveLayer,
                onMoveLayer = onMoveLayer,
                onToggleLayer = onToggleLayer,
                onLayerText = onLayerText,
                onLayerAnchor = onLayerAnchor,
                onLayerMargin = onLayerMargin,
                onLayerMaxWidth = onLayerMaxWidth,
                onLayerAlpha = onLayerAlpha
            )
        }
    }
}

@Composable
private fun LayerRow(
    sceneId: String,
    layer: StreamSceneLayer,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    canMoveFront: Boolean,
    canMoveBack: Boolean,
    onRemoveLayer: (String, String) -> Unit,
    onMoveLayer: (String, String, Boolean) -> Unit,
    onToggleLayer: (String, String) -> Unit,
    onLayerText: (String, String, String) -> Unit,
    onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit,
    onLayerMargin: (String, String, Int) -> Unit,
    onLayerMaxWidth: (String, String, Int) -> Unit,
    onLayerAlpha: (String, String, Float) -> Unit
) {
    val p = LocalMgsPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .then(clayInsetModifier(RoundedCornerShape(22.dp), rim = false))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ClayIconButton(
                icon = if (layer.enabled) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = if (layer.enabled) "Hide layer" else "Show layer",
                onClick = { onToggleLayer(sceneId, layer.id) }
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (layer.text.isBlank()) "(empty text)" else layer.text,
                    color = if (layer.enabled) p.text else p.muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "z${layer.zOrder} • ${anchorLabels.getValue(layer.anchor)}" +
                        if (layer.enabled) "" else " • hidden",
                    color = p.muted,
                    fontSize = 10.sp
                )
            }
            if (canMoveFront) {
                ClayIconButton(
                    icon = Icons.Rounded.ArrowUpward,
                    contentDescription = "Move toward front",
                    onClick = { onMoveLayer(sceneId, layer.id, true) }
                )
            }
            if (canMoveBack) {
                ClayIconButton(
                    icon = Icons.Rounded.ArrowDownward,
                    contentDescription = "Move toward back",
                    onClick = { onMoveLayer(sceneId, layer.id, false) }
                )
            }
            ClayIconButton(
                icon = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse layer controls" else "Expand layer controls",
                onClick = onToggleExpand
            )
            ClayIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "Remove layer",
                onClick = { onRemoveLayer(sceneId, layer.id) }
            )
        }
        if (expanded) {
            ClayTextField(
                value = layer.text,
                onValueChange = { onLayerText(sceneId, layer.id, it) },
                label = "Overlay text (max $MAX_TEXT_LAYER_CHARS chars)"
            )
            ClayOptionGroup(
                title = "Position",
                options = anchorLabels.values.toList(),
                selected = anchorLabels.getValue(layer.anchor),
                onSelect = { label ->
                    val anchor = anchorLabels.entries.first { it.value == label }.key
                    onLayerAnchor(sceneId, layer.id, anchor)
                }
            )
            LayerSlider(
                label = "Margin",
                valueLabel = "${layer.marginPx} px",
                value = layer.marginPx.toFloat(),
                range = 0f..SCENE_LAYER_MAX_MARGIN_UI_PX.toFloat(),
                steps = 31,
                onChange = { onLayerMargin(sceneId, layer.id, it.roundToInt()) }
            )
            LayerSlider(
                label = "Maximum width",
                valueLabel = "${layer.maxWidthPx} px",
                value = layer.maxWidthPx.toFloat().coerceIn(
                    SCENE_LAYER_MIN_MAX_WIDTH_UI_PX.toFloat(),
                    SCENE_LAYER_MAX_MAX_WIDTH_UI_PX.toFloat()
                ),
                range = SCENE_LAYER_MIN_MAX_WIDTH_UI_PX.toFloat()..
                    SCENE_LAYER_MAX_MAX_WIDTH_UI_PX.toFloat(),
                steps = 31,
                onChange = { onLayerMaxWidth(sceneId, layer.id, it.roundToInt()) }
            )
            LayerSlider(
                label = "Opacity",
                valueLabel = "${(layer.alpha * 100f).roundToInt()}%",
                value = layer.alpha.coerceIn(0f, 1f),
                range = 0f..1f,
                steps = 19,
                onChange = { onLayerAlpha(sceneId, layer.id, it) }
            )
        }
    }
}

@Composable
private fun LayerSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(
                text = label.uppercase(),
                color = p.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.1.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel,
                color = p.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

/**
 * Local 16:9 composition preview. Measures each layer's text with the
 * same `Paint` settings the renderer uses, places boxes with the real
 * H4 [ScenePreviewBoxes] math on a 1080p reference, and draws real
 * glyphs back-to-front with per-layer alpha. Disabled layers render as
 * ghost outlines (never streamed). Always labeled PREVIEW — NOT LIVE.
 */
@Composable
private fun ScenePreviewCanvas(scene: StreamScene) {
    val p = LocalMgsPalette.current
    val measurePaint = remember { Paint().apply { isAntiAlias = true } }
    val drawPaint = remember { Paint().apply { isAntiAlias = true } }
    val badgePaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER } }
    val textSize = SceneTextLayerLayout.textSizePxFor(SCENE_PREVIEW_HEIGHT_PX).toFloat()
    val measured = remember(scene) {
        measurePaint.textSize = textSize
        scene.layers.mapNotNull { layer ->
            val spec = layer.toOverlaySpec() ?: return@mapNotNull null
            val bounds = android.graphics.Rect()
            measurePaint.getTextBounds(layer.text, 0, layer.text.length, bounds)
            Triple(spec, SceneSizePx(bounds.width(), bounds.height()), layer.text)
        }
    }
    val enabledCount = scene.layers.count { it.enabled }
    val droppedCount = enabledCount - measured.count { it.first.enabled }
    val boxes = remember(measured) {
        ScenePreviewBoxes.layout(
            SCENE_PREVIEW_WIDTH_PX,
            SCENE_PREVIEW_HEIGHT_PX,
            measured.map { SceneOverlayLayerInput(it.first, it.second) }
        )
    }
    val textById = remember(measured) { measured.associate { it.first.id to it.third } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF101018))
                .border(1.dp, p.clayEdge.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
        ) {
            if (boxes == null) {
                Text(
                    text = "Preview unavailable: invalid composition. The stream would use the default source.",
                    color = p.warning,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            } else if (boxes.none { it.enabled }) {
                Text(
                    text = if (boxes.isEmpty()) "No layers to preview" else "All layers hidden",
                    color = p.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            if (boxes != null) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val scale = size.width / SCENE_PREVIEW_WIDTH_PX.toFloat()
                    for (box in boxes) {
                        val left = box.left * size.width
                        val top = box.top * size.height
                        val right = box.right * size.width
                        val bottom = box.bottom * size.height
                        val glyphs = textById[box.id].orEmpty()
                        if (box.enabled) {
                            drawRect(
                                color = Color.White.copy(alpha = 0.14f * box.alpha),
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top)
                            )
                            drawRect(
                                color = Color.White.copy(alpha = 0.65f * box.alpha),
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                style = Stroke(width = 2f)
                            )
                            drawPaint.color = android.graphics.Color.WHITE
                            drawPaint.alpha = (box.alpha * 255f).roundToInt().coerceIn(0, 255)
                            drawPaint.textSize = textSize * scale
                            drawContext.canvas.nativeCanvas.drawText(
                                glyphs, left, bottom - drawPaint.descent(), drawPaint
                            )
                        } else {
                            drawRect(
                                color = Color.White.copy(alpha = 0.25f),
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                style = Stroke(width = 2f)
                            )
                            badgePaint.color = android.graphics.Color.WHITE
                            badgePaint.alpha = 160
                            badgePaint.textSize = 22f * scale.coerceAtLeast(0.2f)
                            drawContext.canvas.nativeCanvas.drawText(
                                "OFF", (left + right) / 2f, (top + bottom) / 2f, badgePaint
                            )
                        }
                        badgePaint.color = android.graphics.Color.BLACK
                        badgePaint.alpha = 200
                        badgePaint.textSize = 20f * scale.coerceAtLeast(0.2f)
                        val badgeCx = (right - 26f * scale).coerceAtLeast(left + 12f * scale)
                        drawContext.canvas.nativeCanvas.drawText(
                            "z${box.zOrder}", badgeCx, top + 24f * scale, badgePaint
                        )
                    }
                }
            }
            Text(
                text = "PREVIEW — NOT LIVE",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.4.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        if (droppedCount > 0) {
            Text(
                text = "$droppedCount layer(s) hidden: empty or invalid text is never streamed.",
                color = p.warning,
                fontSize = 11.sp
            )
        }
        Text(
            text = "1080p-reference layout • top badge is draw order (z1 = back)",
            color = p.muted,
            fontSize = 10.sp
        )
    }
}
