"""A ring course in the sky on a shared survival server, built by the bot itself in creative.

    python skycourse.py plan             # print the cells, change nothing
    python skycourse.py check            # read the world: every cell to fill must be air, with room around
    python skycourse.py build [from]     # build it (spine, then the rings in order), from piece `from`
    python skycourse.py fly [--video]    # fly the rings, print the misses

Nothing is placed in mid-air: a block needs a face to go against, so the rings stand on posts on
a one-block spine of white concrete that runs west from the edge of the platform at y=114. The
spine starts against the platform's own edge, each ring's post starts on the spine, and every
cell of a piece is listed so that it touches one placed before it (the build queue places a
batch bottom-up). Nothing is broken: the build policy forbids it before anything starts.

The zone (a plot on a shared server, given by its owner): the strip z -175..-148 west of x -563, the platform's
top at y=114, open air beyond its western edge at x=-635.
"""
import json
import math
import sys
import time

import bench
from bench import mcp

BOT = "arcmlchikkk"
TOP = 114                      # the platform's top, and the spine's height
SPINE_Z = -162                 # between the two lanes
SPINE_X0, SPINE_X1 = -636, -800
OUT_Z, BACK_Z = -166, -158     # ring centres: the frame's near column sits over the spine
ZONE_Z = (-175, -148)
ZONE_X_EAST = -563
SPINE_BLOCK = "white_concrete"
# x, centre y, lane centre z, colour: out to the west, then back to the east
RINGS = [(-600, 123, OUT_Z, "red"), (-645, 128, OUT_Z, "orange"), (-690, 120, OUT_Z, "yellow"),
         (-735, 126, OUT_Z, "lime"), (-780, 121, OUT_Z, "green"),
         (-760, 125, BACK_Z, "cyan"), (-715, 119, BACK_Z, "light_blue"), (-670, 127, BACK_Z, "blue"),
         (-628, 122, BACK_Z, "purple")]
HALF = 4                       # a 9x9 frame round a 7x7 opening


def ring_cells(x, cy, cz, colour):
    """Post up from the spine (or the platform) under the near column, then a path round the frame
    in which y never goes down: bottom row across, far column up, near column up, top row."""
    block = f"{colour}_concrete"
    near = SPINE_Z
    far = cz + (cz - near)
    step = 1 if far > near else -1
    b, t = cy - HALF, cy + HALF
    cells = [(x, y, near, block) for y in range(TOP + 1, b)]
    cells += [(x, b, z, block) for z in range(near, far + step, step)]
    cells += [(x, y, far, block) for y in range(b + 1, t + 1)]
    cells += [(x, y, near, block) for y in range(b + 1, t + 1)]
    cells += [(x, t, z, block) for z in range(near + step, far, step)]
    return cells


def pieces():
    """(name, cells) in build order."""
    out = []
    spine = [(x, TOP, SPINE_Z, SPINE_BLOCK) for x in range(SPINE_X0, SPINE_X1 - 1, -1)]
    for i in range(0, len(spine), 40):
        out.append((f"spine {i}", spine[i:i + 40]))
    for i, r in enumerate(RINGS):
        out.append((f"ring {i + 1} {r[3]}", ring_cells(*r)))
    return out


def block(x, y, z):
    return mcp("getBlockAt", {"x": x, "y": y, "z": z}, nick=BOT).get("id", "?").replace("minecraft:", "")


def check():
    """Every cell to fill is air or already ours; nothing but air within 2 blocks of a ring (the
    platform's own grass and the spine aside); everything inside the zone."""
    bad = []
    ours = {(x, y, z): b for _, cells in pieces() for x, y, z, b in cells}
    for (x, y, z), b in ours.items():
        if not (ZONE_Z[0] <= z <= ZONE_Z[1] and x <= ZONE_X_EAST):
            bad.append(f"outside the zone: {x},{y},{z}")
    for (x, y, z), b in ours.items():
        now = block(x, y, z)
        if now not in ("air", b):
            bad.append(f"{x},{y},{z} is {now}")
    for x, cy, cz, _ in RINGS:
        for dx in (-2, -1, 0, 1, 2):
            for y in range(cy - HALF - 1, cy + HALF + 2):
                for z in range(cz - HALF - 2, cz + HALF + 3):
                    if (x + dx, y, z) in ours:
                        continue
                    now = block(x + dx, y, z)
                    if now not in ("air", "void_air") and not (y == TOP and now == "grass_block"):
                        bad.append(f"near ring at {x}: {x + dx},{y},{z} is {now}")
    return bad


def wait(tool, done, seconds):
    t = time.time()
    st = {}
    while time.time() - t < seconds:
        st = mcp(tool, nick=BOT)
        if done(st):
            return st
        time.sleep(0.5)
    return st


def hover(x, y, z):
    mcp("hoverTo", {"x": x, "y": y, "z": z}, nick=BOT)
    st = wait("hoverStatus", lambda s: s.get("phase") in ("done", "failed"), 60)
    return st.get("phase") == "done", st


