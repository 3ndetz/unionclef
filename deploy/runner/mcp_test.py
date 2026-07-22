#!/usr/bin/env python3
"""In-mod MCP server autotest — Streamable HTTP JSON-RPC over the LAN port.

Hits the MCP server the mod hosts (0.0.0.0:25350, published to the host by
compose.test.yml) exactly as a cognitive agent (Claude) would over the LAN:
  initialize -> tools/list -> tools/call (getGameState read + a real action).
Proves the control surface works end-to-end without py4j/docker-exec. Exit 0=pass.
"""
import functools, json, subprocess, sys, time, urllib.request
print = functools.partial(print, flush=True)
SERVER="uctest-server"; BOT="tester1"; MCP="http://127.0.0.1:25350/mcp"
_id=0
def rpc(method, params=None, timeout=30):
    global _id; _id+=1
    body={"jsonrpc":"2.0","id":_id,"method":method}
    if params is not None: body["params"]=params
    req=urllib.request.Request(MCP, data=json.dumps(body).encode(),
        headers={"Content-Type":"application/json","Accept":"application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())
def call(name, args=None, timeout=30):
    r=rpc("tools/call", {"name":name,"arguments":args or {}}, timeout)
    if "error" in r: raise RuntimeError(r["error"])
    txt=r["result"]["content"][0]["text"]
    try: return json.loads(txt)
    except Exception: return txt
def rcon(c,t=20): return subprocess.run(["docker","exec",SERVER,"rcon-cli",c],capture_output=True,text=True,timeout=t).stdout.strip()
def wait_for(desc,fn,ts,iv=3):
    t0=time.time(); last=None
    while time.time()-t0<ts:
        try:
            last=fn()
            if last: print(f"  [ok] {desc}"); return last
        except Exception as e: last=e
        time.sleep(iv)
    raise TimeoutError(f"{desc}: {ts}s ({last})")

def main():
    wait_for("rcon", lambda:"players" in rcon("list"),300,5)
    # 1. transport up + initialize
    init=wait_for("MCP initialize", lambda: rpc("initialize",{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"autotest","version":"1"}}),600,10)
    server=init.get("result",{}).get("serverInfo",{})
    print(f"  serverInfo: {server}")
    # 2. tools/list
    tl=rpc("tools/list")
    names=[t["name"] for t in tl["result"]["tools"]]
    print(f"  tools ({len(names)}): {', '.join(names[:12])}...")
    need={"getGameState","gotoXYZ","pathStatus","fillSelection","select","buildDefenseAround","clickMenuByName","ConnectToServer"}
    missing=need-set(names)
    # 3. ensure in game (agent-style: connect if needed)
    if not call("inGame").get("ok"):
        call("ConnectToServer",{"ip":"test-server"})
        wait_for("in game", lambda: call("inGame").get("ok"), 180, 5); time.sleep(4)
    # 4. read lever: getGameState
    gs=call("getGameState")
    print(f"  getGameState: inGame={gs.get('inGame')} self.pos={gs.get('self',{}).get('pos')} players={gs.get('playerCount')}")
    # 5. action lever end-to-end: select + fillSelection via MCP, verify with rcon
    rcon("forceload add 0 0 8 8"); rcon("fill 1 -60 1 2 -60 2 air"); rcon("fill 0 -61 0 4 -61 4 stone")
    rcon(f"item replace entity {BOT} hotbar.0 with dirt 64")
    call("selectHotbar",{"slot":0})
    rcon(f"tp {BOT} 0.5 -60 0.5 -45 0"); time.sleep(3)
    call("select",{"x1":1,"y1":-60,"z1":1,"x2":2,"y2":-60,"z2":2})
    fr=call("fillSelection",{"block":"dirt"})
    print(f"  fillSelection via MCP: {fr}")
    time.sleep(1)
    cells=[(1,-60,1),(2,-60,1),(1,-60,2),(2,-60,2)]
    filled=sum(1 for (x,y,z) in cells if "passed" in rcon(f"execute if block {x} {y} {z} dirt").lower())

    print("\n=== RESULTS ===")
    print(f"  initialize serverInfo.name: {server.get('name')}")
    print(f"  tools present: {len(names)} | missing required: {missing or 'none'}")
    print(f"  getGameState inGame: {gs.get('inGame')}")
    print(f"  fillSelection via MCP -> cells dirt: {filled}/4")
    ok = (server.get("name")=="unionclef" and not missing and gs.get("inGame") and filled>=4)
    print("  MCP:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)

if __name__=="__main__": main()
