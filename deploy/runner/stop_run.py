"""Stop every running gamer_smoke and keep its recording.

    python deploy/runner/stop_run.py [footage-name]

A run started with `nohup python3 gamer_smoke.py ... &` is three Windows processes (nohup, the
store python shim, the real interpreter); `pkill` does not exist in Git Bash and `wmic` is gone
from current Windows, so earlier stops killed nothing -- on 2026-09-28 the full49 and full50
runners were still polling the client an hour later, alongside full51. This asks Windows for
every process whose command line mentions gamer_smoke, stops them, then finalizes the client's
recording (SIGINT to its ffmpeg) and copies it to reports/footage/<footage-name>.mp4.
"""
from __future__ import annotations

from uctest import process as subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLIENT = "uctest-mc-tester1"


def stop_runners() -> list[str]:
    ps = ("Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'gamer_smoke' "
          "-and $_.CommandLine -notmatch 'stop_run' } | ForEach-Object { "
          "Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; $_.ProcessId }")
    out = subprocess.run(["powershell.exe", "-NoProfile", "-Command", ps], capture_output=True, text=True)
    return out.stdout.split()


def main() -> int:
    killed = stop_runners()
    print(f"stopped {len(killed)} process(es): {' '.join(killed) or '-'}")
    sys.path.insert(0, str(HERE))
    from uctest.recording import finish_recording
    finish_recording(CLIENT)
    time.sleep(5)
    if len(sys.argv) > 1:
        dst = HERE.parent.parent / "reports" / "footage" / f"{sys.argv[1]}.mp4"
        dst.parent.mkdir(parents=True, exist_ok=True)
        r = subprocess.run(["docker", "cp", f"{CLIENT}:/mc-data/rec_gamer.mp4", str(dst)])
        if r.returncode == 0:
            subprocess.run(["docker", "exec", CLIENT, "rm", "-f", "/mc-data/rec_gamer.mp4"])
            print(f"recording kept: {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
