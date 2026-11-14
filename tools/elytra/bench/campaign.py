"""One block of the test protocol on the jar the bot's client runs now, with everything needed to say
later what was flown: the jar's hash, the commit and a patch of the working tree, the bench scripts'
hashes, the protocol, and every raw result copied next to its log.

    python campaign.py <campaign> <variant> --jar <path of the jar in mods> [--maze 10] [--land 3] [--walls 3]
                       [--physcheck] [--video] [--source <worktree>] [--tick-rate 20]

Writes out/campaign/<campaign>/<NN>-<variant>/: manifest.json, maze.json, land-flat.json,
land-walls.json, physcheck.txt with its flight log, patch.diff and the console log of each part.
Blocks of two variants are meant to be flown in turn (A B A B ...), so that whatever drifts on the
machine over an afternoon falls on both. campaign_report.py sums the blocks up.
"""
import argparse
import hashlib
import json
import pathlib
import platform
import shutil
import subprocess
import sys
import time

import bench

HERE = bench.HERE
SOURCE = HERE.parents[2]  # the tree the jar was built from; --source for another worktree


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def git(*args):
    return subprocess.run(["git", *args], cwd=SOURCE, capture_output=True, text=True, encoding="utf-8").stdout


def run(block, name, args, newest):
    """One bench script; its console to <name>.log, its newest result file (by the glob given) copied
    to <name>.json. A script that dies is recorded, not hidden: the block goes on."""
    t0 = time.time()
    with open(block / f"{name}.log", "w", encoding="utf-8") as log:
        code = subprocess.run([sys.executable, *args], cwd=HERE, stdout=log, stderr=subprocess.STDOUT).returncode
    made = [p for p in HERE.glob(newest) if p.stat().st_mtime >= t0]
    if made:
        shutil.copy2(max(made, key=lambda p: p.stat().st_mtime), block / f"{name}.json")
    return {"args": args, "exit": code, "seconds": round(time.time() - t0), "result": f"{name}.json" if made else None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("campaign")
    ap.add_argument("variant")
    ap.add_argument("--jar", required=True)
    ap.add_argument("--maze", type=int, default=10)
    ap.add_argument("--land", type=int, default=3)
    ap.add_argument("--walls", type=int, default=3)
    ap.add_argument("--physcheck", action="store_true")
    ap.add_argument("--video", action="store_true")
    ap.add_argument("--tick-rate", type=float, default=20, help="the server's tick rate for the block (/tick rate)")
    ap.add_argument("--world-sha", default="", help="hash of the world the server started from (server_fresh)")
    ap.add_argument("--source", help="the worktree the jar was built from (default: this bench's repo)")
    a = ap.parse_args()
    global SOURCE
    if a.source:
        SOURCE = pathlib.Path(a.source)

    root = bench.OUT / "campaign" / a.campaign
    root.mkdir(parents=True, exist_ok=True)
    block = root / f"{len([d for d in root.iterdir() if d.is_dir()]) + 1:02d}-{a.variant}"
    block.mkdir()
    (block / "patch.diff").write_text(git("diff", "HEAD"), encoding="utf-8")
    manifest = {
        "campaign": a.campaign, "variant": a.variant, "started": time.strftime("%Y-%m-%d %H:%M:%S"),
        "jar": a.jar, "jarSha256": sha256(a.jar), "source": str(SOURCE),
        "commit": git("rev-parse", "HEAD").strip(), "patchSha256": sha256(block / "patch.diff"),
        "untracked": git("ls-files", "--others", "--exclude-standard").split(),
        "bench": {p.name: sha256(p) for p in sorted(HERE.glob("*.py"))},
        "python": sys.version.split()[0], "platform": platform.platform(),
        "worldSha": a.world_sha,
        "protocol": {"tickRate": a.tick_rate, "maze": a.maze, "land": a.land, "walls": a.walls, "physcheck": a.physcheck, "video": a.video},
        "parts": {},
    }
    video = [] if a.video else ["--no-video"]
    bench.cmd(f"tick rate {a.tick_rate:g}")
    manifest["serverTickAtStart"] = bench.server_tick()
    if a.maze:
        manifest["parts"]["maze"] = run(block, "maze", ["mazeseries.py", str(a.maze), "--compact", *(["--video"] if a.video else [])],
                                        "out/results/*-maze.json")
    if a.land:
        manifest["parts"]["land-flat"] = run(block, "land-flat", ["landtest.py", str(a.land), *video], "out/landtest-*.json")
    if a.walls:
        manifest["parts"]["land-walls"] = run(block, "land-walls", ["landtest.py", str(a.walls), "--walls", "--legs", "150,300", *video],
                                              "out/landtest-*.json")
    if a.physcheck:
        manifest["parts"]["physcheck"] = run(block, "physcheck", ["physcheck.py"], "out/results/flightlog-*.json")
    manifest["serverTickAtEnd"] = bench.server_tick()
    bench.cmd("tick rate 20")
    manifest["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
    (block / "manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    print(block)


if __name__ == "__main__":
    main()
