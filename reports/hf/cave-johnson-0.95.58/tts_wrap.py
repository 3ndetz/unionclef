"""Thin wrapper around reports/pitch/tts.py for this film (the original is not edited).

    python tts_wrap.py gen [--only id,id] [--takes N] [--from K]   # voice/<id>-t<k>.wav + STT check
    python tts_wrap.py words <id>=<take> ...                       # voice/pick/<id>.wav + words.json

Same voice reference and toolkit as the pitch; secrets are read by tts.py and never printed.
"""
from __future__ import annotations

import base64
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PITCH = HERE.parents[1] / "pitch" / "tts.py"
spec = importlib.util.spec_from_file_location("pitch_tts", PITCH)
T = importlib.util.module_from_spec(spec)
spec.loader.exec_module(T)
T.VOICE = HERE / "voice"


def stt_words(path: Path) -> list:
    """Word timings (s, relative to the take) from POST /stt; 0.8 s pad removed."""
    pad = Path(tempfile.gettempdir()) / f"padw_{path.stem}.wav"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-t", "0.8", "-i", "anullsrc=r=24000:cl=mono",
                    "-i", str(path), "-filter_complex", "[0]aresample=24000[a];[1]aresample=24000,aformat=channel_layouts=mono[b];[a][b]concat=n=2:v=0:a=1",
                    str(pad)], check=True)
    b = base64.b64encode(pad.read_bytes()).decode()
    r = T.post("/stt", {"audio": "data:audio/wav;base64," + b, "num_speakers": 1})
    out = []
    for e in r.get("events", []):
        if e.get("event") != "final":
            continue
        for wd in e.get("words", []):
            out.append([wd["text"], round(wd["offset_ms"] / 1000 - 0.8, 3), round(wd["offset_end_ms"] / 1000 - 0.8, 3)])
    return out, r


def main():
    cmd = sys.argv[1]
    lines_path = str(HERE / "lines.json")
    if cmd == "gen":
        sys.argv = [sys.argv[0], lines_path] + sys.argv[2:]
        T.main()
    elif cmd == "probe":
        _w, r = stt_words(Path(sys.argv[2]))
        ev = [e for e in r.get("events", []) if e.get("event") == "final"]
        print(json.dumps(ev[:1], indent=1)[:1500])
    elif cmd == "words":
        pick = HERE / "voice" / "pick"
        pick.mkdir(parents=True, exist_ok=True)
        wf = HERE / "words.json"
        words = json.loads(wf.read_text()) if wf.exists() else {}
        for arg in sys.argv[2:]:
            lid, take = arg.split("=")
            src = HERE / "voice" / f"{lid}-t{take}.wav"
            shutil.copy(src, pick / f"{lid}.wav")
            words[lid], _ = stt_words(pick / f"{lid}.wav")
            print(lid, " ".join(w[0] for w in words[lid]))
        wf.write_text(json.dumps(words, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
