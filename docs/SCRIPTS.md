# Scripting & Test Automation

UnionClef has a Py4J bridge that lets external programs (Python, agents, CI) control the bot at runtime.

## What for

- **Manual testing**: edit code → run Minecraft → run a script → see result in a file
- **Agent integration**: Python agents connect via Py4J and drive the bot
- **Repeatable scenarios**: write a script once, run it after every change

## How it works

```
┌──────────────┐    Py4J (port 25333)    ┌──────────────┐
│ Python script │ ◄─────────────────────► │  Minecraft   │
│  (scripts/)   │    100+ methods         │  + altoclef  │
└──────┬───────┘                          └──────────────┘
       │
       ▼
  result.txt (PASS/FAIL)
```

The mod exposes a gateway with methods for executing commands, reading game state, connecting to servers, etc. All four command prefixes work from Python:

| Prefix | Target | Example |
|--------|--------|---------|
| `@` | altoclef | `@goto 0 64 0` |
| `#` | baritone (shredder) | `#goto 0 64 0` |
| `;` | tungsten | `;goto 100 64 100` |
| `/` | server command | `/warp park` |
| _(none)_ | chat message | `hello` |

## Typical workflow

```bash
# 1. Launch Minecraft (once)
gradlew runClient

# 2. Edit your script in scripts/custom/
# 3. Run it
cd scripts
uv run custom/my_test.py

# 4. Check the output
cat custom/result.txt

# 5. Edit code, hot-swap or relaunch, repeat from step 3
```

For auto-connect on launch, set `autoConnectServer` in `altoclef_settings.json`:

```json
{ "autoConnectServer": "mc.example.com" }
```

## Full docs

Python setup, uv, API reference, examples → **[scripts/README.md](../scripts/README.md)**
