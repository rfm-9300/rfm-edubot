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
  suppliers: {
    tintas: oid("665f80000000000000000001"),
    andaimes: oid("665f80000000000000000002"),
  },
  employees: {
    ana: oid("665f90000000000000000001"),
    rui: oid("665f90000000000000000002"),
  },
  payments: {
    p1: oid("665f90000000000000000001"),
    p2: oid("665f90000000000000000002"),
    p3: oid("665f90000000000000000003"),
  },
  bookingServices: {
    consult: oid("665f60000000000000000001"),
    visit: oid("665f60000000000000000002"),
  },
  bookings: {
    b1: oid("665f70000000000000000001"),
    b2: oid("665f70000000000000000002"),
  },
  agents: {
    overdue: oid("665fa0000000000000000001"),
    quotes: oid("665fa0000000000000000002"),
    cash: oid("665fa0000000000000000003"),
    leads: oid("665fa0000000000000000004"),
    bills: oid("665fa0000000000000000005"),
  },
  agentRuns: {
    reminder: oid("665fa1000000000000000001"),
    waiting: oid("665fa1000000000000000002"),
    quoteAccepted: oid("665fa1000000000000000003"),
    requestFailed: oid("665fa1000000000000000004"),
    lead: oid("665fa1000000000000000005"),
    briefing: oid("665fa1000000000000000006"),
  },
  agentApprovals: {
    pending: oid("665fa2000000000000000001"),
    approved: oid("665fa2000000000000000002"),
  },
  agentTasks: {
    lead: oid("665fa3000000000000000001"),
    martins: oid("665fa3000000000000000002"),
    call: oid("665fa3000000000000000003"),
  },
  notifications: {
    approval: oid("665fa4000000000000000001"),
    martinsTask: oid("665fa4000000000000000002"),
    leadTask: oid("665fa4000000000000000003"),
    failed: oid("665fa4000000000000000004"),
    reconnect: oid("665fa4000000000000000005"),
    briefing: oid("665fa4000000000000000006"),
  },
  gmail: oid("665fa5000000000000000001"),
  emails: {
    quoteSent: oid("665fa6000000000000000001"),
    quoteReply: oid("665fa6000000000000000002"),
    lead: oid("665fa6000000000000000003"),
    supplierBill: oid("665fa6000000000000000004"),
    request: oid("665fa6000000000000000005"),
  },
  // Referenced by runs as the events that woke them; the events themselves aren't seeded.
  events: {
    quoteSent: oid("665fa7000000000000000001"),
    lead: oid("665fa7000000000000000002"),
    request: oid("665fa7000000000000000003"),
  },
  employeeLogins: {
    ana: oid("665fb0000000000000000001"),
  },
  serviceSubmissions: {
    facade: oid("665fb1000000000000000001"),
    wash: oid("665fb1000000000000000002"),
    interior: oid("665fb1000000000000000003"),
    roof: oid("665fb1000000000000000004"),
  },
  clientServices: {
    interior: oid("665fb2000000000000000001"),
  },
  submittedNotification: oid("665fb3000000000000000001"),
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

