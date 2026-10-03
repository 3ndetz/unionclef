"""Caption the finished investor pitch using its saved voice timings.

    python reports/pitch/add_subtitles.py
    python reports/pitch/add_subtitles.py --render

Writes English SRT/ASS files and a checked cue manifest under out/. The render
burns captions into the existing master, copies its audio, and stays below the
Telegram upload limit. It does not rebuild the HyperFrames picture or voice.
"""
from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
from functools import lru_cache
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "deploy" / "runner"))
from uctest import process as subprocess

OUT = HERE / "out"


def key(text: str) -> str:
    value = re.sub(r"[^a-z0-9']", "", text.lower())
    return {"indets": "3ndetz", "endets": "3ndetz", "cleff": "clef"}.get(value, value)


def display_script(text: str, line: str) -> str:
    text = (text.replace("En-detz", "3ndetz").replace("Union Clef", "unionclef")
            .replace("M C P", "MCP").replace("A I", "AI"))
    # The chosen take starts at "The numbers" and says "one hundred thirty-one".
    if line == "l11":
        text = text.removeprefix("Now, ")
        text = text[0].upper() + text[1:]
    if line == "l12":
        text = text.replace("A hundred and thirty-one", "One hundred thirty-one")
    return text


def align(text: str, heard: list, offset: float) -> tuple[list[dict], list[dict]]:
    # Split hyphenated words for alignment, then keep their written punctuation.
    written = re.findall(r"[A-Za-z0-9]+(?:'[A-Za-z]+)?[^\w\s]*", text)
    spoken = []
    for word, start, end in heard:
        pieces = re.findall(r"[A-Za-z0-9]+(?:'[A-Za-z]+)?", word)
        for i, piece in enumerate(pieces):
            span = (end - start) / len(pieces)
            spoken.append((piece, start + i * span, start + (i + 1) * span))
    matcher = difflib.SequenceMatcher(None, [key(x) for x in written],
                                     [key(x[0]) for x in spoken], autojunk=False)
    times: list[tuple[float, float] | None] = [None] * len(written)
    repairs = []
    for op, a, b, c, d in matcher.get_opcodes():
        if op == "equal":
            for i, j in zip(range(a, b), range(c, d)):
                times[i] = spoken[j][1:]
        elif b > a:
            # Use the actual ASR interval for substitutions, not uniform line timing.
            start = spoken[c][1] if d > c else (spoken[c - 1][2] if c else 0)
            end = spoken[d - 1][2] if d > c else (spoken[c][1] if c < len(spoken) else start + .15)
            end = max(end, start + .04 * (b - a))
            weights = [max(1, len(key(word))) for word in written[a:b]]
            cursor = start
            for i, weight in zip(range(a, b), weights):
                next_time = cursor + (end - start) * weight / sum(weights)
                times[i] = (cursor, next_time)
                cursor = next_time
            repairs.append({"written": " ".join(written[a:b]),
                            "heard": " ".join(x[0] for x in spoken[c:d]),
                            "start": start, "end": end})
    return [{"text": word, "start": round(offset + time[0], 3),
             "end": round(offset + time[1], 3)}
            for word, time in zip(written, times)], repairs


def join(words: list[dict]) -> str:
    text = " ".join(x["text"] for x in words)
    text = re.sub(r"- +", "-", text)
    return text.replace("Union Clef", "unionclef")


def timestamp(seconds: float, ass: bool = False) -> str:
    units = 100 if ass else 1000
    value = round(seconds * units)
    hour, value = divmod(value, 3600 * units)
    minute, value = divmod(value, 60 * units)
    sec, frac = divmod(value, units)
    return (f"{hour}:{minute:02}:{sec:02}.{frac:02}" if ass else
            f"{hour:02}:{minute:02}:{sec:02},{frac:03}")


