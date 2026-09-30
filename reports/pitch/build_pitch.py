"""Build the 3ndetz Labs investor pitch for unionclef: cut footage, compose in HyperFrames, mix, render.

    python reports/pitch/build_pitch.py            # cut + compose + render + mix -> out/pitch.mp4
    python reports/pitch/build_pitch.py --compose  # write hf/index.html only (no render)
    python reports/pitch/build_pitch.py --mix      # remix the audio onto the last silent render

Inputs: voice/pick/lXX.wav (chosen takes, see lines.json and tts.py), words.json (word timings of
those takes from POST /stt), data.json (git history), hf/assets (fonts, images, grain), the footage
paths in FOOT below, and work/Hep_Cats.mp3 (Kevin MacLeod, incompetech.com, CC BY 4.0).
Picture is rendered silent by HyperFrames; voice and music are mixed with ffmpeg afterwards
(music ducked under the voice with a sidechain compressor).
"""
from __future__ import annotations

import html
import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
HF = HERE / "hf"
VID = HF / "videos"
OUT = HERE / "out"
WORK = HERE / "work"
HF_CLI = "hyperframes@0.8.30"
MUSIC = WORK / "Hep_Cats.mp3"
ART = Path("C:/Repos/pet/unionclef/deploy/runner/artifacts")

WORDS = json.loads((HERE / "words.json").read_text(encoding="utf-8"))
DATA = json.loads((HERE / "data.json").read_text(encoding="utf-8"))
FOOT = json.loads((HERE / "footage.json").read_text(encoding="utf-8"))


def vdur(line: str) -> float:
    p = HERE / "voice" / "pick" / f"{line}.wav"
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)],
                       capture_output=True, text=True)
    return float(r.stdout.strip())


def w(line: str, word: str, n: int = 1) -> float:
    """Start time (s, relative to the line) of the n-th occurrence of `word` in the take."""
    k = 0
    for t, s, _e in WORDS[line]:
        if t.lower().strip(".,?!") == word.lower():
            k += 1
            if k == n:
                return s
    raise KeyError(f"{line}: no '{word}' #{n}")


# ---------------------------------------------------------------- footage
SRCDUR: dict[str, float] = {}


def cut(key: str, src: str, start: float, dur: float, speed: float, crop: float, cy: float) -> str:
    """Cut `dur` timeline seconds from `src` at `start`, sped up, cropped in (hides chat/HUD), 1080p30."""
    name = f"{key}.mp4"
    dst = VID / name
    have = SRCDUR.setdefault(src, float(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src],
        capture_output=True, text=True).stdout.strip()))
    if start + dur * speed > have - 0.1:
        print(f"  WARN {key}: needs {start + dur * speed:.1f}s of {Path(src).name} ({have:.1f}s); shifting start")
        start = max(0.0, have - 0.1 - dur * speed)
    cw, ch = f"iw*{crop}", f"ih*{crop}"
    vf = (f"setpts=PTS/{speed},crop={cw}:{ch}:(iw-{cw})/2:(ih-{ch})*{cy},"
          f"scale=1920:1080:flags=lanczos,fps=30,eq=saturation=1.08:contrast=1.04")
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.3f}", "-t", f"{dur * speed + 0.2:.3f}", "-i", src,
           "-vf", vf, "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p",
           "-t", f"{dur + 0.1:.3f}", str(dst)]
    if "--compose" not in sys.argv or not dst.exists():
        subprocess.run(cmd, check=True)
    return name


class TL:
    def __init__(self):
        self.parts: list[str] = []
        self.tw: list[str] = []
        self.voice: list[tuple[str, float]] = []
        self.sfx: list[float] = []
        self.n = 0

    def id(self, p="e"):
        self.n += 1
        return f"{p}{self.n}"

    def t(self, s: str):
        self.tw.append(s)

    # --- media
    def clip(self, foot: str, t0: float, dur: float, frm: float | None = None, speed: float | None = None,
             z: int = 1, zoom: tuple[float, float] = (1.0, 1.06), cls: str = "vid", badge: bool = True,
             crop: float | None = None) -> str:
        f = FOOT[foot]
        src = f["src"]
        frm = f.get("from", 0) if frm is None else frm
        speed = f.get("speed", 1) if speed is None else speed
        vid = self.id("v")
        name = cut(vid, src, frm, dur, speed, crop or f.get("crop", 0.8), f.get("cy", 0.3))
        self.parts.append(f'<video id="{vid}" class="clip {cls}" style="z-index:{z}" src="videos/{name}" muted '
                          f'playsinline data-start="{t0:.3f}" data-duration="{dur:.3f}"></video>')
        if zoom and cls == "vid":
            self.t(f"tl.fromTo('#{vid}',{{scale:{zoom[0]}}},{{scale:{zoom[1]},duration:{dur:.3f},ease:'none'}},{t0:.3f});")
        if badge and speed != 1:
            self.card(t0, dur, f'<div class="speed">x{speed:g}</div>', z=z + 30)
        return vid

    def img(self, src: str, t0: float, dur: float, z: int = 0, kb=(1.0, 1.08), origin="50% 50%", xy=(0, 0)) -> str:
        i = self.id("i")
        self.parts.append(f'<div class="clip" style="z-index:{z}" data-start="{t0:.3f}" data-duration="{dur:.3f}">'
                          f'<img id="{i}" class="bgimg" src="{src}" style="transform-origin:{origin}"></div>')
        self.t(f"tl.fromTo('#{i}',{{scale:{kb[0]},x:0,y:0}},{{scale:{kb[1]},x:{xy[0]},y:{xy[1]},duration:{dur:.3f},ease:'sine.inOut'}},{t0:.3f});")
        return i

    def card(self, t0: float, dur: float, inner: str, z: int = 20, cls: str = "") -> str:
        c = self.id("c")
        self.parts.append(f'<div id="{c}" class="clip card {cls}" style="z-index:{z}" data-start="{t0:.3f}" '
                          f'data-duration="{dur:.3f}">{inner}</div>')
        return c

    # --- text
    def kin(self, t0: float, dur: float, big: str, small: str = "", pos: str = "bl", color: str = "red", z: int = 40):
        """Big condensed label with a kicker, sliding in; `pos` bl/br/tl/tc."""
        k = self.id("k")
        sm = f'<div class="kin-s">{html.escape(small)}</div>' if small else ""
        self.card(t0, dur, f'<div id="{k}" class="kin {pos} {color}">{sm}<div class="kin-b">{html.escape(big)}</div></div>', z=z)
        self.t(f"tl.fromTo('#{k}',{{opacity:0,x:-70}},{{opacity:1,x:0,duration:0.35,ease:'power3.out'}},{t0:.3f});")

    def stamp(self, t0: float, dur: float, text: str, x: int = 960, y: int = 540, rot: float = -8, size: int = 110,
              color: str = "#d8453a", z: int = 60):
        s = self.id("s")
        self.sfx.append(t0 + 0.26)
        self.card(t0, dur, f'<div class="stampbox" style="left:{x}px;top:{y}px"><div id="{s}" class="stamp" '
                           f'style="font-size:{size}px;color:{color};border-color:{color}">{html.escape(text)}</div></div>', z=z)
        self.t(f"tl.fromTo('#{s}',{{scale:2.6,opacity:0,rotation:{rot - 14}}},{{scale:1,opacity:0.95,rotation:{rot},duration:0.28,ease:'power4.in'}},{t0:.3f});")
        self.t(f"tl.fromTo('#{s}',{{x:0}},{{x:6,duration:0.05,repeat:3,yoyo:true}},{t0 + 0.28:.3f});")

    def say(self, line: str, t0: float):
        self.voice.append((line, t0))
        return t0 + vdur(line)


