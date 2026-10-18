// Run against the data database (mongosh wabot). Tenants live in "<database>_platform" and webhook
// events in "<database>_runtime".
const t = db.getSiblingDB(`${db.getName()}_platform`).tenants.findOne();

if (!t) {
  throw new Error("seed the first tenant before migrating");
}

const tenantId = t._id;

[
  "users",
  "conversations",
  "messages",
  "crm.clients",
  "crm.quotes",
  "crm.invoices",
  "crm.sequences",
  "crm.standard_items",
  "crm.suppliers",
  "crm.employees",
  "crm.payments",
].map((name) => db.getCollection(name)).concat([db.getSiblingDB(`${db.getName()}_runtime`).getCollection("webhook_events")]).forEach((collection) => {
  const result = collection.updateMany(
    { tenantId: { $exists: false } },
    { $set: { tenantId } },
  );
  print(`${collection.getName()}: matched=${result.matchedCount} modified=${result.modifiedCount}`);
});
