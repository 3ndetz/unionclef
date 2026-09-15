"""Shared finalisation for suite and survival recordings."""
import subprocess


def finish_recording(container):
    """Stop only suite recorders and wait for MP4 finalisation; images need no procps."""
    code = r"""
import os, signal, time
from pathlib import Path
pending = []
for proc in Path('/proc').iterdir():
    if not proc.name.isdigit():
        continue
    try:
        args = (proc / 'cmdline').read_bytes().split(b'\0')
        if (args and os.path.basename(args[0]) == b'ffmpeg'
                and any(a.startswith(b'/mc-data/rec_') and a.endswith(b'.mp4') for a in args)):
            os.kill(int(proc.name), signal.SIGINT)
            pending.append(proc)
    except (FileNotFoundError, ProcessLookupError):
        pass
def alive(proc):
    try:
        return bool((proc / 'cmdline').read_bytes())
    except FileNotFoundError:
        return False
end = time.monotonic() + 30
while pending:
    pending = [p for p in pending if alive(p)]
    if not pending:
        break
    if time.monotonic() >= end:
        raise RuntimeError('suite recorder did not finish; refusing to copy a partial MP4')
    time.sleep(0.2)
"""
    subprocess.run(["docker", "exec", container, "python3", "-c", code],
                   check=True, timeout=40, capture_output=True)


