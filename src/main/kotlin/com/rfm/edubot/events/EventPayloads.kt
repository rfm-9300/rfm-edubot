package com.rfm.edubot.events

import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.User
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientService
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.Quote
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Small snapshots stored on domain events: enough for trigger filters and timelines, never full records. */
internal object EventPayloads {
    fun client(client: Client, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("number", client.number)
        put("name", client.name)
        put("phone", client.phone)
        client.email?.let { put("email", it) }
        put("hasEmail", !client.email.isNullOrBlank())
        extra()
    }

    fun quote(quote: Quote, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("number", quote.number)
        put("clientId", quote.clientId.toHexString())
        put("status", quote.status.name)
        put("totalCents", quote.totalCents)
        quote.validUntil?.let { put("validUntil", it.toString()) }
        extra()
    }

    fun invoice(invoice: Invoice, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("number", invoice.number)
        put("clientId", invoice.clientId.toHexString())
        invoice.quoteId?.let { put("quoteId", it.toHexString()) }
        put("status", invoice.status.name)
        put("totalCents", invoice.totalCents)
        put("dueDate", invoice.dueDate.toString())
        extra()
    }

    fun payment(payment: Payment, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("number", payment.number)
        payment.supplierId?.let { put("supplierId", it.toHexString()) }
        payment.employeeId?.let { put("employeeId", it.toHexString()) }
        payment.clientId?.let { put("clientId", it.toHexString()) }
        put("status", payment.status.name)
        put("totalCents", payment.totalCents)
        put("dueDate", payment.dueDate.toString())
        extra()
    }

    fun service(service: ClientService, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("clientId", service.clientId.toHexString())
        put("name", service.name)
        put("status", service.status.name)
        put("totalCents", service.totalCents)
        service.bookingId?.let { put("bookingId", it.toHexString()) }
        extra()
    }

    fun booking(booking: Booking, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("serviceName", booking.serviceName)
        booking.clientId?.let { put("clientId", it.toHexString()) }
        put("contactName", booking.contactName)
        put("contactPhone", booking.contactPhone)
        put("startAt", booking.startAt.toString())
        put("endAt", booking.endAt.toString())
        put("status", booking.status.name)
        put("source", booking.source.name)
        booking.priceCents?.let { put("priceCents", it) }
        extra()
    }

    fun contact(user: User): JsonObject = buildJsonObject {
        put("channel", user.channel.name)
        put("waId", user.waId)
        user.displayName?.let { put("displayName", it) }
    }

    fun conversation(conversation: Conversation, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("channel", conversation.channel.name)
        put("waId", conversation.waId)
        put("userId", conversation.userId.toHexString())
        extra()
    }

    fun clientRefs(vararg ids: org.bson.types.ObjectId?): List<SubjectRef> =
        ids.filterNotNull().map { SubjectRef.of(SubjectTypes.CLIENT, it) }
}
