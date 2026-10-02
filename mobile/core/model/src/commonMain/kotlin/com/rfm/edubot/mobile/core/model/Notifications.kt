package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /app/api/notifications`. The backend sends a [kind] and its [params] rather than a sentence,
 * so each client writes the copy in its own language — the mobile catalogs key off `kind`.
 */
@Serializable
data class AppNotification(
    val id: String,
    val kind: String,
    val params: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** Web dashboard hash (`#agents`, `#invoices`); mapped to a mobile destination on tap. */
    val link: String? = null,
    val subject: AgentSubject? = null,
    val read: Boolean = false,
    val createdAt: String = "",
)

@Serializable
data class Notifications(
    val items: List<AppNotification> = emptyList(),
    val unread: Long = 0,
)
