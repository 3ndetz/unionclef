# Shredder → 1.21.11 Migration Guide

⛔ FATE OF THIS DOCUMENT, RECORDED 2026-09-01: the migration described below did NOT happen in the
form planned here — instead of bringing shredder up to 1.21.11, the "G-0" migration (2026-08-24,
see `TODOS.md`) took shredder out of the build COMPLETELY and on ALL versions, not just 1.21.11.
Today `settings.gradle.kts` does not compile shredder at all (not noop mode — the module itself
is absent from the build on every version), and `#` commands are not registered anywhere. Tungsten
now drives all pathfinding on every version by itself, including 1.21.11. Below is a snapshot of
the state from when the noop workaround was still the current plan; the document's value is now
historical (it explains WHY movement on 1.21.11 used to not work), not a guide to action.

## Current state (at time of writing, outdated — see the note above)

Shredder (our baritone fork) is compiled for MC 1.21.1 (yarn mappings).
On 1.21.11 it runs in **noop mode**: `BaritoneAPI` catches `NoClassDefFoundError`
during `BaritoneProvider` initialization and substitutes `NoopBaritoneProvider` —
a dynamic proxy where every method returns safe defaults (null/false/emptyList).

### What works on 1.21.11 right now

- Minecraft launches, the world loads
- Altoclef commands (`@help`, `@goto`, etc.) — are accepted, but without pathfinding
- Tab-complete for `@` commands — works (moved into altoclef's own mixin)
- Command syntax highlighting — works
- Tungsten (line rendering, A* movement) — works independently

### What does NOT work

- **Pathfinding** — fully disabled (noop)
- **`#` commands from shredder** — not registered, not executed
- **All altoclef tasks that depend on baritone** — get noop responses, do nothing
  - GoToTask, MineTask, GetItemTask, KillTask, etc.
- **TungstenBridge** — is not activated (PathExecutor noop)
- **God Bridge / Jump Bridge** — do not work
- **Chunk caching** — does not work

## What the migration needs

### The key problem: mappings

Upstream [cabaletta/baritone](https://github.com/cabaletta/baritone) already has a branch
for 1.21.11, but it uses **mojmap** (Mojang official mappings). Our shredder is **yarn**.

Between 1.21.1 and 1.21.11 Mojang renamed/changed a number of MC classes and methods.
Baritone upstream has already adapted its code to these changes (on mojmap).
We need to carry those adaptations over, but in yarn terms.

### Three migration strategies

#### Strategy A: Adapt upstream's diff to our code

**Idea**: take the diff between baritone 1.21.1 and 1.21.11 (on mojmap), translate
the changed names into yarn, and apply it to our shredder surgically.

**Pros**:
- Minimal amount of work — touches only what changed
- Keeps all our custom features (TungstenBridge, GodBridge, jump bridge)
- No need to re-migrate 345 files

**Cons**:
- Need to manually map every mojmap name → yarn name in the diff
- If upstream changed architecture (not just names), there may be conflicts
- Risk of missing non-obvious changes

**Complexity estimate**: medium. A good option if the diff between versions is small.

#### Strategy B: Re-migrate upstream 1.21.11 into yarn from scratch, then merge our features in

**Idea**: take a clean cabaletta/baritone 1.21.11 branch (mojmap), run it
through `migrateMappings` into yarn (as was done when shredder was created), and then
cherry-pick/merge our custom changes on top.

**Pros**:
- Clean base — guaranteed compatible with 1.21.11
- `migrateMappings` automates most of the renaming
- Easier to verify correctness

**Cons**:
- `migrateMappings` is not perfect — some code will need manual fixing
- Need to reintroduce ALL our custom changes from scratch (something could get lost)
- Our files differ structurally from upstream — the merge will not be trivial

**Complexity estimate**: high. But the result is more reliable.

#### Strategy C: Take upstream 1.21.11 as the base, port our features on top

**Idea**: use cabaletta/baritone 1.21.11 AS IS (mojmap, or migrate
to yarn), and bring in our additions as patches on top.

**Pros**:
- The cleanest base, 100% upstream compatibility
- Easier to maintain going forward (upstream updates → merge)

**Cons**:
- The largest amount of manual work
- Need to port all custom features again from scratch
- If mojmap is kept — compat layers are needed for altoclef (yarn)
- If migrated to yarn — double the work

**Complexity estimate**: very high. Only makes sense if we plan
to sync with upstream regularly.

### Recommendation

**Strategy A** is the most pragmatic choice. Our custom changes touch
~10 files out of 345. The remaining 335 are upstream baritone code, already on yarn.
It is enough to:

1. Get the diff between cabaletta/baritone 1.21.1 and 1.21.11
2. Translate mojmap names to yarn (mapping table below)
3. Apply the changes to our shredder
4. Check that our features did not break

## Our custom files (delta from upstream)

### New files (absent from upstream)

| File | Purpose |
|------|------------|
| `baritone/tungsten/TungstenBridge.java` | Bridge to tungsten physics movement |
| `baritone/utils/GodBridgeClickHelper.java` | Render-frame jitter clicks for god bridge |
| `baritone/api/noop/NoopBaritone.java` | Noop proxy for incompatible versions |
| `baritone/api/noop/NoopBaritoneProvider.java` | Noop provider |

### Modified files (differ from upstream)

| File | What changed |
|------|-------------|
| `baritone/pathing/path/PathExecutor.java` | TungstenBridge integration, jump bridge state machine |
| `baritone/pathing/movement/movements/MovementTraverse.java` | God bridge mode |
| `baritone/api/Settings.java` | +5 settings: bridgingMode, godBridgeEdgeDistance, useTungsten, tungstenMinSegment, experimentalPathfinding |
| `baritone/launch/mixins/MixinMinecraft.java` | Render-frame hook for GodBridgeClickHelper, joinWorld moved |
| `baritone/api/BaritoneAPI.java` | Noop fallback on initialization error |
| `baritone/BaritoneProvider.java` | Noop-aware initialization |

### Registered mixins (10 of them)

All in `mixins.shredder.json`:
MixinChunkArray, MixinClientChunkProvider, MixinClientPlayNetHandler,
MixinCommandSuggestionHelper, MixinEntity, MixinFireworkRocketEntity,
MixinItemStack, MixinLivingEntity, MixinMinecraft, MixinNetworkManager.

Checked: all target methods exist in the 1.21.11 yarn mappings.
The mixins themselves are compatible — the problem is in the initialization of the core classes.

## Step-by-step migration plan (strategy A)

### Step 1: Get the upstream diff

```bash
# Clone upstream baritone
git clone https://github.com/cabaletta/baritone.git /tmp/baritone-upstream
cd /tmp/baritone-upstream

# Find the branches/tags for 1.21.1 and 1.21.11
git branch -r | grep 1.21

# Get the diff
git diff <1.21.1-branch>..<1.21.11-branch> -- src/main/java/ > upstream-diff.patch
```

### Step 2: Build the mojmap → yarn mapping table

For each renamed class/method in the diff, find the yarn equivalent.
Use the [Yarn browser](https://mappings.dev/) or the tiny file:
`versions/1.21.11/.gradle/loom-cache/source_mappings/*.tiny`

Known mojmap → yarn differences:
- `Minecraft` → `MinecraftClient`
- `LocalPlayer` → `ClientPlayerEntity`
- `MultiPlayerGameMode` → `ClientPlayerInteractionManager`
- `Connection` → `ClientConnection`
- `Level` → `World`
- `net.minecraft.core.BlockPos` → `net.minecraft.util.math.BlockPos`
- Etc. — the full list needs to be built from the diff

### Step 3: Apply the changes to shredder

For each changed file in the upstream diff:
1. Find the corresponding file in `shredder/src/main/java/`
2. Translate mojmap names → yarn
3. Apply the change
4. If the file is on our "modified" list — merge carefully

### Step 4: Check initialization

Make sure `BaritoneProvider` creates `Baritone` without errors on 1.21.11.
If any MC classes changed structurally — fix it.

### Step 5: Check the mixins

All 10 registered mixins are already compatible on their target methods.
But if upstream added new mixins for 1.21.11 — port those too.

### Step 6: Testing

- Launch on 1.21.11, join a world
- `#goto 100 64 100` — basic pathfinding
- `#mine diamond_ore` — mining
- God bridge on flat ground
- TungstenBridge delegation on flat sections

## Upstream diff reconnaissance (04.04.2026)

### cabaletta/baritone branches

Existing branches: `1.21`, `1.21.1`, `1.21.3`, `1.21.4`, `1.21.5`, `1.21.8`,
`1.21.10`, `1.21.11`. A clean linear chain, no divergences.
No tags for 1.21.x — diff by branch only.

### Overall diff `1.21.1...1.21.11`

- **65 commits**, 0 behind
- **76 files** changed (out of ~345 in baritone)
- **+840 / -615 lines** (net +225)

### Incremental steps

| Step             | Commits | Files |
| --------------- | ------- | ------ |
| 1.21.1 → 1.21.3 | 8 | 29 |
| 1.21.3 → 1.21.4 | 10 | 7 |
| 1.21.4 → 1.21.5 | 15 | 38 |
| 1.21.5 → 1.21.8 | 10 | 12 |
| 1.21.8 → 1.21.10 | 8 | 13 |
| 1.21.10 → 1.21.11 | 14 | 41 |

Two big jumps: **1.21.4→1.21.5** (38 files) and **1.21.10→1.21.11** (41 files).

### Key areas of change

**Rendering (the bulk of it):**
- `IRenderer.java` — +141/-57 (major rework)
- `PathRenderer.java` — +97/-62 (major rework)
- 4 new files: `MixinRenderPipelines`, `MixinRenderType`, `IRenderPipelines`, `IRenderType`
- ⚠️ The render pipeline was reworked in MC 1.21.11 — this is the most labor-intensive part
  of the migration. Upstream added 4 new files (mixins + accessors) specifically for this.

**Player input/movement:**
- `PlayerMovementInput.java` — +28/-17

**Tools & inventory:**
- `ToolSet.java` — +35/-18
- `InventoryBehavior.java` — +15/-14

**Block handling:**
- `BlockOptionalMeta.java` — +33/-51
- `ChunkPacker.java` — +10/-16
- `BaritoneToast.java` — +4/-56 (simplified)

**Schematics:**
- `LitematicaSchematic.java`, `MCEditSchematic.java`, `SpongeSchematic.java` — minor edits

**Mixins:**
- Updated: `MixinClientPlayerEntity`, `MixinLivingEntity`, `MixinScreen`,
  `MixinWorldRenderer`, `MixinMinecraft`, `MixinNetworkManager`, `MixinEntityRenderManager`
- 2 new mixins in `mixins.baritone.json`

**Build/config:**
- `gradle.properties`, `build.gradle`, `fabric.mod.json` — version bumps

### Conclusions from the recon

1. **Moderate volume.** 76 files, but the real substance is 10-15 files. The rest are minor
   import/version edits, API tweaks.
2. **Strategy A confirmed** as optimal. The diff is surveyable, no architectural breaks.
3. **Rendering is the most labor-intensive part.** IRenderer/PathRenderer are heavily
   reworked, plus 4 new files. MC 1.21.11 changed the render pipeline.
4. **A preprocessor** is worth adding to shredder for multi-version, the infrastructure
   already exists in the project.
5. **All names in the upstream diff are mojmap.** A mojmap→yarn table is needed before
   applying, for every changed symbol.

## Notes

- Shredder's build.gradle currently hardcodes `minecraft "com.mojang:minecraft:1.21.1"`.
  For 1.21.11 it needs either multi-version (preprocessor), or a separate build.
- altoclef's `build.gradle` already excludes the shredder JAR for 1.21.11:
  `if (mcVersion < 12111) { include project(":shredder") }`.
  After the migration this condition should be removed.
- Tab-complete for altoclef's `@` commands already works without shredder (moved
  into ChatInputSuggestorMixin). But shredder's `#` commands still depend
  on MixinCommandSuggestionHelper.
