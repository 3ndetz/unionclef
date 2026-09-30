# AUTOTESTING — autodeploy + autotest of the mod

> **UPDATE 2026-07-24: unified suite pipeline (RW-5) — `deploy/runner/run_suite.py`.**
> New PvP/ranged/chase/bridge scenarios live in the `uctest` library
> (`deploy/runner/uctest/`), one entrypoint, consistent PASS/FAIL + artifacts.
> Design + scenario catalogue: **[features/PVP_SUITE.md](features/PVP_SUITE.md)**.
> Legacy per-feature `deploy/runner/*_test.py` scripts remain until migrated.
>
> **UPDATE 2026-09-01:** everything below this banner is the ORIGINAL Phase-0 design (2026-07-20),
> largely superseded by the actual `deploy/runner/` pipeline built since (`run_suite.py`,
> `gamer_smoke.py`, `paired_ab.py` and the rest — see `TODOS.md` and `docs/CHECKLIST.md` for how
> that pipeline is actually used). One specific line below is now not just superseded but moot:
> the proposed `shredder_goto.py` scenario (`#goto` + jump bridging) targets a module that no
> longer compiles at all — the "G-0" migration (2026-08-24) retired shredder alongside baritone,
> so there is nothing left for a `#`-prefixed scenario to test. Kept for the historical design
> shape, not as a live plan.

Status: **phase 0 implemented and working** (2026-07-20): `deploy/compose.test.yml`
(itzg vanilla 1.21.11 + mineswarm-mc client), `deploy/runner/slime_test.py`
(slime parkour, both courses PASS), entry point — `deploy/autotest.sh` on the Mac.
Below is the overall pipeline design: altoclef tasks, tungsten parkour,
shredder navigation, CI trigger. Phases 1+ have not been done yet.

TL;DR: almost all the building blocks already exist — mineswarm gives a ready-made headless
client with py4j, the mod gives a command interface and `Py4jEntryPoint`, the Mac gives hardware
and Docker. What's left: write the test server, the scenario runner and the trigger. Smoke
version — 1-2 days of work, a full parkour bench — another 2-3.

## What already exists (none of this needs to be written)

| Asset | Where | What it provides |
|---|---|---|
| Headless MC client in Docker | `../mineswarm/game/minecraft/docker/` | PortableMC → Fabric 1.21.11, Java 21, software GL (llvmpipe), noVNC :5800 for eyes, py4j baked-in |
| Mod deploy without a rebuild | `../mineswarm/game/minecraft/mods/` | jar is mounted read-only; a fresh build = copy the jar + `docker compose restart` |
| Mod's py4j interface | `src/main/java/adris/altoclef/Py4jEntryPoint.java` | ~100 methods: `ExecuteCommand`, `ChatMessage`, `ConnectToServer`, `inGame`, `hasActiveTask`, `getRecentChat`, `getScreenshot`, `getPlayersInfo`, `getBlockAt`… port 25333 (`pythonGatewayPort`, +2 if taken) |
| e2e test skeleton | `scripts/custom/example_server_test.py` | join → command → poll `hasActiveTask()` → `result.txt` OK/FAIL. This is exactly the loop the runner needs |
| Autoconnect | `altoclef_settings.json`: `autoConnectServer`, `autoReconnect`, `autoRespawn` | the client joins the server on its own at startup — the runner does not need to click anything |
| Mod multi-versions | `versions/1.21`, `1.21.1`, `1.21.11` (replaymod preprocessor) | one `gradlew build` produces a jar for every MC version: `versions/<v>/build/libs/unionclef-<v>-<mod_version>-all.jar` |
| Mac as host | mactrindetz `192.168.1.20` (M4 Max, 48 GB, Docker Desktop) | the mineswarm mini-stack already runs there 24/7 (`docker-compose.mac.yml`, client `mc-crossentropy`), clones of `unionclef`/`mineswarm` live in `~/repos/pet` |
| Test-server precedent | `C:\repos\srv\agicraftmc` | Paper + RCON + push-to-main autodeploy; we take the RCON verification pattern from there |
| CI-runner precedent | `nettyan-toolkit/.github/workflows/deploy.yml` | push → self-hosted runner rebuilds the container; same pattern, but the runner is on the Mac |

Access to the Mac and passwords — `tools.personalabs.ru/docs` + `/creds` (`hosts.mac`).

## Architecture

