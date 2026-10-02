package com.rfm.edubot.mobile.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Semantic tone, so a screen never has to decide which green a success is. */
enum class Tone { Neutral, Accent, Ok, Warn, Bad, Info }

@Composable
internal fun Tone.ink(): Color = when (this) {
    Tone.Neutral -> BotColors.inkMuted
    Tone.Accent -> BotColors.accentDeep
    Tone.Ok -> BotColors.okInk
    Tone.Warn -> BotColors.warnInk
    Tone.Bad -> BotColors.badInk
    Tone.Info -> BotColors.infoInk
}

@Composable
internal fun Tone.fill(): Color = when (this) {
    Tone.Neutral -> BotColors.surfaceAlt
    Tone.Accent -> BotColors.accentSoft
    Tone.Ok -> BotColors.okSoft
    Tone.Warn -> BotColors.warnSoft
    Tone.Bad -> BotColors.badSoft
    Tone.Info -> BotColors.infoSoft
}

/** The tone a backend status name should read in. Keeps colour decisions out of every screen. */
fun toneForStatus(status: String): Tone = when (status.uppercase()) {
    "ACTIVE", "CONFIRMED", "COMPLETED", "PAID", "PAGA", "ACCEPTED", "ACEITO", "DONE", "SUCCEEDED",
    "DELIVERED", "READ", "READY" -> Tone.Ok
    "PENDING", "PENDENTE", "DRAFT", "WAITING", "QUEUED", "COMPILING", "PAUSED" -> Tone.Warn
    "BLOCKED", "FAILED", "CANCELLED", "REJECTED", "RECUSADO", "NO_SHOW", "DISABLED", "OVERDUE" -> Tone.Bad
    "INVOICED" -> Tone.Ok
    "OPEN", "SENT", "ENVIADO", "RUNNING" -> Tone.Info
    else -> Tone.Neutral
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) = Box(
    modifier.fillMaxSize().padding(BotSpace.xxl),
    contentAlignment = Alignment.Center,
) {
    CircularProgressIndicator(color = BotColors.accentDeep, modifier = Modifier.size(32.dp))
}

@Composable
fun InlineSpinner() = CircularProgressIndicator(
    color = BotColors.accentDeep,
    strokeWidth = 2.dp,
    modifier = Modifier.size(16.dp),
)

@Composable
fun ScreenHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = BotSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow.isNotBlank()) {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = BotColors.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(BotSpace.xs))
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, color = BotColors.ink)
        }
        action?.invoke()
    }
    HorizontalDivider(color = BotColors.line)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(BotSpace.xl, BotSpace.lg, BotSpace.xl, BotSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = BotColors.inkMuted,
        )
        trailing?.invoke()
    }
}

/** A card. The web calls it `.panel`; same padding, same hairline, same radius. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        modifier = modifier.fillMaxWidth().then(clickable),
        color = if (tone == Tone.Neutral) BotColors.surface else tone.fill(),
        shape = RoundedCornerShape(BotRadius.lg),
        border = BorderStroke(1.dp, if (tone == Tone.Neutral) BotColors.line else Color.Transparent),
    ) {
        Column(Modifier.padding(BotSpace.lg), content = content)
    }
}

/** Compose's `ColumnScope` is not exported from this module's API surface; this keeps call sites terse. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
fun Badge(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) = Surface(
    modifier = modifier,
    color = tone.fill(),
    shape = RoundedCornerShape(BotRadius.pill),
) {
    Text(
        text,
        Modifier.padding(horizontal = BotSpace.sm, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = tone.ink(),
        maxLines = 1,
    )
}

@Composable
fun StatusBadge(status: String, label: String, modifier: Modifier = Modifier) =
    Badge(label, toneForStatus(status), modifier)

/** A filter chip row entry, matching the web's `.chip` / `.chip.is-on`. */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) = Surface(
    modifier = modifier.clickable(onClick = onClick),
    color = if (selected) BotColors.accentSoft else BotColors.surface,
    shape = RoundedCornerShape(BotRadius.pill),
    border = BorderStroke(1.dp, if (selected) BotColors.accent else BotColors.line),
) {
    Row(
        Modifier.padding(horizontal = BotSpace.md, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BotSpace.xs),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) BotColors.accentDeep else BotColors.inkSecondary,
            maxLines = 1,
        )
        if (count != null && count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) BotColors.accentDeep else BotColors.inkMuted,
            )
        }
    }
}

