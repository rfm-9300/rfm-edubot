import { z } from 'zod';

const text = z.string().trim().min(1);
const id = z.string().regex(/^[0-9a-f]{24}$/i).describe('24-character dashboard record ID');
const search = text.optional().describe('Dashboard search text');
const status = text.optional().describe('Status to filter by, as shown by the dashboard API');
const instant = z.iso.datetime({ offset: true });
const dateRange = { from: instant.optional(), to: instant.optional() };
const directoryFilters = { q: search, archived: z.boolean().optional().describe('Show archived records') };
const billingFilters = { clientId: id.optional(), status };
const clientFields = {
  name: text, phone: text, email: text.optional(), address: text.optional(),
  city: text.optional(), postalCode: text.optional(), taxId: text.optional(),
  contactPerson: text.optional(), notes: z.string().optional(),
  customFields: z.record(z.string(), z.json()).optional(),
};

function read(description, path, args = {}, queryFor = (query) => query) {
  return {
    description,
    schema: z.strictObject(args),
    async run(api, query, signal) {
      return api.json(path, { query: queryFor(query), signal });
    },
  };
}

function directory(description, path) {
  return read(description, path, directoryFilters, ({ q, archived }) => ({ q, archived: archived ? 1 : undefined }));
}

function detail(description, path, suffix = '') {
  return {
    description,
    schema: z.strictObject({ id }),
    async run(api, { id }, signal) {
      return api.json(`${path}${id}${suffix}`, { signal });
    },
  };
}

function write(description, method, pathFor, args, bodyFor) {
  return {
    description,
    schema: z.strictObject(args),
    writes: true,
    async run(api, input, signal) {
      return api.json(pathFor(input), { method, body: bodyFor(input), signal });
    },
  };
}

export const operations = {
  health: read('Check if the edubot HTTP server is up.', '/health'),
  ready: read('Check if the server can reach its database.', '/ready'),
  whoami: read('Get the signed-in company, user, enabled modules and available companies.', '/app/api/me'),
  overview: read('Get the dashboard overview for the current company.', '/app/api/overview'),
  list_notifications: read('List recent notifications for the current user.', '/app/api/notifications'),

  list_clients: directory('Search the current company’s client directory.', '/app/api/crm/clients'),
  get_client: detail('Get a client by ID.', '/app/api/crm/clients/'),
  list_suppliers: directory('Search the supplier directory.', '/app/api/crm/suppliers'),
  get_supplier: detail('Get a supplier by ID.', '/app/api/crm/suppliers/'),
  list_employees: directory('Search the employee directory.', '/app/api/crm/employees'),
  get_employee: detail('Get an employee by ID.', '/app/api/crm/employees/'),
  list_contacts: read('Search the current company’s messaging contacts.', '/app/api/contacts', { q: search }),
  list_catalog_items: read('Search the standard items in the catalog.', '/app/api/crm/standard-items', {
    q: search, type: text.optional(),
  }),
  list_services: read('List work logged in Serviços.', '/app/api/crm/services', {
    ...billingFilters, quoteId: id.optional(), employeeId: id.optional(),
  }),
  get_service: detail('Get a recorded service by ID.', '/app/api/crm/services/'),
  list_quotes: read('List quotes for the current company.', '/app/api/crm/quotes', billingFilters),
  get_quote: detail('Get a quote and its line items.', '/app/api/crm/quotes/'),
  list_invoices: read('List invoices for the current company.', '/app/api/crm/invoices', billingFilters),
  get_invoice: detail('Get an invoice by ID.', '/app/api/crm/invoices/'),
  list_payments: read('List outgoing payments for the current company.', '/app/api/crm/payments', {
    ...billingFilters, supplierId: id.optional(), employeeId: id.optional(),
  }),
  get_payment: detail('Get an outgoing payment by ID.', '/app/api/crm/payments/'),

  list_bookings: read('List appointments, optionally within an ISO 8601 date-time range.', '/app/api/bookings', {
    ...dateRange, ...billingFilters, q: search,
  }),
  get_booking: detail('Get an appointment by ID.', '/app/api/bookings/'),
  list_booking_services: read('List services available in the booking calendar.', '/app/api/bookings/services', {
    active: z.boolean().optional(),
  }),
  list_booking_slots: read('Find available times for a booking service within a date-time range.', '/app/api/bookings/slots', {
    serviceId: text, from: instant, to: instant,
  }),
  list_projects: read('List Building projects.', '/app/api/building/projects', { quoteId: id.optional() }),
  get_project: detail('Get a Building project by ID.', '/app/api/building/projects/'),
  get_timesheet_board: read('Get open shifts and expected attendance for the current company.', '/app/api/timesheets/board'),

  list_conversations: read('Search customer conversations.', '/app/api/conversations', { q: search }),
  list_conversation_messages: detail('Get the recent messages in a conversation.', '/app/api/conversations/', '/messages'),
  list_email_threads: read('List or search company email threads.', '/app/api/email/inbox/threads', {
    q: search, account: id.optional(), filter: text.optional(),
  }),
  get_email_thread: detail('Get an email thread by its dashboard message ID.', '/app/api/email/inbox/threads/'),
  list_chatbot_flows: read('List the company’s configured chatbot flows.', '/app/api/chatbot/flows'),
  get_agent_overview: read('Get the Agents module’s automation overview.', '/app/api/agents/overview'),

  create_client: write('Create a client in the current company.', 'POST', () => '/app/api/crm/clients',
    clientFields, (input) => input),
  send_conversation_reply: write('Send a customer-facing text reply to an existing conversation.', 'POST',
    ({ id }) => `/app/api/conversations/${id}/messages`, { id, text }, ({ text }) => ({ text })),
  set_conversation_auto_reply: write('Enable or pause automatic replies for a conversation.', 'PATCH',
    ({ id }) => `/app/api/conversations/${id}/auto-reply`, { id, enabled: z.boolean() }, ({ enabled }) => ({ enabled })),
  mark_notification_read: write('Mark a dashboard notification as read for the current user.', 'POST',
    ({ id }) => `/app/api/notifications/${id}/read`, { id }, () => undefined),
};

export async function runOperation(api, operation, args, { allowWrites = false, signal } = {}) {
  if (operation.writes && !allowWrites) throw new Error('Use --confirm to run a write command.');
  const parsed = operation.schema.parse(args);
  return operation.run(api, parsed, signal);
}
