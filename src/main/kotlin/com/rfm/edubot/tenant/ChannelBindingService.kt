package com.rfm.edubot.tenant

import com.mongodb.client.model.Updates
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import org.bson.Document
import java.util.Date

/**
 * Shared channel-binding mutations used by both the admin API (manual paste) and the Instagram
 * OAuth onboarding callback (docs/plan-instagram-oauth-onboarding.md §6.4). Persists the change,
 * re-indexes the registry, and evicts the tenant pipeline so routing picks up the new binding
 * without a restart. Keeping this in one place guarantees both entry points converge on identical
 * persistence + cache behavior.
 */
class ChannelBindingService(
    private val tenantRepository: TenantRepository,
    private val tenantRegistry: TenantRegistry,
    private val pipelineFactory: TenantPipelineFactory,
) {
    /** Add or replace the binding for [binding].platform on [slug]. */
    suspend fun upsert(slug: String, binding: ChannelBinding): Tenant? {
        val tenant = tenantRepository.findBySlug(slug) ?: return null
        val next = tenant.channels.filterNot { it.platform == binding.platform } + binding
        return persist(slug, tenant, next)
    }

    /** Remove the binding identified by [platform] + [externalId] from [slug]. */
    suspend fun remove(slug: String, platform: Platform, externalId: String): Tenant? {
        val tenant = tenantRepository.findBySlug(slug) ?: return null
        val next = tenant.channels.filterNot { it.platform == platform && it.externalId == externalId }
        return persist(slug, tenant, next)
    }

    private suspend fun persist(slug: String, previous: Tenant, next: List<ChannelBinding>): Tenant? {
        val updated = tenantRepository.update(
            slug,
            Updates.combine(
                Updates.set("channels", next.map { it.toDocument() }),
                phoneNumberIdUpdate(next),
                Updates.set("updatedAt", Date(SystemClock.now().toEpochMilliseconds())),
            ),
        ) ?: return null
        tenantRegistry.remove(previous)
        tenantRegistry.put(updated)
        pipelineFactory.evict(updated.id)
        return updated
    }
}

private fun ChannelBinding.toDocument(): Document = Document("platform", platform.name)
    .append("externalId", externalId)
    .append("accessToken", accessToken)
    .appendIfNotNull("displayName", displayName)
    .appendIfNotNull("wabaId", wabaId)
    .appendIfNotNull("tokenObtainedAt", tokenObtainedAt?.let { Date(it.toEpochMilliseconds()) })
    .appendIfNotNull("source", source)
    .append("grantedScopes", grantedScopes)
    .append("allowedOrigins", allowedOrigins)

private fun phoneNumberIdUpdate(bindings: List<ChannelBinding>) =
    bindings.firstOrNull { it.platform == Platform.WHATSAPP }?.externalId?.takeIf { it.isNotBlank() }
        ?.let { Updates.set("phoneNumberId", it) }
        ?: Updates.unset("phoneNumberId")

private fun Document.appendIfNotNull(key: String, value: Any?): Document = apply {
    if (value != null) append(key, value)
}
