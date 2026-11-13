package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.ChangeOrder
import com.rfm.edubot.mobile.core.model.ChangeStatus
import com.rfm.edubot.mobile.core.model.Measurement
import com.rfm.edubot.mobile.core.model.MeasurementInvoiced
import com.rfm.edubot.mobile.core.model.MySite
import com.rfm.edubot.mobile.core.model.MySiteDetail
import com.rfm.edubot.mobile.core.model.Project
import com.rfm.edubot.mobile.core.model.SaveIssue
import com.rfm.edubot.mobile.core.model.SaveSiteLog
import com.rfm.edubot.mobile.core.model.SiteIssue
import com.rfm.edubot.mobile.core.model.SiteLog
import com.rfm.edubot.mobile.core.network.BuildingApi
import com.rfm.edubot.mobile.core.network.MySitesApi
import kotlinx.serialization.builtins.ListSerializer

/**
 * Where a diary entry or an issue is written. The team writes through `/building/…`, a site manager
 * through their own `/portal/sites/{id}/…`; the forms take one of these and don't know which.
 */
interface SiteWriter {
    suspend fun writeDiary(projectId: String, request: SaveSiteLog): Outcome<SiteLog>
    suspend fun editDiary(projectId: String, entryId: String, request: SaveSiteLog): Outcome<SiteLog>
    suspend fun logIssue(projectId: String, request: SaveIssue): Outcome<SiteIssue>
    suspend fun editIssue(projectId: String, issueId: String, request: SaveIssue): Outcome<SiteIssue>
}

/** The Building category's team pages. */
class BuildingRepository(
    private val api: BuildingApi,
    cache: SnapshotCache,
) : SiteWriter {
    val projects = CachedResource(
        key = "building.projects",
        serializer = ListSerializer(Project.serializer()),
        cache = cache,
        fetch = { api.projects() },
    )

    /** The last two weeks across every project, as the web's Site diary opens. */
    val diary = CachedResource(
        key = "building.diary",
        serializer = ListSerializer(SiteLog.serializer()),
        cache = cache,
        fetch = { api.diary() },
    )

    val issues = CachedResource(
        key = "building.issues",
        serializer = ListSerializer(SiteIssue.serializer()),
        cache = cache,
        fetch = { api.issues() },
    )

    val changes = CachedResource(
        key = "building.changes",
        serializer = ListSerializer(ChangeOrder.serializer()),
        cache = cache,
        fetch = { api.changes() },
    )

    val measurements = CachedResource(
        key = "building.measurements",
        serializer = ListSerializer(Measurement.serializer()),
        cache = cache,
        fetch = { api.measurements() },
    )

    suspend fun project(id: String): Outcome<Project> = apiCall { api.project(id) }

    suspend fun setProjectStatus(project: Project, status: String): Outcome<Project> =
        replaceProject(apiCall { api.setProjectStatus(project.id, status) })

    suspend fun setPhaseProgress(project: Project, phaseId: String, progressPct: Int): Outcome<Project> =
        replaceProject(apiCall { api.setPhaseProgress(project.id, phaseId, progressPct.coerceIn(0, 100)) })

    suspend fun diaryFor(projectId: String): Outcome<List<SiteLog>> = apiCall { api.diary(projectId = projectId) }

    suspend fun diaryEntry(id: String): Outcome<SiteLog> = apiCall { api.diaryEntry(id) }

    suspend fun issuesFor(projectId: String): Outcome<List<SiteIssue>> = apiCall { api.issues(projectId = projectId) }

    suspend fun issue(id: String): Outcome<SiteIssue> = apiCall { api.issue(id) }

    suspend fun changesFor(projectId: String): Outcome<List<ChangeOrder>> = apiCall { api.changes(projectId = projectId) }

    suspend fun change(id: String): Outcome<ChangeOrder> = apiCall { api.change(id) }

    suspend fun measurementsFor(projectId: String): Outcome<List<Measurement>> = apiCall { api.measurements(projectId = projectId) }

    suspend fun measurement(id: String): Outcome<Measurement> = apiCall { api.measurement(id) }

    override suspend fun writeDiary(projectId: String, request: SaveSiteLog): Outcome<SiteLog> {
        val written = apiCall { api.writeDiary(request.copy(projectId = projectId)) }
        written.valueOrNull?.let { entry ->
            diary.mutate { listOf(entry) + it }
            projects.refresh()
        }
        return written
    }

    override suspend fun editDiary(projectId: String, entryId: String, request: SaveSiteLog): Outcome<SiteLog> {
        val edited = apiCall { api.editDiary(entryId, request) }
        edited.valueOrNull?.let { entry -> diary.mutate { current -> current.map { if (it.id == entry.id) entry else it } } }
        return edited
    }

    override suspend fun logIssue(projectId: String, request: SaveIssue): Outcome<SiteIssue> {
        val logged = apiCall { api.logIssue(request.copy(projectId = projectId)) }
        logged.valueOrNull?.let { issue -> issues.mutate { listOf(issue) + it } }
        return logged
    }

    override suspend fun editIssue(projectId: String, issueId: String, request: SaveIssue): Outcome<SiteIssue> {
        val edited = apiCall { api.editIssue(issueId, request) }
        edited.valueOrNull?.let { issue -> issues.mutate { current -> current.map { if (it.id == issue.id) issue else it } } }
        return edited
    }

    /** Sends a draft to the client, decides a sent one for them, or takes it back to draft. */
    suspend fun setChangeStatus(change: ChangeOrder, request: ChangeStatus): Outcome<ChangeOrder> {
        val updated = apiCall { api.setChangeStatus(change.id, request) }
        updated.valueOrNull?.let { saved -> changes.mutate { current -> current.map { if (it.id == saved.id) saved else it } } }
        return updated
    }

    suspend fun approveMeasurement(measurement: Measurement): Outcome<Measurement> =
        replaceMeasurement(apiCall { api.approveMeasurement(measurement.id) })

    suspend fun reopenMeasurement(measurement: Measurement): Outcome<Measurement> =
        replaceMeasurement(apiCall { api.reopenMeasurement(measurement.id) })

    /** Bills an approved measurement as an ordinary invoice to the project's client. */
    suspend fun invoiceMeasurement(measurement: Measurement, dueDate: String): Outcome<MeasurementInvoiced> {
        val invoiced = apiCall { api.invoiceMeasurement(measurement.id, dueDate) }
        invoiced.valueOrNull?.let { replaceMeasurement(Outcome.Success(it.measurement)) }
        return invoiced
    }

    private suspend fun replaceProject(updated: Outcome<Project>): Outcome<Project> {
        updated.valueOrNull?.let { saved -> projects.mutate { current -> current.map { if (it.id == saved.id) saved else it } } }
        return updated
    }

    private suspend fun replaceMeasurement(updated: Outcome<Measurement>): Outcome<Measurement> {
        updated.valueOrNull?.let { saved -> measurements.mutate { current -> current.map { if (it.id == saved.id) saved else it } } }
        return updated
    }
}

