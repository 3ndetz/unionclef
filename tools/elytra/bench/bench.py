"""Shared parts of the elytra bench: configuration, an MCP client for the mod, RCON to the test server,
the arena, and the helpers every test uses (reset a player, fly, watch a status).

Configuration comes from the environment, or from local.json next to this file (git-ignored):

    MC_SETTINGS     the clients' altoclef_settings.json (for the MCP token);
                    default <%APPDATA% or ~>/.minecraft/altoclef/altoclef_settings.json
    BENCH_CLIENTS   nick=mcp-url pairs, comma separated; the first one is the bot under test
                    default arcmlchikkk=http://127.0.0.1:25350/mcp, pyhtun_lead=...:25351, pyhtun_wing=...:25352
    RCON_HOST, RCON_PORT, RCON_PASSWORD   the test server (default 127.0.0.1:25575; the password may
                    also sit in rcon.secret next to this file)
    FFMPEG          ffmpeg for compile.py (default: ffmpeg on PATH)

Nothing here prints the MCP token or the RCON password.
"""
import json
import os
import shutil
import pathlib
import socket
import struct
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "out"
_local = json.loads((HERE / "local.json").read_text(encoding="utf-8")) if (HERE / "local.json").exists() else {}


def conf(key, default=""):
    return os.environ.get(key) or _local.get(key) or default


_home = os.environ.get("APPDATA") or os.path.expanduser("~")
MC_SETTINGS = conf("MC_SETTINGS", os.path.join(_home, ".minecraft", "altoclef", "altoclef_settings.json"))
FFMPEG = conf("FFMPEG", "ffmpeg")
CLIENTS = dict(pair.split("=", 1) for pair in conf(
    "BENCH_CLIENTS",
    "arcmlchikkk=http://127.0.0.1:25350/mcp,pyhtun_lead=http://127.0.0.1:25351/mcp,"
    "pyhtun_wing=http://127.0.0.1:25352/mcp").split(","))
NICKS = list(CLIENTS)
BOT = NICKS[0]
G = -61          # the flat world's grass; feet stand at G + 1
F = G + 1


# ---- MCP (the mod's server: JSON-RPC tools/call over HTTP, bearer token) ----

_token = None


def _bearer():
    global _token
    if _token is None:
        _token = json.loads(pathlib.Path(MC_SETTINGS).read_text(encoding="utf-8")).get("mcpAuthToken", "")
    return _token


_checked = False


def _post(url, tool, args, timeout):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": tool, "arguments": args or {}}}).encode("utf-8")
    req = urllib.request.Request(url, body, {"Content-Type": "application/json",
                                             "Authorization": "Bearer " + _bearer()})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            res = json.loads(r.read().decode("utf-8")).get("result") or {}
        text = (res.get("content") or [{}])[0].get("text", "")
        return json.loads(text) if text.startswith(("{", "[")) else {"text": text}
    except Exception as e:  # a test reads a failed call as a failed step, not a crash
        return {"error": repr(e)[:200]}


def _match_clients():
    """Each client's MCP port by the name its player logs in with. A second client takes the next
    free port, so the ports go in the order the clients came up, not the order they were started
    in: with them taken as listed, a teleport went to one player and its flight to the other."""
    global _checked
    _checked = True
    urls = sorted(set(CLIENTS.values()))
    names = {}
    for url in urls:
        who = (_post(url, "getGameState", {}, 15).get("self") or {}).get("name")
        if who:
            names[who.lower()] = url
    for nick in CLIENTS:
        if nick.lower() in names:
            CLIENTS[nick] = names[nick.lower()]


def mcp(tool, args=None, nick=None, timeout=90):
    """Call a mod tool on a client (the bot by default); the tool's JSON answer, or {"error": ...}."""
    if not _checked:
        _match_clients()
    return _post(CLIENTS[nick or BOT], tool, args, timeout)


def me(nick=None):
    return mcp("getGameState", nick=nick).get("self", {})


def pos(nick=None):
    s = me(nick)
    return [float(v) for v in s["pos"].split(",")] if s.get("pos") else None


# ---- RCON ----

class Rcon:
    def __init__(self):
        pw = conf("RCON_PASSWORD") or (HERE / "rcon.secret").read_text(encoding="utf-8").strip()
        self.s = socket.create_connection((conf("RCON_HOST", "127.0.0.1"), int(conf("RCON_PORT", "25575"))), timeout=40)
        self.id = 0
        if self._req(3, pw)[0] == -1:
            raise SystemExit("rcon: wrong password")

    def _req(self, kind, body):
        self.id += 1
        data = struct.pack("<ii", self.id, kind) + body.encode("utf-8") + b"\x00\x00"
        self.s.sendall(struct.pack("<i", len(data)) + data)
        size = struct.unpack("<i", self._read(4))[0]
        rid, _ = struct.unpack("<ii", self._read(8))
        return rid, self._read(size - 8)[:-2].decode("utf-8", "replace")

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("rcon closed")
            buf += chunk
        return buf

    def cmd(self, command):
        return self._req(2, command)[1]


