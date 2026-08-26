import re, sys, io
# Honest metric the operator asked for: how much of each run the bot was ALIVE.
# A sample is "dead" when the position has not moved since the previous sample.
raw = io.open(sys.argv[1], encoding="utf-8", errors="ignore").read()
runs = re.split(r"=+ RUN (\d+)/(\d+) =+", raw)
for i in range(1, len(runs), 3):
    idx, body = runs[i], runs[i + 2]
    samples = re.findall(r"t=(\d+)s .*?pos=([-\d.]+),([-\d.]+),([-\d.]+).*?items=(\d+)", body)
    if len(samples) < 2:
        continue
    dead = alive = 0
    last = None
    for t, x, y, z, it in samples:
        cur = (round(float(x), 1), round(float(y), 1), round(float(z), 1), it)
        if last is not None:
            if cur[:3] == last[:3] and cur[3] == last[3]:
                dead += 1
            else:
                alive += 1
        last = cur
    tot = dead + alive
    span = int(samples[-1][0])
    print("RUN %s: %d/%d samples dead (%.0f%%), last t=%ds, final items=%s"
          % (idx, dead, tot, 100.0 * dead / tot, span, samples[-1][4]))
