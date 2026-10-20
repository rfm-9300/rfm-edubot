package com.rfm.edubot.dashboard.assistant

import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.crm.ClientDirectory
import com.rfm.edubot.crm.ClientDraft
import com.rfm.edubot.crm.ClientFields
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.DirectoryFields
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `get_client`, `create_client` and `update_client`. The assistant's create_client takes the place of the bot's,
 * so a client gets what the company requires of one, checked by [ClientDirectory] like the Clients form.
 */
internal class ClientTools(private val s: AssistantToolSession) : AssistantToolGroup {
    override val spec: ToolGroupSpec get() = Companion

    private val directory by lazy { ClientDirectory(s.clients, ClientFields.of(s.tenant)) }

    override suspend fun execute(name: String, args: JsonObject): JsonObject = when (name) {
        GET_CLIENT -> getClient(args)
        CREATE_CLIENT -> createClient(args)
        UPDATE_CLIENT -> updateClient(args)
        else -> throw ToolProblem("unknown_tool", "Unknown tool.")
    }

    override suspend fun describe(name: String, args: JsonObject): JsonObject? = when (name) {
        UPDATE_CLIENT -> {
            val client = s.resolveClient(args.text("client_id") ?: return null)
            buildJsonObject {
                put("client", s.clientLabel(client))
                putJsonArray("changes") {
                    clientChanges(client, args).forEach { (field, from, to) ->
                        addJsonObject { put("field", field); from?.let { put("from", it) }; put("to", to) }
                    }
                }
            }
        }
        else -> null
    }

    override suspend fun check(name: String, args: JsonObject) {
        when (name) {
            CREATE_CLIENT -> newClient(args)
            UPDATE_CLIENT -> clientUpdate(args)
        }
    }

    private suspend fun getClient(args: JsonObject): JsonObject {
        val client = s.resolveClient(args.text("client_id") ?: throw missing("client_id"))
        val fields = ClientFields.of(s.tenant)
        val today = s.today()
        return buildJsonObject {
            put("client", s.clientJson(client, fields))
            if (DashboardModules.INVOICES in s.usable) {
                val all = s.invoices.search(clientId = client.id, limit = 500).filter { it.status != InvoiceStatus.CANCELLED }
                putJsonObject("invoices") {
                    put("count", all.size)
                    put("total_eur", eur(all.sumOf { it.totalCents }))
                    put("paid_eur", eur(all.sumOf { it.paidCents }))
                    put("outstanding_eur", eur(all.sumOf { it.outstandingCents }))
                    val overdue = all.filter { s.effective(it, today) == InvoiceStatus.OVERDUE }
                    put("overdue", overdue.size)
                    put("overdue_eur", eur(overdue.sumOf { it.outstandingCents }))
                    putJsonArray("latest") { all.take(5).forEach { add(s.invoiceRow(it, today, null)) } }
                }
            }
            if (DashboardModules.QUOTES in s.usable) {
                val all = s.quotes.search(clientId = client.id, limit = 500)
                putJsonObject("quotes") {
                    put("count", all.size)
                    val open = all.filter { it.status != QuoteStatus.ACEITO }
                    put("open", open.size)
                    put("open_eur", eur(open.sumOf { it.totalCents }))
                    put("accepted", all.count { it.status == QuoteStatus.ACEITO })
                    putJsonArray("latest") { all.take(5).forEach { add(s.quoteRow(it, today, null, null)) } }
                }
            }
            if (DashboardModules.SERVICES in s.usable) {
                val open = ClientServiceRepository(s.mongo, s.tenant.id).list(client.id, ClientServiceStatus.OPEN)
                putJsonObject("services_not_invoiced") { put("count", open.size); put("total_eur", eur(open.sumOf { it.totalCents })) }
            }
            if (DashboardModules.BOOKINGS in s.usable) {
                BookingRepository(s.mongo, s.tenant.id).list(from = SystemClock.now(), clientId = client.id).firstOrNull()?.let { next ->
                    put("next_booking", "${s.localTime(next.startAt)} · ${next.serviceName} · ${next.status.name}")
                }
            }
        }
    }

