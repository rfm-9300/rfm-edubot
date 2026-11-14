package com.rfm.edubot.mobile.feature.timeclock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rfm.edubot.mobile.core.common.BiometricPromptText
import com.rfm.edubot.mobile.core.common.DeviceSigner
import com.rfm.edubot.mobile.core.common.LocationProvider
import com.rfm.edubot.mobile.core.common.TenantClock
import com.rfm.edubot.mobile.core.data.TimeClockRepository
import com.rfm.edubot.mobile.core.localization.Strings
import com.rfm.edubot.mobile.core.localization.Txt
import com.rfm.edubot.mobile.core.model.JobOptions
import com.rfm.edubot.mobile.core.model.JobRequest
import com.rfm.edubot.mobile.core.model.JobRules
import com.rfm.edubot.mobile.core.model.PunchRequest
import com.rfm.edubot.mobile.core.model.ShiftSegment
import com.rfm.edubot.mobile.core.model.TimesheetPolicy
import com.rfm.edubot.mobile.core.ui.BotColors
import com.rfm.edubot.mobile.core.ui.BotField
import com.rfm.edubot.mobile.core.ui.BotSpace
import com.rfm.edubot.mobile.core.ui.EmptyState
import com.rfm.edubot.mobile.core.ui.InfoPanel
import com.rfm.edubot.mobile.core.ui.ListRow
import com.rfm.edubot.mobile.core.ui.LoadingScreen
import com.rfm.edubot.mobile.core.ui.MetricRow
import com.rfm.edubot.mobile.core.ui.Panel
import com.rfm.edubot.mobile.core.ui.PrimaryButton
import com.rfm.edubot.mobile.core.ui.ScreenHeader
import com.rfm.edubot.mobile.core.ui.SecondaryButton
import com.rfm.edubot.mobile.core.ui.SectionLabel
import com.rfm.edubot.mobile.core.ui.StatusBadge
import com.rfm.edubot.mobile.core.ui.Tone

/** How many clients the picker lists before asking for a search: a company can have thousands. */
private const val CLIENTS_SHOWN = 30

/**
 * Picks a job (client, project, activity, note) for [target]: the next clock-in, a switch (which punches at once,
 * with the same location and fingerprint checks as any punch) or one part of a shift being split. Each part shows
 * only when the company has something to pick; the company's strict rule marks what a complete job needs.
 */
