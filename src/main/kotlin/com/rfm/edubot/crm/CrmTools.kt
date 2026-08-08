package com.rfm.edubot.crm

import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.QuoteStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

class CrmTools(
    private val clients: ClientRepository,
    private val quotes: QuoteRepository,
    private val invoices: InvoiceRepository,
    private val standardItems: StandardItemRepository,
) {
    val definitions: List<ToolDefinition> = listOf(
        tool("search_clients", "Search clients by name or phone", obj("query" to "string"), listOf("query")),
        tool("list_service_templates", "List reusable construction service templates and default clauses", obj("query" to "string")),
        tool("list_standard_items", "List standard quote/invoice line items split by service or material", obj("query" to "string", "type" to "string")),
        tool("create_client", "Create a new client", obj("name" to "string", "phone" to "string", "address" to "string"), listOf("name", "phone")),
        tool("create_quote", "Create a quote for a client", createDocumentSchema(false), listOf("client_id", "items")),
        tool("update_quote", "Update an existing quote: replace items, change status (PENDENTE/SENT/ACEITO), update notes or valid_until. Pass only the fields to change.", updateQuoteSchema(), listOf("quote_id")),
        tool("list_quotes", "List quotes, optionally filtered by client_id or status", obj("client_id" to "string", "status" to "string")),
        tool("create_invoice", "Create an invoice for a client", createDocumentSchema(true), listOf("client_id", "items", "due_date")),
        tool("list_invoices", "List invoices, optionally filtered by client_id or status", obj("client_id" to "string", "status" to "string")),
        tool("mark_invoice_paid", "Mark an invoice as paid", obj("invoice_id" to "string"), listOf("invoice_id")),
        tool("sum_quotes_by_client", "Return total quote value grouped by client, sorted by total descending. Use this for 'soma dos orçamentos por cliente', 'total por cliente', ranking questions.", obj()),
        tool("sum_invoices_by_client", "Return total invoice value grouped by client (with paid/pending split), sorted by total descending.", obj()),
    )

    /** Tools that only read data — safe for the persona test playground (never mutate the tenant's records). */
    val readOnlyDefinitions: List<ToolDefinition> = definitions.filter { it.name in READ_ONLY_TOOL_NAMES }

    suspend fun execute(call: ToolCall): JsonObject = when (call.name) {
        "search_clients" -> searchClients(call.arguments)
        "list_service_templates" -> listServiceTemplates(call.arguments)
        "list_standard_items" -> listStandardItems(call.arguments)
        "create_client" -> createClient(call.arguments)
        "create_quote" -> createQuote(call.arguments)
        "update_quote" -> updateQuote(call.arguments)
        "list_quotes" -> listQuotes(call.arguments)
        "create_invoice" -> createInvoice(call.arguments)
        "list_invoices" -> listInvoices(call.arguments)
        "mark_invoice_paid" -> markInvoicePaid(call.arguments)
        "sum_quotes_by_client" -> sumQuotesByClient()
        "sum_invoices_by_client" -> sumInvoicesByClient()
        else -> buildJsonObject { put("error", "Unknown tool: ${call.name}") }
    }

    private suspend fun searchClients(args: JsonObject): JsonObject {
        val result = clients.search(args.string("query"))
        return buildJsonObject {
            put("clients", buildJsonArray {
                result.forEach { client -> add(clientJson(client.id.toHexString(), client.number, client.name, client.phone, client.address)) }
            })
        }
    }

    private fun listServiceTemplates(args: JsonObject): JsonObject {
        val templates = ServiceTemplates.search(args.optionalString("query"))
        return buildJsonObject {
            put("templates", buildJsonArray {
                templates.forEach { template ->
                    add(buildJsonObject {
                        put("id", template.id)
                        put("name", template.name)
                        put("category", template.category)
                        put("default_duration", template.defaultDuration)
                        put("default_warranty", template.defaultWarranty)
                        put("payment_terms", buildJsonArray { template.paymentTerms.forEach { add(JsonPrimitive(it)) } })
                        put("included", buildJsonArray { template.included.forEach { add(JsonPrimitive(it)) } })
                        put("excluded", buildJsonArray { template.excluded.forEach { add(JsonPrimitive(it)) } })
                        put("client_responsibilities", buildJsonArray { template.clientResponsibilities.forEach { add(JsonPrimitive(it)) } })
                        put("work_items", buildJsonArray { template.workItems.forEach { add(JsonPrimitive(it)) } })
                        put("notes", buildJsonArray { template.notes.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        }
    }

    private suspend fun listStandardItems(args: JsonObject): JsonObject {
        val items = standardItems.search(args.optionalString("query"), args.optionalString("type"))
        return buildJsonObject {
            put("items", buildJsonArray {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id)
                        put("type", item.type)
                        put("category", item.category)
                        put("description", item.description)
                        put("unit", item.unit)
                        put("default_unit_price_eur", item.defaultUnitPriceEur)
                    })
                }
            })
        }
    }

    private suspend fun createClient(args: JsonObject): JsonObject {
        val client = clients.create(args.string("name"), args.string("phone"), args.optionalString("address"))
        return buildJsonObject { put("client", clientJson(client.id.toHexString(), client.number, client.name, client.phone, client.address)) }
    }

    private suspend fun createQuote(args: JsonObject): JsonObject {
        val quote = quotes.create(
            clientId = resolveClientId(args.string("client_id")),
            items = args.items(),
            notes = args.optionalString("notes"),
            validUntil = args.optionalString("valid_until")?.let { kotlinx.datetime.LocalDate.parse(it) },
        )
        return buildJsonObject {
            put("created", true)
            put("type", "quote")
            put("id", quote.id.toHexString())
            put("number", quote.number)
            put("total_eur", quote.totalCents / 100.0)
        }
    }

    private suspend fun updateQuote(args: JsonObject): JsonObject {
        val id = resolveQuoteId(args.string("quote_id"))
        val items = args["items"]?.jsonArray?.map { item ->
            val obj = item.jsonObject
            lineItem(
                description = obj.string("description"),
                quantity = obj.optionalDouble("quantity") ?: 1.0,
                unitPriceEur = obj.getValue("price_eur").jsonPrimitive.double,
            )
        }
        val quote = quotes.update(
            id = id,
            items = items,
            notes = args.optionalString("notes"),
            validUntil = args.optionalString("valid_until")?.let { kotlinx.datetime.LocalDate.parse(it) },
            status = args.optionalString("status")?.uppercase()?.let { QuoteStatus.valueOf(it) },
        ) ?: return buildJsonObject { put("error", "Quote not found") }
        return buildJsonObject {
            put("updated", true)
            put("type", "quote")
            put("id", quote.id.toHexString())
            put("number", quote.number)
            put("status", quote.status.name)
            put("total_eur", quote.totalCents / 100.0)
        }
    }

    private suspend fun listQuotes(args: JsonObject): JsonObject {
        val result = quotes.list(
            clientId = args.optionalString("client_id")?.let { resolveClientId(it) },
            status = args.optionalString("status")?.uppercase()?.let { QuoteStatus.valueOf(it) },
        )
        return buildJsonObject {
            put("quotes", buildJsonArray {
                result.forEach { quote ->
                    add(buildJsonObject {
                        put("id", quote.id.toHexString())
                        put("number", quote.number)
                        put("client_id", quote.clientId.toHexString())
                        put("status", quote.status.name)
                        put("total_eur", quote.totalCents / 100.0)
                    })
                }
            })
        }
    }

    private suspend fun createInvoice(args: JsonObject): JsonObject {
        val invoice = invoices.create(
            clientId = resolveClientId(args.string("client_id")),
            quoteId = args.optionalString("quote_id")?.let { ObjectId(it) },
            items = args.items(),
            dueDate = kotlinx.datetime.LocalDate.parse(args.string("due_date")),
        )
        return buildJsonObject {
            put("created", true)
            put("type", "invoice")
            put("id", invoice.id.toHexString())
            put("number", invoice.number)
            put("total_eur", invoice.totalCents / 100.0)
        }
    }

    private suspend fun listInvoices(args: JsonObject): JsonObject {
        val result = invoices.list(
            clientId = args.optionalString("client_id")?.let { resolveClientId(it) },
            status = args.optionalString("status")?.uppercase()?.let { InvoiceStatus.valueOf(it) },
        )
        return buildJsonObject {
            put("invoices", buildJsonArray {
                result.forEach { invoice ->
                    add(buildJsonObject {
                        put("id", invoice.id.toHexString())
                        put("number", invoice.number)
                        put("client_id", invoice.clientId.toHexString())
                        put("status", invoice.status.name)
                        put("due_date", invoice.dueDate.toString())
                        put("total_eur", invoice.totalCents / 100.0)
                    })
                }
            })
        }
    }

    private suspend fun markInvoicePaid(args: JsonObject): JsonObject {
        val invoice = invoices.markPaid(ObjectId(args.string("invoice_id")))
            ?: return buildJsonObject { put("error", "Invoice not found") }
        return buildJsonObject {
            put("id", invoice.id.toHexString())
            put("number", invoice.number)
            put("status", invoice.status.name)
        }
    }

    private suspend fun sumQuotesByClient(): JsonObject {
        val rows = quotes.sumByClient()
        return buildJsonObject {
            put("totals", buildJsonArray {
                rows.forEach { row ->
                    add(buildJsonObject {
                        put("client_name", row.clientName)
                        put("client_number", row.clientNumber)
                        put("quote_count", row.quoteCount)
                        put("total_eur", row.totalCents / 100.0)
                    })
                }
            })
        }
    }

    private suspend fun sumInvoicesByClient(): JsonObject {
        val rows = invoices.sumByClient()
        return buildJsonObject {
            put("totals", buildJsonArray {
                rows.forEach { row ->
                    add(buildJsonObject {
                        put("client_name", row.clientName)
                        put("client_number", row.clientNumber)
                        put("invoice_count", row.invoiceCount)
                        put("total_eur", row.totalCents / 100.0)
                        put("paid_eur", row.paidCents / 100.0)
                        put("pending_eur", (row.totalCents - row.paidCents) / 100.0)
                    })
                }
            })
        }
    }

    private fun JsonObject.items() = getValue("items").jsonArray.map { item ->
        val obj = item.jsonObject
        lineItem(
            description = obj.string("description"),
            quantity = obj.optionalDouble("quantity") ?: 1.0,
            unitPriceEur = obj.getValue("price_eur").jsonPrimitive.double,
        )
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.optionalDouble(name: String): Double? = get(name)?.jsonPrimitive?.content?.toDoubleOrNull()

    private fun clientJson(id: String, number: String, name: String, phone: String, address: String?) = buildJsonObject {
        put("id", id)
        put("number", number)
        put("name", name)
        put("phone", phone)
        address?.let { put("address", it) }
    }

    private suspend fun resolveQuoteId(value: String): ObjectId {
        val trimmed = value.trim()
        if (ObjectId.isValid(trimmed)) return ObjectId(trimmed)
        val quote = quotes.findByNumber(trimmed)
        return quote?.id ?: throw IllegalArgumentException("Quote not found for reference: $trimmed")
    }

    private suspend fun resolveClientId(value: String): ObjectId {
        val trimmed = value.trim()
        if (ObjectId.isValid(trimmed)) return ObjectId(trimmed)

        val matches = clients.search(trimmed)
        val exact = matches.firstOrNull {
            it.number.equals(trimmed, ignoreCase = true) ||
                it.phone == trimmed ||
                it.name.equals(trimmed, ignoreCase = true) ||
                it.address?.equals(trimmed, ignoreCase = true) == true
        }
        val client = exact ?: matches.singleOrNull()
        return client?.id ?: throw IllegalArgumentException("Client not found for reference: $trimmed")
    }

    private fun tool(name: String, description: String, properties: JsonObject, required: List<String> = emptyList()) = ToolDefinition(
        name = name,
        description = description,
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", properties)
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        },
    )

    private fun obj(vararg fields: Pair<String, String>) = buildJsonObject {
        fields.forEach { (name, type) -> put(name, buildJsonObject { put("type", type) }) }
    }

    private fun updateQuoteSchema() = buildJsonObject {
        put("quote_id", buildJsonObject { put("type", "string"); put("description", "Quote ObjectId hex or number e.g. ORC-001") })
        put("status", buildJsonObject { put("type", "string"); put("description", "PENDENTE, SENT, or ACEITO") })
        put("notes", buildJsonObject { put("type", "string") })
        put("valid_until", buildJsonObject { put("type", "string"); put("description", "YYYY-MM-DD") })
        put("items", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", obj("description" to "string", "quantity" to "number", "price_eur" to "number"))
                put("required", buildJsonArray { add(JsonPrimitive("description")); add(JsonPrimitive("price_eur")) })
            })
        })
    }

    private fun createDocumentSchema(invoice: Boolean) = buildJsonObject {
        put("client_id", buildJsonObject { put("type", "string") })
        put("quote_id", buildJsonObject { put("type", "string") })
        put("notes", buildJsonObject { put("type", "string") })
        put("valid_until", buildJsonObject { put("type", "string"); put("description", "YYYY-MM-DD") })
        put("due_date", buildJsonObject { put("type", "string"); put("description", "YYYY-MM-DD") })
        put("items", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", obj("description" to "string", "quantity" to "number", "price_eur" to "number"))
                put("required", buildJsonArray {
                    add(JsonPrimitive("description")); add(JsonPrimitive("price_eur"))
                })
            })
        })
        if (invoice) put("due_date_required", JsonPrimitive(true))
    }

    companion object {
        /** Names of tools that only read data — used to build [readOnlyDefinitions] and to guard execution in the test playground. */
        val READ_ONLY_TOOL_NAMES = setOf(
            "search_clients",
            "list_service_templates",
            "list_standard_items",
            "list_quotes",
            "list_invoices",
            "sum_quotes_by_client",
            "sum_invoices_by_client",
        )
    }
}
