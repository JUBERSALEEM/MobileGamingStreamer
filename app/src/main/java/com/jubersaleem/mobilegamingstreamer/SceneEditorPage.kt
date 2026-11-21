package com.jubersaleem.mobilegamingstreamer

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jubersaleem.mobilegamingstreamer.scene.MAX_STATIC_OVERLAY_LAYERS
import com.jubersaleem.mobilegamingstreamer.scene.MAX_TEXT_LAYER_CHARS
import com.jubersaleem.mobilegamingstreamer.scene.BORDER_COLOR_DEFAULT
import com.jubersaleem.mobilegamingstreamer.scene.SHADOW_COLOR_DEFAULT
import com.jubersaleem.mobilegamingstreamer.scene.TEXT_COLOR_DEFAULT
import com.jubersaleem.mobilegamingstreamer.scene.SceneImageLayer
import com.jubersaleem.mobilegamingstreamer.scene.SceneCanvasProfile
import com.jubersaleem.mobilegamingstreamer.scene.SceneClockWidgets
import com.jubersaleem.mobilegamingstreamer.scene.SceneDesignOrientation
import com.jubersaleem.mobilegamingstreamer.scene.SceneImageLoader
import com.jubersaleem.mobilegamingstreamer.scene.SceneLayerKind
import com.jubersaleem.mobilegamingstreamer.scene.SceneLayerStyle
import com.jubersaleem.mobilegamingstreamer.scene.SceneOverlayAnchor
import com.jubersaleem.mobilegamingstreamer.scene.SceneOverlayLayerInput
import com.jubersaleem.mobilegamingstreamer.scene.SceneScaleMode
import com.jubersaleem.mobilegamingstreamer.scene.ScenePreviewBoxes
import com.jubersaleem.mobilegamingstreamer.scene.SceneSizePx
import com.jubersaleem.mobilegamingstreamer.scene.SceneTextLayerLayout
import com.jubersaleem.mobilegamingstreamer.scene.StreamScene
import com.jubersaleem.mobilegamingstreamer.scene.StreamSceneLayer
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** UI slider bounds (the pure codec/H4 validation backstop stays authoritative). */
internal const val SCENE_IMAGE_DEFAULT_MAX_WIDTH_PX = 640
internal const val SCENE_BORDER_MAX_UI_PX = 64
internal const val SCENE_RADIUS_MAX_UI_PX = 64
internal const val SCENE_SHADOW_MAX_UI_PX = 32
internal const val SCENE_LAYER_MAX_MARGIN_UI_PX = 256
internal const val SCENE_LAYER_MIN_MAX_WIDTH_UI_PX = 320
internal const val SCENE_LAYER_MAX_MAX_WIDTH_UI_PX = 1920

private val textColorSwatches = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF5252.toInt(), 0xFFFF9800.toInt(),
    0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(), 0xFF00BCD4.toInt(), 0xFF448AFF.toInt()
)

private val backgroundSwatches: List<Int?> = listOf(
    null, 0xCC000000.toInt(), 0xB3FFFFFF.toInt(), 0xCCFF5252.toInt(),
    0xCC448AFF.toInt(), 0xCC4CAF50.toInt()
)

private val borderColorSwatches = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF5252.toInt(), 0xFFFF9800.toInt(),
    0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(), 0xFF00BCD4.toInt(), 0xFF448AFF.toInt()
)

