"""Voice lines for the pitch: clone the founder voice through the toolkit's POST /tts.

    python reports/pitch/tts.py lines.json [--only id,id] [--takes N]

lines.json: [{"id": "l01", "text": "..."}]. Writes voice/<id>-t<k>.wav (k = take number) and
transcribes each take back through POST /stt so a mangled take can be spotted and redone.
Reads CODEX_DOCS_URL / CODEX_DOCS_KEY from ~/.claude/codex-docs.env; never prints them.
"""
from __future__ import annotations

import base64
import json
import os
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
VOICE = HERE / "voice"
REF = os.environ.get("PITCH_REF", "C:/Users/jayra/Downloads/Cave_Johnson_fifties_repulsion_intro02.wav")
REF_TEXT = os.environ.get("PITCH_REF_TEXT",
                          "All right, let's get started. This first test involves something the lab boys call repulsion gel.")


def env():
    p = Path.home() / ".claude" / "codex-docs.env"
    for line in p.read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"'))
    return os.environ["CODEX_DOCS_URL"], os.environ["CODEX_DOCS_KEY"]


def post(path, body, timeout=300):
    url, key = env()
    req = urllib.request.Request(url + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=timeout))


def tts(text: str, out: Path):
    ref = "data:audio/wav;base64," + base64.b64encode(Path(REF).read_bytes()).decode()
    r = post("/tts", {"text": text, "ref_audio": ref, "ref_text": REF_TEXT})
    audio = r["audio"].split("base64,", 1)[1]
    out.write_bytes(base64.b64decode(audio))


def stt(path: Path) -> str:
    # The recogniser drops the first word of a clip that starts on speech; pad 0.8 s of silence.
    import subprocess, tempfile
    pad = Path(tempfile.gettempdir()) / f"pad_{path.stem}.wav"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-t", "0.8", "-i", "anullsrc=r=24000:cl=mono",
                    "-i", str(path), "-filter_complex", "[0][1]concat=n=2:v=0:a=1", str(pad)], check=True)
    b = base64.b64encode(pad.read_bytes()).decode()
    r = post("/stt", {"audio": "data:audio/wav;base64," + b, "num_speakers": 1})
    return " ".join(e.get("text", "") for e in r.get("events", []) if e.get("event") == "final").strip()


def main():
    lines = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    only = None
    takes = 1
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
    if "--takes" in sys.argv:
        takes = int(sys.argv[sys.argv.index("--takes") + 1])
    VOICE.mkdir(exist_ok=True)
    if "--check" in sys.argv:  # re-transcribe existing takes only
        for f in sorted(VOICE.glob("*.wav")):
            if not only or f.stem.split("-")[0] in only:
                print(f"{f.stem}: {stt(f)}", flush=True)
        return
    for ln in lines:
        if only and ln["id"] not in only:
            continue
        first = int(sys.argv[sys.argv.index("--from") + 1]) if "--from" in sys.argv else 1
        for k in range(first, first + takes):
            out = VOICE / f"{ln['id']}-t{k}.wav"
            try:
                tts(ln["text"], out)
                heard = stt(out)
            except Exception as e:  # keep going; a failed line is listed and redone
                print(f"{ln['id']} t{k}: FAILED {e}")
                continue
            print(f"{ln['id']} t{k}: {heard}", flush=True)


if __name__ == "__main__":
    main()
