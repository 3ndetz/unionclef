# Multi-versioning Guide

UnionClef supports multiple Minecraft versions via the [ReplayMod preprocessor](https://github.com/ReplayMod/preprocessor). Currently enabled: **1.21**, **1.21.1**.

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

**Baritone** is compiled once and shared across all MC versions.
**Tungsten** uses the preprocessor (versioned subprojects: `tungsten-1.21.1`, `tungsten-1.21.11`).

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

Tungsten now uses the preprocessor. Versioned subprojects: `tungsten-1.21.1`, `tungsten-1.21.11`.
Source lives in `tungsten/src/`, `tungsten/versions/mainProject` = `1.21.1`.

To add a new tungsten version:

1. Add to `settings.gradle.kts` tungsten version list
2. Add node + link in `root.gradle.kts` tungsten chain
3. Add mappings/fabric-api entry in `tungsten/build.gradle`
4. Create `tungsten/versions/X.Y.Z/` directory
5. Gate version-specific code with `//#if MC >= XXXXX`

## Version support matrix

| MC Version | altoclef     | baritone     | tungsten     |
| ---------- | ------------ | ------------ | ------------ |
| 1.21       | preprocessor | shared build | n/a          |
| 1.21.1     | preprocessor | shared build | preprocessor |
| 1.21.11    | not yet      | not yet      | preprocessor |

## Shredder preprocessor support (TODO)

Shredder currently compiles once against MC 1.21 and depends on `tungsten-1.21.1`.
When altoclef adds 1.21.11, shredder will also need versioning (same approach as tungsten).

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