# ------------------------------------------------------------------ scenes
def build() -> tuple[str, float, TL]:
    T = TL()
    t = 0.0

    # ---- S0 cold open: fast cuts, "Gentlemen..."
    v0 = 0.7
    end = T.say("l01", v0) + 0.35
    cuts = [("co_gaps", 0.0), ("co_lava", 0.85), ("co_nether", 1.7), ("co_water", 2.55),
            ("co_bridge", 3.3), ("co_skel", 4.05), ("co_lit", 4.85)]
    for i, (k, s) in enumerate(cuts):
        e = cuts[i + 1][1] if i + 1 < len(cuts) else end
        T.clip(k, s, e - s, zoom=(1.12, 1.0), badge=False)
    T.card(0, end, '<div class="reel">3NDETZ LABS &middot; CONFIDENTIAL INVESTOR REEL &middot; DO NOT TOUCH ANYTHING</div>', z=45)
    T.t("tl.fromTo('.reel',{opacity:0},{opacity:1,duration:0.4},0.2);")
    # flash to title
    t = end

    # ---- S1 title over the lab painting
    v = t + 0.35
    end = T.say("l02", v) + 0.5
    T.img("assets/lab.jpg", t, end - t, kb=(1.12, 1.0), origin="50% 30%")
    T.card(t, end - t, '<div class="vign"></div><div class="topshade"></div>', z=5)
    lg = T.card(t, end - t, f'<div class="titlebox"><div id="lg-badge">{LOGO}</div>'
                              '<div id="lg-lab" class="labname">3NDETZ LABS</div>'
                              '<div id="lg-pres" class="presents">presents</div>'
                              '<div id="lg-title" class="bigtitle">UNIONCLEF</div>'
                              '<div id="lg-sub" class="subline">an open platform for AI agents that play Minecraft</div></div>', z=30)
    T.t(f"tl.fromTo('#lg-badge',{{scale:0,rotation:-180}},{{scale:1,rotation:0,duration:0.8,ease:'back.out(1.6)'}},{t + 0.1:.3f});")
    T.t(f"tl.fromTo('#lg-lab',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.5}},{v + w('l02', 'labs') - 0.2:.3f});")
    T.t(f"tl.fromTo('#lg-pres',{{opacity:0}},{{opacity:1,duration:0.4}},{v + w('l02', 'this') :.3f});")
    T.t(f"tl.fromTo('#lg-title',{{opacity:0,scale:1.8}},{{opacity:1,scale:1,duration:0.6,ease:'expo.out'}},{v + w('l02', 'union'):.3f});")
    T.t(f"tl.fromTo('#lg-sub',{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.5}},{v + w('l02', 'union') + 0.6:.3f});")
    T.stamp(v + w("l02", "agree") - 0.1, end - (v + w("l02", "agree") - 0.1), "AGREED", x=1500, y=820, rot=-12, size=90)
    t = end

    # ---- S2 the problem
    v = t + 0.3
    end = T.say("l03", v) + 0.45
    T.img("assets/fail.jpg", t, end - t, kb=(1.0, 1.1), origin="40% 40%")
    T.card(t, end - t, '<div class="vign"></div>', z=5)
    items = [("writes a sonnet", "write", True), ("passes the bar exam", "pass", True),
             ("explains your divorce", "explain", True), ("punches a tree", "punch", False)]
    rows = "".join(f'<div id="ck{i}" class="ckrow {"ok" if ok else "no"}"><span class="ckmark">{"✓" if ok else "✕"}</span>'
                   f'{html.escape(txt)}</div>' for i, (txt, _wd, ok) in enumerate(items))
    T.card(t, end - t, f'<div class="ckbox"><div class="ckhead">YOUR A.I., A PERFORMANCE REVIEW</div>{rows}</div>', z=30)
    T.t(f"tl.fromTo('.ckbox',{{opacity:0,x:-60}},{{opacity:1,x:0,duration:0.5,ease:'power3.out'}},{t + 0.2:.3f});")
    for i, (_txt, wd, ok) in enumerate(items):
        tt = v + w("l03", wd)
        T.t(f"tl.fromTo('#ck{i}',{{opacity:0,x:-30}},{{opacity:1,x:0,duration:0.3,ease:'back.out(2)'}},{tt:.3f});")
    T.t(f"tl.fromTo('#ck3',{{backgroundColor:'rgba(216,69,58,0)'}},{{backgroundColor:'rgba(216,69,58,0.18)',duration:0.3}},{v + w('l03', 'tree'):.3f});")
    T.stamp(v + w("l03", "embarrassing"), end - v - w("l03", "embarrassing"), "EMBARRASSING", x=1330, y=760, rot=-9, size=96)
    t = end

    # ---- S3 levers (MCP)
    v = t + 0.3
    end = T.say("l04", v) + 0.5
    d = end - t
    T.card(t, d, '<div class="paper"></div>', z=0)
    # TV with footage on the right
    tv_x, tv_y, tv_w, tv_h = 1010, 170, 820, 462
    T.card(t, d, f'<div class="tvframe" style="left:{tv_x - 22}px;top:{tv_y - 22}px;width:{tv_w + 44}px;height:{tv_h + 44}px"></div>', z=2)
    seq = [("look", "look", "around"), ("goto", "go", None), ("mine", "mind", None), ("hit", "hi", None),
           ("build", "build", None), ("thinking", "does", None)]
    times = [t] + [v + w("l04", a) for _k, a, _b in seq[1:]] + [end]
    for i, (k, _a, _b) in enumerate(seq):
        T.clip(k, times[i], times[i + 1] - times[i], z=3, cls="tvvid", badge=False, zoom=None)
    agent = ('<div id="ag" class="box agent"><div class="box-k">YOUR AGENT</div><div class="box-t">thinks</div>'
             '<div class="box-s">Claude Code · Claude Desktop · your own</div></div>'
             '<div id="pipe1" class="pipe p1"><span>MCP :25350</span></div>'
             '<div id="pipe2" class="pipe p2"><span>PY4J :25333</span></div>'
             '<div id="botlab" class="box-k botlab">UNIONCLEF · ARMS AND LEGS</div>')
    T.card(t, d, agent, z=10)
    T.t(f"tl.fromTo('#ag',{{opacity:0,y:40}},{{opacity:1,y:0,duration:0.5,ease:'power3.out'}},{v + w('l04', 'agent'):.3f});")
    T.t(f"tl.fromTo('#pipe1',{{scaleX:0}},{{scaleX:1,duration:0.45,ease:'power2.out'}},{v + w('l04', 'mcp') - 0.1:.3f});")
    T.t(f"tl.fromTo('#pipe2',{{scaleX:0}},{{scaleX:1,duration:0.45,ease:'power2.out'}},{v + w('l04', 'python'):.3f});")
    T.t(f"tl.fromTo('#botlab',{{opacity:0}},{{opacity:1,duration:0.4}},{v + w('l04', 'arms'):.3f});")
    tools = ["getGameState", "gotoXYZ", "mineBlock", "punk", "buildBlocks", "bridgeTo", "shootArrowAt",
             "getBlocksAround", "clickMenuByName", "fillSelection", "ExecuteCommand", "…and 49 more"]
    chips = "".join(f'<div id="tc{i}" class="chip">{html.escape(x)}</div>' for i, x in enumerate(tools))
    T.card(t, d, f'<div class="chips">{chips}</div><div class="levers"><span id="lvn">0</span> TOOLS</div>', z=10)
    lt = v + w("l04", "sixty")
    T.t(f"tl.fromTo('.levers',{{opacity:0,scale:0.7}},{{opacity:1,scale:1,duration:0.4,ease:'back.out(2)'}},{lt - 0.2:.3f});")
    T.t(f"(function(){{var o={{v:0}};tl.to(o,{{v:60,duration:0.9,ease:'power2.out',onUpdate:function(){{document.getElementById('lvn').textContent=Math.round(o.v)}}}},{lt - 0.2:.3f});}})();")
    T.t(f"tl.fromTo('.chip',{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.25,stagger:0.05}},{lt:.3f});")
    for i, wd in enumerate(["look", "go", "mind", "hi", "build"]):
        tt = v + w("l04", wd)
        T.t(f"tl.fromTo('#tc{i}',{{backgroundColor:'#1d2a2f',color:'#f3e9d2',scale:1}},{{backgroundColor:'#d8453a',color:'#fff',scale:1.12,duration:0.15,yoyo:true,repeat:1,repeatDelay:0.5}},{tt:.3f});")
    T.stamp(v + w("l04", "division"), end - v - w("l04", "division"), "DIVISION OF LABOR", x=1480, y=985, rot=-6, size=64)
    t = end

    # ---- S4 tungsten
    v = t + 0.25
    end = T.say("l05", v) + 0.8
    marks = [("walk", t), ("tungsten", v + w("l05", "tungsten") - 0.3), ("physics", v + w("l05", "runs")),
             ("gaps_jump2", v + w("l05", "parkour")), ("bridge2", v + w("l05", "bridges")),
             ("pillar", v + w("l05", "pillars")), ("water_dive2", v + w("l05", "swimming")),
             ("lava_lane2", v + w("l05", "one")), ("END", end)]
    for i in range(len(marks) - 1):
        k, s = marks[i]
        T.clip(k, s, marks[i + 1][1] - s, zoom=(1.0, 1.07))
    T.kin(v + w("l05", "tungsten") - 0.3, 2.9, "TUNGSTEN", "the pathfinder", pos="tl", color="teal")
    ph = v + w("l05", "runs")
    T.kin(ph, w("l05", "parkour") - w("l05", "runs"), "PLANS ON A PHYSICS SIMULATION", "of the player's body, tick by tick", pos="tl", color="teal")
    for wd, big, small in [("parkour", "PARKOUR JUMPS", "2, 3 and 4-block gaps"), ("bridges", "BRIDGES", "over nothing"),
                           ("pillars", "PILLARS", "block under its own feet"), ("swimming", "SWIMMING", "dives, crosses, climbs out"),
                           ("one", "ONE-BLOCK LANE", "lava on both sides")]:
        s = v + w("l05", wd)
        nxt = {"parkour": "bridges", "bridges": "pillars", "pillars": "swimming", "swimming": "one"}.get(wd)
        e = v + w("l05", nxt) if nxt else end
        T.kin(s, e - s, big, small, pos="bl", color="red")
    t = end

    # ---- S5 18 of 18
    v = t + 0.2
    end = T.say("l06", v) + 0.6
    d = end - t
    T.card(t, d, '<div class="paper"></div>', z=0)
    names = ["flat", "staircase", "steep", "gaps", "descend", "cliff", "water", "ladder", "slime", "break",
             "wall2", "bridge", "hazard", "notch", "lava", "powder", "powder_pit", "tunnel"]
    tiles = "".join(f'<div id="nt{i}" class="tile"><span class="tn">nav_{n}</span><span class="tk">✓</span></div>'
                    for i, n in enumerate(names))
    T.card(t, d, f'<div class="gridhead">NAVIGATION COURSES · RELEASE 0.95.53</div><div class="grid">{tiles}'
                 f'<div id="nt18" class="tile ghost"><span class="tn">nav_19</span><span class="tk">?</span></div></div>'
                 f'<div class="score"><span id="scn">0</span><span class="of">/18</span></div>', z=5)
    T.t(f"tl.fromTo('.tile:not(.ghost)',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.25,stagger:0.04}},{t + 0.1:.3f});")
    a = v + w("l06", "passes")
    T.t(f"tl.fromTo('.tile:not(.ghost) .tk',{{opacity:0,scale:3}},{{opacity:1,scale:1,duration:0.15,stagger:0.09}},{a:.3f});")
    T.t(f"tl.to('.tile:not(.ghost)',{{backgroundColor:'#2f7d5b',color:'#f3e9d2',duration:0.15,stagger:0.09}},{a:.3f});")
    T.t(f"(function(){{var o={{v:0}};tl.to(o,{{v:18,duration:1.62,ease:'none',onUpdate:function(){{document.getElementById('scn').textContent=Math.round(o.v)}}}},{a:.3f});}})();")
    n19 = v + w("l06", "nineteen")
    T.t(f"tl.fromTo('#nt18',{{opacity:0,scale:0.5}},{{opacity:1,scale:1,duration:0.3,ease:'back.out(2)'}},{n19:.3f});")
    T.t(f"tl.to('#nt18',{{x:10,duration:0.05,repeat:5,yoyo:true}},{v + w('l06', 'only'):.3f});")
    T.t(f"tl.to('#nt18',{{opacity:0.15,duration:0.3}},{v + w('l06', 'only') + 0.35:.3f});")
    T.stamp(v + w("l06", "build"), end - v - w("l06", "build"), "BUILD MORE COURSES", x=1180, y=900, rot=-5, size=70)
    t = end

    # ---- S6 arrows
    v = t + 0.2
    end = T.say("l07", v) + 0.9
    s_split = v + w("l07", "every")
    T.clip("skeleton2", t, s_split - t, zoom=(1.0, 1.08))
    T.kin(t + 0.3, s_split - t - 0.4, "SKELETONS", "they shoot arrows, arrows hurt", pos="bl")
    sp_end = v + w("l07", "ten")
    T.clip("ledge", s_split, end - s_split, zoom=(1.0, 1.1))
    T.kin(s_split, sp_end - s_split, "18 WAYS TO STEP, EVERY TICK", "tungsten physics · lava one side, a 6-block drop the other", pos="tl", color="teal")
    bt = v + w("l07", "before")
    zt = v + w("l07", "zero")
    T.card(sp_end, end - sp_end, '<div class="dmg"><div class="dmgrow"><span class="dl">damage from 10 arrows · course arrow_dodge</span></div>'
                                 '<div class="dmgrow"><span class="dk">dodge off</span><div id="db1" class="dbar bad"></div><span id="dn1" class="dn bad">0</span><span class="du">hp</span></div>'
                                 '<div class="dmgrow"><span class="dk">dodge on</span><span id="dn2" class="dn good">0</span><span class="du">hp</span></div></div>', z=14)
    T.t(f"tl.fromTo('.dmg',{{opacity:0,y:40}},{{opacity:1,y:0,duration:0.35}},{sp_end:.3f});")
    T.t(f"tl.fromTo('#db1',{{width:0}},{{width:760,duration:0.8,ease:'power3.out'}},{bt:.3f});")
    T.t(f"(function(){{var o={{v:0}};tl.to(o,{{v:24,duration:0.8,ease:'power3.out',onUpdate:function(){{document.getElementById('dn1').textContent=Math.round(o.v)}}}},{bt:.3f});}})();")
    T.t(f"tl.fromTo('#dn2',{{scale:2.5,opacity:0}},{{scale:1,opacity:1,duration:0.3,ease:'back.out(2)'}},{zt:.3f});")
    T.stamp(v + w("l07", "denied"), end - v - w("l07", "denied"), "COMPLAINT DENIED", x=960, y=300, rot=-7, size=84)
    t = end

    # ---- S7 survival
    v = t + 0.2
    end = T.say("l08", v) + 0.6
    nt = v + w("l08", "night")
    marks = [("gamer_wood", t), ("gamer_stone", v + w("l08", "stone")), ("gamer_iron", v + w("l08", "iron")),
             ("shelter", nt), ("END", end)]
    for i in range(len(marks) - 1):
        k, s = marks[i]
        T.clip(k, s, marks[i + 1][1] - s, zoom=(1.0, 1.06))
    lad = ('<div class="ladder"><div class="lk">FROM AN EMPTY INVENTORY</div><div class="lrow">'
           '<span id="r1" class="rung">WOOD</span><span class="arr">→</span><span id="r2" class="rung">STONE</span>'
           '<span class="arr">→</span><span id="r3" class="rung">IRON TOOLS</span><span id="r4" class="rtime">8.4 MIN</span></div></div>')
    T.card(t, nt - t, lad, z=30)
    T.t(f"tl.fromTo('.ladder',{{opacity:0,y:-30}},{{opacity:1,y:0,duration:0.4}},{t + 0.2:.3f});")
    for i, wd in enumerate(["wood", "stone", "iron", "eight"]):
        T.t(f"tl.fromTo('#r{i + 1}',{{opacity:0.2,scale:0.8}},{{opacity:1,scale:1,duration:0.25,ease:'back.out(2)'}},{v + w('l08', wd):.3f});")
    T.kin(nt, end - nt, "NIGHT SHELTER", "dig a 1x1 hole, put a lid on, wait for morning", pos="bl", color="teal")
    T.stamp(v + w("l08", "retirement"), end - v - w("l08", "retirement"), "RETIREMENT PLAN", x=1250, y=330, rot=-8, size=80)
    t = end

    # ---- S8 portal
    v = t + 0.2
    end = T.say("l09", v) + 1.4
    marks = [("portal_walk", t), ("portal_lava", v + w("l09", "pours")), ("portal_water", v + w("l09", "pours", 2)),
             ("portal_cast", v + w("l09", "casts")), ("portal_lit", v + w("l09", "lights")),
             ("portal_in", v + w("l09", "and")), ("portal_nether", v + w("l09", "hell")), ("END", end)]
    for i in range(len(marks) - 1):
        k, s = marks[i]
        T.clip(k, s, marks[i + 1][1] - s, zoom=(1.0, 1.06))
    dp = v + w("l09", "diamond")
    T.kin(dp, v + w("l09", "pours") - dp, "NO DIAMOND PICKAXE", "normally required. not here.", pos="tl", color="red")
    T.stamp(v + w("l09", "budget"), v + w("l09", "pours") - v - w("l09", "budget"), "BUDGET CUTS", x=1300, y=700, rot=-10, size=100)
    T.kin(v + w("l09", "pours"), w("l09", "pours", 2) - w("l09", "pours"), "LAVA INTO A MOULD", "bucket 1", pos="bl")
    T.kin(v + w("l09", "pours", 2), w("l09", "casts") - w("l09", "pours", 2), "WATER ON TOP", "bucket 2 · lava turns to obsidian", pos="bl", color="teal")
    cs = v + w("l09", "casts")
    ce = v + w("l09", "lights")
    T.card(cs, ce - cs, '<div class="obs"><span id="obn">0</span><span class="obt">/10 OBSIDIAN CAST</span></div>', z=40)
    T.t(f"(function(){{var o={{v:0}};tl.to(o,{{v:10,duration:{ce - cs - 0.2:.3f},ease:'none',onUpdate:function(){{document.getElementById('obn').textContent=Math.round(o.v)}}}},{cs:.3f});}})();")
    T.kin(ce, v + w("l09", "and") - ce, "LIT", "flint and steel", pos="bl", color="red")
    T.kin(v + w("l09", "and"), end - v - w("l09", "and"), "THE NETHER", "walked straight in", pos="bl", color="red")
    t = end

    # ---- S9 portal stat
    v = t + 0.15
    end = T.say("l10", v) + 0.6
    d = end - t
    T.card(t, d, '<div class="paper"></div>', z=0)
    stat = ('<div class="stat"><div class="sth">PORTAL LIT ON THE SAME COURSE</div>'
            '<div class="srow"><span class="sl">this morning</span><div id="sb1" class="sbar bad"></div><span class="sv bad">1 of 8</span></div>'
            '<div class="srow"><span class="sl">by dinner</span><div id="sb2" class="sbar good"></div><span class="sv good">5 of 9</span></div>'
            '<div class="snote">portal_lava_lake · 0.95.52 → 0.95.53, both on Sep 30 · lit in 125–155 s</div>'
            '<div id="x4" class="x4">×4.4</div><div id="chk" class="chk">math: still checking…</div></div>')
    T.card(t, d, stat, z=5)
    T.t(f"tl.fromTo('#sb1',{{width:0}},{{width:{int(1000 * 1 / 8)},duration:0.6,ease:'power3.out'}},{v + w('l10', 'one'):.3f});")
    T.t(f"tl.fromTo('#sb2',{{width:0}},{{width:{int(1000 * 5 / 9)},duration:0.8,ease:'power3.out'}},{v + w('l10', 'five'):.3f});")
    T.t(f"tl.fromTo('#x4',{{opacity:0,scale:3,rotation:-20}},{{opacity:1,scale:1,rotation:-8,duration:0.3,ease:'power4.in'}},{v + w('l10', 'four'):.3f});")
    T.t(f"tl.fromTo('#chk',{{opacity:0}},{{opacity:1,duration:0.4}},{v + w('l10', 'check'):.3f});")
    t = end

    # ---- S10 numbers: the boardroom, zoom into the screen, the chart
    v = t + 0.3
    e11 = T.say("l11", v)
    v2 = e11 + 0.35
    end = T.say("l12", v2) + 0.7
    d = end - t
    # screen rect in the 1920x1080 painting
    sx, sy, sw, sh = 706, 110, 634, 392
    bars = DATA["commits"]
    mx = max(bars)
    bw = sw / len(bars)
    rects = "".join(f'<div class="cb" id="cb{i}" style="left:{i * bw + 1:.1f}px;width:{bw - 2:.1f}px;height:{max(1.5, 300 * c / mx):.1f}px"></div>'
                    for i, c in enumerate(bars))
    cum = DATA["cum_commits"]
    pts = " ".join(f"{i * bw + bw / 2:.1f},{330 - 300 * c / cum[-1]:.1f}" for i, c in enumerate(cum))
    months = "".join(f'<span style="left:{i * bw:.0f}px">{m}</span>' for i, m in
                     [(0, "MAR"), (3, "APR"), (7, "MAY"), (11, "JUN"), (16, "JUL"), (20, "AUG"), (25, "SEP")])
    jul = 18 * bw
    chart = (f'<div id="room" class="room"><img class="roomimg" src="assets/board.jpg">'
             f'<div class="screen" style="left:{sx}px;top:{sy}px;width:{sw}px;height:{sh}px">'
             f'<div class="ch-t">COMMITS PER WEEK · SINCE MARCH 17</div><div class="bars">{rects}</div>'
             f'<svg class="cum" width="{sw}" height="{sh}"><polyline id="cumline" points="{pts}" fill="none" stroke="#d8453a" stroke-width="4" '
             f'stroke-linejoin="round"/></svg><div class="months">{months}</div>'
             f'<div id="nothing" class="ann" style="left:{5 * bw:.0f}px;top:250px">almost nothing</div>'
             f'<div id="julm" class="julm" style="left:{jul:.0f}px"><span>JULY: AN A.I. AGENT<br>JOINS THE STAFF</span></div></div></div>')
    T.card(t, d, chart, z=2)
    # camera: start wide, push into the screen
    T.t(f"tl.fromTo('#room',{{scale:1,x:0,y:0}},{{scale:2.72,x:{(960 - (sx + sw / 2)) * 2.72:.0f},y:{(540 - (sy + sh / 2)) * 2.72 + 30:.0f},duration:2.2,ease:'power2.inOut'}},{t + 0.2:.3f});")
    T.t(f"tl.fromTo('.cb',{{scaleY:0}},{{scaleY:1,duration:0.35,stagger:0.05,ease:'power2.out'}},{t + 1.6:.3f});")
    T.t(f"tl.fromTo('#nothing',{{opacity:0}},{{opacity:1,duration:0.3}},{v + w('l11', 'almost'):.3f});")
    T.t(f"tl.fromTo('#julm',{{opacity:0,scaleY:0}},{{opacity:1,scaleY:1,duration:0.4}},{v + w('l11', 'july'):.3f});")
    T.t(f"tl.fromTo('.cb:nth-child(n+19)',{{backgroundColor:'#1f6f78'}},{{backgroundColor:'#d8453a',duration:0.3,stagger:0.03}},{v + w('l11', 'building'):.3f});")
    ln = v + w("l11", "look")
    T.t(f"tl.fromTo('#cumline',{{strokeDasharray:2000,strokeDashoffset:2000}},{{strokeDashoffset:0,duration:1.4,ease:'power1.inOut'}},{ln - 0.2:.3f});")
    # the numbers, on the screen, as big counters
    nums = ('<div class="bign"><div class="bn"><span id="n1">0</span><small>commits</small></div>'
            '<div class="bn"><span id="n2">0</span><small>releases</small></div>'
            '<div class="bn"><span id="n3">0</span><small>hours slept since july</small></div></div>')
    T.card(v2, end - v2, nums, z=10)
    T.t(f"tl.fromTo('.bign',{{opacity:0}},{{opacity:1,duration:0.3}},{v2:.3f});")
    for nid, val, wd in (("n1", DATA["total_commits"], "three"), ("n2", DATA["total_releases"], "one")):
        tt = v2 + w("l12", wd)
        T.t(f"(function(){{var o={{v:0}};tl.to(o,{{v:{val},duration:1.1,ease:'power2.out',onUpdate:function(){{document.getElementById('{nid}').textContent=Math.round(o.v).toLocaleString('en-US')}}}},{tt:.3f});}})();")
    T.t(f"tl.fromTo('.bn:nth-child(2)',{{opacity:0.2}},{{opacity:1,duration:0.2}},{v2 + w('l12', 'one'):.3f});")
    T.t(f"tl.fromTo('.bn:nth-child(3)',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.3}},{v2 + w('l12', 'slept'):.3f});")
    T.t(f"tl.fromTo('.bn:nth-child(1)',{{opacity:0.2}},{{opacity:1,duration:0.2}},{v2 + w('l12', 'three'):.3f});")
    T.t(f"tl.fromTo('#room',{{filter:'brightness(1)'}},{{filter:'brightness(0.35)',duration:0.4}},{v2 - 0.1:.3f});")
    t = end

    # ---- S11 next: the dragon
    v = t + 0.3
    end = T.say("l13", v) + 0.6
    T.img("assets/dragon.jpg", t, end - t, kb=(1.0, 1.12), origin="50% 30%")
    T.card(t, end - t, '<div class="vign"></div>', z=5)
    T.kin(v + w("l13", "beat"), w("l13", "unbothered") - w("l13", "beat"), "NEXT: THE END", "eyes of ender · the stronghold · the dragon", pos="tl", color="teal")
    T.kin(v + w("l13", "unbothered"), end - v - w("l13", "unbothered"), "DRAGON STATUS: UNBOTHERED", "for the moment", pos="bl", color="red")
    T.stamp(v + w("l13", "bother"), end - v - w("l13", "bother"), "TO BE BOTHERED", x=1350, y=320, rot=-8, size=80)
    t = end

    # ---- S12 close
    v = t + 0.4
    ve = T.say("l14", v)
    end = ve + 4.2
    d = end - t
    T.card(t, d, '<div class="paper dark"></div>', z=0)
    close = (f'<div class="closebox"><div id="cl-badge">{LOGO}</div><div id="cl-lab" class="labname big">3NDETZ LABS</div>'
             '<div id="cl-t" class="closet">unionclef</div><div id="cl-u" class="closeu">github.com/3ndetz/unionclef</div>'
             '<div id="cl-f" class="closef">open source · GPL-3.0 · Minecraft 1.21 · Fabric · MCP + Python</div></div>'
             '<div id="cl-credit" class="credit">Music: “Hep Cats” by Kevin MacLeod (incompetech.com), licensed under CC BY 4.0</div>')
    T.card(t, d, close, z=5)
    T.t(f"tl.fromTo('#cl-badge',{{scale:0,rotation:180}},{{scale:1,rotation:0,duration:0.8,ease:'back.out(1.5)'}},{t + 0.1:.3f});")
    T.t(f"tl.fromTo('#cl-lab',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.5}},{v + w('l14', 'endets') - 0.1:.3f});")
    T.t(f"tl.fromTo(['#cl-t','#cl-u','#cl-f'],{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.4,stagger:0.2}},{v + w('l14', 'open'):.3f});")
    T.stamp(v + w("l14", "invest"), end - v - w("l14", "invest"), "INVEST ANYWAY", x=1480, y=900, rot=-9, size=80)
    T.t(f"tl.fromTo('#cl-credit',{{opacity:0}},{{opacity:1,duration:0.6}},{ve + 0.3:.3f});")
    t = end

    total = t
    # film grain + gate weave over everything
    T.card(0, total, '<div id="grain" class="grain"></div><div class="edgevig"></div>', z=90)
    T.t(f"tl.to('#grain',{{backgroundPosition:'173px 97px',duration:0.08,repeat:{int(total / 0.16)},yoyo:true,ease:'steps(1)'}},0);")
    return page(total, "\n".join(T.parts), "\n".join(T.tw)), total, T