_rc = None


def cmd(*commands):
    global _rc
    if _rc is None:
        _rc = Rcon()
    return [_rc.cmd(c) for c in commands]


# ---- the arena ----

RINGS = [(80, F + 18, 130), (140, F + 24, 136), (200, F + 16, 124), (260, F + 26, 132), (320, F + 20, 128),
         (380, F + 14, 130)]


def arena():
    cmd("forceload add -160 -160 560 160", "forceload add 560 -32 1100 32",
        "gamerule send_command_feedback false", "time set 6000", "weather clear")
    # a gazebo: plank roof four blocks up on fence posts
    cmd(f"fill -43 {F+3} -3 -37 {F+3} 3 minecraft:oak_planks",
        *[f"fill {x} {F} {z} {x} {F+2} {z} minecraft:oak_fence" for x in (-43, -37) for z in (-3, 3)])
    # a leaf canopy five blocks up over a trunk
    cmd(f"fill -5 {F+4} 55 5 {F+5} 65 minecraft:oak_leaves[persistent=true]",
        f"fill 0 {F} 60 0 {F+4} 60 minecraft:oak_log")
    # a strip of tall trees on the line of a low-level flight
    for x in range(30, 211, 15):
        cmd(f"fill {x} {F} -82 {x} {F+9} -82 minecraft:oak_log",
            f"fill {x-2} {F+7} -84 {x+2} {F+10} -80 minecraft:oak_leaves[persistent=true] keep")
    # a water tank 16 deep, open on top; its walls rise a block over the water (no way out there),
    # a ledge level with the water along the east wall is the one bank. ("hollow" alone builds a lid.)
    cmd(f"fill 58 {F} -62 82 {F+16} -38 minecraft:stone_bricks hollow",
        f"fill 59 {F} -61 81 {F+15} -39 minecraft:water",
        f"fill 59 {F+16} -61 81 {F+16} -39 minecraft:air",
        f"fill 81 {F+15} -61 81 {F+15} -39 minecraft:stone_bricks")
    # rings: square frames with a 7x7 opening across a lane at z ~130
    for x, y, z in RINGS:
        cmd(f"fill {x} {y-4} {z-4} {x} {y+4} {z+4} minecraft:yellow_concrete",
            f"fill {x} {y-3} {z-3} {x} {y+3} {z+3} minecraft:air")


# The maze: a glass hall 240 long, 40 wide, 30 high, open at its west end, cut by three coloured walls,
# each with one 9x9 gap: low on the left, high on the right, in the middle. Straight at cruise height
# hits the ceiling; the way is through the gaps (route search: elytraRouteSearch).
MAZE_Z = -200
MAZE_WALLS = [  # (x, colour, gap z from, gap y from) -- gaps are 9x9
    (660, "red", MAZE_Z - 16, F + 3), (720, "blue", MAZE_Z + 8, F + 19), (780, "lime", MAZE_Z - 4, F + 11)]


# The compact maze: the same three gaps, walls 25 apart instead of 60, a hall 112 long -- all of it
# within the view distance (8 chunks, 128 blocks) from where the glider is after its take-off, so the
# test is the way through the gaps and its physics, not finding out what lies beyond sight (the long
# hall tests that). The climbs are steeper.
MAZE2_Z = -320
MAZE2_X1 = 712
MAZE2_WALLS = [(627, "red", MAZE2_Z - 16, F + 3), (652, "blue", MAZE2_Z + 8, F + 19), (677, "lime", MAZE2_Z - 4, F + 11)]


def in_maze(p, compact=False):
    """Feet inside the maze hall: under its roof, between its side walls (not on the roof, not beside it)."""
    x, y, z = p[0], p[1], p[2]
    mz, x1 = (MAZE2_Z, MAZE2_X1) if compact else (MAZE_Z, 840)
    return 600 < x < x1 and F <= y < F + 30 and mz - 21 < z < mz + 21


def hall_unloaded(compact=True):
    """Chunks of the maze hall the bot's client does not have yet: one point of the glass floor in each
    chunk, read from the client's world (an unloaded chunk reads as air). A route searched before the
    hall is in takes the missing chunks for walls."""
    mz, x1 = (MAZE2_Z, MAZE2_X1) if compact else (MAZE_Z, 840)
    z0, z1 = mz - 21, mz + 21
    missing = []
    for cx in range(600 >> 4, (x1 >> 4) + 1):
        for cz in range(z0 >> 4, (z1 >> 4) + 1):
            x = min(max(cx * 16 + 8, 600), x1)
            z = min(max(cz * 16 + 8, z0), z1)
            if mcp("getBlockAt", {"x": x, "y": F, "z": z}).get("is_air") != "false":
                missing.append((cx, cz))
    return missing


