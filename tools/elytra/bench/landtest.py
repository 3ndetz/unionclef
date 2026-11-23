"""Landings: the solver's (tungsten LandingPlan) against the pilot's, on the same legs.

    python landtest.py [repeats] [--modes solver,pilot] [--legs 50,150,300] [--walls] [--no-video] [--all-notes]

In survival, so a hard touchdown hurts: each landing is scored by where the bot stops against the
target, the health it lost, its rockets and time, and what the flight reported of its landing plan.
Every landing is filmed (bench.Video, the game window) unless --no-video.
The flat world's grass is at G (bench.py); every leg starts from the origin of a clean strip.
"""
import argparse
import json
import time

import bench
from bench import F, cmd, mcp, reset, watch


def fly_watch(seconds):
    """flyStatus till the flight ends, and a trace of where the bot was, how fast it sank and its
    health: a landing that hurts shows the tick it hurt."""
    t0, trace, st = time.time(), [], {}
    while time.time() - t0 < seconds:
        st = mcp("flyStatus")
        me = mcp("getGameState")["self"]
        trace.append([round(time.time() - t0, 1), me["pos"], me["hp"], st.get("gliding"), st.get("mode")])
        if st.get("phase") in ("done", "failed", "idle", "stopped"):
            break
        time.sleep(0.3)
    st["seconds"] = round(time.time() - t0, 1)
    return st, trace


def obstacles(tx, tz, on):
    """Across the way in, 12 blocks short of the spot, a stone wall 8 high and 13 wide; round the spot,
    four oak-log posts 6 high, 4 blocks out. With on=False the same blocks are cleared again."""
    block = "stone" if on else "air"
    log = "oak_log" if on else "air"
    cmd(f"fill {tx - 12} {F} {tz - 6} {tx - 12} {F + 7} {tz + 6} minecraft:{block}")
    for dx, dz in ((4, 4), (4, -4), (-4, 4), (-4, -4)):
        cmd(f"fill {tx + dx} {F} {tz + dz} {tx + dx} {F + 5} {tz + dz} minecraft:{log}")


def leg(mode, length, n, walls=False, video=True, all_notes=False):
    x0, z0 = 0, 200 + 40 * n       # a strip of its own for every flight, away from the arena
    reset(x0, z0, yaw=-90)         # facing +x, along the leg
    if walls:
        obstacles(x0 + length, z0, True)
    # full health first: a leg after a hard one started on 6 of 20 and died of what the next one cost
    cmd(f"effect give {bench.BOT} minecraft:instant_health 1 10 true",
        f"gamemode survival {bench.BOT}", f"effect give {bench.BOT} minecraft:saturation 30 5 true")
    time.sleep(1)
    mcp("flySettings", {"solver": mode == "solver"})
    hp0 = mcp("getGameState")["self"]["hp"]
    tx, tz = x0 + length, z0
    v = bench.Video(f"land-{length}{'w' if walls else ''}-{mode}", video)
    mcp("flyTo", {"x": tx, "y": F, "z": tz})
    time.sleep(1)
    st, trace = fly_watch(90)
    time.sleep(2)  # the slide after the touch
    clip = v.stop()
    me = mcp("getGameState")["self"]
    p = [float(v) for v in me["pos"].split(",")]
    out = {"mode": mode, "leg": length, "phase": st.get("phase"), "reason": st.get("reason"),
           "off": round(((p[0] - tx) ** 2 + (p[2] - tz) ** 2) ** 0.5, 1), "hpLost": round(hp0 - me["hp"], 1),
           "rockets": st.get("rocketsUsed"), "seconds": st.get("seconds"), "landing": st.get("landing", ""),
           "video": clip}
    notes = [s for s in st.get("planNotes", []) if all_notes or "landing" in s or "unsafe" in s or "touched" in s]
    out["landingNotes"] = notes if all_notes else notes[-12:]
    if out["hpLost"] > 0:  # where it hurt: the trace round the first drop
        hp = [t[2] for t in trace] + [me["hp"]]
        i = next((k for k in range(1, len(hp)) if hp[k] < hp[0]), len(trace))
        out["landingNotes"] += [f"hurt near: {t}" for t in trace[max(0, i - 4):i + 1]]
    cmd(f"gamemode creative {bench.BOT}")
    if walls:
        obstacles(tx, tz, False)
        out["leg"] = f"{length}+walls"
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("repeats", nargs="?", type=int, default=2)
    ap.add_argument("--modes", default="solver,pilot")
    ap.add_argument("--legs", default="50,150,300")
    ap.add_argument("--walls", action="store_true", help="a wall across the way in and posts round the spot")
    ap.add_argument("--no-video", action="store_true")
    ap.add_argument("--all-notes", action="store_true", help="every note of the flight, not only the landing's")
    a = ap.parse_args()
    rows, n = [], 0
    for r in range(a.repeats):
        for length in map(int, a.legs.split(",")):
            for mode in a.modes.split(","):
                row = leg(mode, length, n, a.walls, not a.no_video, a.all_notes)
                n += 1
                rows.append(row)
                print(json.dumps({k: v for k, v in row.items() if k != "landingNotes"}, ensure_ascii=False), flush=True)
                for s in row["landingNotes"]:
                    print("    ", s, flush=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    (bench.OUT).mkdir(exist_ok=True)
    (bench.OUT / f"landtest-{stamp}.json").write_text(json.dumps(rows, indent=1, ensure_ascii=False), encoding="utf-8")
