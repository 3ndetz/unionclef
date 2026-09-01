# AUTOTESTING — автодеплой + автотест мода

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

Статус: **фаза 0 реализована и работает** (2026-07-20): `deploy/compose.test.yml`
(itzg vanilla 1.21.11 + mineswarm-mc клиент), `deploy/runner/slime_test.py`
(слайм-паркур, оба курса PASS), вход — `deploy/autotest.sh` на маке.
Ниже — общий дизайн пайплайна: altoclef-таски, tungsten-паркур,
shredder-навигация, CI-триггер. Фазы 1+ ещё не делались.

TL;DR: почти все кирпичи уже существуют — mineswarm даёт готовый headless-клиент с py4j,
мод даёт командный интерфейс и `Py4jEntryPoint`, мак даёт железо и Docker. Осталось
написать тест-сервер, раннер сценариев и триггер. Смоук-версия — 1–2 дня работы,
полноценный паркур-стенд — ещё 2–3.

## Что уже есть (ничего из этого писать не надо)

| Актив | Где | Что даёт |
|---|---|---|
| Headless MC-клиент в Docker | `../mineswarm/game/minecraft/docker/` | PortableMC → Fabric 1.21.11, Java 21, софтверный GL (llvmpipe), noVNC :5800 для глаз, py4j baked-in |
| Деплой мода без ребилда | `../mineswarm/game/minecraft/mods/` | jar монтируется read-only; свежий билд = скопировать jar + `docker compose restart` |
| Py4j-интерфейс мода | `src/main/java/adris/altoclef/Py4jEntryPoint.java` | ~100 методов: `ExecuteCommand`, `ChatMessage`, `ConnectToServer`, `inGame`, `hasActiveTask`, `getRecentChat`, `getScreenshot`, `getPlayersInfo`, `getBlockAt`… порт 25333 (`pythonGatewayPort`, при занятости +2) |
| Скелет e2e-теста | `scripts/custom/example_server_test.py` | join → команда → poll `hasActiveTask()` → `result.txt` OK/FAIL. Это ровно тот цикл, который нужен раннеру |
| Автоконнект | `altoclef_settings.json`: `autoConnectServer`, `autoReconnect`, `autoRespawn` | клиент сам заходит на сервер при старте — раннеру не надо ничего кликать |
| Мульти-версии мода | `versions/1.21`, `1.21.1`, `1.21.11` (replaymod preprocessor) | один `gradlew build` даёт jar на каждую MC-версию: `versions/<v>/build/libs/unionclef-<v>-<mod_version>-all.jar` |
| Мак как хост | mactrindetz `192.168.1.20` (M4 Max, 48 GB, Docker Desktop) | mineswarm мини-стек уже крутится там 24/7 (`docker-compose.mac.yml`, клиент `mc-crossentropy`), клоны `unionclef`/`mineswarm` лежат в `~/repos/pet` |
| Прецедент тест-сервера | `C:\repos\srv\agicraftmc` | Paper + RCON + push-to-main автодеплой; оттуда берём паттерн RCON-верификации |
| Прецедент CI-раннера | `nettyan-toolkit/.github/workflows/deploy.yml` | push → self-hosted runner пересобирает контейнер; тот же паттерн, но раннер на маке |

Доступ к маку и пароли — `tools.personalabs.ru/docs` + `/creds` (`hosts.mac`).

## Архитектура

```
push в 1.21.11 (GitHub)
        |
        v
self-hosted runner на маке  (или launchd-поллер, см. "Триггер")
        |
        |  1. gradlew build  (Java 21 на маке уже есть; jar — чистый байткод, arch не важна)
        |  2. cp jar -> deploy-стенд, docker compose up
        v
+---------------------- docker network: uctest ----------------------+
|                                                                    |
|  test-server (itzg/minecraft-server, Paper/Fabric,                 |
|      offline-mode, RCON, world-template с паркуром)                |
|          ^                ^                    ^                   |
|          |                |                    |                   |
|   mc-test-1         mc-test-2            mc-test-N                |
|   (mineswarm-mc + свежий jar; autoConnectServer=test-server)       |
|                                                                    |
+--------------------------------------------------------------------+
        |
        v
runner (python/uv): docker exec -> py4j -> сценарии -> junit.xml,
скриншоты и latest.log при фейлах -> GH check + артефакты (+ TG опционально)
```

