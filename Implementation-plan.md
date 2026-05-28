╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
 CRM System for Construction Firm — WhatsApp Bot Extension

 Context

 The bot currently works as a generic WhatsApp AI assistant: message in → LLM → reply out. The goal is to extend it into an internal operations tool
 for a small construction company, allowing employees to manage clients, quotes (orçamentos), and invoices (faturas) entirely through WhatsApp
 conversation. The LLM will guide employees through data collection using native tool/function calling, then store results in MongoDB and generate
 PDFs. A minimal admin panel (HTML/JS served by Ktor) will show the records and allow PDF downloads.

 ---
 Approach: LLM Tool Calling Loop

 The LLM is given a set of CRM tools (JSON Schema). When the employee says "vamos fazer um orçamento", the LLM naturally guides the conversation,
 collecting required fields, then calls the appropriate tool (e.g., create_quote). The pipeline executes the tool, feeds the result back to the LLM,
 which responds with a final human message. Loop capped at 5 iterations.

 ---
 New Packages & Files

 src/main/kotlin/com/rfm/edubot/
 ├── crm/
 │   ├── model/
 │   │   └── CrmModels.kt         ← Client, Quote, Invoice, LineItem, enums
 │   ├── CrmRepositories.kt       ← ClientRepository, QuoteRepository, InvoiceRepository
 │   ├── CrmTools.kt              ← Tool definitions (JSON Schema) + ToolExecutor
 │   └── PdfGenerator.kt          ← Apache PDFBox — generates PDF bytes
 ├── admin/
 │   └── AdminRoutes.kt           ← REST API: /admin/clients, /admin/quotes, /admin/invoices
 src/main/resources/
 └── admin/
     ├── index.html               ← SPA shell
     ├── app.js                   ← Vanilla JS, no framework
     └── style.css

 Modified Files

 ┌──────────────────────────────┬────────────────────────────────────────────────────────────────┐
 │             File             │                             Change                             │
 ├──────────────────────────────┼────────────────────────────────────────────────────────────────┤
 │ ai/AiClient.kt               │ Add tools param; return sealed AiResponse (Text or ToolCall)   │
 ├──────────────────────────────┼────────────────────────────────────────────────────────────────┤
 │ ai/SystemPrompts.kt          │ New CRM_V1 prompt describing available tools and CRM context   │
 ├──────────────────────────────┼────────────────────────────────────────────────────────────────┤
 │ messaging/MessagePipeline.kt │ Tool execution loop (up to 5 iterations)                       │
 ├──────────────────────────────┼────────────────────────────────────────────────────────────────┤
 │ persistence/MongoModule.kt   │ Register crm.clients, crm.quotes, crm.invoices, crm.sequences  │
 ├──────────────────────────────┼────────────────────────────────────────────────────────────────┤
 │ config/AppConfig.kt          │ Add pdfStoragePath config key                                  │
 ├──────────────────────────────┼────────────────────────────────────────────────────────────────┤
 │ whatsapp/WhatsAppClient.kt   │ Add uploadMedia() + sendDocument() for PDF delivery            │
 ├──────────────────────────────┼────────────────────────────────────────────────────────────────┤
 │ Application.kt               │ Register adminRoutes, serve static files from resources/admin/ │
 ├──────────────────────────────┼────────────────────────────────────────────────────────────────┤
 │ build.gradle.kts             │ Add org.apache.pdfbox:pdfbox:3.0.x dependency                  │
 └──────────────────────────────┴────────────────────────────────────────────────────────────────┘

 ---
 Domain Models (crm/model/CrmModels.kt)

 data class Client(
     @BsonId val id: ObjectId,
     val name: String,
     val phone: String,
     val createdAt: Instant,
     val updatedAt: Instant
 )

 data class LineItem(
     val description: String,
     val quantity: Double,
     val unit: String,           // "m²", "sacos", "horas", etc.
     val unitPriceCents: Long,
     val totalCents: Long
 )

 enum class QuoteStatus { DRAFT, SENT, APPROVED, REJECTED }
 enum class InvoiceStatus { PENDING, PAID, OVERDUE, CANCELLED }

 data class Quote(
     @BsonId val id: ObjectId,
     val number: String,         // "ORÇ-001"
     val clientId: ObjectId,
     val items: List<LineItem>,
     val notes: String?,
     val status: QuoteStatus,
     val totalCents: Long,
     val validUntil: LocalDate?,
     val pdfPath: String?,       // local file path
     val createdAt: Instant,
     val updatedAt: Instant
 )

 data class Invoice(
     @BsonId val id: ObjectId,
     val number: String,         // "FAT-001"
     val clientId: ObjectId,
     val quoteId: ObjectId?,
     val items: List<LineItem>,
     val status: InvoiceStatus,
     val dueDate: LocalDate,
     val paidAt: Instant?,
     val totalCents: Long,
     val pdfPath: String?,
     val createdAt: Instant,
     val updatedAt: Instant
 )

 ---
 CRM Tools (crm/CrmTools.kt)

 Seven tools exposed to the LLM via OpenRouter function-calling:

 ┌───────────────────┬──────────────────────────────────────────────────────────────────────────────────┬────────────────────────────────────────┐
 │       Tool        │                                    Arguments                                     │                Returns                 │
 ├───────────────────┼──────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────┤
 │ search_clients    │ query: String                                                                    │ List of matching clients (id, name,    │
 │                   │                                                                                  │ phone)                                 │
 ├───────────────────┼──────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────┤
 │ create_client     │ name, phone                                                                      │ Created client (with id)               │
 ├───────────────────┼──────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────┤
 │ create_quote      │ client_id, items:[{description,quantity,unit,unit_price_brl}], notes?,           │ Quote summary + number + total         │
 │                   │ valid_until?                                                                     │                                        │
 ├───────────────────┼──────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────┤
 │ list_quotes       │ client_id?, status?                                                              │ List of quotes with totals             │
 ├───────────────────┼──────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────┤
 │ create_invoice    │ client_id, items, due_date, quote_id?                                            │ Invoice summary + number               │
 ├───────────────────┼──────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────┤
 │ list_invoices     │ client_id?, status?                                                              │ List of invoices with status           │
 ├───────────────────┼──────────────────────────────────────────────────────────────────────────────────┼────────────────────────────────────────┤
 │ mark_invoice_paid │ invoice_id                                                                       │ Updated invoice                        │
 └───────────────────┴──────────────────────────────────────────────────────────────────────────────────┴────────────────────────────────────────┘

 ToolExecutor is a class injected into MessagePipeline that maps tool names → suspend functions, executes them, and returns a JsonObject result.

 ---
 AiClient Changes (ai/AiClient.kt)

 sealed class AiResponse {
     data class Text(val content: String, val usage: UsageInfo?)
     data class ToolUse(val calls: List<ToolCall>, val usage: UsageInfo?)
 }

 data class ToolCall(val id: String, val name: String, val arguments: JsonObject)
 data class ToolDefinition(val name: String, val description: String, val parameters: JsonObject)

 // complete() signature change:
 suspend fun complete(
     messages: List<ChatMessage>,
     tools: List<ToolDefinition> = emptyList()
 ): AiResponse

 OpenRouter already returns tool_calls in the choices[0].message when the model decides to call a tool. Parse finish_reason == "tool_calls" to
 discriminate.

 ---
 MessagePipeline Tool Loop (messaging/MessagePipeline.kt)

 buildContext() → messages list
 loop (max 5 iterations):
     aiClient.complete(messages, crmTools)
     if AiResponse.Text → sendWhatsApp(text), break
     if AiResponse.ToolUse:
         for each call → toolExecutor.execute(call) → result: String
         append assistant message (tool_calls) + tool result messages
         continue loop

 After the loop, PDF generation (if a quote/invoice was created) and WhatsApp document send happen:
 1. PdfGenerator.generateQuote(quote, client) → ByteArray
 2. Save to pdfStoragePath/quotes/ORÇ-001.pdf
 3. WhatsAppClient.uploadMedia(bytes, "application/pdf") → mediaId
 4. WhatsAppClient.sendDocument(waId, mediaId, "Orçamento ORÇ-001.pdf")

 ---
 PDF Generation (crm/PdfGenerator.kt)

 Uses Apache PDFBox 3.x (pure Java, no external processes):
 - generateQuote(quote: Quote, client: Client): ByteArray
 - generateInvoice(invoice: Invoice, client: Client): ByteArray

 Layout: header (company name placeholder), client name/phone, line items table, total, notes, footer. Plain but readable.

 ---
 WhatsApp Media (whatsapp/WhatsAppClient.kt)

 Two new methods:
 suspend fun uploadMedia(bytes: ByteArray, mimeType: String): String  // returns mediaId
 suspend fun sendDocument(to: String, mediaId: String, filename: String)

 Upload: POST /{phoneNumberId}/media (multipart/form-data).
 Send: same messages endpoint with type: "document".

 ---
 Admin Panel

 REST API (admin/AdminRoutes.kt) — mounted at /admin/api/:
 - GET /clients + GET /clients/{id}
 - GET /quotes?clientId=&status= + GET /quotes/{id} + GET /quotes/{id}/pdf
 - GET /invoices?clientId=&status= + GET /invoices/{id} + GET /invoices/{id}/pdf
 - PATCH /invoices/{id}/paid

 Frontend — static files in src/main/resources/admin/:
 - Single-page app with 3 tabs: Clientes, Orçamentos, Faturas
 - Vanilla JS fetch calls to the REST API
 - Table views with client name, total, status, PDF download button
 - No authentication (internal network tool — can add basic auth later)

 ---
 MongoDB Collections & Indexes

 crm.clients      — unique(phone), text_index(name)
 crm.quotes       — index(clientId), index(status), unique(number)
 crm.invoices     — index(clientId), index(status), index(dueDate), unique(number)
 crm.sequences    — {name: "quote_number", value: 1}, {name: "invoice_number", value: 1}

 Sequence increment uses findOneAndUpdate with $inc + returnDocument: AFTER for atomic numbering.

 ---
 Updated System Prompt (ai/SystemPrompts.kt)

 New CRM_V1 prompt (replaces V1 when CRM tools are passed):

 Você é um assistente interno de operações de uma pequena construtora.
 Você pode ajudar com: orçamentos, clientes e faturas.

 Ferramentas disponíveis: [list them]
 Sempre confirme os dados com o usuário antes de criar registros.
 Para itens de orçamento, pergunte: descrição, quantidade, unidade e preço unitário em R$.
 Formate valores monetários como R$ X.XXX,XX.
 Idioma: português brasileiro.

 ---
 Implementation Order

 1. build.gradle.kts — add PDFBox dependency
 2. crm/model/CrmModels.kt — domain models
 3. crm/CrmRepositories.kt — repositories + sequence counter
 4. persistence/MongoModule.kt — register new collections + indexes
 5. crm/PdfGenerator.kt — PDF generation
 6. whatsapp/WhatsAppClient.kt — add uploadMedia + sendDocument
 7. ai/AiClient.kt — tool calling support (AiResponse sealed class)
 8. crm/CrmTools.kt — tool definitions + ToolExecutor
 9. ai/SystemPrompts.kt — add CRM_V1
 10. messaging/MessagePipeline.kt — tool execution loop
 11. admin/AdminRoutes.kt — REST endpoints
 12. src/main/resources/admin/ — HTML/JS/CSS admin panel
 13. Application.kt — wire everything together
 14. config/AppConfig.kt — add pdfStoragePath

 ---
 Verification

 1. ./gradlew build — compile checks
 2. ./gradlew test — run existing tests
 3. Start stack locally (docker compose up -d mongo && ./gradlew run)
 4. Send "vamos fazer um orçamento" via WhatsApp test number
 5. Walk through the conversation, receive a PDF via WhatsApp
 6. Open http://localhost:8080/admin/ — verify quote appears in the table
 7. Download PDF from admin panel
 8. Mark an invoice as paid via admin PATCH endpoint