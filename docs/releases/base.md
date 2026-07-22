## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for the Minecraft version listed in the release title
2. Drop the release JAR into your `.minecraft/mods/` folder
3. Launch Minecraft

No separate mods install needed — everything is bundled into the main UnionClef JARfile. But if you see another JARs like `tungsten-**.jar` or `shredder-**.jar`, you can use it separetely if you want to, but NOT with UnionClef because it combines them all!

## Modules

UnionClef bundles three systems, each with its own command prefix:

| Module | Prefix | What it does |
| --- | --- | --- |
| **Tungsten** | `;` | A* movement — precise physics-simulated pathfinding |
| **Shredder** | `#` | Block-level pathfinding (baritone fork, bridging, mining) |
| **AltoCef** | `@` | High-level bot tasks (get items, kill mobs, survive) |

## Quick start commands

```
;goto <x> <y> <z>          — tungsten: walk/jump to coordinates
;followPlayer <nick>        — tungsten: follow a player
;stop                       — tungsten: stop current action
;settings debugTime true    — tungsten: enable profiling output

#goto <x> <y> <z>          — shredder: pathfind to coordinates
#stop                       — shredder: stop

@goto <x> <y> <z>          — altoclef: smart goto (avoids mobs, eats, etc.)
```