private val shadowColorSwatches = listOf(
    0x99000000.toInt(), 0x66000000.toInt(), 0x66FFFFFF.toInt(), 0x99FF5252.toInt()
)

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
    onSceneOrientation: (String, SceneDesignOrientation) -> Unit,
    streamOrientation: String,
    onAddLayer: (String) -> Unit,
    onAddImageLayer: (String) -> Unit,
    onAddClockLayer: (String) -> Unit,
    onAddTimerLayer: (String) -> Unit,
    onRemoveLayer: (String, String) -> Unit,
    onReplaceImageLayer: (String, String) -> Unit,
    onMoveLayer: (String, String, Boolean) -> Unit,
    onToggleLayer: (String, String) -> Unit,
    onLayerText: (String, String, String) -> Unit,
    onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit,
    onLayerMargin: (String, String, Int) -> Unit,
    onLayerMaxWidth: (String, String, Int) -> Unit,
    onLayerAlpha: (String, String, Float) -> Unit,
    onLayerScaleMode: (String, String, SceneScaleMode) -> Unit,
    onLayerTextColor: (String, String, Int) -> Unit,
    onLayerBackground: (String, String, Int?) -> Unit,
    onLayerBorderWidth: (String, String, Int) -> Unit,
    onLayerBorderColor: (String, String, Int) -> Unit,
    onLayerCornerRadius: (String, String, Int) -> Unit,
    onLayerShadowOffset: (String, String, Int) -> Unit,
    onLayerShadowColor: (String, String, Int) -> Unit,
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
                onSceneOrientation = onSceneOrientation,
                streamOrientation = streamOrientation,
                onAddLayer = onAddLayer,
                onAddImageLayer = onAddImageLayer,
                onAddClockLayer = onAddClockLayer,
                onAddTimerLayer = onAddTimerLayer,
                onRemoveLayer = onRemoveLayer,
                onReplaceImageLayer = onReplaceImageLayer,
                onMoveLayer = onMoveLayer,
                onToggleLayer = onToggleLayer,
                onLayerText = onLayerText,
                onLayerAnchor = onLayerAnchor,
                onLayerMargin = onLayerMargin,
                onLayerMaxWidth = onLayerMaxWidth,
                onLayerAlpha = onLayerAlpha,
                onLayerScaleMode = onLayerScaleMode,
                onLayerTextColor = onLayerTextColor,
                onLayerBackground = onLayerBackground,
                onLayerBorderWidth = onLayerBorderWidth,
                onLayerBorderColor = onLayerBorderColor,
                onLayerCornerRadius = onLayerCornerRadius,
                onLayerShadowOffset = onLayerShadowOffset,
                onLayerShadowColor = onLayerShadowColor,
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
    onSceneOrientation: (String, SceneDesignOrientation) -> Unit,
    streamOrientation: String,
    onAddLayer: (String) -> Unit,
    onAddImageLayer: (String) -> Unit,
    onAddClockLayer: (String) -> Unit,
    onAddTimerLayer: (String) -> Unit,
    onRemoveLayer: (String, String) -> Unit,
    onReplaceImageLayer: (String, String) -> Unit,
    onMoveLayer: (String, String, Boolean) -> Unit,
    onToggleLayer: (String, String) -> Unit,
    onLayerText: (String, String, String) -> Unit,
    onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit,
    onLayerMargin: (String, String, Int) -> Unit,
    onLayerMaxWidth: (String, String, Int) -> Unit,
    onLayerAlpha: (String, String, Float) -> Unit,
    onLayerScaleMode: (String, String, SceneScaleMode) -> Unit,
    onLayerTextColor: (String, String, Int) -> Unit,
    onLayerBackground: (String, String, Int?) -> Unit,
    onLayerBorderWidth: (String, String, Int) -> Unit,
    onLayerBorderColor: (String, String, Int) -> Unit,
    onLayerCornerRadius: (String, String, Int) -> Unit,
    onLayerShadowOffset: (String, String, Int) -> Unit,
    onLayerShadowColor: (String, String, Int) -> Unit,
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
                        text = "${scene.layers.size} layers • $visibleCount visible • " +
                            if (scene.orientation == SceneDesignOrientation.PORTRAIT) "Portrait" else "Landscape",
                        color = p.muted,
                        fontSize = 11.sp
                    )
                }
                if (isActive) {
                    ClayStatusPill(label = "ACTIVE", tone = ClayTone.Live)
                }
            }
            if (isEditing) {
                ClayOptionGroup(
                    title = "Canvas",
                    options = listOf("Landscape", "Portrait"),
                    selected = if (scene.orientation == SceneDesignOrientation.PORTRAIT) "Portrait" else "Landscape",
                    onSelect = { label ->
                        onSceneOrientation(
                            scene.id,
                            if (label == "Portrait") SceneDesignOrientation.PORTRAIT
                            else SceneDesignOrientation.LANDSCAPE
                        )
                    }
                )
                ScenePreviewCanvas(scene = scene, streamOrientation = streamOrientation)
                LayerListEditor(
                    scene = scene,
                    expandedLayers = expandedLayers,
                    onToggleExpand = onToggleExpand,
                    layerKey = layerKey,
                    onAddLayer = onAddLayer,
                    onAddImageLayer = onAddImageLayer,
                    onAddClockLayer = onAddClockLayer,
                    onAddTimerLayer = onAddTimerLayer,
                    onRemoveLayer = onRemoveLayer,
                    onReplaceImageLayer = onReplaceImageLayer,
                    onMoveLayer = onMoveLayer,
                    onToggleLayer = onToggleLayer,
                    onLayerText = onLayerText,
                    onLayerAnchor = onLayerAnchor,
                    onLayerMargin = onLayerMargin,
                    onLayerMaxWidth = onLayerMaxWidth,
                    onLayerAlpha = onLayerAlpha,
                    onLayerScaleMode = onLayerScaleMode,
                    onLayerTextColor = onLayerTextColor,
                    onLayerBackground = onLayerBackground,
                    onLayerBorderWidth = onLayerBorderWidth,
                    onLayerBorderColor = onLayerBorderColor,
                    onLayerCornerRadius = onLayerCornerRadius,
                    onLayerShadowOffset = onLayerShadowOffset,
                    onLayerShadowColor = onLayerShadowColor,
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
    onAddImageLayer: (String) -> Unit,
    onAddClockLayer: (String) -> Unit,
    onAddTimerLayer: (String) -> Unit,
    onRemoveLayer: (String, String) -> Unit,
    onReplaceImageLayer: (String, String) -> Unit,
    onMoveLayer: (String, String, Boolean) -> Unit,
    onToggleLayer: (String, String) -> Unit,
    onLayerText: (String, String, String) -> Unit,
    onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit,
    onLayerMargin: (String, String, Int) -> Unit,
    onLayerMaxWidth: (String, String, Int) -> Unit,
    onLayerAlpha: (String, String, Float) -> Unit,
    onLayerScaleMode: (String, String, SceneScaleMode) -> Unit,
    onLayerTextColor: (String, String, Int) -> Unit,
    onLayerBackground: (String, String, Int?) -> Unit,
    onLayerBorderWidth: (String, String, Int) -> Unit,
    onLayerBorderColor: (String, String, Int) -> Unit,
    onLayerCornerRadius: (String, String, Int) -> Unit,
    onLayerShadowOffset: (String, String, Int) -> Unit,
    onLayerShadowColor: (String, String, Int) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClayButton(
                    label = "ADD TEXT",
                    onClick = { onAddLayer(scene.id) },
                    enabled = !atCap,
                    style = ClayButtonStyle.Raised,
                    icon = Icons.Rounded.Add
                )
                ClayButton(
                    label = "ADD IMAGE",
                    onClick = { onAddImageLayer(scene.id) },
                    enabled = !atCap,
                    style = ClayButtonStyle.Raised,
                    icon = Icons.Rounded.Add
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClayButton(
                label = "ADD CLOCK",
                onClick = { onAddClockLayer(scene.id) },
                enabled = !atCap,
                style = ClayButtonStyle.Raised,
                icon = Icons.Rounded.Add
            )
            ClayButton(
                label = "ADD TIMER",
                onClick = { onAddTimerLayer(scene.id) },
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
                onReplaceImageLayer = onReplaceImageLayer,
                onMoveLayer = onMoveLayer,
                onToggleLayer = onToggleLayer,
                onLayerText = onLayerText,
                onLayerAnchor = onLayerAnchor,
                onLayerMargin = onLayerMargin,
                onLayerMaxWidth = onLayerMaxWidth,
                onLayerAlpha = onLayerAlpha,
                onLayerScaleMode = onLayerScaleMode,
                onLayerTextColor = onLayerTextColor,
                onLayerBackground = onLayerBackground,
                onLayerBorderWidth = onLayerBorderWidth,
                onLayerBorderColor = onLayerBorderColor,
                onLayerCornerRadius = onLayerCornerRadius,
                onLayerShadowOffset = onLayerShadowOffset,
                onLayerShadowColor = onLayerShadowColor,
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
    onReplaceImageLayer: (String, String) -> Unit,
    onMoveLayer: (String, String, Boolean) -> Unit,
    onToggleLayer: (String, String) -> Unit,
    onLayerText: (String, String, String) -> Unit,
    onLayerAnchor: (String, String, SceneOverlayAnchor) -> Unit,
    onLayerMargin: (String, String, Int) -> Unit,
    onLayerMaxWidth: (String, String, Int) -> Unit,
    onLayerAlpha: (String, String, Float) -> Unit,
    onLayerScaleMode: (String, String, SceneScaleMode) -> Unit,
    onLayerTextColor: (String, String, Int) -> Unit,
    onLayerBackground: (String, String, Int?) -> Unit,
    onLayerBorderWidth: (String, String, Int) -> Unit,
    onLayerBorderColor: (String, String, Int) -> Unit,
    onLayerCornerRadius: (String, String, Int) -> Unit,
    onLayerShadowOffset: (String, String, Int) -> Unit,
    onLayerShadowColor: (String, String, Int) -> Unit,
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
                    text = if (layer.kind == SceneLayerKind.IMAGE) "Image layer"
                    else if (layer.kind == SceneLayerKind.CLOCK) "Clock widget"
                    else if (layer.kind == SceneLayerKind.TIMER) "Stream timer"
                    else if (layer.text.isBlank()) "(empty text)" else layer.text,
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
            if (layer.kind == SceneLayerKind.IMAGE) {
                ImageLayerThumbnail(
                    imageUri = layer.imageUri,
                    onReplace = { onReplaceImageLayer(sceneId, layer.id) }
                )
                ScaleModeRow(
                    selected = layer.scaleMode,
                    onSelect = { onLayerScaleMode(sceneId, layer.id, it) }
                )
            } else if (layer.kind == SceneLayerKind.CLOCK || layer.kind == SceneLayerKind.TIMER) {
                Text(
                    text = if (layer.kind == SceneLayerKind.CLOCK) "Shows the current time (HH:MM)."
                    else "Counts up from stream start (HH:MM:SS).",
                    color = p.muted,
                    fontSize = 11.sp
                )
            } else {
                ClayTextField(
                    value = layer.text,
                    onValueChange = { onLayerText(sceneId, layer.id, it) },
                    label = "Overlay text (max $MAX_TEXT_LAYER_CHARS chars)"
                )
            }
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
                // Stored layers may carry codec-valid margins above the UI
                // range (foreign/corrupt prefs); coerce like the siblings.
                value = layer.marginPx.toFloat().coerceIn(0f, SCENE_LAYER_MAX_MARGIN_UI_PX.toFloat()),
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
            if (layer.kind != SceneLayerKind.IMAGE) {
                StyleSwatchRow(
                    title = "Text color",
                    options = textColorSwatches,
                    selected = layer.textColor,
                    onSelect = { onLayerTextColor(sceneId, layer.id, it ?: TEXT_COLOR_DEFAULT) }
                )
            }
            StyleSwatchRow(
                title = "Background",
                options = backgroundSwatches,
                selected = layer.backgroundColor,
                onSelect = { onLayerBackground(sceneId, layer.id, it) }
            )
            LayerSlider(
                label = "Border width",
                valueLabel = "${layer.borderWidthPx} px",
                value = layer.borderWidthPx.toFloat().coerceIn(0f, SCENE_BORDER_MAX_UI_PX.toFloat()),
                range = 0f..SCENE_BORDER_MAX_UI_PX.toFloat(),
                steps = 15,
                onChange = { onLayerBorderWidth(sceneId, layer.id, it.roundToInt()) }
            )
            StyleSwatchRow(
                title = "Border color",
                options = borderColorSwatches,
                selected = layer.borderColor,
                onSelect = { onLayerBorderColor(sceneId, layer.id, it ?: BORDER_COLOR_DEFAULT) }
            )
            LayerSlider(
                label = "Corner radius",
                valueLabel = "${layer.cornerRadiusPx} px",
                value = layer.cornerRadiusPx.toFloat().coerceIn(0f, SCENE_RADIUS_MAX_UI_PX.toFloat()),
                range = 0f..SCENE_RADIUS_MAX_UI_PX.toFloat(),
                steps = 15,
                onChange = { onLayerCornerRadius(sceneId, layer.id, it.roundToInt()) }
            )
            LayerSlider(
                label = "Shadow offset",
                valueLabel = "${layer.shadowOffsetPx} px",
                value = layer.shadowOffsetPx.toFloat().coerceIn(0f, SCENE_SHADOW_MAX_UI_PX.toFloat()),
                range = 0f..SCENE_SHADOW_MAX_UI_PX.toFloat(),
                steps = 15,
                onChange = { onLayerShadowOffset(sceneId, layer.id, it.roundToInt()) }
            )
            StyleSwatchRow(
                title = "Shadow color",
                options = shadowColorSwatches,
                selected = layer.shadowColor,
                onSelect = { onLayerShadowColor(sceneId, layer.id, it ?: SHADOW_COLOR_DEFAULT) }
            )
        }
    }
}

/**
 * J1 FIT/FILL selector. FIT is the default (H6 aspect-fit);
 * FILL cover-crops to the full max-width frame. Surfaced only
 * for IMAGE layers, only where the renderer honors it.
 */
@Composable
private fun ScaleModeRow(selected: SceneScaleMode, onSelect: (SceneScaleMode) -> Unit) {
    val p = LocalMgsPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "Scale", color = p.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        SceneScaleMode.entries.forEach { mode ->
            ClayButton(
                label = mode.name,
                onClick = { onSelect(mode) },
                style = if (mode == selected) ClayButtonStyle.Pressed else ClayButtonStyle.Raised
            )
        }
    }
}

