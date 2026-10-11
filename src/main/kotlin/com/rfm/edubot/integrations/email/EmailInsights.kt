package com.rfm.edubot.integrations.email

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/** What a received email is about, as the model reads it; the Email page picks its suggestions from it. */
object EmailIntents {
    const val QUOTE_REQUEST = "quote_request"
    const val BOOKING_REQUEST = "booking_request"
    const val PAYMENT_SENT = "payment_sent"
    const val SUPPLIER_BILL = "supplier_bill"
    const val QUOTE_REPLY = "quote_reply"
    const val QUESTION = "question"
    const val COMPLAINT = "complaint"
    const val OTHER = "other"

    val all = listOf(QUOTE_REQUEST, BOOKING_REQUEST, PAYMENT_SENT, SUPPLIER_BILL, QUOTE_REPLY, QUESTION, COMPLAINT, OTHER)
}

/** Who wrote, as their signature or text says. Every field is what the email states, never a guess. */
data class EmailContact(
    val name: String? = null,
    val company: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val taxId: String? = null,
    val address: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
)

/** A line of what they asked for, without a price: the person sets prices in the quote. */
data class EmailItem(val description: String, val quantity: Double? = null, val unit: String? = null)

/**
 * The model's reading of one received email: a summary, what it asks for and the details a dashboard
 * form needs (a contact, lines, a date, an amount). Dates are `yyyy-mm-dd`, the time `HH:MM`; [reply]
 * is a draft a person may send. Kept on the email and dropped with its text.
 */
data class EmailInsights(
    val summary: String,
    val intent: String,
    /** For a reply about a quote: whether they accept it. */
    val accepted: Boolean? = null,
    val contact: EmailContact = EmailContact(),
    val request: String? = null,
    val items: List<EmailItem> = emptyList(),
    /** The day, and time, they asked to be seen or served. */
    val date: String? = null,
    val time: String? = null,
    /** When a bill they sent is due. */
    val dueDate: String? = null,
    val amountCents: Long? = null,
    /** A quote or invoice number they mention, as written. */
    val documentNumber: String? = null,
    /** One of the company's bookable services, exactly as named to the model. */
    val serviceName: String? = null,
    val reply: String? = null,
    val generatedAt: Instant,
) {
    companion object {
        const val MAX_SUMMARY = 400
        const val MAX_REQUEST = 600
        const val MAX_REPLY = 3_000
        const val MAX_ITEMS = 10
        private const val MAX_FIELD = 200
        private const val MAX_ADDRESS = 300
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        /**
         * The model's `submit_insights` arguments as insights, or null without a summary. Anything that isn't
         * the declared type or shape (an unknown intent, a date that doesn't exist, a service the company
         * doesn't offer) is left out instead of guessed.
         */
        fun read(submitted: JsonObject, services: Collection<String>, now: Instant): EmailInsights? {
            val summary = submitted.text("summary", MAX_SUMMARY) ?: return null
            val contact = submitted["contact"] as? JsonObject
            return EmailInsights(
                summary = summary,
                intent = submitted.text("intent", 40)?.lowercase()?.takeIf { it in EmailIntents.all } ?: EmailIntents.OTHER,
                accepted = (submitted["accepted"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.toBooleanStrictOrNull() },
                contact = EmailContact(
                    name = contact?.text("name", MAX_FIELD),
                    company = contact?.text("company", MAX_FIELD),
                    phone = contact?.text("phone", 40)?.takeIf { phone -> phone.count { it.isDigit() } in 6..15 },
                    email = contact?.text("email", 254)?.let(EmailAddresses::normalize),
                    taxId = contact?.text("taxId", 32),
                    address = contact?.text("address", MAX_ADDRESS),
                    postalCode = contact?.text("postalCode", 20),
                    city = contact?.text("city", 100),
                ),
                request = submitted.text("request", MAX_REQUEST),
                items = (submitted["items"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val description = item.text("description", MAX_FIELD) ?: return@mapNotNull null
                    EmailItem(description, item.number("quantity")?.takeIf { it > 0 && it < 1_000_000 }, item.text("unit", 20))
                }.take(MAX_ITEMS),
                date = submitted.date("date"),
                time = submitted.text("time", 5)?.takeIf { TIME.matches(it) },
                dueDate = submitted.date("dueDate"),
                amountCents = submitted.number("amount")?.takeIf { it > 0 && it < 100_000_000 }?.let { (it * 100).roundToLong() },
                documentNumber = submitted.text("documentNumber", 40),
                serviceName = submitted.text("serviceName", MAX_FIELD)?.let { name -> services.firstOrNull { it.equals(name, ignoreCase = true) } },
                reply = submitted.text("reply", MAX_REPLY),
                generatedAt = now,
            )
        }

        private fun JsonObject.text(name: String, max: Int): String? =
            (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }?.take(max)

        private fun JsonObject.number(name: String): Double? {
            val primitive = this[name] as? JsonPrimitive ?: return null
            return primitive.takeIf { !it.isString }?.doubleOrNull ?: primitive.contentOrNull?.let(::parseAmount)
        }

        private fun JsonObject.date(name: String): String? =
            text(name, 10)?.takeIf { DATE.matches(it) && runCatching { LocalDate.parse(it) }.isSuccess }

        /** "1.234,50", "1,234.50", "€ 230" and "230 EUR" are all amounts. */
        internal fun parseAmount(raw: String): Double? {
            val digits = raw.filter { it.isDigit() || it == '.' || it == ',' }
            if (digits.none { it.isDigit() }) return null
            val lastSeparator = maxOf(digits.lastIndexOf('.'), digits.lastIndexOf(','))
            val decimals = if (lastSeparator >= 0) digits.length - lastSeparator - 1 else 0
            val normalized = if (lastSeparator >= 0 && decimals in 1..2) {
                digits.substring(0, lastSeparator).filter { it.isDigit() } + "." + digits.substring(lastSeparator + 1)
            } else {
                digits.filter { it.isDigit() }
            }
            return normalized.toDoubleOrNull()
        }
    }
}
