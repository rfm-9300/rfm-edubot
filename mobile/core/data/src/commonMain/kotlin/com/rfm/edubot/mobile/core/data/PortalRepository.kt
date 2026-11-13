package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.CatalogItem
import com.rfm.edubot.mobile.core.model.PortalClient
import com.rfm.edubot.mobile.core.model.ServiceSubmission
import com.rfm.edubot.mobile.core.model.SubmitService
import com.rfm.edubot.mobile.core.network.PortalApi
import kotlinx.serialization.builtins.ListSerializer

/**
 * An employee's My services. Cached under the [employeeId] so the next person signing in on a shared
 * phone never sees the previous one's submissions from disk.
 */
class PortalRepository(
    private val api: PortalApi,
    cache: SnapshotCache,
    val employeeId: String,
) {
    val services = CachedResource(
        key = "portal.services.$employeeId",
        serializer = ListSerializer(ServiceSubmission.serializer()),
        cache = cache,
        fetch = { api.myServices().sortedByDescending { it.performedAt } },
    )

    val clients = CachedResource(
        key = "portal.clients.$employeeId",
        serializer = ListSerializer(PortalClient.serializer()),
        cache = cache,
        fetch = { api.clients() },
    )

    val catalog = CachedResource(
        key = "portal.catalog.$employeeId",
        serializer = ListSerializer(CatalogItem.serializer()),
        cache = cache,
        fetch = { api.catalog() },
    )

    /** Registers a service ([id] null) or changes one still pending. */
    suspend fun save(request: SubmitService, id: String? = null): Outcome<ServiceSubmission> {
        val saved = if (id == null) apiCall { api.submit(request) } else apiCall { api.update(id, request) }
        saved.valueOrNull?.let { submission ->
            services.mutate { current ->
                if (current.any { it.id == submission.id }) current.map { if (it.id == submission.id) submission else it }
                else listOf(submission) + current
            }
        }
        return saved
    }

    /** Takes back a service nobody decided on yet. */
    suspend fun withdraw(submission: ServiceSubmission): Outcome<Unit> {
        val done = apiCall { api.withdraw(submission.id) }
        if (done is Outcome.Success) services.mutate { current -> current.filterNot { it.id == submission.id } }
        return done
    }
}
