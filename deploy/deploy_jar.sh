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

# ...AND THE SAME CHECK FOR THE JARS INSIDE IT. A fresh outer jar can still carry a stale
# shredder/tungsten nested jar, which is how a client-tick freeze fix was measured as failed
# while the deployed bytecode still had the unbounded join it removed. See check_nested_fresh.py.
python deploy/check_nested_fresh.py "$JAR" || exit 1

mkdir -p deploy/run/mods
rm -f deploy/run/mods/unionclef-*.jar
cp "$JAR" deploy/run/mods/
echo "deployed: $(basename "$JAR")"

CONTAINERS="${*:-uctest-mc-tester1 uctest-mc-tester2}"
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
        if ! UCTEST_MCP_PORT="${UCTEST_MCP_PORT:-25350}" docker compose -f deploy/compose.test.yml                 up -d --force-recreate "$svc" >/dev/null 2>&1; then
            # The in-mod MCP port is often taken on a dev box (a local Minecraft client binds
            # the same one). Fall back to a free host port — the harness talks py4j through
            # `docker exec`, so nothing in the tests depends on that mapping.
            alt=$((25350 + $$ % 100 + 1))
            echo "  MCP port busy — recreating $svc with UCTEST_MCP_PORT=$alt"
            UCTEST_MCP_PORT="$alt" docker compose -f deploy/compose.test.yml                 up -d --force-recreate "$svc" >/dev/null
        fi
        echo "recreated: $c"
    fi
done

# wait for the py4j bridge so callers can run a suite straight after
for c in $CONTAINERS; do
    docker ps --format '{{.Names}}' | grep -qx "$c" || continue
    printf "waiting for %s py4j" "$c"
    until docker exec "$c" python3 -c "
from py4j.java_gateway import JavaGateway, GatewayParameters
gw=JavaGateway(gateway_parameters=GatewayParameters(address='127.0.0.1',port=25333,auto_convert=True))
gw.entry_point.getGameState(); gw.close()
" >/dev/null 2>&1; do printf "."; sleep 10; done
    echo " up"
done
