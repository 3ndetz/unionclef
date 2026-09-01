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

The mod exposes a gateway with methods for executing commands, reading game state, connecting to servers, etc.

⛔ STALE, FLAGGED 2026-09-01: this table used to say "all four command prefixes work" including
`#`. Since the "G-0" migration (2026-08-24, see `TODOS.md`), `shredder/` is source-reference-only
and NOT compiled — its `#`-prefixed command registration (`shredder/.../GotoCommand.java` and the
rest of `baritone.command.defaults`) isn't part of the built JAR at all, so `#` commands do
nothing. tungsten is the only pathfinder now; use `;` for everything that table's `#` row used to
cover.

| Prefix | Target | Example |
|--------|--------|---------|
| `@` | altoclef | `@goto 0 64 0` |
| ~~`#`~~ | ~~baritone (shredder)~~ — **retired, not compiled, does nothing** | — |
| `;` | tungsten (the only pathfinder) | `;goto 100 64 100` |
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
