"""uctest harness — the ONE py4j bridge + rcon + wait/artifact helpers.

Every scenario talks to a bot through Py4jClient.call("method", args...) — a
generic call-by-name executed inside the bot's container (py4j listens on the
container loopback). This replaces the per-test PY4J_SNIPPET copies (RW-5).
No host-side dependencies beyond python3 + docker CLI.
"""
import json
import os
import subprocess
import time

SERVER_CONTAINER = "uctest-server"

# Generic bridge: batch of {"m": name, "a": [args]} calls, JSON out. Java maps/
# lists are converted to plain python; anything else falls back to str().
_PY4J_SNIPPET = r"""
import json, sys
from py4j.java_gateway import JavaGateway, GatewayParameters
req = json.loads(sys.argv[1])
gw = JavaGateway(gateway_parameters=GatewayParameters(
    address="127.0.0.1", port=req.get("port", 25333), auto_convert=True))
mc = gw.entry_point
def conv(v):
    try:
        from py4j.java_collections import JavaMap, JavaList, JavaSet, JavaArray
        if isinstance(v, JavaMap):
            return {str(k): conv(v[k]) for k in v.keySet()}
        if isinstance(v, (JavaList, JavaSet, JavaArray)):
            return [conv(x) for x in v]
    except Exception:
        pass
    return v
out = []
for call in req["calls"]:
    try:
        r = getattr(mc, call["m"])(*call.get("a", []))
        if isinstance(r, (bytes, bytearray)):
            path = call.get("bytes_to", "/tmp/py4j_bytes.bin")
            open(path, "wb").write(bytes(r))
            r = {"bytes": len(r), "path": path}
        out.append({"ok": True, "r": conv(r)})
    except Exception as e:
        out.append({"ok": False, "e": str(e)[-400:]})
print(json.dumps(out, default=str))
gw.close()
"""


def sh(args, timeout=30):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)


class Py4jError(RuntimeError):
    pass


class Py4jClient:
    """py4j entry point of one bot container, via docker exec."""

    def __init__(self, container, port=25333):
        self.container = container
        self.port = port

    def batch(self, calls, timeout=30):
        """calls: list of (method, args...) tuples -> list of results (raises on
        transport failure; per-call java errors raise Py4jError with detail)."""
        req = json.dumps({
            "port": self.port,
            "calls": [{"m": c[0], "a": list(c[1:])} for c in calls],
        })
        r = sh(["docker", "exec", self.container, "python3", "-c", _PY4J_SNIPPET, req],
               timeout)
        if r.returncode != 0:
            raise Py4jError(f"{self.container}: {r.stderr.strip()[-300:]}")
        out = json.loads(r.stdout.strip().splitlines()[-1])
        results = []
        for c, item in zip(calls, out):
            if not item.get("ok"):
                raise Py4jError(f"{self.container}.{c[0]}: {item.get('e')}")
            results.append(item.get("r"))
        return results

    def call(self, method, *args, timeout=30):
        return self.batch([(method, *args)], timeout=timeout)[0]

    def try_call(self, method, *args, timeout=30):
        try:
            return True, self.call(method, *args, timeout=timeout)
        except Exception as e:  # noqa: BLE001 - probing is the point
            return False, str(e)

    def screenshot(self, local_path, timeout=40):
        """getScreenshot -> /tmp in-container -> docker cp to local_path."""
        req = json.dumps({"port": self.port, "calls": [
            {"m": "getScreenshot", "a": [], "bytes_to": "/tmp/uctest_shot.png"}]})
        r = sh(["docker", "exec", self.container, "python3", "-c", _PY4J_SNIPPET, req],
               timeout)
        if r.returncode != 0:
            return False
        r2 = sh(["docker", "cp", f"{self.container}:/tmp/uctest_shot.png", local_path])
        return r2.returncode == 0 and os.path.exists(local_path)


