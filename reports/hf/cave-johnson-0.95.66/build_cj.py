"""Cave Johnson field report for releases 0.95.65 + 0.95.66: cut footage, compose in HyperFrames, mix, render.

    python build_cj.py            # cut + compose + render + mix -> out/cj-0.95.66-clean.mp4
    python build_cj.py --compose  # write hf/index.html only
    python build_cj.py --mix      # remix audio onto the last silent render

Copied from cave-johnson-0.95.64 (see docs/VIDEO_REPORTS.md, "Cave Johnson films"). The picture is rendered silent
by HyperFrames, voice and music are mixed with ffmpeg afterwards (music ducked under the voice with a sidechain
compressor; stamp thuds only where nobody is talking; static gain + limiter, no loudnorm on the mix).
Subtitles: add_subs.py. Inputs: voice/pick/lXX.wav + words.json (tts_wrap.py), footage/ (copies, see FOOT),
work/Hep_Cats.mp3 (Kevin MacLeod, incompetech.com, CC BY 4.0) + work/thud.wav (from reports/pitch/work), hf/assets.
New in 0.95.66: the burned-in clock takes a label and a zero point per cut ("IN LAVA 12 S" counts from the moment the
bot dropped into the pit), and still frames (still()) hold a two-frame event under its ring.
"""
from __future__ import annotations

import html
import json
import shutil
import subprocess
import sys
from pathlib import Path

VER = "0.95.66"
HERE = Path(__file__).resolve().parent
HF = HERE / "hf"
VID = HF / "videos"
OUT = HERE / "out"
WORK = HERE / "work"
FOOTDIR = HERE / "footage"
HF_CLI = "hyperframes@0.8.30"
MUSIC = WORK / "Hep_Cats.mp3"
WORDS = json.loads((HERE / "words.json").read_text(encoding="utf-8"))

# key -> (file in footage/, ffmpeg crop). Recordings are 854x480, 15 fps. Footage = copies of
# deploy/runner/artifacts/video-0.95.65/ (CLIPS.md there lists the claims these clips may carry):
#   boxed-old-0.95.64.mp4  course lava_boxed_source on 0.95.64: bedrock box in view 0-1 s, then stands on
#                          "Approaching 4, -60, 0" to the end (155 s), no bucket
#   boxed-new.mp4          same course on 0.95.65: chat "No closer to the liquid at 4, -61, 0 for 20 s; trying another
#                          source" on screen 22.25-22.9, walks to the open pool, lava bucket in hand from ~30
#   repro10-release.mp4    0.95.65 from checkpoint repro-lava-stuck-cell: iron pickaxe breaks 36.45 (in hand at 36.40,
#                          fist at 36.47); crafting table GUI 115.27-117.13, cobblestone laid in 115.8-116.5, stone
#                          pickaxe in the output slot 116.67-117.07; lava pool in view 171-172, "Escaping lava" 173-175,
#                          dead / "Loading terrain" 176 (log LAVA/DEATH t=192 s: log runs ~17 s ahead of the video)
#   pit-jump-off.mp4       course escape_lava_deep_kit, walkerJumpsInLiquid=false: drops in ~7.3, leaves the pit
#                          50.5-51.0 (reported 43.7 s)
#   pit-jump-on.mp4        same, lever on: drops in ~6.6-7.0, level with the rim at 13.0 (reported 6.0 s)
FULL = "crop=iw:ih:0:0"
FOOT = {
    "old": ("boxed-old-0.95.64.mp4", FULL),
    "new": ("boxed-new.mp4", FULL),
    "field": ("repro10-release.mp4", FULL),
    "gui": ("repro10-release.mp4", "crop=320:180:267:150"),   # crafting GUI x5 in the framed inset
    "off": ("pit-jump-off.mp4", FULL),
    "on": ("pit-jump-on.mp4", FULL),
}
# status-line strips: key -> (footage key, second, crop) -- one HUD line, shown x3 on a dark bar
STRIPS = {
    "s_closer": ("new", 22.30, "330:20:0:422"),   # "No closer to the liquid at 4, -61, 0 for 20 s; / trying another source"
}
USED: list[dict] = []
CLOCK = ("drawtext=fontfile=hf/assets/IBMPlexMono-Bold.ttf:textfile={tf}:x=w-tw-12:y=10:fontsize={fs}:"
         "fontcolor=0xf3e9d2:box=1:boxcolor=0x1d2a2fdd:boxborderw=6")


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


