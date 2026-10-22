"""Cave Johnson field report for release 0.95.63: cut footage, compose in HyperFrames, mix, render.

    python build_cj.py            # cut + compose + render + mix -> out/cj-0.95.63-clean.mp4
    python build_cj.py --compose  # write hf/index.html only
    python build_cj.py --mix      # remix audio onto the last silent render

Copied from cave-johnson-0.95.62 (see docs/VIDEO_REPORTS.md, "Cave Johnson films"). The picture is rendered silent
by HyperFrames, voice and music are mixed with ffmpeg afterwards (music ducked under the voice with a sidechain
compressor; stamp thuds only where nobody is talking; static gain + limiter, no loudnorm on the mix).
Subtitles: add_subs.py. Inputs: voice/pick/lXX.wav + words.json (tts_wrap.py), footage/ (copies, see FOOT),
work/Hep_Cats.mp3 (Kevin MacLeod, incompetech.com, CC BY 4.0), hf/assets (fonts, grain).
"""
from __future__ import annotations

import html
import json
import shutil
import subprocess
import sys
from pathlib import Path

VER = "0.95.63"
HERE = Path(__file__).resolve().parent
HF = HERE / "hf"
VID = HF / "videos"
OUT = HERE / "out"
WORK = HERE / "work"
FOOTDIR = HERE / "footage"
HF_CLI = "hyperframes@0.8.30"
MUSIC = WORK / "Hep_Cats.mp3"
WORDS = json.loads((HERE / "words.json").read_text(encoding="utf-8"))

# key -> (file in footage/, ffmpeg crop). Recordings are 854x480 (~15 fps). Footage = copies of deploy/runner/artifacts/:
#   nav_water_pillar.mp4    = 20261022-055702/nav_water_pillar/  (this build: looks down the flooded shaft 0-1.8,
#                             rises 1.9-2.4, cobblestone placed in the source cell ~2.5, stands on it after)
#   nav_grid_water_roof.mp4 = 20261022-055702/nav_grid_water_roof/ (this build: dives under the sealed roof 2-8, up 8-11)
#   drown2-2-before.mp4     = water-takeover-ab-20261022/ (0.95.62, repro-drown-pre: creeper + zombie in view 223.9,
#                             blown up 229.3 ("Loading terrain" 229.3-229.9); one heart 305-306.8, slain by Zombie 306.9)
#   drown2-1-after.mp4      = water-takeover-ab-20261022/ (this build, same checkpoint: skeleton 504, two spiders
#                             505.5-510, spider killed 510.5; no deaths, lowest health 11.2 at ~517)
HUDLESS = "crop=iw*0.86:ih*0.86:(iw-iw*0.86)/2:(ih-ih*0.86)*0.3"
FOOT = {
    "pillar": ("nav_water_pillar.mp4", "crop=640:360:107:15"),   # drops the bench chat (y 377-436) and the hotbar
    "roof": ("nav_grid_water_roof.mp4", HUDLESS),
    "before": ("drown2-2-before.mp4", HUDLESS),
    "beforefull": ("drown2-2-before.mp4", "crop=iw:ih:0:0"),     # keeps the hearts
    "after": ("drown2-1-after.mp4", HUDLESS),
}
# status-line strips: key -> (footage key, second, crop) -- one chat line, shown x3 on a dark bar
STRIPS = {
    "s_creeper": ("before", 229.55, "174:10:2:404"),   # "tester1 was blown up by Creeper"
    "s_zombie": ("before", 307.1, "142:10:2:386"),     # "tester1 was slain by Zombie"
}
USED: list[dict] = []


def vdur(line: str) -> float:
    p = HERE / "voice" / "pick" / f"{line}.wav"
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)],
                       capture_output=True, text=True)
    return float(r.stdout.strip())


def w(line: str, word: str, n: int = 1) -> float:
    k = 0
    for t, s, _e in WORDS[line]:
        if t.lower().strip(".,?!") == word.lower():
            k += 1
            if k == n:
                return s
    raise KeyError(f"{line}: no '{word}' #{n}")


def cut(key: str, foot: str, start: float, dur: float, speed: float) -> str:
    name, crop = FOOT[foot]
    src = FOOTDIR / name
    dst = VID / f"{key}.mp4"
    vf = (f"setpts=PTS/{speed},{crop},"
          f"scale=1920:1080:flags=lanczos,fps=30,eq=saturation=1.08:contrast=1.04")
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.3f}", "-t", f"{dur * speed + 0.3:.3f}", "-i", str(src),
           "-vf", vf, "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
           "-t", f"{dur + 0.1:.3f}", str(dst)]
    if "--compose" not in sys.argv or not dst.exists():
        subprocess.run(cmd, check=True)
    USED.append({"key": key, "file": name, "src_in": round(start, 2), "src_out": round(start + dur * speed, 2),
                 "speed": speed})
    return dst.name


