package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.toObjectIdOrNull
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/** A Building project as a job names it. */
data class JobProject(val id: ObjectId, val number: String, val name: String, val clientId: ObjectId?)

/**
 * The Building projects a job can name. Building implements it and `AppRoutes` passes it in: Building reads the
 * time clock (job costing), so the time clock never imports Building.
 */
interface JobProjects {
    /** The projects people may be working on: planned or running. */
    suspend fun open(tenantId: ObjectId): List<JobProject>

    /** Any of the company's projects, for a job someone sends. */
    suspend fun find(tenantId: ObjectId, id: ObjectId): JobProject?

    /** A company without Building, or a test that doesn't need it. */
    object None : JobProjects {
        override suspend fun open(tenantId: ObjectId): List<JobProject> = emptyList()
        override suspend fun find(tenantId: ObjectId, id: ObjectId): JobProject? = null
    }
}

/** A job as someone sends it: ids of the company's records and a note; every part may be left out. */
@Serializable
internal data class JobRequest(
    val clientId: String? = null,
    val projectId: String? = null,
    val activityId: String? = null,
    val note: String? = null,
)

/**
 * The company's rules as they apply to it: a job can only need a client when the company has clients, and a project
 * when it has Building's projects.
 */
internal fun TimesheetSettings.forCompany(tenant: Tenant): TimesheetSettings {
    val modules = DashboardModules.effectiveFor(tenant)
    return copy(
        jobNeeds = jobNeeds.copy(
            client = jobNeeds.client && DashboardModules.CLIENTS in modules,
            project = jobNeeds.project && DashboardModules.PROJECTS in modules,
        ),
    )
}

/**
 * Turns a job someone sent into a [Job]: each part must be one of the company's records (archived clients and
 * activities out of use included, so an older shift can still be edited), and its name is kept as it is now. A
 * project with a client brings that client.
 */
internal class JobResolver(private val mongo: MongoModule, private val tenant: Tenant, private val projects: JobProjects) {
    private val modules = DashboardModules.effectiveFor(tenant)
    private val hasClients = DashboardModules.CLIENTS in modules
    private val hasProjects = DashboardModules.PROJECTS in modules

    suspend fun resolve(request: JobRequest?): Outcome<Job?> {
        if (request == null) return Outcome.Ok(null)
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > ShiftMath.MAX_NOTE) return Outcome.Refused("note_too_long", 400)
        val clientId = request.clientId.id() ?: return Outcome.Refused("client_not_found", 400)
        val projectId = request.projectId.id() ?: return Outcome.Refused("project_not_found", 400)
        val activityId = request.activityId.id() ?: return Outcome.Refused("activity_not_found", 400)
        if (clientId.value == null && projectId.value == null && activityId.value == null && note == null) return Outcome.Ok(null)

        val clients = ClientRepository(mongo, tenant.id)
        var client = clientId.value?.let { id ->
            if (!hasClients) return Outcome.Refused("client_not_found", 400)
            clients.findById(id) ?: return Outcome.Refused("client_not_found", 400)
        }
        val project = projectId.value?.let { id ->
            if (!hasProjects) return Outcome.Refused("project_not_found", 400)
            projects.find(tenant.id, id) ?: return Outcome.Refused("project_not_found", 400)
        }
        val owner = project?.clientId
        if (owner != null) {
            if (client != null && client.id != owner) return Outcome.Refused("project_client_mismatch", 400)
            if (client == null && hasClients) client = clients.findById(owner)
        }
        val activity = activityId.value?.let { id -> ActivityRepository(mongo, tenant.id).findById(id) ?: return Outcome.Refused("activity_not_found", 400) }
        return Outcome.Ok(
            Job(
                clientId = client?.id,
                clientName = client?.name,
                projectId = project?.id,
                projectName = project?.name,
                activityId = activity?.id,
                activityName = activity?.name,
                note = note,
                billable = activity?.billable ?: (client != null || project != null),
            ),
        )
    }

    private class Ref(val value: ObjectId?)

    /** Blank is no record; anything else must be an id, or null. */
    private fun String?.id(): Ref? {
        val raw = this?.trim().orEmpty()
        if (raw.isEmpty()) return Ref(null)
        return raw.toObjectIdOrNull()?.let(::Ref)
    }
}
