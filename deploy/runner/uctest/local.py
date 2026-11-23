"""uctest on clients and a server running on this machine instead of in containers.

The suite reaches a bot with `docker exec <container> python3 -c <snippet>` and the server with
`docker exec <server> rcon-cli`. Here the same snippet runs in a local Python that has py4j, against
the client's gateway port, and rcon is a plain socket to the local server. Everything above that
(Bot, Ctx, the scenarios, their verdicts) is the suite's own code, unchanged.

    UCTEST_PY4J_PYTHON  a python with py4j installed (default: this one)
    UCTEST_RCON         host:port of the server's rcon (default 127.0.0.1:25575)
    UCTEST_RCON_PASSWORD, or UCTEST_RCON_PASSWORD_FILE  its password (never printed)
"""
import json
import os
import socket
import struct
import sys

from . import process as subprocess
from .harness import Py4jClient, Py4jError, Rcon


# The suite's snippet answers one request and exits; here one process per client stays up and
# answers a request per line. A fresh python + gateway per call took about a second, which left a
# pursuit sampled four times in a minute.
HELPER = r"""
import json, sys
from py4j.java_gateway import JavaGateway, GatewayParameters
from py4j.java_collections import JavaMap, JavaList, JavaSet, JavaArray
gws = {}
def conv(v):
    if isinstance(v, JavaMap):
        return {str(k): conv(v[k]) for k in v.keySet()}
    if isinstance(v, (JavaList, JavaSet, JavaArray)):
        return [conv(x) for x in v]
    return v
for line in sys.stdin:
    req = json.loads(line)
    port = req.get("port", 25333)
    if port not in gws:
        gws[port] = JavaGateway(gateway_parameters=GatewayParameters(
            address="127.0.0.1", port=port, auto_convert=True))
    mc = gws[port].entry_point
    out = []
    for call in req["calls"]:
        try:
            out.append({"ok": True, "r": conv(getattr(mc, call["m"])(*call.get("a", [])))})
        except Exception as e:
            out.append({"ok": False, "e": str(e)[-400:]})
    print(json.dumps(out, default=str), flush=True)
"""


class LocalPy4jClient(Py4jClient):
    """The py4j gateway of a client on this machine, by port, through one long-lived helper."""

    def __init__(self, port):
        super().__init__(container=f"local:{port}", port=port)
        self.proc = None

    def _helper(self):
        if self.proc is None or self.proc.poll() is not None:
            python = os.environ.get("UCTEST_PY4J_PYTHON", sys.executable)
            self.proc = subprocess.Popen([python, "-u", "-c", HELPER], stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        return self.proc

    def batch(self, calls, timeout=30):
        req = json.dumps({"port": self.port, "calls": [{"m": c[0], "a": list(c[1:])} for c in calls]})
        p = self._helper()
        p.stdin.write(req + "\n")
        p.stdin.flush()
        line = p.stdout.readline()
        if not line:
            raise Py4jError(f"{self.container}: helper died: {p.stderr.read()[-300:]}")
        out = json.loads(line)
        results = []
        for c, item in zip(calls, out):
            if not item.get("ok"):
                raise Py4jError(f"{self.container}.{c[0]}: {item.get('e')}")
            results.append(item.get("r"))
        return results


class LocalRcon(Rcon):
    """rcon over a socket, with the suite's rejection handling (Rcon.cmd's checks) kept."""

    def __init__(self):
        super().__init__(container="local")
        host, _, port = os.environ.get("UCTEST_RCON", "127.0.0.1:25575").partition(":")
        pw = os.environ.get("UCTEST_RCON_PASSWORD")
        if pw is None:
            with open(os.environ["UCTEST_RCON_PASSWORD_FILE"], encoding="utf-8") as f:
                pw = f.read().strip()
        self.s = socket.create_connection((host, int(port)), timeout=40)
        self._id = 0
        if self._send(3, pw)[0] == -1:
            raise RuntimeError("rcon: wrong password")

    def _send(self, kind, body):
        self._id += 1
        data = body.encode("utf-8")
        self.s.sendall(struct.pack("<iii", len(data) + 10, self._id, kind) + data + b"\0\0")
        size = struct.unpack("<i", self._read(4))[0]
        payload = self._read(size)
        rid, _ = struct.unpack("<ii", payload[:8])
        return rid, payload[8:-2].decode("utf-8", "replace")

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.s.recv(n - len(buf))
            if not chunk:
                raise RuntimeError("rcon: connection closed")
            buf += chunk
        return buf

    def cmd(self, command, timeout=20, allow_reject=False):
        out = self._send(2, command)[1].strip()
        if (not allow_reject and any(x in out for x in self.REJECTIONS)
                and self._rejects_this(out, command)):
            raise RuntimeError(f"rcon REJECTED `{command}`: {out[:300]}")
        return out
