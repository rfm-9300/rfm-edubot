# edubot CLI and MCP server

Both commands use the same dashboard session and company permissions as `/app`. Install them from
this repository with Node.js 22 or later.

```sh
cd tools/edubot
npm ci
node src/cli.js help
```

Use `node src/cli.js <command>` from this directory, or run `npm link` here to put `edubot` and
`edubot-mcp` on your PATH.

## Sign in

Set `EDUBOT_URL` to the dashboard origin and `EDUBOT_TOKEN` to a dashboard session token. Use HTTPS
for remote dashboards. Local development also accepts loopback HTTP, such as
`http://127.0.0.1:8080`.

For a dashboard account with password sign-in enabled, run the following in Bash. The password is
read without echo and passed on stdin. The CLI prints the session token for your shell to capture.

```bash
export EDUBOT_URL=http://127.0.0.1:8080
read -rsp 'Dashboard password: ' edubot_password
printf '\n'
EDUBOT_TOKEN=$(printf '%s' "$edubot_password" | node src/cli.js login --email user@example.test)
unset edubot_password
export EDUBOT_TOKEN
node src/cli.js whoami
```

For a Google-only account, use a dashboard session token from an existing sign-in or pass a Firebase
ID token from the dashboard's configured Google sign-in to `node src/cli.js login --google` on
stdin. Keep session tokens out of shared configuration and shell history. Sign in again when a
session expires.

## CLI

```sh
node src/cli.js overview
node src/cli.js help list-clients
node src/cli.js list-clients --args '{"q":"Example"}'
node src/cli.js list-invoices --args '{"status":"PAID"}'
node src/cli.js create-client --confirm --args - < client.json
node src/cli.js request GET "/app/api/crm/quotes/$QUOTE_ID/pdf" --output quote.pdf
```

Named commands have JSON schemas in `edubot help <command>`. Use `request` for other dashboard
endpoints. It accepts URL query parameters, JSON request bodies and raw file downloads. JSON results
go to stdout and failures go to stderr with a nonzero exit code. Commands that change dashboard
data require `--confirm`.

Use `whoami` to find the current and available company IDs. `switch-company <id>` prints a
replacement token with the original session expiry. Capture it into `EDUBOT_TOKEN` for subsequent
commands.

## MCP server

Use this local stdio server configuration in a compatible MCP host. Give the host process access
to `EDUBOT_TOKEN` through its environment or its secret configuration. Replace the checkout path
and dashboard origin with your own values.

```json
{
  "mcpServers": {
    "edubot": {
      "command": "node",
      "args": ["/absolute/path/to/rfm-edubot/tools/edubot/src/mcp.js"],
      "env": {
        "EDUBOT_URL": "http://127.0.0.1:8080"
      }
    }
  }
}
```

The server discovers typed tools for the dashboard overview and session, CRM and billing, bookings,
Building projects, attendance, conversations, email, chatbot flows, Agents and notifications. It
also provides the `edubot://dashboard/session` and `edubot://dashboard/overview` resources and a
`company-brief` prompt.

The default tool set is read-only. Set `EDUBOT_MCP_ALLOW_WRITES=1` in the server environment to
enable creating clients, sending conversation replies, changing automatic reply settings and
marking notifications read. A server instance uses one dashboard token. Restart it with the
replacement token after sign-in or company switching.

## Test

Run `npm test` from this directory. The tests spawn the CLI and an MCP client over stdio and use
a local HTTP fixture for the dashboard.