```
push to 1.21.11 (GitHub)
        |
        v
self-hosted runner on the Mac  (or a launchd poller, see "Trigger")
        |
        |  1. gradlew build  (Java 21 already on the Mac; jar is pure bytecode, arch doesn't matter)
        |  2. cp jar -> deploy bench, docker compose up
        v
+---------------------- docker network: uctest ----------------------+
|                                                                    |
|  test-server (itzg/minecraft-server, Paper/Fabric,                 |
|      offline-mode, RCON, world-template with parkour)              |
|          ^                ^                    ^                   |
|          |                |                    |                   |
|   mc-test-1         mc-test-2            mc-test-N                |
|   (mineswarm-mc + fresh jar; autoConnectServer=test-server)        |
|                                                                    |
+--------------------------------------------------------------------+
        |
        v
runner (python/uv): docker exec -> py4j -> scenarios -> junit.xml,
screenshots and latest.log on failures -> GH check + artifacts (+ TG optionally)
```

## Proposed layout in the repo

```
deploy/
  test-server/
    compose part (itzg/minecraft-server)
    world-template/          # committed world zip with the parkour courses
    courses.json             # start/finish coordinates of each course
  compose.test.yml           # server + N clients + network
  runner/
    pyproject.toml           # uv, py4j
    runner.py                # orchestration: wait -> connect -> scenarios -> report
    scenarios/
      smoke.py               # mod loaded, py4j answers, joined the server
      altoclef_goto.py       # @goto x y z
      altoclef_follow.py     # @follow between two bots
      tungsten_parkour.py    # ;goto through a parkour course
      tungsten_follow.py     # ;followPlayer behind a second bot
      shredder_goto.py       # #goto + a jump-bridging segment
  autotest.sh                # local entry point: build -> deploy -> up -> run -> report
.github/workflows/autotest.yml   # push trigger (self-hosted mac runner)
```

## Components

### deploy/test-server

Our own local server, not an external one: determinism (nobody interferes), op rights,
RCON for setup/teardown, no rate limits and no anticheat.

- Base: `itzg/minecraft-server` (Paper for RCON convenience; Fabric — if server mods are
  ever needed, not yet). `online-mode=false` — clients join under
  offline usernames from `MC_USERNAME`.
- World: built once by hand (parkour courses, task platforms, a forest for `@get log`),
  saved and committed as `world-template/` (zip). The container unpacks the template into
  a temporary volume at startup — every run starts from a clean world.
- `courses.json` — the course map: `{id, start: [x,y,z], finish: [x,y,z], radius, timeout_s}`.
  The runner teleports the bot to start (RCON `tp`) and waits for it to reach the finish box.
- RCON — the runner's second hand: `tp`, `gamemode`, `give`, `time set`, and verification
  via `execute if entity @a[name=...,x=...,dx=...]` as an alternative to py4j coordinates.

### deploy/testing-docker-image (client)

**Do not fork the image.** `mineswarm-mc` (built from `../mineswarm/game/minecraft/docker/`)
already solves every pain point: fabric-lib prefetch with jar integrity checks,
llvmpipe, options.txt lockdown, py4j pip baked in. The test bench simply uses it:

```yaml
# fragment of compose.test.yml
mc-test-1:
  image: mineswarm-mc:amd64        # already built on the Mac; see "Risks" about arm64
  platform: linux/amd64
  environment: { MC_USERNAME: "tester1" }
  volumes:
    - ./run/mods:/mc-data/mods:ro          # autotest.sh places the fresh jar here
    - ./run/data/tester1:/mc-data
```

Important: the mod's py4j listens on `127.0.0.1` **inside** the container. The runner
reaches it the same way the mineswarm gateway does — `docker exec mc-test-1 python3 -c
'<py4j snippet>'` (ready-made pattern: `../mineswarm/docker/gateway/gateway.py`,
`_PY4J_SNIPPET` / `_mc_call`). A future alternative — configure `pythonGatewayBindAll`
in the mod so the runner can reach it directly over the docker network; for phase 0,
exec is enough.

`run/mods` gets: the fresh `unionclef-*-all.jar` + a minimal set from
`../mineswarm/game/minecraft/mods/` (fabric-api is mandatory; sodium/lithium — to taste,
better left in on the software renderer).

#### Rendering: llvmpipe by default, GPU when there is a usable one

