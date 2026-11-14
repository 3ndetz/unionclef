"""Elytra, creative-flight and water checks of the mod on a local test server.

    python flighttests.py [--no-video] [--solver] [name ...]

Each test resets the bot over RCON (place, kit, game mode), drives one mod tool over MCP and watches
its status until it ends. --solver steers with tungsten's ElytraSolver (setting elytraSolver), else
with the PI pilot. Results go to out/results/<time>.md and .json, videos to out/video.
"""
import json
import socket
import sys
import time

import bench
from bench import F, RINGS, Video, dist, mcp, reset, watch


def fly_test(x0, z0, x1, z1, hotbar=True, low=False, seconds=90, yaw=-90, before=None):
    """before: called between the reset and the start; what it returns goes into the result as "before"."""
    reset(x0, z0, rockets_in_hotbar=hotbar, yaw=yaw)
    pre = before() if before else None
    args = {"x": x1, "y": F, "z": z1}
    if low:
        args["low"] = True
    mcp("flyTo", args)
    st, path = watch("flyStatus", seconds)
    end = path[-1] if path else [0, 0, 0]
    ok = st.get("phase") == "done" and dist(end, x1, z1) < 30 and (st.get("rocketsUsed") or 0) > 0
    return ok, {"status": st, "end": end, "off_target": round(dist(end, x1, z1), 1), "path": path, "before": pre}


def t_flat_hotbar():
    """Take-off from flat ground with the rockets in the hotbar, 150 blocks east."""
    return fly_test(0, 0, 150, 0)


def t_flat_backpack():
    """Rockets only in the backpack: the take-off must still get its first boost."""
    return fly_test(0, 0, 150, 0, hotbar=False)


def t_long_500():
    """A long flight: 500 blocks, landing within 30 of the point."""
    return fly_test(0, 0, 500, 0, seconds=150)


def t_long_1000():
    """1000 blocks: what a trip costs in rockets and time."""
    return fly_test(0, 0, 1000, 0, seconds=240)


def t_gazebo_edge():
    """Take-off just beside a roof, the course leading under it."""
    return fly_test(-35, 0, -150, 0, yaw=90)


def t_under_canopy():
    """Under leaves: the mod must refuse the take-off and name open sky to walk to."""
    reset(1, 60)
    mcp("flyTo", {"x": 150, "y": F, "z": 60})
    st, _ = watch("flyStatus", 20)
    ok = st.get("phase") == "failed" and st.get("reason") == "under a roof" and bool(st.get("openSky"))
    return ok, {"status": st}


def t_low_forest():
    """Low-level flight along a strip of tall trees standing on the line."""
    return fly_test(10, -82, 230, -82, low=True, seconds=90)


def t_rings():
    """Six rings with a 7x7 opening, heights and sides varied: through all of them in order."""
    reset(0, 130, yaw=-90)
    mcp("flyRings", {"points": ";".join(f"{x + 0.5},{y + 0.5},{z + 0.5}" for x, y, z in RINGS)})
    st, path = watch("flyStatus", 120)
    miss = st.get("ringMiss") or []
    ok = len(miss) == len(RINGS) and all(m < 3.2 for m in miss) and st.get("phase") == "done"
    return ok, {"status": st, "ringMiss": miss, "path": path}


def t_maze():
    """A glass hall cut by three walls with one 9x9 gap each, low, high, middle: the route search has to
    find the way through the gaps and the glider fly it (the ceiling is too low to go over)."""
    bench.maze()
    mcp("flySettings", {"routeSearch": True})
    try:
        ok, info = fly_test(560, bench.MAZE_Z, 820, bench.MAZE_Z, seconds=120)
    finally:
        mcp("flySettings", {"routeSearch": False})
    st = info["status"]
    info["routeSearch"] = st.get("routeSearch")
    info["ringMiss"] = st.get("ringMiss")
    info["inside"] = bench.in_maze(info["end"])  # landed in the hall, not on its roof
    return ok and st.get("reason") != "blocked" and info["inside"], info


def t_tank_swim():
    """At the bottom of 16 blocks of water: swimToShore must come up and climb out on the one bank."""
    reset(70, -50, y=F)
    time.sleep(2)
    out = mcp("swimToShore", {"radius": 48})
    st, _ = (out, []) if out.get("phase") == "failed" else watch("swimStatus", 60)
    end = bench.me()
    return (not end.get("inWater")), {"start": out, "status": st, "inWater": end.get("inWater"), "pos": end.get("pos")}


def t_tank_hover():
    """At the bottom of the tank: creative flight out to dry ground (water is no roof)."""
    reset(70, -50, y=F)
    time.sleep(2)
    mcp("hoverTo", {"x": 120, "z": -50})
    st, _ = watch("hoverStatus", 60)
    end = bench.me()
    return (not end.get("inWater")) and st.get("phase") == "done", {"status": st, "pos": end.get("pos")}


def t_py4j_bridge():
    """A settings change (@set) must leave the py4j gateway listening."""
    def listening():
        s = socket.socket()
        s.settimeout(1)
        try:
            return s.connect_ex(("127.0.0.1", 25333)) == 0
        finally:
            s.close()
    before = listening()
    mcp("ExecuteCommand", {"cmd": "@set autoMLGBucket false"})
    time.sleep(5)
    after = listening()
    return before and after, {"before": before, "after": after}


TESTS = [t_flat_hotbar, t_flat_backpack, t_long_500, t_gazebo_edge, t_under_canopy, t_low_forest,
         t_rings, t_tank_swim, t_tank_hover, t_py4j_bridge, t_long_1000, t_maze]


def main():
    video = "--no-video" not in sys.argv
    solver = "--solver" in sys.argv
    want = [a for a in sys.argv[1:] if not a.startswith("--")]
    bench.arena()
    mcp("flySettings", {"solver": solver})
    rows = []
    for t in TESTS:
        name = t.__name__[2:]
        if want and name not in want:
            continue
        print(f"== {name}: {t.__doc__.strip()}", flush=True)
        v = Video(name, video and name != "py4j_bridge")
        try:
            ok, info = t()
        except Exception as e:  # a broken test is a failed test, the run goes on
            ok, info = False, {"error": repr(e)}
        clip = v.stop()
        info.pop("path", None)
        rows.append({"test": name, "ok": ok, "about": t.__doc__.strip(), "video": clip, **info})
        st = info.get("status", {})
        print(f"   {'PASS' if ok else 'FAIL'}  {st.get('phase', '')} {st.get('reason', '')} "
              f"rockets={st.get('rocketsUsed', '-')} off={info.get('off_target', '-')} {st.get('seconds', '')}s",
              flush=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    res = bench.OUT / "results"
    res.mkdir(parents=True, exist_ok=True)
    (res / f"{stamp}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    md = ["| test | result | phase / reason | rockets | off target | s |", "|---|---|---|---|---|---|"]
    for r in rows:
        st = r.get("status", {})
        md.append(f"| {r['test']} | {'PASS' if r['ok'] else 'FAIL'} | {st.get('phase', '')} {st.get('reason', '')} | "
                  f"{st.get('rocketsUsed', '')} | {r.get('off_target', '')} | {st.get('seconds', '')} |")
    (res / f"{stamp}.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n".join(md))


if __name__ == "__main__":
    main()
