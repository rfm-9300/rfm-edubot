package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.bookings.BookingRoutes
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.dashboard.building.ChangeOrderRoutes
import com.rfm.edubot.dashboard.building.MeasurementRoutes
import com.rfm.edubot.dashboard.building.ProjectRoutes
import com.rfm.edubot.dashboard.building.SiteDiaryRoutes
import com.rfm.edubot.dashboard.building.SiteIssueRoutes
import com.rfm.edubot.dashboard.crm.CatalogRoutes
import com.rfm.edubot.dashboard.crm.ClientRoutes
import com.rfm.edubot.dashboard.crm.EmployeeRoutes
import com.rfm.edubot.dashboard.crm.InvoiceRoutes
import com.rfm.edubot.dashboard.crm.PaymentRoutes
import com.rfm.edubot.dashboard.crm.QuoteRoutes
import com.rfm.edubot.dashboard.crm.ServiceRoutes
import com.rfm.edubot.dashboard.crm.SupplierRoutes
import com.rfm.edubot.dashboard.inbox.ContactRoutes
import com.rfm.edubot.dashboard.inbox.InboxRoutes
import com.rfm.edubot.dashboard.settings.DocumentTemplateRoutes
import com.rfm.edubot.dashboard.settings.SettingsRoutes
import com.rfm.edubot.instagram.InstagramRoutes
import com.rfm.edubot.instagram.InstagramSocialService
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaCompiler
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRepository
import io.ktor.server.routing.Route

/**
 * One area of the dashboard's API, mounted under `/app/api` behind a gate for [modules]: a company without one of
 * them gets [denial] before any handler runs. A new area is a new file with an object like this, listed in
 * [DashboardFeatures.all]; a route that needs more modules than its area nests [requireModules].
 */
internal abstract class DashboardFeature(vararg modules: String, val denial: ModuleDenial = ModuleDenial.SILENT) {
    val modules: Set<String> = modules.toSet()

    /** Paths are relative to `/app/api`. Handlers read the session with [moduleContext]. */
    abstract fun Route.routes(deps: DashboardDeps)
}

/** What the dashboard's areas are built from, once per application. */
internal class DashboardDeps(
    val mongo: MongoModule,
    val tenants: TenantRepository,
    val dashboardUsers: DashboardUserRepository,
    val pipelineFactory: TenantPipelineFactory,
    val personaCompiler: PersonaCompiler,
    val aiClient: AiClient,
    val runtimeConfig: RuntimeConfig,
    val channelBindings: ChannelBindingService,
    val instagramSocial: InstagramSocialService,
    val assistantExtension: AssistantExtension?,
) {
    /** The inbox's sender, which the assistant also replies to customer chats with. */
    val inbox = InboxService(mongo, { pipelineFactory.whatsAppFor(it) }, { tenant, platform -> pipelineFactory.responderFor(tenant, platform) })
}

/** Every area of the dashboard's API, mounted in this order. */
internal object DashboardFeatures {
    val all: List<DashboardFeature> = listOf(
        OverviewRoutes,
        ContactRoutes,
        InboxRoutes,
        PersonaRoutes,
        SettingsRoutes,
        DocumentTemplateRoutes,
        AssistantRoutes,
        ClientRoutes,
        CatalogRoutes,
        QuoteRoutes,
        InvoiceRoutes,
        ServiceRoutes,
        SupplierRoutes,
        EmployeeRoutes,
        PaymentRoutes,
        ProjectRoutes,
        SiteDiaryRoutes,
        SiteIssueRoutes,
        ChangeOrderRoutes,
        MeasurementRoutes,
        BookingRoutes,
        InstagramRoutes,
    )
}

/** Mounts [features], each behind its gate. */
internal fun Route.installDashboardFeatures(deps: DashboardDeps, features: List<DashboardFeature> = DashboardFeatures.all) {
    for (feature in features) {
        requireModules(*feature.modules.toTypedArray(), denial = feature.denial) {
            with(feature) { routes(deps) }
        }
    }
}
