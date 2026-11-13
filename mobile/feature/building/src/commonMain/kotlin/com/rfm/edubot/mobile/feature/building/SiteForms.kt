package com.rfm.edubot.mobile.feature.building

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.common.parseDay
import com.rfm.edubot.mobile.core.data.SiteWriter
import com.rfm.edubot.mobile.core.model.PhaseProgressEntry
import com.rfm.edubot.mobile.core.model.SaveIssue
import com.rfm.edubot.mobile.core.model.SaveSiteLog
import com.rfm.edubot.mobile.core.model.SiteLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A project the form can be for, and its phases as they stand. */
data class ProjectOption(val id: String, val label: String)

data class PhaseOption(val id: String, val name: String, val progressPct: Int)

/** What a form needs to know: where it may write, and each project's phases. */
data class SiteFormContext(
    val projects: List<ProjectOption> = emptyList(),
    val phases: List<PhaseOption> = emptyList(),
)

data class DiaryFormState(
    val context: SiteFormContext = SiteFormContext(),
    val projectId: String? = null,
    val day: String = "",
    val weather: String = "",
    val temperature: String = "",
    val workers: String = "",
    val workDone: String = "",
    val equipment: String = "",
    val materials: String = "",
    val visitors: String = "",
    val incidents: String = "",
    val lostHours: String = "",
    val shared: Boolean = false,
    /** Phase id to the progress typed, only for the phases someone touched. */
    val progress: Map<String, String> = emptyMap(),
    val editing: SiteLog? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
) {
    val dayValid: Boolean get() = editing != null || parseDay(day) != null

    val canSave: Boolean get() = !saving && projectId != null && dayValid && workDone.isNotBlank()
}

/**
 * The site diary's form: one entry per project and day, which can move the phases' progress. The
 * team writes through `/building/diary`, a site manager through their My sites, and [writer] is
 * whichever that is. [loadContext] gives the projects to pick from (when [projectId] is null) and a
 * project's phases.
 */
class DiaryFormViewModel(
    private val writer: SiteWriter,
    private val loadContext: suspend (projectId: String?) -> SiteFormContext,
    private val today: String,
    projectId: String?,
    private val existing: SiteLog? = null,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(existing?.toState() ?: DiaryFormState(projectId = projectId, day = today))
    val state: StateFlow<DiaryFormState> = mutable.asStateFlow()

    fun load() = scope.launch {
        mutable.update { it.copy(loading = true) }
        val context = loadContext(mutable.value.projectId)
        mutable.update { it.copy(context = context, loading = false) }
    }

    fun pickProject(id: String) = scope.launch {
        mutable.update { it.copy(projectId = id, progress = emptyMap(), error = null) }
        val context = loadContext(id)
        mutable.update { it.copy(context = context) }
    }

    fun edit(transform: DiaryFormState.() -> DiaryFormState) = mutable.update { it.transform().copy(error = null) }

    fun setProgress(phaseId: String, value: String) = mutable.update { it.copy(progress = it.progress + (phaseId to value), error = null) }

    fun save() = scope.launch {
        val current = mutable.value
        val projectId = current.projectId ?: return@launch
        if (!current.canSave) return@launch
        mutable.value = current.copy(saving = true, error = null)
        val request = current.toRequest()
        val saved = current.editing?.let { writer.editDiary(projectId, it.id, request) } ?: writer.writeDiary(projectId, request)
        when (saved) {
            is Outcome.Success -> mutable.update { it.copy(saving = false, saved = true) }
            is Outcome.Failure -> mutable.update { it.copy(saving = false, error = saved.error) }
        }
    }
}

private fun SiteLog.toState() = DiaryFormState(
    projectId = projectId,
    day = day,
    weather = weather.orEmpty(),
    temperature = temperatureC?.toString().orEmpty(),
    workers = workers?.toString().orEmpty(),
    workDone = workDone,
    equipment = equipment.orEmpty(),
    materials = materials.orEmpty(),
    visitors = visitors.orEmpty(),
    incidents = incidents.orEmpty(),
    lostHours = lostHours?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }.orEmpty(),
    shared = shared,
    progress = progress.associate { it.phaseId to it.toPct.toString() },
    editing = this,
)

