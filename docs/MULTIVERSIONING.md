# Multi-versioning Guide

UnionClef supports multiple Minecraft versions via the [ReplayMod preprocessor](https://github.com/ReplayMod/preprocessor). Currently enabled (`settings.gradle.kts`): **1.21**, **1.21.1**, **1.21.11**
— `1.21.11` is the current working version (see `AGENTS.md`), not "not yet" as an older version of
this doc's matrix below said.

## How it works

Altoclef source code contains conditional directives:

```java
//#if MC >= 12100
return pos.getSquaredDistance(obj);
//#else
//$$ return pos.getSquaredDistance(obj.getX(), obj.getY(), obj.getZ(), true);
//#endif
```

The preprocessor strips inactive branches for each target version. Active code compiles normally, inactive code stays as comments (`//$$`).

**Baritone/shredder** are NOT compiled at all since the "G-0" migration (2026-08-24, see
`TODOS.md`) — source-reference-only, no build output, nothing to version. (This section used to
say baritone was "compiled once and shared across all MC versions" — that was true before G-0.)
**Tungsten** is the only compiled pathfinder, but it does NOT use the preprocessor and is NOT
multi-version — ⛔ CORRECTED 2026-09-01, this used to claim "versioned subprojects:
`tungsten-1.21.1`, `tungsten-1.21.11`", which never existed. `tungsten/build.gradle` is a plain
single-version Fabric Loom project pinned directly to `minecraft "com.mojang:minecraft:1.21.11"`
/ `yarn:1.21.11+build.4` with no preprocessor plugin applied at all, and `settings.gradle.kts`
registers exactly one `include(":tungsten")` — there is no `tungsten-1.21.1` or
`tungsten-1.21.11` project, and no `tungsten/versions/` directory on disk. The
`docs/TUNGSTEN_MULTIVERSION.md` plan this section was describing (a preprocessor-based tungsten
with per-MC-version subprojects) was never built — see the note now at the top of that file.
`settings.gradle.kts`'s own comment above the version-subproject list ("MC version subprojects
(altoclef + tungsten source, preprocessed)") and `root.gradle.kts`'s ("altoclef + tungsten source
preprocessed together") both describe an EARLIER arrangement that has since changed again: the
root `build.gradle` says outright, in its own comment (`:39-40`) — "Tungsten is now a standalone
mod (1.21.11-primary source, own build.gradle). No longer compiled inline via srcDir — imported
as dependency instead." Each `versions/X.Y.Z` subproject now pulls tungsten in as a plain Gradle
project dependency (`implementation project(path: ":tungsten", ...)`, `build.gradle:121-122`),
not as preprocessed source of its own — so even the two settings/root comments above are stale
leftovers of a THIRD, intermediate arrangement, neither the original preprocessor plan nor the
current single-binary-dependency one.

## Adding a new version (e.g. 1.21.4)

### Step 1: altoclef (usually enough)

For minor bumps (1.21.x), altoclef is often the only thing that needs changes.

**1. `settings.gradle.kts`** — add the version:

```kotlin
listOf(
    "1.21.4",  // ← add
    "1.21.1",
    "1.21",
)
```

**2. `root.gradle.kts`** — add preprocessor node:

```kotlin
preprocess {
    val mc12104 = createNode("1.21.4", 12104, "yarn")  // ← add
    val mc12101 = createNode("1.21.1", 12101, "yarn")
    val mc12100 = createNode("1.21", 12100, "yarn")

    mc12104.link(mc12101)  // ← add
    mc12101.link(mc12100)
}
```

**3. `build.gradle`** — add mappings and fabric API version:

```groovy
def mappingsVersions = [
        12104: "1.21.4+build.X",  // ← check https://fabricmc.net/develop/
        12101: "1.21.1+build.3",
        12100: "1.21+build.9",
]

def fabricApiVersions = [
        12104: "0.XXX.X+1.21.4",  // ← check https://fabricmc.net/develop/
        12101: "0.110.0+1.21.1",
        12100: "0.100.7+1.21",
]
```

**4. Create version directory** (if it doesn't exist):

```bash
mkdir -p versions/1.21.4/src/main/java
mkdir -p versions/1.21.4/src/main/resources
```

**5. Build:**

```bash
gradlew :1.21.4:compileJava
```

If there are API changes, add `//#if MC >= 12104` directives in the affected source files.

### Step 2: baritone (only if it breaks)

Baritone is compiled once for 1.21 with yarn mappings. For minor versions (1.21.x), it usually works without changes — the API is stable.

**If baritone doesn't compile against the new version:**

1. Fix the breaking code in `baritone/src/main/java/`
2. Use `//noinspection` or version checks if needed
3. Baritone doesn't use the preprocessor, so fixes must be compatible with all enabled versions

**If the MC version is far enough that baritone needs different code per version** (e.g. 1.20 vs 1.21):

- Option A: Add preprocessor directives to baritone source (requires adding baritone to the preprocessor config)
- Option B: Keep baritone pinned to the latest MC version and only support nearby versions

In practice, baritone works across 1.21.x without changes.

### Step 3: tungsten

⛔ CORRECTED 2026-09-01 — this step described a preprocessor setup that was never built (see the
note earlier in this doc, and `docs/TUNGSTEN_MULTIVERSION.md`). Tungsten is a standalone,
single-version Fabric mod: `tungsten/build.gradle` has no preprocessor plugin and pins
`minecraft "com.mojang:minecraft:1.21.11"` / `yarn:1.21.11+build.4` directly, no version list, no
`tungsten/versions/` directory. Each `versions/X.Y.Z` altoclef subproject depends on the compiled
`:tungsten` jar (`build.gradle:121-122`, `implementation project(path: ":tungsten", ...)`), it
does not compile tungsten's source itself.

**There is currently no supported way to "add a tungsten version"** — tungsten IS 1.21.11, and an
altoclef version that needs a different tungsten API would need either a second tungsten mod
build or an actual preprocessor migration (the abandoned plan in `TUNGSTEN_MULTIVERSION.md`).
Left unresolved rather than inventing a procedure that was never implemented.

## Version support matrix

⛔ STALE, CORRECTED 2026-09-01: this table said 1.21.11 was "not yet" supported by altoclef and
baritone. altoclef's 1.21.11 support has existed for a while and is now the primary working
version (`AGENTS.md`); baritone's column is moot since G-0 (2026-08-24) — it isn't compiled for
ANY version any more, not just 1.21.11.

⛔ `tungsten` column CORRECTED 2026-09-01: it is not preprocessed per-version (see Step 3 above);
it is a single standalone mod built against 1.21.11 only and depended on as a compiled jar by
every altoclef version subproject, 1.21/1.21.1 included.

| MC Version | altoclef     | baritone/shredder      | tungsten     |
| ---------- | ------------ | ---------------------- | ------------ |
| 1.21       | preprocessor | not compiled (source reference only) | compiled jar dependency (1.21.11 build) |
| 1.21.1     | preprocessor | not compiled (source reference only) | compiled jar dependency (1.21.11 build) |
| 1.21.11    | preprocessor, current working version | not compiled (source reference only) | standalone mod, builds natively for this version |

## Shredder preprocessor support (retired, was TODO)

⛔ MOOT since the "G-0" migration (2026-08-24, see `TODOS.md`) — shredder is not compiled for any
MC version and never will need versioning, because tungsten replaced it as the only pathfinder.
Left here so a future reader doesn't go looking for a still-open task.

## Quick reference

```bash
# Build specific version
gradlew :1.21:build
gradlew :1.21.1:build

# Build all enabled versions
gradlew build

# Compile only (no JAR)
gradlew :1.21.4:compileJava
```