    /** A new client's details, checked like the dashboard's form checks them. */
    private suspend fun newClient(args: JsonObject): ClientDraft {
        val name = args.text("name") ?: throw missing("name")
        val phone = args.text("phone") ?: throw missing("phone")
        val fields = ClientFields.of(s.tenant)
        val draft = ClientDraft(
            name = name,
            phone = phone,
            address = args.text("address"),
            email = args.text("email"),
            taxId = args.text("tax_id"),
            notes = args.text("notes"),
            postalCode = args.text("postal_code"),
            city = args.text("city"),
            contactPerson = args.text("contact_person"),
            customFields = customValues(args, fields),
        )
        directory.check(draft)?.let { throw it.problem(fields, update = false) }
        s.clients.findByPhone(phone)?.let { throw phoneTaken(it) }
        return draft
    }

    private suspend fun createClient(args: JsonObject): JsonObject {
        val client = when (val outcome = directory.create(newClient(args))) {
            is ClientDirectory.Outcome.Saved -> outcome.client
            is ClientDirectory.Outcome.PhoneTaken -> throw outcome.owner?.let(::phoneTaken) ?: ToolProblem("phone_taken", "Another record already has this phone.")
            is ClientDirectory.Outcome.Invalid -> throw outcome.problem(ClientFields.of(s.tenant), update = false)
            ClientDirectory.Outcome.NotFound -> throw ToolProblem("client_not_found", "That client no longer exists.")
        }
        return buildJsonObject {
            put("created", true)
            put("type", "client")
            put("id", client.id.toHexString())
            put("client", s.clientJson(client, ClientFields.of(s.tenant)))
        }
    }

    /** A change to an existing client, checked like the dashboard's form checks it. */
    private class ClientUpdate(val client: Client, val draft: ClientDraft)

    private suspend fun clientUpdate(args: JsonObject): ClientUpdate {
        val client = s.resolveClient(args.text("client_id") ?: throw missing("client_id"))
        if (clientChanges(client, args).isEmpty()) throw ToolProblem("nothing_to_change", "Say which details to change.")
        val fields = ClientFields.of(s.tenant)
        val phone = args.text("phone") ?: client.phone
        val draft = ClientDraft(
            name = args.text("name") ?: client.name,
            phone = phone,
            address = if (args.has("address")) args.text("address") else client.address,
            email = args.raw("email"),
            taxId = args.raw("tax_id"),
            notes = args.raw("notes"),
            postalCode = args.raw("postal_code"),
            city = args.raw("city"),
            contactPerson = args.raw("contact_person"),
            customFields = customValues(args, fields),
        )
        directory.check(draft, existing = client)?.let { throw it.problem(fields, update = true) }
        if (args.has("phone")) s.clients.findByPhone(phone)?.takeIf { it.id != client.id }?.let { throw phoneTaken(it) }
        return ClientUpdate(client, draft)
    }

    private suspend fun updateClient(args: JsonObject): JsonObject {
        val change = clientUpdate(args)
        val updated = when (val outcome = directory.update(change.client.id, change.draft)) {
            is ClientDirectory.Outcome.Saved -> outcome.client
            is ClientDirectory.Outcome.PhoneTaken -> throw ToolProblem("phone_taken", "Another record already has this phone.")
            is ClientDirectory.Outcome.Invalid -> throw outcome.problem(ClientFields.of(s.tenant), update = true)
            ClientDirectory.Outcome.NotFound -> throw ToolProblem("client_not_found", "That client no longer exists.")
        }
        return buildJsonObject {
            put("updated", true)
            put("type", "client")
            put("id", updated.id.toHexString())
            put("client", s.clientJson(updated, ClientFields.of(s.tenant)))
        }
    }

    /** What the model reads for a refused client: what is required, what isn't valid, or which of the company's own fields. */
    private fun ClientDirectory.Outcome.Invalid.problem(fields: DirectoryFields, update: Boolean): ToolProblem {
        val key = field
        return when {
            key != null -> ToolProblem(error, "The field \"${fields.custom.firstOrNull { it.key == key }?.label ?: key}\" is missing or isn't valid: check it with the person.")
            error in ClientFields.REQUIRABLE.values -> ToolProblem(
                error,
                if (update) "The company requires ${FIELD_NAMES[error] ?: "this field"}: it can't be emptied."
                else "The company requires ${FIELD_NAMES[error] ?: "this field"} for new clients: ask the person for it.",
            )
            else -> ToolProblem(error, "${FIELD_NAMES[error]?.replaceFirstChar(Char::uppercase) ?: "A field"} isn't valid: check it with the person.")
        }
    }