// An item without its own description stores its title there, as the app does.
const standardItems = [
  { id: "srv-landing-page", code: "SRV-001", type: "service", category: "Digital", title: "Website landing page", description: "Website landing page", unit: "servico", defaultUnitPriceEur: 350 },
  { id: "srv-business-website", code: "SRV-002", type: "service", category: "Digital", title: "Business website", description: "5 pages, contact form and basic SEO", unit: "servico", defaultUnitPriceEur: 750 },
  { id: "srv-ecommerce-setup", code: "SRV-003", type: "service", category: "Digital", title: "E-commerce setup", description: "E-commerce setup", unit: "servico", defaultUnitPriceEur: 1200 },
  { id: "srv-whatsapp-bot", code: "SRV-004", type: "service", category: "Digital", title: "WhatsApp bot setup", description: "WhatsApp bot setup", unit: "servico", defaultUnitPriceEur: 600 },
  { id: "srv-ai-chatbot", code: "SRV-005", type: "service", category: "Digital", title: "AI chatbot integration", description: "AI chatbot integration", unit: "servico", defaultUnitPriceEur: 900 },
  { id: "srv-booking-system", code: "SRV-006", type: "service", category: "Digital", title: "Booking system", description: "Booking system", unit: "servico", defaultUnitPriceEur: 800 },
  { id: "srv-crm-setup", code: "SRV-007", type: "service", category: "Digital", title: "CRM setup", description: "CRM setup", unit: "servico", defaultUnitPriceEur: 700 },
  { id: "srv-social-automation", code: "SRV-008", type: "service", category: "Digital", title: "Social media automation", description: "Social media automation", unit: "servico", defaultUnitPriceEur: 450 },
  { id: "srv-seo-basic", code: "SRV-009", type: "service", category: "Digital", title: "SEO basic setup", description: "SEO basic setup", unit: "servico", defaultUnitPriceEur: 300 },
  { id: "srv-monthly-maintenance", code: "SRV-010", type: "service", category: "Digital", title: "Monthly maintenance", description: "Updates, backups and small fixes", unit: "mes", defaultUnitPriceEur: 150 },
  { id: "srv-lavagem-fachada", code: "SRV-011", type: "service", category: "Fachadas", title: "Lavagem de fachada", description: "Lavagem e preparacao de fachada", unit: "m2", defaultUnitPriceEur: 4.5 },
  { id: "srv-pintura-fachada", code: "SRV-012", type: "service", category: "Fachadas", title: "Pintura de fachada", description: "Pintura exterior com duas demaos", unit: "m2", defaultUnitPriceEur: 8.5 },
  { id: "mat-tinta-acrilica", code: "MAT-001", type: "material", category: "Fachadas", title: "Tinta acrilica exterior", description: "Tinta acrilica exterior premium", unit: "l", defaultUnitPriceEur: 12 },
  { id: "srv-membrana-liquida", code: "SRV-013", type: "service", category: "Coberturas", title: "Impermeabilizacao com membrana liquida", description: "Aplicacao de membrana liquida impermeabilizante", unit: "m2", defaultUnitPriceEur: 22 },
  { id: "mat-membrana-liquida", code: "MAT-002", type: "material", category: "Coberturas", title: "Membrana liquida elastica", description: "Membrana liquida elastica", unit: "kg", defaultUnitPriceEur: 7.5 },
  // Bookable services: Bookings offers catalog services that have a duration and the bookable flag.
  { id: "srv-consultation", code: "SRV-014", type: "service", category: "Marcações", title: "Consultation", description: "Consultation", unit: "sessão", defaultUnitPriceEur: 60, durationMinutes: 60, bookable: true },
  { id: "srv-site-visit", code: "SRV-015", type: "service", category: "Marcações", title: "Site visit", description: "Site visit", unit: "visita", defaultUnitPriceEur: 45, durationMinutes: 90, bookable: true },
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
  { _id: ids.clients.hillsong, number: "CLT-001", name: "Hillsong Portugal", phone: "+351910100001", address: "Rua das Flores 10, 1200-001 Lisboa", email: "geral@hillsong.pt", taxId: "501234567", notes: "Contact the office manager before 17:00. Invoices go to the finance team.", createdAt: date("2026-05-01T09:10:00.000Z"), updatedAt: now },
  { _id: ids.clients.martins, number: "CLT-002", name: "Martins Digital Lda", phone: "+351910100002", address: "Av. da Liberdade 45, 1250-140 Lisboa", email: "ola@martinsdigital.pt", taxId: "514567890", createdAt: date("2026-05-02T11:30:00.000Z"), updatedAt: now },
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

const suppliers = [
  { _id: ids.suppliers.tintas, number: "FOR-001", name: "Tintas Norte, Lda.", phone: "+351220100001", address: "Zona Industrial, Porto", type: "Materiais", createdAt: date("2026-05-03T09:00:00.000Z"), updatedAt: now },
  { _id: ids.suppliers.andaimes, number: "FOR-002", name: "Andaimes & Cia", phone: "+351220100002", address: "Rua do Ferro 8, 2400-100 Leiria", type: "Equipamento", createdAt: date("2026-05-08T11:00:00.000Z"), updatedAt: now },
];

const employees = [
  { _id: ids.employees.ana, number: "COL-001", name: "Ana Costa", phone: "+351910200001", role: "Pintora", createdAt: date("2026-05-04T09:00:00.000Z"), updatedAt: now },
  { _id: ids.employees.rui, number: "COL-002", name: "Rui Mendes", phone: "+351910200002", role: "Eletricista", createdAt: date("2026-05-12T10:30:00.000Z"), updatedAt: now },
];

const payment1Items = [line("Tinta acrilica exterior premium", 40, "l", 12)];
const payment2Items = [line("Aluguer de andaimes · 2 semanas", 1, "servico", 280)];
const payment3Items = [line("Membrana liquida elastica", 80, "kg", 7.5)];

const payments = [
  {
    _id: ids.payments.p1,
    number: "PAG-001",
    supplierId: ids.suppliers.tintas,
    items: payment1Items,
    notes: "Encomenda para a fachada Oliveira.",
    status: "PAID",
    dueDate: "2026-05-20",
    paidAt: date("2026-05-18T10:00:00.000Z"),
    totalCents: payment1Items.reduce((sum, item) => sum + item.totalCents, 0),
    createdAt: date("2026-05-12T09:00:00.000Z"),
    updatedAt: date("2026-05-18T10:00:00.000Z"),
  },
  {
    _id: ids.payments.p2,
    number: "PAG-002",
    supplierId: ids.suppliers.andaimes,
    items: payment2Items,
    notes: null,
    status: "PENDING",
    dueDate: "2026-06-05",
    paidAt: null,
    totalCents: payment2Items.reduce((sum, item) => sum + item.totalCents, 0),
    createdAt: date("2026-05-19T14:00:00.000Z"),
    updatedAt: now,
  },
  {
    _id: ids.payments.p3,
    number: "PAG-003",
    supplierId: ids.suppliers.tintas,
    items: payment3Items,
    notes: "Cobertura Costa.",
    status: "OVERDUE",
    dueDate: "2026-05-10",
    paidAt: null,
    totalCents: payment3Items.reduce((sum, item) => sum + item.totalCents, 0),
    createdAt: date("2026-04-28T08:00:00.000Z"),
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

const seedTenant = target.tenants.findOne({}) || null;
const seedTenantId = seedTenant ? seedTenant._id : null;
if (seedTenantId) {
  suppliers.forEach((item) => { item.tenantId = seedTenantId; });
  employees.forEach((item) => { item.tenantId = seedTenantId; });
  payments.forEach((item) => { item.tenantId = seedTenantId; });
  standardItems.forEach((item) => { item.tenantId = seedTenantId; });
}
const sequenceNames = ["client_number", "quote_number", "invoice_number", "supplier_number", "employee_number", "payment_number", "catalog_srv_code", "catalog_mat_code"];

const bookingAvailability = seedTenantId ? [
  { _id: oid("665f61000000000000000001"), tenantId: seedTenantId, dayOfWeek: 1, startLocal: "09:00", endLocal: "17:00" },
  { _id: oid("665f61000000000000000002"), tenantId: seedTenantId, dayOfWeek: 2, startLocal: "09:00", endLocal: "17:00" },
  { _id: oid("665f61000000000000000003"), tenantId: seedTenantId, dayOfWeek: 3, startLocal: "09:00", endLocal: "17:00" },
  { _id: oid("665f61000000000000000004"), tenantId: seedTenantId, dayOfWeek: 4, startLocal: "09:00", endLocal: "17:00" },
  { _id: oid("665f61000000000000000005"), tenantId: seedTenantId, dayOfWeek: 5, startLocal: "09:00", endLocal: "13:00" },
] : [];

const bookingAppointments = seedTenantId ? [
  {
    _id: ids.bookings.b1,
    tenantId: seedTenantId,
    catalogItemId: "srv-consultation",
    serviceName: "Consultation",
    priceCents: cents(60),
    clientId: ids.clients.hillsong,
    contactName: "Hillsong Portugal",
    contactPhone: "+351910100001",
    startAt: date("2026-05-21T09:00:00.000Z"),
    endAt: date("2026-05-21T10:00:00.000Z"),
    status: "CONFIRMED",
    notes: "Initial consultation",
    source: "DASHBOARD",
    createdAt: now,
    updatedAt: now,
  },
  {
    _id: ids.bookings.b2,
    tenantId: seedTenantId,
    catalogItemId: "srv-site-visit",
    serviceName: "Site visit",
    priceCents: cents(45),
    clientId: ids.clients.martins,
    contactName: "Martins Digital Lda",
    contactPhone: "+351910100002",
    startAt: date("2026-05-22T14:00:00.000Z"),
    endAt: date("2026-05-22T15:30:00.000Z"),
    status: "PENDING",
    notes: null,
    source: "WHATSAPP",
    createdAt: now,
    updatedAt: now,
  },
] : [];

/**
 * Agents with their runs, approvals, tasks and notifications, a Gmail account and its emails.
 * Unlike the records above they are dated from when the script runs: notifications expire after
 * 90 days, email text is purged after 90, finished runs after 180, and pending approvals within days.
 */
function buildAgentSeed() {
  const runAt = new Date();
  const later = (value, seconds) => new Date(value.getTime() + seconds * 1000);
  const hoursAgo = (hours) => later(runAt, -hours * 3600);
  const daysAgo = (days) => hoursAgo(days * 24);
  const iso = (value) => value.toISOString();
  const dayOf = (value) => iso(value).slice(0, 10);
  const daysBetween = (fromDay, toDay) => Math.round((Date.parse(`${toDay}T00:00:00Z`) - Date.parse(`${fromDay}T00:00:00Z`)) / 86400000);
  const hex = (id) => id.toHexString();
  const byNumber = (list, number) => list.find((item) => item.number === number);
  const subjectOf = (type, id) => ({ type, id: hex(id) });
  const sumCents = (list) => list.reduce((sum, item) => sum + item.totalCents, 0);
  // flow.wait's business-day waits: the next weekday at that hour (UTC, close enough to Lisbon).
  const weekdayAt = (value, hourUtc) => {
    const day = new Date(value);
    day.setUTCHours(hourUtc, 0, 0, 0);
    while (day.getUTCDay() === 0 || day.getUTCDay() === 6) day.setUTCDate(day.getUTCDate() + 1);
    return day;
  };
  // Amounts and dates as agents write them for a pt-PT company.
  const money = (cents) => {
    const [whole, fraction] = (cents / 100).toFixed(2).split(".");
    return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, "\u00a0")},${fraction}\u00a0€`;
  };
  const ptDate = (isoDay) => isoDay.split("-").reverse().join("/");

  const seedUser = target.getCollection("dashboard_users").findOne({ tenantId: seedTenantId });
  const actorId = seedUser ? hex(seedUser._id) : "operator";
  const actorName = seedUser ? seedUser.email : "operator";
  const documentTemplate = seedTenant.documentTemplate || {};
  const companyName = documentTemplate.companyName || seedTenant.name;
  const company = {
    name: companyName,
    phone: documentTemplate.phone || "",
    email: documentTemplate.email || "",
    address: documentTemplate.address || "",
    taxId: documentTemplate.taxId || "",
  };
  const signature = `Com os melhores cumprimentos,\n${companyName}`;
  const gmailAccount = "orcamentos@example.com";

  const hillsong = byNumber(clients, "CLT-001");
  const martins = byNumber(clients, "CLT-002");
  const oliveira = byNumber(clients, "CLT-003");
  const quoteMartins = byNumber(quotes, "ORC-001");
  const quoteOliveira = byNumber(quotes, "ORC-002");
  const invoiceMartins = byNumber(invoices, "FAT-002");
  const invoiceHillsong = byNumber(invoices, "FAT-003");

  // What a run keeps of its record; email text and snippets are never stored on runs.
  const baseContext = (at) => ({ now: iso(at), today: dayOf(at), company, event: {} });
  const clientContext = (client) => ({
    id: hex(client._id),
    number: client.number,
    name: client.name,
    firstName: client.name.trim().split(" ")[0],
    phone: client.phone || "",
    email: client.email || "",
    address: client.address || "",
    taxId: client.taxId || "",
    hasEmail: Boolean(client.email),
    hasTaxId: Boolean(client.taxId),
  });
  const invoiceContext = (invoice, at, quoteNumber = null) => {
    const untilDue = daysBetween(dayOf(at), invoice.dueDate);
    const overdue = (invoice.status === "PENDING" || invoice.status === "OVERDUE") && untilDue < 0;
    return {
      id: hex(invoice._id),
      number: invoice.number,
      status: invoice.status,
      total: money(invoice.totalCents),
      totalCents: invoice.totalCents,
      dueDate: invoice.dueDate,
      daysUntilDue: untilDue,
      daysOverdue: overdue ? -untilDue : 0,
      isOverdue: overdue,
      ...(quoteNumber ? { quoteNumber } : {}),
    };
  };
  const hasPdf = (email) => email.attachments.some((file) => file.mimeType === "application/pdf");
  const emailContext = (email) => ({
    id: hex(email._id),
    from: email.from,
    fromName: email.fromName || email.from,
    subject: email.subject,
    hasAttachments: email.attachments.length > 0,
    hasPdf: hasPdf(email),
    knownClient: Boolean(email.clientId),
    threadId: email.threadId,
    automated: Boolean(email.automated),
  });
  const emailEvent = (email) => ({
    type: "email.received",
    actorType: "SYSTEM",
    from: email.from,
    data_fromName: email.fromName,
    data_subject: email.subject,
    data_hasAttachments: email.attachments.length > 0,
    data_hasPdf: hasPdf(email),
    data_automated: Boolean(email.automated),
    data_connectionId: hex(email.connectionId),
    data_threadId: email.threadId,
  });

  // ── Gmail account and emails ──
  const snippetOf = (text) => text.replace(/\s+/g, " ").trim().slice(0, 200);
  const emailDoc = ({ id, providerId, threadId, direction = "INBOUND", from, fromName, to = [gmailAccount], subject, bodyText, attachments = [], automated = false, clientId = null, record = null, sentByUser = false, date }) => {
    const doc = {
      _id: id,
      tenantId: seedTenantId,
      connectionId: ids.gmail,
      providerMessageId: `mock-${providerId}`,
      threadId: `mock-thread-${threadId}`,
      messageIdHeader: `<mock-${providerId}@mail.example.com>`,
      direction,
      from,
      fromName,
      to,
      cc: [],
      bcc: [],
      subject,
      snippet: snippetOf(bodyText),
      bodyText,
      attachments,
      clientId,
      record,
      sentByType: sentByUser ? "USER" : null,
      sentById: sentByUser ? actorId : null,
      sentByName: sentByUser ? actorName : null,
      runId: null,
      date,
      createdAt: date,
    };
    if (automated) doc.automated = true;
    return doc;
  };
  const quoteSent = emailDoc({
    id: ids.emails.quoteSent,
    providerId: "quote-sent",
    threadId: "orc-001",
    direction: "OUTBOUND",
    from: gmailAccount,
    fromName: companyName,
    to: [martins.email],
    subject: `Orçamento ${quoteMartins.number} · ${companyName}`,
    bodyText: `Olá ${martins.name},\n\nSegue em anexo o orçamento ${quoteMartins.number}. Se tiver alguma dúvida, basta responder a este email.\n\n${signature}`,
    attachments: [{ filename: `${quoteMartins.number}.pdf`, mimeType: "application/pdf", size: 48213 }],
    clientId: martins._id,
    record: subjectOf("quote", quoteMartins._id),
    sentByUser: true,
    date: daysAgo(3),
  });
  const quoteReply = emailDoc({
    id: ids.emails.quoteReply,
    providerId: "quote-reply",
    threadId: "orc-001",
    from: martins.email,
    fromName: "Joana Martins",
    subject: `Re: ${quoteSent.subject}`,
    bodyText: "Olá,\n\nObrigada pelo orçamento. Podemos começar pela landing page e pelo bot de WhatsApp e deixar a manutenção para o mês seguinte?\n\nJoana Martins\nMartins Digital Lda",
    clientId: martins._id,
    date: daysAgo(2),
  });
  const request = emailDoc({
    id: ids.emails.request,
    providerId: "request",
    threadId: "request",
    from: "carlos.nunes@example.com",
    fromName: "Carlos Nunes",
    subject: "Infiltração na cobertura do armazém",
    bodyText: "Bom dia,\n\nDepois das últimas chuvas apareceu uma infiltração na cobertura do nosso armazém em Leiria. Fazem impermeabilização com membrana líquida? Para quando seria possível uma visita?\n\nCumprimentos,\nCarlos Nunes",
    date: daysAgo(2),
  });
  const lead = emailDoc({
    id: ids.emails.lead,
    providerId: "lead",
    threadId: "lead",
    from: "sofia.almeida@example.com",
    fromName: "Sofia Almeida",
    subject: "Pedido de orçamento: pintura de moradia",
    bodyText: "Boa tarde,\n\nGostaria de pedir um orçamento para pintar o exterior de uma moradia de dois pisos em Oeiras, com cerca de 220 m² de fachada. Seria possível visitarem a obra na próxima semana?\n\nPode ligar-me para o 912 345 678.\n\nObrigada,\nSofia Almeida",
    date: hoursAgo(6),
  });
  // The newest email received, so testing the supplier bill draft reads it.
  const supplierBill = emailDoc({
    id: ids.emails.supplierBill,
    providerId: "supplier-bill",
    threadId: "supplier-bill",
    from: "faturacao@tintasnorte.pt",
    fromName: "Tintas Norte, Lda.",
    subject: "Fatura FT 2026/1187",
    bodyText: "Exmos. Senhores,\n\nEnviamos em anexo a fatura FT 2026/1187, no valor de 492,00 €, com vencimento a 30 dias.\n\nCom os melhores cumprimentos,\nDepartamento de Faturação\nTintas Norte, Lda.",
    attachments: [{ filename: "FT-2026-1187.pdf", mimeType: "application/pdf", size: 86417 }],
    automated: true,
    date: hoursAgo(2),
  });

  // No tokens: the account waits for a reconnect and never calls Google.
  const gmailBrokeAt = hoursAgo(1);
  const otherGoogleAccounts = target.getCollection("integration_connections").countDocuments({
    tenantId: seedTenantId,
    provider: "google",
    _id: { $ne: ids.gmail },
    accountEmail: { $ne: gmailAccount },
  });
  const gmailConnection = {
    _id: ids.gmail,
    tenantId: seedTenantId,
    provider: "google",
    accountEmail: gmailAccount,
    scopes: [
      "openid",
      "email",
      "https://www.googleapis.com/auth/gmail.send",
      "https://www.googleapis.com/auth/gmail.readonly",
      "https://www.googleapis.com/auth/gmail.modify",
    ],
    status: "NEEDS_RECONNECT",
    connectedByUserId: seedUser ? actorId : null,
    connectedByEmail: seedUser ? actorName : null,
    lastError: "invalid_grant",
    isDefault: otherGoogleAccounts === 0,
    settings: { senderName: companyName, replyTo: null, signature, inboxSync: true },
    dailySends: { day: dayOf(quoteSent.date), count: 1 },
    inbox: { enabledAt: daysAgo(10), lastSyncedAt: later(gmailBrokeAt, -300), lastError: null },
    createdAt: daysAgo(14),
    updatedAt: gmailBrokeAt,
  };

  // ── Agents: the pt definitions their templates build ──
  const policy = { autonomy: "APPROVE", maxRunsPerDay: 200, cooldownHours: 0, approvers: "ANY_MEMBER", notifyUserIds: [], notifyOnFailure: true, maxTokensPerRun: 20000 };
  const voice = { tone: "friendly", usePersona: false, emoji: false };
  const invoiceOverdue = { match: "ALL", conditions: [{ field: "invoice.isOverdue", op: "eq", value: true }] };
  const quoteStillSent = { match: "ALL", conditions: [{ field: "quote.status", op: "eq", value: "SENT" }] };
  const firstReminder = "Olá {{client.firstName}}, a fatura {{invoice.number}} ({{invoice.total}}) venceu a {{invoice.dueDate}}. Se já pagou, ignore esta mensagem. Obrigado! {{company.name}}";
  const leadInstructions = "Leia o email e decida se é um novo pedido de trabalho ou de orçamento (e não uma newsletter, uma fatura, uma notificação ou uma resposta sobre um trabalho em curso). Encontre o nome de quem escreve e o telefone, se o indicar, e escreva um resumo de uma linha do que precisa.";
  const leadTaskTitle = "Novo pedido por email de {{email.fromName}}: {{steps.s1.output.summary}}";
  const definitions = {
    overdue: {
      triggers: [{ id: "t1", type: "date_offset", config: { entity: "invoice", offsetDays: 1, offsetHours: 0, at: "10:00", statuses: ["PENDING", "OVERDUE"] } }],
      steps: [
        { id: "s1", action: "whatsapp.send", input: { to: "client", text: firstReminder, attachPdf: "none", fallback: "email" }, autonomy: "APPROVE", onError: "RETRY_THEN_FAIL" },
        { id: "s2", action: "flow.wait", input: { days: 7, hours: 0, minutes: 0, at: "10:00", businessDay: true }, onError: "RETRY_THEN_FAIL" },
        {
          id: "s3",
          action: "whatsapp.send",
          input: { to: "client", text: "Olá {{client.firstName}}, a fatura {{invoice.number}} ({{invoice.total}}) está em atraso há {{invoice.daysOverdue}} dias. Pode indicar-nos quando conta pagar? Envio novamente a fatura. {{company.name}}", attachPdf: "invoice", fallback: "email" },
          guard: invoiceOverdue,
          autonomy: "APPROVE",
          onError: "RETRY_THEN_FAIL",
        },
        { id: "s4", action: "flow.wait", input: { days: 7, hours: 0, minutes: 0, at: "09:00", businessDay: true }, onError: "RETRY_THEN_FAIL" },
        { id: "s5", action: "team.task.create", input: { title: "Ligar a {{client.name}} sobre a fatura {{invoice.number}} ({{invoice.daysOverdue}} dias em atraso)", dueInDays: 0 }, guard: invoiceOverdue, onError: "RETRY_THEN_FAIL" },
      ],
      exitRules: [{ event: "invoice.paid" }],
      policy,
      voice,
    },
    quotes: {
      triggers: [{ id: "t1", type: "event", config: { event: "quote.status_changed", toStatus: "SENT" } }],
      steps: [
        { id: "s1", action: "flow.wait", input: { days: 3, hours: 0, minutes: 0, at: "10:00", businessDay: true }, onError: "RETRY_THEN_FAIL" },
        {
          id: "s2",
          action: "whatsapp.send",
          input: { to: "client", text: "Olá {{client.firstName}}, já teve oportunidade de ver o orçamento {{quote.number}} ({{quote.total}})? Estou disponível para qualquer dúvida. {{company.name}}", attachPdf: "none", fallback: "email" },
          guard: quoteStillSent,
          autonomy: "APPROVE",
          onError: "RETRY_THEN_FAIL",
        },
        { id: "s3", action: "flow.wait", input: { days: 4, hours: 0, minutes: 0, at: "09:00", businessDay: true }, onError: "RETRY_THEN_FAIL" },
        { id: "s4", action: "team.task.create", input: { title: "Fazer seguimento com {{client.name}} do orçamento {{quote.number}}", dueInDays: 0 }, guard: quoteStillSent, onError: "RETRY_THEN_FAIL" },
      ],
      exitRules: [{ event: "quote.status_changed" }],
      policy,
      voice,
    },
    cash: {
      triggers: [{ id: "t1", type: "schedule", config: { frequency: "weekly", time: "17:00", weekdays: [5] } }],
      steps: [
        { id: "s1", action: "data.summary", input: { kind: "cash_week" }, onError: "RETRY_THEN_FAIL" },
        { id: "s2", action: "team.notify", input: { message: "{{steps.s1.output.text}}", audience: "admins" }, onError: "RETRY_THEN_FAIL" },
      ],
      exitRules: [],
      policy,
      voice,
    },
    leads: {
      triggers: [{ id: "t1", type: "email.received", config: { sender: "unknown" } }],
      conditions: { match: "ALL", conditions: [{ field: "email.automated", op: "eq", value: false }] },
      steps: [
        {
          id: "s1",
          action: "ai.task",
          input: {
            instructions: leadInstructions,
            outputs: [{ name: "isRequest", type: "boolean" }, { name: "name", type: "text" }, { name: "phone", type: "text" }, { name: "summary", type: "text" }],
            readData: false,
            actions: [],
          },
          onError: "RETRY_THEN_FAIL",
        },
        {
          id: "s2",
          action: "team.task.create",
          input: {
            title: leadTaskTitle,
            detail: "Nome: {{steps.s1.output.name | default: não indicado}}\nTelefone: {{steps.s1.output.phone | default: não indicado}}\nEmail: {{email.from}}\nAssunto: {{email.subject}}",
            dueInDays: 0,
          },
          guard: { match: "ALL", conditions: [{ field: "steps.s1.output.isRequest", op: "eq", value: true }] },
          onError: "RETRY_THEN_FAIL",
        },
      ],
      exitRules: [],
      policy,
      voice,
    },
    bills: {
      triggers: [{ id: "t1", type: "email.received", config: { sender: "any", hasPdf: true } }],
      steps: [
        {
          id: "s1",
          action: "ai.task",
          input: {
            instructions: "Decida se o email é uma fatura que a empresa tem de pagar (e não um orçamento, um recibo de algo já pago, um extrato ou publicidade). Se for, escreva uma descrição curta do que se trata, o valor a pagar com a moeda, tal como está escrito (por exemplo, 1234,50 €), e a data de vencimento. O PDF anexo não está incluído: deixe de fora o que o próprio email não diz.",
            outputs: [{ name: "isBill", type: "boolean" }, { name: "description", type: "text" }, { name: "amount", type: "text" }, { name: "dueDate", type: "date" }],
            readData: false,
            actions: [],
          },
          onError: "RETRY_THEN_FAIL",
        },
        {
          id: "s2",
          action: "team.notify",
          input: {
            message: "Fatura de fornecedor de {{email.fromName}}: {{steps.s1.output.description}} · {{steps.s1.output.amount | default: valor não indicado no email}} · vencimento: {{steps.s1.output.dueDate | default: não indicado}}.",
            audience: "admins",
          },
          guard: { match: "ALL", conditions: [{ field: "steps.s1.output.isBill", op: "eq", value: true }] },
          onError: "RETRY_THEN_FAIL",
        },
      ],
      exitRules: [],
      policy,
      voice,
    },
  };

  const noRuns = { runs: 0, succeeded: 0, failed: 0, lastRunAt: null, consecutiveFailures: 0, approvalsInARow: 0 };
  const agentDoc = ({ id, name, icon, kind, templateKey, templateParams, status, definition, createdAt, updatedAt = createdAt, stats = {} }) => ({
    _id: id,
    tenantId: seedTenantId,
    name,
    description: null,
    icon,
    kind,
    templateKey,
    templateParams,
    status,
    definition,
    triggerTypes: [...new Set(definition.triggers.map((trigger) => trigger.type))],
    eventTypes: [...new Set([
      ...definition.triggers.filter((trigger) => trigger.type === "event").map((trigger) => trigger.config.event),
      ...definition.exitRules.map((rule) => rule.event),
    ])],
    scheduleState: {},
    nextFireAt: null,
    version: 1,
    createdBy: actorId,
    createdAt,
    updatedAt,
    stats: { ...noRuns, ...stats },
    pausedReason: null,
  });

  const lastBriefingAt = (() => {
    const day = new Date(runAt);
    day.setUTCHours(16, 0, 0, 0);
    while (day.getUTCDay() !== 5 || day > runAt) day.setUTCDate(day.getUTCDate() - 1);
    return day;
  })();
  const leadRunAt = later(lead.date, 60);
  const leadRunDone = later(leadRunAt, 9);
  const requestRunAt = later(request.date, 60);
  const requestRunFailed = later(requestRunAt, 92);

  const overdueAgent = agentDoc({
    id: ids.agents.overdue,
    name: "Seguimento de faturas em atraso",
    icon: "alert",
    kind: "WORKFLOW",
    templateKey: "overdue_sequence",
    templateParams: { firstAfterDays: 1, secondAfterDays: 7, taskAfterDays: 7, fallback: "email", autonomy: "APPROVE" },
    status: "ACTIVE",
    definition: definitions.overdue,
    createdAt: daysAgo(12),
    stats: { approvalsInARow: 1 },
  });
  const quotesAgent = agentDoc({
    id: ids.agents.quotes,
    name: "Seguimento de orçamentos",
    icon: "quote",
    kind: "WORKFLOW",
    templateKey: "quote_follow_up",
    templateParams: { firstAfterDays: 3, taskAfterDays: 4, fallback: "email", autonomy: "APPROVE" },
    status: "ACTIVE",
    definition: definitions.quotes,
    createdAt: daysAgo(12),
  });
  const cashAgent = agentDoc({
    id: ids.agents.cash,
    name: "Resumo semanal de tesouraria",
    icon: "chart",
    kind: "DIGEST",
    templateKey: "weekly_cash_briefing",
    templateParams: { weekday: 5, time: "17:00", audience: "admins" },
    status: "PAUSED",
    definition: definitions.cash,
    createdAt: later(lastBriefingAt, -3 * 86400),
    updatedAt: new Date(Math.min(runAt.getTime(), later(lastBriefingAt, 3600).getTime())),
    stats: { runs: 1, succeeded: 1, lastRunAt: later(lastBriefingAt, 3) },
  });
  const leadsAgent = agentDoc({
    id: ids.agents.leads,
    name: "Captar pedidos por email",
    icon: "mail",
    kind: "AI_WORKER",
    templateKey: "email_lead_capture",
    templateParams: { newContactsOnly: true },
    status: "ACTIVE",
    definition: definitions.leads,
    createdAt: daysAgo(10),
    stats: { runs: 2, succeeded: 1, failed: 1, lastRunAt: leadRunDone },
  });
  const billsAgent = agentDoc({
    id: ids.agents.bills,
    name: "Receção de faturas de fornecedores",
    icon: "receipt",
    kind: "AI_WORKER",
    templateKey: "supplier_bill_intake",
    templateParams: { pdfOnly: true, audience: "admins", createTask: false },
    status: "DRAFT",
    definition: definitions.bills,
    createdAt: daysAgo(1),
  });

  // ── Runs ──
  const stepResult = (stepId, action, status, startedAt, finishedAt, attempts, fields = {}) => ({
    stepId,
    action,
    status,
    ...(startedAt ? { startedAt: iso(startedAt) } : {}),
    ...(finishedAt ? { finishedAt: iso(finishedAt) } : {}),
    attempts,
    ...fields,
  });
  const runDoc = ({ id, agent, trigger, subject = null, subjectLabel = null, clientId = null, dedupeKey, status, currentStep, context, steps, resumeAt = null, createdAt, updatedAt, finishedAt = null, error = null, outcome = null, promptTokens = 0, completionTokens = 0 }) => ({
    _id: id,
    tenantId: seedTenantId,
    agentId: agent._id,
    agentName: agent.name,
    agentVersion: agent.version,
    definition: agent.definition,
    trigger,
    subject,
    subjectLabel,
    clientId,
    dedupeKey,
    status,
    currentStep,
    context,
    steps,
    resumeAt,
    depth: 0,
    dryRun: false,
    promptTokens,
    completionTokens,
    createdAt,
    updatedAt,
    startedAt: later(createdAt, 1),
    finishedAt,
    claimedAt: later(createdAt, 1),
    error,
    outcome,
  });
  const reminderInput = (client, invoice) => ({
    to: "client",
    text: `Olá ${clientContext(client).firstName}, a fatura ${invoice.number} (${money(invoice.totalCents)}) venceu a ${ptDate(invoice.dueDate)}. Se já pagou, ignore esta mensagem. Obrigado! ${companyName}`,
    attachPdf: "none",
    fallback: "email",
  });
  // Neither client has written on WhatsApp in the last 24 hours, so only a template could go.
  const reminderPreview = (client, input) => ({
    kind: "message",
    channel: "whatsapp",
    recipients: [client.phone.replace(/\D/g, "")],
    body: input.text,
    attachments: [],
    fields: {},
    editable: ["text"],
    warnings: ["window_closed"],
  });

  const reminderAt = hoursAgo(20);
  const reminder = reminderInput(hillsong, invoiceHillsong);
  const reminderRun = runDoc({
    id: ids.agentRuns.reminder,
    agent: overdueAgent,
    trigger: { type: "date_offset", triggerId: "t1", firedAt: iso(reminderAt) },
    subject: subjectOf("invoice", invoiceHillsong._id),
    subjectLabel: `${invoiceHillsong.number} · ${hillsong.name}`,
    clientId: hillsong._id,
    dedupeKey: `date:t1:${hex(invoiceHillsong._id)}:${invoiceHillsong.dueDate}`,
    status: "AWAITING_APPROVAL",
    currentStep: 0,
    context: { ...baseContext(reminderAt), invoice: invoiceContext(invoiceHillsong, reminderAt), client: clientContext(hillsong) },
    steps: [stepResult("s1", "whatsapp.send", "AWAITING_APPROVAL", later(reminderAt, 2), null, 0, { input: reminder, output: {} })],
    createdAt: reminderAt,
    updatedAt: later(reminderAt, 2),
  });

  // Approved two days ago; with the WhatsApp window closed and Gmail to reconnect, a task went to the team.
  const waitingAt = daysAgo(3);
  const approvedAt = daysAgo(2);
  const resumeAt = weekdayAt(later(approvedAt, 7 * 86400), 9);
  const martinsReminder = reminderInput(martins, invoiceMartins);
  const waitingRun = runDoc({
    id: ids.agentRuns.waiting,
    agent: overdueAgent,
    trigger: { type: "date_offset", triggerId: "t1", firedAt: iso(waitingAt) },
    subject: subjectOf("invoice", invoiceMartins._id),
    subjectLabel: `${invoiceMartins.number} · ${martins.name}`,
    clientId: martins._id,
    dedupeKey: `date:t1:${hex(invoiceMartins._id)}:${invoiceMartins.dueDate}`,
    status: "WAITING",
    currentStep: 2,
    context: {
      ...baseContext(approvedAt),
      invoice: invoiceContext(invoiceMartins, approvedAt, quoteMartins.number),
      client: clientContext(martins),
      steps: { s1: { output: { taskId: hex(ids.agentTasks.martins) } } },
    },
    steps: [
      stepResult("s1", "whatsapp.send", "DONE", later(waitingAt, 2), later(approvedAt, 1), 1, { input: martinsReminder, output: { taskId: hex(ids.agentTasks.martins) }, note: "approved" }),
      stepResult("s2", "flow.wait", "DONE", later(approvedAt, 1), later(approvedAt, 1), 1, { input: definitions.overdue.steps[1].input, note: `wait_until:${iso(resumeAt)}` }),
    ],
    resumeAt,
    createdAt: waitingAt,
    updatedAt: later(approvedAt, 1),
  });

  // The quote was accepted while the agent waited to follow it up, so the exit rule ended the run.
  const quoteSentAt = daysAgo(6);
  const quoteAcceptedAt = daysAgo(4);
  const quoteSentEvent = hex(ids.events.quoteSent);
  const quoteRun = runDoc({
    id: ids.agentRuns.quoteAccepted,
    agent: quotesAgent,
    trigger: { type: "event", triggerId: "t1", eventId: quoteSentEvent, eventType: "quote.status_changed", firedAt: iso(quoteSentAt) },
    subject: subjectOf("quote", quoteOliveira._id),
    subjectLabel: `${quoteOliveira.number} · ${oliveira.name}`,
    clientId: oliveira._id,
    dedupeKey: `event:${quoteSentEvent}:t1`,
    status: "CANCELLED",
    currentStep: 1,
    context: {
      ...baseContext(quoteSentAt),
      event: {
        type: "quote.status_changed",
        actorType: "USER",
        from: "PENDENTE",
        to: "SENT",
        data_number: quoteOliveira.number,
        data_clientId: hex(oliveira._id),
        data_status: "SENT",
        data_totalCents: quoteOliveira.totalCents,
        data_validUntil: quoteOliveira.validUntil,
      },
      quote: {
        id: hex(quoteOliveira._id),
        number: quoteOliveira.number,
        status: "SENT",
        total: money(quoteOliveira.totalCents),
        totalCents: quoteOliveira.totalCents,
        validUntil: quoteOliveira.validUntil,
        daysUntilExpiry: daysBetween(dayOf(quoteSentAt), quoteOliveira.validUntil),
        createdAt: iso(quoteOliveira.createdAt),
        itemCount: quoteOliveira.items.length,
      },
      client: clientContext(oliveira),
    },
    steps: [
      stepResult("s1", "flow.wait", "DONE", later(quoteSentAt, 1), later(quoteSentAt, 1), 1, {
        input: definitions.quotes.steps[0].input,
        note: `wait_until:${iso(weekdayAt(later(quoteSentAt, 3 * 86400), 9))}`,
      }),
    ],
    createdAt: quoteSentAt,
    updatedAt: quoteAcceptedAt,
    finishedAt: quoteAcceptedAt,
    outcome: "exit:quote.status_changed",
  });

  const requestEvent = hex(ids.events.request);
  const requestRun = runDoc({
    id: ids.agentRuns.requestFailed,
    agent: leadsAgent,
    trigger: { type: "email.received", triggerId: "t1", eventId: requestEvent, eventType: "email.received", firedAt: iso(requestRunAt) },
    subject: subjectOf("email", request._id),
    subjectLabel: request.subject,
    dedupeKey: `event:${requestEvent}:t1`,
    status: "FAILED",
    currentStep: 0,
    context: { ...baseContext(requestRunAt), event: emailEvent(request), email: emailContext(request) },
    steps: [stepResult("s1", "ai.task", "FAILED", later(requestRunAt, 1), requestRunFailed, 3, { input: definitions.leads.steps[0].input, error: "ai_unavailable" })],
    createdAt: requestRunAt,
    updatedAt: requestRunFailed,
    finishedAt: requestRunFailed,
    error: "s1: ai_unavailable",
    outcome: "step_failed",
  });

  const leadOutput = {
    isRequest: true,
    name: "Sofia Almeida",
    phone: "912 345 678",
    summary: "orçamento para pintar o exterior de uma moradia de dois pisos em Oeiras (cerca de 220 m²), com visita na próxima semana",
  };
  const leadTask = {
    title: `Novo pedido por email de ${lead.fromName}: ${leadOutput.summary}`,
    detail: `Nome: ${leadOutput.name}\nTelefone: ${leadOutput.phone}\nEmail: ${lead.from}\nAssunto: ${lead.subject}`,
    dueInDays: 0,
  };
  const leadEvent = hex(ids.events.lead);
  const leadRun = runDoc({
    id: ids.agentRuns.lead,
    agent: leadsAgent,
    trigger: { type: "email.received", triggerId: "t1", eventId: leadEvent, eventType: "email.received", firedAt: iso(leadRunAt) },
    subject: subjectOf("email", lead._id),
    subjectLabel: lead.subject,
    dedupeKey: `event:${leadEvent}:t1`,
    status: "SUCCEEDED",
    currentStep: 2,
    context: {
      ...baseContext(leadRunAt),
      event: emailEvent(lead),
      email: emailContext(lead),
      steps: { s1: { output: leadOutput }, s2: { output: { taskId: hex(ids.agentTasks.lead) } } },
    },
    steps: [
      stepResult("s1", "ai.task", "DONE", later(leadRunAt, 1), later(leadRunAt, 8), 1, { input: definitions.leads.steps[0].input, output: leadOutput }),
      stepResult("s2", "team.task.create", "DONE", later(leadRunAt, 8), leadRunDone, 1, { input: leadTask, output: { taskId: hex(ids.agentTasks.lead) } }),
    ],
    createdAt: leadRunAt,
    updatedAt: leadRunDone,
    finishedAt: leadRunDone,
    promptTokens: 1834,
    completionTokens: 96,
  });

  // The week data.summary's cash_week reads from the invoices and payments above.
  const briefingDay = dayOf(lastBriefingAt);
  const weekStart = dayOf(later(new Date(`${briefingDay}T00:00:00Z`), -((lastBriefingAt.getUTCDay() + 6) % 7) * 86400));
  const openInvoices = invoices.filter((invoice) => invoice.status === "PENDING" || invoice.status === "OVERDUE");
  const overdueInvoices = openInvoices.filter((invoice) => invoice.dueDate < briefingDay);
  const collected = invoices.filter((invoice) => invoice.status === "PAID" && invoice.paidAt && dayOf(invoice.paidAt) >= weekStart);
  const payables = payments.filter((payment) => payment.status === "PENDING" && payment.dueDate <= dayOf(later(lastBriefingAt, 7 * 86400)));
  const cashLines = [
    `Recebido: ${money(sumCents(collected))}`,
    `A receber: ${money(sumCents(openInvoices))}`,
    `Em atraso: ${money(sumCents(overdueInvoices))} (${overdueInvoices.length})`,
    `A pagar esta semana: ${money(sumCents(payables))}`,
  ];
  const cashSummary = { text: `A semana em números\n${cashLines.map((entry) => `• ${entry}`).join("\n")}`, count: 0, lines: cashLines };
  const briefingDone = later(lastBriefingAt, 3);
  const briefingRun = runDoc({
    id: ids.agentRuns.briefing,
    agent: cashAgent,
    trigger: { type: "schedule", triggerId: "t1", firedAt: iso(lastBriefingAt) },
    dedupeKey: `schedule:t1:${lastBriefingAt.getTime()}`,
    status: "SUCCEEDED",
    currentStep: 2,
    context: {
      ...baseContext(lastBriefingAt),
      steps: { s1: { output: cashSummary }, s2: { output: { notificationId: hex(ids.notifications.briefing) } } },
    },
    steps: [
      stepResult("s1", "data.summary", "DONE", later(lastBriefingAt, 1), later(lastBriefingAt, 2), 1, { input: { kind: "cash_week", limit: 15 }, output: cashSummary }),
      stepResult("s2", "team.notify", "DONE", later(lastBriefingAt, 2), briefingDone, 1, { input: { message: cashSummary.text, audience: "admins" }, output: { notificationId: hex(ids.notifications.briefing) } }),
    ],
    createdAt: lastBriefingAt,
    updatedAt: briefingDone,
    finishedAt: briefingDone,
  });

  // ── Approvals ──
  const approvalDoc = ({ id, run, input, preview, createdAt, decidedAt = null }) => ({
    _id: id,
    tenantId: seedTenantId,
    agentId: run.agentId,
    agentName: run.agentName,
    runId: run._id,
    stepId: "s1",
    seq: 0,
    action: "whatsapp.send",
    input,
    preview,
    subject: run.subject,
    subjectLabel: run.subjectLabel,
    approvers: "ANY_MEMBER",
    status: decidedAt ? "APPROVED" : "PENDING",
    createdAt,
    expiresAt: later(createdAt, 3 * 86400),
    decidedAt,
    decidedBy: decidedAt ? actorId : null,
    decidedByName: decidedAt ? actorName : null,
    edited: false,
    reason: null,
    dryRun: false,
  });
  const pendingApproval = approvalDoc({ id: ids.agentApprovals.pending, run: reminderRun, input: reminder, preview: reminderPreview(hillsong, reminder), createdAt: later(reminderAt, 2) });
  const approvedApproval = approvalDoc({ id: ids.agentApprovals.approved, run: waitingRun, input: martinsReminder, preview: reminderPreview(martins, martinsReminder), createdAt: later(waitingAt, 2), decidedAt: approvedAt });

  // ── Tasks ──
  const taskDoc = ({ id, title, detail, subject, subjectLabel, clientId = null, run = null, dueAt, createdAt, completedAt = null }) => ({
    _id: id,
    tenantId: seedTenantId,
    title,
    detail,
    subject,
    subjectLabel,
    clientId,
    assigneeUserId: run || !seedUser ? null : actorId,
    assigneeName: run || !seedUser ? null : actorName,
    dueAt,
    status: completedAt ? "DONE" : "OPEN",
    agentId: run ? run.agentId : null,
    agentName: run ? run.agentName : null,
    runId: run ? run._id : null,
    createdBy: run ? `agent:${hex(run.agentId)}` : actorId,
    createdAt,
    updatedAt: completedAt || createdAt,
    completedAt,
    completedBy: completedAt ? actorId : null,
  });
  const martinsTask = taskDoc({
    id: ids.agentTasks.martins,
    title: `Contactar ${martins.name}`,
    detail: `O agente não conseguiu enviar esta mensagem por WhatsApp (a janela de 24 horas do WhatsApp está fechada). Envie-a manualmente:\n\n${martinsReminder.text}`,
    subject: waitingRun.subject,
    subjectLabel: waitingRun.subjectLabel,
    clientId: martins._id,
    run: waitingRun,
    dueAt: later(approvedAt, 1),
    createdAt: later(approvedAt, 1),
  });
  const leadFollowUp = taskDoc({
    id: ids.agentTasks.lead,
    title: leadTask.title,
    detail: leadTask.detail,
    subject: leadRun.subject,
    subjectLabel: leadRun.subjectLabel,
    run: leadRun,
    dueAt: leadRunDone,
    createdAt: leadRunDone,
  });
  const callTask = taskDoc({
    id: ids.agentTasks.call,
    title: `Ligar à ${hillsong.name} sobre a fatura ${invoiceHillsong.number}`,
    detail: "Confirmar a data de pagamento com o gestor do escritório.",
    subject: reminderRun.subject,
    subjectLabel: reminderRun.subjectLabel,
    clientId: hillsong._id,
    dueAt: daysAgo(2),
    createdAt: daysAgo(4),
    completedAt: daysAgo(1),
  });

  // ── Notifications ──
  const notificationDoc = ({ id, kind, audience = "ADMINS", params, body = null, link = "agents", subject = null, ref = null, read = false, createdAt }) => ({
    _id: id,
    tenantId: seedTenantId,
    audience,
    userId: null,
    kind,
    params,
    body,
    link,
    subject,
    ref,
    readBy: read && seedUser ? [actorId] : [],
    createdAt,
  });
  const agentNotifications = [
    notificationDoc({
      id: ids.notifications.approval,
      kind: "agent_approval",
      audience: "ALL",
      params: { agent: overdueAgent.name, subject: reminderRun.subjectLabel, count: "1" },
      subject: reminderRun.subject,
      ref: `approval:${hex(pendingApproval._id)}`,
      createdAt: pendingApproval.createdAt,
    }),
    notificationDoc({
      id: ids.notifications.martinsTask,
      kind: "agent_task",
      params: { agent: overdueAgent.name, title: martinsTask.title, subject: waitingRun.subjectLabel },
      subject: waitingRun.subject,
      ref: `task:${hex(martinsTask._id)}`,
      read: true,
      createdAt: martinsTask.createdAt,
    }),
    notificationDoc({
      id: ids.notifications.leadTask,
      kind: "agent_task",
      params: { agent: leadsAgent.name, title: leadFollowUp.title, subject: leadRun.subjectLabel },
      subject: leadRun.subject,
      ref: `task:${hex(leadFollowUp._id)}`,
      createdAt: leadFollowUp.createdAt,
    }),
    notificationDoc({
      id: ids.notifications.failed,
      kind: "agent_failed",
      params: { agent: leadsAgent.name, subject: requestRun.subjectLabel, error: requestRun.error },
      subject: requestRun.subject,
      ref: `run:${hex(requestRun._id)}`,
      read: true,
      createdAt: requestRunFailed,
    }),
    notificationDoc({
      id: ids.notifications.reconnect,
      kind: "integration_reconnect",
      params: { integration: "GMAIL", account: gmailAccount },
      link: "settings",
      createdAt: gmailBrokeAt,
    }),
    notificationDoc({
      id: ids.notifications.briefing,
      kind: "agent_notice",
      params: { agent: cashAgent.name, subject: "" },
      body: cashSummary.text,
      read: true,
      createdAt: briefingDone,
    }),
  ];

  return {
    agents: [overdueAgent, quotesAgent, cashAgent, leadsAgent, billsAgent],
    runs: [reminderRun, waitingRun, quoteRun, requestRun, leadRun, briefingRun],
    approvals: [pendingApproval, approvedApproval],
    tasks: [martinsTask, leadFollowUp, callTask],
    notifications: agentNotifications,
    connections: [gmailConnection],
    emails: [quoteSent, quoteReply, request, lead, supplierBill],
  };
}

const agentSeed = seedTenantId
  ? buildAgentSeed()
  : { agents: [], runs: [], approvals: [], tasks: [], notifications: [], connections: [], emails: [] };

// bcrypt of "colaborador123", the mock employee's password (see README.md).
const EMPLOYEE_PASSWORD_HASH = "$2b$12$GimrgeRMzPGOSDQJkIufNu.sudM4XzmUu8w8m9WAqgYhXVbyR89Fq";
const EMPLOYEE_EMAIL = "ana.costa@example.com";

/**
 * Ana Costa's own sign-in and the services she registered: two waiting for the team, one approved
 * into a Serviços row done by her, one rejected with a reason. Dated from when the script runs, like
 * the agents, so the waiting ones read as recent.
 */
function buildEmployeeWorkSeed() {
  const runAt = new Date();
  const hoursAgo = (hours) => new Date(runAt.getTime() - hours * 3600 * 1000);
  const dayOf = (value) => value.toISOString().slice(0, 10);
  const reviewer = target.getCollection("dashboard_users").findOne({ tenantId: seedTenantId, role: { $ne: "TENANT_EMPLOYEE" } });
  const reviewedBy = reviewer ? reviewer.email : "operator";
  const sumCents = (items) => items.reduce((sum, item) => sum + item.totalCents, 0);
  const submission = ({ id, clientId, name, items, notes = null, doneHoursAgo, sentHoursAgo, status = "PENDING", decided = {} }) => ({
    _id: id,
    tenantId: seedTenantId,
    employeeId: ids.employees.ana,
    clientId,
    name,
    notes,
    items,
    totalCents: sumCents(items),
    catalogItemId: null,
    performedAt: dayOf(hoursAgo(doneHoursAgo)),
    status,
    adjusted: false,
    createdAt: hoursAgo(sentHoursAgo),
    updatedAt: decided.reviewedAt || hoursAgo(sentHoursAgo),
    ...decided,
  });
  const interiorItems = [line("Pintura interior da rececao", 6, "h", 18.5)];
  const approvedAt = hoursAgo(46);
  const submissions = [
    submission({
      id: ids.serviceSubmissions.facade,
      clientId: ids.clients.oliveira,
      name: "Pintura de fachada + Tinta acrilica exterior",
      items: [line("Pintura de fachada - Pintura exterior com duas demaos", 40, "m2", 8.5), line("Tinta acrilica exterior - Tinta acrilica exterior premium", 6, "l", 12)],
      notes: "Lado norte terminado. Falta o lado sul.",
      doneHoursAgo: 20,
      sentHoursAgo: 3,
    }),
    submission({
      id: ids.serviceSubmissions.wash,
      clientId: ids.clients.costa,
      name: "Lavagem de fachada",
      items: [line("Lavagem de fachada - Lavagem e preparacao de fachada", 55, "m2", 4.5)],
      doneHoursAgo: 28,
      sentHoursAgo: 26,
    }),
    submission({
      id: ids.serviceSubmissions.interior,
      clientId: ids.clients.hillsong,
      name: "Pintura interior da rececao",
      items: interiorItems,
      doneHoursAgo: 72,
      sentHoursAgo: 70,
      status: "APPROVED",
      decided: { serviceId: ids.clientServices.interior, reviewedBy, reviewedAt: approvedAt },
    }),
    submission({
      id: ids.serviceSubmissions.roof,
      clientId: ids.clients.martins,
      name: "Reparacao de cobertura",
      items: [line("Reparacao de cobertura", 4, "h", 22)],
      doneHoursAgo: 96,
      sentHoursAgo: 94,
      status: "REJECTED",
      decided: { reviewedBy, reviewedAt: hoursAgo(90), rejectionReason: "Foram 2 horas, nao 4. Regista de novo, por favor." },
    }),
  ];
  const interior = submissions[2];
  return {
    login: {
      _id: ids.employeeLogins.ana,
      tenantId: seedTenantId,
      email: EMPLOYEE_EMAIL,
      passwordHash: EMPLOYEE_PASSWORD_HASH,
      role: "TENANT_EMPLOYEE",
      status: "ACTIVE",
      createdAt: hoursAgo(24 * 10),
      lastLoginAt: hoursAgo(3),
      employeeId: ids.employees.ana,
      employeeTenantId: seedTenantId,
    },
    submissions,
    services: [{
      _id: ids.clientServices.interior,
      tenantId: seedTenantId,
      clientId: interior.clientId,
      name: interior.name,
      notes: null,
      quantity: interiorItems[0].quantity,
      unit: interiorItems[0].unit,
      unitPriceCents: interiorItems[0].unitPriceCents,
      totalCents: interior.totalCents,
      items: interiorItems,
      status: "OPEN",
      invoiceId: null,
      bookingServiceId: null,
      catalogItemId: null,
      bookingId: null,
      performedAt: interior.performedAt,
      createdAt: approvedAt,
      updatedAt: approvedAt,
      employeeId: ids.employees.ana,
    }],
    notifications: [{
      _id: ids.submittedNotification,
      tenantId: seedTenantId,
      audience: "ALL",
      userId: null,
      kind: "service_submitted",
      params: { employee: "Ana Costa", service: submissions[0].name, client: "Condominio Rua Oliveira" },
      body: null,
      link: "employees",
      subject: { type: "employee", id: ids.employees.ana.toHexString() },
      ref: `submission:${ids.serviceSubmissions.facade.toHexString()}`,
      readBy: [],
      createdAt: submissions[0].createdAt,
    }],
  };
}

const workSeed = seedTenantId ? buildEmployeeWorkSeed() : { login: null, submissions: [], services: [], notifications: [] };

function removeSeedConflicts() {
  target.users.deleteMany({ $or: [{ _id: { $in: users.map((item) => item._id) } }, { waId: { $in: users.map((item) => item.waId) } }, { "metadata.mockSeed": "create-mocks" }] });
  target.conversations.deleteMany({ $or: [{ _id: { $in: conversations.map((item) => item._id) } }, { waId: { $in: conversations.map((item) => item.waId) } }] });
  target.messages.deleteMany({ $or: [{ _id: { $in: messages.map((item) => item._id) } }, { waMessageId: { $in: messages.map((item) => item.waMessageId).filter(Boolean) } }] });
  target.getCollection("webhook_events").deleteMany({ eventId: { $in: webhookEvents.map((item) => item.eventId) } });
  target.getCollection("crm.clients").deleteMany({ $or: [{ _id: { $in: clients.map((item) => item._id) } }, { number: { $in: clients.map((item) => item.number) } }, { phone: { $in: clients.map((item) => item.phone) } }] });
  target.getCollection("crm.quotes").deleteMany({ $or: [{ _id: { $in: quotes.map((item) => item._id) } }, { number: { $in: quotes.map((item) => item.number) } }] });
  target.getCollection("crm.invoices").deleteMany({ $or: [{ _id: { $in: invoices.map((item) => item._id) } }, { number: { $in: invoices.map((item) => item.number) } }] });
  target.getCollection("crm.suppliers").deleteMany({ $or: [{ _id: { $in: suppliers.map((item) => item._id) } }, { number: { $in: suppliers.map((item) => item.number) } }, { phone: { $in: suppliers.map((item) => item.phone) } }] });
  target.getCollection("crm.employees").deleteMany({ $or: [{ _id: { $in: employees.map((item) => item._id) } }, { number: { $in: employees.map((item) => item.number) } }, { phone: { $in: employees.map((item) => item.phone) } }] });
  target.getCollection("crm.payments").deleteMany({ $or: [{ _id: { $in: payments.map((item) => item._id) } }, { number: { $in: payments.map((item) => item.number) } }] });
  target.getCollection("crm.standard_items").deleteMany({ $or: [{ id: { $in: standardItems.map((item) => item.id) } }, { tenantId: seedTenantId, code: { $in: standardItems.map((item) => item.code) } }] });
  target.getCollection("crm.sequences").deleteMany({ name: { $in: sequenceNames } });
  target.getCollection("bookings.services").deleteMany({ _id: { $in: Object.values(ids.bookingServices) } });
  target.getCollection("bookings.availability").deleteMany({ _id: { $in: bookingAvailability.map((item) => item._id) } });
  target.getCollection("bookings.appointments").deleteMany({ _id: { $in: Object.values(ids.bookings) } });
  // Runs, approvals and tasks the app made for the mock agents go with them, so dedupe keys can't clash.
  const agentIds = Object.values(ids.agents);
  target.agents.deleteMany({ _id: { $in: agentIds } });
  target.getCollection("agent_runs").deleteMany({ $or: [{ _id: { $in: Object.values(ids.agentRuns) } }, { agentId: { $in: agentIds } }] });
  target.getCollection("agent_approvals").deleteMany({ $or: [{ _id: { $in: Object.values(ids.agentApprovals) } }, { agentId: { $in: agentIds } }] });
  target.getCollection("agent_tasks").deleteMany({ $or: [{ _id: { $in: Object.values(ids.agentTasks) } }, { agentId: { $in: agentIds } }] });
  target.notifications.deleteMany({ _id: { $in: Object.values(ids.notifications) } });
  target.getCollection("integration_connections").deleteMany({
    $or: [{ _id: ids.gmail }, ...agentSeed.connections.map((item) => ({ tenantId: item.tenantId, provider: item.provider, accountEmail: item.accountEmail }))],
  });
  target.getCollection("email_messages").deleteMany({ $or: [{ _id: { $in: Object.values(ids.emails) } }, { connectionId: ids.gmail }] });
  // What the app saved for the mock employee goes too, so the counts start from the seed again.
  target.getCollection("dashboard_users").deleteMany({ $or: [{ _id: ids.employeeLogins.ana }, { employeeId: ids.employees.ana }, { email: EMPLOYEE_EMAIL }] });
  target.getCollection("crm.service_submissions").deleteMany({ $or: [{ _id: { $in: Object.values(ids.serviceSubmissions) } }, { employeeId: ids.employees.ana }] });
  target.getCollection("crm.client_services").deleteMany({ $or: [{ _id: { $in: Object.values(ids.clientServices) } }, { employeeId: ids.employees.ana }] });
  target.notifications.deleteMany({ $or: [{ _id: ids.submittedNotification }, { kind: "service_submitted", "subject.id": ids.employees.ana.toHexString() }] });
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
insertMany("crm.suppliers", suppliers);
insertMany("crm.employees", employees);
insertMany("crm.payments", payments);
insertMany("crm.standard_items", standardItems);
insertMany("crm.sequences", [
  { name: "client_number", value: 4 },
  { name: "quote_number", value: 3 },
  { name: "invoice_number", value: 3 },
  { name: "supplier_number", value: 2 },
  { name: "employee_number", value: 2 },
  { name: "payment_number", value: 3 },
  { name: "catalog_srv_code", value: 15 },
  { name: "catalog_mat_code", value: 2 },
].map((item) => seedTenantId ? { ...item, tenantId: seedTenantId } : item));
insertMany("bookings.availability", bookingAvailability);
insertMany("bookings.appointments", bookingAppointments);
insertMany("agents", agentSeed.agents);
insertMany("agent_runs", agentSeed.runs);
insertMany("agent_approvals", agentSeed.approvals);
insertMany("agent_tasks", agentSeed.tasks);
insertMany("notifications", agentSeed.notifications);
insertMany("integration_connections", agentSeed.connections);
insertMany("email_messages", agentSeed.emails);
insertMany("dashboard_users", workSeed.login ? [workSeed.login] : []);
insertMany("crm.service_submissions", workSeed.submissions);
insertMany("crm.client_services", workSeed.services);
insertMany("notifications", workSeed.notifications);

const summary = {
  database: dbName,
  users: target.users.countDocuments({ _id: { $in: users.map((item) => item._id) } }),
  conversations: target.conversations.countDocuments({ _id: { $in: conversations.map((item) => item._id) } }),
  messages: target.messages.countDocuments({ _id: { $in: messages.map((item) => item._id) } }),
  webhook_events: target.getCollection("webhook_events").countDocuments({ eventId: { $in: webhookEvents.map((item) => item.eventId) } }),
  crm_clients: target.getCollection("crm.clients").countDocuments({ _id: { $in: clients.map((item) => item._id) } }),
  crm_quotes: target.getCollection("crm.quotes").countDocuments({ _id: { $in: quotes.map((item) => item._id) } }),
  crm_invoices: target.getCollection("crm.invoices").countDocuments({ _id: { $in: invoices.map((item) => item._id) } }),
  crm_suppliers: target.getCollection("crm.suppliers").countDocuments({ _id: { $in: suppliers.map((item) => item._id) } }),
  crm_employees: target.getCollection("crm.employees").countDocuments({ _id: { $in: employees.map((item) => item._id) } }),
  crm_payments: target.getCollection("crm.payments").countDocuments({ _id: { $in: payments.map((item) => item._id) } }),
  crm_standard_items: target.getCollection("crm.standard_items").countDocuments({ id: { $in: standardItems.map((item) => item.id) } }),
  crm_sequences: target.getCollection("crm.sequences").countDocuments({ name: { $in: sequenceNames } }),
  bookable_services: target.getCollection("crm.standard_items").countDocuments({ id: { $in: ["srv-consultation", "srv-site-visit"] }, bookable: true }),
  booking_availability: target.getCollection("bookings.availability").countDocuments({ _id: { $in: bookingAvailability.map((item) => item._id) } }),
  booking_appointments: target.getCollection("bookings.appointments").countDocuments({ _id: { $in: Object.values(ids.bookings) } }),
  agents: target.agents.countDocuments({ _id: { $in: Object.values(ids.agents) } }),
  agent_runs: target.getCollection("agent_runs").countDocuments({ _id: { $in: Object.values(ids.agentRuns) } }),
  agent_approvals: target.getCollection("agent_approvals").countDocuments({ _id: { $in: Object.values(ids.agentApprovals) } }),
  agent_tasks: target.getCollection("agent_tasks").countDocuments({ _id: { $in: Object.values(ids.agentTasks) } }),
  notifications: target.notifications.countDocuments({ _id: { $in: Object.values(ids.notifications) } }),
  integration_connections: target.getCollection("integration_connections").countDocuments({ _id: ids.gmail }),
  email_messages: target.getCollection("email_messages").countDocuments({ _id: { $in: Object.values(ids.emails) } }),
  employee_logins: target.getCollection("dashboard_users").countDocuments({ _id: ids.employeeLogins.ana }),
  service_submissions: target.getCollection("crm.service_submissions").countDocuments({ _id: { $in: Object.values(ids.serviceSubmissions) } }),
  crm_client_services: target.getCollection("crm.client_services").countDocuments({ _id: { $in: Object.values(ids.clientServices) } }),
};

printjson(summary);
