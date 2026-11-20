package com.jubersaleem.mobilegamingstreamer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Visual weight of a clay button. */
enum class ClayButtonStyle { Filled, Raised, Inset }

/**
 * Semantic tones for clay pills and badges.
 *
 * Live is teal (an active broadcast is a live/technical state), Active is
 * green (a healthy ready state), Idle is graphite (a truthful standby, never
 * a lit-up false positive), Accent is violet (informational highlights),
 * Warning is amber (degraded or action-needed states that are not errors)
 * and Alert is red, reserved for errors and destructive states.
 */
enum class ClayTone { Live, Active, Idle, Accent, Warning, Alert }

/** One destination of the clay navigation dock. */
data class ClayNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Primary clay control. Filled buttons are sculpted electric-violet blocks
 * for the single most important action on a card, raised buttons are soft
 * light slabs for secondary actions and inset buttons are recessed wells for
 * tertiary actions. Every button meets the 48dp minimum touch target and
 * exposes its label to accessibility services.
 */
@Composable
fun ClayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ClayButtonStyle = ClayButtonStyle.Filled,
    icon: ImageVector? = null
) {
    val p = LocalMgsPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "clayButtonScale"
    )
    val shape = RoundedCornerShape(20.dp)
    val contentColor = if (style == ClayButtonStyle.Filled) p.onPrimary else p.text
    val shadowRadius = if (pressed) 6.dp else 14.dp
    val shadowOffset = DpOffset(if (pressed) 2.dp else 5.dp, if (pressed) 3.dp else 6.dp)
    val liftOffset = DpOffset(if (pressed) (-2).dp else (-4).dp, if (pressed) (-2).dp else (-4).dp)
    val sculpted = when (style) {
        ClayButtonStyle.Filled -> Modifier
            .dropShadow(
                shape,
                Shadow(
                    radius = shadowRadius,
                    color = p.clayShadow.copy(alpha = 0.62f),
                    offset = shadowOffset
                )
            )
            .dropShadow(
                shape,
                Shadow(
                    radius = shadowRadius - 2.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    offset = liftOffset
                )
            )
        ClayButtonStyle.Raised -> Modifier
            .dropShadow(
                shape,
                Shadow(
                    radius = shadowRadius,
                    color = p.clayShadow.copy(alpha = 0.55f),
                    offset = shadowOffset
                )
            )
            .dropShadow(
                shape,
                Shadow(
                    radius = shadowRadius - 2.dp,
                    color = Color.White.copy(alpha = 0.95f),
                    offset = liftOffset
                )
            )
        ClayButtonStyle.Inset -> Modifier
            .clip(shape)
            .background(p.inset, shape)
            .innerShadow(
                shape,
                Shadow(
                    radius = 8.dp,
                    color = p.clayShadow.copy(alpha = 0.45f),
                    offset = DpOffset(3.dp, 3.dp)
                )
            )
            .innerShadow(
                shape,
                Shadow(
                    radius = 7.dp,
                    color = Color.White.copy(alpha = 0.85f),
                    offset = DpOffset((-3).dp, (-3).dp)
                )
            )
    }
    val fill = when (style) {
        ClayButtonStyle.Filled -> Brush.linearGradient(listOf(p.primary, p.primaryDeep))
        ClayButtonStyle.Raised -> Brush.linearGradient(listOf(p.highlight, p.surface))
        ClayButtonStyle.Inset -> Brush.linearGradient(listOf(p.inset, p.surfaceDeep))
    }
    val rim = when (style) {
        ClayButtonStyle.Filled ->
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), p.primaryDeep))
        else -> Brush.linearGradient(listOf(Color.White, p.clayEdge, Color.White))
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.55f
            }
            .then(sculpted)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, rim, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = label,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = 50.dp)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor
                )
            }
            Text(
                text = label,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.9.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Recessed clay text field. Colours come from the clay [MaterialTheme]
 * scheme. Technical values such as URLs and keys use [mono] so every
 * character stays distinguishable.
 */
@Composable
fun ClayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    val p = LocalMgsPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(clayInsetModifier(RoundedCornerShape(18.dp), rim = false)),
        singleLine = singleLine,
        textStyle = TextStyle(fontFamily = if (mono) FontFamily.Monospace else null),
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = if (leadingIcon != null) {
            {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = p.muted
                )
            }
        } else {
            null
        },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(18.dp)
    )
}

/**
 * Segmented clay selector carved into a recessed well. Options behave as a
 * radio group for accessibility services, with the chosen tile raised and
 * inked in deep violet.
 */
