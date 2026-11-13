package com.rfm.edubot.mobile.feature.building

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rfm.edubot.mobile.core.common.formatEuros
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.ChangeOrderStatus
import com.rfm.edubot.mobile.core.model.IssueStatus
import com.rfm.edubot.mobile.core.model.MeasurementStatus
import com.rfm.edubot.mobile.core.model.ProjectHealth
import com.rfm.edubot.mobile.core.model.ProjectStatus
import com.rfm.edubot.mobile.core.ui.Badge
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.ProgressBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.Tone

/** The vocabularies the web's catalogs keep per Building record. */
internal object Vocab {
    const val STATUS = "building.status"
    const val HEALTH = "building.health"
    const val PHASE_STATE = "building.phaseState"
    const val WEATHER = "building.weather"
    const val ISSUE_KIND = "building.issueKind"
    const val PRIORITY = "building.priority"
    const val ISSUE_STATUS = "building.issueStatus"
    const val CHANGE_KIND = "building.changeKind"
    const val CHANGE_STATUS = "building.changeStatus"
    const val MEASUREMENT_STATUS = "building.measurementStatus"
}

internal fun money(eur: Double): String = "€\u00A0${formatEuros(eur)}"

/** `2`, or `12,5`: a quantity as people write it, not as Kotlin prints a Double. */
internal fun formatQuantity(quantity: Double): String =
    if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString() else quantity.toString().replace('.', ',')

internal fun healthTone(health: String): Tone = when (health) {
    ProjectHealth.ON_TRACK, "DONE" -> Tone.Ok
    ProjectHealth.AT_RISK, ProjectStatus.ON_HOLD -> Tone.Warn
    ProjectHealth.DELAYED, ProjectStatus.CANCELLED -> Tone.Bad
    else -> Tone.Neutral
}

internal fun phaseTone(state: String): Tone = when (state) {
    "DONE" -> Tone.Ok
    "LATE" -> Tone.Bad
    "IN_PROGRESS" -> Tone.Info
    else -> Tone.Neutral
}

internal fun issueTone(status: String, overdue: Boolean): Tone = when {
    overdue -> Tone.Bad
    status == IssueStatus.OPEN -> Tone.Warn
    status == IssueStatus.IN_PROGRESS -> Tone.Info
    status == IssueStatus.RESOLVED -> Tone.Accent
    else -> Tone.Neutral
}

internal fun changeTone(status: String): Tone = when (status) {
    ChangeOrderStatus.SENT -> Tone.Warn
    ChangeOrderStatus.APPROVED -> Tone.Ok
    ChangeOrderStatus.REJECTED -> Tone.Bad
    else -> Tone.Neutral
}

internal fun measurementTone(status: String): Tone = when (status) {
    MeasurementStatus.APPROVED -> Tone.Warn
    MeasurementStatus.INVOICED -> Tone.Ok
    else -> Tone.Neutral
}

/** "3 days behind", "On plan", or nothing before the plan says anything. */
internal fun scheduleText(days: Int?, strings: Strings): String? = when {
    days == null -> null
    days > 0 -> strings.format(Txt.BUILDING_AHEAD, "days" to days)
    days < 0 -> strings.format(Txt.BUILDING_BEHIND, "days" to -days)
    else -> strings[Txt.BUILDING_ON_PLAN]
}

/** Progress against the plan: the bar, the figures under it and how far ahead or behind. */
@Composable
internal fun ProgressBlock(progressPct: Int, plannedPct: Int?, scheduleDays: Int?, health: String, strings: Strings) = Column(Modifier.fillMaxWidth()) {
    val figures = if (plannedPct != null) {
        strings.format(Txt.BUILDING_PROGRESS, "done" to progressPct, "planned" to plannedPct)
    } else {
        "$progressPct%"
    }
    ProgressBar(progressPct, plannedPct = plannedPct, tone = healthTone(health).takeIf { it != Tone.Neutral } ?: Tone.Accent, description = figures)
    Spacer(Modifier.height(BotSpace.xs))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(figures, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
        scheduleText(scheduleDays, strings)?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = if ((scheduleDays ?: 0) < 0) BotColors.badInk else BotColors.inkSecondary)
        }
    }
}

@Composable
internal fun LabelBadge(strings: Strings, vocabulary: String, value: String, tone: Tone) =
    Badge(strings.label(vocabulary, value), tone)

/** A pushed screen's header with the way back, which iOS has no gesture for. */
@Composable
internal fun BackHeader(eyebrow: String, title: String, strings: Strings, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) =
    ScreenHeader(eyebrow, title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            action?.invoke()
            TextButton(onClick = onBack) { Text(strings[Txt.ACTION_BACK], color = BotColors.accentDeep) }
        }
    }

internal val sectionPadding = Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)
