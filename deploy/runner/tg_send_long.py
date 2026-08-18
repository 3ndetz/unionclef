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


def chunks(text):
    out, cur = [], ""
    for para in text.split("\n\n"):
        candidate = (cur + "\n\n" + para) if cur else para
        if len(candidate) > LIMIT and cur:
            out.append(cur)
            cur = para
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
