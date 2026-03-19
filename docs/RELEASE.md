# Release-Guide Guide

## How versioning works

Version comes from **git tags**. Tag `v0.20.0` → version `0.20.0-1.21` in the JAR.

No tag? Falls back to `mod_version` in `gradle.properties`.

## One-time setup: GitHub token

GitHub Release-Guides need a personal access token. Create one at [github.com/settings/tokens](https://github.com/settings/tokens) with `repo` scope.

Add it to your **global** Gradle properties (NOT the project file):

```properties
# ~/.gradle/gradle.properties  (Windows: C:\Users\YOU\.gradle\gradle.properties)
github.token=ghp_your_token_here
```

## Release-Guide workflow

### 0. Write release notes

Create `docs/releases/<version>.md` (e.g. `docs/releases/0.20.3.md`) —
gradle reads it as the GitHub release body based on `mod_version` from
`gradle.properties`. Falls back to `latest.md` if no versioned file found.

### 1. Tag

```bash
git tag v0.20.0
git push origin v0.20.0
```

### 2. Build

```bash
gradlew build
```

Output JAR: `build/libs/unionclef-0.20.0-1.21.jar`

### 3. Publish to GitHub Release-Guides

```bash
gradlew githubRelease-Guide
```

This uploads the JAR to [github.com/3ndetz/unionclef/Release-Guides](https://github.com/3ndetz/unionclef/Release-Guides) with auto-generated Release-Guide notes.

### All in one

```bash
git tag v0.20.0 && git push origin v0.20.0
gradlew build githubRelease-Guide
```

## What gets bundled in the JAR

The Release-Guide JAR includes everything needed to run:

- UnionClef (altoclef) classes
- Baritone classes (remapped to intermediary)
- Tungsten classes
- Nether pathfinder
- Jackson JSON
- Py4J
- MixinExtras

Users just drop the JAR into their `mods/` folder. No separate baritone install needed.

## Version bumping

Edit `mod_version` in `gradle.properties` for the fallback version. But the actual Release-Guide version always comes from the git tag.
