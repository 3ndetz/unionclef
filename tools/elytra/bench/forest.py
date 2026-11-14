"""Forest courses: low flight over a forest, a landing in a clearing in it, a flight in under its roof.

    python forest.py [repeats] [--courses over,clearing,under] [--modes solver,pilot] [--spacing 7] [--no-video]

A forest is built from a seed on a strip of its own (z round FZ): oak trunks 5 to 9 high, a crown of
persistent leaves round the top, trees on a grid of `spacing` blocks each moved up to a third of it,
a clearing of radius CLEARING_R round (CLEARING_X, FZ) left empty. In survival, so a tree hurts.

- over: from open ground short of the forest to open ground past it, low=True (the pilot keeps
  LOW_CLEARANCE over the top of what is below). Scored: arrived, tree hits, height over the crowns
  under the path (median, least), health lost, rockets, seconds.
- clearing: the target is the clearing's centre. Scored as landtest: off, health lost, and whether the
  bot is down inside the clearing; tree hits.

- under: a second strip (z round UZ), trunks 9 high on a grid of 8 moved up to 2, and over them one
  roof of leaves, unbroken: no way over it. From open ground to a spot on the ground under the roof,
  UNDER_IN blocks in: the flight has to go in between the trunks. The solver flies with the route
  search on (cells, then the physics plan in their corridor), as through the maze. Scored: down under
  the roof, off, tree hits, health lost, the share of the flight's ticks over the strip spent under
  the roof.

A tree hit is a gliding tick of the flight log with a collision while the feet are over a block above
the ground: a touchdown is a collision too, but at the ground.
"""
import argparse
import json
import math
import random
import time

import bench
from bench import F, cmd, mcp, reset

FZ = -480              # the forest strip's centre line
HALF = 30              # half its width
X0, X1 = 0, 260        # where the forest begins and ends
CLEARING_X, CLEARING_R = 150, 9
START_X = -40          # open ground short of it
PAST_X = X1 + 40       # open ground past it
UZ, UHALF, UX1 = -700, 60, 200   # the roofed strip: z centre, half width (no way in from the side), length
UNDER_IN, ROOF = 100, 9          # the target's way in under the roof; the roof's underside over F


