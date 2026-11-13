package com.voleybolveveleybol.volleystat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.ui.theme.Dimens
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors

/** Dashed outline: the app's one visual sign for "this does not exist yet" or "not measured". */
fun Modifier.dashedOutline(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
    )
}

@Composable
fun VsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(Dimens.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, LocalVsColors.current.divider)
    val body: @Composable () -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick == null) {
        Surface(modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surface, border = border, content = body)
    } else {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surface, border = border, content = body)
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, modifier = modifier.semantics { heading() })
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = modifier.semantics { heading() })
}

/** Icon on a soft tinted tile, as in the home list rows of the design. */
@Composable
fun IconTile(icon: Int, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun ListRow(icon: Int, title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = Dimens.cardPadding, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = LocalVsColors.current.divider, modifier = Modifier.padding(horizontal = Dimens.cardPadding))

enum class Tone { POSITIVE, CAUTION, NEGATIVE, ACTIVE, NEUTRAL, NOT_YET }

/** Status is always icon plus words; the colour only supports it. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalVsColors.current
    val (container, content, icon) = when (tone) {
        Tone.POSITIVE -> Triple(extra.positiveContainer, extra.onPositiveContainer, R.drawable.ic_check_circle)
        Tone.CAUTION -> Triple(extra.cautionContainer, extra.onCautionContainer, R.drawable.ic_warning)
        Tone.NEGATIVE -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.drawable.ic_error)
        Tone.ACTIVE -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.drawable.ic_pending)
        Tone.NEUTRAL -> Triple(scheme.surfaceVariant, scheme.onSurface, R.drawable.ic_schedule)
        Tone.NOT_YET -> Triple(Color.Transparent, scheme.onSurfaceVariant, R.drawable.ic_hourglass_empty)
    }
    val outline = if (tone == Tone.NOT_YET) Modifier.dashedOutline(scheme.outline, 8.dp) else Modifier
    Row(
        modifier.then(outline).background(container, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/**
 * Marks everything that comes from the demo analyzer. Shown on every screen that displays such
 * data. The compact form is for screens where the user works through many items and the full
 * explanation would push the answers off the screen.
 */
@Composable
fun DemoBanner(modifier: Modifier = Modifier, compact: Boolean = false) {
    val extra = LocalVsColors.current
    Row(
        modifier.fillMaxWidth().background(extra.cautionContainer, MaterialTheme.shapes.medium).padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = extra.caution, modifier = Modifier.padding(top = if (compact) 0.dp else 2.dp).size(20.dp))
        if (compact) {
            Text(stringResource(R.string.demo_short), style = MaterialTheme.typography.labelMedium, color = extra.onCautionContainer)
        } else {
            Column {
                Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.titleSmall, color = extra.onCautionContainer)
                Text(stringResource(R.string.demo_body), style = MaterialTheme.typography.bodySmall, color = extra.onCautionContainer)
            }
        }
    }
}

/** Home side is a filled badge, away side an outlined one. Position and words carry identity; colour supports it. */
@Composable
fun TeamBadge(home: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val scheme = MaterialTheme.colorScheme
    val spoken = stringResource(if (home) R.string.team_home else R.string.team_away)
    val box = if (home) Modifier.background(scheme.primary, CircleShape) else Modifier.background(scheme.surface, CircleShape).border(2.dp, scheme.primary, CircleShape)
    Box(modifier.size(size).then(box).clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_apparel), contentDescription = null, tint = if (home) scheme.onPrimary else scheme.primary, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun Notice(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalVsColors.current
    val (container, content, icon) = when (tone) {
        Tone.NEGATIVE -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.drawable.ic_error)
        Tone.CAUTION -> Triple(extra.cautionContainer, extra.onCautionContainer, R.drawable.ic_warning)
        Tone.POSITIVE -> Triple(extra.positiveContainer, extra.onPositiveContainer, R.drawable.ic_check_circle)
        else -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.drawable.ic_info)
    }
    Row(
        modifier.fillMaxWidth().background(container, MaterialTheme.shapes.medium).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

/** Section-level message for a feature or a result that does not exist yet. Never a table of placeholders. */
@Composable
fun NotYetCard(title: String, body: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().dashedOutline(scheme.outline, 16.dp).padding(Dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusChip(stringResource(R.string.state_not_yet), Tone.NOT_YET)
        Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

/** Label on the left, value on the right; long values wrap under the label at large font sizes. */
@Composable
fun FactRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
fun FactRow(label: String, value: String, modifier: Modifier = Modifier) {
    FactRow(label, modifier) { Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End) }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = Dimens.buttonHeight), enabled = enabled, shape = MaterialTheme.shapes.medium) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick, modifier.fillMaxWidth().heightIn(min = Dimens.buttonHeight), enabled = enabled, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (enabled) scheme.outline else scheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = scheme.surface, contentColor = scheme.primary),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** A choice card with a radio mark, as on the video source screen. */
