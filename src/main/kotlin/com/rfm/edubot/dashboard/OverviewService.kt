package com.rfm.edubot.dashboard

import com.mongodb.client.model.Filters
import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.instagram.InstagramCommentRepository
import com.rfm.edubot.oauth.InstagramOAuthScopes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.persona.PersonaStatus
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

class OverviewService(private val mongo: MongoModule) {
    suspend fun build(tenant: Tenant): OverviewDto = coroutineScope {
        val modules = DashboardModules.effectiveFor(tenant).toSet()
        val window = OverviewMath.window(SystemClock.now(), TenantTimeZones.normalize(tenant.timezone))
        val tenantFilter = Filters.eq("tenantId", tenant.id)

        val usersCount = async { coll("users").countDocuments(tenantFilter) }
        val conversationsCount = async { coll("conversations").countDocuments(tenantFilter) }
        val messagesCount = async { coll("messages").countDocuments(tenantFilter) }
        val messagesTodayCount = async {
            coll("messages").countDocuments(Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.todayStart.toEpochMilliseconds()))))
        }
        val quotesCount = async { coll("crm.quotes").countDocuments(tenantFilter) }
        val invoicesCount = async { coll("crm.invoices").countDocuments(tenantFilter) }

        val cash = async { if (DashboardModules.INVOICES in modules) cash(tenant.id, window) else null }
        val pipeline = async { if (DashboardModules.QUOTES in modules) pipeline(tenant.id, window) else null }
        val customers = async { if (DashboardModules.CLIENTS in modules) customers(tenant.id, window) else null }
        val waitingList = async {
            if (DashboardModules.CONVERSATIONS in modules) waitingConversations(tenant.id) else emptyList()
        }
        val inbox = async {
            if (DashboardModules.CONVERSATIONS in modules || DashboardModules.CONTACTS in modules) {
                inbox(tenant.id, window, conversationsCount.await(), usersCount.await(), messagesTodayCount.await(), waitingList.await().size)
            } else {
                null
            }
        }
        val calendar = async { if (DashboardModules.BOOKINGS in modules) calendar(tenant.id, window) else null }
        val social = async { if (DashboardModules.INSTAGRAM in modules) social(tenant) else null }
        val catalog = async {
            if (DashboardModules.CATALOG in modules) OverviewCatalogDto(items = coll("crm.standard_items").countDocuments(tenantFilter)) else null
        }
        val services = async { if (DashboardModules.SERVICES in modules) services(tenant.id, window) else null }
        val assistant = async {
            if (DashboardModules.AI_ASSISTANT in modules) {
                OverviewAssistantDto(
                    pendingActions = coll("dashboard_assistant_messages").countDocuments(
                        Filters.and(tenantFilter, Filters.eq("action.status", "PENDING")),
                    ).toInt(),
                )
            } else {
                null
            }
        }
        val personaEmpty = async {
            if (DashboardModules.PERSONA in modules) {
                val persona = PersonaRepository(mongo).findByTenant(tenant.id)
                persona == null || persona.status == PersonaStatus.EMPTY || persona.compiledInstructions.isBlank()
            } else {
                false
            }
        }

        val cashDto = cash.await()
        val pipelineDto = pipeline.await()
        val customersDto = customers.await()
        val inboxDto = inbox.await()
        val calendarDto = calendar.await()
        val socialDto = social.await()
        val catalogDto = catalog.await()
        val servicesDto = services.await()
        val assistantDto = assistant.await()

        val waiting = waitingList.await()
        val attention = attentionItems(tenant.id, window, modules, cashDto, pipelineDto, inboxDto, calendarDto, socialDto, assistantDto, waiting)
        val setup = OverviewMath.setupItems(
            modules = modules,
            hasWhatsApp = tenant.binding(Platform.WHATSAPP) != null,
            hasInstagram = tenant.binding(Platform.INSTAGRAM) != null,
            hasWidget = tenant.binding(Platform.WEB)?.externalId?.isNotBlank() == true,
            personaEmpty = personaEmpty.await(),
        )
        val health = OverviewMath.health(
            overdueCount = cashDto?.overdueCount ?: 0,
            waiting = inboxDto?.waiting ?: 0,
            pendingBookings = calendarDto?.pending ?: 0,
            unreplied = socialDto?.unreplied ?: 0,
            dueSoonCount = cashDto?.dueSoonCount ?: 0,
            expiringQuotes = pipelineDto?.expiringSoonCount ?: 0,
            pendingAssistant = assistantDto?.pendingActions ?: 0,
        )
        OverviewHomeLayout.apply(
            OverviewDto(
                users = usersCount.await(),
                conversations = conversationsCount.await(),
                messages = messagesCount.await(),
                messagesToday = messagesTodayCount.await(),
                quotes = quotesCount.await(),
                invoices = invoicesCount.await(),
                instagramUnreplied = (socialDto?.unreplied ?: 0).toLong(),
                generatedAt = window.now.toString(),
                timezone = window.zone.id,
                today = window.today.toString(),
                attentionCount = attention.size,
                health = health,
                highlights = OverviewMath.pickHighlights(modules, cashDto, pipelineDto, inboxDto, calendarDto, customersDto, socialDto),
                attention = attention,
                cash = cashDto,
                pipeline = pipelineDto,
                customers = customersDto,
                inbox = inboxDto,
                calendar = calendarDto,
                social = socialDto,
                catalog = catalogDto,
                services = servicesDto,
                assistant = assistantDto,
                setup = setup,
            ),
            tenant.overviewHiddenCards,
        )
    }

    private suspend fun cash(tenantId: ObjectId, window: OverviewMath.Window): OverviewCashDto {
        val byStatus = sumByStatus("crm.invoices", tenantId)
        val collectedThisMonth = sumPaid(tenantId, window.monthStart, window.nextMonthStart)
        val collectedLastMonth = sumPaid(tenantId, window.lastMonthStart, window.monthStart)
        val issuedThisMonth = sumCreated("crm.invoices", tenantId, window.monthStart, window.nextMonthStart)
        val open = coll("crm.invoices").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("status", listOf("PENDING", "OVERDUE")),
            ),
        ).limit(300).toList()

        var overdueCents = 0L
        var overdueCount = 0
        var dueSoonCents = 0L
        var dueSoonCount = 0
        var agingCurrent = 0L
        var agingWeek = 0L
        var agingMonth = 0L
        var agingOld = 0L
        val overdueDocs = mutableListOf<Document>()
        for (doc in open) {
            val cents = doc.get("totalCents").asLong()
            val due = doc.localDate("dueDate")
            val status = doc.getString("status") ?: "PENDING"
            when (OverviewMath.agingBucket(due ?: window.today, window.today)) {
                OverviewMath.AgingBucket.CURRENT -> agingCurrent += cents
                OverviewMath.AgingBucket.WEEK -> agingWeek += cents
                OverviewMath.AgingBucket.MONTH -> agingMonth += cents
                OverviewMath.AgingBucket.OLD -> agingOld += cents
            }
            if (OverviewMath.isEffectivelyOverdue(status, due, window.today)) {
                overdueCents += cents
                overdueCount += 1
                overdueDocs += doc
            } else if (status == "PENDING" && due != null && due >= window.today && due <= window.dueSoonEnd) {
                dueSoonCents += cents
                dueSoonCount += 1
            }
        }
        val outstanding = (byStatus["PENDING"]?.cents ?: 0L) + (byStatus["OVERDUE"]?.cents ?: 0L)
        val top = overdueDocs.sortedByDescending { it.get("totalCents").asLong() }.take(3)
        val names = clientNames(tenantId, top.mapNotNull { it.getObjectIdOrNull("clientId") })
        return OverviewCashDto(
            collectedThisMonthCents = collectedThisMonth.first,
            collectedLastMonthCents = collectedLastMonth.first,
            issuedThisMonthCents = issuedThisMonth,
            outstandingCents = outstanding,
            overdueCents = overdueCents,
            overdueCount = overdueCount,
            dueSoonCents = dueSoonCents,
            dueSoonCount = dueSoonCount,
            invoiceCount = byStatus.values.sumOf { it.count },
            paidCountThisMonth = collectedThisMonth.second,
            agingCurrentCents = agingCurrent,
            agingWeekCents = agingWeek,
            agingMonthCents = agingMonth,
            agingOldCents = agingOld,
            topOverdue = top.map { doc ->
                val clientId = doc.getObjectIdOrNull("clientId")
                OverviewNamedAmountDto(
                    id = doc.getObjectId("_id").toHexString(),
                    name = clientId?.let { names[it] }.orEmpty().ifBlank { doc.getString("number") ?: "" },
                    amountCents = doc.get("totalCents").asLong(),
                    number = doc.getString("number"),
                )
            },
        )
    }

    private suspend fun pipeline(tenantId: ObjectId, window: OverviewMath.Window): OverviewPipelineDto {
        val byStatus = sumByStatus("crm.quotes", tenantId)
        val pending = byStatus["PENDENTE"] ?: StatusSum()
        val sent = byStatus["SENT"] ?: StatusSum()
        val accepted = byStatus["ACEITO"] ?: StatusSum()
        val acceptedThisMonth = coll("crm.quotes").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("status", "ACEITO"),
                Filters.gte("updatedAt", Date(window.monthStart.toEpochMilliseconds())),
                Filters.lt("updatedAt", Date(window.nextMonthStart.toEpochMilliseconds())),
            ),
        ).toList()
        val expiring = coll("crm.quotes").countDocuments(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("status", listOf("PENDENTE", "SENT")),
                Filters.gte("validUntil", window.today.toString()),
                Filters.lte("validUntil", window.dueSoonEnd.toString()),
            ),
        )
        return OverviewPipelineDto(
            openCents = pending.cents + sent.cents,
            pendingCount = pending.count,
            sentCount = sent.count,
            acceptedCount = accepted.count,
            acceptedThisMonthCents = acceptedThisMonth.sumOf { it.get("totalCents").asLong() },
            acceptedThisMonthCount = acceptedThisMonth.size,
            winRatePct = OverviewMath.winRatePct(accepted.count, pending.count, sent.count),
            expiringSoonCount = expiring.toInt(),
            quoteCount = byStatus.values.sumOf { it.count },
        )
    }

    private suspend fun services(tenantId: ObjectId, window: OverviewMath.Window): OverviewServicesDto {
        val tenantFilter = Filters.eq("tenantId", tenantId)
        val open = coll("crm.client_services").find(Filters.and(tenantFilter, Filters.eq("status", "OPEN"))).limit(500).toList()
        val invoiced = coll("crm.client_services").find(
            Filters.and(
                tenantFilter,
                Filters.eq("status", "INVOICED"),
                Filters.gte("updatedAt", Date(window.monthStart.toEpochMilliseconds())),
                Filters.lt("updatedAt", Date(window.nextMonthStart.toEpochMilliseconds())),
            ),
        ).limit(500).toList()
        return OverviewServicesDto(
            openCount = open.size,
            openCents = open.sumOf { it.get("totalCents").asLong() },
            invoicedThisMonthCount = invoiced.size,
            invoicedThisMonthCents = invoiced.sumOf { it.get("totalCents").asLong() },
        )
    }

    private suspend fun customers(tenantId: ObjectId, window: OverviewMath.Window): OverviewCustomersDto {
        val tenantFilter = Filters.eq("tenantId", tenantId)
        return OverviewCustomersDto(
            total = coll("crm.clients").countDocuments(tenantFilter),
            newThisMonth = coll("crm.clients").countDocuments(
                Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.monthStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.nextMonthStart.toEpochMilliseconds()))),
            ),
            newLastMonth = coll("crm.clients").countDocuments(
                Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.lastMonthStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.monthStart.toEpochMilliseconds()))),
            ),
        )
    }

    private suspend fun inbox(
        tenantId: ObjectId,
        window: OverviewMath.Window,
        conversations: Long,
        contacts: Long,
        messagesToday: Long,
        waiting: Int,
    ): OverviewInboxDto {
        val tenantFilter = Filters.eq("tenantId", tenantId)
        val messagesThisWeek = coll("messages").countDocuments(
            Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.weekStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.nextWeekStart.toEpochMilliseconds()))),
        )
        val messagesLastWeek = coll("messages").countDocuments(
            Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.lastWeekStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.weekStart.toEpochMilliseconds()))),
        )
        val newContactsThisWeek = coll("users").countDocuments(
            Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.weekStart.toEpochMilliseconds()))),
        )
        val autoReplyPaused = coll("conversations").countDocuments(
            Filters.and(tenantFilter, Filters.eq("autoReplyEnabled", false)),
        )
        return OverviewInboxDto(
            waiting = waiting,
            conversations = conversations,
            messagesToday = messagesToday,
            messagesThisWeek = messagesThisWeek,
            messagesLastWeek = messagesLastWeek,
            contacts = contacts,
            newContactsThisWeek = newContactsThisWeek,
            autoReplyPaused = autoReplyPaused.toInt(),
        )
    }

    private suspend fun calendar(tenantId: ObjectId, window: OverviewMath.Window): OverviewCalendarDto {
        val repo = BookingRepository(mongo, tenantId)
        val week = repo.list(from = window.weekStart, to = window.nextWeekStart)
            .filter { it.status != BookingStatus.CANCELLED }
        val today = week.filter { it.startAt >= window.todayStart && it.startAt < window.tomorrowStart }
        val pending = repo.list(status = BookingStatus.PENDING).filter { it.startAt >= window.now }
        val upcoming = week.filter { it.startAt >= window.now && it.status != BookingStatus.CANCELLED }
            .minByOrNull { it.startAt }
        return OverviewCalendarDto(
            today = today.size,
            thisWeek = week.size,
            pending = pending.size,
            next = upcoming?.let { OverviewCalendarNextDto(it.id.toHexString(), it.contactName, it.startAt.toString()) },
        )
    }

    private suspend fun social(tenant: Tenant): OverviewSocialDto {
        val binding = tenant.binding(Platform.INSTAGRAM)
        val unreplied = InstagramCommentRepository(mongo, tenant.id).countUnreplied()
        return OverviewSocialDto(
            unreplied = unreplied,
            connected = binding != null,
            commentsEnabled = binding != null && InstagramOAuthScopes.hasComments(binding.grantedScopes),
        )
    }

    private suspend fun attentionItems(
        tenantId: ObjectId,
        window: OverviewMath.Window,
        modules: Set<String>,
        cash: OverviewCashDto?,
        pipeline: OverviewPipelineDto?,
        inbox: OverviewInboxDto?,
        calendar: OverviewCalendarDto?,
        social: OverviewSocialDto?,
        assistant: OverviewAssistantDto?,
        waiting: List<WaitingConversation>,
    ): List<OverviewAttentionItemDto> {
        val items = mutableListOf<OverviewAttentionItemDto>()
        if (DashboardModules.INVOICES in modules && cash != null) {
            val open = coll("crm.invoices").find(
                Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("status", listOf("PENDING", "OVERDUE"))),
            ).limit(80).toList()
            val names = clientNames(tenantId, open.mapNotNull { it.getObjectIdOrNull("clientId") })
            open.sortedBy { it.localDate("dueDate") ?: window.today }.forEach { doc ->
                val due = doc.localDate("dueDate")
                val status = doc.getString("status") ?: "PENDING"
                val cents = doc.get("totalCents").asLong()
                val number = doc.getString("number") ?: ""
                val name = doc.getObjectIdOrNull("clientId")?.let { names[it] }.orEmpty()
                val detail = listOf(number, name).filter { it.isNotBlank() }.joinToString(" · ")
                if (OverviewMath.isEffectivelyOverdue(status, due, window.today)) {
                    items += OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_OVERDUE_INVOICE,
                        tab = DashboardModules.INVOICES,
                        id = doc.getObjectId("_id").toHexString(),
                        detail = detail,
                        amountCents = cents,
                        at = due?.toString(),
                    )
                } else if (status == "PENDING" && due != null && due >= window.today && due <= window.dueSoonEnd && items.count { it.kind == OverviewMath.KIND_DUE_SOON_INVOICE } < 3) {
                    items += OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_DUE_SOON_INVOICE,
                        tab = DashboardModules.INVOICES,
                        id = doc.getObjectId("_id").toHexString(),
                        detail = detail,
                        amountCents = cents,
                        at = due.toString(),
                    )
                }
            }
        }
        if (DashboardModules.CONVERSATIONS in modules) {
            waiting.take(5).forEach { row ->
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_WAITING_CHAT,
                    tab = DashboardModules.CONVERSATIONS,
                    id = row.id,
                    detail = row.detail,
                    at = row.at,
                )
            }
        }
        if (DashboardModules.BOOKINGS in modules) {
            BookingRepository(mongo, tenantId).list(status = BookingStatus.PENDING)
                .filter { it.startAt >= window.now }
                .sortedBy { it.startAt }
                .take(5)
                .forEach { booking ->
                    items += OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_PENDING_BOOKING,
                        tab = DashboardModules.BOOKINGS,
                        id = booking.id.toHexString(),
                        detail = booking.contactName,
                        at = booking.startAt.toString(),
                    )
                }
        }
        if (DashboardModules.INSTAGRAM in modules && (social?.unreplied ?: 0) > 0) {
            InstagramCommentRepository(mongo, tenantId).listUnreplied(5).forEach { comment ->
                val who = comment.fromUsername?.let { "@$it" } ?: "—"
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_INSTAGRAM_COMMENT,
                    tab = DashboardModules.INSTAGRAM,
                    id = comment.commentId,
                    detail = listOf(who, comment.text.trim().take(80)).filter { it.isNotBlank() }.joinToString(" · "),
                    at = comment.createdAt.toString(),
                )
            }
        }
        if (DashboardModules.QUOTES in modules && (pipeline?.expiringSoonCount ?: 0) > 0) {
            val expiring = coll("crm.quotes").find(
                Filters.and(
                    Filters.eq("tenantId", tenantId),
                    Filters.`in`("status", listOf("PENDENTE", "SENT")),
                    Filters.gte("validUntil", window.today.toString()),
                    Filters.lte("validUntil", window.dueSoonEnd.toString()),
                ),
            ).limit(4).toList()
            val names = clientNames(tenantId, expiring.mapNotNull { it.getObjectIdOrNull("clientId") })
            expiring.forEach { doc ->
                val number = doc.getString("number") ?: ""
                val name = doc.getObjectIdOrNull("clientId")?.let { names[it] }.orEmpty()
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_QUOTE_EXPIRING,
                    tab = DashboardModules.QUOTES,
                    id = doc.getObjectId("_id").toHexString(),
                    detail = listOf(number, name).filter { it.isNotBlank() }.joinToString(" · "),
                    amountCents = doc.get("totalCents").asLong(),
                    at = doc.getString("validUntil"),
                )
            }
        }
        if (DashboardModules.AI_ASSISTANT in modules && (assistant?.pendingActions ?: 0) > 0) {
            items += OverviewAttentionItemDto(
                kind = OverviewMath.KIND_ASSISTANT_ACTION,
                tab = DashboardModules.AI_ASSISTANT,
                detail = assistant!!.pendingActions.toString(),
            )
        }
        return rankAttention(items)
    }

    private fun rankAttention(items: List<OverviewAttentionItemDto>): List<OverviewAttentionItemDto> {
        val order = listOf(
            OverviewMath.KIND_OVERDUE_INVOICE,
            OverviewMath.KIND_WAITING_CHAT,
            OverviewMath.KIND_PENDING_BOOKING,
            OverviewMath.KIND_INSTAGRAM_COMMENT,
            OverviewMath.KIND_DUE_SOON_INVOICE,
            OverviewMath.KIND_QUOTE_EXPIRING,
            OverviewMath.KIND_ASSISTANT_ACTION,
        )
        return items.sortedBy { order.indexOf(it.kind).let { idx -> if (idx < 0) 99 else idx } }.take(10)
    }

    private data class WaitingConversation(val id: String, val detail: String, val at: String?)

    private suspend fun waitingConversations(tenantId: ObjectId): List<WaitingConversation> {
        val conversations = ConversationRepository(mongo, tenantId).list(limit = 80)
        if (conversations.isEmpty()) return emptyList()
        val last = MessageRepository(mongo, tenantId).lastByConversationIds(conversations.map { it.id })
        val displayNames = com.rfm.edubot.conversation.UserRepository(mongo, tenantId)
            .displayNamesByIds(conversations.map { it.userId })
        return conversations.mapNotNull { convo ->
            val message = last[convo.id] ?: return@mapNotNull null
            if (message.role != UserRole.USER) return@mapNotNull null
            val name = displayNames[convo.userId]?.takeIf { it.isNotBlank() } ?: convo.waId
            val preview = when (val content = message.content) {
                is MessageContent.Text -> content.body.trim()
                else -> ""
            }.take(80)
            WaitingConversation(
                id = convo.id.toHexString(),
                detail = listOf(name, preview).filter { it.isNotBlank() }.joinToString(" · "),
                at = convo.lastMessageAt.toString(),
            )
        }
    }

    private suspend fun sumByStatus(collection: String, tenantId: ObjectId): Map<String, StatusSum> {
        val pipeline = listOf(
            Document("\$match", Document("tenantId", tenantId)),
            Document(
                "\$group",
                Document("_id", "\$status")
                    .append("cents", Document("\$sum", "\$totalCents"))
                    .append("count", Document("\$sum", 1)),
            ),
        )
        return coll(collection).aggregate<Document>(pipeline).toList().associate { doc ->
            (doc.get("_id")?.toString() ?: "") to StatusSum(doc.get("cents").asLong(), doc.get("count").asLong().toInt())
        }
    }

    private suspend fun sumPaid(tenantId: ObjectId, from: Instant, to: Instant): Pair<Long, Int> {
        val docs = coll("crm.invoices").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("status", "PAID"),
                Filters.or(
                    Filters.and(Filters.gte("paidAt", Date(from.toEpochMilliseconds())), Filters.lt("paidAt", Date(to.toEpochMilliseconds()))),
                    Filters.and(Filters.exists("paidAt", false), Filters.gte("updatedAt", Date(from.toEpochMilliseconds())), Filters.lt("updatedAt", Date(to.toEpochMilliseconds()))),
                ),
            ),
        ).toList()
        return docs.sumOf { it.get("totalCents").asLong() } to docs.size
    }

    private suspend fun sumCreated(collection: String, tenantId: ObjectId, from: Instant, to: Instant): Long {
        val pipeline = listOf(
            Document(
                "\$match",
                Document("tenantId", tenantId)
                    .append("createdAt", Document("\$gte", Date(from.toEpochMilliseconds())).append("\$lt", Date(to.toEpochMilliseconds())))
                    .append("status", Document("\$ne", "CANCELLED")),
            ),
            Document("\$group", Document("_id", null).append("cents", Document("\$sum", "\$totalCents"))),
        )
        return coll(collection).aggregate<Document>(pipeline).toList().firstOrNull()?.get("cents").asLong()
    }

    private suspend fun clientNames(tenantId: ObjectId, ids: Collection<ObjectId>): Map<ObjectId, String> {
        if (ids.isEmpty()) return emptyMap()
        return coll("crm.clients").find(Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("_id", ids.toList())))
            .toList()
            .associate { it.getObjectId("_id") to (it.getString("name") ?: "") }
    }

    private fun coll(name: String) = mongo.database.getCollection<Document>(name)

    private data class StatusSum(val cents: Long = 0, val count: Int = 0)
}

private fun Any?.asLong(): Long = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Double -> this.toLong()
    is Number -> this.toLong()
    else -> 0L
}

private fun Document.getObjectIdOrNull(field: String): ObjectId? = get(field, ObjectId::class.java)

private fun Document.localDate(field: String): LocalDate? =
    getString(field)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