LOGO = ('<svg width="170" height="170" viewBox="0 0 200 200"><defs><radialGradient id="lgg" cx="40%" cy="35%">'
        '<stop offset="0" stop-color="#fff6df"/><stop offset="1" stop-color="#e9d9b4"/></radialGradient></defs>'
        '<circle cx="100" cy="100" r="94" fill="url(#lgg)" stroke="#1d2a2f" stroke-width="6"/>'
        '<circle cx="100" cy="100" r="80" fill="none" stroke="#d8453a" stroke-width="3" stroke-dasharray="4 7"/>'
        '<g fill="none" stroke="#1f6f78" stroke-width="6"><ellipse cx="100" cy="100" rx="70" ry="24"/>'
        '<ellipse cx="100" cy="100" rx="70" ry="24" transform="rotate(60 100 100)"/>'
        '<ellipse cx="100" cy="100" rx="70" ry="24" transform="rotate(-60 100 100)"/></g>'
        '<g transform="translate(100 100)"><polygon points="0,-26 23,-13 0,0 -23,-13" fill="#6fbf4a"/>'
        '<polygon points="-23,-13 0,0 0,26 -23,13" fill="#8a5a33"/><polygon points="23,-13 0,0 0,26 23,13" fill="#6b4426"/>'
        '<polygon points="0,-26 23,-13 0,0 -23,-13 0,-26" fill="none" stroke="#1d2a2f" stroke-width="2.5"/>'
        '<path d="M-23,-13 L-23,13 L0,26 L23,13 L23,-13 M0,0 L0,26" fill="none" stroke="#1d2a2f" stroke-width="2.5"/></g>'
        '<circle cx="170" cy="100" r="7" fill="#d8453a"/><circle cx="65" cy="40" r="7" fill="#d8453a"/></svg>')


