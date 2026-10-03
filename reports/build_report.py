"""Build a video report from a storyboard: cut the clips, compose them in HyperFrames, render.

    python reports/build_report.py reports/stories/<name>.json [--send]

Everything in a report is English (the project language is ONLY ENGLISH).

The storyboard is a JSON file:

    {
      "name": "0.95.53",                      # output file name: reports/out/<name>.mp4
      "title": "unionclef 0.95.53",
      "subtitle": "one line: what this release is about",
      "caption": "Telegram caption",
      "hook": {"src": "rec.mp4", "from": 120, "to": 128, "speed": 1,
               "text": "the payoff, in five words"},          # optional cold open
      "segments": [
        {"kind": "clip", "src": "rec.mp4", "from": 285, "to": 330, "speed": 4,
         "title": "short event name", "caption": "what happens, in one line",
         "zoom": 1.12,                                           # optional slow push-in
         "callouts": [{"t": 3.5, "x": 960, "y": 540, "text": "arrow"}]},   # t on the cut clip
        {"kind": "split", "title": "same course, two builds",
         "left":  {"src": "a.mp4", "from": 10, "to": 40, "speed": 2, "label": "before"},
         "right": {"src": "b.mp4", "from": 10, "to": 40, "speed": 2, "label": "after"}},
        {"kind": "stat", "title": "what was measured", "before": "28-36 hp", "after": "0-4 hp",
         "before_n": 32, "after_n": 2, "unit": "hp",             # optional: animated bars
         "note": "where it was measured"},
        {"kind": "text", "title": "a heading", "lines": ["point one", "point two"]}
      ]
    }

Layout: an optional cold open (the best moment first, with one big line), then the title,
then the segments separated by a quick wipe, then the end card. A progress bar with a tick per
segment runs along the top. See docs/VIDEO_REPORTS.md for what goes into a report.
"""
from __future__ import annotations

import html
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "deploy" / "runner"))
from uctest import process as subprocess

HF = ROOT / "hf"
VIDEOS = HF / "videos"
OUT = ROOT / "out"
# Use the project's render pin so a CLI upgrade also reaches this launcher.
HF_CLI = next(token for token in
              json.loads((HF / "package.json").read_text(encoding="utf-8"))["scripts"]["render"].split()
              if token.startswith("hyperframes@"))

TITLE_S, TEXT_S, STAT_S, END_S, WIPE_S = 3.0, 5.0, 5.0, 3.0, 0.45
ACCENT = "#3fd0c9"
BAD = "#ff7a7a"
GOOD = "#7dffb0"
BG = "#0b0f14"


def cut(src: str, start: float, end: float, speed: float, dst: Path, w: int = 1920, h: int = 1080) -> float:
    """Trim, speed up and letterbox one clip; return its length on the timeline in seconds."""
    vf = (f"setpts=PTS/{speed},scale={w}:{h}:force_original_aspect_ratio=decrease,"
          f"pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=0x0b0f14,fps=30")
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", str(start), "-t", str(end - start), "-i", src,
           "-vf", vf, "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
           "-pix_fmt", "yuv420p", str(dst)]
    subprocess.run(cmd, check=True)
    return round((end - start) / speed, 3)


def card(cid: str, start: float, dur: float, inner: str, cls: str = "") -> str:
    return (f'<div id="{cid}" class="clip card {cls}" data-start="{start:.3f}" data-duration="{dur:.3f}">'
            f"{inner}</div>")


class Timeline:
    def __init__(self):
        self.parts: list[str] = []
        self.tweens: list[str] = []
        self.chapters: list[tuple[float, str]] = []
        self.t = 0.0
        self.n = 0

    def tw(self, s: str):
        self.tweens.append(s)

    def wipe(self):
        """A bar of the accent colour sweeps across the cut between two segments."""
        self.n += 1
        wid = f"wipe{self.n}"
        start = max(self.t - WIPE_S / 2, 0)
        self.parts.append(card(wid, start, WIPE_S, f'<div id="{wid}-b" class="wipebar"></div>', "top"))
        self.tw(f"tl.fromTo('#{wid}-b',{{x:-2200}},{{x:2200,duration:{WIPE_S},ease:'power2.inOut'}},{start:.3f});")


