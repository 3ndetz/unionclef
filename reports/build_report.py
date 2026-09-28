"""Build a video report from a storyboard: cut the clips, compose them in HyperFrames, render.

    python reports/build_report.py reports/stories/<name>.json [--send]

The storyboard is a JSON file:

    {
      "name": "0.95.47",                      # output file name: reports/out/<name>.mp4
      "title": "unionclef 0.95.47",
      "subtitle": "one line: what this release is about",
      "segments": [
        {"kind": "clip", "src": "path/to/recording.mp4", "from": 285, "to": 330, "speed": 4,
         "title": "short event name", "caption": "what happens, in one line"},
        {"kind": "stat", "title": "what was measured", "before": "20 -> 1.7 hp",
         "after": "20 hp all night", "note": "where it was measured"},
        {"kind": "text", "title": "a heading", "lines": ["point one", "point two"]}
      ]
    }

Clips are pre-cut with ffmpeg (trim, speed-up, scale to 1920x1080 letterboxed, no audio), then
laid out one after another with a title card first and an end card last; every clip gets an
animated lower third naming the event. See docs/VIDEO_REPORTS.md for what goes into a report.
"""
from __future__ import annotations

import html
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
HF = ROOT / "hf"
VIDEOS = HF / "videos"
OUT = ROOT / "out"
HF_CLI = "hyperframes@0.8.30"

TITLE_S, TEXT_S, STAT_S, END_S = 3.5, 5.0, 4.5, 3.0
ACCENT = "#3fd0c9"


def cut(src: str, start: float, end: float, speed: float, dst: Path) -> float:
    """Trim, speed up and letterbox one clip; return its length on the timeline in seconds."""
    vf = (f"setpts=PTS/{speed},scale=1920:1080:force_original_aspect_ratio=decrease,"
          f"pad=1920:1080:(ow-iw)/2:(oh-ih)/2:color=0x0b0f14,fps=30")
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", str(start), "-t", str(end - start), "-i", src,
           "-vf", vf, "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
           "-pix_fmt", "yuv420p", str(dst)]
    subprocess.run(cmd, check=True)
    return round((end - start) / speed, 3)


def card(cid: str, start: float, dur: float, inner: str) -> str:
    return (f'<div id="{cid}" class="clip card" data-start="{start:.3f}" data-duration="{dur:.3f}">'
            f"{inner}</div>")


def build(story: dict) -> tuple[str, float]:
    esc = html.escape
    parts, tweens = [], []
    t = 0.0
    VIDEOS.mkdir(parents=True, exist_ok=True)
    tag = f'<div class="tag">unionclef · {esc(story.get("name", ""))}</div>'

    parts.append(card("title", t, TITLE_S,
                      f'<div class="center"><div id="t-h" class="h1">{esc(story["title"])}</div>'
                      f'<div id="t-s" class="sub">{esc(story.get("subtitle", ""))}</div></div>'))
    tweens.append(f"tl.fromTo('#t-h',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.7,ease:'power3.out'}},{t + 0.2:.3f});")
    tweens.append(f"tl.fromTo('#t-s',{{opacity:0}},{{opacity:1,duration:0.6}},{t + 0.8:.3f});")
    t += TITLE_S

    for i, seg in enumerate(story["segments"]):
        kind = seg["kind"]
        if kind == "clip":
            name = f"seg{i:02d}.mp4"
            dur = cut(seg["src"], float(seg["from"]), float(seg["to"]), float(seg.get("speed", 1)),
                      VIDEOS / name)
            speed = float(seg.get("speed", 1))
            badge = f'<div class="speed">x{speed:g}</div>' if speed != 1 else ""
            parts.append(
                f'<video id="v{i}" class="clip vid" src="videos/{name}" muted playsinline '
                f'data-start="{t:.3f}" data-duration="{dur:.3f}"></video>')
            parts.append(card(f"lt{i}", t, dur,
                              f'{tag}{badge}<div id="lt{i}-box" class="lower"><div class="lt-title">'
                              f'{esc(seg.get("title", ""))}</div><div class="lt-cap">'
                              f'{esc(seg.get("caption", ""))}</div></div>'))
            tweens.append(f"tl.fromTo('#lt{i}-box',{{opacity:0,x:-60}},{{opacity:1,x:0,duration:0.6,ease:'power3.out'}},{t + 0.3:.3f});")
            tweens.append(f"tl.to('#lt{i}-box',{{opacity:0,duration:0.4}},{t + max(dur - 0.5, 0.8):.3f});")
            t += dur
        elif kind == "stat":
            note = f'<div class="note">{esc(seg.get("note", ""))}</div>' if seg.get("note") else ""
            parts.append(card(f"st{i}", t, STAT_S,
                              f'{tag}<div class="center"><div class="h2">{esc(seg["title"])}</div>'
                              f'<div class="cmp"><div id="st{i}-b" class="was"><span>было</span>'
                              f'{esc(seg["before"])}</div><div class="arrow">→</div>'
                              f'<div id="st{i}-a" class="now"><span>стало</span>{esc(seg["after"])}</div>'
                              f"</div>{note}</div>"))
            tweens.append(f"tl.fromTo('#st{i}-b',{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.5}},{t + 0.3:.3f});")
            tweens.append(f"tl.fromTo('#st{i}-a',{{opacity:0,scale:0.9}},{{opacity:1,scale:1,duration:0.6,ease:'back.out(1.6)'}},{t + 1.1:.3f});")
            t += STAT_S
        elif kind == "text":
            items = "".join(f'<li id="tx{i}-{k}">{esc(x)}</li>' for k, x in enumerate(seg.get("lines", [])))
            parts.append(card(f"tx{i}", t, TEXT_S,
                              f'{tag}<div class="center left"><div class="h2">{esc(seg["title"])}</div>'
                              f"<ul>{items}</ul></div>"))
            for k in range(len(seg.get("lines", []))):
                tweens.append(f"tl.fromTo('#tx{i}-{k}',{{opacity:0,x:-30}},{{opacity:1,x:0,duration:0.45}},{t + 0.4 + 0.35 * k:.3f});")
            t += TEXT_S
        else:
            raise ValueError(f"unknown segment kind: {kind}")

    parts.append(card("end", t, END_S,
                      f'<div class="center"><div id="e-h" class="h1">{esc(story["title"])}</div>'
                      f'<div class="sub">github.com/3ndetz/unionclef</div></div>'))
    tweens.append(f"tl.fromTo('#e-h',{{opacity:0}},{{opacity:1,duration:0.6}},{t + 0.2:.3f});")
    t += END_S
    return page(t, "\n      ".join(parts), "\n      ".join(tweens)), t


