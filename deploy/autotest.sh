#!/bin/sh
# unionclef autotest entry (docs/AUTOTESTING.md phase 0) — run on the docker
# host (the Mac). Builds the mod, prepares the client mods dir from the
# mineswarm set + fresh jar, (re)starts the test stand, runs the slime test.
#
#   deploy/autotest.sh [--no-build]
set -e
cd "$(dirname "$0")/.."

MINESWARM_MODS="${MINESWARM_MODS:-$HOME/repos/pet/mineswarm/game/minecraft/mods}"
JAR_DIR="versions/1.21.11/build/libs"

if [ "$1" != "--no-build" ]; then
    ./gradlew build -x check --console=plain
fi

JAR=$(ls "$JAR_DIR"/unionclef-1.21.11-*.jar | grep -v -- '-all\|-sources' | head -1)
[ -n "$JAR" ] || { echo "no jar in $JAR_DIR"; exit 1; }

mkdir -p deploy/run/mods deploy/run/data/tester1
rm -f deploy/run/mods/*.jar
cp "$MINESWARM_MODS"/*.jar deploy/run/mods/
rm -f deploy/run/mods/unionclef-*.jar
cp "$JAR" deploy/run/mods/
echo "deployed: $(basename "$JAR")"

docker compose -f deploy/compose.test.yml up -d
docker compose -f deploy/compose.test.yml restart mc-tester1

python3 deploy/runner/slime_test.py
