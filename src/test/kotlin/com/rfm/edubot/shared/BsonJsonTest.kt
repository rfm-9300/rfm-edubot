package com.rfm.edubot.shared

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

class BsonJsonTest {
    @Test
    fun `json survives a round trip through a document`() {
        val json = buildJsonObject {
            put("text", "Olá, {{client.firstName}}")
            put("count", 3)
            put("big", 12_345_678_901L)
            put("ratio", 0.25)
            put("on", true)
            put("none", JsonNull)
            putJsonArray("list") { add("a"); add(2) }
            putJsonObject("nested") { put("key", "value") }
        }

        assertEquals(json, BsonJson.toJsonObject(BsonJson.toDocument(json)))
    }

    @Test
    fun `mongo values read back as plain json`() {
        val id = ObjectId()
        val doc = Document("id", id).append("at", Date(0)).append("n", 7)

        val json = BsonJson.toJsonObject(doc)

        assertEquals(JsonPrimitive(id.toHexString()), json["id"])
        assertEquals(JsonPrimitive("1970-01-01T00:00:00Z"), json["at"])
        assertEquals(JsonPrimitive(7L), json["n"])
    }
}
