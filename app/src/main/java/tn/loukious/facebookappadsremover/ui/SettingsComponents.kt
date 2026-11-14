package tn.loukious.facebookappadsremover.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import tn.loukious.facebookappadsremover.R
import kotlin.math.floor

/** Rounded group: big outer corners, small inner ones, so rows read as one card. */
fun groupShape(count: Int, index: Int): Shape {
    val o = Tokens.OuterCorner
    val i = Tokens.InnerCorner
    return when {
        count == 1 -> RoundedCornerShape(o)
        index == 0 -> RoundedCornerShape(o, o, i, i)
        index == count - 1 -> RoundedCornerShape(i, i, o, o)
        else -> RoundedCornerShape(i)
    }
}

/** Lays out [rows] as one group, each row receiving its position's shape. */
@Composable
fun RowGroup(rows: List<@Composable (Shape) -> Unit>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenMargin),
        verticalArrangement = Arrangement.spacedBy(Tokens.RowGap),
    ) {
        rows.forEachIndexed { index, row -> row(groupShape(rows.size, index)) }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Tokens.RowPadding + 4.dp, end = Tokens.RowPadding, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = Tokens.RowPadding + 4.dp, vertical = 12.dp),
    )
}

/** The row surface every list row sits on. */
@Composable
private fun RowSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.RowMinHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** Icon · title/summary · trailing widget. */
@Composable
private fun RowContent(
    icon: Int?,
    title: String,
    summary: String?,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.RowPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
            Spacer(Modifier.width(Tokens.RowPadding))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            if (!summary.isNullOrEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
        trailing()
    }
}

/** Switch with a check / cross inside the thumb. */
@Composable
fun IconSwitch(checked: Boolean, enabled: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        modifier = Modifier.padding(start = Tokens.RowPadding),
        colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
        thumbContent = {
            Icon(
                painterResource(if (checked) R.drawable.ic_check else R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

@Composable
fun SwitchRow(
    shape: Shape,
    icon: Int?,
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    RowSurface(
        shape,
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) {
            haptics.performHapticFeedback(
                if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
            )
            onChange(it)
        },
    ) {
        RowContent(icon, title, summary, enabled) { IconSwitch(checked, enabled) }
    }
}

enum class Trailing { Chevron, External, None }

@Composable
fun NavigationRow(
    shape: Shape,
    icon: Int?,
    title: String,
    summary: String?,
    trailing: Trailing = Trailing.Chevron,
    onClick: () -> Unit,
) {
    RowSurface(shape, Modifier.clickable(onClick = onClick)) {
        RowContent(icon, title, summary) {
            val vector = when (trailing) {
                Trailing.Chevron -> R.drawable.ic_chevron_right
                Trailing.External -> R.drawable.ic_open_in_new
                Trailing.None -> null
            }
            if (vector != null) {
                Icon(
                    painterResource(vector), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Tokens.RowPadding),
                )
            }
        }
    }
}

/** A row that hosts arbitrary content (keyword field, session buttons, ...). */
@Composable
fun ContentRow(shape: Shape, content: @Composable () -> Unit) {
    RowSurface(shape) { Box(Modifier.padding(Tokens.RowPadding)) { content() } }
}

// ---------------------------------------------------------------- badge ----

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val BadgeShapes: List<RoundedPolygon>
    get() = listOf(
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Sunny,
        MaterialShapes.Cookie12Sided,
        MaterialShapes.Clover8Leaf,
    )

/** Tap pulse: pushes the shape morph forward briefly, then springs back. */
@Stable
class BadgePulse(private val scope: CoroutineScope) {
    val morph = Animatable(0f)
    fun tap() {
        scope.launch {
            morph.animateTo(0.45f, spring(dampingRatio = 0.55f, stiffness = 900f))
            morph.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 260f))
        }
    }
}

/**
 * Content clipped to a slowly spinning shape that morphs through the
 * Material 3 Expressive cookie / sunny / clover polygons.
 */
