package com.rfm.edubot.dashboard.building

import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.Reply
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.timesheets.zone
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.assertEquals

// What the Building HTTP tests seed and read with: companies' days, clients, projects, phases, quotes, and JSON.

internal const val BUILDING_API = "/app/api/building"

internal val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(600) { 7 }
internal val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(600) { 7 }

/** The company's day [offset] days from today, in its timezone, as the server reads it. */
internal fun TestCompany.day(offset: Int = 0): String =
    Clock.System.now().toLocalDateTime(tenant.zone()).date.plus(offset, DateTimeUnit.DAY).toString()

internal suspend fun DashboardApi.client(company: TestCompany, name: String = "Casa Silva"): String =
    company.post("/app/api/crm/clients") {
        put("name", name); put("phone", "+351 91${(1..7).map { (0..9).random() }.joinToString("")}"); put("taxId", "1"); put("address", "Rua 1")
    }.text("id")!!

internal suspend fun DashboardApi.project(company: TestCompany, body: JsonObjectBuilder.() -> Unit = {}): Reply =
    company.post("$BUILDING_API/projects") { put("name", "Moradia Silva"); body() }

/** Two phases: ten days that ended ten days ago, finished, and twenty days around today at [second]%. */
internal suspend fun DashboardApi.twoPhases(company: TestCompany, projectId: String, second: Int = 20): Reply =
    company.put("$BUILDING_API/projects/$projectId/phases") {
        putJsonArray("phases") {
            addJsonObject { put("name", "Fundações"); put("plannedStart", company.day(-20)); put("plannedEnd", company.day(-11)); put("progressPct", 100) }
            addJsonObject { put("name", "Estrutura"); put("plannedStart", company.day(-10)); put("plannedEnd", company.day(9)); put("progressPct", second) }
        }
    }

/** A quote of 100 m² of walls at 25 € and a roof at 8 000 €: 10 500 €. */
internal suspend fun DashboardApi.quote(company: TestCompany, clientId: String): String =
    company.post("/app/api/crm/quotes") {
        put("clientId", clientId)
        putJsonArray("items") {
            addJsonObject { put("description", "Paredes"); put("quantity", 100.0); put("unit", "m2"); put("unitPriceEur", 25.0) }
            addJsonObject { put("description", "Cobertura"); put("quantity", 1.0); put("unit", "vg"); put("unitPriceEur", 8000.0) }
        }
    }.also { assertEquals(HttpStatusCode.Created, it.status, it.toString()) }.text("id")!!

internal fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.int
internal fun JsonObject.eur(name: String): Double = this[name]!!.jsonPrimitive.double
internal fun JsonObject.list(name: String): List<JsonObject> = this[name]!!.jsonArray.map { it.jsonObject }
internal fun Reply.int(name: String): Int = obj.int(name)
internal fun Reply.eur(name: String): Double = obj.eur(name)
internal fun Reply.list(name: String): List<JsonObject> = obj.list(name)
