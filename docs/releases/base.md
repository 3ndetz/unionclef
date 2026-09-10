## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for the Minecraft version listed in the release title
2. Drop the release JAR into your `.minecraft/mods/` folder
3. Launch Minecraft

No separate mods install needed — everything is bundled into the main UnionClef JAR. If you see a separate `tungsten-**.jar`, do not install it next to UnionClef: UnionClef already contains it.

## Modules

UnionClef bundles two systems, each with its own command prefix:

| Module | Prefix | What it does |
| --- | --- | --- |
| **Tungsten** | `;` | Movement — physics-simulated pathfinding, block-space search, bridging, mining |
| **AltoClef** | `@` | High-level bot tasks (get items, kill mobs, survive) |

## Quick start commands

```
;goto <x> <y> <z>          — tungsten: walk/jump to coordinates
;followPlayer <nick>        — tungsten: follow a player
;stop                       — tungsten: stop current action
;settings debugTime true    — tungsten: enable profiling output

@goto <x> <y> <z>          — altoclef: smart goto (avoids mobs, eats, etc.)
```
