# Autojoin — how it works and how to add one

## The idea

Autojoin is automatic entry into a minigame on a server. Two stages:
1. **Click the compass** in the lobby (opens a chest menu for choosing the mode)
2. **Click a slot** in the chest menu (picks the specific game)

## Where everything lives

| What | File |
|-----|------|
| Shared autojoin (SW/BW/MM) | `src/main/java/adris/altoclef/chains/GameMenuTaskChain.java` |
| SkyPvP autojoin | `src/main/java/adris/altoclef/tasks/multiplayer/minigames/SkyPvpTask.java` |
| Click on a custom item | `src/main/java/adris/altoclef/util/helpers/ItemHelper.java` — `clickCustomItem()` |
| Find a slot by name | `src/main/java/adris/altoclef/util/helpers/ItemHelper.java` — `getCustomItemSlot()` |
| Pipeline enum | `src/main/java/adris/altoclef/util/agent/Pipeline.java` (moved from `butler/`, fixed 2026-09-01) |
| autoJoin setting | `src/main/java/adris/altoclef/butler/ButlerConfig.java` |

## Two approaches

### 1. Via GameMenuTaskChain (SW, BW, MurderMystery)

`GameMenuTaskChain.getPriority()` does:
- Clicks the compass (`clickCustomItem("Выбор сервера", "Выбор лобби", "Выбор режима")` —
  the server's own menu item names, meaning "Server select", "Lobby select", "Mode select")
- Once the chest is open — looks for the "minigames" slot, then the slot for the specific game
- Returns priority 90 while the menu is open (blocks other chains)

Requires `ButlerConfig.autoJoin = true`.

### 2. Via the Task itself (SkyPvP)

`SkyPvpTask.onTick()` does everything itself:
- Detects the lobby by the presence of a "Выбор режима" ("Mode select") compass in the inventory
- Clicks the compass via `clickCustomItem`
- Once the chest is open — looks for the "SkyPvP" slot itself and clicks it

Does not depend on `autoJoin`. GameMenuTaskChain does NOT hold priority 90 for SkyPvP.

## How to add a new autojoin

### Option A: via GameMenuTaskChain (if the server is similar to SW/BW)

1. Add a pipeline to `Pipeline.java`
2. Add a case to the `GameMenuTaskChain` switch (line ~160):
   ```java
   case MyGame:
       ClickTitles = new String[]{"MyGame", "mygame"};
       break;
   ```
3. Add the pipeline to `isMinigamePipeline()`
4. Make sure the chest menu's title contains one of the strings in `isAutoJoinMenu`

### Option B: via a Task (if the server is non-standard)

1. In the task's `onTick()` — first check whether a chest with the needed title is open → click the slot
2. Then check the lobby → click the compass via `clickCustomItem`
3. **Do not** add handling to `GameMenuTaskChain` (otherwise priority 90 will block the task)

## Pitfalls

### `clickCustomItem` cannot be called while a screen is open
Inside there is a guard, `instanceof PlayerScreenHandler`. If a chest/any GUI is open — it returns false. Otherwise `forceEquipSlot` does a SWAP through someone else's screen handler and breaks the GUI.

### Server menu items are not movable
`clickCustomItem` for hotbar slots (0-8) switches `selectedSlot` instead of doing a SWAP. Servers block moving menu items, and the SWAP is silently rolled back by the server.

### `PlayerInteractionFixChain` closes the screen on a rotation change
If rotation changes by >0.1° while a screen is open → `closeScreen()`. That's why GameMenuTaskChain returns priority 90 — it blocks other chains from changing rotation. If a task handles the menu itself, it needs to handle the chest within 1-2 ticks, before rotation changes.

### `tryAvoidingInteractable` closes the screen
`LookHelper.tryAvoidingInteractable()` is called inside `clickCustomItem`. If a screen is open and the cursor is empty — it calls `closeScreen()`. The guard in `clickCustomItem` prevents this, but keep it in mind if you call `tryAvoidingInteractable` separately.

### `getCustomItemSlot` matches partial strings both ways
```java
checkLower.equals(itemName) || checkLower.contains(itemName) || itemName.contains(checkLower)
```
Searching for "SkyWars" will match "SkyWars", "SkyWars [клик]" ("SkyWars [click]"), and even
an item named "sw" (because "skywars".contains("sw")). Use strings that are unique enough.

### Chest-menu title — always a lowercase check
Titles are checked via `title.getString().toLowerCase().contains(...)`. Make sure the string in the code is lowercase.