def still(key: str, foot: str, at: float) -> str:
    name, crop = FOOT[foot]
    dst = VID / f"{key}.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.3f}", "-i", str(FOOTDIR / name), "-frames:v", "1",
                    "-vf", f"{crop},scale=1920:1080:flags=lanczos", str(dst)],
                   check=True)
    return dst.name


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

    def clip(self, foot: str, t0: float, dur: float, frm: float, speed: float = 1.0, z: int = 1,
             zoom=(1.0, 1.06), cls: str = "vid", badge: bool = True, style: str = "") -> str:
        vid = self.id("v")
        name = cut(vid, foot, frm, dur, speed)
        self.parts.append(f'<video id="{vid}" class="clip {cls}" style="z-index:{z};{style}" src="videos/{name}" muted '
                          f'playsinline data-start="{t0:.3f}" data-duration="{dur:.3f}"></video>')
        if zoom:
            self.t(f"tl.fromTo('#{vid}',{{scale:{zoom[0]}}},{{scale:{zoom[1]},duration:{dur:.3f},ease:'none'}},{t0:.3f});")
        if badge and speed != 1 and cls == "vid":
            self.card(t0, dur, f'<div class="speed">x{speed:g}</div>', z=z + 30)
        return vid

    def img(self, src: str, t0: float, dur: float, z: int = 0, kb=(1.0, 1.08), origin="50% 50%", cls="bgimg",
            style="") -> str:
        i = self.id("i")
        self.parts.append(f'<div class="clip" style="z-index:{z}" data-start="{t0:.3f}" data-duration="{dur:.3f}">'
                          f'<img id="{i}" class="{cls}" src="{src}" style="transform-origin:{origin};{style}"></div>')
        if cls.startswith("strip"):
            self.t(f"tl.fromTo('#{i}',{{opacity:0,y:-12}},{{opacity:1,y:0,duration:0.3}},{t0:.3f});")
        if kb:
            self.t(f"tl.fromTo('#{i}',{{scale:{kb[0]}}},{{scale:{kb[1]},duration:{dur:.3f},ease:'sine.inOut'}},{t0:.3f});")
        return i

    def card(self, t0: float, dur: float, inner: str, z: int = 20, cls: str = "") -> str:
        c = self.id("c")
        self.parts.append(f'<div id="{c}" class="clip card {cls}" style="z-index:{z}" data-start="{t0:.3f}" '
                          f'data-duration="{dur:.3f}">{inner}</div>')
        return c

    def kin(self, t0: float, dur: float, big: str, small: str = "", pos: str = "bl", color: str = "red", z: int = 40):
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

    def ring(self, t0: float, dur: float, x: int, y: int, r: int, word: str, z: int = 55, up: bool = False):
        g = self.id("r")
        upc = " up" if up else ""
        self.card(t0, dur, f'<div id="{g}" class="ring{upc}" style="left:{x - r}px;top:{y - r}px;width:{2 * r}px;height:{2 * r}px">'
                           f'<span>{html.escape(word)}</span></div>', z=z)
        self.t(f"tl.fromTo('#{g}',{{scale:1.8,opacity:0}},{{scale:1,opacity:1,duration:0.35,ease:'back.out(2)'}},{t0:.3f});")

    def say(self, line: str, t0: float):
        self.voice.append((line, t0))
        return t0 + vdur(line)


def strip_w(key: str) -> str:
    """Display width so every strip shows text at the same height (~40 px), capped at 1700 px."""
    cw, ch = (int(x) for x in STRIPS[key][2].split(":")[:2])
    return f"width:{min(1700, round(cw * 40 / ch))}px"


def strip(key: str) -> str:
    foot, at, crop = STRIPS[key]
    name, _c = FOOT[foot]
    dst = VID / f"{key}.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.3f}", "-i", str(FOOTDIR / name), "-frames:v", "1",
                    "-vf", f"crop={crop},scale=iw*3:ih*3:flags=lanczos", str(dst)], check=True)
    return dst.name


# Source seconds, read off frames (review/*.png).
PIL_RISE = 1.85        # still looking down the flooded shaft until here; the walls shrink 1.9-2.4 (it rises)
PIL_PLACED = 2.5       # cobblestone in the source cell, centre of frame
ROOF_IN = 2.0          # dives under the sealed roof; up at 8-11, at the dry goal 12
B_CREEPER_END = 229.25  # last frame before "Loading terrain" (blown up by the creeper)
B_ZOMBIE_END = 306.85   # one heart, zombie behind; respawn screen from 306.9
A_FIGHT = 504.0         # skeleton, then two spiders 505.5-510, one killed 510.5


