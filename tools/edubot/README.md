# edubot CLI and MCP server

Use the CLI or local stdio MCP server to work with edubot from your terminal or MCP host. The
operation catalog includes the dashboard, employee portal, backoffice and client share pages.

## Install

Install Node.js 22 or later, then run:

```sh
cd tools/edubot
npm ci
node src/cli.js help
```

You can run `npm link` from this directory to put `edubot` and `edubot-mcp` on your PATH.

## CLI and MCP

Run `node src/cli.js catalog` to find actions by path, scope or method. Use `call` with a
method and path template from the catalog and `help` for command options.

```sh
node src/cli.js catalog --scope dashboard --search quotes
node src/cli.js help list-clients
```

Configure your MCP host to run `node /path/to/rfm-edubot/tools/edubot/src/mcp.js` over stdio.
Use `list_operations` to discover operations in your session and `read_operation` to call them.
The server exposes write tools when enabled in its configuration.

Both interfaces use edubot's existing account permissions. The CLI's `open` command and MCP's
`browser_workflow` link to application pages for steps that require user interaction.

## Test and update the catalog

Run `npm test` from this directory. The Kotlin catalog test checks the committed catalog against
the mounted application routes.

From the repo root, run:

```sh
UPDATE_EDUBOT_CATALOG=1 ./gradlew test --tests '*EdubotOperationCatalogTest'
```