def page(total: float, body: str, tweens: str) -> str:
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=1920, height=1080" />
<script src="https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js"></script>
<style>
@font-face {{ font-family: "Bebas"; src: url("assets/BebasNeue-Regular.ttf"); }}
@font-face {{ font-family: "Serif"; src: url("assets/DMSerifDisplay-Regular.ttf"); }}
@font-face {{ font-family: "SerifI"; src: url("assets/DMSerifDisplay-Italic.ttf"); }}
@font-face {{ font-family: "Mono"; src: url("assets/IBMPlexMono-Regular.ttf"); }}
@font-face {{ font-family: "MonoB"; src: url("assets/IBMPlexMono-Bold.ttf"); }}
@font-face {{ font-family: "Inter"; src: url("assets/Inter.ttf"); }}
* {{ margin:0; padding:0; box-sizing:border-box; }}
html, body {{ width:1920px; height:1080px; overflow:hidden; background:#1d2a2f; }}
body {{ font-family:"Inter", sans-serif; color:#1d2a2f; }}
#root {{ position:relative; width:100%; height:100%; overflow:hidden; background:#1d2a2f; }}
.clip {{ position:absolute; inset:0; }}
.card {{ pointer-events:none; }}
.vid {{ width:1920px; height:1080px; object-fit:cover; transform-origin:50% 50%; }}
.bgimg {{ position:absolute; inset:0; width:1920px; height:1080px; object-fit:cover; }}
.topshade {{ position:absolute; left:0; right:0; top:0; height:720px; background:linear-gradient(180deg, rgba(20,28,31,0.88) 0%, rgba(20,28,31,0.7) 55%, rgba(20,28,31,0) 100%); }}
.vign {{ position:absolute; inset:0; background:radial-gradient(ellipse at 50% 45%, rgba(0,0,0,0) 45%, rgba(20,16,10,0.55) 100%); }}
.edgevig {{ position:absolute; inset:0; background:radial-gradient(ellipse at 50% 50%, rgba(0,0,0,0) 62%, rgba(25,18,8,0.35) 100%); }}
.grain {{ position:absolute; inset:-200px; background:url("assets/grain.png"); opacity:0.07; mix-blend-mode:overlay; }}
.paper {{ position:absolute; inset:0; background:radial-gradient(circle at 30% 25%, #fbf3df 0%, #efe2c4 60%, #e2d0a8 100%); }}
.paper.dark {{ background:radial-gradient(circle at 50% 40%, #27383e 0%, #1d2a2f 55%, #141d20 100%); }}
.reel {{ position:absolute; top:34px; left:40px; font-family:"MonoB"; font-size:22px; letter-spacing:3px; color:#f3e9d2;
         background:rgba(29,42,47,0.8); padding:8px 16px; border-left:6px solid #d8453a; }}
.speed {{ position:absolute; top:36px; right:44px; font-family:"MonoB"; font-size:30px; color:#f3e9d2; background:#1d2a2f;
          padding:4px 16px; border:3px solid #f3e9d2; }}
/* title */
.titlebox {{ position:absolute; left:0; right:0; top:40px; display:flex; flex-direction:column; align-items:center; gap:6px; }}
.labname {{ font-family:"Serif"; font-size:64px; color:#f3e9d2; letter-spacing:10px; text-shadow:0 4px 0 #1d2a2f, 0 0 30px rgba(0,0,0,0.6); }}
.labname.big {{ font-size:110px; }}
.presents {{ font-family:"SerifI"; font-size:40px; color:#f3e9d2; text-shadow:0 3px 0 #1d2a2f; }}
.bigtitle {{ font-family:"Bebas"; font-size:230px; line-height:0.9; color:#d8453a; letter-spacing:14px;
             text-shadow:6px 6px 0 #1d2a2f, 0 0 50px rgba(0,0,0,0.5); -webkit-text-stroke:3px #f3e9d2; }}
.subline {{ font-family:"SerifI"; font-size:44px; color:#1d2a2f; background:#f3e9d2; padding:6px 26px; border:3px solid #1d2a2f; }}
/* checklist */
.ckbox {{ position:absolute; left:440px; top:60px; width:860px; background:rgba(243,233,210,0.95); border:5px solid #1d2a2f;
          box-shadow:14px 14px 0 rgba(29,42,47,0.85); padding:26px 34px; display:flex; flex-direction:column; gap:14px; }}
.ckhead {{ font-family:"MonoB"; font-size:30px; letter-spacing:2px; color:#1f6f78; border-bottom:3px solid #1d2a2f; padding-bottom:10px; }}
.ckrow {{ font-family:"Serif"; font-size:62px; display:flex; align-items:center; gap:22px; padding:2px 10px; }}
.ckmark {{ font-family:"Inter"; font-weight:800; font-size:52px; width:52px; text-align:center; }}
.ckrow.ok .ckmark {{ color:#2f7d5b; }}
.ckrow.no {{ color:#d8453a; }}
/* stamp */
.stampbox {{ position:absolute; width:0; height:0; display:flex; align-items:center; justify-content:center; }}
.stamp {{ font-family:"Bebas"; white-space:nowrap; letter-spacing:6px; padding:6px 28px 0; border:9px solid; border-radius:14px;
          background:rgba(243,233,210,0.92); box-shadow:0 0 0 6px rgba(243,233,210,0.92), 10px 12px 0 6px rgba(29,42,47,0.55); }}
/* levers */
.tvframe {{ position:absolute; background:#1d2a2f; border-radius:34px; box-shadow:16px 16px 0 rgba(29,42,47,0.3); border:6px solid #d8453a; }}
.tvvid {{ position:absolute; left:1010px; top:170px; width:820px; height:462px; object-fit:cover; border-radius:18px; }}
.box {{ position:absolute; left:80px; top:150px; width:640px; background:#1d2a2f; color:#f3e9d2; padding:26px 32px; border-radius:6px;
        box-shadow:12px 12px 0 #d8453a; }}
.box-k {{ font-family:"MonoB"; font-size:32px; letter-spacing:4px; color:#e3a73a; }}
.box-t {{ font-family:"Bebas"; font-size:170px; line-height:1; }}
.box-s {{ font-family:"MonoB"; font-size:25px; color:#c9d4d3; }}
.botlab {{ position:absolute; left:1010px; top:655px; color:#1d2a2f; font-size:36px; }}
.pipe {{ position:absolute; left:730px; width:280px; height:14px; background:#1f6f78; transform-origin:0 50%; }}
.pipe span {{ position:absolute; left:6px; top:-44px; font-family:"MonoB"; font-size:27px; color:#1d2a2f; white-space:nowrap; }}
.pipe.p1 {{ top:300px; }} .pipe.p2 {{ top:430px; background:#e3a73a; }}
.chips {{ position:absolute; left:80px; top:760px; width:1740px; display:flex; flex-wrap:wrap; gap:14px; }}
.chip {{ font-family:"MonoB"; font-size:38px; background:#1d2a2f; color:#f3e9d2; padding:10px 20px; border-radius:40px; }}
.levers {{ position:absolute; left:80px; top:560px; font-family:"Bebas"; font-size:190px; line-height:1; color:#d8453a; }}
/* kinetic */
.kin {{ position:absolute; padding:10px 30px 4px 26px; background:#f3e9d2; border-left:14px solid #d8453a; box-shadow:10px 10px 0 rgba(29,42,47,0.85); max-width:1500px; }}
.kin.teal {{ border-left-color:#1f6f78; }}
.kin.bl {{ left:60px; bottom:80px; }} .kin.tl {{ left:60px; top:70px; }}
.kin-s {{ font-family:"MonoB"; font-size:30px; letter-spacing:2px; color:#1f6f78; text-transform:uppercase; }}
.kin-b {{ font-family:"Bebas"; font-size:124px; line-height:1; color:#1d2a2f; }}
/* grid */
.gridhead {{ position:absolute; left:80px; top:60px; font-family:"MonoB"; font-size:36px; letter-spacing:4px; color:#1f6f78; }}
.grid {{ position:absolute; left:80px; top:130px; width:1260px; display:grid; grid-template-columns:repeat(4, 1fr); gap:14px; }}
.tile {{ height:150px; background:#fbf3df; border:4px solid #1d2a2f; display:flex; align-items:center; justify-content:space-between;
         padding:0 20px; font-family:"MonoB"; font-size:30px; }}
.tile .tk {{ font-family:"Inter"; font-weight:800; font-size:40px; }}
.tile.ghost {{ border-style:dashed; border-color:#d8453a; color:#d8453a; background:transparent; }}
.score {{ position:absolute; right:60px; top:300px; font-family:"Bebas"; font-size:360px; line-height:1; color:#2f7d5b; }}
.score .of {{ color:#1d2a2f; font-size:170px; }}
/* halves */
.half {{ position:absolute; top:190px; width:930px; height:523px; object-fit:cover; border:6px solid #f3e9d2; }}
.half.left {{ left:20px; }} .half.right {{ left:970px; }}
.halflab {{ position:absolute; top:100px; width:930px; text-align:center; font-family:"Bebas"; font-size:80px; letter-spacing:6px; }}
.halflab.left {{ left:20px; }} .halflab.right {{ left:970px; }}
.halfcap {{ position:absolute; top:735px; left:0; right:0; text-align:center; font-family:"Mono"; font-size:26px; color:#c9d4d3; }}
.bad {{ color:#ff7a6a; }} .good {{ color:#7fe0a6; }}
.dmg {{ position:absolute; left:300px; top:740px; width:1320px; background:#f3e9d2; border:5px solid #1d2a2f; padding:16px 30px;
        box-shadow:12px 12px 0 rgba(0,0,0,0.5); }}
.dl {{ font-family:"MonoB"; font-size:26px; letter-spacing:3px; color:#1f6f78; text-transform:uppercase; }}
.dmgrow {{ display:flex; align-items:center; gap:22px; height:74px; }}
.dbar {{ height:52px; }} .dbar.bad {{ background:#d8453a; }} .dbar.good {{ background:#2f7d5b; }}
.dn {{ font-family:"Bebas"; font-size:84px; }} .dn.bad {{ color:#d8453a; }} .dn.good {{ color:#2f7d5b; }}
.du {{ font-family:"MonoB"; font-size:28px; color:#1d2a2f; }}
.dk {{ font-family:"SerifI"; font-size:40px; width:220px; }}
/* ladder */
.ladder {{ position:absolute; left:60px; top:60px; background:#f3e9d2; border:5px solid #1d2a2f; padding:14px 28px 8px;
           box-shadow:10px 10px 0 rgba(29,42,47,0.85); }}
.lk {{ font-family:"MonoB"; font-size:24px; letter-spacing:3px; color:#1f6f78; }}
.lrow {{ display:flex; align-items:center; gap:20px; font-family:"Bebas"; font-size:100px; line-height:1.05; }}
.arr {{ color:#d8453a; font-family:"Inter"; font-size:60px; }}
.rtime {{ background:#d8453a; color:#f3e9d2; padding:0 18px; margin-left:12px; }}
.obs {{ position:absolute; right:60px; top:60px; background:#1d2a2f; color:#f3e9d2; padding:10px 30px; border:5px solid #b36cff;
        font-family:"Bebas"; display:flex; align-items:baseline; gap:12px; }}
#obn {{ font-size:140px; line-height:1; color:#c9a0ff; }} .obt {{ font-size:48px; }}
/* stat */
.stat {{ position:absolute; left:110px; top:110px; width:1700px; }}
.sth {{ font-family:"Bebas"; font-size:150px; color:#1d2a2f; }}
.srow {{ display:flex; align-items:center; gap:34px; height:230px; }}
.sl {{ font-family:"SerifI"; font-size:70px; width:420px; text-align:right; }}
.sbar {{ height:140px; }} .sbar.bad {{ background:#d8453a; }} .sbar.good {{ background:#2f7d5b; }}
.sv {{ font-family:"Bebas"; font-size:150px; white-space:nowrap; }} .sv.bad {{ color:#d8453a; }} .sv.good {{ color:#2f7d5b; }}
.snote {{ font-family:"Mono"; font-size:28px; color:#4a5a5f; margin-top:30px; }}
.x4 {{ position:absolute; right:0px; top:600px; font-family:"Bebas"; font-size:230px; color:#1f6f78; border:10px solid #1f6f78; padding:0 30px; border-radius:16px; }}
.chk {{ position:absolute; left:0px; top:760px; font-family:"SerifI"; font-size:54px; color:#4a5a5f; }}
/* room */
.room {{ position:absolute; inset:0; transform-origin:50% 50%; }}
.roomimg {{ position:absolute; inset:0; width:1920px; height:1080px; }}
.screen {{ position:absolute; overflow:hidden; }}
.ch-t {{ position:absolute; left:14px; top:10px; font-family:"MonoB"; font-size:16px; letter-spacing:1px; color:#1d2a2f; }}
.bars {{ position:absolute; left:0; bottom:40px; width:100%; height:320px; }}
.cb {{ position:absolute; bottom:0; background:#1f6f78; transform-origin:50% 100%; }}
.cum {{ position:absolute; left:0; top:0; }}
.months {{ position:absolute; left:0; bottom:12px; width:100%; height:22px; font-family:"MonoB"; font-size:14px; color:#1d2a2f; }}
.months span {{ position:absolute; }}
.ann {{ position:absolute; font-family:"SerifI"; font-size:22px; color:#d8453a; }}
.julm {{ position:absolute; top:40px; bottom:40px; border-left:3px dashed #d8453a; transform-origin:50% 0; }}
.julm span {{ position:absolute; left:-230px; top:10px; width:220px; text-align:right; font-family:"MonoB"; font-size:14px; line-height:1.2; color:#d8453a; }}
.bign {{ position:absolute; inset:0; background:rgba(20,28,31,0.72); display:flex; flex-direction:column; justify-content:center; align-items:center; gap:10px; }}
.bn {{ display:flex; align-items:baseline; gap:26px; font-family:"Bebas"; color:#f3e9d2; }}
.bn span {{ font-size:190px; line-height:0.95; color:#e3a73a; text-shadow:6px 6px 0 #1d2a2f; min-width:520px; text-align:right; }}
.bn small {{ font-size:74px; letter-spacing:4px; width:760px; }}
/* close */
.closebox {{ position:absolute; left:0; right:0; top:70px; display:flex; flex-direction:column; align-items:center; gap:10px; }}
.closet {{ font-family:"Bebas"; font-size:260px; line-height:1; color:#d8453a; letter-spacing:12px; }}
.closeu {{ font-family:"MonoB"; font-size:58px; color:#f3e9d2; }}
.closef {{ font-family:"Mono"; font-size:34px; color:#c9d4d3; }}
.credit {{ position:absolute; left:0; right:0; bottom:40px; text-align:center; font-family:"Mono"; font-size:26px; color:#8fa3a6; }}
</style>
</head>
<body>
<div id="root" data-composition-id="main" data-start="0" data-duration="{total:.3f}" data-width="1920" data-height="1080">
{body}
</div>
<script>
const tl = gsap.timeline({{ paused: true }});
{tweens}
window.__timelines["main"] = tl;
</script>
</body>
</html>
"""


def mix(T: TL, total: float, silent: Path, out: Path):
    """Voice lines at their times (normalised), music bed ducked under them, muxed onto the render."""
    inputs, filt, labels = [], [], []
    for i, (line, t0) in enumerate(T.voice):
        inputs += ["-i", str(HERE / "voice" / "pick" / f"{line}.wav")]
        ms = int(t0 * 1000)
        filt.append(f"[{i + 1}:a]aresample=48000,loudnorm=I=-16:TP=-1.5:LRA=7,adelay={ms}|{ms},apad[v{i}]")
        labels.append(f"[v{i}]")
    n = len(T.voice)
    for j, ts in enumerate(T.sfx):
        inputs += ["-i", str(WORK / "thud.wav")]
        ms = int(ts * 1000)
        filt.append(f"[{n + j + 1}:a]aresample=48000,volume=0.55,adelay={ms}|{ms},apad[s{j}]")
    sfx = "".join(f"[s{j}]" for j in range(len(T.sfx)))
    filt.append(f"{sfx}amix=inputs={len(T.sfx)}:normalize=0:duration=longest,atrim=0:{total:.3f}[sfx]")
    filt.append(f"{''.join(labels)}amix=inputs={n}:normalize=0:duration=longest,atrim=0:{total:.3f},"
                f"acompressor=threshold=-18dB:ratio=3:attack=5:release=120,"
                f"highpass=f=70,equalizer=f=3000:t=q:w=1:g=2,asplit=2[vo][key]")
    mi = n + len(T.sfx) + 1
    fade0 = total - 3.5
    filt.append(f"[{mi}:a]aresample=48000,atrim=0:{total:.3f},volume=0.30,afade=t=in:d=0.4,"
                f"afade=t=out:st={fade0:.2f}:d=3.5[mus]")
    filt.append("[mus][key]sidechaincompress=threshold=0.03:ratio=7:attack=40:release=450:makeup=1[duck]")
    filt.append("[vo][duck][sfx]amix=inputs=3:normalize=0,loudnorm=I=-15:TP=-1.5:LRA=9,aresample=48000[aout]")
    cmd = (["ffmpeg", "-v", "error", "-y", "-i", str(silent)] + inputs + ["-i", str(MUSIC)] +
           ["-filter_complex", ";".join(filt), "-map", "0:v", "-map", "[aout]", "-c:v", "copy",
            "-c:a", "aac", "-b:a", "192k", "-shortest", str(out)])
    subprocess.run(cmd, check=True)


def main() -> int:
    if "--mix" not in sys.argv and "--compose" not in sys.argv and VID.exists():
        shutil.rmtree(VID)
    VID.mkdir(parents=True, exist_ok=True)
    doc, total, T = build()
    (HF / "index.html").write_text(doc, encoding="utf-8")
    (HERE / "timeline.json").write_text(json.dumps({"total": total, "voice": T.voice}, indent=1), encoding="utf-8")
    print(f"composition: {total:.1f} s, {len(T.voice)} voice lines")
    if "--compose" in sys.argv:
        return 0
    OUT.mkdir(exist_ok=True)
    silent = OUT / "pitch-silent.mp4"
    npx = shutil.which("npx") or "npx"
    if "--mix" not in sys.argv:
        r = subprocess.run([npx, "--yes", HF_CLI, "render", "--output", str(silent)], cwd=HF)
        if r.returncode != 0 or not silent.exists():
            print("render failed")
            return 1
    final = OUT / "pitch.mp4"
    mix(T, total, silent, final)
    print(f"final: {final} ({final.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