/**
 * A list row.
 *
 * [actionLabel] is a deliberate, separately tappable action. Rows that change a record — marking an
 * invoice paid, approving an agent — use it rather than making the whole row the action, which a
 * scroll can trigger by accident.
 */
@Composable
fun ListRow(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    leading: String? = null,
    trailingLabel: String? = null,
    status: String? = null,
    statusLabel: String? = null,
    emphasised: Boolean = false,
    actionLabel: String? = null,
    actionTone: Tone = Tone.Accent,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().then(clickable).padding(horizontal = BotSpace.xl, vertical = BotSpace.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(36.dp), shape = RoundedCornerShape(BotRadius.md), color = BotColors.surfaceAlt) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    (leading ?: title).take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = BotColors.inkSecondary,
                )
            }
        }
        Spacer(Modifier.width(BotSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = BotColors.ink,
                fontWeight = if (emphasised) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = BotColors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingLabel != null || status != null) {
            Spacer(Modifier.width(BotSpace.sm))
            Column(horizontalAlignment = Alignment.End) {
                trailingLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted, maxLines = 1)
                }
                status?.let {
                    if (trailingLabel != null) Spacer(Modifier.height(BotSpace.xs))
                    StatusBadge(it, statusLabel ?: it)
                }
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(BotSpace.sm))
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = actionTone.ink())
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 68.dp), color = BotColors.lineSoft)
}

/** One number with its label. The web's `.dash-kpi`. */
@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) = Surface(
    modifier = modifier,
    color = BotColors.surface,
    shape = RoundedCornerShape(BotRadius.lg),
    border = BorderStroke(1.dp, BotColors.line),
) {
    Column(Modifier.padding(BotSpace.lg)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted, maxLines = 2)
        Spacer(Modifier.height(BotSpace.sm))
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = if (tone == Tone.Neutral) BotColors.ink else tone.ink(),
            maxLines = 1,
        )
    }
}

@Composable
fun MetricRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
    modifier: Modifier = Modifier,
    leftTone: Tone = Tone.Neutral,
    rightTone: Tone = Tone.Neutral,
) = Row(
    modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = BotSpace.xs),
    horizontalArrangement = Arrangement.spacedBy(BotSpace.md),
) {
    MetricCard(leftLabel, leftValue, Modifier.weight(1f), leftTone)
    MetricCard(rightLabel, rightValue, Modifier.weight(1f), rightTone)
}

@Composable
fun InfoPanel(title: String, detail: String = "", modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) = Panel(
    modifier = modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm),
    tone = tone,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = if (tone == Tone.Neutral) BotColors.ink else tone.ink(),
    )
    if (detail.isNotBlank()) {
        Spacer(Modifier.height(BotSpace.xs))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (tone == Tone.Neutral) BotColors.inkMuted else tone.ink(),
        )
    }
}

/**
 * A failure with a way out of it. Screens used to render a bare sentence with no retry, which left
 * a transient network error looking like an empty screen.
 */
