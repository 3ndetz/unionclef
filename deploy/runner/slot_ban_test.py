#!/usr/bin/env python3
"""G83 bench: one slot click gets ONE server verdict -- a burst of packets must not muzzle the slot
for ten minutes, and the craft that needs the slot must go through.

Round 42 stood from 22:48 to 22:57 with a log in hotbar slot 38 and the plank craft asking for it
(mv=4706/3754/952/0/0: 952 pick-ups asked, none delivered), nothing in the log, and walked again
exactly six hundred seconds after the run's only muzzle line: "window slot 38 (flint x1) --
blacklisting for 4s (cancel #1)". The pending click was never consumed by its verdict, so every
further server packet for the slot inside 600 ms -- a full inventory sync is one per slot --
matched the same click again: cancel #2, #3, 4 s -> 30 s -> 600 s in one burst, printed once
("already blocked"). Then a death, a new body, a log in the muzzled slot, ten silent minutes.

    python3 deploy/runner/slot_ban_test.py          # exit 0 = PASS

Flat server: the bot with ONE dark oak log in hotbar slot 1 (window slot 37 of the inventory
screen). The py4j hook `debugSlotRevertBurst(37, 3)` replays the burst: one pending click, three
server updates carrying the pre-click stack. Old code: cancel #1, #2, #3 in a row -> a 600 s muzzle.
Then `@get dark_oak_planks 4`, which must pick the log up out of that very slot. PASS = the muzzle
after the burst is at most the single-cancel four seconds, and four planks are in the pack within
the window.
"""
import functools, json, os, subprocess, sys, time
print = functools.partial(print, flush=True)
SERVER = "uctest-server"; C1 = "uctest-mc-tester1"; BOT = "tester1"
X, GROUND, Z = 2400, -60, 300
LOG_WINDOW_SLOT = 37                 # PlayerScreenHandler: hotbar i -> window 36 + i; hotbar.1 -> 37
WINDOW_S = 45
SINGLE_CANCEL_MS = 4000

SNIP = r"""
import json,sys
from py4j.java_gateway import JavaGateway,GatewayParameters
req=json.loads(sys.argv[1])
gw=JavaGateway(gateway_parameters=GatewayParameters(address="127.0.0.1",port=25333,auto_convert=True))
mc=gw.entry_point; op=req["op"]; out={}
if op=="state": out={"inGame":mc.inGame(),"busy":mc.hasActiveTask()}
elif op=="connect": mc.ConnectToServer(req["ip"]); out={"ok":True}
elif op=="cmd": mc.ExecuteCommand(req["c"]); out={"ok":True}
elif op=="chatcmd": mc.ChatMessage(req["c"]); out={"ok":True}
elif op=="chat": out={"chat":[str(c) for c in mc.getRecentChat(req.get("n",8))]}
elif op=="stats": out={"s": str(mc.placeStats())}
elif op=="burst": out={"r": str(mc.debugSlotRevertBurst(int(req["slot"]), int(req["n"])))}
elif op=="banms": out={"r": str(mc.slotBanRemainingMs(int(req["slot"])))}
elif op=="inv":
    items={}
    try:
        for s in mc.getInventoryFull().get("slots") or []:
            sd=dict(s)
            if not sd.get("empty"):
                nm=str(sd.get("item") or sd.get("name") or "")
                try: n=int(sd.get("count") or 1)
                except Exception: n=1
                if nm: items[nm]=items.get(nm,0)+n
    except Exception: pass
    out={"items": items}
print(json.dumps(out,default=str)); gw.close()
"""


def sh(a, to=60):
    return subprocess.run(a, capture_output=True, text=True, timeout=to)


def py4j(op, to=40, **kw):
    r = sh(["docker", "exec", C1, "python3", "-c", SNIP, json.dumps({"op": op, **kw})], to)
    if r.returncode != 0:
        raise RuntimeError(f"{op}: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def rcon(c):
    return sh(["docker", "exec", SERVER, "rcon-cli", c]).stdout.strip()


def planks():
    try:
        return sum(n for k, n in py4j("inv")["items"].items() if "dark_oak_planks" in k)
    except Exception:
        return 0


def ban_ms(r):
    try:
        return int(str(r).split("=", 1)[1])
    except (ValueError, IndexError):
        return -9


def main():
    if BOT not in rcon("list"):
        py4j("connect", ip="test-server"); t0 = time.time()
        while time.time() - t0 < 120 and BOT not in rcon("list"):
            time.sleep(5)
        if BOT not in rcon("list"):
            print("FAIL: never joined test-server"); return 2
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop"); time.sleep(1)
    rcon(f"gamemode survival {BOT}")
    rcon("difficulty peaceful")
    rcon(f"forceload add {X-12} {Z-12} {X+12} {Z+12}"); time.sleep(1)
    rcon(f"fill {X-8} {GROUND+1} {Z-8} {X+8} {GROUND+5} {Z+8} minecraft:air")
    rcon(f"fill {X-8} {GROUND} {Z-8} {X+8} {GROUND} {Z+8} minecraft:grass_block")
    rcon(f"spawnpoint {BOT} {X} {GROUND+1} {Z}")
    rcon(f"tp {BOT} {X+0.5} {GROUND+1} {Z+0.5}")
    rcon(f"clear {BOT}")
    rcon(f"item replace entity {BOT} hotbar.1 with minecraft:dark_oak_log 1")
    rcon(f"effect give {BOT} minecraft:instant_health 1 10 true")
    time.sleep(2)
    py4j("stats")   # reset the counters
    inv = py4j("inv")["items"]
    if not any("dark_oak_log" in k for k in inv):
        print(f"FAIL: the log was not given (inv={inv})"); return 2
    burst = py4j("burst", slot=LOG_WINDOW_SLOT, n=3)["r"]
    ms = ban_ms(burst)
    print(f"one click on window slot {LOG_WINDOW_SLOT} (the log), three server packets with the old stack -> {burst}")
    if ms < 0:
        print(f"FAIL: the hook could not run ({burst})"); return 2
    py4j("cmd", c="@get dark_oak_planks 4")
    t0 = time.time(); seen = set(); got = 0; drops = "?"
    while time.time() - t0 < WINDOW_S:
        time.sleep(3)
        ch = [c for c in py4j("chat", n=8)["chat"] if c not in seen]; seen.update(ch)
        note = [c for c in ch if any(w in c for w in ("muzzled", "cancelled slot", "MOVEMISMATCH", "stuck"))]
        got = planks()
        st = py4j("stats")["s"]
        for t in st.split():
            if t.startswith("shBan="):
                drops = t.split("=", 1)[1]
        print(f"  t={time.time()-t0:.0f}s planks={got} shBan={drops}"
              + (" | " + " || ".join(x[-80:] for x in note[-2:]) if note else ""))
        if got >= 4:
            break
    py4j("cmd", c="@stop"); py4j("chatcmd", c=";stop")
    rcon(f"forceload remove {X-12} {Z-12} {X+12} {Z+12}")
    print(f"result: banAfterBurst={ms}ms planks={got} took={time.time()-t0:.0f}s")
    if ms <= SINGLE_CANCEL_MS and got >= 4:
        print("PASS: one click, one verdict; the craft picked the log out of the slot"); return 0
    if ms > SINGLE_CANCEL_MS:
        print(f"FAIL: a burst of packets muzzled the slot for {ms/1000:.0f}s (> {SINGLE_CANCEL_MS/1000:.0f}s)"); return 1
    print("FAIL: the planks were not crafted within the window"); return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass
    sys.exit(main())
