package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.TokenStore
import com.rfm.edubot.mobile.core.model.ChangeStatus
import com.rfm.edubot.mobile.core.model.SaveClient
import com.rfm.edubot.mobile.core.model.SaveSiteLog
import com.rfm.edubot.mobile.core.model.UpdateService
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FixedTokens : TokenStore {
    override suspend fun read(): String = "jwt"
    override suspend fun write(token: String) = Unit
    override suspend fun clear() = Unit
}

/**
 * The bodies here are shaped like the server's DTOs as it sends them: kotlinx's `encodeDefaults` is
 * off there, so a property left at its default is missing from the JSON, not sent as `[]` or `0`.
 */
class NewEndpointsTest {
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun http(handler: MockRequestHandler) =
        DashboardHttpClient("https://example.test/", SessionTokens(FixedTokens()), MockEngine(handler))

    @Test
    fun `a company's client fields decode with their custom fields`() = runTest {
        val api = KtorCrmApi(
            http {
                respond(
                    """{"standard":[{"key":"name","required":true,"locked":true},{"key":"phone","required":true,"locked":true},
                       {"key":"taxId","required":false,"locked":false},{"key":"email","required":true,"locked":false}],
                       "custom":[{"key":"cf_ab12cd34","label":"Gate code","type":"TEXT","required":true,"showInList":false,"options":[]},
                       {"key":"cf_zz","label":"Size","type":"SELECT","required":false,"showInList":true,"options":["S","M"]}],
                       "canEdit":false}""",
                    HttpStatusCode.OK,
                    json,
                )
            },
        )
        val fields = api.clientFields()
        assertTrue(fields.isRequired("email"))
        assertFalse(fields.isRequired("taxId"), "the company made the NIF optional")
        assertEquals(listOf("S", "M"), fields.custom.last().options)
    }

    @Test
    fun `a client save sends custom values and leaves out nothing it shows`() = runTest {
        var body: String? = null
        val api = KtorCrmApi(
            http { request ->
                body = (request.body as TextContent).text
                respond("""{"id":"c1","number":"CLT-001","name":"Ana","phone":"912","createdAt":"2026-01-01T00:00:00Z"}""", HttpStatusCode.OK, json)
            },
        )
        api.updateClient("c1", SaveClient(name = "Ana", phone = "912", email = "", customFields = mapOf("cf_ab12cd34" to JsonPrimitive("1234"))))
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(body!!).jsonObject
        assertEquals("\"\"", sent["email"].toString(), "an empty field clears it on an edit, so it has to be sent")
        assertEquals("\"1234\"", sent["customFields"]!!.jsonObject["cf_ab12cd34"].toString())
    }

    @Test
    fun `an invoice paid in parts decodes its installments and what is left`() = runTest {
        val api = KtorCrmApi(
            http {
                respond(
                    """{"id":"i1","number":"FT 2026/7","clientId":"c1","clientName":"Ana","status":"PENDING","dueDate":"2026-12-01",
                       "totalEur":300.0,"hasPdf":true,"createdAt":"2026-11-01T10:00:00Z","paidEur":100.0,"outstandingEur":200.0,
                       "installments":[{"amountEur":100.0,"dueDate":"2026-11-01","paidAt":"2026-11-02T10:00:00Z"},{"amountEur":200.0,"dueDate":"2026-12-01"}]}""",
                    HttpStatusCode.OK,
                    json,
                )
            },
        )
        val invoice = api.invoice("i1")
        assertEquals(listOf(true, false), invoice.installments.map { it.paid })
        assertEquals(200.0, invoice.outstandingEur)
        assertFalse(invoice.closed)
    }

    @Test
    fun `cancelling an invoice posts to its cancel route`() = runTest {
        var seen: Pair<HttpMethod, String>? = null
        val api = KtorCrmApi(
            http { request ->
                seen = request.method to request.url.encodedPath
                respond("""{"id":"i1","status":"CANCELLED","paidEur":0.0,"outstandingEur":0.0}""", HttpStatusCode.OK, json)
            },
        )
        val invoice = api.cancelInvoice("i1")
        assertEquals(HttpMethod.Post to "/app/api/crm/invoices/i1/cancel", seen)
        assertTrue(invoice.cancelled && invoice.closed)
    }