def page(total: float, body: str, tweens: str) -> str:
    return f"""<!doctype html>
<html lang="ru">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=1920, height=1080" />
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ width: 1920px; height: 1080px; overflow: hidden; background: #0b0f14; }}
      body {{ font-family: "Segoe UI", "Inter", Arial, sans-serif; color: #e8eef2; }}
      .clip {{ position: absolute; inset: 0; }}
      .vid {{ width: 1920px; height: 1080px; object-fit: contain; background: #0b0f14; }}
      .card {{ pointer-events: none; }}
      .center {{ position: absolute; inset: 0; display: flex; flex-direction: column;
                 align-items: center; justify-content: center; gap: 28px; text-align: center; }}
      .center.left {{ align-items: flex-start; padding-left: 220px; text-align: left; }}
      .h1 {{ font-size: 96px; font-weight: 800; letter-spacing: 2px; }}
      .h2 {{ font-size: 64px; font-weight: 700; color: {ACCENT}; }}
      .sub {{ font-size: 40px; color: #9fb2bf; max-width: 1500px; }}
      .tag {{ position: absolute; bottom: 40px; right: 44px; font-size: 26px; letter-spacing: 3px;
              text-transform: uppercase; color: {ACCENT}; background: rgba(11,15,20,0.72);
              padding: 8px 16px; border-left: 4px solid {ACCENT}; }}
      .speed {{ position: absolute; top: 36px; right: 44px; font-size: 30px; font-weight: 700;
                color: #0b0f14; background: {ACCENT}; padding: 6px 16px; border-radius: 6px; }}
      .lower {{ position: absolute; left: 44px; bottom: 60px; max-width: 1300px;
                background: rgba(11,15,20,0.84); border-left: 8px solid {ACCENT};
                padding: 22px 32px; }}
      .lt-title {{ font-size: 48px; font-weight: 800; }}
      .lt-cap {{ font-size: 32px; color: #b9c7d0; margin-top: 8px; }}
      .cmp {{ display: flex; align-items: center; gap: 56px; font-size: 64px; font-weight: 700; }}
      .cmp span {{ display: block; font-size: 26px; letter-spacing: 3px; text-transform: uppercase;
                   color: #7d8f9b; margin-bottom: 10px; font-weight: 600; }}
      .was {{ color: #ff7a7a; }}
      .now {{ color: #7dffb0; }}
      .arrow {{ color: #7d8f9b; }}
      .note {{ font-size: 28px; color: #7d8f9b; }}
      ul {{ list-style: none; display: flex; flex-direction: column; gap: 22px; }}
      li {{ font-size: 44px; padding-left: 34px; border-left: 6px solid {ACCENT}; }}
    </style>
  </head>
  <body>
    <div id="root" data-composition-id="main" data-start="0" data-duration="{total:.3f}"
         data-width="1920" data-height="1080">
      {body}
    </div>
    <script>
      window.__timelines = window.__timelines || {{}};
      const tl = gsap.timeline({{ paused: true }});
      {tweens}
      window.__timelines["main"] = tl;
    </script>
  </body>
</html>
"""


def main() -> int:
    story = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    if VIDEOS.exists():
        shutil.rmtree(VIDEOS)
    doc, total = build(story)
    (HF / "index.html").write_text(doc, encoding="utf-8")
    print(f"composition: {total:.1f} s, {len(story['segments'])} segments")
    npx = shutil.which("npx") or "npx"
    subprocess.run([npx, "--yes", HF_CLI, "check"], cwd=HF, check=False)
    OUT.mkdir(exist_ok=True)
    out = OUT / f"{story.get('name', 'report')}.mp4"
    r = subprocess.run([npx, "--yes", HF_CLI, "render", "--output", str(out)], cwd=HF)
    if r.returncode != 0 or not out.exists():
        print("render failed")
        return 1
    print(f"rendered: {out} ({out.stat().st_size // 1024} KB)")
    if "--send" in sys.argv:
        sys.path.insert(0, str(ROOT.parent / "deploy" / "runner"))
        from tg_speedup import send
        ok, info = send(str(out), story.get("caption", story["title"]))
        print("TG:", ok, info)
    return 0


if __name__ == "__main__":
    sys.exit(main())
