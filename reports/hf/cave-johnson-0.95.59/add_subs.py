"""English burned-in subtitles for the 0.95.59 field report, reusing the pitch's aligner.

    python add_subs.py            # out/cj.en.srt, out/cj.en.ass
    python add_subs.py --render   # out/cj-0.95.59.mp4 (master, subtitled) + out/cj-0.95.59-tg.mp4 (< 20 MB)
"""
from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"
spec = importlib.util.spec_from_file_location("pitch_subs", HERE.parents[1] / "pitch" / "add_subtitles.py")
S = importlib.util.module_from_spec(spec)
spec.loader.exec_module(S)


from functools import lru_cache


def phrase_groups(words: list[dict]) -> list[list[dict]]:
    """Choose readable phrase boundaries without leaving a one-word tail."""
    protected = {("bar", "exam"), ("lab", "boys"), ("iron", "tools"),
                 ("diamond", "pickaxe"), ("3ndetz", "labs"), ("artificial", "intelligence"),
                 ("the", "robot"), ("watching", "the"), ("you", "and"), ("and", "i"), ("i", "do"), ("lab", "boys"), ("thankyou", "note")}

    @lru_cache(None)
    def best(start: int) -> tuple[float, tuple[int, ...]]:
        if start == len(words):
            return 0, ()
        options = []
        for end in range(start + 1, min(len(words), start + 11) + 1):
            group = words[start:end]
            text = S.join(group)
            if len(text) > 62:
                break
            span = group[-1]["end"] - group[0]["start"]
            if span > 4.8 and end > start + 1:
                break
            cost = abs(len(text) - 38) / 12 + 2
            cost += max(0, .8 - span) * 12
            cost += 12 if len(group) == 1 else 3 if len(group) == 2 else 0
            if end < len(words):
                following = words[end]
                pause = following["start"] - group[-1]["end"]
                cost += 0 if text.endswith((".", "!", "?")) else 1 if text.endswith((",", ":")) else 4
                cost -= min(max(0, pause), .6) * 4
                if ((S.key(group[-1]["text"]), S.key(following["text"])) in protected
                        or group[-1]["text"].endswith("-")):
                    cost += 30
            rest, boundaries = best(end)
            options.append((cost + rest, (end,) + boundaries))
        return min(options)

    groups, start = [], 0
    for end in best(0)[1]:
        groups.append(words[start:end])
        start = end
    return groups


def display(text: str, line: str) -> str:
    return text.replace("zero point nine five point five nine", "0.95.59")


def dur(p: Path) -> float:
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)],
                                capture_output=True, text=True).stdout.strip())


def build() -> list[dict]:
    lines = json.loads((HERE / "lines.json").read_text(encoding="utf-8"))
    tl = json.loads((HERE / "timeline.json").read_text(encoding="utf-8"))
    words = json.loads((HERE / "words.json").read_text(encoding="utf-8"))
    starts = dict(tl["voice"])
    cues = []
    for ln in lines:
        lid = ln["id"]
        d = dur(HERE / "voice" / "pick" / f"{lid}.wav")
        heard = [[t, min(s, d - 0.1), min(e, d - 0.02)] for t, s, e in words[lid] if t.strip(".,?!")]
        al, _rep = S.align(display(ln["text"], lid), heard, starts[lid])
        groups = phrase_groups(al)
        line_end = starts[lid] + d
        for i, g in enumerate(groups):
            st = g[0]["start"]
            nxt = groups[i + 1][0]["start"] if i + 1 < len(groups) else line_end + 0.4
            en = min(max(g[-1]["end"] + 0.18, st + 0.7), nxt - 0.025)
            cues.append({"line": lid, "start": round(st, 3), "end": round(en, 3), "text": S.join(g).replace("0. 95. 59", "0.95.59")})
    for i, c in enumerate(cues):
        if i + 1 < len(cues):
            c["end"] = min(c["end"], cues[i + 1]["start"] - 0.025)
        assert 0 <= c["start"] < c["end"] <= tl["total"], c
        assert len(c["text"]) <= 62, c
    OUT.mkdir(exist_ok=True)
    (OUT / "cj.en.srt").write_text("\n\n".join(
        f"{i}\n{S.timestamp(c['start'])} --> {S.timestamp(c['end'])}\n{c['text']}" for i, c in enumerate(cues, 1)) + "\n",
        encoding="utf-8")
    header = """[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Inter,48,&H00D2E9F3,&H00D2E9F3,&H00141C1F,&H80141C1F,1,0,0,0,100,100,0,0,3,10,0,2,120,120,24,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    ev = [f"Dialogue: 0,{S.timestamp(c['start'], True)},{S.timestamp(c['end'], True)},Default,,0,0,0,,{c['text']}"
          for c in cues]
    (OUT / "cj.en.ass").write_text(header + "\n".join(ev) + "\n", encoding="utf-8")
    (OUT / "cj-captions.json").write_text(json.dumps(cues, indent=1), encoding="utf-8")
    print(f"{len(cues)} cues")
    return cues


def main():
    build()
    if "--render" not in sys.argv:
        return
    vf = "ass=cj.en.ass:fontsdir=../hf/assets"
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-i", "cj-0.95.59-clean.mp4", "-vf", vf,
                    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-r", "30",
                    "-c:a", "copy", "-movflags", "+faststart", "cj-0.95.59.mp4"], cwd=OUT, check=True)
    # Telegram copy: two-pass to ~3 Mbit/s video -> ~16 MB for 42 s
    common = ["-vf", vf, "-c:v", "libx264", "-preset", "medium", "-b:v", "3000k", "-pix_fmt", "yuv420p", "-r", "30"]
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-i", "cj-0.95.59-clean.mp4"] + common +
                   ["-pass", "1", "-passlogfile", "tg2pass", "-an", "-f", "mp4", "NUL"], cwd=OUT, check=True)
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-i", "cj-0.95.59-clean.mp4"] + common +
                   ["-pass", "2", "-passlogfile", "tg2pass", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
                    "cj-0.95.59-tg.mp4"], cwd=OUT, check=True)
    for f in ("cj-0.95.59.mp4", "cj-0.95.59-tg.mp4"):
        print(f, round((OUT / f).stat().st_size / 1e6, 1), "MB")


if __name__ == "__main__":
    main()