@Composable
fun JobPickerScreen(
    repository: TimeClockRepository,
    location: LocationProvider,
    signer: DeviceSigner,
    strings: Strings,
    target: JobTarget,
    padding: PaddingValues,
    onDone: () -> Unit,
) {
    val vm = timeClockViewModel(repository, location, signer)
    val ui by vm.state.collectAsState()
    val statusState by repository.status.state.collectAsState()
    val jobsState by repository.jobs.state.collectAsState()
    LaunchedEffect(repository) { repository.jobs.load() }
    val policy = statusState.value?.policy ?: TimesheetPolicy()
    val options = jobsState.value
    val initial = remember(target) {
        when (target) {
            JobTarget.ClockIn -> ui.job
            JobTarget.Switch -> statusState.value?.open?.currentJob?.request
            is JobTarget.Part -> ui.split?.parts?.getOrNull(target.index)?.job
        } ?: JobRequest()
    }
    var clientId by remember(target) { mutableStateOf(initial.clientId) }
    var projectId by remember(target) { mutableStateOf(initial.projectId) }
    var activityId by remember(target) { mutableStateOf(initial.activityId) }
    var note by remember(target) { mutableStateOf(initial.note.orEmpty()) }
    var query by remember(target) { mutableStateOf("") }
    var missing by remember(target) { mutableStateOf<String?>(null) }
    val title = when (target) {
        JobTarget.ClockIn -> Txt.TIME_JOB_TITLE
        JobTarget.Switch -> Txt.TIME_SWITCH
        is JobTarget.Part -> Txt.TIME_WORK_LOG_EDIT
    }
    val needs = if (policy.jobsRequired) policy.jobNeeds else null
    val mark = { needed: Boolean? -> strings[if (needed == true) Txt.TIME_JOB_REQUIRED else Txt.TIME_JOB_OPTIONAL] }

    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader(strings[Txt.TIME_CLOCK], strings[title]) {
                TextButton(onClick = onDone) { Text(strings[Txt.ACTION_CANCEL], color = BotColors.accentDeep) }
            }
        }
        if (options == null) {
            item { if (jobsState.loading) LoadingScreen() else jobsState.error?.let { InfoPanel(strings.error(it), tone = Tone.Bad) } }
            return@LazyColumn
        }
        if (target == JobTarget.Switch) item { InfoPanel(strings[Txt.TIME_SWITCH_HINT], tone = Tone.Info) }
        if (needs != null && !needs.client && !needs.project && !needs.activity && !needs.note) {
            item { Hint(strings[Txt.TIME_JOB_NEEDS_ANY]) }
        }
        if (options.clients.isNotEmpty()) {
            item { SectionLabel("${strings[Txt.TIME_JOB_CLIENT]} · ${mark(needs?.client)}") }
            item {
                BotField(query, { query = it }, strings[Txt.TIME_JOB_SEARCH], Modifier.fillMaxWidth().padding(horizontal = BotSpace.xl))
            }
            val matches = options.clients.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.number.contains(query.trim(), ignoreCase = true) }
            val listed = matches.take(CLIENTS_SHOWN)
            // A picked client the search hides goes on top, so the choice stays in sight; one the list shows keeps its
            // place, so the row doesn't jump from under the finger that picked it.
            val picked = options.clients.firstOrNull { it.id == clientId }?.takeIf { pick -> listed.none { it.id == pick.id } }
            val shown = listOfNotNull(picked) + listed
            item { ChoiceRow(strings[Txt.TIME_JOB_NO_CLIENT], null, selected = clientId == null) { clientId = null } }
            items(shown, key = { "client:${it.id}" }) { client ->
                ChoiceRow(client.name, client.number.takeIf(String::isNotBlank), selected = client.id == clientId) {
                    clientId = client.id
                    if (options.projects.firstOrNull { it.id == projectId }?.clientId?.let { owner -> owner != client.id } == true) projectId = null
                }
            }
            if (matches.size > CLIENTS_SHOWN) item { Hint(strings.format(Txt.TIME_JOB_MORE, "count" to matches.size - CLIENTS_SHOWN)) }
        }
        val projects = options.projectsFor(clientId)
        if (projects.isNotEmpty()) {
            item { SectionLabel("${strings[Txt.TIME_JOB_PROJECT]} · ${mark(needs?.project)}") }
            item { ChoiceRow(strings[Txt.TIME_JOB_NO_PROJECT], null, selected = projectId == null) { projectId = null } }
            items(projects, key = { "project:${it.id}" }) { project ->
                ChoiceRow(project.name, project.number.takeIf(String::isNotBlank), selected = project.id == projectId) {
                    projectId = project.id
                    project.clientId?.let { owner -> if (options.clients.any { it.id == owner }) clientId = owner }
                }
            }
        }
        if (options.activities.isNotEmpty()) {
            item { SectionLabel("${strings[Txt.TIME_JOB_ACTIVITY]} · ${mark(needs?.activity)}") }
            item { ChoiceRow(strings[Txt.TIME_JOB_NO_ACTIVITY], null, selected = activityId == null) { activityId = null } }
            items(options.activities, key = { "activity:${it.id}" }) { activity ->
                ChoiceRow(activity.name, activity.code, selected = activity.id == activityId) { activityId = activity.id }
            }
        }
        item { SectionLabel("${strings[Txt.TIME_JOB_NOTE]} · ${mark(needs?.note)}") }
        item {
            BotField(note, { note = it.take(500) }, strings[Txt.TIME_JOB_NOTE], Modifier.fillMaxWidth().padding(horizontal = BotSpace.xl), singleLine = false)
        }
        missing?.let { part -> item { InfoPanel(missingText(part, strings), tone = Tone.Warn) } }
        item {
            Column(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.lg), verticalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
                PrimaryButton(
                    strings[if (target == JobTarget.Switch) Txt.TIME_SWITCH_NOW else Txt.TIME_JOB_USE],
                    onClick = {
                        val job = JobRequest(clientId, projectId, activityId, note.trim().ifEmpty { null })
                        missing = JobRules.missing(job, policy)
                        if (missing == null) {
                            val prompt = BiometricPromptText(strings[Txt.TIME_PROMPT_TITLE], strings[Txt.TIME_SWITCH], strings[Txt.TIME_PROMPT_CANCEL])
                            if (target is JobTarget.Part) vm.setPartJob(target.index, job, options.label(job)) else vm.applyJob(target, job, prompt)
                            onDone()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    busy = ui.busy && target == JobTarget.Switch,
                )
                SecondaryButton(strings[Txt.ACTION_CANCEL], onClick = onDone, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * One shift as the employee sees it: its times and alerts, the planned shift it matched, what its time was for
 * ([onSplit] opens the editor while nobody approved it), and its clock record.
 */
@Composable
fun ShiftScreen(
    repository: TimeClockRepository,
    location: LocationProvider,
    signer: DeviceSigner,
    strings: Strings,
    clock: TenantClock,
    shiftId: String,
    padding: PaddingValues,
    onSplit: () -> Unit,
    onBack: () -> Unit,
) {
    val vm = timeClockViewModel(repository, location, signer)
    val ui by vm.state.collectAsState()
    val statusState by repository.status.state.collectAsState()
    val shiftsState by repository.shifts.state.collectAsState()
    val status = statusState.value
    val shift = shiftsState.value?.firstOrNull { it.id == shiftId } ?: status?.open?.takeIf { it.id == shiftId }
    val now = rememberNow(clock)

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader(strings[Txt.TIME_SHIFT], shift?.let { dayLabel(it.day, strings) } ?: strings[Txt.TIME_SHIFT]) {
                TextButton(onClick = onBack) { Text(strings[Txt.ACTION_BACK], color = BotColors.accentDeep) }
            }
        }
        if (shift == null) {
            item { EmptyState(strings[Txt.EMPTY_TITLE], strings[Txt.TIME_HISTORY_EMPTY]) }
            return@LazyColumn
        }
        val worked = workedNow(shift, now)
        item {
            MetricRow(
                leftLabel = range(shift),
                leftValue = hm(strings, worked),
                rightLabel = strings[Txt.TIME_BREAKS_TOTAL],
                rightValue = if (shift.breakMinutes > 0) hm(strings, shift.breakMinutes) else "—",
            )
        }
        item {
            val (review, reviewLabel) = reviewStatus(shift, strings)
            val plan = shift.planned?.let { plan ->
                buildList {
                    add("${strings[Txt.TIME_PLANNED]} ${plan.startTime}–${plan.endTime}")
                    if (shift.lateMinutes > 0) add(strings.format(Txt.TIME_LATE_BY, "time" to hm(strings, shift.lateMinutes)))
                    if (shift.earlyLeaveMinutes > 0) add(strings.format(Txt.TIME_LEFT_EARLY_BY, "time" to hm(strings, shift.earlyLeaveMinutes)))
                }.joinToString(" · ")
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = BotSpace.xl, vertical = BotSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(review, reviewLabel)
                plan?.let {
                    Spacer(Modifier.width(BotSpace.sm))
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
                }
            }
        }
        if (shift.flags.isNotEmpty()) item { InfoPanel(shift.flags.joinToString(" · ") { strings.timeFlag(it) }, tone = Tone.Warn) }
        item { Feedback(ui, strings) }
        if (shift.segments.isNotEmpty()) {
            item { SectionLabel(strings[Txt.TIME_WORK_LOG]) }
            // The running part's minutes move with the clock, as the shift's total does; the backend's are from its last answer.
            val before = shift.segments.dropLast(1).sumOf { it.minutes }
            itemsIndexed(shift.segments, key = { _, segment -> "segment:${segment.startAt}" }) { i, segment ->
                val running = shift.isOpen && i == shift.segments.lastIndex
                SegmentRow(segment, if (running) maxOf(segment.minutes, worked - before) else segment.minutes, strings)
            }
            if (shift.unallocatedMinutes > 0) item { InfoPanel(strings.format(Txt.TIME_UNALLOCATED, "time" to hm(strings, shift.unallocatedMinutes)), tone = Tone.Warn) }
        }
        if (status?.policy?.logsJobs == true) {
            item {
                Column(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm)) {
                    if (shift.approved) {
                        Hint(strings[Txt.TIME_SPLIT_APPROVED], Modifier)
                    } else {
                        SecondaryButton(
                            strings[Txt.TIME_WORK_LOG_EDIT],
                            onClick = {
                                vm.startSplit(shift)
                                onSplit()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !ui.busy,
                        )
                    }
                }
            }
        }
        if (shift.punches.isNotEmpty()) {
            item { SectionLabel(strings[Txt.TIME_PUNCHES]) }
            items(shift.punches.size) { i ->
                val punch = shift.punches[i]
                ListRow(
                    title = "${strings[punchLabel(punch.type)]} · ${punch.time}",
                    detail = listOfNotNull(
                        punch.job?.label,
                        punch.siteName,
                        strings[if (punch.verified) Txt.TIME_VERIFIED else Txt.TIME_NOT_VERIFIED],
                    ).joinToString(" · "),
                    leading = punch.time,
                )
            }
        }
    }
}

/**
 * Splits a shift's time between jobs: each part from its start (the first is the shift's) to the next part's, with a
 * job [onPickJob] picks. Saving goes back with [onDone].
 */
@Composable
fun SplitScreen(
    repository: TimeClockRepository,
    location: LocationProvider,
    signer: DeviceSigner,
    strings: Strings,
    shiftId: String,
    padding: PaddingValues,
    onPickJob: (JobTarget) -> Unit,
    onDone: () -> Unit,
) {
    val vm = timeClockViewModel(repository, location, signer)
    val ui by vm.state.collectAsState()
    val jobsState by repository.jobs.state.collectAsState()
    val shiftsState by repository.shifts.state.collectAsState()
    LaunchedEffect(repository) { repository.jobs.load() }
    // Opened without a draft of this shift (after a restart, say): start from what the backend has, once it has come.
    // Not keyed on the draft itself: a save clears it, and starting again then would wipe the "saved" line.
    val stored = shiftsState.value?.firstOrNull { it.id == shiftId }
    LaunchedEffect(shiftId, stored != null) {
        if (stored != null && vm.state.value.split?.shiftId != shiftId) vm.startSplit(stored)
    }
    LaunchedEffect(ui.done) {
        if (ui.done == TimeClockDone.SPLIT_SAVED) onDone()
    }
    val split = ui.split?.takeIf { it.shiftId == shiftId }
    val options: JobOptions? = jobsState.value

    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader(strings[Txt.TIME_WORK_LOG], strings[Txt.TIME_SPLIT_TITLE]) {
                TextButton(onClick = {
                    vm.cancelSplit()
                    onDone()
                }) { Text(strings[Txt.ACTION_CANCEL], color = BotColors.accentDeep) }
            }
        }
        if (split == null) {
            item { LoadingScreen() }
            return@LazyColumn
        }
        item { Hint(strings[Txt.TIME_SPLIT_HINT]) }
        itemsIndexed(split.parts, key = { i, _ -> "part:$i" }) { i, part ->
            Panel(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm)) {
                if (i == 0) {
                    Text(strings.format(Txt.TIME_SPLIT_START, "time" to part.start), style = MaterialTheme.typography.titleSmall, color = BotColors.ink)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BotField(part.start, { vm.setPartStart(i, it) }, strings[Txt.TIME_SPLIT_FROM], Modifier.weight(1f))
                        Spacer(Modifier.width(BotSpace.sm))
                        TextButton(onClick = { vm.removePart(i) }, enabled = !ui.busy) {
                            Text(strings[Txt.TIME_SPLIT_REMOVE], color = BotColors.badInk)
                        }
                    }
                }
                Spacer(Modifier.height(BotSpace.xs))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        options?.label(part.job) ?: part.label ?: strings[Txt.TIME_JOB_NONE],
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (part.job == null) BotColors.inkMuted else BotColors.ink,
                    )
                    TextButton(onClick = { onPickJob(JobTarget.Part(i)) }, enabled = !ui.busy) {
                        Text(strings[Txt.TIME_JOB_CHANGE], color = BotColors.accentDeep)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.sm), verticalArrangement = Arrangement.spacedBy(BotSpace.sm)) {
                SecondaryButton(strings[Txt.TIME_SPLIT_ADD], onClick = vm::addPart, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy)
                Feedback(ui, strings)
                PrimaryButton(strings[Txt.TIME_SPLIT_SAVE], onClick = { vm.saveSplit() }, modifier = Modifier.fillMaxWidth(), busy = ui.busy)
            }
        }
    }
}

