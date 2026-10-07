package com.rfm.edubot.mobile.feature.timeclock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.BiometricPromptText
import com.rfm.edubot.mobile.core.common.DeviceSigner
import com.rfm.edubot.mobile.core.common.LocationProvider
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.TimeClockRepository
import com.rfm.edubot.mobile.core.localization.AppLocale
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.Punch
import com.rfm.edubot.mobile.core.model.PunchRequest
import com.rfm.edubot.mobile.core.model.Shift
import com.rfm.edubot.mobile.core.model.TimeClockStatus
import com.rfm.edubot.mobile.core.model.TimesheetPolicy
import com.rfm.edubot.mobile.core.ui.Badge
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.ErrorPanel
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MetricRow
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.RefreshBar
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.Tone
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.math.roundToInt

/**
 * An employee's time clock: the state and running time, the buttons that make sense now, the phone's
 * fingerprint or face check, a forgotten clock-out, and their shifts. The team's side stays on the web.
 */
@Composable
fun TimeClockScreen(
    repository: TimeClockRepository,
    location: LocationProvider,
    signer: DeviceSigner,
    strings: Strings,
    clock: TenantClock,
    padding: PaddingValues,
) {
    val vm = viewModel<TimeClockViewModel>(
        key = "timeclock:${repository.employeeId}",
        factory = viewModelFactory { initializer { TimeClockViewModel(repository, location, signer) } },
    )
    val statusState by repository.status.state.collectAsState()
    val shiftsState by repository.shifts.state.collectAsState()
    val ui by vm.state.collectAsState()
    LaunchedEffect(vm) { vm.load() }
    var now by remember { mutableStateOf(clock.instant().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MILLIS)
            now = clock.instant().toEpochMilliseconds()
        }
    }

    val status = statusState.value
    val prompt = { action: String -> BiometricPromptText(strings[Txt.TIME_PROMPT_TITLE], action, strings[Txt.TIME_PROMPT_CANCEL]) }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader(strings[Txt.TIME_CLOCK], strings.module(DashboardModules.MY_HOURS)) {
                TextButton(onClick = { vm.refresh() }, enabled = !statusState.loading && !ui.busy) {
                    Text(strings[Txt.ACTION_REFRESH], color = BotColors.accentDeep)
                }
            }
            RefreshBar(statusState.loading && statusState.hasValue)
        }
        if (status == null) {
            statusState.error?.let { error -> item { ErrorPanel(strings.error(error), retryLabel = strings[Txt.ACTION_RETRY], onRetry = { vm.refresh() }) } }
            if (statusState.loading) item { LoadingScreen() }
            return@LazyColumn
        }
        if (statusState.fromCache) item { InfoPanel(strings[Txt.OFFLINE_SNAPSHOT], tone = Tone.Info) }
        status.open?.takeIf { status.overdue }?.let { open ->
            item { OverduePanel(open, status, strings, clock, busy = ui.busy, onClose = { end -> vm.closeForgotten(open.id, end) }) }
        }
        item { ClockPanel(status, now, strings, busy = ui.busy, onPunch = { type, label -> vm.punch(type, prompt(label)) }) }
        item { Feedback(ui, strings) }
        if (status.policy.biometric != TimesheetPolicy.POLICY_OFF) {
            item {
                PhonePanel(ui.phone, required = status.policy.biometric == TimesheetPolicy.POLICY_REQUIRED, busy = ui.busy, strings = strings) {
                    vm.setUpPhone(prompt(strings[Txt.TIME_PROMPT_SETUP]))
                }
            }
        }
        item {
            Text(
                strings[if (status.policy.location == TimesheetPolicy.POLICY_OFF) Txt.TIME_LOCATION_OFF_NOTE else Txt.TIME_LOCATION_NOTE],
                Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm),
                style = MaterialTheme.typography.bodySmall,
                color = BotColors.inkMuted,
            )
        }
        item {
            val running = status.open?.let { workedNow(it, now) - it.workedMinutes } ?: 0
            MetricRow(
                leftLabel = strings[Txt.TIME_TODAY],
                leftValue = hm(strings, status.todayMinutes + running),
                rightLabel = strings[Txt.TIME_WEEK],
                rightValue = strings.format(Txt.TIME_WEEK_OF_LIMIT, "worked" to hours(status.weekMinutes + running, strings.locale), "limit" to status.policy.weeklyHours),
            )
        }
        item { SectionLabel(strings[Txt.TIME_HISTORY]) }
        val shifts = shiftsState.value.orEmpty()
        if (shifts.isEmpty()) {
            item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.TIME_HISTORY_EMPTY]) }
        } else {
            items(shifts, key = { it.id }) { shift -> ShiftRow(shift, now, strings) }
        }
    }
}

