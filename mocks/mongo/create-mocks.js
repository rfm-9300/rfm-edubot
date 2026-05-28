const dbName = process.env.MONGO_DATABASE || "wabot";
const target = db.getSiblingDB(dbName);
const now = new Date("2026-05-20T12:00:00.000Z");

const oid = (value) => ObjectId(value);
const cents = (eur) => Math.round(eur * 100);
const date = (value) => new Date(value);

const ids = {
  users: {
    rodrigo: oid("665f00000000000000000001"),
    maria: oid("665f00000000000000000002"),
    joao: oid("665f00000000000000000003"),
  },
  conversations: {
    rodrigo: oid("665f10000000000000000001"),
    maria: oid("665f10000000000000000002"),
    joao: oid("665f10000000000000000003"),
  },
  clients: {
    hillsong: oid("665f20000000000000000001"),
    martins: oid("665f20000000000000000002"),
    oliveira: oid("665f20000000000000000003"),
    costa: oid("665f20000000000000000004"),
  },
  quotes: {
    q1: oid("665f30000000000000000001"),
    q2: oid("665f30000000000000000002"),
    q3: oid("665f30000000000000000003"),
  },
  invoices: {
    i1: oid("665f40000000000000000001"),
    i2: oid("665f40000000000000000002"),
    i3: oid("665f40000000000000000003"),
  },
};

const line = (description, quantity, unit, unitPriceEur) => {
  const unitPriceCents = cents(unitPriceEur);
  return {
    description,
    quantity,
    unit,
    unitPriceCents,
    totalCents: Math.round(quantity * unitPriceCents),
  };
};

const standardItems = [
  { id: "srv-landing-page", type: "service", category: "Digital", description: "Website landing page", unit: "servico", defaultUnitPriceEur: 350 },
  { id: "srv-business-website", type: "service", category: "Digital", description: "Business website, 5 pages", unit: "servico", defaultUnitPriceEur: 750 },
  { id: "srv-ecommerce-setup", type: "service", category: "Digital", description: "E-commerce setup", unit: "servico", defaultUnitPriceEur: 1200 },
  { id: "srv-whatsapp-bot", type: "service", category: "Digital", description: "WhatsApp bot setup", unit: "servico", defaultUnitPriceEur: 600 },
  { id: "srv-ai-chatbot", type: "service", category: "Digital", description: "AI chatbot integration", unit: "servico", defaultUnitPriceEur: 900 },
  { id: "srv-booking-system", type: "service", category: "Digital", description: "Booking system", unit: "servico", defaultUnitPriceEur: 800 },
  { id: "srv-crm-setup", type: "service", category: "Digital", description: "CRM setup", unit: "servico", defaultUnitPriceEur: 700 },
  { id: "srv-social-automation", type: "service", category: "Digital", description: "Social media automation", unit: "servico", defaultUnitPriceEur: 450 },
  { id: "srv-seo-basic", type: "service", category: "Digital", description: "SEO basic setup", unit: "servico", defaultUnitPriceEur: 300 },
  { id: "srv-monthly-maintenance", type: "service", category: "Digital", description: "Monthly maintenance", unit: "mes", defaultUnitPriceEur: 150 },
  { id: "srv-lavagem-fachada", type: "service", category: "Fachadas", description: "Lavagem e preparacao de fachada", unit: "m2", defaultUnitPriceEur: 4.5 },
  { id: "srv-pintura-fachada", type: "service", category: "Fachadas", description: "Pintura exterior com duas demaos", unit: "m2", defaultUnitPriceEur: 8.5 },
  { id: "mat-tinta-acrilica", type: "material", category: "Fachadas", description: "Tinta acrilica exterior premium", unit: "l", defaultUnitPriceEur: 12 },
  { id: "srv-membrana-liquida", type: "service", category: "Coberturas", description: "Aplicacao de membrana liquida impermeabilizante", unit: "m2", defaultUnitPriceEur: 22 },
  { id: "mat-membrana-liquida", type: "material", category: "Coberturas", description: "Membrana liquida elastica", unit: "kg", defaultUnitPriceEur: 7.5 },
];