def place(cells, away=(0, 0, 0)):
    """Hover beside the cells and build them; the cells still missing after that come back. The
    builder reaches 4.5 blocks from the eye, and the face a cell goes against has to be in sight:
    the spine is built from just beside it, a ring from 2 blocks east of its plane, level with the
    middle of the cells."""
    cells = [c for c in cells if block(*c[:3]) != c[3]]  # a build picked up again: done ones out
    if not cells:
        return [], ""
    cx = sum(c[0] for c in cells) / len(cells)
    cy = sum(c[1] for c in cells) / len(cells)
    cz = sum(c[2] for c in cells) / len(cells)
    spine = all(c[1] == TOP for c in cells)
    # The spine goes west, each block against the east one: from on its line 2 blocks further west,
    # level with it, that face is in plain sight (from above it was not, past the third block).
    hx, hy, hz = ((min(c[0] for c in cells) - 2, TOP, SPINE_Z) if spine
                  else (math.floor(cx) + 2, max(TOP + 2, math.floor(cy) - 1), math.floor(cz)))
    # One cell again: a block further on from the one it goes against, so the face to click on is
    # in front of the eye even with the hover half a block off (it stops within 0.7).
    hx, hy, hz = hx + away[0], hy + away[1], hz + away[2]
    ok, st = hover(hx, hy, hz)
    if not ok:
        return cells, f"hover {st.get('reason')}"
    # One block at a time, in the listed order (placeBlockAt finds the face to go against). The
    # build queue (buildBlocks) placed nothing from a hover: every cell came back "no face".
    left = []
    for x, y, z, name in cells:
        if block(x, y, z) == name:
            continue
        given = mcp("creativeGive", {"item": name, "count": 64, "hotbar": True}, nick=BOT)
        mcp("selectHotbar", {"slot": given.get("hotbar", 8)}, nick=BOT)
        # The look turns at a hand's pace: "aim has not converged" is a turn still under way.
        for _ in range(20):
            r = mcp("placeBlockAt", {"x": x, "y": y, "z": z}, nick=BOT)
            time.sleep(0.25)
            if "converged" not in str(r.get("reason", "")):
                break
        if block(x, y, z) != name:
            left.append((x, y, z, name))
            st = r
    return left, "" if not left else str(st)[:160]


def build(start=0):
    mcp("setBuildPolicy", {"breakOnlyNatural": True, "allowPlace": True, "allowBreak": False}, nick=BOT)
    mcp("setBuilderWalks", {"on": False}, nick=BOT)
    for k, (name, cells) in enumerate(pieces()):
        if k < start:
            continue
        # a piece in parts of a few cells, each part from its own spot, in the listed order
        size = 2 if name.startswith("spine") else 4
        for i in range(0, len(cells), size):
            part = cells[i:i + size]
            left, why = place(part)
            for c in list(left):  # what did not go: one at a time, each from its own spot
                # away from the block it goes against: the one under it if that stands, else the
                # cell before it in the list
                j = cells.index(c)
                under = (c[0], c[1] - 1, c[2])
                prev = under if block(*under) not in ("air", "void_air") else (cells[j - 1] if j else c)
                away = tuple(max(-1, min(1, c[n] - prev[n])) for n in range(3))
                if place([c], away)[0]:
                    break
                left.remove(c)
            if left:
                print(f"STOP at piece {k} ({name}), cells {i}..: {len(left)} not placed {why} {left[:3]}", flush=True)
                return False
        print(f"built {k} {name}: {len(cells)} cells", flush=True)
    return True


START = (-575, -166)            # take-off on the platform, east of ring 1, inside the zone


def fly(video=False, name="skycourse"):
    """From the start on the platform, through the rings in order, landing back on the platform."""
    pts = [(x, cy, cz) for x, cy, cz, _ in RINGS]
    mcp("flyStop", nick=BOT)
    # Over the rings (their tops are 132 at most), to just east of the start, then down onto it: the
    # take-off goes the way the bot faces and ring 1 is 25 blocks on, so it has to stand facing the
    # course; and low over the platform the way in ran into ring 1's post.
    mcp("hoverTo", {"x": START[0] + 6, "y": TOP + 22, "z": START[1]}, nick=BOT)
    wait("hoverStatus", lambda s: s.get("phase") in ("done", "failed"), 90)
    mcp("hoverTo", {"x": START[0], "z": START[1]}, nick=BOT)
    wait("hoverStatus", lambda s: s.get("phase") in ("done", "failed"), 60)
    mcp("creativeGive", {"item": "firework_rocket", "count": 64}, nick=BOT)
    mcp("flySettings", {"solver": True}, nick=BOT)
    if video:
        mcp("camera", {"perspective": 1, "hud": False}, nick=BOT)
        mcp("recordStart", {"name": name, "fps": 30}, nick=BOT)
    t0 = time.time()
    mcp("flyRings", {"points": ";".join(f"{x + 0.5},{y + 0.5},{z + 0.5}" for x, y, z in pts)}, nick=BOT)
    time.sleep(2)
    st = wait("flyStatus", lambda s: s.get("phase") in ("done", "failed", "idle"), 240)
    if video:
        time.sleep(2)
        mcp("recordStop", nick=BOT)
        mcp("camera", {"perspective": 0, "hud": True}, nick=BOT)
    out = {k: st.get(k) for k in ("phase", "reason", "ringMiss", "rocketsUsed")}
    out["seconds"] = round(time.time() - t0, 1)
    out["end"] = mcp("getGameState", nick=BOT)["self"]["pos"]
    print(json.dumps(out, ensure_ascii=False), flush=True)
    return out


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "plan"
    if what == "plan":
        for name, cells in pieces():
            print(name, len(cells), cells[0], cells[-1])
        print("cells", sum(len(c) for _, c in pieces()))
    elif what == "check":
        bad = check()
        print("\n".join(bad[:40]) if bad else "all clear")
        print(len(bad), "problems")
    elif what == "build":
        build(int(sys.argv[2]) if len(sys.argv) > 2 else 0)
    elif what == "fly":
        n = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 1
        for i in range(n):
            fly("--video" in sys.argv, f"skycourse-{time.strftime('%H%M%S')}")
