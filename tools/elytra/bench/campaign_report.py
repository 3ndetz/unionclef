"""Sum up the blocks of a campaign (campaign.py) per variant: maze pass rate with a Wilson interval,
landing distance and health by leg with bootstrap intervals of the median, and Fisher's exact test of
the maze pass rate between the first two variants.

    python campaign_report.py <campaign>      -> out/campaign/<campaign>/report.md
"""
import json
import math
import random
import sys
from collections import defaultdict

import bench


def wilson(k, n, z=1.96):
    if n == 0:
        return 0.0, 0.0
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, c - h), min(1.0, c + h)


def median(xs):
    xs = sorted(xs)
    n = len(xs)
    return (xs[n // 2] + xs[(n - 1) // 2]) / 2


def boot_median(xs, reps=2000, seed=1):
    rnd = random.Random(seed)
    ms = sorted(median([rnd.choice(xs) for _ in xs]) for _ in range(reps))
    return ms[int(reps * 0.025)], ms[int(reps * 0.975)]


def fisher(k1, n1, k2, n2):
    """Two-sided Fisher's exact test of k1/n1 against k2/n2."""
    total, kk = n1 + n2, k1 + k2

    def p(a):
        return math.comb(kk, a) * math.comb(total - kk, n1 - a) / math.comb(total, n1)

    p0 = p(k1)
    return min(1.0, sum(p(a) for a in range(max(0, kk - n2), min(kk, n1) + 1) if p(a) <= p0 * (1 + 1e-9)))


def runs_test(seq):
    """Wald-Wolfowitz runs test on a pass/fail sequence: (runs, expected runs, two-sided p). Fewer runs
    than expected means outcomes come in clusters: the flights are not independent."""
    n1 = sum(seq)
    n2 = len(seq) - n1
    if n1 == 0 or n2 == 0:
        return len(seq) and 1, None, None
    runs = 1 + sum(1 for a, b in zip(seq, seq[1:]) if a != b)
    n = n1 + n2
    mu = 2 * n1 * n2 / n + 1
    var = 2 * n1 * n2 * (2 * n1 * n2 - n) / (n * n * (n - 1))
    z = (runs - mu) / math.sqrt(var) if var > 0 else 0
    return runs, mu, math.erfc(abs(z) / math.sqrt(2))


def homogeneity(counts):
    """Chi-square test that blocks of one variant share one pass rate: (chi2, df, p)."""
    from scipy.stats import chi2_contingency
    table = [[k, n - k] for k, n in counts if n]
    if len(table) < 2 or all(r[0] == 0 for r in table) or all(r[1] == 0 for r in table):
        return None
    c, p, df, _ = chi2_contingency(table, correction=False)
    return c, df, p


def n_for(p1, p2, alpha=0.05, power=0.8):
    """Flights per variant to tell pass rates p1 and p2 apart (two-sided, normal approximation)."""
    from scipy.stats import norm
    if p1 == p2:
        return None
    za, zb = norm.ppf(1 - alpha / 2), norm.ppf(power)
    pb = (p1 + p2) / 2
    num = (za * math.sqrt(2 * pb * (1 - pb)) + zb * math.sqrt(p1 * (1 - p1) + p2 * (1 - p2))) ** 2
    return math.ceil(num / (p1 - p2) ** 2)


def main():
    root = bench.OUT / "campaign" / sys.argv[1]
    maze = defaultdict(lambda: [0, 0])
    land = defaultdict(list)  # (variant, mode, leg) -> [(off, hp lost)]
    blocks, order = [], []
    seqs, per_block = defaultdict(list), defaultdict(list)  # variant -> outcomes in order; [(k, n)] per block
    for b in sorted(d for d in root.iterdir() if d.is_dir()):
        m = json.loads((b / "manifest.json").read_text(encoding="utf-8")) if (b / "manifest.json").exists() else None
        if m is None:
            continue  # a block still running, or one that died before its manifest
        v = m["variant"]
        if v not in order:
            order.append(v)
        blocks.append(f"| {b.name} | {v} | {m['jarSha256'][:12]} | {m['commit'][:8]} | {m['patchSha256'][:12]} | {m['started']} |")
        if (b / "maze.json").exists():
            rows = json.loads((b / "maze.json").read_text(encoding="utf-8"))
            maze[v][0] += sum(1 for r in rows if r["ok"])
            maze[v][1] += len(rows)
            seqs[v] += [bool(r["ok"]) for r in rows]
            per_block[v].append((sum(1 for r in rows if r["ok"]), len(rows)))
        for part in ("land-flat", "land-walls"):
            if (b / f"{part}.json").exists():
                for r in json.loads((b / f"{part}.json").read_text(encoding="utf-8")):
                    land[(v, r["mode"], str(r["leg"]))].append((r["off"], r["hpLost"]))
    out = [f"# Campaign {sys.argv[1]}", "", "| block | variant | jar | commit | patch | started |", "|---|---|---|---|---|---|", *blocks, ""]
    out += ["## Compact maze", "", "| variant | through | rate | 95% CI |", "|---|---|---|---|"]
    for v in order:
        k, n = maze[v]
        lo, hi = wilson(k, n)
        out.append(f"| {v} | {k}/{n} | {k / n if n else 0:.2f} | {lo:.2f}-{hi:.2f} |")
    if len(order) >= 2 and all(maze[v][1] for v in order[:2]):
        (k1, n1), (k2, n2) = maze[order[0]], maze[order[1]]
        out.append(f"\nFisher's exact test, {order[0]} vs {order[1]}: p = {fisher(k1, n1, k2, n2):.3f}")
    out += ["", "## Is the bench sound? (maze)", "",
            "Runs test: fewer runs of pass/fail than expected means failures cluster (flights not independent). "
            "Homogeneity: blocks of one variant should share one pass rate.", "",
            "| variant | blocks | runs (expected) | runs p | block rates | homogeneity chi2 (df) | p |", "|---|---|---|---|---|---|---|"]
    for v in order:
        if not seqs[v]:
            continue
        r, mu, pr = runs_test(seqs[v])
        h = homogeneity(per_block[v])
        rates = " ".join(f"{k}/{n}" for k, n in per_block[v])
        out.append(f"| {v} | {len(per_block[v])} | {r} ({mu:.1f}) | {pr:.3f} | {rates} | "
                   + (f"{h[0]:.2f} ({h[1]}) | {h[2]:.3f} |" if h else "- | - |") if mu else
                   f"| {v} | {len(per_block[v])} | all alike | - | {rates} | - | - |")
    if len(order) >= 2 and all(maze[v][1] for v in order[:2]):
        p1, p2 = (maze[v][0] / maze[v][1] for v in order[:2])
        need = n_for(p1, p2)
        if need:
            out.append(f"\nFlights per variant to tell {p1:.2f} from {p2:.2f} (alpha 0.05, power 0.8): {need}")
    out += ["", "## Landings: distance from the spot at rest", "",
            "| variant | mode | leg | n | median off | 95% CI | hurt | hp lost |", "|---|---|---|---|---|---|---|---|"]
    for key in sorted(land, key=lambda k: (k[2], k[1], order.index(k[0]))):
        offs = [o for o, _ in land[key]]
        hurt = [h for _, h in land[key] if h > 0]
        lo, hi = boot_median(offs)
        out.append(f"| {key[0]} | {key[1]} | {key[2]} | {len(offs)} | {median(offs):.1f} | {lo:.1f}-{hi:.1f} | "
                   f"{len(hurt)}/{len(offs)} | {sum(hurt):.1f} |")
    (root / "report.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    print("\n".join(out))


if __name__ == "__main__":
    main()