@Composable
fun ClayOptionGroup(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = ""
) {
    val p = LocalMgsPalette.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            color = p.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.1.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clayInsetModifier(RoundedCornerShape(20.dp)))
                .selectableGroup()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            options.forEach { option ->
                val chosen = option == selected
                val itemShape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .then(
                            if (chosen) {
                                Modifier
                                    .dropShadow(
                                        itemShape,
                                        Shadow(
                                            radius = 8.dp,
                                            color = p.clayShadow.copy(alpha = 0.45f),
                                            offset = DpOffset(3.dp, 4.dp)
                                        )
                                    )
                                    .dropShadow(
                                        itemShape,
                                        Shadow(
                                            radius = 7.dp,
                                            color = Color.White.copy(alpha = 0.92f),
                                            offset = DpOffset((-3).dp, (-3).dp)
                                        )
                                    )
                            } else {
                                Modifier
                            }
                        )
                        .clip(itemShape)
                        .background(
                            if (chosen) {
                                Brush.linearGradient(listOf(p.highlight, p.surface))
                            } else {
                                Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                            itemShape
                        )
                        .then(
                            if (chosen) Modifier.border(1.dp, p.clayEdge.copy(alpha = 0.7f), itemShape) else Modifier
                        )
                        .selectable(selected = chosen, role = Role.RadioButton) { onSelect(option) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option + suffix,
                        color = if (chosen) p.primaryInk else p.muted,
                        fontSize = 11.sp,
                        fontWeight = if (chosen) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Recessed clay switch row with an explanatory caption. The whole row is a
 * single switch control for accessibility services, so TalkBack announces
 * the title together with the on/off state.
 */
@Composable
fun ClayToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clayInsetModifier(RoundedCornerShape(20.dp)))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = p.muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = p.primary,
                checkedBorderColor = p.primaryDeep,
                uncheckedThumbColor = p.highlight,
                uncheckedTrackColor = p.surfaceDeep,
                uncheckedBorderColor = p.clayEdge
            )
        )
    }
}

/**
 * Bottom clay navigation dock. Destinations behave as tabs for accessibility
 * services; the icon is decorative because the visible label already names
 * each destination.
 */
