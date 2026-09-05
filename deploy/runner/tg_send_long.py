#!/usr/bin/env python3
"""Send a long report to Telegram as several messages.

tg_report.py posts a file as ONE message, and Telegram rejects anything over 4096 characters with
a bare HTTP 400 -- which reads like an auth or network failure and is neither. A full status report
is routinely longer than that, so split it here rather than writing shorter reports.

Splits on blank lines so a paragraph is never cut in half, and numbers the parts so the operator
can tell at a glance whether one went missing.

    python deploy/runner/tg_send_long.py <file> [header]
"""
import os
import subprocess
import sys
import tempfile

LIMIT = 3800          # 4096 minus room for the "(part n/m)" header
REPORT = r"C:/repos/pet/mineswarm/game/cristalix/tg_report.py"


def _split_long(para):
    """A paragraph with no blank line inside it that still exceeds LIMIT on its own (a wide
    table, a single unbroken log dump) -- split on line boundaries, hard-cutting any line
    that alone exceeds LIMIT. Without this, chunks() below never breaks such a paragraph and
    hands Telegram a single message over the 4096-char cap -- the exact HTTP 400 this tool
    exists to route around, just one level down."""
    pieces, cur = [], ""
    for line in para.splitlines(keepends=True) or [para]:
        while len(line) > LIMIT:
            pieces.append(line[:LIMIT])
            line = line[LIMIT:]
        candidate = cur + line
        if len(candidate) > LIMIT and cur:
            pieces.append(cur)
            cur = line
        else:
            cur = candidate
    if cur:
        pieces.append(cur)
    return pieces


def chunks(text):
    out, cur = [], ""
    for para in text.split("\n\n"):
        for piece in ([para] if len(para) <= LIMIT else _split_long(para)):
            candidate = (cur + "\n\n" + piece) if cur else piece
            if len(candidate) > LIMIT and cur:
                out.append(cur)
                cur = piece
            else:
                cur = candidate
    if cur:
        out.append(cur)
    return out


def main():
    path = sys.argv[1]
    header = sys.argv[2] if len(sys.argv) > 2 else ""
    with open(path, encoding="utf-8") as fh:
        parts = chunks(fh.read())
    for i, part in enumerate(parts, 1):
        tag = f"{header} (часть {i}/{len(parts)})\n\n" if len(parts) > 1 else ""
        tmp = os.path.join(tempfile.gettempdir(), f"tg_part_{i}.txt")
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write(tag + part)
        res = subprocess.run([sys.executable, REPORT, tmp], capture_output=True, text=True)
        print(f"part {i}/{len(parts)} ({len(part)} chars): {res.stdout.strip() or res.stderr.strip()}")
        if res.returncode != 0:
            sys.exit(res.returncode)


if __name__ == "__main__":
    main()
