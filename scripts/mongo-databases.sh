# shellcheck shell=bash disable=SC2034
# Sourced by backup-mongo.sh, restore-mongo.sh and restore-tenant.sh: where the app keeps what
# (MongoModule). One MongoDB server, three databases:
#   DATA_DB      companies' data; every document has its company's tenantId (or uses it as _id)
#   PLATFORM_DB  the platform's own records
#   RUNTIME_DB   disposable state, never backed up
# Until the split (October 2026) everything lived in DATA_DB, and the originals of the moved
# collections stay there for a rollback to an older release. MongoModuleLayoutTest keeps these
# lists equal to MongoModule's.

DATA_DB="${DATA_DB:-wabot}"
PLATFORM_DB="${PLATFORM_DB:-${DATA_DB}_platform}"
RUNTIME_DB="${RUNTIME_DB:-${DATA_DB}_runtime}"

PLATFORM_COLLECTIONS=(tenants dashboard_users admin_emails platform_settings tenant_usage)
RUNTIME_COLLECTIONS=(webhook_events scheduler_leases timesheets.challenges)
