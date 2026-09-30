package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentSettings
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.store.AgentTaskRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.events.Actor
import com.rfm.edubot.instagram.InstagramSocialService
import com.rfm.edubot.integrations.email.EmailSender
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import com.rfm.edubot.whatsapp.WhatsAppClient
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject

/** Everything actions reach outside the run. Built once at startup; tests pass fakes. */
class AgentServices(
    val mongo: MongoModule,
    val aiClient: AiClient? = null,
    /** The tenant's outbound client for a channel, or null when that channel isn't connected. */
    val outbound: (Tenant, Platform) -> OutboundClient? = { _, _ -> null },
    val whatsApp: (Tenant) -> WhatsAppClient? = { null },
    val instagramSocial: InstagramSocialService? = null,
    val email: EmailSender? = null,
    val pdf: PdfGenerator = PdfGenerator(),
    val notifications: NotificationRepository = NotificationRepository(mongo),
    val tasks: AgentTaskRepository = AgentTaskRepository(mongo),
    val outboundLog: OutboundLogRepository = OutboundLogRepository(mongo),
    val dashboardUsers: DashboardUserRepository = DashboardUserRepository(mongo),
    val usage: (Tenant) -> TenantUsageRepository = { TenantUsageRepository(mongo, it.id) },
    /** Where generated quote and invoice PDFs are kept (`app.pdf.storagePath`). */
    val pdfStoragePath: () -> String = { "./data/pdfs" },
    val clock: () -> Instant = SystemClock::now,
)

/** What an action sees while a step runs. */
class RunContext(
    val tenant: Tenant,
    val run: AgentRun,
    val step: StepSpec,
    /** The run's variables (record, company, event, earlier step outputs, params). */
    val variables: JsonObject,
    val settings: AgentSettings,
    val services: AgentServices,
    val now: Instant,
    val autonomy: Autonomy,
) {
    val dryRun: Boolean get() = run.dryRun
    val zone: TimeZone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))

    /** The language messages are written in: the agent's voice, else the company's. */
    val locale: String get() = run.definition.voice.language?.takeIf { it.isNotBlank() } ?: tenant.locale

    /** One key per run step, so a send is attempted once however often the step is retried or resumed. */
    val idempotencyKey: String get() = "${run.id.toHexString()}:${step.id}"

    val actor: Actor get() = Actor.agent(run.agentId, run.id, run.agentName)
}