def build(story: dict) -> tuple[str, float]:
    esc = html.escape
    tl = Timeline()
    VIDEOS.mkdir(parents=True, exist_ok=True)
    tag = f'<div class="tag">unionclef · {esc(story.get("name", ""))}</div>'

    hook = story.get("hook")
    if hook:
        dur = cut(hook["src"], float(hook["from"]), float(hook["to"]), float(hook.get("speed", 1)),
                  VIDEOS / "hook.mp4")
        tl.parts.append(f'<video id="vhook" class="clip vid" src="videos/hook.mp4" muted playsinline '
                        f'data-start="0" data-duration="{dur:.3f}"></video>')
        tl.parts.append(card("hookc", 0, dur,
                             f'<div class="hookline"><span id="hook-t">{esc(hook.get("text", ""))}</span></div>'))
        tl.tw("tl.fromTo('#vhook',{scale:1.18},{scale:1.0,duration:%.3f,ease:'power1.out'},0);" % dur)
        tl.tw("tl.fromTo('#hook-t',{opacity:0,y:40,scale:0.9},{opacity:1,y:0,scale:1,duration:0.6,ease:'back.out(1.7)'},0.3);")
        tl.t = dur
        tl.wipe()

    tl.parts.append(card("title", tl.t, TITLE_S,
                         f'<div class="center"><div id="t-k" class="kicker">video report</div>'
                         f'<div id="t-h" class="h1">{esc(story["title"])}</div>'
                         f'<div id="t-line" class="rule"></div>'
                         f'<div id="t-s" class="sub">{esc(story.get("subtitle", ""))}</div></div>', "bgcard"))
    tl.tw(f"tl.fromTo('#t-k',{{opacity:0,y:-20}},{{opacity:1,y:0,duration:0.6}},{tl.t + 0.1:.3f});")
    tl.tw(f"tl.fromTo('#t-h',{{opacity:0,y:40}},{{opacity:1,y:0,duration:0.7,ease:'power3.out'}},{tl.t + 0.2:.3f});")
    tl.tw(f"tl.fromTo('#t-line',{{scaleX:0}},{{scaleX:1,duration:0.6,ease:'power2.out'}},{tl.t + 0.6:.3f});")
    tl.tw(f"tl.fromTo('#t-s',{{opacity:0}},{{opacity:1,duration:0.6}},{tl.t + 0.9:.3f});")
    tl.t += TITLE_S

    for i, seg in enumerate(story["segments"]):
        tl.wipe()
        kind = seg["kind"]
        tl.chapters.append((tl.t, seg.get("title", "")))
        if kind == "clip":
            name = f"seg{i:02d}.mp4"
            speed = float(seg.get("speed", 1))
            dur = cut(seg["src"], float(seg["from"]), float(seg["to"]), speed, VIDEOS / name)
            badge = f'<div class="speed">x{speed:g}</div>' if speed != 1 else ""
            tl.parts.append(
                f'<video id="v{i}" class="clip vid" src="videos/{name}" muted playsinline '
                f'data-start="{tl.t:.3f}" data-duration="{dur:.3f}"></video>')
            zoom = float(seg.get("zoom", 1.08))
            tl.tw(f"tl.fromTo('#v{i}',{{scale:1.0}},{{scale:{zoom},duration:{dur:.3f},ease:'none'}},{tl.t:.3f});")
            tl.parts.append(card(f"lt{i}", tl.t, dur,
                                 f'{tag}{badge}<div id="lt{i}-box" class="lower"><div class="lt-num">'
                                 f'{len(tl.chapters):02d}</div><div><div class="lt-title">'
                                 f'{esc(seg.get("title", ""))}</div><div class="lt-cap">'
                                 f'{esc(seg.get("caption", ""))}</div></div></div>'))
            tl.tw(f"tl.fromTo('#lt{i}-box',{{opacity:0,x:-80}},{{opacity:1,x:0,duration:0.6,ease:'power3.out'}},{tl.t + 0.3:.3f});")
            tl.tw(f"tl.to('#lt{i}-box',{{opacity:0,x:-40,duration:0.4}},{tl.t + max(dur - 0.5, 0.8):.3f});")
            for k, c in enumerate(seg.get("callouts", [])):
                cid = f"co{i}-{k}"
                ct = tl.t + float(c["t"])
                cd = float(c.get("dur", 2.2))
                tl.parts.append(card(cid, ct, cd,
                                     f'<div id="{cid}-r" class="ring" style="left:{c["x"] - 70}px;top:{c["y"] - 70}px"></div>'
                                     f'<div id="{cid}-l" class="ringlabel" style="left:{c["x"] + 90}px;top:{c["y"] - 30}px">'
                                     f'{esc(c.get("text", ""))}</div>'))
                tl.tw(f"tl.fromTo('#{cid}-r',{{scale:2.2,opacity:0}},{{scale:1,opacity:1,duration:0.45,ease:'back.out(2)'}},{ct:.3f});")
                tl.tw(f"tl.fromTo('#{cid}-l',{{opacity:0,x:-20}},{{opacity:1,x:0,duration:0.4}},{ct + 0.2:.3f});")
            tl.t += dur
        elif kind == "split":
            durs = []
            halves = []
            for side in ("left", "right"):
                c = seg[side]
                name = f"seg{i:02d}{side[0]}.mp4"
                d = cut(c["src"], float(c["from"]), float(c["to"]), float(c.get("speed", 1)), VIDEOS / name, 956, 538)
                durs.append(d)
                halves.append((side, name, c, d))
            dur = max(durs)
            for side, name, c, d in halves:
                good = side == "right"
                tl.parts.append(
                    f'<video id="v{i}{side[0]}" class="clip half {side}" src="videos/{name}" muted playsinline '
                    f'data-start="{tl.t:.3f}" data-duration="{d:.3f}"></video>')
            spd = float(seg["left"].get("speed", 1))
            badge = f'<div class="speed">x{spd:g}</div>' if spd != 1 else ""
            tl.parts.append(card(f"sp{i}", tl.t, dur,
                                 f'{tag}{badge}<div id="sp{i}-h" class="splith">{esc(seg.get("title", ""))}</div>'
                                 f'<div id="sp{i}-l" class="splitlab left bad">{esc(seg["left"].get("label", "before"))}</div>'
                                 f'<div id="sp{i}-r" class="splitlab right good">{esc(seg["right"].get("label", "after"))}</div>'
                                 f'<div class="splitcap left">{esc(seg["left"].get("caption", ""))}</div>'
                                 f'<div class="splitcap right">{esc(seg["right"].get("caption", ""))}</div>',
                                 "splitbg"))
            tl.tw(f"tl.fromTo('#v{i}l',{{x:-300,opacity:0}},{{x:0,opacity:1,duration:0.6,ease:'power3.out'}},{tl.t:.3f});")
            tl.tw(f"tl.fromTo('#v{i}r',{{x:300,opacity:0}},{{x:0,opacity:1,duration:0.6,ease:'power3.out'}},{tl.t:.3f});")
            tl.tw(f"tl.fromTo('#sp{i}-h',{{opacity:0,y:-30}},{{opacity:1,y:0,duration:0.5}},{tl.t + 0.2:.3f});")
            tl.tw(f"tl.fromTo(['#sp{i}-l','#sp{i}-r'],{{opacity:0,scale:0.7}},{{opacity:1,scale:1,duration:0.5,ease:'back.out(2)',stagger:0.15}},{tl.t + 0.5:.3f});")
            tl.t += dur
        elif kind == "stat":
            note = f'<div class="note">{esc(seg.get("note", ""))}</div>' if seg.get("note") else ""
            bars = ""
            bn, an = seg.get("before_n"), seg.get("after_n")
            if bn is not None and an is not None:
                top = max(float(bn), float(an), 1e-9)
                bw = int(1100 * float(bn) / top)
                aw = int(1100 * float(an) / top)
                unit = esc(seg.get("unit", ""))
                bars = (f'<div class="bars"><div class="barrow"><span class="blab">before</span>'
                        f'<div id="st{i}-bb" class="bar bad" style="width:{max(bw, 8)}px"></div>'
                        f'<span id="st{i}-bn" class="bnum bad">0</span><span class="bunit">{unit}</span></div>'
                        f'<div class="barrow"><span class="blab">after</span>'
                        f'<div id="st{i}-ab" class="bar good" style="width:{max(aw, 8)}px"></div>'
                        f'<span id="st{i}-an" class="bnum good">0</span><span class="bunit">{unit}</span></div></div>')
                tl.tw(f"tl.fromTo('#st{i}-bb',{{scaleX:0}},{{scaleX:1,duration:0.9,ease:'power3.out'}},{tl.t + 0.4:.3f});")
                tl.tw(f"tl.fromTo('#st{i}-ab',{{scaleX:0}},{{scaleX:1,duration:0.9,ease:'power3.out'}},{tl.t + 1.3:.3f});")
                for sid, val, at in ((f"st{i}-bn", bn, 0.4), (f"st{i}-an", an, 1.3)):
                    tl.tw(f"(function(){{var o={{v:0}};tl.to(o,{{v:{float(val)},duration:0.9,ease:'power3.out',"
                          f"onUpdate:function(){{document.getElementById('{sid}').textContent=Math.round(o.v)}}}},{tl.t + at:.3f});}})();")
                body = f'<div class="h2">{esc(seg["title"])}</div>{bars}'
            else:
                body = (f'<div class="h2">{esc(seg["title"])}</div>'
                        f'<div class="cmp"><div id="st{i}-b" class="was"><span>before</span>'
                        f'{esc(seg["before"])}</div><div class="arrow">→</div>'
                        f'<div id="st{i}-a" class="now"><span>after</span>{esc(seg["after"])}</div></div>')
                tl.tw(f"tl.fromTo('#st{i}-b',{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.5}},{tl.t + 0.3:.3f});")
                tl.tw(f"tl.fromTo('#st{i}-a',{{opacity:0,scale:0.8}},{{opacity:1,scale:1,duration:0.6,ease:'back.out(1.8)'}},{tl.t + 1.1:.3f});")
            tl.parts.append(card(f"st{i}", tl.t, STAT_S, f'{tag}<div class="center">{body}{note}</div>', "bgcard"))
            tl.t += STAT_S
        elif kind == "text":
            items = "".join(f'<li id="tx{i}-{k}">{esc(x)}</li>' for k, x in enumerate(seg.get("lines", [])))
            tl.parts.append(card(f"tx{i}", tl.t, TEXT_S,
                                 f'{tag}<div class="center left"><div class="h2">{esc(seg["title"])}</div>'
                                 f"<ul>{items}</ul></div>", "bgcard"))
            for k in range(len(seg.get("lines", []))):
                tl.tw(f"tl.fromTo('#tx{i}-{k}',{{opacity:0,x:-40}},{{opacity:1,x:0,duration:0.45,ease:'power2.out'}},{tl.t + 0.4 + 0.4 * k:.3f});")
            tl.t += TEXT_S
        else:
            raise ValueError(f"unknown segment kind: {kind}")

    tl.wipe()
    tl.parts.append(card("end", tl.t, END_S,
                         f'<div class="center"><div id="e-h" class="h1">{esc(story["title"])}</div>'
                         f'<div id="e-s" class="sub">github.com/3ndetz/unionclef</div></div>', "bgcard"))
    tl.tw(f"tl.fromTo('#e-h',{{opacity:0,scale:0.92}},{{opacity:1,scale:1,duration:0.6,ease:'power2.out'}},{tl.t + 0.2:.3f});")
    tl.tw(f"tl.fromTo('#e-s',{{opacity:0}},{{opacity:1,duration:0.6}},{tl.t + 0.6:.3f});")
    tl.t += END_S
    total = tl.t

    # Progress bar with a tick per chapter, over everything.
    ticks = "".join(f'<div class="tick" style="left:{1920 * s / total:.1f}px"></div>' for s, _ in tl.chapters)
    tl.parts.append(card("prog", 0, total, f'<div class="progtrack">{ticks}<div id="progfill" class="progfill"></div></div>', "top"))
    tl.tw(f"tl.fromTo('#progfill',{{scaleX:0}},{{scaleX:1,duration:{total:.3f},ease:'none'}},0);")
    return page(total, "\n      ".join(tl.parts), "\n      ".join(tl.tweens)), total


