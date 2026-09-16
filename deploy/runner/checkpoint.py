"""World checkpoints for the survival stand: freeze a run where the trouble starts, resume there.

A sixty-minute playthrough reaches diamonds at minute thirty and its next wall at minute forty;
starting every test from an empty inventory spends thirty minutes to look at ten. A checkpoint is
the WHOLE world folder of the gamer server -- the bot's position, inventory, armour, health and
hunger (playerdata), the time of day and the seed (level.dat), every shaft it dug and every
furnace it placed (region files) -- copied out while the server is not writing, and put back in
place of the live world when asked. Nothing is synthesised: the state is exactly the one the run
left.

    python deploy/runner/checkpoint.py save NAME [--note "..."]   # freeze the live world as NAME
    python deploy/runner/checkpoint.py restore NAME               # put NAME back and restart the server
    python deploy/runner/checkpoint.py list                       # what is on disk, with the notes
    python deploy/runner/gamer_smoke.py 20 --from NAME            # resume a run from it (gamer_smoke)

Checkpoints live in deploy/runner/checkpoints/NAME/ (world/ + meta.json), git-ignored: a world
of this stand is about two gigabytes. `save` does save-off, save-all flush, docker cp, save-on --
about a minute, the bot keeps playing in memory meanwhile. `restore` kicks the bot, stops the
server, replaces /data/world, fixes the ownership (docker cp writes as root, the server runs as
the `minecraft` user), starts the server and waits for rcon; the caller reconnects the bot.
"""
import functools, json, os, pathlib, shutil, subprocess, sys, time
print = functools.partial(print, flush=True)

SERVER = os.environ.get("UC_GAMER_SERVER", "uctest-gamer-server")
BOT = os.environ.get("UC_BOT", "tester1")
ROOT = pathlib.Path(__file__).with_name("checkpoints")
WORLD_IN_CONTAINER = "/data/world"


def sh(a, to=600, check=False):
    r = subprocess.run(a, capture_output=True, text=True, timeout=to)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(a[:4])}...: {r.stderr.strip()[-300:] or r.stdout.strip()[-300:]}")
    return r


def rcon(c, to=60):
    r = sh(["docker", "exec", SERVER, "rcon-cli", c], to)
    return (r.stdout or "").strip()


def server_running():
    r = sh(["docker", "inspect", "-f", "{{.State.Running}}", SERVER], 30)
    return r.stdout.strip() == "true"


def wait_rcon(timeout=240):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if server_running() and "players" in rcon("list", 20):
            return True
        time.sleep(5)
    return False


def bot_summary():
    """What rcon can say about the bot right now (only while it is online)."""
    out = {}
    for key in ("Pos", "Health", "foodLevel", "XpLevel", "Dimension"):
        v = rcon(f"data get entity {BOT} {key}", 30)
        if v and "No entity" not in v and "has the following" in v:
            out[key] = v.split("has the following entity data:", 1)[1].strip()
    inv = rcon(f"data get entity {BOT} Inventory", 60)
    if inv and "has the following" in inv:
        body = inv.split("has the following entity data:", 1)[1]
        # count the stacks and name the item ids, enough to read a checkpoint at a glance
        ids = [seg.split('"')[1] for seg in body.split('id: "')[1:]]
        out["inventory_stacks"] = len(ids)
        out["inventory_ids"] = sorted(set(ids))
    return out


def save(name, note=""):
    dst = ROOT / name
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    t0 = time.time()
    print(f"[checkpoint] saving {name}: save-off, flush, copy {WORLD_IN_CONTAINER}...")
    meta = {"name": name, "note": note, "saved_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "seed": rcon("seed").replace("Seed: ", "").strip("[]"),
            "daytime": rcon("time query daytime"), "bot": bot_summary()}
    rcon("save-off")
    rcon("save-all flush", 120)
    time.sleep(2)
    try:
        sh(["docker", "cp", f"{SERVER}:{WORLD_IN_CONTAINER}", str(dst / "world")], 1800, check=True)
    finally:
        rcon("save-on")
    size = sum(f.stat().st_size for f in (dst / "world").rglob("*") if f.is_file())
    meta["world_bytes"] = size
    meta["copy_seconds"] = round(time.time() - t0, 1)
    (dst / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[checkpoint] saved {name}: {size // (1 << 20)} MB in {meta['copy_seconds']} s; "
          f"bot: {meta['bot'].get('Pos', '(offline)')} hp={meta['bot'].get('Health', '?')} "
          f"stacks={meta['bot'].get('inventory_stacks', '?')}")
    return meta


def restore(name):
    src = ROOT / name / "world"
    if not src.exists():
        raise SystemExit(f"[checkpoint] no such checkpoint: {name} ({src})")
    meta = json.loads((ROOT / name / "meta.json").read_text(encoding="utf-8")) if (ROOT / name / "meta.json").exists() else {}
    print(f"[checkpoint] restoring {name} ({meta.get('saved_at', '?')}, {meta.get('note', '')})")
    image = sh(["docker", "inspect", "-f", "{{.Config.Image}}", SERVER], 30).stdout.strip()
    if server_running():
        rcon(f"kick {BOT} checkpoint restore", 30)
        rcon("save-off"); rcon("save-all flush", 120)
        sh(["docker", "stop", "-t", "60", SERVER], 120, check=True)
    # the old world goes, the copy comes in, and it is owned by the server's user again
    sh(["docker", "run", "--rm", "--volumes-from", SERVER, "--entrypoint", "sh", image,
        "-c", f"rm -rf {WORLD_IN_CONTAINER}"], 600, check=True)
    sh(["docker", "cp", str(src), f"{SERVER}:{WORLD_IN_CONTAINER}"], 1800, check=True)
    sh(["docker", "run", "--rm", "--volumes-from", SERVER, "--entrypoint", "sh", image,
        "-c", f"chown -R 1000:1000 {WORLD_IN_CONTAINER}"], 600, check=True)
    sh(["docker", "start", SERVER], 60, check=True)
    if not wait_rcon():
        raise SystemExit("[checkpoint] the server did not come back after the restore")
    print(f"[checkpoint] restored {name}; seed {rcon('seed')}, {rcon('time query daytime')}")
    return meta


def list_():
    if not ROOT.exists():
        print("(no checkpoints)"); return
    for d in sorted(ROOT.iterdir()):
        m = d / "meta.json"
        meta = json.loads(m.read_text(encoding="utf-8")) if m.exists() else {}
        bot = meta.get("bot", {})
        print(f"{d.name:24s} {meta.get('saved_at', '?'):20s} {meta.get('world_bytes', 0) // (1 << 20):5d} MB "
              f"pos={bot.get('Pos', '-')} hp={bot.get('Health', '-')} stacks={bot.get('inventory_stacks', '-')} "
              f"{meta.get('note', '')}")


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("save", "restore", "list"):
        print(__doc__); sys.exit(2)
    op = sys.argv[1]
    if op == "list":
        list_()
    elif op == "save":
        note = sys.argv[sys.argv.index("--note") + 1] if "--note" in sys.argv else ""
        save(sys.argv[2], note)
    else:
        restore(sys.argv[2])