def wait_hall_loaded(compact=True, timeout=30):
    """Wait till the client has every chunk of the hall; how long it took and what is still missing."""
    t0 = time.time()
    missing = hall_unloaded(compact)
    while missing and time.time() - t0 < timeout:
        time.sleep(0.5)
        missing = hall_unloaded(compact)
    return {"hallWaitS": round(time.time() - t0, 1), "hallMissing": len(missing)}


def server_tick():
    """The server's tick rate and average milliseconds per tick (/tick query), as text."""
    return " ".join(cmd("tick query")[0].split())


def maze_compact():
    z0, z1 = MAZE2_Z - 21, MAZE2_Z + 21
    cmd("forceload add 540 -360 760 -280",
        f"fill 600 {F} {z0} {MAZE2_X1} {F} {z1} minecraft:glass",
        f"fill 600 {F+30} {z0} {MAZE2_X1} {F+30} {z1} minecraft:glass",
        f"fill 600 {F} {z0} {MAZE2_X1} {F+30} {z0} minecraft:glass",
        f"fill 600 {F} {z1} {MAZE2_X1} {F+30} {z1} minecraft:glass",
        f"fill {MAZE2_X1} {F} {z0} {MAZE2_X1} {F+30} {z1} minecraft:glass")
    for x, colour, gz, gy in MAZE2_WALLS:
        cmd(f"fill {x} {F+1} {z0+1} {x+1} {F+29} {z1-1} minecraft:{colour}_concrete",
            f"fill {x} {gy} {gz} {x+1} {gy+8} {gz+8} minecraft:air")


def maze():
    z0, z1 = MAZE_Z - 21, MAZE_Z + 21
    # face by face: one hollow fill of the whole hall is ten times the 32768 blocks fill allows
    cmd("forceload add 540 -240 860 -160",
        f"fill 600 {F} {z0} 840 {F} {z1} minecraft:glass",            # floor
        f"fill 600 {F+30} {z0} 840 {F+30} {z1} minecraft:glass",      # ceiling
        f"fill 600 {F} {z0} 840 {F+30} {z0} minecraft:glass",         # side walls
        f"fill 600 {F} {z1} 840 {F+30} {z1} minecraft:glass",
        f"fill 840 {F} {z0} 840 {F+30} {z1} minecraft:glass")         # the far end; the west end is the way in
    for x, colour, gz, gy in MAZE_WALLS:
        cmd(f"fill {x} {F+1} {z0+1} {x+1} {F+29} {z1-1} minecraft:{colour}_concrete",
            f"fill {x} {gy} {gz} {x+1} {gy+8} {gz+8} minecraft:air")


def reset(x, z, rockets_in_hotbar=True, y=None, yaw=0, nick=None):
    """A clean start: creative, an elytra, 64 rockets (in the backpack if asked), at x, z."""
    nick = nick or BOT
    cmd(f"gamemode creative {nick}", f"clear {nick}", f"effect clear {nick}")
    if not rockets_in_hotbar:  # fill the hotbar so the rockets land in the backpack
        cmd(*[f"item replace entity {nick} hotbar.{i} with minecraft:cobblestone" for i in range(9)])
    cmd(f"give {nick} minecraft:elytra", f"give {nick} minecraft:firework_rocket 64",
        f"tp {nick} {x} {F if y is None else y} {z} {yaw} 0")
    mcp("ExecuteCommand", {"cmd": "@stop"}, nick=nick)
    mcp("flyStop", nick=nick)
    time.sleep(2)


def watch(status_tool, seconds, nick=None):
    """Poll a status tool until it leaves its running phases; the last status and the path flown."""
    t0, path, st = time.time(), [], {}
    while time.time() - t0 < seconds:
        st = mcp(status_tool, nick=nick)
        p = pos(nick)
        if p:
            path.append([round(v, 1) for v in p])
        if st.get("phase") in ("done", "failed", "idle", "stopped"):
            break
        time.sleep(0.4)
    st["seconds"] = round(time.time() - t0, 1)
    return st, path


class Video:
    """Recorded by the mod (recordStart): the game window only, 1920x1080, 20 fps. Needs the path of
    ffmpeg in <game>/altoclef/recorder_ffmpeg.txt; without it the test simply runs unfilmed."""

    def __init__(self, name, on=True, nick=None):
        self.nick, self.on = nick or BOT, on
        if not on:
            return
        self.stamp = f"{time.strftime('%m%d-%H%M%S')}-{name}"
        self.on = bool(mcp("recordStart", {"name": self.stamp, "fps": 20}, nick=self.nick).get("ok"))

    def stop(self):
        if not self.on:
            return None
        time.sleep(1.5)
        done = mcp("recordStop", nick=self.nick)
        if not done.get("file") or not os.path.exists(done["file"]):
            return None
        (OUT / "video").mkdir(parents=True, exist_ok=True)
        out = OUT / "video" / f"{self.stamp}.mp4"
        shutil.move(done["file"], out)
        return str(out)


def dist(a, x, z):
    return ((a[0] - x) ** 2 + (a[2] - z) ** 2) ** 0.5