@Composable
fun SelectCard(selected: Boolean, icon: Int, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else LocalVsColors.current.divider),
    ) {
        Row(Modifier.heightIn(min = 76.dp).padding(Dimens.cardPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            // Drawn mark, so the whole card is one radio target instead of a nested control.
            Box(Modifier.size(22.dp).border(2.dp, if (selected) scheme.primary else scheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(12.dp).background(scheme.primary, CircleShape))
            }
        }
    }
}

/** Two or three mutually exclusive options side by side. */
@Composable
fun SegmentedChoice(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).border(1.dp, scheme.outline, MaterialTheme.shapes.medium).selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier.weight(1f).heightIn(min = Dimens.touchTarget)
                    .background(if (selected) scheme.primary else scheme.surface)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.onPrimary else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

enum class StageState { DONE, ACTIVE, FAILED, CAUTION, WAITING, NOT_YET }

class Stage(val title: String, val detail: String? = null, val state: StageState, val moment: String? = null)

/**
 * Named steps as on the analysis screen. Only a step whose work is really running gets a moving
 * indicator; a step that has not started is a hollow circle, and one that can not happen says so.
 */
@Composable
fun StageList(stages: List<Stage>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalVsColors.current
    Column(modifier.fillMaxWidth()) {
        stages.forEachIndexed { index, stage ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when (stage.state) {
                            StageState.DONE -> Icon(painterResource(R.drawable.ic_check_circle), null, tint = extra.positive, modifier = Modifier.size(24.dp))
                            StageState.FAILED -> Icon(painterResource(R.drawable.ic_error), null, tint = scheme.error, modifier = Modifier.size(24.dp))
                            StageState.CAUTION -> Icon(painterResource(R.drawable.ic_warning), null, tint = extra.caution, modifier = Modifier.size(24.dp))
                            StageState.ACTIVE -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                            StageState.WAITING -> Box(Modifier.size(20.dp).border(2.dp, scheme.outline, CircleShape))
                            StageState.NOT_YET -> Icon(painterResource(R.drawable.ic_hourglass_empty), null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        }
                    }
                    if (index < stages.lastIndex) {
                        Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).background(extra.divider, CircleShape))
                    }
                }
                Column(Modifier.weight(1f).padding(bottom = if (index < stages.lastIndex) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stage.title, style = MaterialTheme.typography.titleSmall,
                        color = if (stage.state == StageState.WAITING) scheme.onSurfaceVariant else scheme.onSurface,
                    )
                    if (stage.detail != null) Text(stage.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (stage.moment != null) Text(stage.moment, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The ring of the analysis screen. [fraction] is a measured share of the work, or null when the
 * amount of work is unknown; then the ring spins instead of showing a number nobody measured.
 */
@Composable
fun ProgressRing(fraction: Float?, modifier: Modifier = Modifier, spinning: Boolean = fraction == null, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 10.dp.toPx()
            drawArc(scheme.primaryContainer, 0f, 360f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            if (fraction != null) {
                drawArc(scheme.primary, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        if (fraction == null && spinning) CircularProgressIndicator(Modifier.matchParentSize().padding(0.dp), strokeWidth = 10.dp, trackColor = Color.Transparent)
        content()
    }
}

/** An empty list with one way forward. */
@Composable
fun EmptyState(icon: Int, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, size = 64.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) action()
    }
}
