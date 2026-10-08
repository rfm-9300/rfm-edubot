package com.rfm.edubot.persona

import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

/** A source without its text, for lists. */
data class PersonaSourceSummary(
    val id: ObjectId,
    val kind: SourceKind,
    val label: String,
    val chars: Int,
    val compiledIntoVersion: Int?,
    val createdAt: Instant,
    val addedBy: String?,
    val truncated: Boolean,
)

/** A version without its text, for the history list. */
data class PersonaVersionSummary(
    val version: Int,
    val change: PersonaChange,
    val author: String?,
    val restoredFrom: Int?,
    val sourceCount: Int,
    val trimmed: Boolean,
    val chars: Int,
    val createdAt: Instant,
)

class PersonaRepository(mongo: MongoModule) {
    private val collection = mongo.database.getCollection<Document>("tenant_persona")
    private val sources = mongo.database.getCollection<Document>("persona_sources")
    private val versions = mongo.database.getCollection<Document>("persona_versions")

    suspend fun findByTenant(tenantId: ObjectId): TenantPersona? =
        collection.find(Filters.eq("tenantId", tenantId)).firstOrNull()?.toPersona()

    /** Direct write of the compiled file by hand. Kept for callers that predate [saveInstructions]. */
    suspend fun upsertCompiled(tenantId: ObjectId, instructions: String): TenantPersona =
        saveInstructions(tenantId, instructions, PersonaChange.MANUAL, author = null)!!

    /**
     * Writes new instructions as the next version and records it in history. With [expectedVersion] the
     * write only happens if nobody changed the persona since that version, and returns null otherwise,
     * so a synthesis never overwrites an edit made while it ran.
     */
    suspend fun saveInstructions(
        tenantId: ObjectId,
        instructions: String,
        change: PersonaChange,
        author: String?,
        expectedVersion: Int? = null,
        sourceCount: Int = 0,
        trimmed: Boolean = false,
    ): TenantPersona? {
        val fields = mutableMapOf<String, Any?>("compiledInstructions" to instructions)
        if (change == PersonaChange.MANUAL || change == PersonaChange.REBUILD) fields["stale"] = false
        return write(tenantId, fields, change, author, expectedVersion, sourceCount = sourceCount, trimmed = trimmed)
    }

    suspend fun saveBehavior(tenantId: ObjectId, behavior: PersonaBehavior, author: String?): TenantPersona =
        write(tenantId, mapOf("behavior" to behavior.toDocument()), PersonaChange.SETTINGS, author)!!

    /** Brings back [version] as a new version; null when that version isn't kept. */
    suspend fun restore(tenantId: ObjectId, version: Int, author: String?): TenantPersona? {
        val snapshot = findVersion(tenantId, version) ?: return null
        val fields = mapOf("compiledInstructions" to snapshot.compiledInstructions, "behavior" to snapshot.behavior.toDocument())
        return write(tenantId, fields, PersonaChange.RESTORE, author, restoredFrom = version)
    }

    /** Flip status without touching the persona — used while a compile is in flight or on failure. */
    suspend fun setStatus(tenantId: ObjectId, status: PersonaStatus, error: String? = null): TenantPersona =
        collection.findOneAndUpdate(
            Filters.eq("tenantId", tenantId),
            Updates.combine(
                Updates.setOnInsert("_id", ObjectId()),
                Updates.set("status", status.name),
                if (error != null) Updates.set("lastError", error) else Updates.unset("lastError"),
                Updates.set("updatedAt", SystemClock.now().toDate()),
            ),
            FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
        )!!.toPersona()

    /** Ends a synthesis: [PersonaStatus.ERROR] with [error], else ready when anything is set and empty otherwise. */
    suspend fun settleStatus(tenantId: ObjectId, error: String? = null): TenantPersona {
        val current = findByTenant(tenantId)
        val status = when {
            error != null -> PersonaStatus.ERROR
            current == null || current.isEmpty -> PersonaStatus.EMPTY
            else -> PersonaStatus.READY
        }
        return setStatus(tenantId, status, error)
    }

    suspend fun markStale(tenantId: ObjectId) {
        collection.updateOne(Filters.eq("tenantId", tenantId), Updates.set("stale", true))
    }

