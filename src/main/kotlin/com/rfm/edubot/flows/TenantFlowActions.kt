package com.rfm.edubot.flows

import com.rfm.edubot.bookings.BookingConflictException
import com.rfm.edubot.bookings.BookingRuleException
import com.rfm.edubot.bookings.BookingScheduler
import com.rfm.edubot.bookings.BookableServiceRepository
import com.rfm.edubot.bookings.NewBooking
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.tenant.model.Platform
import org.bson.types.ObjectId

/**
 * A company's flow actions on its real records. [clients] and [booking] are null when the company doesn't have that
 * module, so the action fails (and the flow takes its fail path) instead of writing where the company can't see.
 */
class TenantFlowActions(
    private val tenantId: ObjectId,
    private val clients: ClientRepository?,
    private val booking: BookingSide?,
    private val notifications: NotificationRepository,
) : FlowActions {
    /** What a booking needs: the scheduler, and the services a customer's words are matched against. */
    class BookingSide(val scheduler: BookingScheduler, val services: BookableServiceRepository)

    override suspend fun isKnownClient(phone: String?): Boolean =
        phone != null && clients?.findAllByPhone(phone)?.any { it.archivedAt == null } == true

    override suspend fun createClient(client: FlowClient): FlowActionResult {
        val repo = clients ?: return FlowActionResult(false, "clients module off")
        val existing = repo.findAllByPhone(client.phone).firstOrNull()
        if (existing != null) {
            // A client who comes back is restored, not duplicated; one already there is left as the team has it.
            if (existing.archivedAt != null) repo.setArchived(existing.id, archived = false)
            return FlowActionResult(true, "already a client: ${existing.number}")
        }
        val created = repo.create(name = client.name, phone = client.phone, email = client.email, notes = client.notes)
        return FlowActionResult(true, "created ${created.number}")
    }

    override suspend fun createBooking(booking: FlowBooking): FlowActionResult {
        val side = this.booking ?: return FlowActionResult(false, "bookings module off")
        val service = booking.serviceId?.let { side.services.findById(it) }?.takeIf { it.active }
            ?: booking.serviceName?.let { wanted -> side.services.list(activeOnly = true).let { all -> matchService(all.map { it.name to it }, wanted) } }
            ?: return FlowActionResult(false, "service not found")
        val source = when (booking.platform) {
            Platform.WHATSAPP -> BookingSource.WHATSAPP
            Platform.INSTAGRAM -> BookingSource.INSTAGRAM
            Platform.WEB -> BookingSource.WEB
        }
        return try {
            side.scheduler.create(
                NewBooking(
                    serviceId = service.id,
                    startAt = booking.startAt,
                    contactName = booking.name,
                    contactPhone = booking.phone,
                    notes = booking.notes,
                    status = BookingStatus.PENDING,
                    source = source,
                ),
            )
            FlowActionResult(true, "booked ${service.name}")
        } catch (e: BookingRuleException) {
            FlowActionResult(false, e.code)
        } catch (e: BookingConflictException) {
            FlowActionResult(false, "slot taken")
        }
    }

    override suspend fun notifyTeam(note: FlowTeamNote): FlowActionResult {
        val answers = note.answers.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
        notifications.notify(
            tenantId = tenantId,
            kind = NotificationKinds.CHATBOT_ANSWERS,
            audience = NotificationAudience.ALL,
            params = mapOf(
                "name" to (note.answers["name"] ?: note.customerName?.takeIf { it.isNotBlank() } ?: note.waId),
                "flow" to note.flowName,
                "answers" to answers,
                "channel" to note.platform.name,
            ),
            body = answers.takeIf { it.isNotBlank() },
            link = DashboardModules.CONVERSATIONS,
            subject = note.conversationId?.let { SubjectRef.of(SubjectTypes.CONVERSATION, it) },
        )
        return FlowActionResult(true, "team told")
    }

    private fun <T> matchService(named: List<Pair<String, T>>, wanted: String): T? {
        val said = FlowText.normalize(wanted)
        named.firstOrNull { FlowText.normalize(it.first) == said }?.let { return it.second }
        return named.filter { FlowText.containsPhrase(said, it.first) || FlowText.containsPhrase(FlowText.normalize(it.first), wanted) }.singleOrNull()?.second
    }
}

/** For the test chat: nothing is written, every action "works", and the effects list says what it would have done. */
class DryRunFlowActions(private val knownClient: Boolean = false) : FlowActions {
    override suspend fun isKnownClient(phone: String?) = knownClient
    override suspend fun createClient(client: FlowClient) = FlowActionResult(true, "${client.name} · ${client.phone}")
    override suspend fun createBooking(booking: FlowBooking) = FlowActionResult(true, "${booking.serviceName ?: booking.serviceId} · ${booking.startAt}")
    override suspend fun notifyTeam(note: FlowTeamNote) = FlowActionResult(true, note.answers.entries.joinToString(" · ") { "${it.key}: ${it.value}" })
}
