"""Sample the live task chain and tally which LEAF the bot sits in.

A freeze dump is one frame, and three passes of this session were spent reasoning from
single frames that did not survive. This polls instead, so the answer is a distribution.
Usage: tasksample.py [seconds] [interval]
"""
import json, re, subprocess, sys, time, collections

CLIENT, PORT = "uctest-mc-tester1", 25333
src = open("deploy/runner/gamer_smoke.py", encoding="utf-8").read()
SNIP = re.search(r'SNIP\s*=\s*(?:r?"""|r?\'\'\')(.*?)(?:"""|\'\'\')', src, re.S).group(1)

def q(op):
    try:
        r = subprocess.run(["docker", "exec", CLIENT, "python3", "-c", SNIP,
                            json.dumps({"op": op, "port": PORT})],
                           capture_output=True, text=True, timeout=60)
        return (r.stdout.strip().splitlines() or [""])[-1]
    except Exception:
        return ""

secs = int(sys.argv[1]) if len(sys.argv) > 1 else 600
step = int(sys.argv[2]) if len(sys.argv) > 2 else 5
leaves, deadline = collections.Counter(), time.time() + secs
chains = {}  # leaf -> the full chain seen with it, so the PARENT is visible too
while time.time() < deadline:
    t = q("task")
    # the LAST numbered entry is the leaf the bot is actually executing
    parts = re.findall(r"\d+(?:\.\d+)*\. <([^>]{0,60})", t)
    leaf = parts[-1].strip() if parts else "(none)"
    leaves[leaf] += 1
    if leaf not in chains and parts:
        chains[leaf] = " -> ".join(p.strip()[:34] for p in parts[-5:])
    time.sleep(step)

total = sum(leaves.values()) or 1
for leaf, n in leaves.most_common(12):
    print("%5.1f%%  %4d  %s" % (100.0 * n / total, n, leaf))
    if leaf in chains:
        print("           chain: %s" % chains[leaf])