## Предлагаемая раскладка в репе

```
deploy/
  test-server/
    compose-часть (itzg/minecraft-server)
    world-template/          # закоммиченный zip мира с паркур-курсами
    courses.json             # координаты start/finish каждого курса
  compose.test.yml           # сервер + N клиентов + сеть
  runner/
    pyproject.toml           # uv, py4j
    runner.py                # оркестрация: wait -> connect -> сценарии -> отчёт
    scenarios/
      smoke.py               # мод загрузился, py4j отвечает, зашёл на сервер
      altoclef_goto.py       # @goto x y z
      altoclef_follow.py     # @follow между двумя ботами
      tungsten_parkour.py    # ;goto через паркур-курс
      tungsten_follow.py     # ;followPlayer за вторым ботом
      shredder_goto.py       # #goto + сегмент jump bridging
  autotest.sh                # локальный вход: build -> deploy -> up -> run -> report
.github/workflows/autotest.yml   # триггер на push (self-hosted mac runner)
```

## Компоненты

### deploy/test-server

Свой локальный сервер, а не внешний: детерминизм (никто не мешает), op-права,
RCON для setup/teardown, нет rate-limit'ов и античитов.

- База: `itzg/minecraft-server` (Paper для RCON-удобства; Fabric — если понадобятся
  серверные моды, пока не нужны). `online-mode=false` — клиенты заходят под
  offline-никами из `MC_USERNAME`.
- Мир: строится один раз руками (паркур-курсы, площадки для тасков, лес для `@get log`),
  сохраняется и коммитится как `world-template/` (zip). Контейнер при старте
  распаковывает шаблон во временный volume — каждый прогон начинается с чистого мира.
- `courses.json` — карта курсов: `{id, start: [x,y,z], finish: [x,y,z], radius, timeout_s}`.
  Раннер телепортирует бота на start (RCON `tp`) и ждёт попадания в finish-бокс.
- RCON — вторая рука раннера: `tp`, `gamemode`, `give`, `time set`, и верификация
  через `execute if entity @a[name=...,x=...,dx=...]` как альтернатива координатам из py4j.

### deploy/testing-docker-image (клиент)

**Не форкать образ.** `mineswarm-mc` (собирается из `../mineswarm/game/minecraft/docker/`)
уже решает все больные места: prefetch fabric-либ с проверкой целостности jar'ов,
llvmpipe, options.txt lockdown, py4j pip внутри. Тест-стенд просто использует его:

```yaml
# фрагмент compose.test.yml
mc-test-1:
  image: mineswarm-mc:amd64        # на маке уже собран; см. "Риски" про arm64
  platform: linux/amd64
  environment: { MC_USERNAME: "tester1" }
  volumes:
    - ./run/mods:/mc-data/mods:ro          # свежий jar кладёт autotest.sh
    - ./run/data/tester1:/mc-data
```

Важно: py4j в моде слушает `127.0.0.1` **внутри** контейнера. Раннер ходит к нему так же,
как mineswarm gateway — `docker exec mc-test-1 python3 -c '<py4j snippet>'`
(готовый паттерн: `../mineswarm/docker/gateway/gateway.py`, `_PY4J_SNIPPET` / `_mc_call`).
Альтернатива на будущее — настройка `pythonGatewayBindAll` в моде, чтобы раннер ходил
по docker-сети напрямую; для phase 0 достаточно exec.

