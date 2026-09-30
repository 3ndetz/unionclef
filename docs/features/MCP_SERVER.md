# MCP server in the mod (LAN control surface)

The mod itself hosts an **MCP server** (Model Context Protocol) — the cognitive agent (Claude)
connects over the network and drives the bot with the same levers as py4j, but directly, without
docker-exec or the loopback bottleneck. One source of truth: MCP wraps the methods of
`Py4jEntryPoint` (see [AGENT_PY4J_LEVERS.md](AGENT_PY4J_LEVERS.md)).

## What it is

- Transport: **Streamable HTTP**, JSON-RPC 2.0, one endpoint `POST /mcp`.
- Implementation: `com.sun.net.httpserver` (built into the JDK, no dependencies),
  `adris.altoclef.mcp.McpServer`. Binds on `0.0.0.0` — reachable over LAN.
- Protocol methods: `initialize`, `tools/list`, `tools/call`, `ping`.
- Tools: a curated set of levers (perception / movement / combat /
  building+WorldEdit / protection / menus / commands), each with a description and
  a JSON schema — the agent understands what each one does.

## Settings (`altoclef_settings.json` / Settings)

| Field | Default | What |
|---|---|---|
| `mcpEnabled` | `true` | Whether to bring up the MCP server |
| `mcpPort` | `25350` | Port (bind 0.0.0.0) |

Starts automatically after the py4j gateway. In the log: `MCP server started on
0.0.0.0:25350`.

## How to connect Claude

Endpoint: `http://<bot-machine-ip>:25350/mcp` (on LAN — e.g.
`http://192.168.1.20:25350/mcp`).

Claude Code (HTTP transport):

```bash
claude mcp add --transport http unionclef http://192.168.1.20:25350/mcp
```

Or in `.mcp.json`:

```json
{ "mcpServers": { "unionclef": { "type": "http", "url": "http://192.168.1.20:25350/mcp" } } }
```

The Docker bench publishes the port externally (`compose.test.yml`: `25350:25350`). A native
client on the host binds 0.0.0.0 itself — visible over LAN without publishing.

## Verification

`deploy/runner/mcp_test.py` — initialize + tools/list + getGameState (read) +
fillSelection (action) over HTTP. Or by hand:

```bash
curl -s http://127.0.0.1:25350/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | python3 -m json.tool
```

## Adding tools

A new lever = a method in `Py4jEntryPoint` + one `tool(...)` line in
`McpServer.registerTools()` (name, description, `schema(...)`, lambda to the method). Don't
duplicate logic — wrapper only. Keep it in sync with the py4j catalog.
