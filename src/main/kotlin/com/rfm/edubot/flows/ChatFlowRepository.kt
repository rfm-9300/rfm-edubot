package com.rfm.edubot.flows

import com.mongodb.client.model.Filters
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory

class ChatFlowRepository(mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongo.database.getCollection<Document>("chat_flows")
    private val log = LoggerFactory.getLogger("ChatFlowRepository")

    /** The company's flows, or null when it has none. A flow that can't be read or has [ChatFlow.problems] is left out and logged. */
    suspend fun find(): ChatFlows? {
        val doc = collection.find(Filters.eq("tenantId", tenantId)).firstOrNull() ?: return null
        val flows = doc.documents("flows").mapIndexedNotNull { index, raw ->
            val flow = runCatching { raw.toFlow() }.getOrNull()
            val problems = flow?.problems() ?: listOf("unreadable")
            if (problems.isEmpty()) {
                flow
            } else {
                log.warn("Skipping chat flow: tenantId={}, flow={}, problems={}", tenantId, flow?.id ?: "#$index", problems)
                null
            }
        }
        return ChatFlows(flows, platforms(doc.strings("teamOnlyChannels")))
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

private fun Document.documents(key: String): List<Document> = (get(key) as? List<*>).orEmpty().filterIsInstance<Document>()

private fun Document.strings(key: String): List<String> = (get(key) as? List<*>).orEmpty().filterIsInstance<String>()

private fun platforms(names: List<String>): Set<Platform> = names.mapNotNull { name -> Platform.entries.firstOrNull { it.name == name } }.toSet()