class Rcon:
    """rcon-cli of the test server + entity readers used by every scenario."""

    def __init__(self, container=SERVER_CONTAINER):
        self.container = container

    # A REJECTED COMMAND IS NOT A SUCCESSFUL ONE. rcon-cli exits 0 while the SERVER replies
    # "Unknown or incomplete command" — so every fill, setblock, tp, gamerule, scoreboard and
    # effect in this harness used to succeed as far as Python was concerned. One instance of
    # that (a silently failing spreadplayers) already burned three runs; the fix was made at
    # the call site, so the hazard stayed everywhere else. It is fixed here instead.
    REJECTIONS = ("Unknown or incomplete command", "Incorrect argument",
                  "Expected ", "Unknown command", "Invalid ")

    def cmd(self, command, timeout=20, allow_reject=False):
        r = sh(["docker", "exec", self.container, "rcon-cli", command], timeout)
        if r.returncode != 0:
            raise RuntimeError(f"rcon `{command}`: {r.stderr.strip()[-300:]}")
        out = r.stdout.strip()
        if not allow_reject and any(x in out for x in self.REJECTIONS):
            raise RuntimeError(f"rcon REJECTED `{command}`: {out[:300]}")
        return out

    def entity_float(self, name, path):
        out = self.cmd(f"data get entity {name} {path}")
        try:
            return float(out.rsplit(":", 1)[-1].strip().rstrip("dbfs"))
        except (ValueError, IndexError):
            return None

    def entity_pos(self, name):
        out = self.cmd(f"data get entity {name} Pos")
        try:
            inner = out[out.index("[") + 1:out.index("]")]
            return [float(p.strip().rstrip("d")) for p in inner.split(",")]
        except (ValueError, IndexError):
            return None

    def held_item(self, name):
        out = self.cmd(f"data get entity {name} SelectedItem.id")
        return out.rsplit(":", 1)[-1].strip().strip('"') if '"' in out else None

    def hurt_time(self, name):
        v = self.entity_float(name, "HurtTime")
        return int(v) if v is not None else None

    def score(self, name, objective):
        out = self.cmd(f"scoreboard players get {name} {objective}")
        try:
            return int(out.split("has ")[1].split(" ")[0])
        except (ValueError, IndexError):
            return 0

    def reset_kd(self, names):
        for obj, crit in (("k", "playerKillCount"), ("d", "deathCount")):
            self.cmd(f"scoreboard objectives remove {obj}")
            self.cmd(f"scoreboard objectives add {obj} {crit}")
        for n in names:
            for obj in ("k", "d"):
                self.cmd(f"scoreboard players set {n} {obj} 0")


def wait_for(desc, fn, timeout_s, interval=3, log=print):
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout_s:
        try:
            last = fn()
            if last:
                log(f"  [ok] {desc} ({time.time() - t0:.0f}s)")
                return last
        except Exception as e:  # noqa: BLE001 - polling
            last = e
        time.sleep(interval)
    raise TimeoutError(f"{desc}: timed out after {timeout_s}s (last: {last})")


class Artifacts:
    """Per-scenario artifact dir: timeline.jsonl, chat, screenshots, verdict."""

    def __init__(self, root, scenario):
        self.dir = os.path.join(root, scenario)
        os.makedirs(self.dir, exist_ok=True)
        self._timeline = open(os.path.join(self.dir, "timeline.jsonl"), "w", encoding="utf-8")

    def path(self, name):
        return os.path.join(self.dir, name)

    def sample(self, record):
        self._timeline.write(json.dumps(record) + "\n")
        self._timeline.flush()

    def write_json(self, name, obj):
        with open(self.path(name), "w", encoding="utf-8") as f:
            json.dump(obj, f, indent=1, default=str)

    def write_text(self, name, text):
        with open(self.path(name), "w", encoding="utf-8") as f:
            f.write(text)

    def close(self):
        self._timeline.close()
