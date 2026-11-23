"""Check tungsten's ElytraPhysics against the game: fly, read the mod's tick log (flightLog) and feed each
tick's velocity and look to the same model (ported here, line for line) to compare with the velocity
the game had a tick later.

    python physcheck.py [log.json]      -- without a file: flies 300 blocks first and saves the log

Prints the error per tick (median, 95th percentile, max), gliding apart from rocket ticks, and for the
rocket both orders of its pull (the rocket entity ticking before or after its shooter). Then the same for
the move: the step from one logged position to the next against the glide alone (the rocket's pull
moves the player from the next tick on) and against the velocity with the pull (how the planners moved
the bot before 08.11).
"""
import json
import math
import sys
import time

import bench

RAD = math.pi / 180


def look(yaw, pitch):
    p, y = pitch * RAD, -yaw * RAD
    return (math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p))


def glide(v, yaw, pitch):
    lx, ly, lz = look(yaw, pitch)
    mx, my, mz = v
    pr = pitch * RAD
    lh = math.hypot(lx, lz)
    flat = math.hypot(mx, mz)
    cp2 = math.cos(pr) ** 2
    my += -0.08 + cp2 * 0.06
    if my < 0 and lh > 0:
        lift = my * -0.1 * cp2
        my += lift
        mx += lx * lift / lh
        mz += lz * lift / lh
    if pr < 0 and lh > 0:
        climb = flat * -math.sin(pr) * 0.04
        my += climb * 3.2
        mx -= lx * climb / lh
        mz -= lz * climb / lh
    if lh > 0:
        mx += (lx / lh * flat - mx) * 0.1
        mz += (lz / lh * flat - mz) * 0.1
    return (mx * 0.99, my * 0.98, mz * 0.99)


def rocket(v, yaw, pitch):
    lx, ly, lz = look(yaw, pitch)
    return tuple(v[i] + l * 0.1 + (l * 1.5 - v[i]) * 0.5 for i, l in enumerate((lx, ly, lz)))


def stats(xs):
    if not xs:
        return "-"
    xs = sorted(xs)
    return f"n={len(xs)} med={xs[len(xs) // 2]:.4f} p95={xs[int(len(xs) * 0.95)]:.4f} max={xs[-1]:.4f}"


def fly_and_log():
    from flighttests import fly_test
    ok, info = fly_test(0, 0, 300, 0, seconds=120)
    print("flight:", ok, info["status"].get("phase"), "rockets", info["status"].get("rocketsUsed"))
    time.sleep(1)
    log = bench.mcp("flightLog", {"n": 2400})
    res = bench.OUT / "results"
    res.mkdir(parents=True, exist_ok=True)
    path = res / f"flightlog-{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(log), encoding="utf-8")
    print("log:", path, len(log.get("rows", [])), "ticks")
    return log


def main():
    log = json.loads(open(sys.argv[1], encoding="utf-8").read()) if len(sys.argv) > 1 else fly_and_log()
    rows = log["rows"]
    out = {"glide": [], "rocket_after": [], "rocket_before": [], "rocket_none": [],
           "move_glide": [], "move_rocket": [], "move_rocket_pulled": []}
    for a, b in zip(rows, rows[1:]):
        if b[0] != a[0] + 1 or a[10] or b[10]:  # a gap in the log or a collision: not free flight
            continue
        if math.dist(a[1:4], b[1:4]) > 4:  # a teleport between bench flights (the log keeps two minutes)
            continue
        v, yaw, pitch, rk = (a[4], a[5], a[6]), a[7], a[8], a[9] or b[9]
        real = (b[4], b[5], b[6])
        g = glide(v, yaw, pitch)
        moved = (b[1] - a[1], b[2] - a[2], b[3] - a[3])
        if not rk:
            out["glide"].append(math.dist(g, real))
            out["move_glide"].append(math.dist(g, moved))
        else:
            out["rocket_after"].append(math.dist(rocket(g, yaw, pitch), real))
            out["rocket_before"].append(math.dist(glide(rocket(v, yaw, pitch), yaw, pitch), real))
            out["rocket_none"].append(math.dist(g, real))
            out["move_rocket"].append(math.dist(g, moved))
            out["move_rocket_pulled"].append(math.dist(rocket(g, yaw, pitch), moved))
    for k, xs in out.items():
        print(f"{k:14s} {stats(xs)}")


if __name__ == "__main__":
    main()
