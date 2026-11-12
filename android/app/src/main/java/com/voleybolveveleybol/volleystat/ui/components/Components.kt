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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsTokens

/** Dashed outline: the app's one visual sign for "this does not exist yet". */
fun Modifier.dashedOutline(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
    )
}

@Composable
fun VsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalVsColors.current
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, colors.cardLine)
    val body: @Composable () -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick == null) {
        Surface(modifier.fillMaxWidth(), shape = shape, color = colors.card, border = border, content = body)
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().semantics { if (onClickLabel != null) contentDescription = onClickLabel },
            shape = shape, color = colors.card, border = border, content = body,
        )
    }
}

/** A screen's main title. The short brush stroke under it is the only decorative use of the accent. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        if (LocalVsTokens.current.titleRule) {
            Canvas(Modifier.padding(top = 4.dp).size(width = 56.dp, height = 8.dp)) {
                val stroke = Path().apply {
                    moveTo(2f, size.height * 0.6f)
                    cubicTo(size.width * 0.3f, size.height * 0.1f, size.width * 0.6f, size.height * 0.95f, size.width - 2f, size.height * 0.35f)
                }
                drawPath(stroke, accent, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = modifier.semantics { heading() })
}

enum class Tone { POSITIVE, CAUTION, NEGATIVE, ACTIVE, NEUTRAL, NOT_YET }

/** Status is always icon plus words; the colour only supports it. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalVsColors.current
    val (container, content, icon) = when (tone) {
        Tone.POSITIVE -> Triple(extra.positiveContainer, extra.positive, R.drawable.ic_check_circle)
        Tone.CAUTION -> Triple(extra.cautionContainer, extra.caution, R.drawable.ic_warning)
        Tone.NEGATIVE -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.drawable.ic_error)
        Tone.ACTIVE -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.drawable.ic_upload)
        Tone.NEUTRAL -> Triple(scheme.surfaceContainerHigh, scheme.onSurface, R.drawable.ic_pending)
        Tone.NOT_YET -> Triple(Color.Transparent, scheme.onSurfaceVariant, R.drawable.ic_hourglass_empty)
    }
    val outline = if (tone == Tone.NOT_YET) Modifier.dashedOutline(scheme.outline, 10.dp) else Modifier
    Row(
        modifier.then(outline).background(container, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** Team A is a solid tag, team B an outlined one. Position and letter carry identity; colour supports it. */
@Composable
fun TeamTag(isTeamA: Boolean, modifier: Modifier = Modifier) {
    val extra = LocalVsColors.current
    val spoken = stringResource(if (isTeamA) R.string.team_a_spoken else R.string.team_b_spoken)
    val shape = MaterialTheme.shapes.extraSmall
    val box = if (isTeamA) Modifier.background(extra.teamA, shape) else Modifier.border(2.dp, extra.teamB, shape)
    Box(modifier.size(28.dp).then(box).clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
        Text(
            stringResource(if (isTeamA) R.string.team_a_tag else R.string.team_b_tag),
            style = MaterialTheme.typography.labelLarge,
            color = if (isTeamA) extra.onTeamA else extra.teamB,
        )
    }
}

/** Team A always on the left, team B always on the right, on every match surface. */
@Composable
fun MatchHeader(teamA: String, teamB: String, modifier: Modifier = Modifier, center: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TeamTag(isTeamA = true)
                Text(teamA, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Text(teamB, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
                TeamTag(isTeamA = false)
            }
        }
        center()
    }
}

/** Section-level message for a feature that does not exist yet. Never a table of placeholders. */
@Composable
fun NotYetCard(title: String, body: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().dashedOutline(scheme.outline, LocalVsTokens.current.cardRadius).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusChip(stringResource(R.string.state_not_yet), Tone.NOT_YET)
        Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
fun Notice(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalVsColors.current
    val (container, content, icon) = when (tone) {
        Tone.NEGATIVE -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.drawable.ic_error)
        Tone.CAUTION -> Triple(extra.cautionContainer, extra.caution, R.drawable.ic_warning)
        Tone.POSITIVE -> Triple(extra.positiveContainer, extra.positive, R.drawable.ic_check_circle)
        else -> Triple(scheme.surfaceContainerHigh, scheme.onSurface, R.drawable.ic_info)
    }
    Row(
        modifier.fillMaxWidth().background(container, MaterialTheme.shapes.medium).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

/** Label on the left, value on the right; long values wrap under the label at large font sizes. */
@Composable
fun FactRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
fun FactRow(label: String, value: String, modifier: Modifier = Modifier) {
    FactRow(label, modifier) { Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End) }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = MaterialTheme.shapes.medium) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
    }
}

enum class StageState { DONE, FAILED, CAUTION, WAITING, NOT_YET }

class Stage(val title: String, val detail: String, val state: StageState, val moment: String? = null)

/** Named steps with the time each one finished. No percentage, no countdown, no moving indicator. */
@Composable
fun StageTracker(stages: List<Stage>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalVsColors.current
    Column(modifier.fillMaxWidth()) {
        stages.forEachIndexed { index, stage ->
            val (icon, tint) = when (stage.state) {
                StageState.DONE -> R.drawable.ic_check_circle to extra.positive
                StageState.FAILED -> R.drawable.ic_error to scheme.error
                StageState.CAUTION -> R.drawable.ic_warning to extra.caution
                StageState.WAITING -> R.drawable.ic_pending to scheme.onSurfaceVariant
                StageState.NOT_YET -> R.drawable.ic_hourglass_empty to scheme.onSurfaceVariant
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                    if (index < stages.lastIndex) {
                        Box(Modifier.padding(vertical = 4.dp).width(2.dp).weight(1f).background(scheme.outlineVariant, CircleShape))
                    }
                }
                Column(Modifier.weight(1f).padding(bottom = if (index < stages.lastIndex) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stage.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        if (stage.state == StageState.NOT_YET) StatusChip(stringResource(R.string.state_not_yet), Tone.NOT_YET)
                    }
                    Text(stage.detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    if (stage.moment != null) Text(stage.moment, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
