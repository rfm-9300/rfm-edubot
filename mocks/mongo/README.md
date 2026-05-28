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
- `crm.sequences`
- `crm.standard_items`
