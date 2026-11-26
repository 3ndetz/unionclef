"""Runs INSIDE the client container: call getInventoryFull, getEntitiesInfo(8), getCrosshairTarget over
py4j and over MCP (tools/call, the port and token from altoclef_settings.json; the token is never
printed), and compare the results field by field (floats within 1e-6). Prints one JSON line per tool.
(2026-11-27)"""
import json
import urllib.request

from py4j.java_gateway import GatewayParameters, JavaGateway

cfg = json.load(open("/mc-data/altoclef/altoclef_settings.json", encoding="utf-8"))
port = int(cfg.get("mcpPort", 25350))
token = cfg.get("mcpAuthToken", "")


def to_py(o):
    if hasattr(o, "keySet"):
        return {str(k): to_py(o.get(k)) for k in o.keySet()}
    if hasattr(o, "iterator") and not isinstance(o, str):
        return [to_py(x) for x in o]
    return o


def mcp(name, args):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": name, "arguments": args}}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{port}/mcp", data=body,
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
    r = json.loads(urllib.request.urlopen(req, timeout=15).read())
    res = r.get("result", {})
    text = res.get("content", [{}])[0].get("text", "")
    return json.loads(text), bool(res.get("isError"))


def same(a, b, path="$"):
    if isinstance(a, dict) and isinstance(b, dict):
        if set(a) != set(b):
            return f"{path}: keys {sorted(set(a) ^ set(b))}"
        for k in a:
            d = same(a[k], b[k], f"{path}.{k}")
            if d:
                return d
        return None
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return f"{path}: len {len(a)} vs {len(b)}"
        for i, (x, y) in enumerate(zip(a, b)):
            d = same(x, y, f"{path}[{i}]")
            if d:
                return d
        return None
    if isinstance(a, (int, float)) and isinstance(b, (int, float)) and not isinstance(a, bool) and not isinstance(b, bool):
        return None if abs(float(a) - float(b)) < 1e-6 else f"{path}: {a} vs {b}"
    return None if a == b else f"{path}: {a!r} vs {b!r}"


gw = JavaGateway(gateway_parameters=GatewayParameters(port=25333, auto_convert=True))
ep = gw.entry_point
for name, args, call in (("getInventoryFull", {}, lambda: ep.getInventoryFull()),
                         ("getEntitiesInfo", {"limit": 8}, lambda: ep.getEntitiesInfo(8)),
                         ("getCrosshairTarget", {}, lambda: ep.getCrosshairTarget())):
    a = to_py(call())
    b, err = mcp(name, args)
    diff = "MCP error" if err else same(a, b)
    print(json.dumps({"tool": name, "match": diff is None, "diff": diff,
                      "sample": json.dumps(b)[:200]}), flush=True)
gw.close()