@Composable
private fun ImageLayerThumbnail(imageUri: String?, onReplace: () -> Unit) {
    val p = LocalMgsPalette.current
    val context = LocalContext.current
    val thumb = remember(imageUri) {
        if (imageUri == null) null
        else SceneImageLoader.loadThumbnailBitmap(context.contentResolver, imageUri, 256)?.asImageBitmap()
    }
    // J1 honest asset line: real dimensions + byte size, or the
    // reason when the asset cannot be inspected. Reads are
    // guarded — a revoked/renamed document reports, never throws.
    val assetLine = remember(imageUri) {
        if (imageUri == null) "No image selected"
        else assetLineFor(context, imageUri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = assetLine, color = p.muted, fontSize = 11.sp)
        Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = "Image layer thumbnail",
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, p.clayEdge.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.warning.copy(alpha = 0.14f))
                    .border(1.dp, p.warning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Image unavailable",
                    color = p.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        ClayButton(
            label = "REPLACE",
            onClick = onReplace,
            style = ClayButtonStyle.Raised,
            icon = Icons.Rounded.Edit
        )
        }
    }
}

/**
 * J1 asset inspection line: "<W> x <H> - <size>" from a
 * bounds-only decode plus the document byte size. Every failure
 * mode returns a plain-English reason instead of throwing.
 */