    /**
     * One version-bumping write. Values go through `$literal` because this is a pipeline update, where a
     * string starting with `$` ("$100 off…") would otherwise read as a field path. A synthesis or rebuild
     * settles the status and clears its error; any other change leaves a running synthesis or its failure
     * on show, since the sources it was about are still pending.
     */
    private suspend fun write(
        tenantId: ObjectId,
        fields: Map<String, Any?>,
        change: PersonaChange,
        author: String?,
        expectedVersion: Int? = null,
        restoredFrom: Int? = null,
        sourceCount: Int = 0,
        trimmed: Boolean = false,
    ): TenantPersona? {
        val before = findByTenant(tenantId)
        if (expectedVersion != null && (before?.version ?: 0) != expectedVersion) return null
        if (before != null && before.version > 0) keepBaseline(before)

        val current = before ?: TenantPersona(tenantId = tenantId, updatedAt = SystemClock.now())
        val next = current.copy(
            compiledInstructions = fields["compiledInstructions"] as? String ?: current.compiledInstructions,
            behavior = (fields["behavior"] as? Document)?.toBehavior() ?: current.behavior,
        )
        val settled = if (next.isEmpty) PersonaStatus.EMPTY else PersonaStatus.READY
        val fromCompiler = change == PersonaChange.SYNTHESIS || change == PersonaChange.REBUILD
        val status: Any = if (fromCompiler) {
            settled.name
        } else {
            Document(
                "\$cond",
                listOf(
                    Document("\$in", listOf("\$status", listOf(PersonaStatus.COMPILING.name, PersonaStatus.ERROR.name))),
                    "\$status",
                    settled.name,
                ),
            )
        }
        val set = Document("tenantId", tenantId)
        fields.forEach { (key, value) -> set.append(key, Document("\$literal", value)) }
        set.append("version", Document("\$add", listOf(Document("\$ifNull", listOf("\$version", 0)), 1)))
            .append("tokenEstimate", PersonaPrompt.estimateTokens(PersonaPrompt.personaBlock(next)))
            .append("status", status)
            .append("updatedAt", SystemClock.now().toDate())
        val stages = buildList {
            add(Document("\$set", set))
            if (fromCompiler) add(Document("\$unset", "lastError"))
        }
        val filter = if (expectedVersion != null) {
            Filters.and(
                Filters.eq("tenantId", tenantId),
                if (expectedVersion == 0) Filters.or(Filters.eq("version", 0), Filters.exists("version", false)) else Filters.eq("version", expectedVersion),
            )
        } else {
            Filters.eq("tenantId", tenantId)
        }
        val saved = try {
            collection.findOneAndUpdate(
                filter,
                stages,
                FindOneAndUpdateOptions().upsert(expectedVersion == null || expectedVersion == 0).returnDocument(ReturnDocument.AFTER),
            )
        } catch (e: MongoWriteException) {
            // A concurrent first write inserted the document; with an expected version that means we lost the race.
            if (e.code == DUPLICATE_KEY && expectedVersion != null) return null
            throw e
        }?.toPersona() ?: return null

        insertVersion(
            PersonaVersion(
                tenantId = tenantId,
                version = saved.version,
                change = change,
                compiledInstructions = saved.compiledInstructions,
                behavior = saved.behavior,
                author = author,
                restoredFrom = restoredFrom,
                sourceCount = sourceCount,
                trimmed = trimmed,
                createdAt = saved.updatedAt,
            ),
        )
        versions.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.lte("version", saved.version - PersonaLimits.KEPT_VERSIONS)))
        return saved
    }

    /** A persona written before history was kept gets its current state recorded once, so it can be restored. */
    private suspend fun keepBaseline(before: TenantPersona) {
        if (versions.find(Filters.eq("tenantId", before.tenantId)).limit(1).firstOrNull() != null) return
        insertVersion(
            PersonaVersion(
                tenantId = before.tenantId,
                version = before.version,
                change = PersonaChange.BASELINE,
                compiledInstructions = before.compiledInstructions,
                behavior = before.behavior,
                createdAt = before.updatedAt,
            ),
        )
    }

    private suspend fun insertVersion(version: PersonaVersion) {
        try {
            versions.insertOne(version.toDocument())
        } catch (e: MongoWriteException) {
            if (e.code != DUPLICATE_KEY) throw e
        }
    }

    // --- Sources (the gradual sync feed) ---

    suspend fun addSource(
        tenantId: ObjectId,
        kind: SourceKind,
        content: String,
        label: String,
        addedBy: String? = null,
        truncated: Boolean = false,
    ): PersonaSource {
        val source = PersonaSource(
            tenantId = tenantId, kind = kind, content = content, label = label, createdAt = SystemClock.now(), addedBy = addedBy, truncated = truncated,
        )
        sources.insertOne(source.toDocument())
        return source
    }

    suspend fun findSource(tenantId: ObjectId, sourceId: ObjectId): PersonaSource? =
        sources.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", sourceId))).firstOrNull()?.toSource()

    suspend fun countSources(tenantId: ObjectId): Long = sources.countDocuments(Filters.eq("tenantId", tenantId))

    /** Characters across all of a company's sources. */
    suspend fun totalSourceChars(tenantId: ObjectId): Long =
        sources.aggregate<Document>(
            listOf(
                Document("\$match", Document("tenantId", tenantId)),
                Document("\$group", Document("_id", null).append("chars", Document("\$sum", CHARS))),
            ),
        ).firstOrNull()?.let { (it["chars"] as? Number)?.toLong() } ?: 0L

    /** Newest first, without the text. */
    suspend fun listSources(tenantId: ObjectId): List<PersonaSourceSummary> =
        sources.aggregate<Document>(
            listOf(
                Document("\$match", Document("tenantId", tenantId)),
                Document("\$sort", Document("createdAt", -1)),
                Document(
                    "\$project",
                    Document("kind", 1).append("label", 1).append("compiledIntoVersion", 1).append("createdAt", 1)
                        .append("addedBy", 1).append("truncated", 1).append("chars", CHARS),
                ),
            ),
        ).map { it.toSourceSummary() }.toList()

    suspend fun pendingSources(tenantId: ObjectId): List<PersonaSource> =
        sources.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("compiledIntoVersion", null)))
            .sort(Sorts.ascending("createdAt")).map { it.toSource() }.toList()

    suspend fun allSources(tenantId: ObjectId): List<PersonaSource> =
        sources.find(Filters.eq("tenantId", tenantId)).sort(Sorts.ascending("createdAt")).map { it.toSource() }.toList()

    /** Removes a source and returns it, or null when it isn't this tenant's. */
    suspend fun deleteSource(tenantId: ObjectId, sourceId: ObjectId): PersonaSource? =
        sources.findOneAndDelete(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", sourceId)))?.toSource()

    /** Stamp the given sources as folded into [version] so they are not re-synthesised next time. */
    suspend fun markSourcesCompiled(tenantId: ObjectId, sourceIds: List<ObjectId>, version: Int) {
        if (sourceIds.isEmpty()) return
        sources.updateMany(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("_id", sourceIds)),
            Updates.set("compiledIntoVersion", version),
        )
    }

    // --- History ---

    /** Newest first, without the text. */
    suspend fun listVersions(tenantId: ObjectId, limit: Int = PersonaLimits.KEPT_VERSIONS): List<PersonaVersionSummary> =
        versions.find(Filters.eq("tenantId", tenantId))
            .projection(Projections.exclude("compiledInstructions", "behavior"))
            .sort(Sorts.descending("version"))
            .limit(limit)
            .map { it.toVersionSummary() }
            .toList()

    suspend fun findVersion(tenantId: ObjectId, version: Int): PersonaVersion? =
        versions.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("version", version))).firstOrNull()?.toVersion()

    // --- Recovery ---

    /** Companies with sources still to synthesize, or a synthesis a stopped server left running. */
    suspend fun tenantsToCompile(): Set<ObjectId> {
        val compiling = collection.find(Filters.eq("status", PersonaStatus.COMPILING.name))
            .projection(Projections.include("tenantId")).map { it.getObjectId("tenantId") }.toList()
        val pending = sources.distinct<ObjectId>("tenantId", Filters.eq("compiledIntoVersion", null)).toList()
        return (compiling + pending).toSet()
    }

    private companion object {
        const val DUPLICATE_KEY = 11000
        /** A source's length; sources stored before `chars` was kept are measured. */
        val CHARS = Document("\$ifNull", listOf("\$chars", Document("\$strLenCP", Document("\$ifNull", listOf("\$content", "")))))
    }
}

