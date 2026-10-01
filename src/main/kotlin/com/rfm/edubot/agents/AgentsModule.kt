package com.rfm.edubot.agents

import com.rfm.edubot.agents.registry.AgentAvailability
import com.rfm.edubot.agents.registry.AgentDefinitionValidator
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.Availability
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.agents.store.AgentApprovalRepository
import com.rfm.edubot.agents.store.AgentRepository
import com.rfm.edubot.agents.store.AgentRunRepository
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant

/** Whether a company can send email, and read its inbox, through a connected Google account. */
data class EmailAvailability(val send: Boolean = false, val inbox: Boolean = false)

/** The agents feature's composition root: registry, stores and services the routes and runtime share. */
class AgentsModule(
    val mongo: MongoModule,
    val registry: AgentRegistry,
    val services: AgentServices,
    private val emailAvailability: suspend (Tenant) -> EmailAvailability = { EmailAvailability() },
) {
    val agents = AgentRepository(mongo, services.clock)
    val runs = AgentRunRepository(mongo, services.clock)
    val approvals = AgentApprovalRepository(mongo, services.clock)
    val settings = AgentSettingsRepository(mongo, services.clock)
    val tasks get() = services.tasks
    val notifications get() = services.notifications
    val validator = AgentDefinitionValidator(registry)

    suspend fun availability(tenant: Tenant): Availability {
        val email = emailAvailability(tenant)
        return AgentAvailability.of(tenant, gmail = email.send, gmailInbox = email.inbox)
    }
}
