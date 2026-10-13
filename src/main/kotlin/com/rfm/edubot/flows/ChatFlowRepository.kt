package com.rfm.edubot.flows

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.Date

class ChatFlowRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>("chat_flows")
    private val log = LoggerFactory.getLogger("ChatFlowRepository")

    /** Every readable flow, problems included, for the editor to show and fix; null when the company has none. */
    suspend fun load(): ChatFlows? {
        val doc = collection.find(Filters.eq("tenantId", tenantId)).firstOrNull() ?: return null
        val flows = doc.documents("flows").mapIndexedNotNull { index, raw ->
            runCatching { raw.toFlow() }.getOrNull().also {
                if (it == null) log.warn("Skipping unreadable chat flow: tenantId={}, flow=#{}", tenantId, index)
            }
        }
        return ChatFlows(
            flows = flows,
            teamOnlyChannels = platforms(doc.strings("teamOnlyChannels")),
            updatedAt = doc.getDate("updatedAt")?.let { Instant.fromEpochMilliseconds(it.time) },
            updatedBy = doc.getString("updatedBy"),
        )
    }

    /** The flows that can run, or null when the company has none. A flow with [ChatFlow.problems] is left out and logged. */
    suspend fun find(): ChatFlows? {
        val all = load() ?: return null
        return all.copy(
            flows = all.flows.filter { flow ->
                val problems = flow.problems()
                if (problems.isNotEmpty()) log.warn("Skipping chat flow: tenantId={}, flow={}, problems={}", tenantId, flow.id, problems)
                problems.isEmpty()
            },
        )
    }

    /** Replaces the company's flows and AI setting with [flows], saved by [author]. */
    suspend fun save(flows: ChatFlows, author: String): ChatFlows {
        val now = Date(SystemClock.now().toEpochMilliseconds())
        collection.updateOne(
            Filters.eq("tenantId", tenantId),
            Updates.combine(
                Updates.set("flows", flows.flows.map { it.toDocument() }),
                Updates.set("teamOnlyChannels", flows.teamOnlyChannels.map { it.name }.sorted()),
                Updates.set("updatedAt", now),
                Updates.set("updatedBy", author),
                Updates.setOnInsert("createdAt", now),
            ),
            UpdateOptions().upsert(true),
        )
        return load()!!
    }
}

private fun Document.toFlow() = ChatFlow(
    id = getString("id"),
    name = getString("name").orEmpty(),
    enabled = getBoolean("enabled") ?: true,
    channels = platforms(strings("channels")),
    keywords = strings("keywords"),
    steps = documents("steps").map { step ->
        FlowStep(
            id = step.getString("id"),
            text = step.getString("text").orEmpty(),
            buttons = step.documents("buttons").map { FlowButton(it.getString("title").orEmpty(), it.getString("next").orEmpty()) },
        )
    },
)

private fun ChatFlow.toDocument() = Document("id", id)
    .append("name", name)
    .append("enabled", enabled)
    .append("channels", channels.map { it.name }.sorted())
    .append("keywords", keywords)
    .append(
        "steps",
        steps.map { step ->
            Document("id", step.id)
                .append("text", step.text)
                .append("buttons", step.buttons.map { Document("title", it.title).append("next", it.next) })
        },
    )

private fun Document.documents(key: String): List<Document> = (get(key) as? List<*>).orEmpty().filterIsInstance<Document>()

private fun Document.strings(key: String): List<String> = (get(key) as? List<*>).orEmpty().filterIsInstance<String>()

private fun platforms(names: List<String>): Set<Platform> = names.mapNotNull { name -> Platform.entries.firstOrNull { it.name == name } }.toSet()