def cut(key: str, foot: str, start: float, dur: float, speed: float, clock=None) -> str:
    """clock = (label, zero second in the source, "ms" | "s", font size at 854x480) -> burned-in counter."""
    name, crop = FOOT[foot]
    src = FOOTDIR / name
    dst = VID / f"{key}.mp4"
    pre = ""
    if clock:
        label, zero, fmt, fs = clock
        off = start - zero
        tf = WORK / f"clock-{key}.txt"
        if fmt == "ms":
            body = (f"%{{eif:max(0,trunc((t+{off:.3f})/60)):d:2}}:"
                    f"%{{eif:max(0,mod(trunc(t+{off:.3f}),60)):d:2}}")
        else:
            body = f"%{{eif:max(0,trunc(t+{off:.3f})):d}} S"
        tf.write_text(f"{label} {body}", encoding="utf-8")
        pre = CLOCK.format(tf=f"work/clock-{key}.txt", fs=fs) + ","
    vf = (f"{pre}setpts=PTS/{speed},{crop},"
          f"scale=1920:1080:flags=lanczos,fps=30,eq=saturation=1.08:contrast=1.04")
    cmd = ["ffmpeg", "-v", "error", "-y", "-ss", f"{start:.3f}", "-t", f"{dur * speed + 0.3:.3f}", "-i", str(src),
           "-vf", vf, "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
           "-t", f"{dur + 0.1:.3f}", str(dst)]
    if "--compose" not in sys.argv or not dst.exists():
        subprocess.run(cmd, check=True, cwd=HERE)
    USED.append({"key": key, "file": name, "src_in": round(start, 2), "src_out": round(start + dur * speed, 2),
                 "speed": speed})
    return dst.name


def still(key: str, foot: str, at: float) -> str:
    """One source frame as a 1920x1080 png (for events that last two frames)."""
    dst = VID / f"{key}.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.3f}", "-i", str(FOOTDIR / FOOT[foot][0]),
                    "-frames:v", "1", "-vf", f"{FOOT[foot][1]},scale=1920:1080:flags=lanczos,eq=saturation=1.08:contrast=1.04",
                    str(dst)], check=True)
    USED.append({"key": key, "file": FOOT[foot][0], "still": at})
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
             zoom=(1.0, 1.06), cls: str = "vid", badge: bool = True, style: str = "", clock=None) -> str:
        vid = self.id("v")
        name = cut(vid, foot, frm, dur, speed, clock)
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
    name = FOOT[foot][0]
    dst = VID / f"{key}.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at:.3f}", "-i", str(FOOTDIR / name), "-frames:v", "1",
                    "-vf", f"crop={crop},scale=iw*3:ih*3:flags=lanczos", str(dst)], check=True)
    return dst.name


# Source seconds, read off frames (review/*.png).
NEW_CHAT = 22.30     # boxed-new: "No closer to the liquid ... trying another source" (on screen 22.25-22.9)
NEW_BUCKET = 33.0    # boxed-new: lava bucket in hand from ~30, task finished 33.1 s
OLD_END = 152.0      # boxed-old: still on "Approaching 4, -60, 0" (recording 155.5 s)
F_BREAK = 36.45      # field: iron pickaxe breaks
F_GUI = 115.20       # field: crafting GUI opens 115.27
F_PICK = 116.80      # field: stone pickaxe in the output slot 116.67-117.07
F_LAVA = 169.5       # field: walking to the pool; lava in view 171; dead 176
F_DEAD = 176.4
OFF_IN, OFF_OUT = 7.2, 50.9    # pit, lever off: zero of the in-lava clock = exit - 43.7 s
ON_IN, ON_OUT = 7.0, 13.0      # pit, lever on:  zero = exit - 6.0 s
HALF_L, HALF_R = 20, 970       # .half left edges; a source point (x, y) lands at left + x*1.089, 170 + y*1.090


