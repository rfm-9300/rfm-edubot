package com.rfm.edubot.mobile.feature.building

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.data.SiteWriter
import com.rfm.edubot.mobile.core.model.IssueStatus
import com.rfm.edubot.mobile.core.model.SaveIssue
import com.rfm.edubot.mobile.core.model.SaveSiteLog
import com.rfm.edubot.mobile.core.model.SiteIssue
import com.rfm.edubot.mobile.core.model.SiteLog
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingWriter : SiteWriter {
    val written = mutableListOf<Pair<String, SaveSiteLog>>()
    val edited = mutableListOf<Triple<String, String, SaveSiteLog>>()
    val issues = mutableListOf<Pair<String, SaveIssue>>()
    var refusal: AppError? = null

    override suspend fun writeDiary(projectId: String, request: SaveSiteLog): Outcome<SiteLog> {
        refusal?.let { return Outcome.Failure(it) }
        written += projectId to request
        return Outcome.Success(SiteLog(id = "d1", projectId = projectId))
    }

    override suspend fun editDiary(projectId: String, entryId: String, request: SaveSiteLog): Outcome<SiteLog> {
        edited += Triple(projectId, entryId, request)
        return Outcome.Success(SiteLog(id = entryId, projectId = projectId))
    }

    override suspend fun logIssue(projectId: String, request: SaveIssue): Outcome<SiteIssue> {
        issues += projectId to request
        return Outcome.Success(SiteIssue(id = "i1", projectId = projectId))
    }

    override suspend fun editIssue(projectId: String, issueId: String, request: SaveIssue): Outcome<SiteIssue> =
        Outcome.Success(SiteIssue(id = issueId, projectId = projectId, status = request.status.orEmpty()))
}

private val context = SiteFormContext(
    projects = listOf(ProjectOption("p1", "OB-001 · Moradia"), ProjectOption("p2", "OB-002 · Loja")),
    phases = listOf(PhaseOption("ph1", "Estrutura", 40), PhaseOption("ph2", "Acabamentos", 0)),
)

class DiaryFormTest {
    private fun TestScope.vm(writer: SiteWriter, projectId: String? = "p1", existing: SiteLog? = null) =
        DiaryFormViewModel(writer, { context }, today = "2026-11-10", projectId = projectId, existing = existing, scopeOverride = backgroundScope)

    @Test
    fun `a day's diary needs the work done before it saves`() = runTest {
        val vm = vm(RecordingWriter())
        vm.load().join()
        assertFalse(vm.state.value.canSave)

        vm.edit { copy(workDone = "Betonagem da laje") }

        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `a new entry goes to its project with today's day and only the phases that moved`() = runTest {
        val writer = RecordingWriter()
        val vm = vm(writer)
        vm.load().join()
        vm.edit { copy(workDone = "Betonagem", workers = "6", weather = "RAIN") }
        vm.setProgress("ph1", "55")

        vm.save().join()

        val (project, request) = writer.written.single()
        assertEquals("p1", project)
        assertEquals("2026-11-10", request.day)
        assertEquals(6, request.workers)
        assertEquals("RAIN", request.weather)
        assertEquals(listOf("ph1" to 55), request.phaseProgress?.map { it.phaseId to it.progressPct })
        assertTrue(request.clear.isEmpty())
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun `editing an entry clears the numbers left empty and keeps its day`() = runTest {
        val writer = RecordingWriter()
        val entry = SiteLog(id = "d9", projectId = "p1", day = "2026-11-09", workDone = "Reboco", workers = 4, temperatureC = 18)
        val vm = vm(writer, existing = entry)
        vm.load().join()
        vm.edit { copy(workers = "") }

        vm.save().join()

        val (_, id, request) = writer.edited.single()
        assertEquals("d9", id)
        assertNull(request.day, "the day of an entry isn't moved from the phone")
        assertEquals(listOf("workers"), request.clear)
        assertEquals(18, request.temperatureC)
    }

    @Test
    fun `a progress above 100 goes as 100`() {
        val request = DiaryFormState(projectId = "p1", day = "2026-11-10", workDone = "x", progress = mapOf("ph1" to "130")).toRequest()
        assertEquals(100, request.phaseProgress?.single()?.progressPct)
    }

    @Test
    fun `a day already written keeps the form open with the reason`() = runTest {
        val writer = RecordingWriter().apply { refusal = AppError.Rejected("day_taken", 409) }
        val vm = vm(writer)
        vm.load().join()
        vm.edit { copy(workDone = "x") }

        vm.save().join()

        assertEquals(AppError.Rejected("day_taken", 409), vm.state.value.error)
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun `without a project the team picks one first`() = runTest {
        val vm = vm(RecordingWriter(), projectId = null)
        vm.load().join()
        vm.edit { copy(workDone = "x") }
        assertFalse(vm.state.value.canSave)

        vm.pickProject("p2").join()

        assertEquals("p2", vm.state.value.projectId)
        assertTrue(vm.state.value.canSave)
    }
}

class IssueFormTest {
    @Test
    fun `an issue needs a title and a due date that is a date`() = runTest {
        val vm = IssueFormViewModel(RecordingWriter(), { context }, projectId = "p1", scopeOverride = backgroundScope)
        vm.load().join()
        assertFalse(vm.state.value.canSave)

        vm.edit { copy(title = "Fissura na parede", dueDate = "amanhã") }
        assertFalse(vm.state.value.canSave)

        vm.edit { copy(dueDate = "2026-11-20") }
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `a logged issue leaves out what was left empty`() = runTest {
        val writer = RecordingWriter()
        val vm = IssueFormViewModel(writer, { context }, projectId = "p1", scopeOverride = backgroundScope)
        vm.load().join()
        vm.edit { copy(title = "Fissura", kind = "DEFECT", priority = "HIGH", phaseId = "ph1") }

        vm.save().join()

        val (project, request) = writer.issues.single()
        assertEquals("p1", project)
        assertEquals("DEFECT", request.kind)
        assertEquals("ph1", request.phaseId)
        assertNull(request.dueDate)
        assertNull(request.description)
    }

    @Test
    fun `an issue moves forward first and can always be reopened`() {
        assertEquals(listOf(IssueStatus.IN_PROGRESS, IssueStatus.RESOLVED), nextIssueStatuses(IssueStatus.OPEN))
        assertEquals(IssueStatus.CLOSED, nextIssueStatuses(IssueStatus.RESOLVED).first())
        assertEquals(listOf(IssueStatus.OPEN), nextIssueStatuses(IssueStatus.CLOSED))
    }

    @Test
    fun `percentages read as the web writes them`() {
        assertEquals("42%", formatPct(42.0))
        assertEquals("42,5%", formatPct(42.46))
    }
}
