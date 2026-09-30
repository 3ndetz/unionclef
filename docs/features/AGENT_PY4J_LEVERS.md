# Agent py4j levers — map of the cognitive agent's workspace

This is a catalog of **levers** the cognitive agent (Claude via py4j/MCP) uses to
control the bot. Principle (AGENTS.md): the mod provides primitives and data, **the
agent decides** where/when/what. Each method is on the `entry_point` of the py4j
gateway (port 25333, `docker exec ... python3`).

Composition philosophy: **see → go → act**. `getGameState` (perception) →
`gotoXYZ`/`pathStatus` (movement) → combat/building/menus (action). The agent holds
the strategy (where, whom, when); the mod executes the mechanics.

`Map` returns are a dict in Python. Coordinates are world coordinates (server-agnostic).

**Two transports, one set of levers:** py4j (port 25333, for test runners) and
**MCP** — the mod hosts it itself at `http://<lan-ip>:25350/mcp` (Streamable HTTP,
Settings.mcpPort). MCP wraps exactly these methods (single source), so Claude
drives directly over LAN. Below are the levers themselves (names match the
MCP tools).

**Auth (TODOS.md C7.3, fixed 2026-09-03):** every MCP request needs
`Authorization: Bearer <token>`. The token is generated once on first start and saved to
`altoclef_settings.json` under `mcpAuthToken` — read it from there (or the "MCP server
started..." startup log line) to configure a client. A request with a missing or wrong
token gets a plain 401, no other response content.

## Perception (what's happening)

| Lever | What it gives | When |
|---|---|---|
| `getGameState()` | `self`(hp/maxHp/armor/pos/onGround/held/blocks) + `players[]`(name/pos/distance/hp/sprinting, sorted by distance) + `beds[]`(beds within r=40) | Main combat "eye" — no screenshots needed. Read every decision cycle |
| `inGame()` | whether in-game (bool) | Check before acting |
| `getHealth()` / `getHeldItem()` | HP / held item id | Quick point checks |
| `nearestPlayersInfo(limit, asString)` / `getPlayersInfo(limit)` | nearest players | If you only need the player list |
| `getCrosshairTarget()` | what the crosshair is looking at (block/entity) | Before hitting/placing — what's under the crosshair |
| `reachability(playerName)` | whether the player is reachable (reach/LOS/angle) | Gate before attacking |
| `canReach(x,y,z,withBreaking)` | whether the cell is reachable (reached/pathSize/breaks/endDistance) | Route/breakthrough planning |
| `canBreakBlock(x,y,z)` | whether breaking is allowed (deny-list/zones/block-entity) | Before breaking |
| `getBlockAt(x,y,z)` / `getGroundBlock()` | block at a point / underfoot | Terrain scouting |
| `getOpenScreen()` | the open screen (title + all slots: id/name/count) | Reading a shop/chest menu |
| `getInventoryFull()` / `inventorySpace()` | inventory / free slots + block count by type | Resource planning (bridge no longer than supply) |
| `getRecentChat(n)` | last n chat messages | Event/error markers |
| `getScreenshot()` | PNG bytes of the frame | When you need "eyes" on pixels (visual puzzles etc.) |

## Movement (going to a target)

| Lever | What it does | When |
|---|---|---|
| `gotoXYZ(x,y,z)` | navigation to a coordinate via the **tungsten** pathfinder (walk/parkour/bridge). Fire-and-poll | The main "get there" lever. Also used to reposition for far fillSelection cells |
| `pathStatus()` | `busy`/`pos`/`distance`(to the gotoXYZ target)/`arrived`(now reads `FastNavigator.ARRIVE_DIST`, currently `<=2.0`, not a separately guessed number) | Poll after gotoXYZ until `arrived`, then act |
| `gotoFar(x,y,z,horizon)` | **far target** receding-horizon: one segment <=horizon toward the target; poll gotoFar→pathStatus→gotoFar until `finalSegment` | A very long path without freezing the pathfinder |
| `stopPathing()` | total stop (tungsten `;stop` + altoclef `@stop`) | Abort navigation/task |
| `hasActiveTask()` | whether the bot is busy with a task (bool) | General busy check |
| `bridgeForward(dir, n)` / `bridgeTo(x,y,z)` | godbridge (continuous floor paving) in a direction/to a target | Bridging a gap (bedwars — to another island). Keep a block in hand |
| `bridgeActive()` / `bridgePlaced()` | whether the bridge is active / how many blocks placed | Polling the godbridge |

⛔ CORRECTED 2026-09-01: this used to say "for complex terrain there's baritone/shredder via
`@goto`" — that is no longer true and contradicted the row above in this same table. Since the
"G-0" migration (2026-08-24, see `TODOS.md`) `baritone`/`shredder` are not compiled at all, for
any terrain — `ExecuteCommand("@goto x y z")` (altoclef command) and `gotoXYZ` (py4j lever) BOTH
go through tungsten, there is no other option.

⛔ CORRECTED 2026-09-02: `arrived` in `pathStatus()` held a separately guessed threshold of
`<1.5`, unrelated to the engine — `FastNavigator` itself counts arrival as `<=2.0`. A live run
(the agent loop `gotoXYZ → pathStatus until arrived`, exactly the one described in the row above)
would get stuck FOREVER at distance 1.5-2.0: the engine had ALREADY arrived and stopped the
walker, while the API kept lying `arrived:false`. The fix (commit `099b461e`, `TODOS.md`) —
`pathStatus` now READS `FastNavigator.ARRIVE_DIST` instead of its own copy of the number (the
rule "one side must READ the other", not duplicate the constant). The fix was NOT confirmed on
the bench (no RCON/`docker exec`/`gradlew` in this room at the time of the change, see `TODOS.md`
C8.1) — if you see the hang at distance 1.5-2.0 again after deploying a new build, it means the
fix didn't make it into the deployed jar, not that the diagnosis is wrong.

## Aim and rotation (anti-cheat safe)

All rotations go through the **mouse-pipeline** (like a physical mouse) — NEVER
setYaw/setPitch (anti-cheats flag it).

| Lever | What it does |
|---|---|
| `lookAt(x,y,z)` / `lookAtPlayer(name)` | aim the crosshair at a point/player |
| `rotateCamera(dYaw, dPitch)` | relative camera rotation |

## Combat (primitives; the agent holds the strategy)

| Lever | What it does |
|---|---|
| `mouseClick("left"/"right"/"middle")` | mouse click (hit / use) |
| `interactCrosshairEntity()` | right-click the entity under the crosshair |
| `interactEntity(name, use)` | attack/use a specific player |
| `attackPlayer(name)` / `isAttacking(name)` | set/check the attack target (altoclef brain) |
| `shieldBlock(ticks)` | raise the shield for N ticks |
| `solveArrowAim(name)` | bow ballistics with lead (yaw/pitch/charge) — without shooting |
| `shootArrowAt(name)` | bow shot with trajectory (aim→charge→track→release) |
| `useHeldItem()` | use the held item (food/pearl/potion) |
| `punk(name)` | hunt a player by name (tungsten: A* approach + aura) |
| `punkAny(allow, avoid)` | **multi-target**: hit the NEAREST from `allow` (empty=any), leaving `avoid` alone; auto-retarget. The agent decides who to hit |
| `punkAvoid(avoid)` | update the avoid list on the fly (who not to hit) |
| `punkStatus()` / `punkStop()` | current target + activity / stop combat |

## Building and WorldEdit (real placement, works in survival)

| Lever | What it does | Return |
|---|---|---|
| `placeBlockAt(x,y,z)` | place a block in a cell (aim at the support face, interactBlock) | ok/placed/support/side |
| `placeBlockLooking()` | place a block wherever the crosshair is looking | ok/placed |
| `select(x1,y1,z1,x2,y2,z2)` | set a WorldEdit region (yellow highlight) | min/max/volume |
| `clearSelection()` | clear the selection | ok |
| `fillSelection(block)` | **//set** — fill the region with a block (bottom-up, within reach, capped at 96/call). Equips the named block from the hotbar | filled/remaining/already/truncated/complete |
| `wallsSelection(block)` | **//walls** — 4 vertical walls of the region (hollow center) | same as fillSelection |
| `buildDefenseAround(x,y,z)` | protective shell around a point (sides+roof) — box in a bed | placed/remaining |
| `canBreakBlock(x,y,z)` | whether breaking is allowed (deny-list/zones/privates/altoclef) | bool |
| `canPlaceBlock(x,y,z)` | whether placing is allowed (policy + replaceable) | canPlace/policyAllows/replaceable |
| `markProtectedArea(x,y,z,r)` | mark a private/claim — a cube of radius r around a point; the mod won't break or build there, goes around | zone/protectedZones |
| `clearProtectedAreas()` | clear all runtime privates (place+break deny) | ok |

`fillSelection`/`wallsSelection` return `remaining`>0 if some cells are out of
reach — the agent does `gotoXYZ` closer and calls again (the agent orchestrates).

## Menus, shop, manual input

| Lever | What it does |
|---|---|
| `clickMenuByName(names, button, action, timeoutMs)` | click a menu slot **by item name** (shop/hub navigation) — server-agnostic. Buying = open the shop + this method |
| `clickUiSlot(slot, button, action)` | click a menu slot by index |
| `selectHotbar(slot)` | select hotbar slot 0-8 |
| `getOpenScreen()` | read the open menu (see Perception) |
| `closeOpenScreen()` | close the screen |
| `screenClickAt(x,y,button,scaled)` | click at SCREEN coordinates (arbitrary GUI) |
| `tapKey(name)` / `holdKey(name,ms)` | press/hold a key |

## Commands and communication

| Lever | What it does |
|---|---|
| `ExecuteCommand("@...")` | altoclef command (`@goto`, `@get`, `@game`, `@stop`) |
| `ChatMessage(";...")` / `ChatMessage("text")` | tungsten command (`;goto`, `;bridge`, `;stop`) or chat. Tungsten intercepts exactly the chat send |
| `ConnectToServer(ip)` | connect to a server |

---

Update when adding levers. The single source of descriptions is the javadoc on
`Py4jEntryPoint` methods; the MCP server (see the section above — already implemented, not
"future", line corrected 2026-09-01: it used to contradict this same file's own MCP description
at the top) wraps them, WITHOUT duplicating logic.