The image pins `LIBGL_ALWAYS_SOFTWARE=1` and `GALLIUM_DRIVER=llvmpipe`, so by default the
clients rasterise on the CPU. That is the bench's ceiling. The flat course arena holds 28-30
fps because there is almost nothing to draw, but the survival world — real terrain, real draw
distance — falls to 7-8 fps against `gamer_smoke`'s floor of `SANE_REF_FPS = 12.0`, and the
playthrough (acceptance criterion #1) is simply refused before it starts.

`deploy/compose.gpu.yml` is an override that undoes those two pins and asks for the device.
`deploy_jar.sh` adds it only when a probe confirms a GPU, and never otherwise.

Three things have to be true, and the third is the one that bites:

| piece | where it comes from |
|---|---|
| `/dev/dxg` | appears with `--gpus all` on Docker Desktop / WSL2 |
| `d3d12_dri.so` | Mesa's D3D12 gallium driver, already in the image |
| `libd3d12core.so` | the D3D12 **runtime** — NOT in the image, mounted from `/usr/lib/wsl/lib` |

Docker Desktop's nvidia runtime injects COMPUTE only: `nvidia-smi` answers, but there is no
`libGLX_nvidia` and no Vulkan ICD, so the graphics path is D3D12 through `/dev/dxg` rather
than the usual NVIDIA GLX one.

⛔ **A present GPU is not a working renderer, and the difference is silent.** With the card
visible but the runtime missing, the client dies during GL context creation: the log stops
dead at `Backend library: LWJGL version 3.3.3-snapshot`, the JVM is gone, and there is no
stack trace and no GL error to grep for. So the probe checks the runtime as well as the card,
and — more importantly — the deploy does not trust the probe:

**STATUS ON THIS MACHINE (2026-08-16): the GPU is reachable, and the clients still cannot
render on it.** With all three pieces in place the failure stops being silent and names
itself:

```
GLFW error 65543: GLX: Failed to create context: GLXBadFBConfig
    at org.lwjgl.glfw.GLFW.glfwCreateWindow
```

That is not a missing driver — it is the **display server**. The client asks for its context
through GLX, and GLX hands out framebuffer configs from the X server it is talking to, which
here is **Xvfb**. Xvfb has no DRI: it cannot expose a hardware FBConfig no matter which
gallium driver the client-side Mesa has loaded, so a core-profile context on the GPU is not
something it can grant. Setting `GALLIUM_DRIVER=d3d12` swaps the driver under a GLX stack
that still has nowhere to render.

EGL was the obvious other door, and it is shut too — for the reason that turns out to be the
real one. A ctypes EGL probe (no mesa-utils needed) run inside the image:

| `GALLIUM_DRIVER` | result |
|---|---|
| `llvmpipe` | context OK — `GL_RENDERER = llvmpipe`, GL 4.5 |
| `d3d12` | `eglInitialize failed (0x3001)`, `egl: failed to create dri2 screen` |

The llvmpipe arm is the control: the probe is sound, and d3d12 specifically fails. Mesa's own
debug output names the cause — `DRI2: failed to load driver` / **`Falling back to surfaceless
swrast without DRM`** — and the container confirms it:

```
ls /dev/dri   ->  No such file or directory
ls /dev/dxg   ->  crw-rw-rw- 1 root root 10, 125
```

⛔ **There is no DRM device, so no Mesa path can make a GPU screen.** DRI2, GLX and
surfaceless-EGL all instantiate a screen from a DRM node. WSL2 does not expose one: the GPU
arrives through dxgkrnl as `/dev/dxg`. Only the Mesa Microsoft ships inside WSLg can drive
that, via a DXCore winsys tied to WSLg's own display stack. Docker Desktop's VM is a WSL
distro **without** WSLg, so a container gets the device node and nothing able to talk to it.

Checked and ruled out along the way, so nobody repeats them:

- **Mesa version.** `deploy/gpu-image/Dockerfile` builds a derived image with Mesa **25.0.7**
  from trixie (Debian 12 pins 22.3.6 and offers nothing newer). Identical failure. The driver
  file is fine — `d3d12_dri.so` is a symlink to `libdril_dri.so`, it dlopens cleanly, and all
  its dependencies resolve. There is simply no winsys under it.
- **NVIDIA's own GLX.** Not available: on Docker Desktop + WSL2 the nvidia runtime injects
  compute and encode only (`libnvidia-encode`, `-ml`, `-ngx`, `-opticalflow`). No
  `libGLX_nvidia`, no `libEGL_nvidia`, and `/usr/share/glvnd/egl_vendor.d` holds only
  `50_mesa.json`.

This is host topology, not configuration, and no env-var tuning reaches it. What would change
the answer: running the bench containers under a **WSLg-enabled WSL distro** instead of Docker
Desktop's VM; a Docker Desktop that exposes a DRM node; or any ordinary Linux host with a real
`/dev/dri`. That is an infrastructure choice for the owner of the box, which is why the deploy
records the no and stays on the CPU rather than trying to be clever.

```
recreate_clients "$GPU_ARGS"          # try it
wait_py4j 300                    ||   # did a client actually answer?
    { GPU_ARGS=""; recreate_clients ""; wait_py4j 600; }   # no: put it back on the CPU
```

**Rendering is an optimisation, and an optimisation does not get to break the bench.** The
`wait_py4j` loop is bounded for exactly this reason — it used to be an unbounded `until`, so a
client that never came up hung the deploy for ever instead of being diagnosed. Any failure of
the GPU path lands on llvmpipe with a message saying so, which is also what covers a
CPU-only machine: it simply never enables the override in the first place.

A failed attempt is also **written down**. `deploy/.gpu_unusable` (git-ignored, per machine) is
created when the fallback fires, and later deploys skip straight to the CPU — otherwise every
deploy pays the same 300 s and double recreate to relearn a fact about the box that has not
changed. Delete it to try again after a driver update or a new rendering path:

```
rm deploy/.gpu_unusable
```

Forcing either mode by hand:

```
UCTEST_GPU=0 sh deploy/deploy_jar.sh     # never use the GPU
UCTEST_GPU=1 sh deploy/deploy_jar.sh     # insist (ignores the marker), fail loudly if absent
```

### deploy/runner

Python + uv (as in `scripts/`). Loop per client:

1. `wait_for_gateway()` — py4j answers (port from the log `Py4j gateway started on port N`,
   do not hardcode 25333).
2. `wait_for_game()` — poll `inGame()` (autoConnectServer joins on its own).
3. Run the scenarios in order. Commands: `ExecuteCommand("@…")` for altoclef,
   `ChatMessage(";…")` / `ChatMessage("#…")` for tungsten/shredder — tungsten intercepts
   chat send specifically, its commands do not work through `ExecuteCommand`.
4. Assert: position (py4j `getPlayersInfo` or RCON), `getHealth()`, `getRecentChat(n)`
   for error markers, timeouts via `hasActiveTask()`.
5. On failure — `getScreenshot()` + the tail of `latest.log` into the artifacts. Result — junit.xml.

### World checkpoints of the survival stand (2026-09-16)

`deploy/runner/checkpoint.py` freezes and restores the WHOLE world of the gamer server —
position, inventory, armour, health, hunger (playerdata), time of day and seed (level.dat), every
shaft dug and furnace placed (regions). Nothing is synthesised: the state is the one the run left.

    python deploy/runner/checkpoint.py save NAME [--note "..."]   # save-off, flush, docker cp, save-on (~30 s for 2 GB)
    python deploy/runner/checkpoint.py restore NAME               # kick the bot, stop, swap /data/world, chown, start, wait for rcon
    python deploy/runner/checkpoint.py list
    python deploy/runner/gamer_smoke.py 20 --from NAME            # resume a run there (the swap happens right before @gamer)
    python deploy/runner/gamer_smoke.py 60 --checkpoint-every 10  # freeze the middle too; the end is always `last`

Checkpoints live in `deploy/runner/checkpoints/` (git-ignored, ~2 GB each). The point: a
sixty-minute run reaches its wall at minute forty; a fix for that wall is tested from the
checkpoint in a few minutes, not from an empty inventory in forty.

### Scenarios (starting set)

| id | what it does | criterion | timeout |
|---|---|---|---|
| smoke | client came up, py4j is alive, joined the server | `inGame() == true` | 180 s |
| altoclef_goto | `@goto <platform finish>` | position in the finish box | 60 s |
| altoclef_get | `@get log 3` (platform with a forest) | 3 logs in inventory (`getInventoryFull`) | 120 s |
| altoclef_follow | bot A `@follow tester2`, bot B runs a route | distance A-B < 6 blocks at the end | 90 s |
| tungsten_parkour_flat | `;goto` through a course: straights + 2-block gaps | finish box | 90 s |
| tungsten_parkour_hard | course with 3-block gaps and climbs | finish box | 120 s |
| tungsten_follow | `;followPlayer tester2` | distance < 6 blocks, no falls (health) | 90 s |
| shredder_goto | `#goto` through mixed terrain | finish box | 120 s |
| shredder_bridge | `#goto` across a gap requiring jump bridging | finish box, health == 20 | 120 s |
| gamer_nightly | `@gamer` from scratch | progress markers in chat | hours — **nightly only**, not every push |

Two bots (`tester1`/`tester2`) cover the follow scenarios; N clients in compose is just
N services, py4j ports don't conflict (each has its own netns).

## Multi-version and multi-instance

- **Mod versions** (several builds at once): each client service gets its own
  `run/mods-<tag>` with the needed jar — this lets you run the current build against the
  previous release on one bench (useful for comparing parkour metrics "got worse/better").
- **MC versions**: build already produces a jar for 1.21/1.21.1/1.21.11, but the mineswarm
  image is pinned to `fabric:1.21.11:0.19.3` in `startapp.sh`. A matrix of MC versions needs
  a `MC_VERSION` build-arg in mineswarm's Dockerfile (a change on their side, ~half a day).
  Phase 3, not before: the main value is regressions on 1.21.11.
- **Scale**: a client eats ~1.5-2 GB RAM; the Mac's 48 GB comfortably hosts 4-6 test clients
  alongside the CEZ production stack.

## Trigger (autodeploy)

**Option A — recommended: self-hosted GitHub Actions runner on the Mac.**
Same pattern as nettyan-toolkit (push → runner → deploy). Register a runner
with label `mac-mc`, workflow `autotest.yml`: on push to `1.21.11` → checkout → `gradlew build`
→ `deploy/autotest.sh` → junit report as a GH check + artifacts (screenshots, logs).
Pros: statuses right on the commits, free artifacts, zero own polling infrastructure.

**Option B — fallback: a launchd poller.** A script on the Mac every N minutes: `git fetch`,
if a new commit appeared — the same `autotest.sh`, result to Telegram via the toolkit bot.
Simpler to set up (no GH token needed on the Mac), but statuses on commits are lost.

In both options all the work happens on the Mac — jayra is not involved (requirement:
do not load the work machine). We don't touch the mineswarm production stack on the Mac:
the test bench lives in a separate compose network `uctest` with separate container names.

## Phases

| Phase | Content | Estimate |
|---|---|---|
| 0 — smoke | **done**: compose.test.yml (server + 1 client), autotest.sh, runner with slime parkour (`;goto` via bounce), launch over ssh on the Mac | — |
| 1 — parkour | world with courses + courses.json, tungsten/shredder scenarios, second bot + follow, screenshots on failures | 2-3 days |
| 2 — CI | self-hosted runner on the Mac, autotest.yml, junit + artifacts, TG notification | ~1 day |
| 3 — matrix | MC_VERSION build-arg in the mineswarm image, run on 1.21.1, comparison of build metrics | 1-2 days |

Phase 0 already pays for itself: it catches "the mod didn't load / crashed on join / py4j
died" — a class of regressions that is currently found manually in ~10 minutes per build.
Phases 1+ are the regression net for TODO 1.6.3, where every simulation fix requires
re-testing the pathfinder: without autotest that item is practically infeasible.

## Two ways the bench measures code that was never loaded (both fixed, 2026-08-18)

Both were found in one morning, and both produce the same symptom: a run that looks completely
normal and reports a result for bytecode that is not what you wrote.

**1. `deploy_jar.sh` does not build.** It ships the newest jar in `versions/1.21.11/build/libs`.
`gradlew compileJava` produces *classes* and no jar, so compile-then-deploy silently ships whatever
jar was lying there -- once, a three-hour-old one, and a ten-run `mine_coal` batch was measured
against code that had never been loaded. The only reason it was caught is that the change under
test added a *new counter*, and the counter did not appear; with any change that merely alters
behaviour, the batch would have gone into the register as a real measurement.

The script now refuses when compiled bytecode is newer than the jar, naming the class, with
`UCTEST_ALLOW_STALE=1` as the deliberate escape. It compares against **classes, not sources**:
gradle's up-to-date check is content-hashed, so a `touch` or a branch switch rewinds no bytecode
and must not raise an alarm. The first cut of the guard compared against `.java` and refused a jar
that was entirely current -- which is worse than no guard, because a check that fires on a correct
state teaches you to keep the override switched on permanently.

**2. `deploy_jar.sh | tail -4 && run_suite.py` runs the suite even when the deploy dies.** A
pipeline's exit status is the exit status of its LAST command, and that is `tail`, which succeeds.
A deploy that failed on a syntax error therefore returned 0 and `&&` handed the bench to a full
ten-run suite against a half-deployed stand. Run the deploy on its own line and check `$?`, or set
`pipefail` -- do not pipe a step whose success gates the next one.

## A third way: the counter runs, but not in the course you are testing (2026-08-20)

The two above ship the wrong bytecode. This one ships the right bytecode and still reports a
number that has nothing to do with the run -- and it reads like a finding, not like a fault.

`dealt` (damage the bot's swings removed) printed **0.0 through a pvp fight with seventy swings
and twelve kills in it**. Three separate wirings, each a smaller audience than the last:

1. the counter lived inside `noticeDraws`, whose first loop line is
   `if (!(e instanceof RangedAttackMob)) continue;` -- skeletons only, so damage to a zombie was
   invisible and a player was never enumerated at all;
2. moved out, it still sat behind that method's **call site**, inside `isProjectileClose` -- a
   predicate about arrows, which a melee fight never asks;
3. moved to `MobDefenseChain.getPriority` -- the chain's real per-tick entry -- it *still* read
   zero, because the pvp courses do not tick that chain.

Every one of those zeroes reads exactly like "the bot deals no damage", which is a plausible,
publishable, completely wrong conclusion. It now ticks from `AltoClef.onClientTick` and nowhere
else.

**The rule this gives:** an instrument must not hang off whoever happens to be asking a question
nearby, and every counter needs something beside it that **cannot be zero if it ran**. Two forms,
both cheap:

- a **tick counter** -- `dealt=206.0/230.0/2593` prints ours / all / ledger ticks, so "never ran"
  can never again be read as "found nothing";
- a **denominator that cannot be zero** -- the `seen` total counts *every* hp drop near the bot,
  ours or not. That is what actually caught this: a zero there while the opponent was dying twelve
  times is impossible, and it turned a finding back into a bug.

Note also that the counter's *scope* changed with the fix: `dealt` and `swingHits` previously saw
ranged mobs only, so figures in artifacts from before this date are not comparable with later ones.

Related, and it cost a build: **`set -o pipefail` plus `grep -q` manufactures a false negative.**
`unzip -p jar cls | grep -qa "literal"` -- grep exits at the first match, unzip takes SIGPIPE, the
pipeline status is non-zero and the jar verification reports the literal MISSING from a jar that
contains it. Drop `-q`, or drop `pipefail` for that one check.

Related: `TaskStop` kills the shell, not its children. The orphaned suite kept the bench lock and
ran for seventeen more minutes with its stdout attached to a dead shell, so nothing it produced
could ever be read. After killing a suite, check for surviving `run_suite.py` processes and clear
`%TEMP%/uctest_suite.lock` if it names your own dead run.

## Risks and honest caveats

- **Software rendering + Rosetta = 10-25 FPS.** Game logic ticks at 20 TPS and does not
  depend on FPS, but WindMouse camera smoothing is per-frame; at low FPS turns are coarser.
  For parkour tests this is a source of flakes. Mitigations: (1) generous timeouts and a
  "reached the finish" criterion rather than "executed perfectly"; (2) one automatic retry
  per scenario; (3) the real fix — build `mineswarm-mc` for linux/arm64 (Java 21 arm64 +
  LWJGL linux-arm64 natives for 1.21 exist, PortableMC supports it) — native speed on the
  M4. This is a change to mineswarm's Dockerfile, half a day to a day, worth doing in
  phase 1-2.
- **Movement tests are flaky by nature.** Don't chase 100% green: a screenshot + log on
  failure matters more than perfect stability. A "2 failures in a row = red" threshold
  is fine.
- **`@gamer` is long.** Never on push, nightly only with a hard wall-clock limit.
- **py4j listens on loopback** — access only via `docker exec` (the mineswarm gateway
  pattern), until a bind on 0.0.0.0 is added to the mod (a separate small task, mindful
  that the port would then become visible on the docker network).
- **World template in git** — a world zip of a couple megabytes is fine; don't commit
  regions that have grown after test runs (every run starts from a clean copy).

## Answer to "is it worth it"

Yes. The complexity is moderate (about a week of net work total up to phase 2), because
the three most expensive pieces — the headless client, the py4j bridge and deploying a
jar without a rebuild — are already written and battle-tested by mineswarm. What's left
to write is essentially just the test server with a map, the scenario runner and one
workflow. The value: instant detection of crashes/regressions on every push, and the only
realistic way to carry out items like 1.6.3 ("re-test after every fix") without doing it
by hand.
