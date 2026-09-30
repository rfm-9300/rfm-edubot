package com.rfm.edubot.admin

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.Date

/** An email someone added in the backoffice, on top of the env's ADMIN_EMAILS. */
data class AddedAdminEmail(val email: String, val addedBy: String?, val addedAt: Instant)

class AdminEmailRepository(mongo: MongoModule) {
    private val collection = mongo.database.getCollection<Document>("admin_emails")

    suspend fun list(): List<AddedAdminEmail> = collection.find().toList().map {
        AddedAdminEmail(
            email = it.getString("email"),
            addedBy = it.getString("addedBy"),
            addedAt = Instant.fromEpochMilliseconds(it.getDate("addedAt").time),
        )
    }

    /** False when the email is already there. */
    suspend fun add(entry: AddedAdminEmail): Boolean = try {
        collection.insertOne(
            Document("email", entry.email)
                .append("addedBy", entry.addedBy)
                .append("addedAt", Date(entry.addedAt.toEpochMilliseconds())),
        )
        true
    } catch (e: MongoWriteException) {
        if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
        false
    }

    suspend fun remove(email: String): Boolean = collection.deleteOne(Filters.eq("email", email)).deletedCount > 0
}

/**
 * Who may sign in to the backoffice with Google: the env's ADMIN_EMAILS plus the emails added in the
 * backoffice. Env emails can't be removed here, so the backoffice can never lock everyone out. The
 * live list sits in [RuntimeConfig], which the Google sign-in and every admin request read.
 */
class AdminAccess(private val repository: AdminEmailRepository, private val runtime: RuntimeConfig) {
    private val log = LoggerFactory.getLogger("AdminAccess")

    class Refused(val error: String) : Exception(error)

    data class Entry(val email: String, val fromEnv: Boolean, val addedBy: String?, val addedAt: Instant?)

    suspend fun initialize() {
        runtime.applyAddedAdminEmails(repository.list().map { it.email }.toSet())
    }

    private fun envEmails(): Set<String> = runtime.base.admin.googleSignIn.allowedEmails

    /** Env emails first, then the added ones in the order they were added. */
    suspend fun list(): List<Entry> {
        val env = envEmails()
        return env.sorted().map { Entry(it, fromEnv = true, addedBy = null, addedAt = null) } +
            repository.list().filter { it.email !in env }.sortedBy { it.addedAt }
                .map { Entry(it.email, fromEnv = false, addedBy = it.addedBy, addedAt = it.addedAt) }
    }

    /** @throws Refused `invalid_email` or `already_allowed`. */
    suspend fun add(rawEmail: String, by: String?): Entry {
        val email = normalize(rawEmail)
        if (!EMAIL.matches(email) || email.length > MAX_EMAIL_LENGTH) throw Refused("invalid_email")
        if (email in envEmails()) throw Refused("already_allowed")
        val entry = AddedAdminEmail(email, by, Clock.System.now())
        if (!repository.add(entry)) throw Refused("already_allowed")
        refresh()
        log.info("Backoffice admin added: email={} by={}", email, by ?: "password sign-in")
        return Entry(email, fromEnv = false, addedBy = by, addedAt = entry.addedAt)
    }

    /** @throws Refused `from_env`, `cannot_remove_self` or `not_found`. */
    suspend fun remove(rawEmail: String, by: String?) {
        val email = normalize(rawEmail)
        if (email in envEmails()) throw Refused("from_env")
        if (by != null && normalize(by) == email) throw Refused("cannot_remove_self")
        if (!repository.remove(email)) throw Refused("not_found")
        refresh()
        log.info("Backoffice admin removed: email={} by={}", email, by ?: "password sign-in")
    }

    private suspend fun refresh() {
        runtime.applyAddedAdminEmails(repository.list().map { it.email }.toSet())
    }

    companion object {
        private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
        private const val MAX_EMAIL_LENGTH = 254

        fun normalize(email: String): String = email.trim().lowercase()
    }
}