@Composable
private fun ClockPanel(status: TimeClockStatus, now: Long, strings: Strings, busy: Boolean, onPunch: (String, String) -> Unit) {
    val open = status.open
    val (label, tone) = when (status.state) {
        TimeClockStatus.STATE_WORKING -> strings[Txt.TIME_STATE_WORKING] to Tone.Ok
        TimeClockStatus.STATE_ON_BREAK -> strings[Txt.TIME_STATE_ON_BREAK] to Tone.Warn
        else -> strings[Txt.TIME_STATE_OFF] to Tone.Neutral
    }
    val line = when {
        open == null -> strings[Txt.TIME_OFF_HINT]
        open.onBreak -> strings.format(Txt.TIME_ON_BREAK_SINCE, "time" to (open.breaks.lastOrNull()?.startTime ?: open.startTime))
        else -> strings.format(Txt.TIME_WORKING_SINCE, "time" to open.startTime)
    }
    val buttons = when (status.state) {
        TimeClockStatus.STATE_WORKING -> listOf(PunchRequest.BREAK_START to Txt.TIME_BREAK_START, PunchRequest.OUT to Txt.TIME_CLOCK_OUT)
        TimeClockStatus.STATE_ON_BREAK -> listOf(PunchRequest.BREAK_END to Txt.TIME_BREAK_END, PunchRequest.OUT to Txt.TIME_CLOCK_OUT)
        else -> listOf(PunchRequest.IN to Txt.TIME_CLOCK_IN)
    }
    Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(strings[Txt.TIME_CLOCK], style = MaterialTheme.typography.titleSmall, color = BotColors.ink)
            Badge(label, tone)
        }
        Spacer(Modifier.height(BotSpace.sm))
        if (open != null) {
            Text(
                hm(strings, workedNow(open, now)),
                style = MaterialTheme.typography.headlineLarge,
                color = if (open.onBreak) BotColors.warnInk else BotColors.ink,
            )
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, color = BotColors.inkSecondary)
        open?.punches?.lastOrNull()?.let { last ->
            Spacer(Modifier.height(BotSpace.xs))
            Text(
                strings.format(Txt.TIME_LAST, "what" to "${strings[last.type.punchedKey()]} ${last.time} · ${where(last, strings)}"),
                style = MaterialTheme.typography.bodySmall,
                color = BotColors.inkMuted,
            )
        }
        Spacer(Modifier.height(BotSpace.md))
        Row(horizontalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
            buttons.forEachIndexed { index, (type, key) ->
                val text = strings[key]
                if (index == buttons.lastIndex) {
                    PrimaryButton(text, onClick = { onPunch(type, text) }, Modifier.weight(1f), busy = busy)
                } else {
                    SecondaryButton(text, onClick = { onPunch(type, text) }, Modifier.weight(1f), enabled = !busy)
                }
            }
        }
    }
}

/** What is happening now, or what went through or wrong, read out by screen readers as it changes. */
@Composable
private fun Feedback(ui: TimeClockUiState, strings: Strings) {
    val text = when {
        ui.step == TimeClockStep.LOCATING -> strings[Txt.TIME_LOCATING] to Tone.Info
        ui.step == TimeClockStep.CONFIRMING -> strings[Txt.TIME_CONFIRMING] to Tone.Info
        ui.problem != null -> strings[ui.problem.key()] to Tone.Warn
        ui.error != null -> errorText(ui.error, strings) to Tone.Bad
        ui.done != null -> strings[ui.done.key()] to Tone.Ok
        else -> null
    } ?: return
    InfoPanel(text.first, tone = text.second, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun PhonePanel(phone: PhoneSetup, required: Boolean, busy: Boolean, strings: Strings, onSetUp: () -> Unit) {
    when (phone) {
        PhoneSetup.NOT_USED -> Unit
        PhoneSetup.READY -> InfoPanel(strings[Txt.TIME_SETUP_DONE], strings[Txt.TIME_PHONE_READY], tone = Tone.Ok)
        PhoneSetup.NO_BIOMETRICS -> InfoPanel(strings[Txt.TIME_SETUP_TITLE], strings[Txt.TIME_PROBLEM_BIOMETRIC_NONE], tone = Tone.Warn)
        PhoneSetup.UNSUPPORTED -> InfoPanel(strings[Txt.TIME_SETUP_TITLE], strings[Txt.TIME_PROBLEM_BIOMETRIC_UNAVAILABLE], tone = if (required) Tone.Warn else Tone.Neutral)
        PhoneSetup.NEEDED -> Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm), tone = if (required) Tone.Warn else Tone.Neutral) {
            Text(strings[Txt.TIME_SETUP_TITLE], style = MaterialTheme.typography.titleSmall, color = BotColors.ink)
            Spacer(Modifier.height(BotSpace.xs))
            Text(strings[if (required) Txt.TIME_SETUP_REQUIRED else Txt.TIME_SETUP_BODY], style = MaterialTheme.typography.bodySmall, color = BotColors.inkSecondary)
            Spacer(Modifier.height(BotSpace.md))
            PrimaryButton(strings[Txt.TIME_SETUP_ACTION], onClick = onSetUp, Modifier.fillMaxWidth(), busy = busy)
        }
    }
}

