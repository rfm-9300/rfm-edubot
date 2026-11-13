package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.CategoryOutcome
import com.rfm.edubot.crm.api.CatalogCategoryRequest
import com.rfm.edubot.crm.api.createStandardItem
import com.rfm.edubot.crm.api.updateStandardItem
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.recordTagRoutes
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.tags.TagKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * `/crm/standard-items`: the catalog of services and materials that quotes, invoices and bookings pick from, and
 * `/crm/catalog-categories`: the categories it is sorted into (listed, created, renamed and deleted by name).
 */
internal object CatalogRoutes : DashboardFeature(DashboardModules.CATALOG) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            recordTagRoutes(TagKind.CATALOG_ITEM, "standard-items", deps)
            get("/standard-items") {
                val crm = deps.crm(call)
                call.respond(crm.standardItems.search(call.request.queryParameters["q"], call.request.queryParameters["type"]))
            }
            post("/standard-items") {
                call.createStandardItem(deps.crm(call).standardItems)
            }
            post("/standard-items/{id}") {
                val crm = deps.crm(call)
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.updateStandardItem(crm.standardItems, id)
            }
            delete("/standard-items/{id}") {
                val crm = deps.crm(call)
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (!crm.standardItems.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("deleted" to true))
            }
            get("/catalog-categories") {
                call.respond(deps.crm(call).catalogCategories.list())
            }
            post("/catalog-categories") {
                val name = call.receive<CatalogCategoryRequest>().name
                call.respondCategory(HttpStatusCode.Created, deps.crm(call).catalogCategories.create(name))
            }
            put("/catalog-categories") {
                val from = call.request.queryParameters["name"].orEmpty()
                val to = call.receive<CatalogCategoryRequest>().name
                call.respondCategory(HttpStatusCode.OK, deps.crm(call).catalogCategories.rename(from, to))
            }
            delete("/catalog-categories") {
                call.respondCategory(HttpStatusCode.OK, deps.crm(call).catalogCategories.delete(call.request.queryParameters["name"].orEmpty()))
            }
        }
    }
}

/** The saved category, or why not: 400 for a name that can't be used, 409 for one in use or taken, 404 for none. */
private suspend fun ApplicationCall.respondCategory(saved: HttpStatusCode, outcome: CategoryOutcome) = when (outcome) {
    is CategoryOutcome.Saved -> respond(saved, outcome.category)
    CategoryOutcome.Deleted -> respond(mapOf("deleted" to true))
    CategoryOutcome.NotFound -> respond(HttpStatusCode.NotFound, ErrorBody("category_not_found"))
    is CategoryOutcome.Refused -> respond(
        if (outcome.error.startsWith("name_")) HttpStatusCode.BadRequest else HttpStatusCode.Conflict,
        ErrorBody(outcome.error, outcome.itemCount.takeIf { it > 0 }?.toString()),
    )
}
