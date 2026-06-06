const t = db.tenants.findOne();

if (!t) {
  throw new Error("seed the first tenant before migrating");
}

const tenantId = t._id;

[
  "users",
  "conversations",
  "messages",
  "webhook_events",
  "crm.clients",
  "crm.quotes",
  "crm.invoices",
  "crm.sequences",
  "crm.standard_items",
].forEach((collectionName) => {
  const result = db[collectionName].updateMany(
    { tenantId: { $exists: false } },
    { $set: { tenantId } },
  );
  print(`${collectionName}: matched=${result.matchedCount} modified=${result.modifiedCount}`);
});
