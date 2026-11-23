"""The sky course of skycourse.py rebuilt on the local flat test server, to debug flights on it before
flying the real one: the same rings, spacing and turn, over a platform hung 60 blocks over this
world's ground (open air under the turn, as there), the rings as high over it as over theirs.

    python skycopy.py build | clear | fly [N] [nick]
"""
import json
import sys
import time

import bench
import skycourse as sky
from bench import F, cmd, mcp

DX, DZ = 1100, 600                   # moved into open flat ground: x -800..-575 -> 300..525
PLATFORM_Y = F - 1 + 60              # the platform hangs 60 over this world's grass, as it hangs there
DY = PLATFORM_Y - sky.TOP            # its top 114 -> PLATFORM_Y
PLATFORM_X = (-635 + DX, -563 + DX)  # its part in the zone, from the western edge to the zone's end


def rings():
    return [(x + DX, cy + DY, cz + DZ, colour) for x, cy, cz, colour in sky.RINGS]


def build():
    xs = [r[0] for r in rings()]
    cmd(f"forceload add {min(xs) - 60} {sky.SPINE_Z + DZ - 32} {max(xs) + 60} {sky.SPINE_Z + DZ + 32}")
    z0, z1 = sky.ZONE_Z[0] + DZ, sky.ZONE_Z[1] + DZ
    cmd(f"fill {PLATFORM_X[0]} {PLATFORM_Y} {z0} {PLATFORM_X[1]} {PLATFORM_Y} {z1} minecraft:grass_block")
    for x, cy, cz, colour in rings():
        cmd(f"fill {x} {cy - 4} {cz - 4} {x} {cy + 4} {cz + 4} minecraft:{colour}_concrete",
            f"fill {x} {cy - 3} {cz - 3} {x} {cy + 3} {cz + 3} minecraft:air")


def clear():
    z0, z1 = sky.ZONE_Z[0] + DZ, sky.ZONE_Z[1] + DZ
    cmd(f"fill {PLATFORM_X[0]} {PLATFORM_Y} {z0} {PLATFORM_X[1]} {PLATFORM_Y} {z1} minecraft:air")
    for x, cy, cz, _ in rings():
        cmd(f"fill {x} {cy - 4} {cz - 4} {x} {cy + 4} {cz + 4} minecraft:air")


def fly(nick):
    sx, sz = sky.START[0] + DX, sky.START[1] + DZ
    # A flight still under way carries on gliding from wherever it is put: stop it first, and see
    # the bot standing on the platform before the course starts.
    mcp("flyStop", nick=nick)
    for _ in range(5):
        bench.reset(sx, sz, nick=nick, y=PLATFORM_Y + 1, yaw=90)  # on the platform, facing west
        p = bench.pos(nick)
        if p and abs(p[0] - sx - 0.5) < 2 and abs(p[2] - sz - 0.5) < 2 and abs(p[1] - PLATFORM_Y - 1) < 1:
            break
    mcp("flySettings", {"solver": True}, nick=nick)
    pts = ";".join(f"{x + 0.5},{y + 0.5},{z + 0.5}" for x, y, z, _ in rings())
    mcp("flyRings", {"points": pts}, nick=nick)
    st, path = bench.watch("flyStatus", 240, nick=nick)
    out = {k: st.get(k) for k in ("phase", "reason", "ringMiss", "rocketsUsed", "seconds")}
    out["top"] = max(p[1] for p in path) - (F - 1) if path else None
    print(json.dumps(out), flush=True)
    return out


def race(gap=0.7, video=True):
    """All clients through the course one after another, `gap` seconds apart, the bot last: filmed
    from behind it, the two ahead in view at every ring."""
    sx, sz = sky.START[0] + DX, sky.START[1] + DZ
    order = [n for n in bench.NICKS if n != bench.BOT] + [bench.BOT]
    pts = ";".join(f"{x + 0.5},{y + 0.5},{z + 0.5}" for x, y, z, _ in rings())
    for i, nick in enumerate(order):
        mcp("flyStop", nick=nick)
        bench.reset(sx + 4 * i, sz + (2 if i % 2 else -2) * (i > 0), nick=nick, y=PLATFORM_Y + 1, yaw=90)
        mcp("flySettings", {"solver": True}, nick=nick)
    if video:
        mcp("camera", {"perspective": 1, "hud": False})
        mcp("recordStart", {"name": "skycopy-race", "fps": 30})
    for nick in order:
        mcp("flyRings", {"points": pts}, nick=nick)
        time.sleep(gap)
    sts = {}
    t0 = time.time()
    while time.time() - t0 < 120:
        sts = {n: mcp("flyStatus", nick=n) for n in order}
        if all(st.get("phase") in ("done", "failed", "idle") for st in sts.values()):
            break
        time.sleep(1)
    if video:
        time.sleep(2)
        mcp("recordStop")
        mcp("camera", {"perspective": 0, "hud": True})
    for n, st in sts.items():
        print(n, json.dumps({k: st.get(k) for k in ("phase", "reason", "ringMiss", "rocketsUsed")}), flush=True)


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "build"
    if what == "build":
        build()
    elif what == "clear":
        clear()
    elif what == "race":
        race(float(sys.argv[2]) if len(sys.argv) > 2 else 0.7)
    elif what == "fly":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 1
        nick = sys.argv[3] if len(sys.argv) > 3 else bench.BOT
        for _ in range(n):
            fly(nick)
            time.sleep(2)
