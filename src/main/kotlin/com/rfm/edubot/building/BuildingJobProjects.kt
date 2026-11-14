package com.rfm.edubot.building

import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.timesheets.JobProject
import com.rfm.edubot.timesheets.JobProjects
import org.bson.types.ObjectId

/** Building's projects for the time clock's jobs: the time clock asks through [JobProjects] and never imports Building. */
class BuildingJobProjects(private val mongo: MongoModule) : JobProjects {
    override suspend fun open(tenantId: ObjectId): List<JobProject> =
        ProjectRepository(mongo, tenantId).list(listOf(ProjectStatus.PLANNING, ProjectStatus.ACTIVE)).sortedBy { it.number }.map { it.job() }

    override suspend fun find(tenantId: ObjectId, id: ObjectId): JobProject? = ProjectRepository(mongo, tenantId).findById(id)?.job()

    private fun Project.job() = JobProject(id, number, name, clientId)
}
