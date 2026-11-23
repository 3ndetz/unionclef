"""PI pilot against tungsten's solver on the same flights: each test flown with elytraSolver off, then on.

    python abtest.py [--no-video] [test ...]     (default: flat_hotbar long_500 low_forest gazebo_edge)

"""
import json
import sys
import time

import bench
import flighttests as ft

TESTS = {t.__name__[2:]: t for t in ft.TESTS}


def main():
    video = "--no-video" not in sys.argv
    want = [a for a in sys.argv[1:] if not a.startswith("--")] or \
        ["flat_hotbar", "long_500", "low_forest", "gazebo_edge"]
    bench.arena()
    rows = []
    for name in want:
        for solver in (False, True):
            bench.mcp("flySettings", {"solver": solver})
            time.sleep(1)
            v = bench.Video(f"{name}-{'solver' if solver else 'pilot'}", video)
            try:
                ok, info = TESTS[name]()
            except Exception as e:
                ok, info = False, {"error": repr(e)}
            clip = v.stop()
            st = info.get("status", {})
            row = {"test": name, "steer": "solver" if solver else "pilot", "ok": ok, "phase": st.get("phase"),
                   "reason": st.get("reason"), "rockets": st.get("rocketsUsed"), "seconds": st.get("seconds"),
                   "off": info.get("off_target"), "video": clip}
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False), flush=True)
    bench.mcp("flySettings", {"solver": False})
    res = bench.OUT / "results"
    res.mkdir(parents=True, exist_ok=True)
    (res / f"ab-{time.strftime('%Y%m%d-%H%M%S')}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1),
                                                                  encoding="utf-8")
    print("| test | steer | ok | rockets | s | off |")
    print("|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['test']} | {r['steer']} | {'PASS' if r['ok'] else 'FAIL'} | {r['rockets']} | {r['seconds']} | {r['off']} |")


if __name__ == "__main__":
    main()
