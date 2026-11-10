package com.rfm.edubot.integrations.mailbox

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.aws.S3Client
import com.rfm.edubot.integrations.aws.SesClient
import com.rfm.edubot.integrations.email.EmailAddresses
import com.rfm.edubot.tenant.model.Tenant
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The companies' own addresses on the platform's mail domain (docs/architecture.md, "Company email
 * addresses"): each company gets one, forwards its mail there (Amazon SES receives it into S3 and
 * announces it over SNS, [MailboxInbound]) and its email goes out from it through SES ([SesClient]).
 */
class MailboxIntegration(
    private val configProvider: () -> AppConfig.MailboxConfig,
    val connections: IntegrationConnectionRepository,
    val ses: SesClient,
    val s3: S3Client,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    val config: AppConfig.MailboxConfig get() = configProvider()

    /** Companies may get an address: mail can reach it and leave from it. */
    val configured: Boolean get() = config.enabled

    /** Mail can go out from the addresses companies already have. */
    val sending: Boolean get() = config.sendingEnabled

    val domain: String get() = config.domain

    /** Whether [address] is on the platform's mail domain. */
    fun owns(address: String): Boolean = domain.isNotBlank() && address.trim().lowercase().endsWith("@$domain")

    /**
     * The company's address, made now if it has none yet. Replies start going to the address on its
     * documents, the one it is likely to forward from. Null when the platform has no mail domain.
     */
    suspend fun create(tenant: Tenant, createdByUserId: String?, createdByEmail: String?): IntegrationConnection? {
        if (!configured) return null
        connections.mailboxFor(tenant.id)?.let { return it }
        val replyTo = EmailAddresses.normalize(tenant.documentTemplate.email)?.takeIf { !owns(it) }
        repeat(ATTEMPTS) {
            val address = MailboxAddresses.generate(tenant.slug, domain, random)
            connections.createMailbox(tenant.id, address, replyTo, createdByUserId, createdByEmail)?.let { return it }
        }
        return null
    }

    private companion object {
        const val ATTEMPTS = 5
    }
}

/** How a company's address is made: its slug, a dot and six random characters, e.g. `family-clean.k7p2x9@mail.thebotslab.pt`. */
object MailboxAddresses {
    /** No 0/o, 1/l/i: the address gets read out and typed into mail settings. */
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    private const val SUFFIX = 6
    private const val MAX_BASE = 30

    fun generate(slug: String, domain: String, random: Random): String {
        val suffix = (1..SUFFIX).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "${base(slug)}.$suffix@$domain"
    }

    /** The slug as an address can hold it: unaccented letters, digits and single hyphens. */
    fun base(slug: String): String =
        java.text.Normalizer.normalize(slug.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_BASE).trim('-').ifEmpty { "company" }
}
