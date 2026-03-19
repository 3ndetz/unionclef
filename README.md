# UnionClef

Letting agents loose in block game.

![Kill&loot](https://github.com/3ndetz/autoclef/assets/30196290/7377ec79-1c3d-493b-9a1d-5d701f19d9c9)


An open platform for building AI agents that play Minecraft — pathfinding, combat, survival, multiplayer. The goal is to make it easy for researchers, developers, and tinkerers to plug their agents into the game and see what happens.

![qwenie](https://github.com/user-attachments/assets/64b98492-ceca-410f-b3bc-efbd8ea09dcb)

Built by merging **altoclef**, **shredder**, and **tungsten** into a single codebase. No submodules, no pre-built JARs, no tears.

## What's inside

| Module | What it does |
|--------|-------------|
| **altoclef** (root) | Autonomous bot — speedruns, PvP, SkyWars, Python scripting via Py4J |
| **shredder/** | Pathfinder v2 — fork of baritone with WindMouse camera, tungsten integration, human-like movement |
| **tungsten/** | A* pathfinder that doesn't break blocks — complex parkour, follows players, PvP movement |
| ~~baritone/~~ | Legacy pathfinding code, kept as reference. Replaced by shredder |

**Minecraft 1.21** / **Fabric** / **Java 21**

> **[How to build & run →](docs/DEVELOP.md)** | **[How to release →](docs/RELEASE.md)** | **[Multi-version →](docs/MULTIVERSIONING.md)** | **[Python scripting →](docs/SCRIPTS.md)**

## Features

> (table is not ready yet, TODO. Functions more than this presented. TODO find more organic, suitable and sexy form for this features table)


| Feature | Description | Ready |
|---|---|---|
| **MLG** |
| Enderpearl clutch ![alt text](assets/README/EnderClutch.gif) | TP with enderpearl when pursue target. Save self with enderpearl when dropped from edge. | 3/3 ✅ |
| Arrow dodger ![alt text](assets/README/AutoclefDodging.gif) | Epic incoming arrow dodging. If has shielf - uses it. | 1/1 ✅ |
| `@test mace` ![alt text](assets/README/MaceClutch.gif) | 😎 | 1/1 ✅ |
| `#bridgingMode jump` ![alt text](assets/README/ShredderBridging256.gif) | Super-fast bridging. (To cancel, you can change mode to `slow`, `standart` or `back-jump`) | 1/3 ❌ TODO |
| **PvP** |
| Attacking bot `@punk` | Handles close target battle. [Wind-mouse](https://github.com/arevi/wind-mouse) based rotations. Brokes shields (axe). Uses own shield. Combines ranging and melee attacks automatically, pursues targets. Using mace from the height | 3/3 ✅ |
| Shooting bot `@shoot` | Handles ranged target battle with 2 types of angle (rapid-fire, sniper, artillery) | 3/3 ✅ |
| Pursuing bot | Pursue parkouring targets. Slow for now. | 2/3 ⚠️ |
| **Minigames** |
| Skywars `@game sw` | SkyWars (fails exploration, buggy) | 3/5 ⚠️ |
| Skywars `@game bw` | BedWars (only bed protect) | 3/5 ⚠️ |
| Skywars `@game skypvp` | SkyPvP (on one server, but non-redactable spawn) | 4/5 ✅ |
| Skywars `@game mm` | MurderMystery | 5/5 ✅ |
| **Building** |
| `@grave <text>`, `@sign <text>` | New structures to build | 2/2 ✅ |
| Privated regions support | Temporal block placement and removal locks | 4/5 ✅ |
| `@schematic <schematic>` | Schematic integration | 0/3 ❌ TODO |
| **Multiplayer** |
| Autologin (`@set multiplayer_password <password>`) | Autologin and autoregister | 3/3 ✅ |
| **Agentic** |
| Python integration | Py4J configurable two-way interface. Port configures via `@set pythonGatewayPort <port>`. Supports multi-instance launching. Rich contextual and method base (see `adris.altoclef.Py4JEntryPoint` class) for agents, including live-screenshot support. | 3/3 ✅ |
| Agentic commands | `@check_block`, `@check_player` | 3/3 ✅ |
| Agentic MCP server | MCP for AI agents endpoint on java-side | 0/3 ❌ TODO |
| **Comfort** |
| Command suggestions | Rich chat commands suggestions `@help` | 1/1 ✅ |
| Monorepo structure | Multi-versioned structured mono-repo with easy-to-work with any of integrated mod | 1/1 ✅ |
| Removed naughty prints from baritone | | 1/1 ✅ |

> Vote for the new features, report for bugs in the [issues](https://github.com/3ndetz/unionclef/issues).

## Quick start

1. Drop the latest JAR from [releases](https://github.com/3ndetz/unionclef/releases) into your Minecraft `mods/` folder and launch with Fabric

    > Ensure you have the correct Minecraft version for the release you download

2. Type `@help` in chat for the list of commands

## Development

Quick start for development — clone the repo, build, and run:

```bash
git clone https://github.com/3ndetz/unionclef
cd unionclef
gradlew compileJava     # compiles everything
gradlew runClient       # launches Minecraft
```

See **[docs/DEVELOP.md](docs/DEVELOP.md)** for debug setup, hot-swap, and troubleshooting.

## Demo

<details><summary>SkyWars bot in action</summary>

### Looting chests
![Looting chests](https://github.com/3ndetz/autoclef/assets/30196290/aa44993e-a7e8-4285-bba6-a690b0ac29a2)

### Gapple & EnderPearl
![Gapple & EnderPearl](https://github.com/3ndetz/autoclef/assets/30196290/0d3e73d2-2e1f-40e7-a53b-be43d3d9335d)

### Kill & Loot
![Kill & Loot](https://github.com/3ndetz/autoclef/assets/30196290/7377ec79-1c3d-493b-9a1d-5d701f19d9c9)

### Bow
![Bow](https://github.com/3ndetz/autoclef/assets/30196290/9bae7aee-f535-4704-83a3-3dd9ec885a80)

</details>

<details><summary>Tungsten pathfinding</summary>

Pathfinder that can't build/break blocks and looks like a NASA computing program.

![Tungsten pathfinding](https://raw.githubusercontent.com/3ndetz/Tungsten/altoclef-compat/assets/README/Tungsten2.gif)

</details>

## Project structure

```
unionclef/
├── src/main/java/          altoclef source (bot logic, commands, tasks)
├── src/main/resources/     fabric.mod.json, mixins, assets
├── shredder/               pathfinder v2 (fork of baritone + tungsten bridge)
│   └── src/main/java/      shredder code (baritone.* packages)
├── tungsten/               tungsten source (A* movement, player following)
│   └── src/main/java/      tungsten code
├── baritone/               legacy pathfinding (kept as reference, not used)
│   └── src/main/java/      original baritone code (remapped to yarn)
├── scripts/                python scripting via Py4J (uv project)
├── root.gradle.kts         root build config
├── gradle.properties       versions & settings
└── docs/
    ├── DEVELOP.md          build & run instructions
    └── SCRIPTS.md          python scripting guide
├── README.md               you are here
└── TODOS.md                project TODOs and roadmap
```

## Fork History

### altoclef

1. Origin: **[gaucho-matrero/altoclef](https://github.com/gaucho-matrero/altoclef)** →
2. Fork: **[MarvionKirito/altoclef](https://github.com/MarvionKirito/altoclef)** →
3. Fork: **[MiranCZ/altoclef](https://github.com/MiranCZ/altoclef)** (multi-version support, bug fixes) →
4. Fork: **[3ndetz/autoclef](https://github.com/3ndetz/autoclef)** (multiplayer, SkyWars, Python bridge) →
5. Merged into: **unionclef**

### shredder

Fork of baritone, rebuilt as the primary pathfinder. Keeps `baritone.*` packages for API compatibility but adds WindMouse camera smoothing, human-like movement entropy, and a tungsten bridge that delegates complex parkour segments (no block breaking/placing) to tungsten's A* executor.

1. Origin: **[cabaletta/baritone](https://github.com/cabaletta/baritone)** (by leijurv & Brady) →
2. Patched by altoclef maintainers (GauchoMatrero → MiranCZ → 3ndetz) →
3. Remapped mojmap → yarn →
4. Forked as **shredder** with WindMouse + tungsten integration →
5. Merged into: **unionclef**

### baritone (legacy)

Original pathfinding engine. Kept in the repo as reference code — all active pathfinding now goes through shredder.

1. Origin: **[cabaletta/baritone](https://github.com/cabaletta/baritone)** (by leijurv & Brady) →
2. Remapped mojmap → yarn & merged into: **unionclef** →
3. Superseded by **shredder**

### tungsten

1. Origin: **[CaptainWutax/Tungsten](https://github.com/CaptainWutax/Tungsten)** →
2. Fork: **[Hackerokuz/Tungsten](https://github.com/Hackerokuz/Tungsten)** (crash fixes, followPlayer) →
3. Fork: **[3ndetz/Tungsten](https://github.com/3ndetz/Tungsten)** (altoclef integration) →
4. Merged into: **unionclef**

## License

GPL-3.0 — see [LICENSE](LICENSE).

Incorporates code from: baritone/shredder (LGPL-3.0), altoclef (MIT), tungsten (CC0-1.0).