В `run/mods` кладётся: свежий `unionclef-*-all.jar` + минимальный набор из
`../mineswarm/game/minecraft/mods/` (fabric-api обязателен; sodium/lithium — по вкусу,
на софтверном рендере лучше оставить).

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

Python + uv (как в `scripts/`). Цикл на каждый клиент:

1. `wait_for_gateway()` — py4j отвечает (порт из лога `Py4j gateway started on port N`,
   не хардкодить 25333).
2. `wait_for_game()` — poll `inGame()` (autoConnectServer делает вход сам).
3. Прогнать сценарии по списку. Команды: `ExecuteCommand("@…")` для altoclef,
   `ChatMessage(";…")` / `ChatMessage("#…")` для tungsten/shredder — tungsten перехватывает
   именно chat send, через `ExecuteCommand` его команды не работают.
4. Assert: позиция (py4j `getPlayersInfo` или RCON), `getHealth()`, `getRecentChat(n)`
   на маркеры ошибок, таймауты по `hasActiveTask()`.
5. При фейле — `getScreenshot()` + хвост `latest.log` в артефакты. Итог — junit.xml.

### Сценарии (стартовый набор)

| id | что делает | критерий | таймаут |
|---|---|---|---|
| smoke | клиент поднялся, py4j жив, зашёл на сервер | `inGame() == true` | 180 c |
| altoclef_goto | `@goto <финиш площадки>` | позиция в finish-боксе | 60 c |
| altoclef_get | `@get log 3` (площадка с лесом) | 3 брёвна в инвентаре (`getInventoryFull`) | 120 c |
| altoclef_follow | бот A `@follow tester2`, бот B бегает по маршруту | дистанция A–B < 6 блоков в конце | 90 c |
| tungsten_parkour_flat | `;goto` по курсу: прямые + 2-блочные гэпы | finish-бокс | 90 c |
| tungsten_parkour_hard | курс с 3-блочными гэпами и подъёмами | finish-бокс | 120 c |
| tungsten_follow | `;followPlayer tester2` | дистанция < 6 блоков, без падений (health) | 90 c |
| shredder_goto | `#goto` через смешанный рельеф | finish-бокс | 120 c |
| shredder_bridge | `#goto` через разрыв, требующий jump bridging | finish-бокс, health == 20 | 120 c |
| gamer_nightly | `@gamer` с нуля | прогресс-маркеры в чате | часы — **только nightly**, не на каждый push |

Два бота (`tester1`/`tester2`) закрывают follow-сценарии; N клиентов в compose — это
просто N сервисов, py4j-порты не конфликтуют (у каждого свой netns).

## Мульти-версии и мульти-инстансы

- **Версии мода** (несколько билдов сразу): каждый клиент-сервис получает свой
  `run/mods-<tag>` с нужным jar'ом — так можно катать текущий билд против предыдущего
  релиза на одном стенде (полезно для сравнения паркур-метрик "стало хуже/лучше").
- **Версии MC**: build уже выдаёт jar на 1.21/1.21.1/1.21.11, но образ mineswarm прибит
  к `fabric:1.21.11:0.19.3` в `startapp.sh`. Для матрицы MC-версий нужен build-arg
  `MC_VERSION` в Dockerfile mineswarm (правка на их стороне, ~полдня). Phase 3, не раньше:
  основная ценность — регрессии на 1.21.11.
- **Масштаб**: клиент ест ~1.5–2 GB RAM; на 48 GB мака спокойно живут 4–6 тест-клиентов
  рядом с боевым стеком CEZ.

## Триггер (автодеплой)

**Вариант A — рекомендуемый: self-hosted GitHub Actions runner на маке.**
Тот же паттерн, что у nettyan-toolkit (push → runner → деплой). Регистрируется runner
с label `mac-mc`, workflow `autotest.yml`: on push в `1.21.11` → checkout → `gradlew build`
→ `deploy/autotest.sh` → junit-отчёт как GH check + артефакты (скриншоты, логи).
Плюсы: статусы прямо на коммитах, артефакты бесплатно, ноль своей инфраструктуры опроса.

