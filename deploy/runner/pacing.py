"""Find the two points a shuttling bot paces between, and the task live at each end.

shuttle.py says a run contains shuttling; tasksample.py says which tasks are running. Neither
says WHERE. Two of my theories for the residual shuttle (block re-selection, item pursuit)
were retired by counters, so the next honest step is to watch the trajectory itself.

Usage: pacing.py [seconds] [interval]
"""
import collections, json, math, re, subprocess, sys, time

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

secs = int(sys.argv[1]) if len(sys.argv) > 1 else 300
step = int(sys.argv[2]) if len(sys.argv) > 2 else 3
track, deadline = [], time.time() + secs
while time.time() < deadline:
    gs, t = q("gs"), q("task")
    m = re.search(r'"pos"\s*:\s*"?\(?\s*([-\d.]+)[,\s]+([-\d.]+)[,\s]+([-\d.]+)', gs)
    leaf = re.findall(r"\d+(?:\.\d+)*\.\s*(?:Main task:\s*)?<([^>]{0,50})", t)
    if m:
        track.append((tuple(round(float(v), 1) for v in m.groups()),
                      leaf[-1].strip() if leaf else "-"))
    time.sleep(step)

if len(track) < 6:
    print("not enough samples (%d)" % len(track)); sys.exit()

# a pace = returning within 1.5 blocks of somewhere visited 3+ samples ago
paces = collections.Counter()
for i in range(3, len(track)):
    here, task = track[i]
    for j in range(max(0, i - 12), i - 2):
        there, prev = track[j]
        if math.dist(here, there) < 1.5:
            paces[(there, prev, task)] += 1
            break
print("samples %d, returns to a spot seen 3+ back: %d" % (len(track), sum(paces.values())))
for (spot, prev, task), n in paces.most_common(6):
    print("  %3dx  back to %s   task there: %-28s  now: %s" % (n, spot, prev[:28], task[:28]))
