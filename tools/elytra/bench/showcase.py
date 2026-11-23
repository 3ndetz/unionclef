"""A show course flown by up to three clients, filmed from the bot's client in third person with the
HUD off (the mod's recorder, 1920x1080) -- and the pursuit check.

    python showcase.py build          -- build the course (idempotent)
    python showcase.py solo           -- the bot flies the course
    python showcase.py race           -- all clients fly it, two seconds apart
    python showcase.py chase          -- the second client flies it, the others chase it (flyFollow)
    python showcase.py follow         -- pursuit check: the second client flies a plain route, the bot
                                         follows on the solver and the third on the PI pilot
    python showcase.py maze           -- the bot flies the maze hall (bench.maze) with the route search
    python showcase.py maze3          -- all clients through the maze, three seconds apart
    python showcase.py maze2          -- the bot through the compact maze (bench.maze_compact)
    python showcase.py maze2x3        -- all clients through the compact maze
    python showcase.py maze2old       -- the bot through the compact maze, its corners flown as rings
                                         (before the physics plan), for a before/after
    ... [--no-video] [--hud]

The course, east along z = 200 from x = 0: three rings climbing, a glass tunnel 40 long with a 7x7
bore, a slalom of four gates, a dive through a low ring by the ground, landing 60 blocks on.
Every ring counts when the flight crosses its plane; flyStatus ringMiss gives blocks off centre.
"""
import json
import sys
import time

import bench
from bench import BOT, F, NICKS, Video, cmd, mcp, pos

Z = 200
TUNNEL_Y = F + 28
COURSE = [  # (x, y, z, frame colour): ring centres are the middle blocks of a 7x7 bore
    (60, F + 12, Z, "red"), (110, F + 20, Z, "orange"), (160, F + 28, Z, "yellow"),
    (205, TUNNEL_Y, Z, "lime"), (230, TUNNEL_Y, Z, None), (255, TUNNEL_Y, Z, "lime"),   # the tunnel
    # slalom: +-10 sideways every 40 was too sharp for an elytra (it went round a gate)
    (300, F + 22, Z - 6, "light_blue"), (345, F + 18, Z + 6, "blue"),
    (390, F + 16, Z - 6, "purple"), (435, F + 14, Z + 6, "magenta"),
    (490, F + 5, Z, "pink"),                                                            # the dive
]


def build():
    cmd("forceload add -16 160 620 240", "gamerule send_command_feedback false", "time set 6000", "weather clear")
    cmd(f"fill 280 {F} {Z-20} 520 {F+40} {Z+20} minecraft:air")  # gates move when the course is tuned
    y = TUNNEL_Y
    cmd(f"fill 208 {y-4} {Z-4} 252 {y+4} {Z+4} minecraft:glass hollow",
        f"fill 208 {y-3} {Z-3} 208 {y+3} {Z+3} minecraft:air",
        f"fill 252 {y-3} {Z-3} 252 {y+3} {Z+3} minecraft:air")
    for x, y, z, colour in COURSE:
        if colour is None:
            continue
        if x in (205, 255):  # the tunnel's mouths: a frame just outside the glass
            x = 207 if x == 205 else 253
        cmd(f"fill {x} {y-4} {z-4} {x} {y+4} {z+4} minecraft:{colour}_concrete",
            f"fill {x} {y-3} {z-3} {x} {y+3} {z+3} minecraft:air")
    for x, y, z, _ in COURSE[6:]:
        cmd(f"setblock {x} {y+5} {z} minecraft:lantern")


def points():
    return ";".join(f"{x + 0.5},{y + 0.5},{z + 0.5}" for x, y, z, _ in COURSE)


def kit(nick, x, z):
    cmd(f"gamemode creative {nick}", f"clear {nick}", f"give {nick} minecraft:elytra",
        f"give {nick} minecraft:firework_rocket 64", f"tp {nick} {x} {F} {z} -90 0")


def wait_airborne(nick, seconds=20):
    t0 = time.time()
    while time.time() - t0 < seconds:
        p = pos(nick)
        if p and p[1] > F + 6:
            return
        time.sleep(0.3)


