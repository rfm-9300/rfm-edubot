# Mongo Mock Data

This folder contains deterministic local seed data for the WhatsApp bot MongoDB.

Run it with a local Mongo container:

```bash
docker exec -i whatsapp-bot-mongo-1 mongosh --quiet --file /dev/stdin < mocks/mongo/create-mocks.js
```

The script targets database `wabot` by default. Override with `MONGO_DATABASE` if needed:

```bash
MONGO_DATABASE=wabot_dev docker exec -i whatsapp-bot-mongo-1 mongosh --quiet --file /dev/stdin < mocks/mongo/create-mocks.js
```

The script deletes and recreates only its known seed records. It does not drop the database, delete Mongo volumes, or remove unrelated local data.

Seeded collections:

- `users`
- `conversations`
- `messages`
- `webhook_events`
- `crm.clients`
- `crm.quotes`
- `crm.invoices`
- `crm.suppliers`
- `crm.employees`
- `crm.payments`
- `crm.sequences`
- `crm.standard_items`
- `bookings.services`
- `bookings.availability`
- `bookings.appointments`
- `agents`
- `agent_runs`
- `agent_approvals`
- `agent_tasks`
- `notifications`
- `integration_connections`
- `email_messages`
- `dashboard_users` (only the mock employee's own sign-in)
- `crm.service_submissions`
- `crm.client_services` (only the service approved from a submission)

Bookings, agents, email and the employee's sign-in are seeded only when the database already has a tenant; they go to the first one. Start the app once before seeding a fresh database.

Suppliers and invoices:

- Both suppliers have usual services (Tintas Norte's paints and transport, Andaimes & Cia's scaffolding), so a new payment to either offers them as lines. The ones without a price vary from job to job.
- FAT-001 and FAT-002 carry a tax office code (ATCUD). FAT-002 is paid in two installments: the first one is received, the second one sets its due date.

Employee sign-in:

- Ana Costa (`COL-001`) signs in to `/app` as `ana.costa@example.com` with the password `colaborador123`. Her session only shows My services.
- She registered four services: two waiting for approval (they show on her employee record, on Home's Needs you and in the bell), one approved into a Serviços row done by her, one rejected with a reason.
- They are dated from when the script runs. Running it again also removes what the app saved for her since (her submissions, the services approved from them and her sign-in), then seeds them again.

Agents and email:

- The Agents module is opt-in. A company only sees it once the backoffice turns it on in the company's modules.
- The agents' runs, approvals, tasks, notifications and emails are dated from when the script runs, so the pending approval doesn't expire and the 90-day email clean-up doesn't remove them. Run the script again to refresh them.
- The active agents don't start on the seeded invoices and quotes by themselves. Nothing is sent until someone approves the pending WhatsApp reminder.
- The Gmail account `orcamentos@example.com` has no tokens and shows "needs reconnect". Nothing calls Google. Connecting a real account needs the Google OAuth settings in `.env.example`.
