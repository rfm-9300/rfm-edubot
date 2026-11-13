package com.rfm.edubot.mobile.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The pieces a record screen is made of: the web's record card (label and value rows), its status
 * strip, the progress bar Building draws against the plan, and the dialogs that guard an action.
 */

/** One label and its value, as the web's record card lays them out. A blank value hides the row. */
@Composable
fun FieldRow(label: String, value: String?, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    if (value.isNullOrBlank()) return
    Row(modifier.fillMaxWidth().padding(vertical = BotSpace.xs), verticalAlignment = Alignment.Top) {
        Text(
            label,
            Modifier.width(132.dp),
            style = MaterialTheme.typography.labelMedium,
            color = BotColors.inkMuted,
        )
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (tone == Tone.Neutral) BotColors.ink else tone.ink(),
        )
    }
}

/** A record's card: a title line with an optional status badge, then its rows. */
@Composable
fun RecordCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: String? = null,
    statusLabel: String? = null,
    content: @Composable () -> Unit = {},
) = Panel(modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = BotColors.ink)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
            }
        }
        if (status != null) StatusBadge(status, statusLabel ?: status)
    }
    Spacer(Modifier.height(BotSpace.sm))
    content()
}

/**
 * Progress against the plan: the filled part is what is done, the tick is where the plan expects it
 * by the end of yesterday ([plannedPct]). The tone says whether that is fine.
 */
@Composable
fun ProgressBar(
    progressPct: Int,
    modifier: Modifier = Modifier,
    plannedPct: Int? = null,
    tone: Tone = Tone.Accent,
    description: String? = null,
) {
    var width by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val done = progressPct.coerceIn(0, 100) / 100f
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(BotRadius.pill))
            .background(BotColors.surfaceAlt)
            .onSizeChanged { width = it.width }
            .semantics { if (description != null) contentDescription = description },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(done)
                .background(if (tone == Tone.Accent) BotColors.accent else tone.ink()),
        )
        plannedPct?.let { planned ->
            val x = with(density) { (width * planned.coerceIn(0, 100) / 100f).toDp() }
            Box(Modifier.offset(x = (x - 1.dp).coerceAtLeast(0.dp)).width(2.dp).fillMaxHeight().background(BotColors.ink))
        }
    }
}

/** A row of filter chips that scrolls sideways instead of wrapping. */
@Composable
fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    counts: Map<String, Int> = emptyMap(),
    /** Zero inside a panel or a form that already pads its content. */
    horizontalPadding: Dp = BotSpace.xl,
) = Row(
    modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = horizontalPadding, vertical = BotSpace.sm),
    horizontalArrangement = Arrangement.spacedBy(BotSpace.sm),
) {
    options.forEach { (key, label) ->
        Chip(label = label, selected = key == selected, onClick = { onSelect(key) }, count = counts[key])
    }
}

/** A record's actions, wrapping onto a second line on a narrow phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) = FlowRow(
    modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = BotSpace.sm),
    horizontalArrangement = Arrangement.spacedBy(BotSpace.sm),
    verticalArrangement = Arrangement.spacedBy(BotSpace.sm),
) { content() }

/** Lines of a document: description, quantity × price, and the line total. */
@Composable
fun LineRow(description: String, detail: String, amount: String, modifier: Modifier = Modifier) = Row(
    modifier.fillMaxWidth().padding(vertical = BotSpace.xs),
    verticalAlignment = Alignment.Top,
) {
    Column(Modifier.weight(1f)) {
        Text(description, style = MaterialTheme.typography.bodyMedium, color = BotColors.ink)
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.width(BotSpace.sm))
    Text(amount, style = MaterialTheme.typography.labelLarge, color = BotColors.inkSecondary)
}

/** Asks before an action that can't be undone. [destructive] colours the confirm button. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) = AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = BotColors.surface,
    title = { Text(title, color = BotColors.ink) },
    text = { Text(message, color = BotColors.inkSecondary) },
    confirmButton = {
        TextButton(onClick = onConfirm) {
            Text(confirmLabel, color = if (destructive) BotColors.badInk else BotColors.accentDeep)
        }
    },
    dismissButton = {
        TextButton(onClick = onDismiss) { Text(cancelLabel, color = BotColors.inkMuted) }
    },
)

/**
 * Asks for one value before an action: a due date for an invoice, a reason for a rejection.
 * [canConfirm] decides whether what was typed is enough.
 */
@Composable
fun PromptDialog(
    title: String,
    label: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    supporting: String? = null,
    singleLine: Boolean = true,
    canConfirm: (String) -> Boolean = { true },
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BotColors.surface,
        title = { Text(title, color = BotColors.ink) },
        text = {
            BotField(
                value = value,
                onValueChange = { value = it },
                label = label,
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
                supporting = supporting,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = canConfirm(value)) {
                Text(confirmLabel, color = if (canConfirm(value)) BotColors.accentDeep else BotColors.inkFaint)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel, color = BotColors.inkMuted) }
        },
    )
}

/** Picks one of [options] (key to label) from a dialog; for choices too long for a chip row. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    cancelLabel: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = BotColors.surface,
    title = { Text(title, color = BotColors.ink) },
    text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            options.forEach { (key, label) ->
                TextButton(onClick = { onPick(key) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        Modifier.fillMaxWidth(),
                        color = if (key == selected) BotColors.accentDeep else BotColors.ink,
                    )
                }
            }
        }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel, color = BotColors.inkMuted) } },
)
