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
            startGates = doc.documents("startGates").mapNotNull { raw ->
                val platform = platforms(listOfNotNull(raw.getString("channel"))).singleOrNull() ?: return@mapNotNull null
                platform to StartGate(raw.getBoolean("enabled") ?: true, raw.strings("words"))
            }.toMap(),
            updatedAt = doc.getDate("updatedAt")?.let { Instant.fromEpochMilliseconds(it.time) },
            updatedBy = doc.getString("updatedBy"),
        )
    }

    /** The flows that can run, or null when the company has none. A flow with [ChatFlow.problems] is left out and logged. */
    suspend fun find(): ChatFlows? {
        val all = load() ?: return null
        all.startGates.filter { (_, gate) -> gate.enabled && !gate.usable() }.keys.forEach {
            log.warn("Skipping start gate with no word: tenantId={}, channel={}", tenantId, it)
        }
        return all.copy(
            flows = all.flows.filter { flow ->
                val problems = flow.problems()
                if (problems.isNotEmpty()) log.warn("Skipping chat flow: tenantId={}, flow={}, problems={}", tenantId, flow.id, problems)
                problems.isEmpty()
            },
        )
    }

    /** Replaces only the flows, leaving the AI switch and the start gates as they are: what the Chatbot page saves. */
    suspend fun saveFlows(flows: List<ChatFlow>, author: String): ChatFlows {
        val now = Date(SystemClock.now().toEpochMilliseconds())
        collection.updateOne(
            Filters.eq("tenantId", tenantId),
            Updates.combine(
                Updates.set("flows", flows.map { it.toDocument() }),
                Updates.set("updatedAt", now),
                Updates.set("updatedBy", author),
                Updates.setOnInsert("createdAt", now),
            ),
            UpdateOptions().upsert(true),
        )
        return load()!!
    }

    /** Replaces only the AI switch and the start gates, leaving the flows as they are: what Persona → Channels saves. */
    suspend fun saveChannels(teamOnlyChannels: Set<Platform>, startGates: Map<Platform, StartGate>, author: String): ChatFlows {
        val now = Date(SystemClock.now().toEpochMilliseconds())
        collection.updateOne(
            Filters.eq("tenantId", tenantId),
            Updates.combine(
                Updates.set("teamOnlyChannels", teamOnlyChannels.map { it.name }.sorted()),
                Updates.set(
                    "startGates",
                    startGates.entries.sortedBy { it.key.name }.map { (platform, gate) ->
                        Document("channel", platform.name).append("enabled", gate.enabled).append("words", gate.words)
                    },
                ),
                Updates.set("updatedAt", now),
                Updates.set("updatedBy", author),
                Updates.setOnInsert("createdAt", now),
            ),
            UpdateOptions().upsert(true),
        )
        return load()!!
    }

    /** Replaces the company's flows, AI setting and start gates with [flows], saved by [author]. */
    suspend fun save(flows: ChatFlows, author: String): ChatFlows {
        val now = Date(SystemClock.now().toEpochMilliseconds())
        collection.updateOne(
            Filters.eq("tenantId", tenantId),
            Updates.combine(
                Updates.set("flows", flows.flows.map { it.toDocument() }),
                Updates.set("teamOnlyChannels", flows.teamOnlyChannels.map { it.name }.sorted()),
                Updates.set(
                    "startGates",
                    flows.startGates.entries.sortedBy { it.key.name }.map { (platform, gate) ->
                        Document("channel", platform.name).append("enabled", gate.enabled).append("words", gate.words)
                    },
                ),
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
    steps = documents("steps").map { it.toStep() },
    aiReads = getBoolean("aiReads") ?: false,
    // A flow saved before these settings answered a reply it couldn't read by leaving it to the AI at once; it still does.
    afterMisses = enumOf(getString("afterMisses"), AfterMisses.LET_AI),
    handoverText = getString("handoverText")?.takeIf { it.isNotBlank() },
    retries = getInteger("retries") ?: 0,
    startAt = point("startAt"),
)

/** A step saved before steps had kinds is a message with buttons, which is all it needs. */
private fun Document.toStep() = FlowStep(
    id = getString("id"),
    kind = enumOf(getString("kind"), StepKind.MESSAGE),
    text = getString("text").orEmpty(),
    buttons = documents("buttons").map { FlowButton(it.getString("title").orEmpty(), it.getString("next").orEmpty(), it.strings("words")) },
    show = enumOf(getString("show"), OptionsDisplay.AUTO),
    listLabel = getString("listLabel")?.takeIf { it.isNotBlank() },
    retryText = getString("retryText")?.takeIf { it.isNotBlank() },
    next = getString("next")?.takeIf { it.isNotBlank() },
    key = getString("key")?.takeIf { it.isNotBlank() },
    expect = enumOf(getString("expect"), Expect.TEXT),
    check = enumOf(getString("check"), Check.HOURS),
    hours = documents("hours").map { HoursWindow(it.getInteger("day") ?: 0, it.getString("from").orEmpty(), it.getString("to").orEmpty()) },
    answerKey = getString("answerKey")?.takeIf { it.isNotBlank() },
    answerIs = getString("answerIs")?.takeIf { it.isNotBlank() },
    whenTrue = getString("whenTrue")?.takeIf { it.isNotBlank() },
    whenFalse = getString("whenFalse")?.takeIf { it.isNotBlank() },
    action = enumOf(getString("action"), ActionKind.NOTIFY_TEAM),
    serviceId = getString("serviceId")?.takeIf { it.isNotBlank() },
    failNext = getString("failNext")?.takeIf { it.isNotBlank() },
    at = point("at"),
)

private fun ChatFlow.toDocument() = Document("id", id)
    .append("name", name)
    .append("enabled", enabled)
    .append("channels", channels.map { it.name }.sorted())
    .append("keywords", keywords)
    .append("steps", steps.map { it.toDocument() })
    .append("aiReads", aiReads)
    .append("afterMisses", afterMisses.name)
    .append("handoverText", handoverText)
    .append("retries", retries)
    .append("startAt", startAt?.toDocument())

private fun FlowStep.toDocument() = Document("id", id)
    .append("kind", kind.name)
    .append("text", text)
    .append("buttons", buttons.map { Document("title", it.title).append("next", it.next).append("words", it.words) })
    .append("show", show.name)
    .append("listLabel", listLabel)
    .append("retryText", retryText)
    .append("next", next)
    .append("key", key)
    .append("expect", expect.name)
    .append("check", check.name)
    .append("hours", hours.map { Document("day", it.day).append("from", it.from).append("to", it.to) })
    .append("answerKey", answerKey)
    .append("answerIs", answerIs)
    .append("whenTrue", whenTrue)
    .append("whenFalse", whenFalse)
    .append("action", action.name)
    .append("serviceId", serviceId)
    .append("failNext", failNext)
    .append("at", at?.toDocument())

private fun BoardPoint.toDocument() = Document("x", x).append("y", y)

/** A place on the Chatbot page's board, read as any number type (a document edited in mongosh may hold doubles). */
private fun Document.point(key: String): BoardPoint? {
    val at = get(key) as? Document ?: return null
    return BoardPoint.of((at["x"] as? Number)?.toInt(), (at["y"] as? Number)?.toInt())
}

private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default

private fun Document.documents(key: String): List<Document> = (get(key) as? List<*>).orEmpty().filterIsInstance<Document>()

private fun Document.strings(key: String): List<String> = (get(key) as? List<*>).orEmpty().filterIsInstance<String>()

private fun platforms(names: List<String>): Set<Platform> = names.mapNotNull { name -> Platform.entries.firstOrNull { it.name == name } }.toSet()
