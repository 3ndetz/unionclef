#!/bin/sh
# Deploy the freshly built unionclef jar to the local stand and restart the clients.
#
#   deploy/deploy_jar.sh [container ...]     (default: uctest-mc-tester1 uctest-mc-tester2)
#
# WHY THIS EXISTS. Deploying by hand with `cp` leaves the PREVIOUS version's jar in
# run/mods, because the filename carries mod_version and therefore changes on every
# bump. Fabric then has two jars claiming the same mod id and loads one of them — and
# on 2026-07-27 that silently ran a whole nav-suite comparison against the OLD jar,
# producing results identical to the baseline and nearly costing a wrong conclusion
# about the search rework. Always wipe, then copy exactly one.
set -e
cd "$(dirname "$0")/.."

JAR_DIR="versions/1.21.11/build/libs"
# newest first: stale jars from older mod_versions linger in build/libs too
JAR=$(ls -t "$JAR_DIR"/unionclef-1.21.11-*.jar 2>/dev/null | grep -v -- '-all\|-sources' | head -1)
[ -n "$JAR" ] || { echo "no jar in $JAR_DIR — build first"; exit 1; }

# ...AND THE JAR MUST BE NEWER THAN THE SOURCE IT CLAIMS TO CARRY.
#
# deploy_jar.sh does not build; it ships the newest jar in build/libs. `gradlew compileJava`
# produces CLASSES and no jar, so a session that compiles-then-deploys ships whatever jar was
# lying there -- and the bench reports clean results for code that was never loaded. That cost a
# ten-run mine_coal batch measured against a three-hour-old jar, whose new counter simply never
# appeared; the run looked normal, because a stale jar looks exactly like a fresh one.
#
# Same failure family as the nested-jar check below, one level further out. Refusing is the right
# default for a MEASUREMENT bench: a loud stop costs a build, a silent stale deploy costs a
# conclusion. UCTEST_ALLOW_STALE=1 for the rare deliberate replay of an older jar.
# Compared against the compiled CLASSES, not the .java sources. Gradle's up-to-date check is
# content-hashed, so a touch or a git checkout rewinds no bytecode and must not raise an alarm --
# a guard that cries wolf is one that gets switched off. Classes are rewritten only when the code
# actually changed, which makes "classes newer than jar" exactly the compileJava-then-deploy case
# this exists to catch, and nothing else.
NEWEST_CLS=$(find versions/*/build/classes build/classes tungsten/build/classes shredder/build/classes     -name '*.class' -newer "$JAR" -print -quit 2>/dev/null)
if [ -n "$NEWEST_CLS" ]; then
    echo "STALE JAR: $JAR is older than compiled bytecode ($NEWEST_CLS)" >&2
    echo "  the bench would measure code you did not build -- run:  ./gradlew :1.21.11:build" >&2
    echo "  (set UCTEST_ALLOW_STALE=1 to deploy the old jar deliberately)" >&2
    [ "${UCTEST_ALLOW_STALE:-0}" = "1" ] || exit 1
    echo "  UCTEST_ALLOW_STALE=1 -- continuing with the stale jar" >&2
fi

# ...AND THE SAME CHECK FOR THE JARS INSIDE IT. A fresh outer jar can still carry a stale
# shredder/tungsten nested jar, which is how a client-tick freeze fix was measured as failed
# while the deployed bytecode still had the unbounded join it removed. See check_nested_fresh.py.
python deploy/check_nested_fresh.py "$JAR" || exit 1

mkdir -p deploy/run/mods
rm -f deploy/run/mods/unionclef-*.jar
cp "$JAR" deploy/run/mods/
echo "deployed: $(basename "$JAR")"

CONTAINERS="${*:-uctest-mc-tester1 uctest-mc-tester2}"
# ---------------------------------------------------------------------------------------
# GPU, IF THERE IS ONE. The image pins LIBGL_ALWAYS_SOFTWARE=1 and GALLIUM_DRIVER=llvmpipe,
# so the clients rasterise on the CPU. That is the bench's ceiling: the flat arena holds
# 28-30 fps because there is nothing to draw, while the survival world falls to 7-8 against a
# validity floor of 12 -- which is why the playthrough has been unmeasurable all week.
#
# deploy/compose.gpu.yml flips those two variables and asks for the device. It is a SEPARATE
# file on purpose: `deploy.resources.reservations.devices` makes compose FAIL on a host with
# no GPU rather than degrade, so it must never be in the always-loaded file.
#
# The probe is the honest one -- actually run nvidia-smi inside a throwaway container. Asking
# `docker info` for the runtime only proves the runtime is INSTALLED, which on a laptop with
# no card is exactly the false yes that would break every CPU-only run.
#
# AND IT PROBES THE RUNTIME, NOT ONLY THE CARD. A present GPU is not a working renderer:
# Mesa's d3d12 driver needs libd3d12core.so, the image ships only libdxcore.so, and with the
# card visible but the runtime missing the client dies at GL context creation with no error
# at all -- the log simply stops at `Backend library: LWJGL version 3.3.3-snapshot` and the
# JVM is gone. That is precisely the false yes this second check exists to catch, and it cost
# a broken bench to learn. The runtime is mounted from the Docker Desktop VM (compose.gpu.yml).
#
#   UCTEST_GPU=0  never use it.   UCTEST_GPU=1  insist (and fail loudly if it is not there).
#
# AND IT REMEMBERS A NO. The probe cannot answer the question that actually matters -- "can this
# client create a GL context on it" -- without starting a client, which takes minutes. So the
# deploy answers it the only honest way, by trying, and then WRITES DOWN the answer: if the
# clients fail to come up on the GPU, deploy/.gpu_unusable is created and later deploys skip
# straight to the CPU. Without that marker every single deploy pays the same failed attempt
# (300 s and a double recreate) to relearn a fact about the machine that has not changed.
# Delete the marker to try again (after a driver update, or another rendering path):
#     rm deploy/.gpu_unusable
# UCTEST_GPU=1 ignores the marker.
GPU_MARK="deploy/.gpu_unusable"
GPU_ARGS=""
if [ -f "$GPU_MARK" ] && [ "${UCTEST_GPU:-auto}" != "1" ]; then
    echo "GPU: skipped -- $GPU_MARK says a client could not render on it here"
    echo "GPU:   ($(cat "$GPU_MARK" 2>/dev/null | head -1))"
elif [ "${UCTEST_GPU:-auto}" != "0" ]; then
    # MSYS_NO_PATHCONV: under Git Bash on Windows, /usr/lib/... in an ARGUMENT is rewritten to a
    # Windows path before docker ever sees it, and the probe fails with a baffling
    # "mkdir C:\Program Files\Git\usr\lib\wsl: Access is denied". It is a no-op elsewhere, and
    # compose is unaffected because it parses the volume out of the YAML rather than the shell.
    if MSYS_NO_PATHCONV=1 timeout 90 docker run --rm --gpus all -v /usr/lib/wsl/lib:/wsl:ro mineswarm-mc:amd64 \
           sh -c 'nvidia-smi -L >/dev/null 2>&1 && [ -f /wsl/libd3d12core.so ]' >/dev/null 2>&1; then
        GPU_ARGS="-f deploy/compose.gpu.yml"
        echo "GPU: detected with a usable D3D12 runtime, clients will render on it"
    elif [ "${UCTEST_GPU:-auto}" = "1" ]; then
        echo "ERROR: UCTEST_GPU=1 but no GPU + D3D12 runtime answered from a container" >&2; exit 1
    else
        echo "GPU: none usable, clients stay on llvmpipe (CPU) -- this is fine, just slower"
    fi
fi
# RECREATING AND WAITING ARE FUNCTIONS BECAUSE THE GPU PATH HAS TO BE RETRACTABLE.
# Rendering on the GPU is an optimisation, and an optimisation must never be able to leave the
# bench without clients. So the deploy TRIES the GPU, checks whether py4j actually answered,
# and if it did not, puts the clients back on the CPU by itself. The requirement is that a
# machine with only a CPU works -- and the same code path covers a machine whose GPU is
# present but unusable, which is the harder case and the one that actually bit.
recreate_clients() {
  _gpu="$1"
  for c in $CONTAINERS; do
    # A STOPPED CLIENT IS NOT A CLIENT TO SKIP -- IT IS ONE TO BRING BACK.
    # This loop only acted on RUNNING containers, so once the clients were stopped (which is the
    # right thing to do between runs: they burn ~390% CPU each even idle) deploy became a no-op for
    # them and printed nothing about it. Two suites afterwards failed with "tester1 py4j: timed out
    # after 600s" and were reported as gate FAILURES -- a stand with no client at all, recorded as
    # the bot failing a course.
    if ! docker ps --format '{{.Names}}' | grep -qx "$c"        && docker ps -a --format '{{.Names}}' | grep -qx "$c"; then
        echo "  $c was stopped — starting it"
        docker start "$c" >/dev/null 2>&1 || true
        sleep 5
    fi
    if docker ps --format '{{.Names}}' | grep -qx "$c"; then
        # prove exactly one jar is present INSIDE the container before restarting
        n=$(docker exec "$c" sh -c 'ls /mc-data/mods/unionclef-*.jar 2>/dev/null | wc -l')
        if [ "$n" != "1" ]; then
            echo "ERROR: $c sees $n unionclef jars, expected 1"; exit 1
        fi
        # RECREATE, DO NOT RESTART. The client ages inside a long-lived container: measured
        # 8-10 fps after a session of runs, and 13.4-18.3 straight after recreating it. That
        # matters because the courses are fps-sensitive — nav_slime lands on the pad every
        # time above ~13 fps and misses below ~10 — so a restart-only deploy quietly turns
        # later measurements into noise. `docker compose restart` does NOT clear it; only a
        # full recreate does.
        # START FROM THE SHIPPED DEFAULTS, NOT FROM A SNAPSHOT OF OLD ONES.
        # altoclef writes its WHOLE settings object to altoclef_settings.json, so the file on the
        # stand is a frozen copy of whatever the defaults were the first time it ran -- and it wins
        # over the code. Measured: useCraftingBookToCraft was changed to false (the user's
        # requirement: plenty of servers disable the recipe book), and the stand kept crafting
        # through the book for days because its file still said true. A fifteen-minute @gamer run
        # spent itself in that path -- cgSent=1955 recipe clicks, cgOutReady=0, the grid filled and
        # cleared 1955 times and not one plank made.
        # Deleting it makes the mod write a fresh one from the current defaults, which is the only
        # way the bench measures what actually ships. Anything a course needs set differently, the
        # course sets itself.
        docker exec "$c" sh -c 'rm -f /mc-data/altoclef/altoclef_settings.json' 2>/dev/null || true
        svc=$(echo "$c" | sed 's/^uctest-//')
        if ! UCTEST_MCP_PORT="${UCTEST_MCP_PORT:-25350}" docker compose -f deploy/compose.test.yml $_gpu up -d --force-recreate "$svc" >/dev/null 2>&1; then
            # The in-mod MCP port is often taken on a dev box (a local Minecraft client binds
            # the same one). Fall back to a free host port — the harness talks py4j through
            # `docker exec`, so nothing in the tests depends on that mapping.
            alt=$((25350 + $$ % 100 + 1))
            echo "  MCP port busy — recreating $svc with UCTEST_MCP_PORT=$alt"
            UCTEST_MCP_PORT="$alt" docker compose -f deploy/compose.test.yml $_gpu up -d --force-recreate "$svc" >/dev/null
        fi
        echo "recreated: $c"
    fi
  done
}

# Wait for the py4j bridge so callers can run a suite straight after.
#
# ⛔ BOUNDED, because the loop used to be `until ...; do sleep 10; done` with no way out. A
# client that never comes up is not a slow client, and waiting for it for ever turns a broken
# deploy into a hung terminal with no diagnosis -- which is exactly what the first GPU attempt
# did. Returning non-zero is what makes the fallback below possible at all.
#   $1 = seconds to allow. Startup is ~90 s on an idle box, and this machine is often not idle.
wait_py4j() {
  _budget="$1"
  for c in $CONTAINERS; do
    docker ps --format '{{.Names}}' | grep -qx "$c" || continue
    printf "waiting for %s py4j" "$c"
    _waited=0
    until docker exec "$c" python3 -c "
from py4j.java_gateway import JavaGateway, GatewayParameters
gw=JavaGateway(gateway_parameters=GatewayParameters(address='127.0.0.1',port=25333,auto_convert=True))
gw.entry_point.getGameState(); gw.close()
" >/dev/null 2>&1; do
        if [ "$_waited" -ge "$_budget" ]; then
            echo " NO ANSWER after ${_budget}s"
            return 1
        fi
        printf "."; sleep 10; _waited=$((_waited + 10))
    done
    echo " up"
  done
  return 0
}

# GPU gets a shorter leash than CPU: if it is going to fail it fails at GL context creation,
# seconds in, so a long budget only delays the fallback. The CPU attempt gets the generous one
# because a loaded box genuinely is slow to start a client, and there is nothing to retreat to.
recreate_clients "$GPU_ARGS"
if ! wait_py4j "${UCTEST_PY4J_WAIT:-$([ -n "$GPU_ARGS" ] && echo 300 || echo 600)}"; then
    if [ -n "$GPU_ARGS" ]; then
        echo "GPU: clients did not come up on it -- retreating to CPU and recreating."
        echo "GPU:   (rendering is an optimisation; it does not get to break the bench)"
        printf 'no client came up on the GPU here; see docs/AUTOTESTING.md (GLX/Xvfb)
' > "$GPU_MARK"
        GPU_ARGS=""
        recreate_clients ""
        if ! wait_py4j "${UCTEST_PY4J_WAIT:-600}"; then
            echo "ERROR: clients did not come up on the CPU either -- the stand is broken" >&2
            exit 1
        fi
        echo "clients are up on llvmpipe (CPU). Bench is usable; the GPU override stayed off."
    else
        echo "ERROR: clients did not come up -- the stand is broken" >&2
        exit 1
    fi
fi
