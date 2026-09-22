#!/usr/bin/env bash
# Idempotent repository bootstrap for the WhatsApp Bot (Ktor + MongoDB).
# Runs after the repo is checked out. Safe to run repeatedly.
set -euo pipefail
cd "$(dirname "$0")/.."

# 1. Ensure MongoDB server is available. Normally baked into the base
#    snapshot/image; this guard keeps install working on a plain base too.
if ! command -v mongod >/dev/null 2>&1; then
  echo "Installing MongoDB 8.0 ..."
  sudo apt-get install -y gnupg curl >/dev/null
  curl -fsSL https://pgp.mongodb.com/server-8.0.asc \
    | sudo gpg -o /usr/share/keyrings/mongodb-server-8.0.gpg --dearmor --yes
  echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-8.0.gpg ] https://repo.mongodb.org/apt/ubuntu noble/mongodb-org/8.0 multiverse" \
    | sudo tee /etc/apt/sources.list.d/mongodb-org-8.0.list
  sudo apt-get update -y
  sudo apt-get install -y mongodb-org
fi

# 2. Local data directories.
sudo mkdir -p /data/db /var/log/mongodb
sudo chown -R "$USER":"$USER" /data/db /var/log/mongodb
mkdir -p data/pdfs

# 3. Dev-only .env with placeholder integration values so the app can boot.
#    These are NOT real secrets: WhatsApp/OpenRouter calls are not exercised in
#    local dev. Admin dashboard password is "admin123" (bcrypt hash below).
if [ ! -f .env ]; then
  echo "Writing dev .env ..."
  cat > .env <<'ENV'
MONGO_URI=mongodb://127.0.0.1:27017
WA_VERIFY_TOKEN=dev-verify-token
WA_APP_SECRET=dev-app-secret
WA_PHONE_NUMBER_ID=000000000000000
WA_ACCESS_TOKEN=dev-access-token
OPENROUTER_API_KEY=dev-openrouter-key
ADMIN_JWT_SECRET=dev-jwt-secret-change-me
ADMIN_PASSWORD_HASH=$2a$10$/NeqcxmBZ3DOvapqCCDHlea8ukiSGBwyiMbAX9lKnebyJQH6DcOL.
PDF_STORAGE_PATH=./data/pdfs
ENV
fi

# 4. Warm the Gradle dependency cache and compile (also provisions the JDK 20
#    toolchain via the foojay resolver on first run).
./gradlew compileKotlin compileTestKotlin --console=plain

echo "install.sh complete"