@Composable
fun ErrorPanel(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) = Surface(
    modifier = modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = BotSpace.sm),
    color = BotColors.badSoft,
    shape = RoundedCornerShape(BotRadius.md),
) {
    Row(
        Modifier.padding(horizontal = BotSpace.md, vertical = BotSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = BotColors.badInk)
        if (retryLabel != null && onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(retryLabel, style = MaterialTheme.typography.labelLarge, color = BotColors.badInk)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, detail: String = "", modifier: Modifier = Modifier) = Column(
    modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(BotSpace.sm),
) {
    Box(
        Modifier.size(44.dp).background(BotColors.surfaceAlt, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("—", style = MaterialTheme.typography.titleLarge, color = BotColors.inkFaint)
    }
    Text(title, style = MaterialTheme.typography.titleMedium, color = BotColors.inkSecondary)
    if (detail.isNotBlank()) {
        Text(detail, style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
    }
}

@Composable
fun BotField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    numeric: Boolean = false,
    supporting: String? = null,
    error: Boolean = false,
) = OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    label = { Text(label) },
    singleLine = singleLine,
    isError = error,
    supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
    ),
    shape = RoundedCornerShape(BotRadius.sm),
    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BotColors.accentDeep,
        unfocusedBorderColor = BotColors.line,
        focusedLabelColor = BotColors.accentDeep,
        unfocusedLabelColor = BotColors.inkMuted,
        focusedTextColor = BotColors.ink,
        unfocusedTextColor = BotColors.ink,
        cursorColor = BotColors.accentDeep,
        errorBorderColor = BotColors.bad,
        errorLabelColor = BotColors.badInk,
        errorSupportingTextColor = BotColors.badInk,
    ),
)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) = Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = 44.dp),
    enabled = enabled && !busy,
    shape = RoundedCornerShape(BotRadius.sm),
    colors = ButtonDefaults.buttonColors(
        containerColor = BotColors.accent,
        contentColor = BotColors.accentInk,
        disabledContainerColor = BotColors.lineSoft,
        disabledContentColor = BotColors.inkFaint,
    ),
) {
    if (busy) InlineSpinner() else Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone = Tone.Neutral,
) = Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = 40.dp),
    enabled = enabled,
    shape = RoundedCornerShape(BotRadius.sm),
    border = BorderStroke(1.dp, if (tone == Tone.Neutral) BotColors.line else tone.ink()),
    colors = ButtonDefaults.buttonColors(
        containerColor = Color.Transparent,
        contentColor = if (tone == Tone.Neutral) BotColors.inkSecondary else tone.ink(),
        disabledContainerColor = Color.Transparent,
        disabledContentColor = BotColors.inkFaint,
    ),
) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun BotSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) = Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    enabled = enabled,
    colors = SwitchDefaults.colors(
        checkedThumbColor = BotColors.accentInk,
        checkedTrackColor = BotColors.accent,
        uncheckedThumbColor = BotColors.surface,
        uncheckedTrackColor = BotColors.surfaceAlt,
        uncheckedBorderColor = BotColors.line,
    ),
)

/**
 * A chat bubble. Unlike the previous version this distinguishes the three authors the backend
 * reports — the customer, the assistant, and a person on the tenant's side — which the inbox needs
 * to show who actually replied.
 */
@Composable
fun MessageBubble(
    text: String,
    stamp: String,
    fromCustomer: Boolean,
    modifier: Modifier = Modifier,
    attribution: String? = null,
    footnote: String? = null,
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
    retryLabel: String? = null,
) = Row(
    modifier.fillMaxWidth(),
    horizontalArrangement = if (fromCustomer) Arrangement.Start else Arrangement.End,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.88f),
        color = when {
            failed -> BotColors.badSoft
            fromCustomer -> BotColors.surface
            else -> BotColors.accentSoft
        },
        shape = RoundedCornerShape(BotRadius.lg),
        border = BorderStroke(1.dp, if (fromCustomer) BotColors.line else Color.Transparent),
    ) {
        Column(Modifier.padding(BotSpace.md)) {
            attribution?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = BotColors.inkMuted)
                Spacer(Modifier.height(BotSpace.xs))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = BotColors.ink)
            Spacer(Modifier.height(BotSpace.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stamp, style = MaterialTheme.typography.labelSmall, color = BotColors.inkFaint)
                footnote?.let {
                    Spacer(Modifier.width(BotSpace.sm))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) BotColors.badInk else BotColors.inkFaint,
                    )
                }
                if (failed && onRetry != null && retryLabel != null) {
                    Spacer(Modifier.width(BotSpace.sm))
                    Text(
                        retryLabel,
                        Modifier.clickable(onClick = onRetry),
                        style = MaterialTheme.typography.labelLarge,
                        color = BotColors.badInk,
                    )
                }
            }
        }
    }
}

/** The thin bar a screen shows while refreshing data it already has on screen. */
@Composable
fun RefreshBar(visible: Boolean) {
    if (!visible) return
    Box(Modifier.fillMaxWidth().height(2.dp).background(BotColors.accent))
}
