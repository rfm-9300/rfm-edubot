package com.rfm.edubot.admin.vault

import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Base64

/** A request the vault refuses: [error] is the stable code the backoffice translates, [detail] the field it is about. */
class VaultRefusal(val kind: Kind, val error: String, val detail: String? = null) : Exception(error) {
    enum class Kind { INVALID, NOT_FOUND, CONFLICT, FORBIDDEN }
}

data class VaultState(val meta: VaultMeta, val entryCount: Long)

/**
 * The team's password vault. The browser encrypts everything with a vault key that only the master password
 * unwraps, so the server, the database and its backups hold sealed data it can't read. What the server checks
 * is that a caller who reads or changes entries holds that key: each such call carries the token the browser
 * derives from it, and the server compares its SHA-256 with the one recorded when the vault was created.
 * Signing in to the backoffice alone doesn't let anyone download, delete or overwrite the entries.
 */
class VaultService(private val repository: VaultRepository, private val clock: () -> Instant = SystemClock::now) {
    private val log = LoggerFactory.getLogger("Vault")

    suspend fun state(): VaultState? = repository.meta()?.let { VaultState(it, repository.count()) }

    suspend fun create(kdf: VaultKdf, wrappedKey: Sealed, authToken: String, by: String?): VaultState {
        val token = decode(authToken, "authToken")
        if (token.size != AUTH_TOKEN_BYTES) throw invalid("invalid_auth_token", "authToken")
        val now = now()
        val meta = VaultMeta(kdf(kdf), wrappedKey(wrappedKey), Base64.getEncoder().encodeToString(sha256(token)), now, by, now, by)
        if (!repository.create(meta)) throw VaultRefusal(VaultRefusal.Kind.CONFLICT, "vault_exists")
        log.info("Password vault created by {}", by ?: "a password sign-in")
        return VaultState(meta, 0)
    }

    suspend fun entries(authToken: String?): List<VaultEntry> {
        authorize(authToken)
        return repository.entries()
    }

    suspend fun addEntry(authToken: String?, sealed: Sealed, by: String?): VaultEntry {
        authorize(authToken)
        if (repository.count() >= MAX_ENTRIES) throw VaultRefusal(VaultRefusal.Kind.CONFLICT, "vault_full")
        val now = now()
        val entry = VaultEntry(sealed = entry(sealed), createdAt = now, createdBy = by, updatedAt = now, updatedBy = by)
        repository.insert(entry)
        log.info("Vault entry {} added by {}", entry.id.toHexString(), by ?: "a password sign-in")
        return entry
    }

    /** With [expectedUpdatedAt], refuses (`stale_entry`) when someone saved the entry since the caller read it. */
    suspend fun updateEntry(authToken: String?, id: ObjectId, sealed: Sealed, expectedUpdatedAt: Instant?, by: String?): VaultEntry {
        authorize(authToken)
        val updated = repository.update(id, entry(sealed), expectedUpdatedAt, now(), by)
        if (updated == null) {
            if (repository.findEntry(id) == null) throw VaultRefusal(VaultRefusal.Kind.NOT_FOUND, "entry_not_found")
            throw VaultRefusal(VaultRefusal.Kind.CONFLICT, "stale_entry")
        }
        log.info("Vault entry {} changed by {}", id.toHexString(), by ?: "a password sign-in")
        return updated
    }

    suspend fun deleteEntry(authToken: String?, id: ObjectId, by: String?) {
        authorize(authToken)
        if (!repository.delete(id)) throw VaultRefusal(VaultRefusal.Kind.NOT_FOUND, "entry_not_found")
        log.info("Vault entry {} deleted by {}", id.toHexString(), by ?: "a password sign-in")
    }

    /** A new master password: the same vault key, sealed again, so the entries and the token stay as they are. */
    suspend fun changeKey(authToken: String?, kdf: VaultKdf, wrappedKey: Sealed, by: String?): VaultState {
        authorize(authToken)
        val meta = repository.setKey(kdf(kdf), wrappedKey(wrappedKey), now(), by)
            ?: throw VaultRefusal(VaultRefusal.Kind.NOT_FOUND, "no_vault")
        log.info("Vault master password changed by {}", by ?: "a password sign-in")
        return VaultState(meta, repository.count())
    }

    private suspend fun authorize(authToken: String?) {
        val meta = repository.meta() ?: throw VaultRefusal(VaultRefusal.Kind.NOT_FOUND, "no_vault")
        if (authToken.isNullOrBlank()) throw VaultRefusal(VaultRefusal.Kind.FORBIDDEN, "vault_locked")
        val token = runCatching { Base64.getDecoder().decode(authToken.trim()) }.getOrNull()
        val expected = Base64.getDecoder().decode(meta.authHash)
        if (token == null || !MessageDigest.isEqual(sha256(token), expected)) throw VaultRefusal(VaultRefusal.Kind.FORBIDDEN, "vault_auth_failed")
    }

    private fun kdf(kdf: VaultKdf): VaultKdf {
        if (kdf.name != KDF_NAME || kdf.hash != KDF_HASH || kdf.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw invalid("invalid_kdf", "kdf")
        if (decode(kdf.salt, "kdf").size !in MIN_SALT_BYTES..MAX_SALT_BYTES) throw invalid("invalid_kdf", "kdf")
        return kdf
    }

    private fun wrappedKey(sealed: Sealed): Sealed = sealed(sealed, WRAPPED_KEY_BYTES..WRAPPED_KEY_BYTES, "wrappedKey")

    private fun entry(sealed: Sealed): Sealed = sealed(sealed, (GCM_TAG_BYTES + 1)..(MAX_ENTRY_BYTES + GCM_TAG_BYTES), "data")

    private fun sealed(sealed: Sealed, sizes: IntRange, field: String): Sealed {
        if (decode(sealed.iv, field).size != IV_BYTES) throw invalid("invalid_sealed", field)
        if (decode(sealed.data, field).size !in sizes) throw invalid("invalid_sealed", field)
        return sealed
    }

    private fun decode(value: String, field: String): ByteArray =
        runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: throw invalid("invalid_sealed", field)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    // Mongo keeps milliseconds, so the updatedAt a caller read back matches the stored one.
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock().toEpochMilliseconds())

    private fun invalid(error: String, field: String) = VaultRefusal(VaultRefusal.Kind.INVALID, error, field)

    companion object {
        const val KDF_NAME = "PBKDF2"
        const val KDF_HASH = "SHA-256"
        private const val MIN_ITERATIONS = 100_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val MIN_SALT_BYTES = 16
        private const val MAX_SALT_BYTES = 64
        private const val IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16

        /** A 32-byte AES key and its tag. */
        private const val WRAPPED_KEY_BYTES = 32 + GCM_TAG_BYTES
        private const val AUTH_TOKEN_BYTES = 32
        private const val MAX_ENTRY_BYTES = 64 * 1024
        private const val MAX_ENTRIES = 2_000

        fun of(mongo: MongoModule) = VaultService(VaultRepository(mongo))
    }
}