**Вариант B — запасной: launchd-поллер.** Скрипт на маке раз в N минут: `git fetch`,
если появился новый коммит — тот же `autotest.sh`, результат в Telegram через toolkit-бот.
Проще завести (не нужен GH-токен на маке), но статусы на коммитах теряются.

В обоих вариантах вся работа происходит на маке — jayra не участвует (требование:
не грузить рабочую машину). Боевой стек mineswarm на маке не трогаем: тест-стенд живёт
в отдельной compose-сети `uctest` с отдельными именами контейнеров.

## Фазы

| Фаза | Содержимое | Оценка |
|---|---|---|
| 0 — смоук | **сделано**: compose.test.yml (сервер + 1 клиент), autotest.sh, runner со слайм-паркуром (`;goto` через bounce), запуск по ssh на маке | — |
| 1 — паркур | мир с курсами + courses.json, tungsten/shredder-сценарии, второй бот + follow, скриншоты при фейлах | 2–3 дня |
| 2 — CI | self-hosted runner на маке, autotest.yml, junit + артефакты, TG-нотификация | ~1 день |
| 3 — матрица | MC_VERSION build-arg в mineswarm-образе, прогон на 1.21.1, сравнение метрик билдов | 1–2 дня |

Фаза 0 уже окупается: ловит "мод не загрузился / крашнулся при джойне / py4j умер" —
класс регрессий, который сейчас обнаруживается вручную за ~10 минут на каждый билд.
Фазы 1+ — это регрессионная сетка для TODO 1.6.3, где каждый фикс симуляции требует
перетеста pathfinder'а: без автотеста этот пункт практически невыполним.

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

## Риски и честные оговорки

- **Софтверный рендер + Rosetta = 10–25 FPS.** Игровая логика тикается на 20 TPS и от FPS
  не зависит, но WindMouse-сглаживание камеры — per-frame; на низком FPS повороты грубее.
  Для паркур-тестов это источник флаков. Митигации: (1) щедрые таймауты и критерий
  "дошёл до финиша", а не "прошёл идеально"; (2) один автоматический ретрай на сценарий;
  (3) настоящий фикс — собрать `mineswarm-mc` под linux/arm64 (Java 21 arm64 + LWJGL
  linux-arm64 natives для 1.21 существуют, PortableMC умеет) — нативная скорость на M4.
  Это правка Dockerfile mineswarm, полдня-день, стоит сделать в фазе 1–2.
- **Движенческие тесты флачные по природе.** Не гнаться за 100% зелёного: скриншот +
  лог при фейле важнее идеальной стабильности. Порог "2 фейла подряд = красный" — норм.
- **`@gamer` — длинный.** Никогда не на push, только nightly с жёстким wall-clock лимитом.
- **py4j слушает loopback** — доступ только через `docker exec` (паттерн mineswarm gateway),
  пока в мод не добавлен bind на 0.0.0.0 (отдельная маленькая задача, с оглядкой на то,
  что порт станет виден в docker-сети).
- **Мир-шаблон в git** — zip мира на пару мегабайт это нормально; не коммитить
  разросшиеся регионы после тестовых прогонов (каждый прогон — из чистой копии).

## Ответ на вопрос "стоит ли свеч"

Да. Сложность умеренная (суммарно ~неделя чистой работы до фазы 2), потому что три самых
дорогих куска — headless-клиент, py4j-мост и деплой jar'а без ребилда — уже написаны и
проверены в бою mineswarm'ом. Пишется по сути только тест-сервер с картой, раннер
сценариев и один workflow. Ценность: мгновенное обнаружение крашей/регрессий на каждый
push и единственный реалистичный способ вести пункты вроде 1.6.3 ("перетест после
каждого фикса") не руками.