    @Test
    fun `changing who did a service sends only that field`() = runTest {
        var body: String? = null
        val api = KtorCrmApi(
            http { request ->
                body = (request.body as TextContent).text
                respond("""{"id":"s1","clientId":"c1","clientName":"Ana","name":"Pintura","quantity":1.0,"unit":"","unitPriceEur":0.0,
                   "totalEur":80.0,"items":[{"description":"Pintura","quantity":1.0,"unit":"","unitPriceEur":80.0}],"status":"OPEN",
                   "createdAt":"2026-11-01T10:00:00Z","employeeId":"e1","employeeName":"Rui"}""", HttpStatusCode.OK, json)
            },
        )
        val service = api.updateService("s1", UpdateService(employeeId = "e1"))
        assertEquals("""{"employeeId":"e1"}""", body, "an omitted status keeps the stored one")
        assertEquals("Rui", service.employeeName)
    }

    @Test
    fun `approving a submission reads the submission out of the approval`() = runTest {
        val api = KtorCrmApi(
            http {
                respond(
                    """{"submission":{"id":"sub1","employeeId":"e1","employeeName":"Rui","clientId":"c1","clientName":"Ana","name":"Pintura",
                       "items":[],"totalEur":80.0,"performedAt":"2026-11-09","status":"APPROVED","serviceId":"s9",
                       "createdAt":"2026-11-09T10:00:00Z","updatedAt":"2026-11-10T10:00:00Z"},
                       "service":{"id":"s9","clientId":"c1","clientName":"Ana","name":"Pintura","quantity":1.0,"unit":"","unitPriceEur":0.0,
                       "totalEur":80.0,"items":[],"status":"OPEN","createdAt":"2026-11-10T10:00:00Z"}}""",
                    HttpStatusCode.OK,
                    json,
                )
            },
        )
        val approved = api.approveSubmission("sub1")
        assertEquals("s9", approved.serviceId)
        assertFalse(approved.pending)
    }

    @Test
    fun `a project decodes with its phases and its schedule and money`() = runTest {
        val api = KtorBuildingApi(
            http {
                respond(
                    """{"id":"p1","number":"OB-001","name":"Moradia","status":"ACTIVE","health":"AT_RISK","clientId":"c1","clientName":"Ana",
                       "clientPhone":null,"quoteId":null,"quoteNumber":null,"managerId":"e1","managerName":"Rui","address":null,"description":null,
                       "projectStart":null,"projectEnd":null,"plannedStart":"2026-10-01","plannedEnd":"2026-12-20","revisedEnd":"2026-12-27",
                       "actualStart":"2026-10-02","actualEnd":null,"progressPct":40,"plannedPct":48,"scheduleDays":-6,"manualProgressPct":0,
                       "retentionPct":5.0,"contractEur":10000.0,"changesEur":500.0,"changeDays":7,"currentContractEur":10500.0,"measuredEur":4000.0,
                       "toInvoiceEur":950.0,"retainedEur":200.0,"retentionHeldEur":200.0,"retentionReleasedEur":0.0,"retentionReleases":[],
                       "costBudgetEur":null,"laborRateEur":null,"siteIds":[],"phaseCount":2,"latePhases":1,"openIssues":3,"overdueIssues":1,
                       "pendingChanges":0,"photoCount":12,"lastDiaryDay":"2026-11-09","contractLocked":true,"shareEnabled":false,"shareToken":null,
                       "phases":[{"id":"ph1","name":"Estrutura","plannedStart":"2026-10-01","plannedEnd":"2026-11-01","actualStart":"2026-10-02",
                       "actualEnd":null,"progressPct":80,"weight":null,"plannedPct":100,"state":"LATE"}],
                       "contract":[],"createdAt":"2026-09-20T10:00:00Z","updatedAt":"2026-11-09T18:00:00Z"}""",
                    HttpStatusCode.OK,
                    json,
                )
            },
        )
        val project = api.project("p1")
        assertEquals(-6, project.scheduleDays)
        assertEquals("LATE", project.phases.single().state)
        assertTrue(project.running)
        assertNull(project.address)
    }