def build():
    T = TL()

    # ---- S0 hook: the payoff. The new walker climbing out of the two-deep lava pit, x1.
    v = 0.3
    ve = T.say("l01", v)
    end = ve + 1.2
    T.card(0, end, '<div class="paper dark"></div>', z=0)
    T.clip("on", 0.0, end, frm=ON_OUT + 0.4 - end, speed=1.0, zoom=(1.0, 1.05), badge=False,
           clock=("IN LAVA", ON_IN, "s", 20))
    T.kin(0.4, end - 0.4, "OUT OF A LAVA PIT IN 6 S", f"{VER} · course escape_lava_deep_kit · old input: 43.7 s",
          pos="bl", color="teal")
    T.card(0, end, f'<div class="reel" style="right:auto;left:40px">3NDETZ LABS &middot; FIELD REPORT &middot; '
                   f'RELEASES 0.95.65 + {VER}</div>', z=45)
    T.t("tl.fromTo('.reel',{opacity:0},{opacity:1,duration:0.4},0.1);")
    T.stamp(ve + 0.05, end - ve - 0.05, "IT JUMPS NOW", x=1420, y=380, rot=-8, size=110)
    t = end

    # ---- S1 0.95.65: lava sealed in bedrock. Left: 0.95.64 stands for 150 s (x13). Right: 0.95.65 gives up at 20 s,
    #      fetches the open pool (x3). Separate runs on one course; run clocks burned in.
    v = t + 0.3
    ve = T.say("l02", v)
    end = ve + 1.4
    d = end - t
    sp_r = round((NEW_BUCKET + 0.5) / d, 2)
    sp_l = round(OLD_END / d, 1)
    T.card(t, d, '<div class="paper dark"></div>', z=0)
    T.clip("old", t, d, frm=0.0, speed=sp_l, z=3, cls="half left", zoom=None, badge=False,
           clock=("RUN", 0.0, "ms", 26))
    T.clip("new", t, d, frm=0.0, speed=sp_r, z=3, cls="half right", zoom=None, badge=False,
           clock=("RUN", 0.0, "ms", 26))
    T.card(t, d,
           '<div class="halflab left bad">0.95.64</div><div class="halflab right good">0.95.65</div>'
           f'<div class="hspeed" style="left:40px">x{sp_l:g}</div><div class="hspeed" style="left:990px">x{sp_r:g}</div>'
           '<div class="halfcap">course lava_boxed_source &middot; nearest source sealed in bedrock, an open one 18 blocks on'
           ' &middot; separate runs</div>', z=10)
    T.t(f"tl.fromTo('.halflab',{{opacity:0,y:-20}},{{opacity:1,y:0,duration:0.3}},{t + 0.1:.3f});")
    t_chat = t + NEW_CHAT / sp_r
    T.img("videos/" + strip("s_closer"), t_chat, end - t_chat, z=30, kb=None, cls="strip low", style="width:1250px")
    T.card(end - 2.2, 2.2, '<div class="verdict left bad">150 S · NO BUCKET · 0/2</div>'
                           '<div class="verdict right good">BUCKET AT ~34 S · 2/2</div>', z=12)
    T.t(f"tl.fromTo('.verdict',{{opacity:0,scale:1.3}},{{opacity:1,scale:1,duration:0.3,stagger:0.25}},{end - 2.2:.3f});")
    t = end

    # ---- S2 0.95.65 in the field: iron pickaxe breaks, run clock timelapse, stone pickaxe at the crafting table.
    v = t + 0.3
    ve = T.say("l03", v)
    end = ve + 1.2
    T.card(t, end - t, '<div class="paper dark"></div>', z=0)
    a_dur = 3.4                                   # break lands 2.85 s in
    T.clip("field", t, a_dur, frm=F_BREAK - 2.85, speed=1.0, z=3, zoom=None, cls="framed", badge=False)
    T.kin(t + 0.2, a_dur - 0.2, "IRON PICKAXE: WORN OUT", "0.95.65 · checkpoint repro-lava-stuck-cell · underground",
          pos="tl", color="red")
    t_b = t + a_dur
    c_dur = 3.4                                   # crafting GUI at x0.5
    hold = 1.4
    b_dur = end - t_b - c_dur - hold
    sp_b = round((F_GUI - (F_BREAK + 0.6)) / b_dur)
    T.clip("field", t_b, b_dur, frm=F_BREAK + 0.6, speed=sp_b, z=3, zoom=None, cls="framed", badge=False,
           clock=("RUN", 0.0, "ms", 20))
    T.card(t_b, b_dur, f'<div class="hspeed" style="top:70px;left:200px">x{sp_b}</div>', z=33)
    T.kin(t_b + 0.1, b_dur - 0.1, "TO THE CRAFTING TABLE", "it carries cobblestone · the old build hunted wood for 5 min",
          pos="tl", color="teal")
    t_c = t_b + b_dur
    T.clip("gui", t_c, c_dur, frm=F_GUI, speed=0.5, z=3, zoom=None, cls="framed", badge=False)
    T.card(t_c, c_dur, '<div class="hspeed" style="top:70px;left:200px">x0.5</div>', z=33)
    T.img("videos/" + still("st_pick", "gui", F_PICK), t_c + c_dur, hold, z=4, kb=None, cls="framed")
    # gui crop 320x180 at (267,150) -> framed x5: grid centre (393,195) -> (790,265); output slot (471,199) -> (1180,285)
    T.ring(t_c + 1.25, (F_PICK - F_GUI) * 2 - 1.25, 790, 265, 170, "CARRIED COBBLESTONE")
    t_p = t_c + (F_PICK - 0.12 - F_GUI) * 2
    T.ring(t_p, end - t_p, 1180, 285, 95, "STONE PICKAXE")
    t = end

    # ---- S3 honest: the same run, two minutes later, dies in the portal-site lava. Framed, x0.75.
    v = t + 0.3
    ve = T.say("l04", v)
    end = ve + 1.3
    sp = round((F_DEAD - F_LAVA) / (end - t), 2)
    T.card(t, end - t, '<div class="paper dark"></div>', z=0)
    T.clip("field", t, end - t, frm=F_LAVA, speed=sp, z=3, zoom=None, cls="framed", badge=False)
    T.card(t, end - t, f'<div class="hspeed" style="top:70px;left:200px">x{sp:g}</div>', z=33)
    T.kin(t + 0.2, end - t - 0.2, "SAME RUN · DIES IN THE LAVA", "0.95.65 as released · route's next step one block up · never jumps",
          pos="tl2", color="red")
    T.stamp(ve + 0.05, end - ve - 0.05, "NEVER JUMPED", x=1420, y=760, rot=-7, size=104)
    t = end

    # ---- S4 0.95.66: same pit, lever off (x4) vs on (x1), in-lava clocks from the drop.
    v = t + 0.3
    ve = T.say("l05", v)
    end = ve + 1.7
    d = end - t
    T.card(t, d, '<div class="paper dark"></div>', z=0)
    off_hold = 1.5                                # the left half ends on a still just past the exit, under its verdict
    off_play = d - off_hold
    sp_off = round((OFF_OUT + 0.25 - (OFF_IN - 1.0)) / off_play, 1)
    T.clip("off", t, off_play, frm=OFF_OUT + 0.25 - off_play * sp_off, speed=sp_off, z=3, cls="half left", zoom=None,
           badge=False, clock=("IN LAVA", OFF_IN, "s", 26))
    T.img("videos/" + still("st_off", "off", OFF_OUT + 0.5), t + off_play, off_hold, z=4, kb=None, cls="half left")
    on_from = ON_IN - 1.5
    on_dur = min(d - 0.6, ON_OUT + 0.4 - on_from)
    T.clip("on", t, on_dur, frm=on_from, speed=1.0, z=3, cls="half right", zoom=None, badge=False,
           clock=("IN LAVA", ON_IN, "s", 26))
    T.img("videos/" + still("st_on", "on", on_from + on_dur), t + on_dur, d - on_dur, z=4, kb=None, cls="half right")
    T.card(t, d,
           '<div class="halflab left bad">OLD INPUT</div><div class="halflab right good">0.95.66 · JUMPS</div>'
           f'<div class="hspeed" style="left:40px">x{sp_off:g}</div><div class="hspeed" style="left:990px">x1</div>'
           '<div class="halfcap">course escape_lava_deep_kit &middot; two-deep lava pit, cobblestone in the pack'
           ' &middot; resistance on for both</div>', z=10)
    T.t(f"tl.fromTo('.halflab',{{opacity:0,y:-20}},{{opacity:1,y:0,duration:0.3}},{t + 0.1:.3f});")
    rule = ('<div class="rule"><b>baritone Movement.java:125-127</b> &middot; in a liquid, next step above &rarr; hold jump</div>')
    t_rule = v + w("l05", "rule") - 0.3
    T.card(t_rule, end - t_rule, rule, z=31)
    T.t(f"tl.fromTo('.rule',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.3}},{t_rule:.3f});")
    T.card(t + on_dur, d - on_dur, '<div class="verdict right good">OUT · 6.0 S</div>', z=12)
    T.card(t + off_play, off_hold, '<div class="verdict left bad">OUT · 43.7 S</div>', z=12)
    t = end

    # ---- S5 numbers
    v = t + 0.3
    ve = T.say("l06", v)
    end = ve + 1.0
    T.card(t, end - t, '<div class="paper"></div>', z=0)
    stat = ('<div class="stat2"><div class="sth">MEASURED ON THE BENCH</div>'
            '<div class="shead"><span class="sl"></span><span class="sc bad">BEFORE</span><span class="sc good">AFTER</span></div>'
            '<div class="srow"><span class="sl">out of the lava pit, median</span>'
            '<span class="sc"><div id="b1" class="bar bad"></div><b id="bv1" class="bad">27.9 s</b></span>'
            '<span class="sc"><div id="a1" class="bar good"></div><b id="av1" class="good">8.7 s</b></span></div>'
            '<div class="srow"><span class="sl">stall checkpoint, 10 min</span>'
            '<span class="sc"><b id="bv2" class="bad">1 death in 2</b></span>'
            '<span class="sc"><b id="av2" class="good">0 deaths in 2</b></span></div>'
            '<div class="srow"><span class="sl">reached the nether</span>'
            '<span class="sc"></span>'
            '<span class="sc"><b id="av3" class="good">2 / 2</b></span></div>'
            '<div class="snote">pit, 3 runs each: off 43.7 / 27.9 / 24.0 s &middot; on 8.7 / 15.7 / 6.0 s &middot; course only'
            '<br>bedrock-sealed source: 0/2 on 0.95.64, 2/2 on 0.95.65 &middot; game not beaten &middot; no FPS gain claimed'
            '<br><b>open:</b> with no blocks in the pack the same pit is still not escaped</div></div>')
    T.card(t, end - t, stat, z=5)
    T.t(f"tl.fromTo('.stat2 .srow',{{opacity:0,x:-40}},{{opacity:1,x:0,duration:0.3,stagger:0.1}},{t + 0.05:.3f});")
    T.t(f"tl.set(['#bv1','#av1','#bv2','#av2','#av3'],{{opacity:0}},{t:.3f});")
    tb = v + w("l06", "twenty") - 0.1
    ta = v + w("l06", "nine") - 0.1
    T.t(f"tl.fromTo('#b1',{{width:0}},{{width:330,duration:0.4,ease:'power3.out'}},{tb:.3f});")
    T.t(f"tl.fromTo('#bv1',{{opacity:0}},{{opacity:1,duration:0.2}},{tb + 0.2:.3f});")
    T.t(f"tl.fromTo('#a1',{{width:0}},{{width:103,duration:0.4,ease:'power3.out'}},{ta:.3f});")
    T.t(f"tl.fromTo('#av1',{{opacity:0}},{{opacity:1,duration:0.2}},{ta + 0.2:.3f});")
    t2 = v + w("l06", "deaths") - 0.3
    T.t(f"tl.fromTo(['#bv2','#av2'],{{opacity:0,x:-20}},{{opacity:1,x:0,duration:0.3,stagger:0.15}},{t2:.3f});")
    t3 = v + w("l06", "reached") - 0.1
    T.t(f"tl.fromTo('#av3',{{opacity:0,scale:1.4}},{{opacity:1,scale:1,duration:0.35,ease:'back.out(2)'}},{t3:.3f});")
    t = end

    # ---- S6 close
    v = t + 0.4
    ve = T.say("l07", v)
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
    T.t(f"tl.fromTo('#cl-t',{{opacity:0,scale:1.6}},{{opacity:1,scale:1,duration:0.5,ease:'expo.out'}},{v + 0.1:.3f});")
    T.t(f"tl.fromTo(['#cl-u','#cl-f'],{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.4,stagger:0.2}},{ve - 0.6:.3f});")
    T.stamp(ve + 0.15, end - ve - 0.15, "PLEASE JUMP RESPONSIBLY", x=1340, y=830, rot=-8, size=76)
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
.kin.bl {{ left:60px; bottom:150px; }} .kin.tl2 {{ left:60px; top:175px; }} .kin.tl {{ left:60px; top:70px; }}
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
/* 0.95.64: crash report, race diagram, bench log, stat3 */
.blackbox {{ position:absolute; left:160px; top:40px; width:1600px; height:900px; background:#000; border:6px solid #f3e9d2; box-shadow:12px 12px 0 rgba(0,0,0,0.5); }}
.crash {{ position:absolute; left:230px; top:250px; width:1460px; padding:30px 40px; background:#f3e9d2; border-left:14px solid #d8453a;
          box-shadow:14px 14px 0 rgba(0,0,0,0.6); font-family:"Mono"; color:#1d2a2f; }}
.crh {{ font-family:"MonoB"; font-size:40px; }}
.crq {{ font-size:30px; color:#4a5a5f; margin-top:10px; }}
.crq2 {{ font-size:22px; color:#8a7a5a; margin:4px 0 18px; }}
.crn {{ font-family:"MonoB"; font-size:31px; color:#a2382f; padding:8px 12px; line-height:1.35; }}
.crt {{ font-size:25px; color:#4a5a5f; margin-top:14px; line-height:1.4; }}
.race {{ position:absolute; left:110px; top:130px; width:1700px; }}
.rh {{ font-family:"Bebas"; font-size:120px; color:#1d2a2f; margin-bottom:30px; }}
.rlane {{ display:flex; align-items:center; gap:26px; height:150px; border-bottom:4px dashed rgba(29,42,47,0.25); }}
.rtag {{ font-family:"MonoB"; font-size:40px; color:#1f6f78; width:230px; }}
.chip {{ font-family:"MonoB"; font-size:42px; padding:14px 26px; background:#fbf3df; border:5px solid #1d2a2f; box-shadow:8px 8px 0 rgba(29,42,47,0.5); white-space:nowrap; }}
.chip.warn {{ background:#e3a73a; }} .chip.bad {{ background:#d8453a; color:#f3e9d2; }}
.gap {{ width:330px; }} .gap1 {{ width:200px; }}
.rnote {{ font-family:"SerifI"; font-size:52px; color:#1d2a2f; margin-top:34px; }}
.rfix {{ font-family:"Mono"; font-size:34px; color:#1d2a2f; margin-top:26px; padding:18px 26px; background:#d7ead9; border-left:14px solid #2f7d5b; }}
.rfix b {{ font-family:"Bebas"; font-size:60px; font-weight:normal; color:#2f7d5b; margin-right:18px; }}
.rfix i {{ font-size:28px; color:#4a5a5f; font-style:normal; }}
.term {{ position:absolute; left:140px; top:140px; width:1640px; padding:36px 46px; background:#0f1517; border:6px solid #f3e9d2;
         box-shadow:14px 14px 0 rgba(0,0,0,0.6); font-family:"Mono"; color:#c9d4d3; }}
.tmh {{ font-family:"MonoB"; font-size:32px; letter-spacing:3px; color:#e3a73a; margin-bottom:20px; }}
.tml {{ font-size:40px; line-height:1.6; }}
.tml.big {{ font-family:"MonoB"; font-size:64px; margin-top:10px; }}
.tml .hl {{ color:#ff7a6a; }}
.tml.now {{ font-family:"MonoB"; font-size:36px; color:#7fe0a6; margin-top:26px; border-top:3px solid #3b4a4f; padding-top:20px; }}
.stat3 {{ position:absolute; left:110px; top:100px; width:1700px; }}
.stat3 .sth {{ font-family:"Bebas"; font-size:110px; color:#1d2a2f; margin-bottom:10px; }}
.s3row {{ display:flex; align-items:center; gap:40px; height:170px; border-bottom:3px solid rgba(29,42,47,0.15); }}
.s3l {{ font-family:"SerifI"; font-size:60px; width:560px; text-align:right; color:#1d2a2f; }}
.s3row b {{ font-family:"Bebas"; font-size:130px; font-weight:normal; width:380px; }}
.s3row b.good {{ color:#2f7d5b; }}
.s3row i {{ font-family:"SerifI"; font-size:48px; font-style:normal; color:#4a5a5f; }}
.stat3 .snote {{ font-family:"Mono"; font-size:26px; color:#4a5a5f; margin-top:26px; line-height:1.5; }}
.stat3 .snote b {{ color:#2f7d5b; font-weight:normal; font-family:"MonoB"; font-size:30px; }}
/* 0.95.66: verdict tags under the halves, the baritone rule bar */
.verdict {{ position:absolute; top:600px; width:930px; text-align:center; font-family:"Bebas"; font-size:70px; letter-spacing:4px; }}
.verdict.left {{ left:20px; }} .verdict.right {{ left:970px; }}
.verdict.bad {{ color:#f3e9d2; text-shadow:0 0 0 #000; }} .verdict.good {{ color:#f3e9d2; }}
.verdict.bad, .verdict.good {{ width:auto; padding:4px 24px 0; transform-origin:50% 50%; }}
.verdict.left.bad {{ left:120px; background:#c23b2f; }} .verdict.right.good {{ left:1070px; background:#2f7d5b; }}
.rule {{ position:absolute; left:110px; top:775px; width:1700px; padding:14px 26px; background:#f3e9d2; border-left:14px solid #1f6f78;
         box-shadow:10px 10px 0 rgba(0,0,0,0.5); font-family:"Mono"; font-size:34px; color:#1d2a2f; }}
.rule b {{ font-family:"MonoB"; color:#1f6f78; font-weight:normal; }}
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
