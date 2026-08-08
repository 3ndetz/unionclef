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

### 0. Write release notes

Create `docs/releases/<version>.md` (e.g. `docs/releases/0.20.3.md`) —
gradle reads it as the GitHub release body based on `mod_version` from
`gradle.properties`. Falls back to `latest.md` if no versioned file found.

### 1. Tag — DO NOT do this by hand

The gradle task tags for you: `build.gradle` sets
`tagName = "v${project.mod_version}"`, so bumping `mod_version` IS the tag. The manual
`git tag` step that used to live here was stale and is kept below only as a description of what
the task does for you.

⛔ **BUT FIRST: SYNC THE `1.21.11` BRANCH.** The same block sets `targetCommitish = "1.21.11"`,
so GitHub creates the tag on the **`1.21.11` branch head**, not on the branch you worked in.
Work happens on `main` (see AGENTS.md). If the two have drifted, the release tag lands on stale
source while the attached JAR — built from your working tree — is current. Nothing fails, nothing
warns, and `git checkout v<ver>` gives code that does not match the jar people downloaded.

Measured 2026-08-08: `main` was **717 commits** ahead of `1.21.11`, so the last several tags point
at source without the fixes their own notes describe. Check and fast-forward before every release:

```bash
git rev-list --count origin/1.21.11..main      # must be 0
git merge-base --is-ancestor origin/1.21.11 main &&   git push origin main:1.21.11                 # clean fast-forward, no history rewritten
```

### 2. Build

```bash
gradlew build
```

Output JAR: `build/libs/unionclef-0.20.0-1.21.jar`

### 3. Publish to GitHub releases

```bash
gradlew :1.21.11:githubRelease
```

Target the version subproject explicitly. Bare `gradlew githubRelease` runs the
task on BOTH version subprojects in parallel — they race to create the same tag
and one fails with `422 already_exists` (seen on v0.24.0). If that happens,
rerunning `:1.21.11:githubRelease` alone overwrites the half-made release.

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
