"""Cave Johnson field report for release 0.95.62: cut footage, compose in HyperFrames, mix, render.

    python build_cj.py            # cut + compose + render + mix -> out/cj-0.95.62-clean.mp4
    python build_cj.py --compose  # write hf/index.html only
    python build_cj.py --mix      # remix audio onto the last silent render

Same approach as reports/pitch/build_pitch.py (the reference film): the picture is rendered silent by
HyperFrames, voice and music are mixed with ffmpeg afterwards (music ducked under the voice with a
sidechain compressor; stamp thuds only where nobody is talking). Subtitles: add_subs.py.
Inputs: voice/pick/lXX.wav + words.json (tts_wrap.py), footage/ (copies, see FOOT), work/Hep_Cats.mp3
(Kevin MacLeod, incompetech.com, CC BY 4.0), hf/assets (fonts, grain, lab painting from the pitch).
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
FOOTDIR = HERE / "footage"
HF_CLI = "hyperframes@0.8.30"
MUSIC = WORK / "Hep_Cats.mp3"
WORDS = json.loads((HERE / "words.json").read_text(encoding="utf-8"))

# key -> (file in footage/, ffmpeg crop). Recordings are 854x480 @ 15 fps; the 0.86 centre crop (bias 0.3) trims
# the hotbar. "helmfull" keeps the whole frame (hearts + armour HUD), "craft" is the crafting-table window.
# Footage = copies of deploy/runner/artifacts/...:
#   lake-before = nether-food-ab-20261016/f59-1.mp4      (older build, full59-before-portal: afloat at night ~520-880)
#   lake-after3 = bank-exit-ab-20261022/run-3-false.mp4  (0.95.62, cp1015-2247-t609: swims out 112-127, digs in 129-133)
#   hog-before3 = hoglin-gear-ab-20261022/run-3-before.mp4 (older build, nether-fresh: hoglin throws it, dead ~65.5)
#   hog-after4  = hoglin-gear-ab-20261022/run-4-after.mp4  (0.95.62, nether-fresh: "No food in sight..." 70-84, portal 84-91, sheep 127-132)
#   helmet2     = gold-helmet-ab-20261022/run-2.mp4        (0.95.62, full59-before-portal: gold 59-73, furnace 144-198, helmet 200.6, nether 470-890)
HUDLESS = "crop=iw*0.86:ih*0.86:(iw-iw*0.86)/2:(ih-ih*0.86)*0.3"
FOOT = {
    "lakeB": ("lake-before.mp4", HUDLESS),
    "lakeA": ("lake-after3.mp4", HUDLESS),
    "hogB": ("hog-before3.mp4", HUDLESS),
    "hogA": ("hog-after4.mp4", HUDLESS),
    "helm": ("helmet2.mp4", HUDLESS),
    "helmfull": ("helmet2.mp4", "crop=iw:ih:0:0"),
    "craft": ("helmet2.mp4", "crop=320:180:267:145"),
}
# status-line strips: key -> (footage key, second, crop) -- one line of the bot's task readout, shown x3 on a dark bar
STRIPS = {
    "s_helm": ("helm", 68.0, "480:13:4:31"),        # "Getting a gold helmet for the piglins"
    "s_lakeB": ("lakeB", 700.0, "590:13:4:42"),     # "No place to dig in for the night in nearby loaded terrain"
    "s_lakeA": ("lakeA", 117.0, "250:10:2:359"),    # "[Tungsten] Swimming out onto a bank at y=62"
    "s_hogA": ("hogA", 74.0, "560:13:4:42"),        # "No food in sight in the Nether: going to the Overworld for it"
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
HELM_NETHER_A = 670.5   # lava sea + magma, hearts full, one armour point (the helmet)
HELM_NETHER_B = 705.0   # gravel bank over the lava sea
HELM_GOLD = 66.3        # mining raw gold in granite
HELM_FURNACE = 186.0
HELM_CRAFT = 199.43     # crafting window open since ~199.0, one ingot in the grid
HELM_OUT = 200.53       # helmet in the output slot 200.53-200.66; window closes 200.8
LAKE_B = 530.0          # afloat at night, shore in view, "No place to dig in for the night"
LAKE_A_SHORE = 124.8    # last strokes; on the bank 127, digs down 129-133
HOG_B_DEATH = 65.6      # thrown by the hoglin at ~63, lands dead ~65.5 (respawn screen from 66)
HOG_A_WALK = 70.0       # "No food in sight in the Nether: going to the Overworld" walking to the portal
HOG_A_PORTAL = 86.0
HOG_A_SHEEP_END = 132.6  # mutton chunk 130, mutton drops at 132


def build():
    T = TL()

    # ---- S0 hook: the payoff first. Helmet on in the nether, then how it got one, then back to the nether.
    v = 0.3
    ve = T.say("l01", v)
    end = ve + 1.3
    t_mined = v + w("l01", "mined") - 0.15
    t_smelt = v + w("l01", "smelted") - 0.1
    t_made = v + w("l01", "made") - 0.1
    t_neth = v + w("l01", "nether") - 0.1
    t_helm = v + w("l01", "helmet")
    T.card(0, end, '<div class="paper dark"></div>', z=0)
    T.clip("helmfull", 0.0, t_mined, frm=HELM_NETHER_A, speed=1.0, cls="framed", zoom=None, badge=False)
    T.ring(t_helm, t_mined - t_helm, 862, 862, 100, "GOLD HELMET · FULL HEALTH", up=True)
    T.kin(0.5, t_mined - 0.5, "THE NETHER, IN A GOLD HAT", "0.95.62 · 15-min run · alive at full health", pos="tl", color="teal")
    T.clip("helm", t_mined, t_smelt - t_mined, frm=HELM_GOLD, speed=2.0, z=2, zoom=(1.0, 1.06), badge=False)
    T.img("videos/" + strip("s_helm"), t_mined, t_smelt - t_mined, z=30, kb=None, cls="strip", style=strip_w("s_helm"))
    T.kin(t_mined + 0.1, t_smelt - t_mined - 0.1, "MINES THE GOLD", "x2 · the errand has a 6-minute budget", pos="bl", color="teal")
    T.clip("craft", t_smelt, t_made - t_smelt, frm=HELM_FURNACE, speed=4.0, z=3, zoom=(1.0, 1.04), badge=False)
    T.kin(t_smelt + 0.05, t_made - t_smelt - 0.05, "SMELTS IT", "furnace · x4", pos="bl", color="teal")
    t_out = t_made + (HELM_OUT - HELM_CRAFT)
    T.clip("craft", t_made, t_out - t_made, frm=HELM_CRAFT, speed=1.0, z=4, zoom=(1.0, 1.04), badge=False)
    # the helmet sits in the output slot for two frames only (200.53-200.66): hold that frame under the ring
    T.img("videos/" + still("s_helmout", "craft", HELM_OUT + 0.05), t_out, t_neth - t_out, z=4, kb=(1.04, 1.06))
    T.kin(t_made + 0.05, t_neth - t_made - 0.05, "CRAFTS THE HELMET", "five ingots · x1", pos="bl", color="teal")
    T.ring(t_out, t_neth - t_out, 1224, 336, 120, "GOLD HELMET")
    T.clip("helmfull", t_neth, end - t_neth, frm=HELM_NETHER_B, speed=1.0, z=5, cls="framed", zoom=None, badge=False)
    T.kin(t_neth + 0.05, end - t_neth - 0.05, "INTO THE NETHER", "the same run · 7+ min there, full health", pos="tl", color="teal")
    T.card(0, end, '<div class="reel">3NDETZ LABS &middot; FIELD REPORT &middot; RELEASE 0.95.62</div>', z=45)
    T.t("tl.fromTo('.reel',{opacity:0},{opacity:1,duration:0.4},0.1);")
    T.stamp(ve + 0.1, end - ve - 0.1, "CLOSE ENOUGH", x=1450, y=560, rot=-8, size=100)
    t = end

    # ---- S1 the lake, side by side: left afloat all night (x24), right swims to the bank and digs in.
    v = t + 0.3
    ve = T.say("l02", v)
    end = ve + 1.5
    d = end - t
    T.card(t, d, '<div class="paper dark"></div>', z=0)
    T.clip("lakeB", t, d, frm=LAKE_B, speed=24.0, z=3, cls="half left", zoom=None, badge=False)
    t_bank = v + w("l02", "now") - 0.1   # x2 swim out, then x1.6 from the last strokes to the dig
    sp = 2.0
    T.clip("lakeA", t, t_bank - t, frm=LAKE_A_SHORE - (t_bank - t) * sp, speed=sp, z=3, cls="half right", zoom=None, badge=False)
    T.clip("lakeA", t_bank, end - t_bank, frm=LAKE_A_SHORE, speed=1.6, z=4, cls="half right", zoom=None, badge=False)
    T.card(t, d,
           '<div class="halflab left bad">BEFORE &middot; OLDER BUILD</div><div class="halflab right good">AFTER &middot; 0.95.62</div>'
           '<div class="hspeed" style="left:40px">x24</div>'
           '<div class="halfcap">night, every dig-in site under water &middot; separate runs, not a race</div>', z=10)
    T.card(t, t_bank - t, '<div class="hspeed">x2</div>', z=11)
    T.card(t_bank, end - t_bank, '<div class="hspeed">x1.6</div>', z=11)
    T.t(f"tl.fromTo('.halflab',{{opacity:0,y:-20}},{{opacity:1,y:0,duration:0.3}},{t + 0.1:.3f});")
    t_now = v + w("l02", "now") - 0.1
    T.img("videos/" + strip("s_lakeB"), t + 0.4, t_now - t - 0.4, z=30, kb=None, cls="strip low", style=strip_w("s_lakeB"))
    T.img("videos/" + strip("s_lakeA"), t_now, end - t_now, z=30, kb=None, cls="strip low", style=strip_w("s_lakeA"))
    T.t(f"tl.to('.half.left',{{opacity:0.55,duration:0.4}},{t_now:.3f});")
    T.ring(ve + 0.15, end - ve - 0.15, 1435, 430, 140, "DIGS IN")
    T.sfx.append(ve + 0.2)
    t = end

    # ---- S2 hoglin in stone gear: thrown off the ledge on "ledge", dead a beat later.
    v = t + 0.3
    ve = T.say("l03", v)
    end = ve + 1.1
    t_dead = v + w("l03", "ledge") + 0.6
    T.clip("hogB", t, t_dead - t, frm=HOG_B_DEATH - (t_dead - t), speed=1.0, zoom=(1.0, 1.08), badge=False)
    T.img("videos/" + still("s_dead", "hogB", HOG_B_DEATH), t_dead, end - t_dead, z=2, kb=(1.08, 1.1))
    t_hog = t_dead - (HOG_B_DEATH - 62.5)
    T.kin(t + 0.3, t_hog - t - 0.3, "HOGLIN HUNT, STONE SWORD", "older build · nether-fresh checkpoint · x1", pos="bl", color="red")
    T.kin(t_hog, 1.2, "THE HOGLIN, UP CLOSE", "40 health · throws on every hit", pos="bl", color="red")
    T.kin(t_hog + 1.2, end - t_hog - 1.2, "THROWN OFF THE LEDGE", "run log: was doomed to fall by Hoglin", pos="bl", color="red")
    T.stamp(ve + 0.15, end - ve - 0.15, "EXIT, PURSUED BY A HOGLIN", x=1250, y=330, rot=-8, size=84)
    t = end

    # ---- S3 the fix: no iron, no hoglins -> back through the portal, a sheep instead.
    v = t + 0.3
    ve = T.say("l04", v)
    end = ve + 1.1
    t_por = v + w("l04", "otherwise") - 0.1
    t_ow = v + w("l04", "bothers") - 0.25
    T.clip("hogA", t, t_por - t, frm=HOG_A_WALK, speed=2.0, zoom=(1.0, 1.06), badge=True)
    T.img("videos/" + strip("s_hogA"), t + 0.3, t_por - t - 0.3, z=30, kb=None, cls="strip", style=strip_w("s_hogA"))
    T.kin(t + 0.4, t_por - t - 0.4, "NO IRON, NO HOGLINS", "0.95.62 · same checkpoint · stone sword in hand", pos="bl", color="teal")
    T.clip("hogA", t_por, t_ow - t_por, frm=HOG_A_PORTAL, speed=4.0, z=2, zoom=(1.0, 1.1), badge=True)
    T.kin(t_por + 0.05, t_ow - t_por - 0.05, "BACK TO THE OVERWORLD", "for food", pos="bl", color="teal")
    sp = 1.0
    T.clip("hogA", t_ow, end - t_ow, frm=HOG_A_SHEEP_END - (end - t_ow) * sp, speed=sp, z=3, zoom=(1.0, 1.06), badge=True)
    T.kin(t_ow + 0.05, end - t_ow - 0.05, "OVERWORLD FOOD", "farm animals · x1", pos="bl", color="teal")
    t = end

    # ---- S4 numbers
    v = t + 0.25
    ve = T.say("l05", v)
    end = ve + 0.7
    T.card(t, end - t, '<div class="paper"></div>', z=0)
    stat = ('<div class="stat2"><div class="sth">MEASURED ON THE BENCH</div>'
            '<div class="shead"><span class="sl"></span><span class="sc bad">BEFORE</span><span class="sc good">0.95.62</span></div>'
            '<div class="srow"><span class="sl">night in a lake: left the water</span>'
            '<span class="sc"><div id="b1" class="bar bad"></div><b id="bv1" class="bad">0 / 4</b></span>'
            '<span class="sc"><div id="a1" class="bar good"></div><b id="av1" class="good">all valid</b></span></div>'
            '<div class="srow"><span class="sl">hoglin checkpoint: deaths</span>'
            '<span class="sc"><div id="b2" class="bar bad"></div><b id="bv2" class="bad">2 / 2</b></span>'
            '<span class="sc"><div id="a2" class="bar good"></div><b id="av2" class="good">1 / 2</b><i id="an2" class="snote2">a piglin</i></span></div>'
            '<div class="srow"><span class="sl">gold helmet, 15-min runs</span>'
            '<span class="sc"><b id="bv3" class="bad">&mdash;</b></span>'
            '<span class="sc"><div id="a3" class="bar good"></div><b id="av3" class="good">2 / 2</b><i id="an3" class="snote2">0 deaths</i></span></div>'
            '<div class="snote">nether, earlier replays: 7 of 7 dead or nearly dead in 3-6 min &middot; with the helmet: 7+ min at full health'
            '<br>separate runs, not a race &middot; no speed or FPS gain claimed &middot; the game is not beaten</div></div>')
    T.card(t, end - t, stat, z=5)
    T.t(f"tl.fromTo('.stat2 .srow',{{opacity:0,x:-40}},{{opacity:1,x:0,duration:0.3,stagger:0.1}},{t + 0.05:.3f});")
    anim = [("b1", 6, ("none", 1)), ("a1", 300, ("every", 1)), ("b2", 300, ("deaths", 1)), ("a2", 150, ("one", 1)),
            ("a3", 300, ("hats", 1))]
    for bid, wd, word in anim:
        tb = v + w("l05", *word) - 0.1
        T.t(f"tl.fromTo('#{bid}',{{width:0}},{{width:{wd},duration:0.45,ease:'power3.out'}},{tb:.3f});")
        T.t(f"tl.fromTo('#{bid[0]}v{bid[1]}',{{opacity:0}},{{opacity:1,duration:0.2}},{tb + 0.3:.3f});")
    T.t(f"tl.fromTo('#bv3',{{opacity:0}},{{opacity:1,duration:0.2}},{v + w('l05', 'helmets') - 0.1:.3f});")
    T.t(f"tl.fromTo('#an2',{{opacity:0}},{{opacity:1,duration:0.3}},{v + w('l05', 'piglin') - 0.1:.3f});")
    T.t(f"tl.fromTo('#an3',{{opacity:0}},{{opacity:1,duration:0.3}},{v + w('l05', 'deaths', 2) - 0.1:.3f});")
    T.t(f"tl.fromTo('.stat2 .snote',{{opacity:0}},{{opacity:1,duration:0.4}},{v + w('l05', 'helmets'):.3f});")
    t = end

    # ---- S5 close
    v = t + 0.4
    ve = T.say("l06", v)
    end = ve + 3.0
    d = end - t
    T.card(t, d, '<div class="paper dark"></div>', z=0)
    close = (f'<div class="closebox"><div id="cl-badge">{LOGO}</div><div id="cl-lab" class="labname big">3NDETZ LABS</div>'
             '<div id="cl-t" class="closet">unionclef 0.95.62</div><div id="cl-u" class="closeu">github.com/3ndetz/unionclef</div>'
             '<div id="cl-f" class="closef">open source · GPL-3.0 · Minecraft 1.21 · Fabric</div></div>'
             '<div id="cl-credit" class="credit">Music: “Hep Cats” by Kevin MacLeod (incompetech.com), licensed under CC BY 4.0</div>')
    T.card(t, d, close, z=5)
    T.t(f"tl.fromTo('#cl-badge',{{scale:0,rotation:180}},{{scale:1,rotation:0,duration:0.8,ease:'back.out(1.5)'}},{t + 0.1:.3f});")
    T.t(f"tl.fromTo('#cl-lab',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.5}},{t + 0.3:.3f});")
    T.t(f"tl.fromTo('#cl-t',{{opacity:0,scale:1.6}},{{opacity:1,scale:1,duration:0.5,ease:'expo.out'}},{v + w('l06', 'version'):.3f});")
    T.t(f"tl.fromTo(['#cl-u','#cl-f'],{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.4,stagger:0.2}},{v + w('l06', 'out'):.3f});")
    T.stamp(ve + 0.15, end - ve - 0.15, "TROUSERS: PENDING", x=1440, y=830, rot=-8, size=90)
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
    final = OUT / "cj-0.95.62-clean.mp4"
    mix(T, total, silent, final)
    print(f"final: {final} ({final.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