def fly_maze(all_three, video, hud, compact=False, old=False):
    if compact:
        bench.maze_compact()
    else:
        bench.maze()
    mz, gx = (bench.MAZE2_Z, bench.MAZE2_X1 - 15) if compact else (bench.MAZE_Z, 820)
    flyers = NICKS if all_three else [BOT]
    for nick in NICKS:
        mcp("ExecuteCommand", {"cmd": "@stop"}, nick=nick)
        mcp("flyStop", nick=nick)
    for i, nick in enumerate(flyers):
        mcp("flySettings", {"solver": True}, nick=nick)
        mcp("flySettings", {"routeSearch": True, "routePlan": not old}, nick=nick)
        # one take-off line, 8 apart across it: from further back the far end is out of sight
        kit(nick, 560, mz - 8 + 8 * i)
    time.sleep(2)
    mcp("camera", {"perspective": 1, "hud": hud})
    v = Video(("maze2" if compact else "maze") + ("x3" if all_three else "") + ("-old" if old else ""), video)
    t0 = time.time()
    for nick in flyers:
        mcp("flyTo", {"x": gx, "y": F, "z": mz}, nick=nick)
        time.sleep(3 if all_three else 0)
    while time.time() - t0 < 120:
        sts = {n: mcp("flyStatus", nick=n) for n in flyers}
        if all(st.get("phase") in ("done", "failed", "idle") for st in sts.values()):
            break
        time.sleep(1)
    time.sleep(3)
    sts = {n: mcp("flyStatus", nick=n) for n in flyers}
    for nick in flyers:
        mcp("flySettings", {"routeSearch": False, "routePlan": True}, nick=nick)
    mcp("camera", {"perspective": 0, "hud": True})
    out = {n: {k: st.get(k) for k in ("phase", "reason", "rocketsUsed", "routeSearch", "pos")} for n, st in sts.items()}
    for n, st in sts.items():
        p = [float(v) for v in str(st.get("pos", "0,0,0")).split(",")]
        out[n]["inside"] = bench.in_maze(p, compact)
    out["seconds"] = round(time.time() - t0)
    out["video"] = v.stop()
    print(json.dumps(out, ensure_ascii=False, indent=1))


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "solo"
    video = "--no-video" not in sys.argv
    hud = "--hud" in sys.argv
    build()
    if mode == "build":
        return
    if mode in ("maze", "maze3", "maze2", "maze2x3", "maze2old"):
        return fly_maze(mode in ("maze3", "maze2x3"), video, hud, mode.startswith("maze2"), mode == "maze2old")
    lead = NICKS[1] if len(NICKS) > 1 else BOT
    for i, nick in enumerate(NICKS):
        mcp("ExecuteCommand", {"cmd": "@stop"}, nick=nick)
        mcp("flyStop", nick=nick)
        on = not (mode == "follow" and i == 2)  # follow: the third client keeps the PI pilot
        mcp("flySettings", {"solver": on}, nick=nick)
    starts = [(-6, Z), (0, Z + 6), (-6, Z + 12)]
    if mode == "race":
        starts = [(-30, Z), (0, Z), (-60, Z)]
    for nick, (x, z) in zip(NICKS, starts):
        kit(nick, x, z)
    time.sleep(2)
    mcp("camera", {"perspective": 1, "hud": hud})
    v = Video(mode, video)
    t0 = time.time()
    if mode in ("chase", "follow"):
        if mode == "chase":
            mcp("flyRings", {"points": points()}, nick=lead)
        else:
            mcp("flyRoute", {"points": "140,170;220,230;140,290;20,250;0,200"}, nick=lead)
        wait_airborne(lead)
        for nick in NICKS:
            if nick != lead:
                mcp("flyFollow", {"nick": lead}, nick=nick)
    else:
        for nick in (NICKS if mode == "race" else [BOT]):
            mcp("flyRings", {"points": points()}, nick=nick)
            time.sleep(2 if mode == "race" else 0)
    watched = NICKS if mode != "solo" else [BOT]
    gaps = {n: [] for n in watched if n != lead}
    closest = None
    while time.time() - t0 < 150:
        ps = {n: pos(n) for n in watched}
        if mode in ("chase", "follow") and ps.get(lead):
            for n in gaps:
                if ps.get(n):
                    gaps[n].append(sum((a - b) ** 2 for a, b in zip(ps[n], ps[lead])) ** 0.5)
            others = [ps[n] for n in gaps if ps.get(n)]
            if len(others) == 2:
                d = sum((a - b) ** 2 for a, b in zip(*others)) ** 0.5
                closest = d if closest is None else min(closest, d)
        sts = {n: mcp("flyStatus", nick=n) for n in watched}
        if all(s.get("phase") in ("done", "failed", "idle") for s in sts.values()):
            break
        time.sleep(0.5)
    time.sleep(3)
    sts = {n: mcp("flyStatus", nick=n) for n in watched}
    mcp("camera", {"perspective": 0, "hud": True})
    clip = v.stop()
    out = {n: {k: s.get(k) for k in ("phase", "reason", "rocketsUsed", "ringMiss")} for n, s in sts.items()}
    for n, g in gaps.items():
        g = g[4:] or g
        if g:
            out[n]["gap_median"] = round(sorted(g)[len(g) // 2], 1)
            out[n]["gap_max"] = round(max(g), 1)
    if closest is not None:
        out["pursuers_closest"] = round(closest, 1)
    out["seconds"] = round(time.time() - t0)
    out["video"] = clip
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