    /** (field, from, to) for each detail [args] would change on [client]. */
    private fun clientChanges(client: Client, args: JsonObject): List<Triple<String, String?, String>> {
        val current = mapOf(
            "name" to client.name, "phone" to client.phone, "email" to client.email, "tax_id" to client.taxId,
            "address" to client.address, "postal_code" to client.postalCode, "city" to client.city,
            "contact_person" to client.contactPerson, "notes" to client.notes,
        )
        val standard = current.mapNotNull { (field, from) ->
            if (!args.has(field)) return@mapNotNull null
            val to = args.raw(field)?.trim().orEmpty()
            if (to == from.orEmpty()) null else Triple(field, from, to)
        }
        val fields = ClientFields.of(s.tenant)
        val custom = (args["custom_fields"] as? JsonObject).orEmpty().mapNotNull { (label, value) ->
            val field = fields.custom.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) || it.key == label } ?: return@mapNotNull null
            val to = (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val from = client.customFields[field.key]?.contentOrNull
            if (to == from.orEmpty()) null else Triple(field.label, from, to)
        }
        return standard + custom
    }

    /** The custom values in [args], keyed by field key as the client form expects; labels are accepted. */
    private fun customValues(args: JsonObject, fields: DirectoryFields): Map<String, JsonElement>? {
        val given = args["custom_fields"] as? JsonObject ?: return null
        return given.mapNotNull { (name, value) ->
            val field = fields.custom.firstOrNull { it.label.equals(name.trim(), ignoreCase = true) || it.key == name } ?: return@mapNotNull null
            field.key to value
        }.toMap()
    }

    private fun phoneTaken(owner: Client) = ToolProblem(
        "phone_taken",
        "${owner.name} (${owner.number}) already has this phone${if (owner.archivedAt != null) " (archived — it can be restored in Clients)" else ""}. Use that client or ask for another number.",
    )

    companion object : ToolGroupSpec {
        const val GET_CLIENT = "get_client"
        const val CREATE_CLIENT = "create_client"
        const val UPDATE_CLIENT = "update_client"

        /** The client form's error codes, as the field they are about. */
        private val FIELD_NAMES = mapOf(
            "tax_id_required" to "a tax number (NIF)",
            "email_required" to "an email",
            "contact_person_required" to "a contact person",
            "address_required" to "an address",
            "postal_code_required" to "a postal code",
            "city_required" to "a city",
            "invalid_email" to "the email",
            "tax_id_too_long" to "the tax number",
            "notes_too_long" to "the notes",
            "address_too_long" to "the address",
            "postal_code_too_long" to "the postal code",
            "city_too_long" to "the city",
            "contact_person_too_long" to "the contact person",
        )

        private val CLIENT_FIELDS = buildJsonObject {
            put("name", prop("string"))
            put("phone", prop("string", "With the country code, e.g. +351 912 345 678"))
            put("email", prop("string"))
            put("tax_id", prop("string", "Tax number (NIF)"))
            put("address", prop("string"))
            put("postal_code", prop("string"))
            put("city", prop("string"))
            put("contact_person", prop("string", "Who to talk to, for a company"))
            put("notes", prop("string", "Staff-only notes"))
            put("custom_fields", buildJsonObject { put("type", "object"); put("description", "The company's own client fields, by label") })
        }

        override val definitions = listOf(
            tool(
                GET_CLIENT,
                "A client's full record: contact details, tax number, notes and the company's own fields, plus their invoices (outstanding, overdue), " +
                    "quotes, work not invoiced and next booking.",
                buildJsonObject { put("client_id", prop("string", "From search_clients (or the client's number, e.g. CLT-004)")) },
                listOf("client_id"),
            ),
            tool(
                CREATE_CLIENT,
                "Create a client. Search first so you don't duplicate one. The company may require more than name and phone.",
                CLIENT_FIELDS,
                listOf("name", "phone"),
            ),
            tool(
                UPDATE_CLIENT,
                "Change a client's details. Pass only the fields to change; an empty string clears an optional one.",
                buildJsonObject {
                    put("client_id", prop("string", "From search_clients"))
                    CLIENT_FIELDS.forEach { (key, value) -> put(key, value) }
                },
                listOf("client_id"),
            ),
        )
        override val moduleOf = mapOf(GET_CLIENT to DashboardModules.CLIENTS, CREATE_CLIENT to DashboardModules.CLIENTS, UPDATE_CLIENT to DashboardModules.CLIENTS)
        override val readOnly = setOf(GET_CLIENT)
    }
}
