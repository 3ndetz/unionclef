"""Detect SHUTTLING: a bot that moves constantly and gets nowhere.

The operator watched recordings and described the bot walking to a block, going off to grab
something, returning, and repeating -- endlessly. deadtime.py cannot see this: the body IS
moving, so a shuttle reads as a healthy run. Worse than a freeze, in his words, because it
consumes the run while looking like work.

Measured per window: net displacement / path walked. Near 1.0 is travel; near 0 is a shuttle.
Usage: shuttle.py <log> [window_samples]
"""
import io, math, re, sys

def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))

raw = io.open(sys.argv[1], encoding="utf-8", errors="ignore").read()
win = int(sys.argv[2]) if len(sys.argv) > 2 else 6
for idx, body in [(m[0], m[2]) for m in
                  [re.split(r"=+ RUN (\d+)/(\d+) =+", raw)[i:i + 3]
                   for i in range(1, len(re.split(r"=+ RUN (\d+)/(\d+) =+", raw)), 3)] if len(m) == 3]:
    pts = [tuple(float(v) for v in m) for m in
           re.findall(r"pos=([-\d.]+),([-\d.]+),([-\d.]+)", body)]
    if len(pts) < win + 1:
        continue
    ratios = []
    for i in range(len(pts) - win):
        seg = pts[i:i + win + 1]
        walked = sum(dist(seg[j], seg[j + 1]) for j in range(len(seg) - 1))
        net = dist(seg[0], seg[-1])
        if walked > 4.0:                      # ignore windows where it barely moved
            ratios.append(net / walked)
    if not ratios:
        print("RUN %s: no travelling window" % idx)
        continue
    shuttling = sum(1 for r in ratios if r < 0.25)
    print("RUN %s: %d/%d travelling windows are SHUTTLES (net/path < 0.25), median ratio %.2f"
          % (idx, shuttling, len(ratios), sorted(ratios)[len(ratios) // 2]))