@Composable
fun ClayNavDock(
    items: List<ClayNavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    val dockShape = RoundedCornerShape(30.dp)
    val itemShape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clayRaisedModifier(dockShape, ClayDepth.Medium))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clayInsetModifier(RoundedCornerShape(24.dp)))
                .selectableGroup()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val chosen = item.route == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (chosen) {
                                Modifier
                                    .dropShadow(
                                        itemShape,
                                        Shadow(
                                            radius = 7.dp,
                                            color = p.clayShadow.copy(alpha = 0.42f),
                                            offset = DpOffset(3.dp, 3.dp)
                                        )
                                    )
                                    .dropShadow(
                                        itemShape,
                                        Shadow(
                                            radius = 6.dp,
                                            color = Color.White.copy(alpha = 0.95f),
                                            offset = DpOffset((-3).dp, (-3).dp)
                                        )
                                    )
                            } else {
                                Modifier
                            }
                        )
                        .clip(itemShape)
                        .background(
                            if (chosen) {
                                Brush.linearGradient(listOf(p.highlight, p.surface))
                            } else {
                                Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                            itemShape
                        )
                        .then(
                            if (chosen) Modifier.border(1.dp, p.clayEdge.copy(alpha = 0.65f), itemShape) else Modifier
                        )
                        .selectable(selected = chosen, role = Role.Tab) { onSelect(item.route) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (chosen) p.primaryInk else p.muted
                    )
                    Text(
                        text = item.label,
                        color = if (chosen) p.primaryInk else p.muted,
                        fontSize = 9.sp,
                        fontWeight = if (chosen) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Sculpted clay dialog used for every modal prompt. */
@Composable
fun ClayDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "CANCEL",
    body: @Composable ColumnScope.() -> Unit
) {
    val p = LocalMgsPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = modifier
                .padding(horizontal = 22.dp)
                .widthIn(max = 460.dp)
                .then(clayRaisedModifier(RoundedCornerShape(32.dp), ClayDepth.Deep))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { body() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ClayButton(
                    label = dismissLabel,
                    onClick = onDismiss,
                    style = ClayButtonStyle.Raised,
                    modifier = Modifier.weight(1f)
                )
                ClayButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    style = ClayButtonStyle.Filled,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Recessed clay pill used for status and metadata. The well stays neutral
 * clay and the tone shows only in the dot and the label ink, from the shared
 * palette — teal for live, green for healthy, graphite for standby, violet
 * for information, amber for degraded states and red for errors.
 */
@Composable
fun ClayStatusPill(
    label: String,
    tone: ClayTone,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    val ink = when (tone) {
        ClayTone.Live -> p.accentDeep
        ClayTone.Active -> p.successDeep
        ClayTone.Idle -> p.muted
        ClayTone.Accent -> p.primaryInk
        ClayTone.Warning -> p.warningDeep
        ClayTone.Alert -> p.dangerDeep
    }
    Row(
        modifier = modifier
            .then(clayInsetModifier(RoundedCornerShape(50)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.size(8.dp).background(ink, CircleShape))
        Text(
            text = label,
            color = ink,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp
        )
    }
}

/**
 * Recessed clay statistic tile. Values are technical readouts, so they use
 * the monospace face and turn teal while the measured path is active.
 */
@Composable
fun ClayStat(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    Column(
        modifier = modifier
            .then(clayInsetModifier(RoundedCornerShape(18.dp)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label.uppercase(),
            color = p.muted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (active) p.accent else p.muted.copy(alpha = 0.5f), CircleShape)
            )
            Text(
                text = value,
                color = if (active) p.accentDeep else p.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

/** Clay menu row used by the settings and about menus. */
@Composable
fun ClayMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clayRaisedModifier(shape, ClayDepth.Subtle))
            .clickable(onClickLabel = title, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(clayInsetModifier(RoundedCornerShape(14.dp))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = p.primary
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = p.muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = p.muted
        )
    }
}

/**
 * Small raised clay icon button. The 48dp slab meets the minimum touch
 * target and the icon description becomes the button's click label.
 */
@Composable
fun ClayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .size(48.dp)
            .then(clayRaisedModifier(shape, ClayDepth.Subtle))
            .clickable(onClickLabel = contentDescription, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = p.primaryInk
        )
    }
}

/**
 * Tinted badge that seats a section icon on the shared clay. Raised, so it
 * can sit on flat card surfaces and inside recessed wells alike.
 */
@Composable
fun ClayIconBadge(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .size(40.dp)
            .dropShadow(
                shape,
                Shadow(
                    radius = 10.dp,
                    color = p.clayShadow.copy(alpha = 0.40f),
                    offset = DpOffset(3.dp, 4.dp)
                )
            )
            .dropShadow(
                shape,
                Shadow(
                    radius = 8.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    offset = DpOffset((-3).dp, (-4).dp)
                )
            )
            .clip(shape)
            .background(containerColor, shape)
            .border(1.dp, Brush.linearGradient(listOf(p.edge, p.clayEdge, p.edge)), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = contentColor
        )
    }
}

/** Recessed clay colour chip used by the appearance card. */
@Composable
fun ClaySwatch(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(38.dp)
            .then(clayRaisedModifier(RoundedCornerShape(13.dp), ClayDepth.Subtle)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** Page title block. */
@Composable
fun ClayPageHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = eyebrow.uppercase(),
            color = p.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.6.sp
        )
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = p.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.2).sp
        )
        Text(
            text = subtitle,
            color = p.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

/** Section caption used inside clay cards. */
@Composable
fun ClaySectionLabel(text: String, modifier: Modifier = Modifier) {
    val p = LocalMgsPalette.current
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = p.primary,
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.4.sp
    )
}

/**
 * Key/value line used by the system status card. Values are technical
 * readouts in the monospace face; a live value turns teal and its dot fades
 * in instead of snapping, so state changes read as a gentle transition.
 */
@Composable
fun ClayKeyValueRow(
    label: String,
    value: String,
    accent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val p = LocalMgsPalette.current
    val dotAlpha by animateFloatAsState(
        targetValue = if (accent) 1f else 0.55f,
        animationSpec = tween(durationMillis = 350),
        label = "keyValueDot"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = p.muted, fontSize = 12.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .background(if (accent) p.accent else p.clayEdge, CircleShape)
            )
            Text(
                text = value,
                color = if (accent) p.accentDeep else p.text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp
            )
        }
    }
}

/** Grooved divider used between clay rows. */
@Composable
fun ClayGroove(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .then(clayInsetModifier(RoundedCornerShape(3.dp)))
    )
}

/** Row of small clay buttons that share the available width. */
@Composable
fun ClayButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}