const users = [
  {
    _id: ids.users.rodrigo,
    waId: "351910000001",
    displayName: "Rodrigo Martins",
    locale: "pt_PT",
    status: "ACTIVE",
    createdAt: date("2026-05-01T09:00:00.000Z"),
    lastSeenAt: now,
    metadata: { mockSeed: "create-mocks", persona: "owner" },
  },
  {
    _id: ids.users.maria,
    waId: "351910000002",
    displayName: "Maria Silva",
    locale: "pt_PT",
    status: "ACTIVE",
    createdAt: date("2026-05-03T10:20:00.000Z"),
    lastSeenAt: date("2026-05-20T10:30:00.000Z"),
    metadata: { mockSeed: "create-mocks", segment: "residential" },
  },
  {
    _id: ids.users.joao,
    waId: "351910000003",
    displayName: "Joao Costa",
    locale: "pt_PT",
    status: "ACTIVE",
    createdAt: date("2026-05-05T14:10:00.000Z"),
    lastSeenAt: date("2026-05-19T17:45:00.000Z"),
    metadata: { mockSeed: "create-mocks", segment: "commercial" },
  },
];

const conversations = [
  {
    _id: ids.conversations.rodrigo,
    userId: ids.users.rodrigo,
    waId: "351910000001",
    state: "ACTIVE",
    summary: "Owner testing CRM quote, invoice, and WhatsApp bot flows.",
    summaryUpdatedAt: now,
    lastMessageAt: now,
    messageCount: 4,
    systemPromptVersion: "v1",
    createdAt: date("2026-05-01T09:00:00.000Z"),
  },
  {
    _id: ids.conversations.maria,
    userId: ids.users.maria,
    waId: "351910000002",
    state: "ACTIVE",
    summary: "Asked for an orcamento for digital services and maintenance.",
    summaryUpdatedAt: date("2026-05-20T10:30:00.000Z"),
    lastMessageAt: date("2026-05-20T10:30:00.000Z"),
    messageCount: 3,
    systemPromptVersion: "v1",
    createdAt: date("2026-05-03T10:20:00.000Z"),
  },
  {
    _id: ids.conversations.joao,
    userId: ids.users.joao,
    waId: "351910000003",
    state: "ACTIVE",
    summary: "Commercial client comparing facade and roof waterproofing work.",
    summaryUpdatedAt: date("2026-05-19T17:45:00.000Z"),
    lastMessageAt: date("2026-05-19T17:45:00.000Z"),
    messageCount: 3,
    systemPromptVersion: "v1",
    createdAt: date("2026-05-05T14:10:00.000Z"),
  },
];

const messages = [
  {
    _id: oid("665f50000000000000000001"),
    conversationId: ids.conversations.maria,
    waId: "351910000002",
    role: "USER",
    waMessageId: "mock-wa-maria-001",
    content: { type: "text", text: "Preciso de um orcamento para website, chatbot e manutencao mensal." },
    tokens: null,
    model: null,
    costUsd: 0,
    status: "RECEIVED",
    createdAt: date("2026-05-20T10:25:00.000Z"),
  },
  {
    _id: oid("665f50000000000000000002"),
    conversationId: ids.conversations.maria,
    waId: "351910000002",
    role: "ASSISTANT",
    waMessageId: null,
    content: { type: "text", text: "Claro. Posso preparar um orcamento com landing page, WhatsApp bot e manutencao mensal." },
    tokens: { prompt: 312, completion: 58 },
    model: "openai/gpt-4o-mini",
    costUsd: 0.0021,
    status: "DELIVERED",
    createdAt: date("2026-05-20T10:25:06.000Z"),
  },
  {
    _id: oid("665f50000000000000000003"),
    conversationId: ids.conversations.joao,
    waId: "351910000003",
    role: "USER",
    waMessageId: "mock-wa-joao-001",
    content: { type: "text", text: "Quero comparar pintura de fachada com impermeabilizacao da cobertura." },
    tokens: null,
    model: null,
    costUsd: 0,
    status: "RECEIVED",
    createdAt: date("2026-05-19T17:40:00.000Z"),
  },
  {
    _id: oid("665f50000000000000000004"),
    conversationId: ids.conversations.joao,
    waId: "351910000003",
    role: "ASSISTANT",
    waMessageId: null,
    content: { type: "text", text: "Tenho modelos para fachada e cobertura. Posso listar os itens por area, materiais e prazo." },
    tokens: { prompt: 280, completion: 51 },
    model: "openai/gpt-4o-mini",
    costUsd: 0.0019,
    status: "DELIVERED",
    createdAt: date("2026-05-19T17:40:05.000Z"),
  },
];