/**
 * A site manager's My sites. Keyed by [employeeId] like the time clock, so a shared phone never shows
 * one employee's sites to the next.
 */
class MySitesRepository(
    private val api: MySitesApi,
    cache: SnapshotCache,
    val employeeId: String,
) : SiteWriter {
    val sites = CachedResource(
        key = "portal.sites.$employeeId",
        serializer = ListSerializer(MySite.serializer()),
        cache = cache,
        fetch = { api.sites() },
    )

    suspend fun site(id: String): Outcome<MySiteDetail> = apiCall { api.site(id) }

    override suspend fun writeDiary(projectId: String, request: SaveSiteLog): Outcome<SiteLog> =
        apiCall { api.writeDiary(projectId, request.copy(projectId = null)) }.also { if (it is Outcome.Success) sites.refresh() }

    override suspend fun editDiary(projectId: String, entryId: String, request: SaveSiteLog): Outcome<SiteLog> =
        apiCall { api.editDiary(projectId, entryId, request.copy(projectId = null)) }

    override suspend fun logIssue(projectId: String, request: SaveIssue): Outcome<SiteIssue> =
        apiCall { api.logIssue(projectId, request.copy(projectId = null)) }.also { if (it is Outcome.Success) sites.refresh() }

    override suspend fun editIssue(projectId: String, issueId: String, request: SaveIssue): Outcome<SiteIssue> =
        apiCall { api.editIssue(projectId, issueId, request.copy(projectId = null)) }.also { if (it is Outcome.Success) sites.refresh() }
}