@Composable
private fun OverduePanel(open: Shift, status: TimeClockStatus, strings: Strings, clock: TenantClock, busy: Boolean, onClose: (String) -> Unit) {
    var end by remember(open.id) { mutableStateOf(addTime(open.startTime, status.policy.dailyHours * 60)) }
    val since = "${clock.dayAndMonth(open.startAt).orEmpty()} ${open.startTime}"
    Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm), tone = Tone.Warn) {
        Text(strings.format(Txt.TIME_OVERDUE_TITLE, "when" to since), style = MaterialTheme.typography.titleSmall, color = BotColors.warnInk)
        Spacer(Modifier.height(BotSpace.xs))
        Text(strings[Txt.TIME_OVERDUE_BODY], style = MaterialTheme.typography.bodySmall, color = BotColors.ink)
        Spacer(Modifier.height(BotSpace.sm))
        BotField(end, { end = it.take(5) }, strings[Txt.TIME_OVERDUE_END], Modifier.fillMaxWidth())
        Spacer(Modifier.height(BotSpace.sm))
        PrimaryButton(strings[Txt.TIME_OVERDUE_SAVE], onClick = { onClose(end) }, Modifier.fillMaxWidth(), busy = busy)
    }
}

@Composable
private fun ShiftRow(shift: Shift, now: Long, strings: Strings) {
    val day = runCatching { LocalDate.parse(shift.day) }.getOrNull()
    val dayLabel = day?.let { "${strings.weekday(it.dayOfWeek.isoDayNumber)} ${it.dayOfMonth.pad()}/${it.monthNumber.pad()}" } ?: shift.day
    val range = if (shift.endTime == null) "${shift.startTime}–…" else "${shift.startTime}–${shift.endTime}"
    val (status, label) = when {
        shift.isOpen -> "OPEN" to strings[Txt.TIME_REVIEW_OPEN]
        shift.review == "APPROVED" -> "APPROVED" to strings[Txt.TIME_REVIEW_APPROVED]
        else -> "PENDING" to strings[Txt.TIME_REVIEW_PENDING]
    }
    ListRow(
        title = "$dayLabel · $range",
        detail = listOfNotNull(
            hm(strings, workedNow(shift, now)),
            shift.breakMinutes.takeIf { it > 0 }?.let { strings.format(Txt.TIME_BREAKS, "time" to hm(strings, it)) },
            shift.siteName,
            shift.flags.takeIf { it.isNotEmpty() }?.joinToString(", ") { strings.timeFlag(it) },
        ).joinToString(" · "),
        leading = day?.let { strings.weekday(it.dayOfWeek.isoDayNumber) } ?: shift.day,
        status = status,
        statusLabel = label,
        emphasised = shift.isOpen,
    )
}

private fun where(punch: Punch, strings: Strings): String {
    val parts = mutableListOf<String>()
    when {
        punch.siteName != null && punch.inside == true -> parts += strings.format(Txt.TIME_AT_SITE, "site" to punch.siteName)
        punch.siteName != null -> parts += strings.format(Txt.TIME_FROM_SITE, "distance" to distance(punch.siteDistanceM ?: 0, strings.locale), "site" to punch.siteName)
        punch.accuracyM != null -> parts += strings[Txt.TIME_LOCATED]
        else -> parts += strings[Txt.TIME_NO_LOCATION]
    }
    parts += strings[if (punch.verified) Txt.TIME_VERIFIED else Txt.TIME_NOT_VERIFIED]
    return parts.joinToString(" · ")
}

/** `outside_sites` names the nearest site and how far it is, which says more than the bare code. */
private fun errorText(error: AppError, strings: Strings): String {
    val rejected = error as? AppError.Rejected
    val site = rejected?.details?.get("siteName")
    val meters = rejected?.details?.get("distanceM")?.toIntOrNull()
    if (rejected?.code == "outside_sites" && site != null && meters != null) {
        return strings.format(Txt.TIME_OUTSIDE_SITES, "distance" to distance(meters, strings.locale), "site" to site)
    }
    return strings.error(error)
}

