package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.ChangeOrder
import com.rfm.edubot.mobile.core.model.ChangeStatus
import com.rfm.edubot.mobile.core.model.LineItem
import com.rfm.edubot.mobile.core.model.Measurement
import com.rfm.edubot.mobile.core.model.MeasurementInvoiced
import com.rfm.edubot.mobile.core.model.MySite
import com.rfm.edubot.mobile.core.model.MySiteDetail
import com.rfm.edubot.mobile.core.model.PortalClient
import com.rfm.edubot.mobile.core.model.Project
import com.rfm.edubot.mobile.core.model.SaveIssue
import com.rfm.edubot.mobile.core.model.SaveSiteLog
import com.rfm.edubot.mobile.core.model.ServiceSubmission
import com.rfm.edubot.mobile.core.model.SiteIssue
import com.rfm.edubot.mobile.core.model.SiteLog
import com.rfm.edubot.mobile.core.model.SubmitService
import com.rfm.edubot.mobile.core.network.BuildingApi
import com.rfm.edubot.mobile.core.network.MySitesApi
import com.rfm.edubot.mobile.core.network.PortalApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBuildingApi : BuildingApi {
    var projects = listOf(Project(id = "p1", name = "Moradia", status = "ACTIVE", progressPct = 10))
    var projectFetches = 0
    var diary = mutableListOf<SiteLog>()
    var changes = mutableListOf(ChangeOrder(id = "co1", status = "DRAFT"))
    var measurements = mutableListOf(Measurement(id = "m1", number = 1, status = "APPROVED"))
    val writtenDiary = mutableListOf<SaveSiteLog>()
    val loggedIssues = mutableListOf<SaveIssue>()

    override suspend fun projects(status: String?): List<Project> = projects.also { projectFetches++ }
    override suspend fun project(id: String): Project = projects.first { it.id == id }
    override suspend fun setProjectStatus(id: String, status: String): Project = project(id).copy(status = status)
    override suspend fun setPhaseProgress(projectId: String, phaseId: String, progressPct: Int): Project = project(projectId).copy(progressPct = progressPct)
    override suspend fun diary(projectId: String?, from: String?, to: String?): List<SiteLog> = diary
    override suspend fun diaryEntry(id: String): SiteLog = diary.first { it.id == id }
    override suspend fun writeDiary(request: SaveSiteLog): SiteLog {
        writtenDiary += request
        return SiteLog(id = "d-new", projectId = request.projectId.orEmpty(), day = request.day.orEmpty(), workDone = request.workDone.orEmpty())
    }
    override suspend fun editDiary(id: String, request: SaveSiteLog): SiteLog = SiteLog(id = id, workDone = request.workDone.orEmpty())
    override suspend fun issues(projectId: String?, status: String?): List<SiteIssue> = emptyList()
    override suspend fun issue(id: String): SiteIssue = SiteIssue(id = id)
    override suspend fun logIssue(request: SaveIssue): SiteIssue {
        loggedIssues += request
        return SiteIssue(id = "i-new", projectId = request.projectId.orEmpty(), title = request.title.orEmpty())
    }
    override suspend fun editIssue(id: String, request: SaveIssue): SiteIssue = SiteIssue(id = id, status = request.status ?: "OPEN")
    override suspend fun changes(projectId: String?, status: String?): List<ChangeOrder> = changes
    override suspend fun change(id: String): ChangeOrder = changes.first { it.id == id }
    override suspend fun setChangeStatus(id: String, request: ChangeStatus): ChangeOrder = change(id).copy(status = request.status, decidedBy = request.decidedBy)
    override suspend fun measurements(projectId: String?, status: String?): List<Measurement> = measurements
    override suspend fun measurement(id: String): Measurement = measurements.first { it.id == id }
    override suspend fun approveMeasurement(id: String): Measurement = measurement(id).copy(status = "APPROVED")
    override suspend fun reopenMeasurement(id: String): Measurement = measurement(id).copy(status = "DRAFT")
    override suspend fun invoiceMeasurement(id: String, dueDate: String): MeasurementInvoiced =
        MeasurementInvoiced(measurement(id).copy(status = "INVOICED", invoiceNumber = "FT 1"), "inv1", "FT 1")
}

class BuildingRepositoryTest {
    private fun repository(api: FakeBuildingApi = FakeBuildingApi()) = BuildingRepository(api, SnapshotCache(InMemorySnapshotStore())) to api

    @Test
    fun `writing the diary sends the project and puts the entry on top`() = runTest {
        val (repository, api) = repository()
        repository.diary.load()
        repository.projects.load()

        repository.writeDiary("p1", SaveSiteLog(day = "2026-11-10", workDone = "Betonagem"))

        assertEquals("p1", api.writtenDiary.single().projectId)
        assertEquals(listOf("d-new"), repository.diary.state.value.value?.map { it.id })
        assertEquals(2, api.projectFetches, "a diary entry can move a phase, so the projects are read again")
    }

    @Test
    fun `logging an issue sends the project it is for`() = runTest {
        val (repository, api) = repository()
        repository.issues.load()

        repository.logIssue("p1", SaveIssue(title = "Fissura"))

        assertEquals("p1", api.loggedIssues.single().projectId)
        assertEquals(listOf("i-new"), repository.issues.state.value.value?.map { it.id })
    }