const quote1Items = [
  line("Website landing page", 1, "servico", 350),
  line("WhatsApp bot setup", 1, "servico", 600),
  line("Monthly maintenance", 3, "mes", 150),
];
const quote2Items = [
  line("Lavagem e preparacao de fachada", 180, "m2", 4.5),
  line("Pintura exterior com duas demaos", 180, "m2", 8.5),
  line("Tinta acrilica exterior premium", 40, "l", 12),
];
const quote3Items = [
  line("Aplicacao de membrana liquida impermeabilizante", 95, "m2", 22),
  line("Membrana liquida elastica", 180, "kg", 7.5),
];

const clients = [
  { _id: ids.clients.hillsong, number: "CLT-001", name: "Hillsong Portugal", phone: "+351910100001", address: "Rua das Flores 10, 1200-001 Lisboa", createdAt: date("2026-05-01T09:10:00.000Z"), updatedAt: now },
  { _id: ids.clients.martins, number: "CLT-002", name: "Martins Digital Lda", phone: "+351910100002", address: "Av. da Liberdade 45, 1250-140 Lisboa", createdAt: date("2026-05-02T11:30:00.000Z"), updatedAt: now },
  { _id: ids.clients.oliveira, number: "CLT-003", name: "Condominio Rua Oliveira", phone: "+351910100003", address: "Rua Oliveira 22, 4000-300 Porto", createdAt: date("2026-05-04T15:00:00.000Z"), updatedAt: now },
  { _id: ids.clients.costa, number: "CLT-004", name: "Costa & Filhos Comercio", phone: "+351910100004", address: "Estrada Nacional 8, 2400-100 Leiria", createdAt: date("2026-05-07T13:15:00.000Z"), updatedAt: now },
];

const quotes = [
  {
    _id: ids.quotes.q1,
    number: "ORC-001",
    clientId: ids.clients.martins,
    items: quote1Items,
    notes: "Mock quote based on 10 service price list request.",
    status: "PENDENTE",
    totalCents: quote1Items.reduce((sum, item) => sum + item.totalCents, 0),
    validUntil: "2026-06-20",
    pdfPath: null,
    createdAt: date("2026-05-20T10:45:00.000Z"),
    updatedAt: now,
  },
  {
    _id: ids.quotes.q2,
    number: "ORC-002",
    clientId: ids.clients.oliveira,
    items: quote2Items,
    notes: "Inclui preparacao, pintura e materiais principais.",
    status: "ACEITO",
    totalCents: quote2Items.reduce((sum, item) => sum + item.totalCents, 0),
    validUntil: "2026-06-05",
    pdfPath: null,
    createdAt: date("2026-05-12T09:30:00.000Z"),
    updatedAt: date("2026-05-15T14:00:00.000Z"),
  },
  {
    _id: ids.quotes.q3,
    number: "ORC-003",
    clientId: ids.clients.costa,
    items: quote3Items,
    notes: "Trabalhos de cobertura com garantia standard.",
    status: "PENDENTE",
    totalCents: quote3Items.reduce((sum, item) => sum + item.totalCents, 0),
    validUntil: "2026-06-15",
    pdfPath: null,
    createdAt: date("2026-05-18T16:20:00.000Z"),
    updatedAt: now,
  },
];

const invoices = [
  {
    _id: ids.invoices.i1,
    number: "FAT-001",
    clientId: ids.clients.oliveira,
    quoteId: ids.quotes.q2,
    items: quote2Items,
    status: "PAID",
    dueDate: "2026-05-31",
    paidAt: date("2026-05-18T12:00:00.000Z"),
    totalCents: quote2Items.reduce((sum, item) => sum + item.totalCents, 0),
    pdfPath: null,
    createdAt: date("2026-05-15T14:05:00.000Z"),
    updatedAt: date("2026-05-18T12:00:00.000Z"),
  },
  {
    _id: ids.invoices.i2,
    number: "FAT-002",
    clientId: ids.clients.martins,
    quoteId: ids.quotes.q1,
    items: quote1Items,
    status: "PENDING",
    dueDate: "2026-06-10",
    paidAt: null,
    totalCents: quote1Items.reduce((sum, item) => sum + item.totalCents, 0),
    pdfPath: null,
    createdAt: date("2026-05-20T11:00:00.000Z"),
    updatedAt: now,
  },
  {
    _id: ids.invoices.i3,
    number: "FAT-003",
    clientId: ids.clients.hillsong,
    quoteId: null,
    items: [line("CRM setup", 1, "servico", 700), line("AI chatbot integration", 1, "servico", 900)],
    status: "OVERDUE",
    dueDate: "2026-05-10",
    paidAt: null,
    totalCents: cents(1600),
    pdfPath: null,
    createdAt: date("2026-04-25T08:45:00.000Z"),
    updatedAt: date("2026-05-11T08:00:00.000Z"),
  },
];