/** Minutes worked so far: an open shift runs until [now], less its breaks. */
internal fun workedNow(shift: Shift, now: Long): Int {
    if (!shift.isOpen) return shift.workedMinutes
    val start = parseMillis(shift.startAt) ?: return shift.workedMinutes
    val breaks = shift.breaks.sumOf { b ->
        val from = maxOf(parseMillis(b.startAt) ?: start, start)
        val to = b.endAt?.let(::parseMillis) ?: now
        (to - from).coerceAtLeast(0)
    }
    return ((now - start - breaks).coerceAtLeast(0) / 60_000).toInt()
}

private fun parseMillis(iso: String): Long? = runCatching { kotlinx.datetime.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

internal fun hm(strings: Strings, minutes: Int): String {
    val total = minutes.coerceAtLeast(0)
    return if (total >= 60) strings.format(Txt.TIME_HM, "h" to total / 60, "m" to total % 60) else strings.format(Txt.TIME_MINUTES, "m" to total)
}

private fun hours(minutes: Int, locale: AppLocale): String {
    val tenths = (minutes.coerceAtLeast(0) / 6.0).roundToInt()
    val text = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    return if (locale == AppLocale.English) text else text.replace('.', ',')
}

private fun distance(meters: Int, locale: AppLocale): String {
    if (meters < 1000) return "$meters m"
    val tenths = (meters / 100.0).roundToInt()
    val text = "${tenths / 10}.${tenths % 10} km"
    return if (locale == AppLocale.English) text else text.replace('.', ',')
}

internal fun addTime(hhmm: String, minutes: Int): String {
    val parts = hhmm.split(":").mapNotNull { it.toIntOrNull() }
    if (parts.size != 2) return hhmm
    val total = (((parts[0] * 60 + parts[1] + minutes) % 1440) + 1440) % 1440
    return "${(total / 60).pad()}:${(total % 60).pad()}"
}

private fun Int.pad(): String = if (this < 10) "0$this" else toString()

private fun TimeClockProblem.key(): String = when (this) {
    TimeClockProblem.LOCATION_DENIED -> Txt.TIME_PROBLEM_LOCATION_DENIED
    TimeClockProblem.LOCATION_OFF -> Txt.TIME_PROBLEM_LOCATION_OFF
    TimeClockProblem.LOCATION_TIMEOUT -> Txt.TIME_PROBLEM_LOCATION_TIMEOUT
    TimeClockProblem.LOCATION_UNAVAILABLE -> Txt.TIME_PROBLEM_LOCATION_UNAVAILABLE
    TimeClockProblem.BIOMETRIC_NONE -> Txt.TIME_PROBLEM_BIOMETRIC_NONE
    TimeClockProblem.BIOMETRIC_UNAVAILABLE -> Txt.TIME_PROBLEM_BIOMETRIC_UNAVAILABLE
    TimeClockProblem.BIOMETRIC_LOCKED -> Txt.TIME_PROBLEM_BIOMETRIC_LOCKED
    TimeClockProblem.BIOMETRIC_FAILED -> Txt.TIME_PROBLEM_BIOMETRIC_FAILED
    TimeClockProblem.KEY_CHANGED -> Txt.TIME_PROBLEM_KEY_CHANGED
    TimeClockProblem.SETUP_NEEDED -> Txt.TIME_PROBLEM_SETUP_NEEDED
}

private fun String.punchedKey(): String = when (this) {
    PunchRequest.IN -> Txt.TIME_PUNCHED_IN
    PunchRequest.BREAK_START -> Txt.TIME_PUNCHED_BREAK_START
    PunchRequest.BREAK_END -> Txt.TIME_PUNCHED_BREAK_END
    else -> Txt.TIME_PUNCHED_OUT
}

private fun TimeClockDone.key(): String = when (this) {
    TimeClockDone.IN -> Txt.TIME_PUNCHED_IN
    TimeClockDone.OUT -> Txt.TIME_PUNCHED_OUT
    TimeClockDone.BREAK_START -> Txt.TIME_PUNCHED_BREAK_START
    TimeClockDone.BREAK_END -> Txt.TIME_PUNCHED_BREAK_END
    TimeClockDone.PHONE_READY -> Txt.TIME_SETUP_DONE
    TimeClockDone.SHIFT_CLOSED -> Txt.TIME_OVERDUE_SAVED
}

private const val TICK_MILLIS = 15_000L