    @Test
    fun `deciding a change replaces its row`() = runTest {
        val (repository, _) = repository()
        repository.changes.load()

        repository.setChangeStatus(repository.changes.state.value.value!!.single(), ChangeStatus("APPROVED", decidedBy = "Ana"))

        assertEquals("APPROVED", repository.changes.state.value.value!!.single().status)
    }

    @Test
    fun `invoicing a measurement marks it invoiced in the list`() = runTest {
        val (repository, _) = repository()
        repository.measurements.load()

        val invoiced = repository.invoiceMeasurement(repository.measurements.state.value.value!!.single(), "2026-12-01")

        assertTrue(invoiced is Outcome.Success)
        assertEquals("INVOICED", repository.measurements.state.value.value!!.single().status)
    }

    @Test
    fun `a phase's progress is kept between 0 and 100`() = runTest {
        val (repository, _) = repository()
        val project = repository.project("p1").valueOrNull!!

        val updated = repository.setPhaseProgress(project, "ph1", 140)

        assertEquals(100, updated.valueOrNull?.progressPct)
    }
}

private class FakeMySitesApi : MySitesApi {
    val diary = mutableListOf<Pair<String, SaveSiteLog>>()
    var siteFetches = 0

    override suspend fun sites(): List<MySite> = listOf(MySite(id = "p1", name = "Moradia")).also { siteFetches++ }
    override suspend fun site(id: String): MySiteDetail = MySiteDetail(site = MySite(id = id, name = "Moradia"))
    override suspend fun writeDiary(siteId: String, request: SaveSiteLog): SiteLog {
        diary += siteId to request
        return SiteLog(id = "d1", projectId = siteId)
    }
    override suspend fun editDiary(siteId: String, entryId: String, request: SaveSiteLog): SiteLog = SiteLog(id = entryId)
    override suspend fun logIssue(siteId: String, request: SaveIssue): SiteIssue = SiteIssue(id = "i1")
    override suspend fun editIssue(siteId: String, issueId: String, request: SaveIssue): SiteIssue = SiteIssue(id = issueId)
}

class MySitesRepositoryTest {
    @Test
    fun `a site manager's diary goes to their site and the list says it is written`() = runTest {
        val api = FakeMySitesApi()
        val repository = MySitesRepository(api, SnapshotCache(InMemorySnapshotStore()), employeeId = "e1")
        repository.sites.load()

        repository.writeDiary("p1", SaveSiteLog(projectId = "p1", workDone = "Reboco"))

        val (site, request) = api.diary.single()
        assertEquals("p1", site)
        assertNull(request.projectId, "the site is in the path; the portal's body has no project")
        assertEquals(2, api.siteFetches)
    }
}

private class FakePortalApi : PortalApi {
    var rows = mutableListOf(ServiceSubmission(id = "s1", name = "Pintura", performedAt = "2026-11-01"))
    override suspend fun clients(): List<PortalClient> = listOf(PortalClient("c1", "CLT-001", "Ana"))
    override suspend fun catalog(): List<CatalogItem> = emptyList()
    override suspend fun myServices(): List<ServiceSubmission> = rows
    override suspend fun submit(request: SubmitService): ServiceSubmission =
        ServiceSubmission(id = "s-new", name = request.name.orEmpty(), clientId = request.clientId, performedAt = request.performedAt)
    override suspend fun update(id: String, request: SubmitService): ServiceSubmission = rows.first { it.id == id }.copy(name = request.name.orEmpty())
    override suspend fun withdraw(id: String) {
        rows.removeAll { it.id == id }
    }
}

class PortalRepositoryTest {
    private fun repository() = PortalRepository(FakePortalApi(), SnapshotCache(InMemorySnapshotStore()), employeeId = "e1")

    @Test
    fun `a registered service appears with the others`() = runTest {
        val repository = repository()
        repository.services.load()

        repository.save(SubmitService("c1", "2026-11-10", listOf(LineItem("Limpeza", 1.0, "", 40.0)), name = "Limpeza"))

        assertEquals(listOf("s-new", "s1"), repository.services.state.value.value?.map { it.id })
    }

    @Test
    fun `changing a pending service replaces it rather than adding one`() = runTest {
        val repository = repository()
        repository.services.load()

        repository.save(SubmitService("c1", "2026-11-01", listOf(LineItem("Pintura", 1.0, "", 80.0)), name = "Pintura e massa"), id = "s1")

        assertEquals(listOf("Pintura e massa"), repository.services.state.value.value?.map { it.name })
    }

    @Test
    fun `withdrawing takes the service off the list`() = runTest {
        val repository = repository()
        repository.services.load()

        repository.withdraw(repository.services.state.value.value!!.single())

        assertEquals(emptyList(), repository.services.state.value.value)
    }

    @Test
    fun `each employee's services are cached apart`() = runTest {
        val cache = SnapshotCache(InMemorySnapshotStore())
        val first = PortalRepository(FakePortalApi(), cache, employeeId = "e1")
        first.services.load()
        val second = PortalRepository(object : PortalApi by FakePortalApi() {
            override suspend fun myServices(): List<ServiceSubmission> = error("offline")
        }, cache, employeeId = "e2")

        second.services.load()

        assertNull(second.services.state.value.value, "the next person on a shared phone never sees the previous one's rows")
    }
}
