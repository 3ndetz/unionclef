"""World checkpoints for the survival stand: freeze a run where the trouble starts, resume there.

A sixty-minute playthrough reaches diamonds at minute thirty and its next wall at minute forty;
starting every test from an empty inventory spends thirty minutes to look at ten. A checkpoint is
LIGHT (2026-09-25; it used to be the whole 2 GB world, and 96 of them filled ~190 GB): the seed and
time (level.dat), the bot's position, inventory, armour, health and hunger (playerdata), stats,
advancements and data/, the nether when it is small, and the 3x3 region files around the bot --
what it dug and built. Everything else regenerates from the seed on restore. 15-120 MB each; the
folder is capped at BUDGET_GB and no save happens with less than MIN_FREE_GB free.

    python deploy/runner/checkpoint.py save NAME [--note "..."]   # freeze the live world as NAME
    python deploy/runner/checkpoint.py restore NAME               # put NAME back and restart the server
    python deploy/runner/checkpoint.py list                       # what is on disk, size, free disk
    python deploy/runner/checkpoint.py budget                     # trim the folder to the budget now
    python deploy/runner/gamer_smoke.py 20 --from NAME            # resume a run from it (gamer_smoke)

Checkpoints live in deploy/runner/checkpoints/NAME/ (world/ + meta.json), git-ignored. `save` does
save-off, save-all flush, docker cp, save-on --
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


# ⛔⛔ DISK HYGIENE (operator, 2026-09-25: "ТВОИ ЧЕКПОИНТЫ ЗАСРАЛИ ДИСК ... 190 ГИГОВ"). A checkpoint
# used to be the WHOLE world folder, 2.1 GB, and the periodic series pruned only within one run, so 96
# of them piled up to about 190 GB and the host disk hit 98%. Now:
#   * a checkpoint is LIGHT: level.dat (seed, time, spawn), playerdata (position, inventory, health,
#     hunger), stats, advancements, data/, the whole nether when it is small, and only the 3x3 region
#     files around the bot in its dimension (what it dug and built). Everything else regenerates from
#     the seed on restore. A few MB to ~100 MB instead of 2.1 GB.
#   * the checkpoints folder is capped at BUDGET_GB; after every save the oldest periodic ones go
#     first, then the oldest of the rest, never the PROTECTED names.
#   * no save at all when the disk has less than MIN_FREE_GB free.
BUDGET_GB = 15
MIN_FREE_GB = 40
PROTECTED = {"last", "nether-fresh", "rung-ender"}
SMALL_DIM_BYTES = 200 << 20


def _dir_bytes(p):
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())


def disk_free_gb():
    return shutil.disk_usage(str(ROOT if ROOT.exists() else ROOT.parent)).free / (1 << 30)


def enforce_budget(keep=()):
    """Delete checkpoints, oldest first (periodic cp* before the rest), until the folder fits."""
    if not ROOT.exists():
        return
    def mtime(d):
        m = d / "meta.json"
        return m.stat().st_mtime if m.exists() else d.stat().st_mtime
    dirs = [d for d in ROOT.iterdir() if d.is_dir()]
    total = sum(_dir_bytes(d) for d in dirs)
    victims = sorted([d for d in dirs if d.name.startswith("cp")], key=mtime) +         sorted([d for d in dirs if not d.name.startswith("cp")], key=mtime)
    for d in victims:
        if total <= BUDGET_GB << 30:
            break
        if d.name in PROTECTED or d.name in keep:
            continue
        b = _dir_bytes(d)
        shutil.rmtree(d, ignore_errors=True)
        total -= b
        print(f"[checkpoint] budget: dropped {d.name} ({b >> 20} MB); folder now {total >> 20} MB")


def _parse_pos(s):
    try:
        nums = [float(x.rstrip("d")) for x in s.strip("[]").split(",")]
        return nums[0], nums[2]
    except Exception:
        return None


def _pos_from_playerdata():
    """(dimension, (x, z)) of the bot read straight from its player .dat, for a bot that is offline.

    A tiny NBT scan: the Pos list (3 doubles) and the Dimension string. The bot is the only player
    file on this stand; the newest .dat is taken."""
    import gzip, struct
    r = sh(["docker", "exec", SERVER, "sh", "-c",
            f"ls -t {WORLD_IN_CONTAINER}/playerdata/*.dat 2>/dev/null | head -1"], 30)
    f = r.stdout.strip()
    if not f:
        return "", None
    tmp = ROOT / "_pd.dat"
    ROOT.mkdir(parents=True, exist_ok=True)
    sh(["docker", "cp", f"{SERVER}:{f}", str(tmp)], 60)
    try:
        b = gzip.open(tmp).read()
        dim, xz = "", None
        i = b.find(bytes.fromhex("090003506f730600000003"))
        if i >= 0:
            x, y, z = struct.unpack(">ddd", b[i + 11:i + 35])
            xz = (x, z)
        j = b.find(bytes.fromhex("080009") + b"Dimension")
        if j >= 0:
            n = struct.unpack(">H", b[j + 12:j + 14])[0]
            dim = b[j + 14:j + 14 + n].decode("utf-8", "replace")
        return dim, xz
    except Exception:
        return "", None
    finally:
        tmp.unlink(missing_ok=True)


def save(name, note=""):
    free = disk_free_gb()
    if free < MIN_FREE_GB:
        print(f"[checkpoint] NOT saving {name}: only {free:.0f} GB free on the disk (< {MIN_FREE_GB})")
        return {}
    dst = ROOT / name
    if dst.exists():
        shutil.rmtree(dst)
    (dst / "world").mkdir(parents=True)
    t0 = time.time()
    meta = {"name": name, "note": note, "saved_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "seed": rcon("seed").replace("Seed: ", "").strip("[]"),
            "daytime": rcon("time query daytime"), "bot": bot_summary(), "light": True}
    dim = str(meta["bot"].get("Dimension", "")).strip('"')
    xz = _parse_pos(meta["bot"].get("Pos", ""))
    if xz is None:
        dim, xz = _pos_from_playerdata()
        meta["bot_offline_pos"] = [dim, xz]
    print(f"[checkpoint] saving {name} (light): dim={dim or '?'} pos={xz}")
    rcon("save-off")
    rcon("save-all flush", 120)
    time.sleep(2)
    W = WORLD_IN_CONTAINER
    picks = ["level.dat", "playerdata", "stats", "advancements", "data"]
    try:
        # whole small dimensions (the nether is tens of MB; the end is empty until reached)
        for d in ("DIM-1", "DIM1"):
            r = sh(["docker", "exec", SERVER, "sh", "-c", f"du -sb {W}/{d} 2>/dev/null | cut -f1"], 60)
            if r.stdout.strip().isdigit() and int(r.stdout.strip()) <= SMALL_DIM_BYTES:
                picks.append(d)
        # the 3x3 regions around the bot in its dimension, for what it dug and built
        if xz is not None:
            base = {"minecraft:the_nether": "DIM-1", "minecraft:the_end": "DIM1"}.get(dim, "")
            if base == "" or base not in picks:
                rx, rz = int(xz[0] // 512), int(xz[1] // 512)
                for sub in ("region", "entities", "poi"):
                    for dx in (-1, 0, 1):
                        for dz in (-1, 0, 1):
                            picks.append(f"{base + '/' if base else ''}{sub}/r.{rx + dx}.{rz + dz}.mca")
        listing = " ".join(picks)
        sh(["docker", "exec", SERVER, "sh", "-c",
            f"cd {W} && tar cf /tmp/cp.tar $(for p in {listing}; do [ -e $p ] && echo $p; done)"], 600, check=True)
        sh(["docker", "cp", f"{SERVER}:/tmp/cp.tar", str(dst / "cp.tar")], 600, check=True)
        sh(["docker", "exec", SERVER, "rm", "-f", "/tmp/cp.tar"], 60)
    finally:
        rcon("save-on")
    import tarfile
    with tarfile.open(dst / "cp.tar") as tf:
        tf.extractall(dst / "world")
    (dst / "cp.tar").unlink()
    size = _dir_bytes(dst / "world")
    meta["world_bytes"] = size
    meta["copy_seconds"] = round(time.time() - t0, 1)
    (dst / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[checkpoint] saved {name}: {size // (1 << 20)} MB in {meta['copy_seconds']} s; "
          f"bot: {meta['bot'].get('Pos', '(offline)')} hp={meta['bot'].get('Health', '?')} "
          f"stacks={meta['bot'].get('inventory_stacks', '?')}")
    enforce_budget(keep=(name,))
    return meta


def drop(name):
    """Delete a checkpoint from disk (used by the rolling periodic series)."""
    d = ROOT / name
    if d.exists():
        shutil.rmtree(d, ignore_errors=True)


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
    if len(sys.argv) < 2 or sys.argv[1] not in ("save", "restore", "list", "budget"):
        print(__doc__); sys.exit(2)
    op = sys.argv[1]
    if op == "list":
        list_()
        print(f"folder {_dir_bytes(ROOT) >> 20} MB of {BUDGET_GB} GB budget; disk free {disk_free_gb():.0f} GB")
    elif op == "budget":
        enforce_budget()
    elif op == "save":
        note = sys.argv[sys.argv.index("--note") + 1] if "--note" in sys.argv else ""
        save(sys.argv[2], note)
    else:
        restore(sys.argv[2])