private fun Document.toPersona() = TenantPersona(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    compiledInstructions = getString("compiledInstructions").orEmpty(),
    behavior = (get("behavior") as? Document)?.toBehavior() ?: PersonaBehavior(),
    version = getInteger("version") ?: 0,
    tokenEstimate = getInteger("tokenEstimate") ?: 0,
    status = enumOrNull<PersonaStatus>(getString("status")) ?: PersonaStatus.EMPTY,
    lastError = getString("lastError"),
    stale = getBoolean("stale") ?: false,
    updatedAt = getDate("updatedAt")?.toKxInstant() ?: SystemClock.now(),
)

internal fun PersonaBehavior.toDocument(): Document = Document()
    .append("botName", botName)
    .append("language", language)
    .append("languageStrict", languageStrict)
    .append("tone", tone?.name)
    .append("addressForm", addressForm?.name)
    .append("replyLength", replyLength?.name)
    .append("emoji", emoji?.name)
    .append("greeting", greeting)
    .append("rules", rules)
    .append("handoff", Document("enabled", handoff.enabled).append("triggers", handoff.triggers).append("message", handoff.message))

internal fun Document.toBehavior(): PersonaBehavior {
    val handoff = get("handoff") as? Document
    return PersonaBehavior(
        botName = getString("botName"),
        language = getString("language"),
        languageStrict = getBoolean("languageStrict") ?: false,
        tone = enumOrNull<PersonaTone>(getString("tone")),
        addressForm = enumOrNull<PersonaAddressForm>(getString("addressForm")),
        replyLength = enumOrNull<PersonaReplyLength>(getString("replyLength")),
        emoji = enumOrNull<PersonaEmoji>(getString("emoji")),
        greeting = getString("greeting"),
        rules = getList("rules", String::class.java).orEmpty(),
        handoff = PersonaHandoff(
            enabled = handoff?.getBoolean("enabled") ?: false,
            triggers = handoff?.getString("triggers"),
            message = handoff?.getString("message"),
        ),
    )
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { value -> enumValues<E>().firstOrNull { it.name == value } }

private fun PersonaSource.toDocument() = Document("_id", id)
    .append("tenantId", tenantId)
    .append("kind", kind.name)
    .append("content", content)
    .append("chars", content.length)
    .append("label", label)
    .append("compiledIntoVersion", compiledIntoVersion)
    .append("createdAt", createdAt.toDate())
    .append("addedBy", addedBy)
    .append("truncated", truncated)

private fun Document.toSource() = PersonaSource(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    kind = enumOrNull<SourceKind>(getString("kind")) ?: SourceKind.TEXT_NOTE,
    content = getString("content").orEmpty(),
    label = getString("label").orEmpty(),
    compiledIntoVersion = getInteger("compiledIntoVersion"),
    createdAt = getDate("createdAt")?.toKxInstant() ?: SystemClock.now(),
    addedBy = getString("addedBy"),
    truncated = getBoolean("truncated") ?: false,
)

private fun Document.toSourceSummary() = PersonaSourceSummary(
    id = getObjectId("_id"),
    kind = enumOrNull<SourceKind>(getString("kind")) ?: SourceKind.TEXT_NOTE,
    label = getString("label").orEmpty(),
    chars = (get("chars") as? Number)?.toInt() ?: 0,
    compiledIntoVersion = getInteger("compiledIntoVersion"),
    createdAt = getDate("createdAt")?.toKxInstant() ?: SystemClock.now(),
    addedBy = getString("addedBy"),
    truncated = getBoolean("truncated") ?: false,
)

private fun PersonaVersion.toDocument() = Document("_id", id)
    .append("tenantId", tenantId)
    .append("version", version)
    .append("change", change.name)
    .append("compiledInstructions", compiledInstructions)
    .append("behavior", behavior.toDocument())
    .append("chars", compiledInstructions.length)
    .append("author", author)
    .append("restoredFrom", restoredFrom)
    .append("sourceCount", sourceCount)
    .append("trimmed", trimmed)
    .append("createdAt", createdAt.toDate())

private fun Document.toVersion() = PersonaVersion(
    id = getObjectId("_id"),
    tenantId = getObjectId("tenantId"),
    version = getInteger("version") ?: 0,
    change = enumOrNull<PersonaChange>(getString("change")) ?: PersonaChange.MANUAL,
    compiledInstructions = getString("compiledInstructions").orEmpty(),
    behavior = (get("behavior") as? Document)?.toBehavior() ?: PersonaBehavior(),
    author = getString("author"),
    restoredFrom = getInteger("restoredFrom"),
    sourceCount = getInteger("sourceCount") ?: 0,
    trimmed = getBoolean("trimmed") ?: false,
    createdAt = getDate("createdAt")?.toKxInstant() ?: SystemClock.now(),
)

private fun Document.toVersionSummary() = PersonaVersionSummary(
    version = getInteger("version") ?: 0,
    change = enumOrNull<PersonaChange>(getString("change")) ?: PersonaChange.MANUAL,
    author = getString("author"),
    restoredFrom = getInteger("restoredFrom"),
    sourceCount = getInteger("sourceCount") ?: 0,
    trimmed = getBoolean("trimmed") ?: false,
    chars = getInteger("chars") ?: 0,
    createdAt = getDate("createdAt")?.toKxInstant() ?: SystemClock.now(),
)

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
private fun Date.toKxInstant(): Instant = Instant.fromEpochMilliseconds(toInstant().toEpochMilli())
