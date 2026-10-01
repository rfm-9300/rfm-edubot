package com.rfm.edubot.agents.store

import com.rfm.edubot.shared.BsonJson
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.bson.Document
import java.util.Date

/** Stores the serializable parts of agents (definitions, step results) as readable sub-documents. */
internal object AgentJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun <T> toDocument(serializer: KSerializer<T>, value: T): Document =
        BsonJson.toDocument(json.encodeToJsonElement(serializer, value).jsonObject)

    fun <T> fromDocument(serializer: KSerializer<T>, doc: Document?, fallback: T): T =
        doc?.let { runCatching { json.decodeFromJsonElement(serializer, BsonJson.toJsonObject(it)) }.getOrNull() } ?: fallback
}

internal fun Instant.toDate(): Date = Date(toEpochMilliseconds())

internal fun Document.instant(field: String): Instant? = getDate(field)?.let { Instant.fromEpochMilliseconds(it.time) }