/** A part of a shift's work log: its times, job, [minutes] and who set it. */
@Composable
private fun SegmentRow(segment: ShiftSegment, minutes: Int, strings: Strings) {
    ListRow(
        title = "${segment.startTime}–${segment.endTime ?: "…"} · ${hm(strings, minutes)}",
        detail = listOfNotNull(
            segment.job?.label ?: strings[Txt.TIME_JOB_NONE],
            segment.job?.let { job -> job.note?.takeIf { it != job.label } },
            segment.siteName,
            strings["time.source.${segment.source}"],
        ).joinToString(" · "),
        leading = segment.startTime,
        status = if (segment.complete) null else "PENDING",
        statusLabel = if (segment.complete) null else strings.timeFlag("UNALLOCATED"),
    )
}

/** A radio choice in the job picker. */
@Composable
private fun ChoiceRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = BotSpace.xl, vertical = BotSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = BotColors.accentDeep))
        Spacer(Modifier.width(BotSpace.sm))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = BotColors.ink)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted) }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier.padding(horizontal = BotSpace.xl, vertical = BotSpace.xs)) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = BotColors.inkMuted)
}

private fun punchLabel(type: String): String = when (type) {
    PunchRequest.IN -> Txt.TIME_PUNCH_IN
    PunchRequest.BREAK_START -> Txt.TIME_PUNCH_BREAK_START
    PunchRequest.BREAK_END -> Txt.TIME_PUNCH_BREAK_END
    PunchRequest.SWITCH -> Txt.TIME_PUNCH_SWITCH
    else -> Txt.TIME_PUNCH_OUT
}