def trees(spacing, seed=7):
    """(x, z, trunk height, crown radius) of every tree: the same forest for the same seed."""
    rnd = random.Random(seed)
    out = []
    for gx in range(X0, X1 + 1, spacing):
        for gz in range(FZ - HALF, FZ + HALF + 1, spacing):
            x = gx + rnd.randint(-spacing // 3, spacing // 3)
            z = gz + rnd.randint(-spacing // 3, spacing // 3)
            h, r = rnd.randint(5, 9), rnd.choice((1, 2, 2))
            if math.hypot(x - CLEARING_X, z - FZ) < CLEARING_R + r + 1 or not X0 <= x <= X1:
                continue
            out.append((x, z, h, r))
    return out


def crown_top(forest):
    """Height of the top of the crowns per (x, z) column: the canopy the flight is scored against."""
    top = {}
    for x, z, h, r in forest:
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                k = (x + dx, z + dz)
                top[k] = max(top.get(k, F), F + h + 1)
    return top


def build(forest):
    cmd(f"forceload add {START_X - 20} {FZ - HALF - 16} {PAST_X + 20} {FZ + HALF + 16}")
    for x in range(START_X - 20, PAST_X + 21, 24):  # clear the strip (fill is capped at 32768 blocks)
        cmd(f"fill {x} {F} {FZ - HALF - 8} {min(x + 23, PAST_X + 20)} {F + 14} {FZ + HALF + 8} minecraft:air")
    for x, z, h, r in forest:
        cmd(f"fill {x - r} {F + h - 2} {z - r} {x + r} {F + h} {z + r} minecraft:oak_leaves[persistent=true]",
            f"fill {x} {F + h + 1} {z} {x} {F + h + 1} {z} minecraft:oak_leaves[persistent=true]",
            f"fill {x} {F} {z} {x} {F + h - 1} {z} minecraft:oak_log")


def trunks(spacing=8, seed=11):
    rnd = random.Random(seed)
    return [(gx + rnd.randint(-2, 2), gz + rnd.randint(-2, 2))
            for gx in range(X0 + 4, UX1 - 3, spacing) for gz in range(UZ - UHALF + 4, UZ + UHALF - 3, spacing)]


def build_under():
    cmd(f"forceload add {START_X - 20} {UZ - UHALF - 16} {UX1 + 20} {UZ + UHALF + 16}")
    for x in range(START_X - 20, UX1 + 21, 12):  # fill is capped at 32768 blocks; +30 north: an older, narrower strip
        cmd(f"fill {x} {F} {UZ - UHALF - 8} {min(x + 11, UX1 + 20)} {F + 14} {UZ + UHALF + 30} minecraft:air")
    for x in range(X0, UX1 + 1, 100):
        cmd(f"fill {x} {F + ROOF} {UZ - UHALF} {min(x + 99, UX1)} {F + ROOF + 1} {UZ + UHALF} "
            "minecraft:oak_leaves[persistent=true]")
    for x, z in trunks():
        cmd(f"fill {x} {F} {z} {x} {F + ROOF - 1} {z} minecraft:oak_log")


def log_since(t0):
    rows = mcp("flightLog", {"n": 2400}).get("rows") or []
    return [r for r in rows if r and r[0] > t0]  # an empty log is one null row


def flight(course, mode, n, forest, top, video):
    z = (UZ if course == "under" else FZ) + (n % 5 - 2) * (4 if course == "under" else 8)  # five lanes
    tx, tz = {"over": (PAST_X, z), "clearing": (CLEARING_X, FZ), "under": (X0 + UNDER_IN, UZ)}[course]
    reset(START_X, z, yaw=-90)
    cmd(f"effect give {bench.BOT} minecraft:instant_health 1 10 true",
        f"gamemode survival {bench.BOT}", f"effect give {bench.BOT} minecraft:saturation 30 5 true")
    time.sleep(1)
    route = mode == "solver" and course == "under"
    mcp("flySettings", {"solver": mode == "solver", "routeSearch": route, "routePlan": route})
    hp0 = mcp("getGameState")["self"]["hp"]
    last = [r for r in mcp("flightLog", {"n": 1}).get("rows") or [] if r] or [[0]]
    v = bench.Video(f"forest-{course}-{mode}", video)
    mcp("flyTo", {"x": tx, "y": F, "z": tz, "low": course == "over"})
    t0, st = time.time(), {}
    while time.time() - t0 < 120:
        st = mcp("flyStatus")
        if st.get("phase") in ("done", "failed", "idle", "stopped"):
            break
        time.sleep(0.4)
    time.sleep(2)
    clip = v.stop()
    me = mcp("getGameState")["self"]
    p = [float(c) for c in me["pos"].split(",")]
    rows = log_since(last[-1][0])
    hits = [r for r in rows if r[10] and r[2] > F + 1.01]
    over = [r[2] - top[(math.floor(r[1]), math.floor(r[3]))] for r in rows
            if (math.floor(r[1]), math.floor(r[3])) in top and X0 <= r[1] <= X1]
    over.sort()
    out = {"course": course, "mode": mode, "phase": st.get("phase"), "reason": st.get("reason"),
           "off": round(math.hypot(p[0] - tx, p[2] - tz), 1), "hpLost": round(hp0 - me["hp"], 1),
           "treeHits": len(hits), "rockets": st.get("rocketsUsed"), "seconds": round(time.time() - t0, 1),
           "overCrowns": {"median": round(over[len(over) // 2], 1), "least": round(over[0], 1)} if over else None,
           "landing": st.get("landing", ""), "video": clip}
    if course == "under":
        strip = [r for r in rows if X0 <= r[1] <= UX1 and abs(r[3] - UZ) <= UHALF]
        out["underShare"] = round(sum(r[2] < F + ROOF - 1.8 for r in strip) / len(strip), 2) if strip else 0
        out["downUnder"] = X0 <= p[0] <= UX1 and abs(p[2] - UZ) <= UHALF and p[1] < F + 1
    if course == "clearing":
        out["inClearing"] = math.hypot(p[0] - CLEARING_X, p[2] - FZ) <= CLEARING_R and p[1] < F + 1
    if hits:
        out["firstHit"] = [round(c, 1) for c in hits[0][1:4]]
    out["landingNotes"] = [s for s in st.get("planNotes", []) if "landing" in s or "unsafe" in s or "touched" in s][-14:]
    cmd(f"gamemode creative {bench.BOT}")
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("repeats", nargs="?", type=int, default=2)
    ap.add_argument("--courses", default="over,clearing")
    ap.add_argument("--modes", default="solver,pilot")
    ap.add_argument("--spacing", type=int, default=7, help="blocks between trees (before the jitter)")
    ap.add_argument("--no-build", action="store_true", help="the forest is up already")
    ap.add_argument("--no-video", action="store_true")
    a = ap.parse_args()
    forest = trees(a.spacing)
    top = crown_top(forest)
    if not a.no_build:
        if "over" in a.courses or "clearing" in a.courses:
            build(forest)
            print(f"forest: {len(forest)} trees, spacing {a.spacing}", flush=True)
        if "under" in a.courses:
            build_under()
            print(f"under the roof: {len(trunks())} trunks", flush=True)
    rows, n = [], 0
    for _ in range(a.repeats):
        for course in a.courses.split(","):
            for mode in a.modes.split(","):
                row = flight(course, mode, n, forest, top, not a.no_video)
                n += 1
                rows.append(row)
                print(json.dumps({k: v for k, v in row.items() if k != "landingNotes"}, ensure_ascii=False), flush=True)
                for s in row["landingNotes"]:
                    print("    ", s, flush=True)
    bench.OUT.mkdir(exist_ok=True)
    (bench.OUT / f"forest-{time.strftime('%Y%m%d-%H%M%S')}.json").write_text(
        json.dumps(rows, indent=1, ensure_ascii=False), encoding="utf-8")
