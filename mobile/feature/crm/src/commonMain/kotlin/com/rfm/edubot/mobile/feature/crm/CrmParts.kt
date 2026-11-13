package com.rfm.edubot.mobile.feature.crm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.common.formatEuros
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.LineItem
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.Chip
import com.rfm.edubot.mobile.core.ui.LineRow
import com.rfm.edubot.mobile.core.ui.MetricCard
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone

internal fun money(eur: Double): String = "€\u00A0${formatEuros(eur)}"

/** A record's header with the way back: iOS has no system back, so every pushed screen offers one. */
@Composable
internal fun RecordHeader(eyebrow: String, title: String, strings: Strings, onBack: () -> Unit) =
    ScreenHeader(eyebrow, title) {
        TextButton(onClick = onBack) { Text(strings[Txt.ACTION_BACK], color = BotColors.accentDeep) }
    }

/** A document's lines and its total. */
@Composable
internal fun LinesPanel(items: List<LineItem>, total: Double, strings: Strings) {
    if (items.isEmpty()) return
    SectionLabel(strings[Txt.CRM_LINES])
    Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
        items.forEach { line ->
            LineRow(
                description = line.description,
                detail = listOf(
                    formatQuantity(line.quantity) + line.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
                    money(line.unitPriceEur),
                ).joinToString(" × "),
                amount = money(line.totalEur),
            )
        }
        Spacer(Modifier.height(BotSpace.sm))
        Row(Modifier.fillMaxWidth()) {
            Text(strings[Txt.LABEL_TOTAL], Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = BotColors.inkSecondary)
            Text(money(total), style = MaterialTheme.typography.titleMedium, color = BotColors.ink)
        }
    }
}

internal fun formatQuantity(quantity: Double): String =
    if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString() else quantity.toString().replace('.', ',')

/**
 * The record's money strip, two figures to a row: three side by side on a phone cut an amount down
 * to "€ 5.366,…".
 */
@Composable
internal fun MoneyStrip(vararg figures: Triple<String, String, Tone>) = Column(
    Modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = BotSpace.xs),
    verticalArrangement = Arrangement.spacedBy(BotSpace.sm),
) {
    figures.toList().chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
            row.forEach { (label, value, tone) -> MetricCard(label, value, Modifier.weight(1f), tone) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Something to pick a client from: what the person types narrows the chips below. */
data class ClientOption(val id: String, val name: String, val detail: String = "")

/** Typing again clears the choice, so a form can't save against a client nobody picked. */
@Composable
internal fun ClientPicker(
    query: String,
    selectedId: String?,
    options: List<ClientOption>,
    strings: Strings,
    onQuery: (String) -> Unit,
    onPick: (ClientOption) -> Unit,
) = Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
    BotField(
        value = query,
        onValueChange = onQuery,
        label = strings[Txt.CRM_CLIENT_NAME],
        modifier = Modifier.fillMaxWidth(),
        error = selectedId == null && query.isNotBlank(),
    )
    val matches = matching(options, query)
    if (selectedId == null && matches.isNotEmpty()) {
        Spacer(Modifier.height(BotSpace.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
            items(matches, key = { it.id }) { option ->
                Chip(label = option.name, selected = false, onClick = { onPick(option) })
            }
        }
    }
}

internal fun matching(options: List<ClientOption>, query: String, limit: Int = 6): List<ClientOption> {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) return options.take(limit)
    return options.filter { it.name.lowercase().contains(needle) || it.detail.lowercase().contains(needle) }.take(limit)
}

/** `yyyy-MM-dd` for today in the company's timezone, what date fields start with. */
internal fun TenantClock.todayText(): String = today().toString()