def phrase_groups(words: list[dict]) -> list[list[dict]]:
    """Choose readable phrase boundaries without leaving a one-word tail."""
    protected = {("bar", "exam"), ("lab", "boys"), ("iron", "tools"),
                 ("diamond", "pickaxe"), ("3ndetz", "labs"), ("artificial", "intelligence")}

    @lru_cache(None)
    def best(start: int) -> tuple[float, tuple[int, ...]]:
        if start == len(words):
            return 0, ()
        options = []
        for end in range(start + 1, min(len(words), start + 11) + 1):
            group = words[start:end]
            text = join(group)
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
                if ((key(group[-1]["text"]), key(following["text"])) in protected
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


def build() -> list[dict]:
    scripts = json.loads((HERE / "lines.json").read_text(encoding="utf-8"))
    timings = json.loads((HERE / "timeline.json").read_text(encoding="utf-8"))
    transcript = json.loads((HERE / "words.json").read_text(encoding="utf-8"))
    starts = dict(timings["voice"])
    cues, repairs = [], {}
    for line in scripts:
        line_id = line["id"]
        words, repairs[line_id] = align(display_script(line["text"], line_id),
                                       transcript[line_id], starts[line_id])
        groups = phrase_groups(words)
        for i, group in enumerate(groups):
            start = group[0]["start"]
            next_start = groups[i + 1][0]["start"] if i + 1 < len(groups) else timings["total"]
            end = min(max(group[-1]["end"] + .18, start + .7), next_start - .025)
            cues.append({"line": line_id, "start": start, "end": round(end, 3),
                         "text": join(group)})
    for i, cue in enumerate(cues):
        if i + 1 < len(cues):
            cue["end"] = min(cue["end"], cues[i + 1]["start"] - .025)
        if not (0 <= cue["start"] < cue["end"] <= timings["total"]):
            raise ValueError(f"Invalid cue: {cue}")
        if len(cue["text"]) > 62 or "{" in cue["text"] or "\\" in cue["text"]:
            raise ValueError(f"Unsafe or overlong cue: {cue}")
    OUT.mkdir(exist_ok=True)
    (OUT / "pitch.en.srt").write_text("\n\n".join(
        f"{i}\n{timestamp(c['start'])} --> {timestamp(c['end'])}\n{c['text']}"
        for i, c in enumerate(cues, 1)) + "\n", encoding="utf-8")
    header = """[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Inter,46,&H00D2E9F3,&H00D2E9F3,&H00141C1F,&H80141C1F,1,0,0,0,100,100,0,0,3,10,0,2,120,120,22,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    events = []
    for c in cues:
        # Keep the tool chips/stamp and the chart's month labels unobscured.
        # Closing credits appear after speech, so that card can use the footer.
        position = ({"l04": "{\\an8\\pos(960,60)}",
                     "l11": "{\\an8\\pos(700,350)}"}.get(c["line"], ""))
        events.append(f"Dialogue: 0,{timestamp(c['start'], True)},{timestamp(c['end'], True)},"
                      f"Default,,0,0,0,,{position}{c['text']}")
    (OUT / "pitch.en.ass").write_text(header + "\n".join(events) + "\n", encoding="utf-8")
    (OUT / "pitch-captions.json").write_text(json.dumps(
        {"source": "pitch.mp4", "cues": cues, "alignment_repairs": repairs}, indent=2), encoding="utf-8")
    print(f"Wrote {len(cues)} checked cues; speech {cues[0]['start']:.2f}-{cues[-1]['end']:.2f}s")
    return cues


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--render", action="store_true")
    args = parser.parse_args()
    build()
    if args.render:
        subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", "-i", "pitch.mp4",
                        "-vf", "ass=pitch.en.ass:fontsdir=../hf/assets", "-map", "0:v:0",
                        "-map", "0:a:0", "-c:v", "libx264", "-preset", "medium", "-crf", "25",
                        "-maxrate", "1900k", "-bufsize", "3800k", "-pix_fmt", "yuv420p",
                        "-c:a", "copy", "-movflags", "+faststart", "pitch-subtitled-tg.mp4"],
                       cwd=OUT, check=True)
        output = OUT / "pitch-subtitled-tg.mp4"
        if output.stat().st_size >= 49_000_000:
            raise ValueError("Captioned video exceeds the Telegram upload budget")
        print(f"Rendered {output} ({output.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
