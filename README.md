# UnionClef

Letting agents loose in block game.

An open platform for building AI agents that play Minecraft — pathfinding, combat, survival, multiplayer. The goal is to make it easy for researchers, developers, and tinkerers to plug their agents into the game and see what happens.

Built by merging **altoclef**, **baritone**, and **tungsten** into a single codebase. No submodules, no pre-built JARs, no tears.

![Kill&loot](https://github.com/3ndetz/autoclef/assets/30196290/7377ec79-1c3d-493b-9a1d-5d701f19d9c9)

![qwenie](https://github.com/user-attachments/assets/64b98492-ceca-410f-b3bc-efbd8ea09dcb)

## What's inside

| Module | What it does |
|--------|-------------|
| **altoclef** (root) | Autonomous bot — speedruns, PvP, SkyWars, Python scripting via Py4J |
| **baritone/** | Pathfinding engine — mining, building, navigation, elytra flight |
| **tungsten/** | A* pathfinder that doesn't break blocks — follows players, PvP movement |

**Minecraft 1.21** / **Fabric** / **Java 21**

> **[How to build & run →](docs/DEVELOP.md)** | **[How to release →](docs/RELEASE.md)** | **[Multi-version →](docs/MULTIVERSIONING.md)**

## Quick start

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

Baritone that can't build/break blocks and looks like a NASA computing program.

![Tungsten pathfinding](https://raw.githubusercontent.com/3ndetz/Tungsten/altoclef-compat/assets/README/Tungsten2.gif)

</details>

## Project structure

```
unionclef/
├── src/main/java/          altoclef source (bot logic, commands, tasks)
├── src/main/resources/     fabric.mod.json, mixins, assets
├── baritone/               baritone source (pathfinding, remapped to yarn)
│   └── src/main/java/      all baritone code (api + core + mixins)
├── tungsten/               tungsten source (A* movement, player following)
│   └── src/main/java/      tungsten code
├── root.gradle.kts         root build config
├── gradle.properties       versions & settings
└── docs/
    └── DEVELOP.md          build & run instructions
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

### baritone

1. Origin: **[cabaletta/baritone](https://github.com/cabaletta/baritone)** (by leijurv & Brady) →
2. Patched by each altoclef maintainer along the way (GauchoMatrero → MiranCZ → 3ndetz) →
3. Remapped mojmap → yarn & merged into: **unionclef**

### tungsten

1. Origin: **[CaptainWutax/Tungsten](https://github.com/CaptainWutax/Tungsten)** →
2. Fork: **[Hackerokuz/Tungsten](https://github.com/Hackerokuz/Tungsten)** (crash fixes, followPlayer) →
3. Fork: **[3ndetz/Tungsten](https://github.com/3ndetz/Tungsten)** (altoclef integration) →
4. Merged into: **unionclef**

## License

GPL-3.0 — see [LICENSE](LICENSE).

Incorporates code from: baritone (LGPL-3.0), altoclef (MIT), tungsten (CC0-1.0).