const webhookEvents = [
  {
    eventId: "mock-event-maria-001",
    type: "message",
    rawPayload: JSON.stringify({ mock: true, waId: "351910000002", text: "Preciso de um orcamento" }),
    receivedAt: date("2026-05-20T10:25:00.000Z"),
    status: "processed",
    processedAt: date("2026-05-20T10:25:06.000Z"),
  },
  {
    eventId: "mock-event-joao-001",
    type: "message",
    rawPayload: JSON.stringify({ mock: true, waId: "351910000003", text: "Comparar fachada e cobertura" }),
    receivedAt: date("2026-05-19T17:40:00.000Z"),
    status: "processed",
    processedAt: date("2026-05-19T17:40:05.000Z"),
  },
];

function removeSeedConflicts() {
  target.users.deleteMany({ $or: [{ _id: { $in: users.map((item) => item._id) } }, { waId: { $in: users.map((item) => item.waId) } }, { "metadata.mockSeed": "create-mocks" }] });
  target.conversations.deleteMany({ $or: [{ _id: { $in: conversations.map((item) => item._id) } }, { waId: { $in: conversations.map((item) => item.waId) } }] });
  target.messages.deleteMany({ $or: [{ _id: { $in: messages.map((item) => item._id) } }, { waMessageId: { $in: messages.map((item) => item.waMessageId).filter(Boolean) } }] });
  target.getCollection("webhook_events").deleteMany({ eventId: { $in: webhookEvents.map((item) => item.eventId) } });
  target.getCollection("crm.clients").deleteMany({ $or: [{ _id: { $in: clients.map((item) => item._id) } }, { number: { $in: clients.map((item) => item.number) } }, { phone: { $in: clients.map((item) => item.phone) } }] });
  target.getCollection("crm.quotes").deleteMany({ $or: [{ _id: { $in: quotes.map((item) => item._id) } }, { number: { $in: quotes.map((item) => item.number) } }] });
  target.getCollection("crm.invoices").deleteMany({ $or: [{ _id: { $in: invoices.map((item) => item._id) } }, { number: { $in: invoices.map((item) => item.number) } }] });
  target.getCollection("crm.standard_items").deleteMany({ id: { $in: standardItems.map((item) => item.id) } });
  target.getCollection("crm.sequences").deleteMany({ name: { $in: ["client_number", "quote_number", "invoice_number"] } });
}

function insertMany(collectionName, docs) {
  if (docs.length === 0) return;
  target.getCollection(collectionName).insertMany(docs, { ordered: true });
}

removeSeedConflicts();
insertMany("users", users);
insertMany("conversations", conversations);
insertMany("messages", messages);
insertMany("webhook_events", webhookEvents);
insertMany("crm.clients", clients);
insertMany("crm.quotes", quotes);
insertMany("crm.invoices", invoices);
insertMany("crm.standard_items", standardItems);
insertMany("crm.sequences", [
  { name: "client_number", value: 4 },
  { name: "quote_number", value: 3 },
  { name: "invoice_number", value: 3 },
]);

const summary = {
  database: dbName,
  users: target.users.countDocuments({ _id: { $in: users.map((item) => item._id) } }),
  conversations: target.conversations.countDocuments({ _id: { $in: conversations.map((item) => item._id) } }),
  messages: target.messages.countDocuments({ _id: { $in: messages.map((item) => item._id) } }),
  webhook_events: target.getCollection("webhook_events").countDocuments({ eventId: { $in: webhookEvents.map((item) => item.eventId) } }),
  crm_clients: target.getCollection("crm.clients").countDocuments({ _id: { $in: clients.map((item) => item._id) } }),
  crm_quotes: target.getCollection("crm.quotes").countDocuments({ _id: { $in: quotes.map((item) => item._id) } }),
  crm_invoices: target.getCollection("crm.invoices").countDocuments({ _id: { $in: invoices.map((item) => item._id) } }),
  crm_standard_items: target.getCollection("crm.standard_items").countDocuments({ id: { $in: standardItems.map((item) => item.id) } }),
  crm_sequences: target.getCollection("crm.sequences").countDocuments({ name: { $in: ["client_number", "quote_number", "invoice_number"] } }),
};

printjson(summary);