def build():
    T = TL()

    # ---- S0 hook: the payoff. Bottom of the flooded shaft -> rises -> block under itself -> stands on it.
    v = 0.3
    ve = T.say("l01", v)
    end = ve + 1.2
    sp = 0.5
    t_swim = v + w("l01", "swims")
    frm = PIL_RISE - t_swim * sp
    t_block = t_swim + (PIL_PLACED - PIL_RISE) / sp
    t_old = v + w("l01", "the", 2) - 0.1
    T.card(0, end, '<div class="paper dark"></div>', z=0)
    T.clip("pillar", 0.0, end, frm=frm, speed=sp, zoom=None, badge=False)
    T.card(0, end, '<div class="speed" style="top:110px">x0.5</div>', z=46)
    T.kin(0.4, t_old - 0.4, "OUT OF A FLOODED SHAFT", f"course nav_water_pillar · {VER} · pass · now in the suite",
          pos="tl", color="teal")
    T.ring(t_block, end - t_block, 957, 690, 290, "THE BLOCK UNDER ITSELF", up=True)
    T.kin(t_old, end - t_old, "OLD INPUT: CROUCH + JUMP", "earlier builds · crouching sinks you · it sat at a bank",
          pos="bl", color="red")
    T.card(0, end, f'<div class="reel">3NDETZ LABS &middot; FIELD REPORT &middot; RELEASE {VER}</div>', z=45)
    T.t("tl.fromTo('.reel',{opacity:0},{opacity:1,duration:0.4},0.1);")
    T.stamp(ve + 0.1, end - ve - 0.1, "UP, NOT DOWN", x=1450, y=330, rot=-8, size=100)
    t = end

    # ---- S1 the crate: another session's finished work, found uncommitted, rebuilt, tested, shipped.
    v = t + 0.3
    ve = T.say("l02", v)
    end = ve + 1.4
    d = end - t
    T.card(t, d, '<div class="paper"></div>', z=0)
    crate = ('<div class="crate"><div class="crh">FOUND IN THE BACK ROOM</div>'
             '<div class="crs">work from another session (UNIONCLEF-HARD) &middot; 2026-10-02 to 10-04 &middot; never committed</div>'
             '<ul class="crl"><li id="cr1">pillar in water: swim up, not crouch</li>'
             '<li id="cr2">air escape: price dig vs swim, start in time</li>'
             '<li id="cr3">keep one water bucket, empty the extras</li>'
             '<li id="cr4">block scanner reads chunk copies</li></ul></div>')
    T.card(t, d, crate, z=5)
    T.t(f"tl.fromTo('.crate',{{opacity:0,y:60,rotation:-2}},{{opacity:1,y:0,rotation:-1,duration:0.5,ease:'power3.out'}},{t + 0.1:.3f});")
    T.t(f"tl.fromTo('.crl li',{{opacity:0,x:-30}},{{opacity:1,x:0,duration:0.25,stagger:0.25}},{v + w('l02', 'box') - 0.1:.3f});")
    for word, txt, x, y, rot, col in (("rebuilt", "REBUILT", 1500, 300, -10, "#1f6f78"),
                                      ("tested", "RE-TESTED", 1540, 470, 6, "#1f6f78"),
                                      ("shipped", "SHIPPED", 1500, 640, -6, "#2f7d5b")):
        ts = v + w("l02", word) - 0.05
        T.stamp(ts, end - ts, txt, x=x, y=y, rot=rot, size=72, color=col)
    T.stamp(ve + 0.15, end - ve - 0.15, "FINDERS KEEPERS", x=1180, y=900, rot=-5, size=96)
    t = end

    # ---- S2 water: under a sealed roof it swims to the dry goal (roof intact), then the one-bucket rule (no clip).
    v = t + 0.3
    ve = T.say("l03", v)
    end = ve + 1.3
    t_bk = v + w("l03", "and", 2) - 0.1
    sp = 2.0
    T.clip("roof", t, t_bk - t, frm=ROOF_IN, speed=sp, zoom=(1.0, 1.05), badge=True)
    T.kin(t + 0.3, t_bk - t - 0.3, "UNDER A SEALED ROOF", f"course nav_grid_water_roof · {VER} · swims, roof left intact · pass",
          pos="bl", color="teal")
    T.card(t_bk, end - t_bk, '<div class="paper"></div>', z=3)
    bucket = ('<div class="bk"><div class="bkh">WATER BUCKETS</div>'
              '<div class="bkrow"><span class="bki keep">1</span><span class="bkt">kept, filled</span></div>'
              '<div class="bkrow"><span class="bki dump">+</span><span class="bkt">extras emptied on a solid face</span></div>'
              '<div class="bkn">used to empty every one at its own feet &middot; no clip of this one: release notes</div></div>')
    T.card(t_bk, end - t_bk, bucket, z=5)
    T.t(f"tl.fromTo('.bk .bkrow',{{opacity:0,x:-40}},{{opacity:1,x:0,duration:0.3,stagger:0.35}},{t_bk + 0.1:.3f});")
    T.stamp(ve + 0.12, end - ve - 0.12, "HOARDING: CURED", x=1400, y=830, rot=-7, size=90)
    t = end

    # ---- S3 same checkpoint, before then after. Before: creeper, then one heart and a zombie. After: alive.
    v = t + 0.3
    ve = T.say("l04", v)
    end = ve + 1.4
    t_z = v + w("l04", "then") - 0.1
    t_af = v + w("l04", "after") - 0.1
    spb = 1.5   # from 223.7 (zombie and creeper both in view) to the blast
    T.clip("before", t, t_z - t, frm=B_CREEPER_END - (t_z - t) * spb, speed=spb, zoom=(1.0, 1.06), badge=True)
    T.kin(t + 0.2, t_z - t - 0.2, "BEFORE · 0.95.62", "same checkpoint, 7 min before an old drowning", pos="tl", color="red")
    T.img("videos/" + strip("s_creeper"), t_z - 1.0, 1.0, z=30, kb=None, cls="strip low", style=strip_w("s_creeper"))
    spz = 0.5
    # framed (1600x900 at 160,40) so the hearts row sits above the subtitles: source (341,445) -> (799,873)
    T.card(t_z, t_af - t_z, '<div class="paper dark"></div>', z=2)
    T.clip("beforefull", t_z, t_af - t_z, frm=B_ZOMBIE_END - (t_af - t_z) * spz, speed=spz, z=3, zoom=None, cls="framed", badge=False)
    T.card(t_z, t_af - t_z, '<div class="speed">x0.5</div>', z=33)
    T.ring(t_z + 0.1, t_af - t_z - 0.1, 799, 873, 60, "ONE HEART", up=True)
    T.kin(t_z + 0.05, t_af - t_z - 0.05, "SECOND LIFE", "same run, after the creeper · one heart · empty pockets", pos="tl", color="red")
    T.img("videos/" + strip("s_zombie"), t_af - 0.75, 0.75, z=30, kb=None, cls="strip", style=strip_w("s_zombie"))
    sp = 2.0
    T.clip("after", t_af, end - t_af, frm=A_FIGHT, speed=sp, z=4, zoom=(1.0, 1.06), badge=True)
    T.kin(t_af + 0.05, end - t_af - 0.05, f"AFTER · {VER}", "same checkpoint · skeleton, two spiders · no deaths",
          pos="tl", color="teal")
    T.card(t, end - t, '<div class="botcap">same checkpoint &middot; separate runs, not a race</div>', z=35)
    T.stamp(ve + 0.15, end - ve - 0.15, "STILL VERTICAL", x=1420, y=720, rot=-8, size=96)
    t = end

    # ---- S4 numbers
    v = t + 0.25
    ve = T.say("l05", v)
    end = ve + 0.9
    T.card(t, end - t, '<div class="paper"></div>', z=0)
    stat = ('<div class="stat2"><div class="sth">MEASURED ON THE BENCH</div>'
            f'<div class="shead"><span class="sl"></span><span class="sc bad">0.95.62</span><span class="sc good">{VER}</span></div>'
            '<div class="srow"><span class="sl">nav courses, three in water</span>'
            '<span class="sc"><b id="bv1" class="bad">&mdash;</b></span>'
            '<span class="sc"><div id="a1" class="bar good"></div><b id="av1" class="good">9 / 9</b><i id="an1" class="snote2">pass</i></span></div>'
            '<div class="srow"><span class="sl">same checkpoint: deaths</span>'
            '<span class="sc"><div id="b2" class="bar bad"></div><b id="bv2" class="bad">2</b><i id="bn2" class="snote2">1 of 2 runs</i></span>'
            '<span class="sc"><b id="av2" class="good">0</b><i id="an2" class="snote2">in 2 runs</i></span></div>'
            '<div class="srow"><span class="sl">15-min run on its own: deaths</span>'
            '<span class="sc"><b id="bv3" class="bad">&mdash;</b></span>'
            '<span class="sc"><b id="av3" class="good">0</b><i id="an3" class="snote2">lowest health 17.3</i></span></div>'
            '<div class="snote" id="sn1">also pass: portal_lava_lake, craft_at_distant_table &middot; before-side lowest health 1.8</div>'
            '<div class="snote" id="sn2"><b>the drowning did not recur in either build: not claimed as a fix</b>'
            '<br>no speed or FPS gain claimed &middot; the game is not beaten</div></div>')
    T.card(t, end - t, stat, z=5)
    T.t(f"tl.fromTo('.stat2 .srow',{{opacity:0,x:-40}},{{opacity:1,x:0,duration:0.3,stagger:0.1}},{t + 0.05:.3f});")
    t1 = v + w("l05", "nine", 2) - 0.1
    T.t(f"tl.fromTo('#a1',{{width:0}},{{width:300,duration:0.45,ease:'power3.out'}},{v + w('l05', 'nine') - 0.1:.3f});")
    T.t(f"tl.fromTo(['#av1','#an1','#bv1'],{{opacity:0}},{{opacity:1,duration:0.2}},{t1:.3f});")
    t2 = v + w("l05", "minutes") - 0.6
    T.t(f"tl.fromTo('#b2',{{width:0}},{{width:200,duration:0.45,ease:'power3.out'}},{t2:.3f});")
    T.t(f"tl.fromTo(['#bv2','#bn2','#av2','#an2'],{{opacity:0}},{{opacity:1,duration:0.2,stagger:0.12}},{t2 + 0.3:.3f});")
    t3 = v + w("l05", "deaths") - 0.1
    T.t(f"tl.fromTo(['#bv3','#av3','#an3'],{{opacity:0}},{{opacity:1,duration:0.2}},{t3:.3f});")
    T.t(f"tl.fromTo('#sn1',{{opacity:0}},{{opacity:1,duration:0.4}},{t3 + 0.3:.3f});")
    T.t(f"tl.fromTo('#sn2',{{opacity:0}},{{opacity:1,duration:0.4}},{v + w('l05', 'drowning') - 0.1:.3f});")
    t = end

    # ---- S5 close
    v = t + 0.4
    ve = T.say("l06", v)
    end = ve + 3.0
    d = end - t
    T.card(t, d, '<div class="paper dark"></div>', z=0)
    close = (f'<div class="closebox"><div id="cl-badge">{LOGO}</div><div id="cl-lab" class="labname big">3NDETZ LABS</div>'
             f'<div id="cl-t" class="closet">unionclef {VER}</div><div id="cl-u" class="closeu">github.com/3ndetz/unionclef</div>'
             '<div id="cl-f" class="closef">open source · GPL-3.0 · Minecraft 1.21 · Fabric</div></div>'
             '<div id="cl-credit" class="credit">Music: “Hep Cats” by Kevin MacLeod (incompetech.com), licensed under CC BY 4.0</div>')
    T.card(t, d, close, z=5)
    T.t(f"tl.fromTo('#cl-badge',{{scale:0,rotation:180}},{{scale:1,rotation:0,duration:0.8,ease:'back.out(1.5)'}},{t + 0.1:.3f});")
    T.t(f"tl.fromTo('#cl-lab',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.5}},{t + 0.3:.3f});")
    T.t(f"tl.fromTo('#cl-t',{{opacity:0,scale:1.6}},{{opacity:1,scale:1,duration:0.5,ease:'expo.out'}},{v + w('l06', 'version'):.3f});")
    T.t(f"tl.fromTo(['#cl-u','#cl-f'],{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.4,stagger:0.2}},{v + w('l06', 'out'):.3f});")
    T.stamp(ve + 0.15, end - ve - 0.15, "RETURN ADDRESS: UNKNOWN", x=1380, y=830, rot=-8, size=80)
    T.t(f"tl.fromTo('#cl-credit',{{opacity:0}},{{opacity:1,duration:0.6}},{ve + 0.3:.3f});")
    t = end

    total = t
    T.card(0, total, '<div id="grain" class="grain"></div><div class="edgevig"></div>', z=90)
    T.t(f"tl.to('#grain',{{backgroundPosition:'173px 97px',duration:0.08,repeat:{int(total / 0.16)},yoyo:true,ease:'steps(1)'}},0);")
    return page(total, "\n".join(T.parts), "\n".join(T.tw)), total, T


