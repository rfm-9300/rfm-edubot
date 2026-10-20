package com.rfm.edubot.dashboard

import com.rfm.edubot.http.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.AttributeKey

/** How a module gate refuses a company that doesn't have the module. Each API keeps the answer its pages expect. */
internal enum class ModuleDenial(val body: ErrorBody?) {
    /** 403 without a body: the dashboard's own pages. */
    SILENT(null),

    /** 403 `{"error":"module_disabled"}`: the agents, email and integrations APIs, whose pages say so. */
    MODULE_DISABLED(ErrorBody("module_disabled")),
}

/** The routes under it need [modules]; [requireModules] puts the check on the same route. */
internal class ModuleGateSelector(val modules: Set<String>, val denial: ModuleDenial) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent
    override fun toString(): String = "(modules ${modules.joinToString("+")})"
}

/** The routes under it are for every dashboard session, whatever modules its company has, for [reason]. */
internal class OpenRouteSelector(val reason: String) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent
    override fun toString(): String = "(open)"
}

private class ModuleGateConfig {
    var modules: Set<String> = emptySet()
    var denial: ModuleDenial = ModuleDenial.SILENT
}

private val GatedContextKey = AttributeKey<DashboardContext>("GatedDashboardContext")

// Ktor runs only the innermost installation of a route-scoped plugin, so a nested gate is configured with its parents' modules too.
private val ModuleGate = createRouteScopedPlugin("ModuleGate", ::ModuleGateConfig) {
    val modules = pluginConfig.modules
    val denial = pluginConfig.denial
    on(AuthenticationChecked) { call ->
        val ctx = call.dashboardContext() ?: return@on call.respond(HttpStatusCode.Unauthorized)
        if (modules.all { ctx.requireModule(it) }) {
            call.attributes.put(GatedContextKey, ctx)
        } else {
            denial.body?.let { call.respond(HttpStatusCode.Forbidden, it) } ?: call.respond(HttpStatusCode.Forbidden)
        }
    }
}

/**
 * The routes [build] adds answer 403 ([denial]) before their handler runs, unless the company has every one of
 * [modules] and of the modules an enclosing gate needs. Handlers read the session with [moduleContext].
 */
internal fun Route.requireModules(vararg modules: String, denial: ModuleDenial? = null, build: Route.() -> Unit): Route {
    val outer = enclosingGate()
    val required = outer?.modules.orEmpty() + modules
    val refusal = denial ?: outer?.denial ?: ModuleDenial.SILENT
    val gated = createChild(ModuleGateSelector(required, refusal))
    gated.install(ModuleGate) {
        this.modules = required
        this.denial = refusal
    }
    gated.build()
    return gated
}

/**
 * Routes that every dashboard session reaches whatever its company's modules (its own account, the notification
 * bell…). [reason] says why; the route audit test lists every route under `/app/api` that is neither gated nor open.
 */
internal fun Route.openToEverySession(reason: String, build: Route.() -> Unit): Route =
    createChild(OpenRouteSelector(reason)).apply(build)

/** The session the module gate let through; only a route under [requireModules] has one. */
internal fun ApplicationCall.moduleContext(): DashboardContext =
    attributes.getOrNull(GatedContextKey) ?: error("${request.local.uri} is not behind a module gate")

/** The nearest gate above this route, if any. */
internal fun Route.enclosingGate(): ModuleGateSelector? =
    generateSequence(this) { it.parent }.firstNotNullOfOrNull { (it as? RoutingNode)?.selector as? ModuleGateSelector }
