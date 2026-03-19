# Python Scripting

UnionClef exposes a Py4J bridge that lets you control the bot from Python.
Scripts live in the `scripts/` directory and use [uv](https://docs.astral.sh/uv/) for dependency management.

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
cd scripts
uv run example_goto.py          # default port 25333
uv run example_goto.py 25335    # custom port
```

## How it works

1. Launch Minecraft with the mod (`gradlew runClient` or drop the JAR into mods/)
2. The mod starts a Py4J gateway on port `25333` (configurable via `@set pythonGatewayPort <port>`)
3. Python connects to the gateway and gets an entry point with 100+ methods

## Auto-connect to server

Set `autoConnectServer` in `altoclef_settings.json`:

```json
{
  "autoConnectServer": "mc.example.com"
}
```

The bot will automatically connect to this server when the game launches.
Combined with `idleCommand`, you can have a fully automated startup:

```json
{
  "autoConnectServer": "mc.example.com",
  "autoReconnect": true,
  "autoRespawn": true,
  "idleCommand": "idle"
}
```

## Entry point methods (highlights)

| Method | Description |
|--------|-------------|
| `bot.ExecuteCommand(cmd)` | Run a bot command (e.g. `@goto 0 64 0`) |
| `bot.ConnectToServer(ip)` | Connect to a server by IP |
| `bot.ChatMessage(msg)` | Send a chat message |
| `bot.hasActiveTask()` | Check if a task is running |
| `bot.inGame()` | Check if the bot is in a world |
| `bot.getHealth()` | Get player health |
| `bot.getPlayersInfo(limit)` | Get nearby players info |
| `bot.getTaskChain()` | Get current task chain as list |

Full method list: see [Py4jEntryPoint.java](../src/main/java/adris/altoclef/Py4jEntryPoint.java).

## Writing a test script

```python
from py4j.java_gateway import JavaGateway

gw = JavaGateway()
bot = gw.entry_point

# Wait for game
while not bot.inGame():
    time.sleep(2)

# Run command and check result
bot.ExecuteCommand("@goto 100 64 100")
while bot.hasActiveTask():
    time.sleep(1)

alive = bot.getHealth() > 0
print("PASS" if alive else "FAIL")
```

## Tips

- Port conflicts: if you run multiple instances, the mod auto-increments the port by 2 and saves the new port to settings
- Use `bot.ConnectToServer("ip:port")` from Python to switch servers without restarting
- Chat messages from the server are forwarded via `onStrongChatMessage()` / `onWeakChatMessage()` callbacks