def page(total: float, body: str, tweens: str) -> str:
    return f"""<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=1920, height=1080" />
    <script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
    <style>
      * {{ margin: 0; padding: 0; box-sizing: border-box; }}
      html, body {{ width: 1920px; height: 1080px; overflow: hidden; background: {BG}; }}
      body {{ font-family: "Segoe UI", "Inter", Arial, sans-serif; color: #e8eef2; }}
      .clip {{ position: absolute; inset: 0; }}
      .vid {{ width: 1920px; height: 1080px; object-fit: contain; background: {BG}; transform-origin: 50% 50%; }}
      .card {{ pointer-events: none; }}
      .top {{ z-index: 50; }}
      .bgcard {{ background: radial-gradient(circle at 30% 20%, #15303a 0%, {BG} 60%); }}
      .splitbg {{ z-index: 5; }}
      .center {{ position: absolute; inset: 0; display: flex; flex-direction: column;
                 align-items: center; justify-content: center; gap: 28px; text-align: center; }}
      .center.left {{ align-items: flex-start; padding-left: 220px; text-align: left; }}
      .kicker {{ font-size: 28px; letter-spacing: 8px; text-transform: uppercase; color: {ACCENT}; }}
      .h1 {{ font-size: 104px; font-weight: 800; letter-spacing: 2px; }}
      .rule {{ width: 520px; height: 6px; background: {ACCENT}; transform-origin: 0 50%; }}
      .h2 {{ font-size: 64px; font-weight: 700; color: {ACCENT}; }}
      .sub {{ font-size: 40px; color: #9fb2bf; max-width: 1500px; }}
      .hookline {{ position: absolute; left: 0; right: 0; bottom: 120px; text-align: center; }}
      .hookline span {{ display: inline-block; font-size: 76px; font-weight: 900; color: #fff;
                        background: rgba(11,15,20,0.78); padding: 18px 44px; border-bottom: 8px solid {ACCENT}; }}
      .wipebar {{ position: absolute; top: 0; bottom: 0; width: 1400px; left: 260px;
                  background: linear-gradient(90deg, transparent, {ACCENT} 30%, #1a7f7a 70%, transparent); }}
      .tag {{ position: absolute; bottom: 40px; right: 44px; font-size: 24px; letter-spacing: 3px;
              text-transform: uppercase; color: {ACCENT}; background: rgba(11,15,20,0.72);
              padding: 8px 16px; border-left: 4px solid {ACCENT}; }}
      .speed {{ position: absolute; top: 40px; right: 44px; font-size: 30px; font-weight: 700;
                color: {BG}; background: {ACCENT}; padding: 6px 16px; border-radius: 6px; }}
      .lower {{ position: absolute; left: 44px; bottom: 60px; max-width: 1400px; display: flex; gap: 26px;
                align-items: center; background: rgba(11,15,20,0.86); border-left: 8px solid {ACCENT};
                padding: 22px 32px; }}
      .lt-num {{ font-size: 64px; font-weight: 900; color: {ACCENT}; }}
      .lt-title {{ font-size: 48px; font-weight: 800; }}
      .lt-cap {{ font-size: 32px; color: #b9c7d0; margin-top: 8px; }}
      .ring {{ position: absolute; width: 140px; height: 140px; border-radius: 50%;
               border: 7px solid #ffd84a; box-shadow: 0 0 24px rgba(255,216,74,0.7); }}
      .ringlabel {{ position: absolute; font-size: 36px; font-weight: 800; color: #111;
                    background: #ffd84a; padding: 6px 16px; border-radius: 4px; white-space: nowrap; }}
      .half {{ position: absolute; top: 250px; width: 956px; height: 538px; object-fit: contain; }}
      .half.left {{ left: 0; }}
      .half.right {{ left: 964px; }}
      .splith {{ position: absolute; top: 90px; left: 0; right: 0; text-align: center; font-size: 60px; font-weight: 800; }}
      .splitlab {{ position: absolute; top: 180px; font-size: 40px; font-weight: 900; text-transform: uppercase;
                   letter-spacing: 6px; width: 956px; text-align: center; }}
      .splitlab.left {{ left: 0; }} .splitlab.right {{ left: 964px; }}
      .splitcap {{ position: absolute; top: 810px; width: 956px; text-align: center; font-size: 32px; color: #b9c7d0; padding: 0 30px; }}
      .splitcap.left {{ left: 0; }} .splitcap.right {{ left: 964px; }}
      .bad {{ color: {BAD}; }} .good {{ color: {GOOD}; }}
      .bars {{ display: flex; flex-direction: column; gap: 34px; align-items: flex-start; }}
      .barrow {{ display: flex; align-items: center; gap: 24px; }}
      .blab {{ width: 170px; text-align: right; font-size: 30px; letter-spacing: 4px; text-transform: uppercase; color: #7d8f9b; }}
      .bar {{ height: 64px; border-radius: 6px; transform-origin: 0 50%; }}
      .bar.bad {{ background: {BAD}; }} .bar.good {{ background: {GOOD}; }}
      .bnum {{ font-size: 64px; font-weight: 900; min-width: 90px; }}
      .bunit {{ font-size: 32px; color: #7d8f9b; }}
      .cmp {{ display: flex; align-items: center; gap: 56px; font-size: 64px; font-weight: 700; }}
      .cmp span {{ display: block; font-size: 26px; letter-spacing: 3px; text-transform: uppercase;
                   color: #7d8f9b; margin-bottom: 10px; font-weight: 600; }}
      .was {{ color: {BAD}; }}
      .now {{ color: {GOOD}; }}
      .arrow {{ color: #7d8f9b; }}
      .note {{ font-size: 28px; color: #7d8f9b; }}
      ul {{ list-style: none; display: flex; flex-direction: column; gap: 22px; }}
      li {{ font-size: 44px; padding-left: 34px; border-left: 6px solid {ACCENT}; }}
      .progtrack {{ position: absolute; top: 0; left: 0; width: 1920px; height: 8px; background: rgba(255,255,255,0.08); }}
      .progfill {{ position: absolute; inset: 0; background: {ACCENT}; transform-origin: 0 50%; }}
      .tick {{ position: absolute; top: 0; width: 3px; height: 14px; background: #e8eef2; z-index: 2; }}
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
    subprocess.run([npx, "--yes", HF_CLI, "check"], cwd=HF, check=True)
    OUT.mkdir(exist_ok=True)
    out = OUT / f"{story.get('name', 'report')}.mp4"
    r = subprocess.run([npx, "--yes", HF_CLI, "render", "--output", str(out)], cwd=HF)
    if r.returncode != 0 or not out.exists():
        print("render failed")
        return 1
    print(f"rendered: {out} ({out.stat().st_size // 1024} KB)")
    # Telegram's bot API takes 50 MB; the render is ~1 MB a second. Keep a smaller copy to send.
    tg = OUT / f"{story.get('name', 'report')}-tg.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(out), "-c:v", "libx264", "-preset", "slow",
                    "-crf", "30", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(tg)], check=True)
    print(f"for telegram: {tg} ({tg.stat().st_size // 1024} KB)")
    if "--send" in sys.argv:
        sys.path.insert(0, str(ROOT.parent / "deploy" / "runner"))
        from tg_speedup import send
        ok, info = send(str(tg), story.get("caption", story["title"]))
        print("TG:", ok, info)
    return 0


if __name__ == "__main__":
    sys.exit(main())