    @Test
    fun `editing a diary entry names the numbers it clears`() = runTest {
        var body: String? = null
        val api = KtorBuildingApi(
            http { request ->
                body = (request.body as TextContent).text
                respond("""{"id":"d1","projectId":"p1","projectNumber":"OB-001","projectName":"Moradia","day":"2026-11-10","weather":null,
                   "temperatureC":null,"workers":null,"workDone":"Betonagem","equipment":null,"materials":null,"visitors":null,"incidents":null,
                   "lostHours":null,"shared":false,"progress":[],"photos":[],"createdBy":"Rui","updatedBy":"Rui",
                   "createdAt":"2026-11-10T17:00:00Z","updatedAt":"2026-11-10T17:05:00Z"}""", HttpStatusCode.OK, json)
            },
        )
        api.editDiary("d1", SaveSiteLog(workDone = "Betonagem", clear = listOf("workers")))
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(body!!).jsonObject
        assertEquals("[\"workers\"]", sent["clear"].toString())
        assertFalse("workers" in sent, "a null number is left out; `clear` is what clears it")
    }

    @Test
    fun `a change decided by the team says who decided`() = runTest {
        var body: String? = null
        val api = KtorBuildingApi(
            http { request ->
                body = (request.body as TextContent).text
                respond("""{"id":"co1","number":"TM-001","projectId":"p1","projectNumber":"OB-001","projectName":"Moradia","clientName":"Ana",
                   "clientPhone":null,"title":"Mais tomadas","description":null,"kind":"ADDITION","items":[],"totalEur":300.0,"signedEur":300.0,
                   "daysImpact":2,"status":"APPROVED","sentAt":null,"decidedAt":"2026-11-10T10:00:00Z","decidedBy":"Ana","decisionNote":null,
                   "decidedVia":"TEAM","approvalToken":null,"measured":false,"createdBy":"Rui","createdAt":"2026-11-01T10:00:00Z",
                   "updatedAt":"2026-11-10T10:00:00Z"}""", HttpStatusCode.OK, json)
            },
        )
        val change = api.setChangeStatus("co1", ChangeStatus("APPROVED", decidedBy = "Ana"))
        assertEquals("""{"status":"APPROVED","decidedBy":"Ana"}""", body)
        assertEquals("TEAM", change.decidedVia)
    }

    @Test
    fun `a site manager's site decodes without any money`() = runTest {
        val api = KtorMySitesApi(
            http {
                respond(
                    """{"site":{"id":"p1","number":"OB-001","name":"Moradia","address":"Rua 1","status":"ACTIVE","progressPct":40,
                       "plannedEnd":"2026-12-20","todayEntryId":null,"openIssues":1},"today":"2026-11-10",
                       "phases":[{"id":"ph1","name":"Estrutura","progressPct":80,"state":"LATE"}],"diary":[],"issues":[],"photos":[],
                       "canDiary":true,"canIssues":false}""",
                    HttpStatusCode.OK,
                    json,
                )
            },
        )
        val site = api.site("p1")
        assertTrue(site.canDiary)
        assertFalse(site.canIssues)
        assertNull(site.site.todayEntryId)
    }

    @Test
    fun `a write the company's modules lack is a 403 rather than a sign-out`() = runTest {
        val api = KtorMySitesApi(http { respond("""{"error":"page_unavailable"}""", HttpStatusCode.Forbidden, json) })
        val failure = assertFailsWith<ApiException> { api.logIssue("p1", com.rfm.edubot.mobile.core.model.SaveIssue(title = "x")) }
        assertEquals(AppError.Forbidden, failure.error)
    }
}