@Composable
fun MorphingBadge(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    pulse: BadgePulse? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shapes = remember { BadgeShapes.map { it.normalized() } }
    val morphs = remember { shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) } }
    val easing = remember { CubicBezierEasing(0.4f, 0f, 0.2f, 1f) }
    // Static badges (avatars) skip the infinite transition entirely, so they
    // don't keep the frame clock running.
    var rotation = 0f
    var cycle = 0f
    if (animate) {
        val infinite = rememberInfiniteTransition(label = "badge")
        rotation = infinite.animateFloat(
            0f, 360f, infiniteRepeatable(tween(8_000, easing = LinearEasing)), label = "spin",
        ).value
        cycle = infinite.animateFloat(
            0f, shapes.size.toFloat(),
            infiniteRepeatable(tween(3_200 * shapes.size, easing = LinearEasing)), label = "morph",
        ).value
    }
    Box(
        modifier.drawWithCache {
            val androidPath = android.graphics.Path()
            val matrix = android.graphics.Matrix()
            onDrawWithContent {
                val progress = cycle + (pulse?.morph?.value ?: 0f)
                val whole = floor(progress)
                val index = Math.floorMod(whole.toInt(), morphs.size)
                // Hold each shape, then morph during the first 55% of a step.
                val t = ((progress - whole) / 0.55f).coerceIn(0f, 1f)
                androidPath.rewind()
                morphs[index].toPath(easing.transform(t), androidPath)
                // Polygons are normalised to 0..1; inset a little so the
                // lobes never clip while rotating.
                val s = size.minDimension * 0.92f
                matrix.reset()
                matrix.setScale(s, s)
                matrix.postTranslate((size.width - s) / 2, (size.height - s) / 2)
                matrix.postRotate(rotation, size.center.x, size.center.y)
                androidPath.transform(matrix)
                clipPath(androidPath.asComposePath()) { this@onDrawWithContent.drawContent() }
            }
        },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ----------------------------------------------------------- status card ---

enum class ModuleState { CONNECTING, ACTIVE, INACTIVE }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusCard(state: ModuleState, detail: String?) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val palette = when (state) {
        ModuleState.ACTIVE -> okColors(dark)
        ModuleState.CONNECTING -> waitColors(dark)
        ModuleState.INACTIVE -> StatusColors(scheme.errorContainer, scheme.error, scheme.onErrorContainer)
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(palette.container, effects, label = "statusContainer")
    val badge by animateColorAsState(palette.badge, effects, label = "statusBadge")
    val onContainer by animateColorAsState(palette.onContainer, effects, label = "statusText")
    val scope = rememberCoroutineScope()
    val pulse = remember(scope) { BadgePulse(scope) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenMargin, vertical = 8.dp),
        shape = RoundedCornerShape(Tokens.OuterCorner),
        color = container,
    ) {
        Row(
            Modifier.padding(Tokens.RowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MorphingBadge(
                Modifier.size(56.dp).clickable(
                    interactionSource = null, indication = null, onClick = pulse::tap,
                ),
                animate = state != ModuleState.INACTIVE,
                pulse = pulse,
            ) {
                Box(Modifier.fillMaxSize().background(badge))
                val icon = when (state) {
                    ModuleState.ACTIVE -> R.drawable.ic_check
                    ModuleState.CONNECTING -> R.drawable.ic_hourglass
                    ModuleState.INACTIVE -> R.drawable.ic_priority_high
                }
                Icon(painterResource(icon), contentDescription = null, tint = container, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(Tokens.RowPadding))
            val effectsF = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = state to detail,
                transitionSpec = { fadeIn(effectsF) togetherWith fadeOut(effectsF) using SizeTransform(clip = false) },
                label = "statusText",
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            ) { (shown, text) ->
                Column {
                    Text(
                        when (shown) {
                            ModuleState.ACTIVE -> "Module active"
                            ModuleState.CONNECTING -> "Connecting…"
                            ModuleState.INACTIVE -> "Module not active"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = onContainer,
                    )
                    Text(
                        when (shown) {
                            ModuleState.ACTIVE -> text ?: ""
                            ModuleState.CONNECTING -> "Waiting for the Xposed framework"
                            ModuleState.INACTIVE -> "Enable it for Facebook in LSPosed or Vector"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------- contributors ---

private val AddedGreen = Color(0xFF3FB950)

@Composable
fun ContributorRow(shape: Shape, contributor: Contributor, avatar: ImageBitmap?, onClick: () -> Unit) {
    val fmt = remember { NumberFormat.getIntegerInstance() }
    RowSurface(shape, Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(Tokens.RowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MorphingBadge(Modifier.size(48.dp), animate = false) {
                if (avatar != null) {
                    Image(avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer))
                }
            }
            Spacer(Modifier.width(Tokens.RowPadding))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        contributor.login,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (contributor.owner) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                "Owner",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val mono = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
                    Text("+${fmt.format(contributor.added)}", style = mono, color = AddedGreen)
                    Spacer(Modifier.width(6.dp))
                    Text("−${fmt.format(contributor.removed)}", style = mono, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    val (plus, minus) = diffBlocks(contributor.added, contributor.removed)
                    repeat(5) { i ->
                        val tint = when {
                            i < plus -> AddedGreen
                            i < plus + minus -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        Box(Modifier.padding(end = 2.dp).size(8.dp).clip(RoundedCornerShape(2.dp)).background(tint))
                    }
                }
            }
            Icon(
                painterResource(R.drawable.ic_open_in_new), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Tokens.RowPadding),
            )
        }
    }
}