LOGO = ('<svg width="150" height="150" viewBox="0 0 200 200"><defs><radialGradient id="lgg" cx="40%" cy="35%">'
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
.vign {{ position:absolute; inset:0; background:radial-gradient(ellipse at 50% 45%, rgba(0,0,0,0) 45%, rgba(20,16,10,0.55) 100%); }}
.edgevig {{ position:absolute; inset:0; background:radial-gradient(ellipse at 50% 50%, rgba(0,0,0,0) 62%, rgba(25,18,8,0.35) 100%); }}
.grain {{ position:absolute; inset:-200px; background:url("assets/grain.png"); opacity:0.07; mix-blend-mode:overlay; }}
.paper {{ position:absolute; inset:0; background:radial-gradient(circle at 30% 25%, #fbf3df 0%, #efe2c4 60%, #e2d0a8 100%); }}
.paper.dark {{ background:radial-gradient(circle at 50% 40%, #27383e 0%, #1d2a2f 55%, #141d20 100%); }}
.reel {{ position:absolute; top:34px; right:40px; font-family:"MonoB"; font-size:22px; letter-spacing:3px; color:#f3e9d2;
         background:rgba(29,42,47,0.8); padding:8px 16px; border-left:6px solid #d8453a; }}
.speed {{ position:absolute; top:36px; right:44px; font-family:"MonoB"; font-size:30px; color:#f3e9d2; background:#1d2a2f;
          padding:4px 16px; border:3px solid #f3e9d2; }}
.hspeed {{ position:absolute; top:205px; left:1790px; font-family:"MonoB"; font-size:26px; color:#f3e9d2; background:#1d2a2f;
          padding:2px 12px; border:3px solid #f3e9d2; }}
.stampbox {{ position:absolute; width:0; height:0; display:flex; align-items:center; justify-content:center; }}
.stamp {{ font-family:"Bebas"; white-space:nowrap; letter-spacing:6px; padding:6px 28px 0; border:9px solid; border-radius:14px;
          background:rgba(243,233,210,0.92); box-shadow:0 0 0 6px rgba(243,233,210,0.92), 10px 12px 0 6px rgba(29,42,47,0.55); }}
.kin {{ position:absolute; padding:10px 30px 4px 26px; background:#f3e9d2; border-left:14px solid #d8453a; box-shadow:10px 10px 0 rgba(29,42,47,0.85); max-width:1500px; }}
.kin.teal {{ border-left-color:#1f6f78; }}
.kin.bl {{ left:60px; bottom:150px; }} .kin.tl {{ left:60px; top:70px; }}
.kin-s {{ font-family:"MonoB"; font-size:30px; letter-spacing:2px; color:#1f6f78; text-transform:uppercase; }}
.kin-b {{ font-family:"Bebas"; font-size:110px; line-height:1; color:#1d2a2f; }}
/* halves */
.half {{ position:absolute; top:170px; width:930px; height:523px; object-fit:cover; border:6px solid #f3e9d2; }}
.half.left {{ left:20px; }} .half.right {{ left:970px; }}
.halflab {{ position:absolute; top:60px; width:930px; text-align:center; font-family:"Bebas"; font-size:84px; letter-spacing:6px; }}
.halflab.left {{ left:20px; }} .halflab.right {{ left:970px; }}
.halfcap {{ position:absolute; top:712px; left:0; right:0; text-align:center; font-family:"Mono"; font-size:28px; color:#c9d4d3; }}
.bad {{ color:#ff7a6a; }} .good {{ color:#7fe0a6; }}
.ring {{ position:absolute; border:8px solid #e3a73a; border-radius:50%; box-shadow:0 0 0 4px rgba(29,42,47,0.6); }}
.ring span {{ position:absolute; left:50%; top:100%; transform:translate(-50%, 10px); white-space:nowrap; font-family:"Bebas";
              font-size:52px; letter-spacing:3px; color:#1d2a2f; background:#e3a73a; padding:2px 16px 0; }}
.nearstat {{ position:absolute; left:220px; top:740px; width:1480px; background:#f3e9d2; border:5px solid #1d2a2f; padding:14px 30px;
             box-shadow:12px 12px 0 rgba(0,0,0,0.5); font-family:"Mono"; font-size:34px; color:#1d2a2f; line-height:1.45; }}
.nearstat b {{ font-family:"Bebas"; font-size:54px; color:#2f7d5b; font-weight:normal; display:inline-block; min-width:170px; }}
/* stat */
.stat {{ position:absolute; left:110px; top:120px; width:1700px; }}
.sth {{ font-family:"Bebas"; font-size:120px; color:#1d2a2f; }}
.srow {{ display:flex; align-items:center; gap:34px; height:210px; }}
.sl {{ font-family:"SerifI"; font-size:66px; width:440px; text-align:right; }}
.sbar {{ height:130px; width:900px; }} .sbar.good {{ background:#2f7d5b; }}
.sv {{ font-family:"Bebas"; font-size:140px; white-space:nowrap; }} .sv.good {{ color:#2f7d5b; }}
.snote {{ font-family:"Mono"; font-size:28px; color:#4a5a5f; margin-top:20px; }}
/* grid */
.gridhead {{ position:absolute; left:80px; top:70px; font-family:"MonoB"; font-size:36px; letter-spacing:4px; color:#1f6f78; }}
.grid {{ position:absolute; left:80px; top:140px; width:1150px; display:grid; grid-template-columns:repeat(2, 1fr); gap:16px; }}
.tile {{ height:118px; background:#fbf3df; border:4px solid #1d2a2f; display:flex; align-items:center; justify-content:space-between;
         padding:0 26px; font-family:"MonoB"; font-size:40px; }}
.tile .tk {{ font-family:"Bebas"; font-size:70px; }}
.tile.ghost {{ border-style:dashed; border-color:#d8453a; color:#d8453a; background:transparent; }}
.score {{ position:absolute; right:70px; top:250px; font-family:"Bebas"; font-size:330px; line-height:1; color:#2f7d5b; }}
.score .of {{ color:#1d2a2f; font-size:160px; }}
.foot {{ position:absolute; left:80px; top:880px; font-family:"Mono"; font-size:28px; color:#4a5a5f; }}
/* diagram */
.diag {{ position:absolute; left:110px; top:160px; width:756px; height:588px; }}
.dgrid {{ position:absolute; inset:0; display:grid; grid-template-columns:repeat(9, 84px); grid-auto-rows:84px; }}
.cell {{ border:2px solid rgba(29,42,47,0.25); background:#fbf3df; }}
.cell.wl {{ background:#6d7478; }} .cell.lv {{ background:#e8692d; }}
.dcirc {{ position:absolute; border:7px dashed #1f6f78; border-radius:50%; }}
.dx {{ position:absolute; width:84px; height:84px; display:flex; align-items:center; justify-content:center; font-family:"Inter";
       font-weight:800; font-size:70px; color:#d8453a; text-shadow:0 0 6px #fff; }}
.dlegend {{ position:absolute; left:960px; top:200px; width:880px; }}
.dh {{ font-family:"Bebas"; font-size:100px; color:#1d2a2f; line-height:1; margin-bottom:30px; }}
.dl1, .dl2 {{ font-family:"SerifI"; font-size:50px; display:flex; align-items:center; gap:22px; margin-bottom:26px; }}
.dl1 {{ color:#a2382f; }} .dl2 {{ color:#2f7d5b; }}
.sw {{ width:60px; height:60px; flex:none; border:3px solid #1d2a2f; }} .sw.wl {{ background:#6d7478; }} .sw.ok {{ background:#7fbf8f; }}
.dnote {{ font-family:"Mono"; font-size:26px; color:#4a5a5f; margin-top:30px; }}
/* stat (0.95.59: three rows, longer labels) */
.stat {{ position:absolute; left:90px; top:110px; width:1740px; }}
.sth {{ font-family:"Bebas"; font-size:96px; color:#1d2a2f; margin-bottom:10px; }}
.srow {{ display:flex; align-items:center; gap:30px; height:180px; }}
.sl {{ font-family:"SerifI"; font-size:56px; width:720px; text-align:right; }}
.sbar {{ height:110px; width:560px; }} .sbar.good {{ background:#2f7d5b; }}
.sv {{ font-family:"Bebas"; font-size:120px; white-space:nowrap; }} .sv.good {{ color:#2f7d5b; }}
.snote {{ font-family:"Mono"; font-size:28px; color:#4a5a5f; margin-top:24px; }}
/* keys */
.keys {{ position:absolute; left:140px; top:120px; width:1640px; }}
.kh {{ font-family:"Bebas"; font-size:110px; color:#1d2a2f; margin-bottom:20px; }}
.krow {{ display:flex; align-items:center; height:200px; border-bottom:3px solid rgba(29,42,47,0.2); }}
.krow.khead {{ height:80px; font-family:"MonoB"; font-size:40px; letter-spacing:4px; color:#1f6f78; }}
.kc0 {{ width:700px; display:flex; align-items:center; gap:34px; }} .kc1, .kc2 {{ width:470px; text-align:center; }}
.krow:not(.khead) .kc1, .krow:not(.khead) .kc2 {{ font-family:"Bebas"; font-size:100px; }}
.cap {{ display:inline-block; min-width:150px; padding:18px 26px 8px; text-align:center; font-family:"MonoB"; font-size:54px;
        background:#fbf3df; border:5px solid #1d2a2f; border-radius:14px; box-shadow:0 10px 0 #1d2a2f; }}
.kn {{ font-family:"SerifI"; font-size:62px; }}
.krow .good {{ color:#2f7d5b; }} .badk {{ color:#c23b2f; }}
.keys .snote {{ margin-top:34px; }}
.chatbox {{ position:absolute; left:0px; top:838px; width:880px; height:80px; border:8px solid #e3a73a; border-radius:12px;
            box-shadow:0 0 0 4px rgba(29,42,47,0.6); }}
.chatbox span {{ position:absolute; left:100%; top:50%; transform:translate(18px,-50%); white-space:nowrap; font-family:"Bebas";
                 font-size:56px; letter-spacing:3px; color:#1d2a2f; background:#e3a73a; padding:2px 16px 0; }}
.chatbox.rs {{ left:6px; top:898px; width:1540px; height:104px; }}
.chatbox.rs span {{ left:auto; right:0; top:0; transform:translate(0,-110%); }}
.chatbox.sh {{ left:0px; top:842px; width:1080px; height:76px; }}
/* stat2 (0.95.60: before / after bars) */
.stat2 {{ position:absolute; left:80px; top:90px; width:1760px; }}
.stat2 .sth {{ font-family:"Bebas"; font-size:92px; color:#1d2a2f; margin-bottom:6px; }}
.shead {{ display:flex; height:70px; align-items:center; font-family:"MonoB"; font-size:38px; letter-spacing:4px; }}
.stat2 .srow {{ display:flex; align-items:center; height:165px; border-bottom:3px solid rgba(29,42,47,0.15); }}
.stat2 .sl {{ font-family:"SerifI"; font-size:50px; width:700px; white-space:nowrap; text-align:right; padding-right:40px; color:#1d2a2f; }}
.sc {{ width:530px; display:flex; align-items:center; gap:22px; }} .sc.wide {{ width:1060px; }}
.shead .sc {{ display:block; }}
.bar {{ height:100px; width:0; }} .bar.bad {{ background:#c23b2f; }} .bar.good {{ background:#2f7d5b; }}
.sc b {{ font-family:"Bebas"; font-size:104px; font-weight:normal; white-space:nowrap; }}
.stat2 .bad {{ color:#c23b2f; }} .stat2 .good {{ color:#2f7d5b; }}
.stat2 .bar.bad {{ background:#c23b2f; }} .stat2 .bar.good {{ background:#2f7d5b; }}
.stat2 .snote {{ font-family:"Mono"; font-size:28px; color:#4a5a5f; margin-top:26px; }}
.framed {{ position:absolute; left:160px; top:40px; width:1600px; height:900px; object-fit:cover; border:6px solid #f3e9d2; box-shadow:12px 12px 0 rgba(0,0,0,0.5); }}
.strip {{ position:absolute; left:110px; top:180px; width:1700px; height:auto; background:#141d20; padding:14px 20px; border-left:12px solid #e3a73a; box-shadow:10px 10px 0 rgba(0,0,0,0.5); }}
.strip.low {{ top:770px; }}
.ring.up span {{ top:auto; bottom:100%; transform:translate(-50%, -10px); }}
.snote2 {{ font-family:"SerifI"; font-size:40px; color:#4a5a5f; white-space:nowrap; font-style:normal; }}
/* 0.95.63: crate, buckets, bottom caption */
.crate {{ position:absolute; left:120px; top:110px; width:1180px; padding:40px 50px 36px; background:#d9b77e;
          border:10px solid #8a5a33; box-shadow:16px 16px 0 rgba(29,42,47,0.6);
          background-image:repeating-linear-gradient(0deg, rgba(138,90,51,0.18) 0 3px, rgba(0,0,0,0) 3px 46px); }}
.crh {{ font-family:"Bebas"; font-size:110px; line-height:1; color:#1d2a2f; }}
.crs {{ font-family:"Mono"; font-size:27px; color:#3b2a1a; margin:10px 0 24px; }}
.crl {{ list-style:none; }}
.crl li {{ font-family:"SerifI"; font-size:54px; color:#1d2a2f; line-height:1.45; }}
.crl li:before {{ content:"—  "; color:#8a5a33; }}
.bk {{ position:absolute; left:140px; top:150px; width:1640px; }}
.bkh {{ font-family:"Bebas"; font-size:130px; color:#1d2a2f; margin-bottom:20px; }}
.bkrow {{ display:flex; align-items:center; gap:40px; height:190px; }}
.bki {{ width:150px; height:150px; display:flex; align-items:center; justify-content:center; font-family:"Bebas";
        font-size:130px; color:#f3e9d2; border:6px solid #1d2a2f; }}
.bki.keep {{ background:#2f6fb0; }} .bki.dump {{ background:#8fa3a6; }}
.bkt {{ font-family:"SerifI"; font-size:76px; color:#1d2a2f; }}
.bkn {{ font-family:"Mono"; font-size:30px; color:#4a5a5f; margin-top:30px; }}
.botcap {{ position:absolute; left:60px; top:262px; padding:6px 18px; background:rgba(20,29,32,0.82); }}
.botcap {{ font-family:"Mono"; font-size:30px; color:#f3e9d2; }}
.stat2 .snote b {{ color:#c23b2f; font-weight:normal; font-family:"MonoB"; }}
/* close */
.labname {{ font-family:"Serif"; font-size:64px; color:#f3e9d2; letter-spacing:10px; text-shadow:0 4px 0 #1d2a2f, 0 0 30px rgba(0,0,0,0.6); }}
.labname.big {{ font-size:96px; }}
.closebox {{ position:absolute; left:0; right:0; top:80px; display:flex; flex-direction:column; align-items:center; gap:10px; }}
.closet {{ font-family:"Bebas"; font-size:210px; line-height:1; color:#d8453a; letter-spacing:10px; }}
.closeu {{ font-family:"MonoB"; font-size:56px; color:#f3e9d2; }}
.closef {{ font-family:"Mono"; font-size:32px; color:#c9d4d3; }}
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


def speech_spans(T: TL) -> list[tuple[float, float]]:
    return [(t0, t0 + vdur(line)) for line, t0 in T.voice]


def mix(T: TL, total: float, silent: Path, out: Path):
    """Voice at its times (normalised), music ducked under it, stamp thuds only outside speech."""
    spans = speech_spans(T)
    sfx = [s for s in T.sfx if not any(a - 0.15 <= s <= b + 0.15 for a, b in spans)]
    inputs, filt, labels = [], [], []
    for i, (line, t0) in enumerate(T.voice):
        inputs += ["-i", str(HERE / "voice" / "pick" / f"{line}.wav")]
        ms = int(t0 * 1000)
        filt.append(f"[{i + 1}:a]aresample=48000,loudnorm=I=-16:TP=-1.5:LRA=7,adelay={ms}|{ms},apad[v{i}]")
        labels.append(f"[v{i}]")
    n = len(T.voice)
    for j, ts in enumerate(sfx):
        inputs += ["-i", str(WORK / "thud.wav")]
        ms = int(ts * 1000)
        filt.append(f"[{n + j + 1}:a]aresample=48000,volume=0.2,adelay={ms}|{ms},apad[s{j}]")
    if sfx:
        filt.append(f"{''.join(f'[s{j}]' for j in range(len(sfx)))}amix=inputs={len(sfx)}:normalize=0:duration=longest,"
                    f"atrim=0:{total:.3f}[sfx]")
    else:
        filt.append(f"anullsrc=r=48000:cl=stereo,atrim=0:{total:.3f}[sfx]")
    filt.append(f"{''.join(labels)}amix=inputs={n}:normalize=0:duration=longest,atrim=0:{total:.3f},"
                f"acompressor=threshold=-18dB:ratio=3:attack=5:release=120,"
                f"highpass=f=70,equalizer=f=3000:t=q:w=1:g=2,asplit=2[vo][key]")
    mi = n + len(sfx) + 1
    fade0 = total - 3.0
    filt.append(f"[{mi}:a]aresample=48000,atrim=0:{total:.3f},volume=0.15,afade=t=in:d=0.4,"
                f"afade=t=out:st={fade0:.2f}:d=3.0[mus]")
    filt.append("[mus][key]sidechaincompress=threshold=0.03:ratio=8:attack=40:release=450:makeup=1[duck]")
    # static gain + limiter instead of a single-pass loudnorm: loudnorm is dynamic and lifted the music and the thuds in
    # the pauses to speech level (0.95.62 check: gap peaks -3 dBFS). Voice peaks land at ~-3 dBFS, the rest stays below.
    filt.append("[vo][duck][sfx]amix=inputs=3:normalize=0,volume=5dB,alimiter=limit=0.708:level=disabled,aresample=48000[aout]")
    cmd = (["ffmpeg", "-v", "error", "-y", "-i", str(silent)] + inputs + ["-i", str(MUSIC)] +
           ["-filter_complex", ";".join(filt), "-map", "0:v", "-map", "[aout]", "-c:v", "copy",
            "-c:a", "aac", "-b:a", "192k", "-shortest", str(out)])
    subprocess.run(cmd, check=True)
    print(f"sfx kept {len(sfx)} of {len(T.sfx)} (the rest fall inside speech)")


def main() -> int:
    if "--mix" not in sys.argv and "--compose" not in sys.argv and VID.exists():
        shutil.rmtree(VID)
    VID.mkdir(parents=True, exist_ok=True)
    doc, total, T = build()
    (HF / "index.html").write_text(doc, encoding="utf-8")
    (HERE / "timeline.json").write_text(json.dumps({"total": total, "voice": T.voice, "clips": USED}, indent=1),
                                        encoding="utf-8")
    print(f"composition: {total:.1f} s, {len(T.voice)} voice lines")
    if "--compose" in sys.argv:
        return 0
    OUT.mkdir(exist_ok=True)
    silent = OUT / "cj-silent.mp4"
    npx = shutil.which("npx") or "npx"
    if "--mix" not in sys.argv:
        r = subprocess.run([npx, "--yes", HF_CLI, "render", "--output", str(silent)], cwd=HF)
        if r.returncode != 0 or not silent.exists():
            print("render failed")
            return 1
    final = OUT / f"cj-{VER}-clean.mp4"
    mix(T, total, silent, final)
    print(f"final: {final} ({final.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
