# UnionClef Python Scripts

Control the bot from Python via the Py4J bridge.

## Setup

```bash
# Install uv (if you don't have it)
# Windows:
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
# macOS/Linux:
curl -LsSf https://astral.sh/uv/install.sh | sh

# Install dependencies
cd scripts
uv sync
```

## Running scripts

```bash
uv run example_goto.py              # default port 25333
uv run example_goto.py 25335        # custom port
uv run custom/my_test.py            # your own scripts go in custom/
```

## Connection

The mod starts a Py4J gateway on port `25333` (configurable via `@set pythonGatewayPort <port>`).
If the port is busy (multi-instance), the mod auto-increments by 2 and saves to settings.

```python
from py4j.java_gateway import JavaGateway

gw = JavaGateway()  # default port 25333
bot = gw.entry_point
```

## Sending commands

`ChatMessage()` is the universal interface. It routes by prefix:

```python
bot.ChatMessage("@goto 0 64 0")       # altoclef command
bot.ChatMessage("#goto 0 64 0")       # baritone/shredder command
bot.ChatMessage(";goto 100 64 100")   # tungsten command
bot.ChatMessage("/warp park")         # server command
bot.ChatMessage("gg")                 # plain chat message
```

For altoclef commands specifically, `ExecuteCommand()` bypasses the chat queue:

```python
bot.ExecuteCommand("@goto 0 64 0")    # immediate, no throttle
```

## Auto-connect to server

Set in `altoclef_settings.json` (in `versions/1.21/run/altoclef/`):

```json
{
  "autoConnectServer": "mc.example.com",
  "autoReconnect": true,
  "autoRespawn": true,
  "idleCommand": "idle"
}
```

Or connect from Python at runtime:

```python
bot.ConnectToServer("mc.example.com")
```

## API reference (highlights)

| Method | Description |
|--------|-------------|
| `ChatMessage(msg)` | Universal: send chat, server cmd, or mod cmd by prefix |
| `ExecuteCommand(cmd)` | Run an altoclef command directly (e.g. `@goto`) |
| `ConnectToServer(ip)` | Connect/reconnect to a server |
| `hasActiveTask()` | True if a task is running |
| `inGame()` | True if connected to a world |
| `getHealth()` | Player health (float) |
| `getTaskChain()` | Current task chain as list of strings |
| `getPlayersInfo(limit)` | Nearby players with threat data |

Full list: see [Py4jEntryPoint.java](../src/main/java/adris/altoclef/Py4jEntryPoint.java).

## Custom scripts

Put your own scripts in `scripts/custom/`. This directory is gitignored except for example files.
Output files (`result.txt`, etc.) are also gitignored.

## Tips

- Chat messages from the server come back via `onStrongChatMessage()` / `onWeakChatMessage()` callbacks
- The message queue has throttling to avoid kicks — if you need instant delivery, use `ExecuteCommand()` for altoclef commands
- For tungsten/baritone commands, `ChatMessage()` is the only way (they intercept the chat pipeline)
