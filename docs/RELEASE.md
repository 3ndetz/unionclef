# Release Guide

## How versioning works

Version comes from **git tags**. Tag `v0.20.0` → version `0.20.0-1.21` in the JAR.

No tag? Falls back to `mod_version` in `gradle.properties`.

## One-time setup: GitHub token

GitHub releases need a personal access token. Create one at [github.com/settings/tokens](https://github.com/settings/tokens) with `repo` scope.

Add it to your **global** Gradle properties (NOT the project file):

```properties
# ~/.gradle/gradle.properties  (Windows: C:\Users\YOU\.gradle\gradle.properties)
github.token=ghp_your_token_here
```

## Release workflow

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

### 3. Publish to GitHub Releases

```bash
gradlew githubRelease
```

This uploads the JAR to [github.com/3ndetz/unionclef/releases](https://github.com/3ndetz/unionclef/releases) with auto-generated release notes.

### All in one

```bash
git tag v0.20.0 && git push origin v0.20.0
gradlew build githubRelease
```

## What gets bundled in the JAR

The release JAR includes everything needed to run:

- UnionClef (altoclef) classes
- Baritone classes (remapped to intermediary)
- Tungsten classes
- Nether pathfinder
- Jackson JSON
- Py4J
- MixinExtras

Users just drop the JAR into their `mods/` folder. No separate baritone install needed.

## Version bumping

Edit `mod_version` in `gradle.properties` for the fallback version. But the actual release version always comes from the git tag.
