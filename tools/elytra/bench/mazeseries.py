"""The maze, flown again and again: how often the route search gets through, and at what price.

    python mazeseries.py [N] [--corners] [--cost X] [--compact] [--video]

N flights (10 by default) from the take-off spot to the far end of the maze hall, with the route
search on. The searched way is flown as a physics plan (AirPlan), or with --corners as before, its
corners as rings. --cost sets the rocket price in blocks of progress (12 is creative's, 40
survival's; left out: by game mode). --compact flies the compact hall (all of it within sight from
its door) instead of the long one (240 blocks, beyond the view distance from the take-off).
Results go to out/results/<time>-maze.md and .json.

Before each start the series waits till the bot's client has every chunk of the hall, and records how
long that took; after each flight it keeps the flight's tick log (flightLog), the burn of each rocket
read from it, and the server's tick time, so that a failure can be put next to what was different.
"""
import json
import sys
import time

import bench
from bench import Video, mcp
from flighttests import fly_test


def rocket_burns(rows):
    """Ticks each rocket pulled, from the flight log's rocket column (runs of consecutive ticks)."""
    burns, run, last = [], 0, None
    for r in rows:
        if r[9] and last is not None and r[0] == last + 1 and run:
            run += 1
        elif r[9]:
            if run:
                burns.append(run)
            run = 1
        elif run:
            burns.append(run)
            run = 0
        last = r[0]
    if run:
        burns.append(run)
    return burns


def main():
    args = sys.argv[1:]
    n = int(next((a for a in args if a.isdigit()), 10))
    corners = "--corners" in args
    cost = float(args[args.index("--cost") + 1]) if "--cost" in args else -1
    video = "--video" in args
    compact = "--compact" in args
    bench.arena()
    if compact:
        bench.maze_compact()
    else:
        bench.maze()
    mz, gx = (bench.MAZE2_Z, bench.MAZE2_X1 - 15) if compact else (bench.MAZE_Z, 820)
    mcp("flySettings", {"solver": True, "routeSearch": True, "routePlan": not corners, "rocketCost": cost})
    rows = []
    try:
        for i in range(n):
            v = Video(f"maze{'2' if compact else ''}-{'corners' if corners else 'plan'}-{i + 1}", video)
            t0 = time.time()
            ok, info = fly_test(560, mz, gx, mz, seconds=120, before=lambda: bench.wait_hall_loaded(compact))
            clip = v.stop()
            log = mcp("flightLog", {"n": min(2400, int((time.time() - t0) * 20) + 40)}).get("rows", [])
            st = info["status"]
            end = info.get("end") or [0, 0, 0]
            inside = bench.in_maze(end, compact)
            ok = ok and st.get("reason") != "blocked" and inside
            row = {"run": i + 1, "ok": ok, "inside": inside, "phase": st.get("phase"), "reason": st.get("reason"),
                   "rockets": st.get("rocketsUsed"), "seconds": st.get("seconds"), "off": info.get("off_target"),
                   "routeSearch": st.get("routeSearch"), "plans": st.get("plans"), "planMs": st.get("planMs"),
                   "notes": st.get("planNotes"), "path": info.get("path"), "video": clip,
                   **(info.get("before") or {}), "rocketBurns": rocket_burns(log), "serverTick": bench.server_tick(),
                   "flightLog": log}
            rows.append(row)
            print(f"{i + 1:2d} {'PASS' if ok else 'FAIL'} {'' if inside else 'OUTSIDE '}{row['phase']} {row['reason']} rockets={row['rockets']} "
                  f"{row['seconds']}s off={row['off']} search='{row['routeSearch']}' plans={row['plans']} "
                  f"planMs={row['planMs']} hall={row.get('hallWaitS')}s/{row.get('hallMissing')} "
                  f"burns={row['rocketBurns']}", flush=True)
    finally:
        mcp("flySettings", {"routeSearch": False, "routePlan": True, "rocketCost": -1})
    good = [r for r in rows if r["ok"]]
    summary = (f"{'compact' if compact else 'long'} maze, {'corners' if corners else 'plan'}, rocket price {cost if cost >= 0 else 'by game mode'}: "
               f"{len(good)}/{len(rows)} through")
    if good:
        summary += (f", rockets {sum(r['rockets'] or 0 for r in good) / len(good):.1f}, "
                    f"{sum(r['seconds'] or 0 for r in good) / len(good):.1f} s on average")
    print(summary)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    res = bench.OUT / "results"
    res.mkdir(parents=True, exist_ok=True)
    (res / f"{stamp}-maze.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    md = [summary, "", "| run | result | phase / reason | rockets | s | off | search | plans |", "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        md.append(f"| {r['run']} | {'PASS' if r['ok'] else 'FAIL'} | {r['phase']} {r['reason']} | {r['rockets']} | "
                  f"{r['seconds']} | {r['off']} | {r['routeSearch']} | {r['plans']} |")
    (res / f"{stamp}-maze.md").write_text("\n".join(md) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
