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
seq = []      # ordered leaves, so task SWITCHING can be counted
while time.time() < deadline:
    t = q("task")
    # the LAST numbered entry is the leaf the bot is actually executing
    # The chain prints a single task as "1. Main task: <X>" and nested ones as "1.1. <X>".
    # Matching only the second form reported "(none)" for 29% of samples -- which were in
    # fact the Unstuck Chain running <Shimmying>. Fifth instrument error this session;
    # verify the parser against a raw response before trusting any distribution it prints.
    parts = re.findall(r"\d+(?:\.\d+)*\.\s*(?:Main task:\s*)?<([^>]{0,60})", t)
    leaf = parts[-1].strip() if parts else "(none)"
    leaves[leaf] += 1
    seq.append(leaf)
    if leaf not in chains and parts:
        chains[leaf] = " -> ".join(p.strip()[:34] for p in parts[-5:])
    time.sleep(step)

total = sum(leaves.values()) or 1
# The operator watched the bot walk to a block, leave to grab something, come back, repeat.
# That is not visible in a leaf HISTOGRAM -- both leaves look busy. Count the SWITCHES.
switches = sum(1 for i in range(1, len(seq)) if seq[i] != seq[i-1])
kinds = collections.Counter()
for i in range(1, len(seq)):
    if seq[i] != seq[i-1]:
        kinds[(seq[i-1].split(" at ")[0][:26], seq[i].split(" at ")[0][:26])] += 1
print("SWITCHES: %d over %d samples (%.0f%% of ticks change task)"
      % (switches, len(seq), 100.0 * switches / max(len(seq) - 1, 1)))
for (a_, b_), n in kinds.most_common(5):
    print("   %3d  %s  ->  %s" % (n, a_, b_))
print()
for leaf, n in leaves.most_common(12):
    print("%5.1f%%  %4d  %s" % (100.0 * n / total, n, leaf))
    if leaf in chains:
        print("           chain: %s" % chains[leaf])