private fun assetLineFor(context: android.content.Context, imageUri: String): String {
    val resolver = context.contentResolver
    val uri = try {
        android.net.Uri.parse(imageUri)
    } catch (_: Exception) {
        return "Unreadable image URI"
    } ?: return "Unreadable image URI"
    val dims = try {
        SceneImageLoader.decodeBounds(resolver, imageUri)
    } catch (_: Exception) {
        null
    } ?: return "Image unavailable (moved, renamed, or permission revoked)"
    val bytes = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    } catch (_: Exception) {
        null
    } ?: return "${dims.widthPx} x ${dims.heightPx} px - size unknown"
    return "${dims.widthPx} x ${dims.heightPx} px - ${formatBytes(bytes)}"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
}

@Composable
private fun StyleSwatchRow(
    title: String,
    options: List<Int?>,
    selected: Int?,
    onSelect: (Int?) -> Unit
) {
    val p = LocalMgsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title.uppercase(),
            color = p.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.1.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { color ->
                val isSelected = color == selected
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (color == null) Modifier.background(p.warning.copy(alpha = 0.14f))
                            else Modifier.background(Color(color))
                        )
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) p.text else p.clayEdge.copy(alpha = 0.6f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelect(color) },
                    contentAlignment = Alignment.Center
                ) {
                    if (color == null) {
                        Text(
                            text = "None",
                            color = p.muted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
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
private fun ScenePreviewCanvas(scene: StreamScene, streamOrientation: String) {
    val p = LocalMgsPalette.current
    val measurePaint = remember { Paint().apply { isAntiAlias = true } }
    val drawPaint = remember { Paint().apply { isAntiAlias = true } }
    val previewResolver = LocalContext.current.contentResolver
    val badgePaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER } }
    val refCanvas = remember(scene.orientation) { SceneCanvasProfile.referenceCanvas(scene.orientation) }
    val refWidthPx = refCanvas.widthPx
    val refHeightPx = refCanvas.heightPx
    val textSize = SceneTextLayerLayout.textSizePxFor(refHeightPx).toFloat()
    val measured = remember(scene) {
        measurePaint.textSize = textSize
        scene.layers.mapNotNull { layer ->
            val spec = layer.toOverlaySpec() ?: return@mapNotNull null
            if (spec.kind == SceneLayerKind.IMAGE) {
                val uri = spec.imageUri ?: return@mapNotNull null
                val dims = SceneImageLoader.decodeBounds(previewResolver, uri) ?: return@mapNotNull null
                // J1: FILL previews the same full-bounds frame the
                // renderer composes; FIT keeps the H6 aspect fit.
                val size = if (spec.scaleMode == SceneScaleMode.FILL) {
                    SceneImageLayer.fillSize(
                        spec.maxWidthPx, refHeightPx - spec.marginPx * 2,
                        refWidthPx - spec.marginPx * 2, refHeightPx - spec.marginPx * 2
                    )
                } else {
                    SceneImageLayer.measuredSize(
                        dims.widthPx, dims.heightPx, spec.maxWidthPx,
                        refHeightPx - spec.marginPx * 2
                    )
                } ?: return@mapNotNull null
                val styled = SceneLayerStyle.styledSize(
                    size, spec.borderWidthPx, spec.shadowOffsetPx
                ) ?: return@mapNotNull null
                Triple(spec, styled, "")
            } else {
                // H9: widgets measure the shared worst-case box (stable
                // placement, same as the renderer); glyphs tick below.
                val measureContent = when (spec.kind) {
                    SceneLayerKind.CLOCK -> SceneClockWidgets.WALL_CLOCK_WORST_CASE
                    SceneLayerKind.TIMER -> SceneClockWidgets.TIMER_WORST_CASE
                    else -> layer.text
                }
                val bounds = android.graphics.Rect()
                measurePaint.getTextBounds(measureContent, 0, measureContent.length, bounds)
                val size = SceneLayerStyle.styledSize(
                    SceneSizePx(bounds.width(), bounds.height()),
                    spec.borderWidthPx, spec.shadowOffsetPx
                ) ?: return@mapNotNull null
                Triple(spec, size, layer.text)
            }
        }
    }
    // H9 honest preview tick — recomposes each second only while a
    // dynamic layer is present. CLOCK shows real wall time; TIMER
    // counts from preview start as a live demo (labeled PREVIEW).
    val hasDynamicWidgets = remember(measured) {
        measured.any {
            it.first.kind == SceneLayerKind.CLOCK || it.first.kind == SceneLayerKind.TIMER
        }
    }
    var tickWallMs by remember(hasDynamicWidgets) { mutableStateOf(System.currentTimeMillis()) }
    var tickElapsedMs by remember(hasDynamicWidgets) { mutableStateOf(SystemClock.elapsedRealtime()) }
    val previewStartElapsedMs = remember(hasDynamicWidgets) { SystemClock.elapsedRealtime() }
    val previewZone = remember { ZoneId.systemDefault() }
    if (hasDynamicWidgets) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000L)
                tickWallMs = System.currentTimeMillis()
                tickElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }
    val enabledCount = scene.layers.count { it.enabled }
    val droppedCount = enabledCount - measured.count { it.first.enabled }
    val boxes = remember(measured) {
        ScenePreviewBoxes.layout(
            refWidthPx,
            refHeightPx,
            measured.map { SceneOverlayLayerInput(it.first, it.second) }
        )
    }
    val textById = remember(measured, tickWallMs, tickElapsedMs) {
        measured.associate {
            it.first.id to when (it.first.kind) {
                SceneLayerKind.CLOCK -> SceneClockWidgets.formatWallClock(tickWallMs, previewZone)
                SceneLayerKind.TIMER -> SceneClockWidgets.formatElapsed(previewStartElapsedMs, tickElapsedMs)
                else -> it.third
            }
        }
    }
    val kindById = remember(measured) { measured.associate { it.first.id to it.first.kind } }
    val specById = remember(measured) { measured.associate { it.first.id to it.first } }
    val imageById = remember(measured) {
        measured.filter { it.first.kind == SceneLayerKind.IMAGE }.associate { triple ->
            val uri = triple.first.imageUri
            triple.first.id to if (uri == null) null
            else SceneImageLoader.loadThumbnailBitmap(previewResolver, uri, 512)?.asImageBitmap()
        }
    }
    // Phase I: honest mismatch note when the explicit stream output
    // differs from the scene canvas (AUTO follows the device: no claim).
    val mismatchHint = remember(scene.orientation, streamOrientation) {
        when {
            streamOrientation != "PORTRAIT" && streamOrientation != "LANDSCAPE" -> null
            (streamOrientation == "PORTRAIT") ==
                (scene.orientation == SceneDesignOrientation.PORTRAIT) -> null
            scene.orientation == SceneDesignOrientation.PORTRAIT ->
                "Scene is portrait but stream output is landscape — layers are capped to fit."
            else -> "Scene is landscape but stream output is portrait — layers are capped to fit."
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        mismatchHint?.let {
            Text(text = it, color = p.muted, fontSize = 11.sp)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (scene.orientation == SceneDesignOrientation.PORTRAIT) 9f / 16f else 16f / 9f)
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
                    val scale = size.width / refWidthPx.toFloat()
                    for (box in boxes) {
                        val left = box.left * size.width
                        val top = box.top * size.height
                        val right = box.right * size.width
                        val bottom = box.bottom * size.height
                        val glyphs = textById[box.id].orEmpty()
                        if (box.enabled) {
                            val style = specById[box.id]
                            val borderPx = (style?.borderWidthPx ?: 0) * scale
                            val shadowPx = (style?.shadowOffsetPx ?: 0) * scale
                            val radiusPx = CornerRadius(
                                ((style?.cornerRadiusPx ?: 0) * scale).coerceAtMost(
                                    minOf(right - left, bottom - top) / 2f
                                )
                            )
                            if (style != null && shadowPx > 0f) {
                                drawRoundRect(
                                    color = Color(style.shadowColor),
                                    topLeft = androidx.compose.ui.geometry.Offset(left + shadowPx, top + shadowPx),
                                    size = androidx.compose.ui.geometry.Size(right - left - shadowPx, bottom - top - shadowPx),
                                    cornerRadius = radiusPx,
                                    alpha = box.alpha
                                )
                            }
                            val bg = style?.backgroundColor
                            if (style != null && bg != null) {
                                drawRoundRect(
                                    color = Color(bg),
                                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(right - left - shadowPx, bottom - top - shadowPx),
                                    cornerRadius = radiusPx,
                                    alpha = box.alpha
                                )
                            }
                            if (style != null && borderPx > 0f) {
                                drawRoundRect(
                                    color = Color(style.borderColor),
                                    topLeft = androidx.compose.ui.geometry.Offset(left + borderPx / 2f, top + borderPx / 2f),
                                    size = androidx.compose.ui.geometry.Size(
                                        right - left - shadowPx - borderPx,
                                        bottom - top - shadowPx - borderPx
                                    ),
                                    cornerRadius = radiusPx,
                                    style = Stroke(width = borderPx),
                                    alpha = box.alpha
                                )
                            }
                            if (bg == null) {
                                drawRect(
                                    color = Color.White.copy(alpha = 0.14f * box.alpha),
                                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(right - left, bottom - top)
                                )
                            }
                            drawRect(
                                color = Color.White.copy(alpha = 0.65f * box.alpha),
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                style = Stroke(width = 2f)
                            )
                            if (kindById[box.id] == SceneLayerKind.IMAGE) {
                                val image = imageById[box.id]
                                if (image != null) {
                                    val dw = (right - left - borderPx * 2f - shadowPx).roundToInt().coerceAtLeast(1)
                                    val dh = (bottom - top - borderPx * 2f - shadowPx).roundToInt().coerceAtLeast(1)
                                    // J1: FILL crops the same centered
                                    // cover rect the renderer draws.
                                    val crop = if (specById[box.id]?.scaleMode == SceneScaleMode.FILL) {
                                        SceneImageLayer.fillCrop(image.width, image.height, dw, dh)
                                    } else null
                                    drawImage(
                                        image = image,
                                        srcOffset = if (crop == null) IntOffset.Zero
                                            else IntOffset(crop.left, crop.top),
                                        srcSize = if (crop == null) IntSize(image.width, image.height)
                                            else IntSize(crop.right - crop.left, crop.bottom - crop.top),
                                        dstOffset = IntOffset(
                                            (left + borderPx).roundToInt(),
                                            (top + borderPx).roundToInt()
                                        ),
                                        dstSize = IntSize(dw, dh),
                                        alpha = box.alpha
                                    )
                                }
                            } else {
                                drawPaint.color = style?.textColor ?: android.graphics.Color.WHITE
                                drawPaint.alpha = (box.alpha * 255f).roundToInt().coerceIn(0, 255)
                                drawPaint.textSize = textSize * scale
                                drawContext.canvas.nativeCanvas.drawText(
                                    glyphs, left + borderPx,
                                    bottom - shadowPx - borderPx - drawPaint.descent(), drawPaint
                                )
                            }
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
                text = "$droppedCount layer(s) hidden: empty, invalid, or unloadable layers are never streamed.",
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