/**
 * Every text the form shows goes as typed (an empty one clears it on an edit); a number left empty
 * is named in `clear`, since a JSON null can't say "clear". Only phases whose progress was typed move.
 */
internal fun DiaryFormState.toRequest(): SaveSiteLog {
    val temperatureC = temperature.trim().toIntOrNull()
    val workerCount = workers.trim().toIntOrNull()
    val hours = lostHours.trim().replace(',', '.').toDoubleOrNull()
    return SaveSiteLog(
        day = if (editing == null) day.trim() else null,
        weather = weather,
        temperatureC = temperatureC,
        workers = workerCount,
        workDone = workDone.trim(),
        equipment = equipment.trim(),
        materials = materials.trim(),
        visitors = visitors.trim(),
        incidents = incidents.trim(),
        lostHours = hours,
        shared = shared,
        phaseProgress = progress.mapNotNull { (id, text) -> text.trim().toIntOrNull()?.let { PhaseProgressEntry(id, it.coerceIn(0, 100)) } }
            .takeIf { it.isNotEmpty() },
        // Only a number the entry had and the person erased; a new entry has nothing to clear.
        clear = editing?.let { stored ->
            listOfNotNull(
                "temperatureC".takeIf { temperatureC == null && stored.temperatureC != null },
                "workers".takeIf { workerCount == null && stored.workers != null },
                "lostHours".takeIf { hours == null && stored.lostHours != null },
            )
        }.orEmpty(),
    )
}

data class IssueFormState(
    val context: SiteFormContext = SiteFormContext(),
    val projectId: String? = null,
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val kind: String = "PUNCH",
    val priority: String = "MEDIUM",
    val phaseId: String = "",
    val dueDate: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
) {
    val dueValid: Boolean get() = dueDate.isBlank() || parseDay(dueDate) != null

    val canSave: Boolean get() = !saving && projectId != null && title.isNotBlank() && dueValid
}

/** Logging an issue on site: what's wrong, where, how bad, and by when. */
class IssueFormViewModel(
    private val writer: SiteWriter,
    private val loadContext: suspend (projectId: String?) -> SiteFormContext,
    projectId: String?,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private val mutable = MutableStateFlow(IssueFormState(projectId = projectId))
    val state: StateFlow<IssueFormState> = mutable.asStateFlow()

    fun load() = scope.launch {
        mutable.update { it.copy(loading = true) }
        val context = loadContext(mutable.value.projectId)
        mutable.update { it.copy(context = context, loading = false) }
    }

    fun pickProject(id: String) = scope.launch {
        mutable.update { it.copy(projectId = id, phaseId = "", error = null) }
        val context = loadContext(id)
        mutable.update { it.copy(context = context) }
    }

    fun edit(transform: IssueFormState.() -> IssueFormState) = mutable.update { it.transform().copy(error = null) }

    fun save() = scope.launch {
        val current = mutable.value
        val projectId = current.projectId ?: return@launch
        if (!current.canSave) return@launch
        mutable.value = current.copy(saving = true, error = null)
        val request = SaveIssue(
            title = current.title.trim(),
            description = current.description.trim().ifBlank { null },
            location = current.location.trim().ifBlank { null },
            kind = current.kind,
            priority = current.priority,
            phaseId = current.phaseId.ifBlank { null },
            dueDate = current.dueDate.trim().ifBlank { null },
        )
        when (val saved = writer.logIssue(projectId, request)) {
            is Outcome.Success -> mutable.update { it.copy(saving = false, saved = true) }
            is Outcome.Failure -> mutable.update { it.copy(saving = false, error = saved.error) }
        }
    }
}
