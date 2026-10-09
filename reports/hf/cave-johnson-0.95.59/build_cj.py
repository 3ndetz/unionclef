"""Cave Johnson field report for release 0.95.59: cut footage, compose in HyperFrames, mix, render.

    python build_cj.py            # cut + compose + render + mix -> out/cj-0.95.59-clean.mp4
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

# key -> (file in footage/, ffmpeg crop). The 0.86 centre crop (bias 0.3) trims the hotbar off; "chat" keeps the
# lower-left quarter so the death line in chat is readable at 3x.
HUDLESS = "crop=iw*0.86:ih*0.86:(iw-iw*0.86)/2:(ih-ih*0.86)*0.3"
FOOT = {
    "before": ("bridge-before.mp4", HUDLESS),       # 0.95.58: stop at src ~5.9, walks off, void 7.13-10.9
    "beforechat": ("bridge-before.mp4", "crop=640:360:0:120"),  # respawn, "tester1 fell out of the world"
    "after": ("bridge-after.mp4", HUDLESS),         # 0.95.59 candidate: stop at src ~6.55, stays at the edge
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

    def ring(self, t0: float, dur: float, x: int, y: int, r: int, word: str, z: int = 55):
        g = self.id("r")
        self.card(t0, dur, f'<div id="{g}" class="ring" style="left:{x - r}px;top:{y - r}px;width:{2 * r}px;height:{2 * r}px">'
                           f'<span>{html.escape(word)}</span></div>', z=z)
        self.t(f"tl.fromTo('#{g}',{{scale:1.8,opacity:0}},{{scale:1,opacity:1,duration:0.35,ease:'back.out(2)'}},{t0:.3f});")

    def say(self, line: str, t0: float):
        self.voice.append((line, t0))
        return t0 + vdur(line)


# Source seconds, read off frames (review/before-fall.png, review/after-stop.png): the last placement box
# disappears at the stop. Task-relative stop is ~2.45 s in both runs (result.json cancelled_at_s).
STOP_B = 5.9     # 0.95.58: stop
VOID_B = 7.13    # 0.95.58: screen black, falling out of the world (respawn at ~11.0)
STOP_A = 6.55    # 0.95.59: stop


def build():
    T = TL()

    # ---- S0 hook: the payoff first. 0.95.59 bridging backwards, the stop lands on the word "stop".
    T.say("l01", 0.3)
    end = 0.3 + vdur("l01") + 0.4
    stop_t = 0.3 + w("l01", "stop")
    T.clip("after", 0.0, end, frm=STOP_A - stop_t, speed=1.0, zoom=(1.0, 1.08), badge=False)
    T.card(0, end, '<div class="reel">3NDETZ LABS &middot; FIELD REPORT &middot; RELEASE 0.95.59</div>', z=45)
    T.t("tl.fromTo('.reel',{opacity:0},{opacity:1,duration:0.4},0.1);")
    T.kin(0.4, stop_t - 0.4, "BRIDGING BACKWARDS", "placing a block every step", pos="tl", color="teal")
    T.kin(stop_t, end - stop_t, "STOP. STAYS ON THE BRIDGE", "0.95.59 · one stop · normal speed", pos="tl", color="red")
    t = end

    # ---- S1 side by side, same fixture, both x0.5, both stops on the word "stop"
    v = t + 0.3
    end = T.say("l02", v) + 0.5
    sp = 0.5
    st = v + w("l02", "stop")
    T.card(t, end - t, '<div class="paper dark"></div>', z=0)
    T.clip("before", t, end - t, frm=STOP_B - (st - t) * sp, speed=sp, z=3, cls="half left", zoom=None, badge=False)
    T.clip("after", t, end - t, frm=STOP_A - (st - t) * sp, speed=sp, z=3, cls="half right", zoom=None, badge=False)
    T.card(t, end - t,
           '<div class="halflab left bad">BEFORE &middot; 0.95.58</div><div class="halflab right good">AFTER &middot; 0.95.59</div>'
           '<div class="hspeed" style="left:40px">x0.5</div><div class="hspeed">x0.5</div>'
           '<div class="halfcap">same bridge fixture &middot; one stop ~2.4 s in &middot; separate runs, not a race</div>', z=10)
    T.t(f"tl.fromTo('.halflab',{{opacity:0,y:-20}},{{opacity:1,y:0,duration:0.3}},{t + 0.1:.3f});")
    T.t(f"tl.to('.half.left',{{opacity:0.5,duration:0.4}},{v + w('l02', 'this'):.3f});")
    void_t = st + (VOID_B - STOP_B) / sp
    T.ring(st + 0.05, void_t - st - 0.05, 485, 430, 150, "STOP")
    T.ring(st + 0.05, v + w("l02", "this") - st - 0.05, 1435, 430, 150, "STOP")
    T.kin(st + 0.8, void_t - st - 0.8, "LEFT: STILL WALKING BACK", "sneak released, move back still held", pos="bl", color="red")
    T.ring(void_t, end - void_t, 485, 430, 150, "THE VOID")
    T.ring(v + w("l02", "stays"), end - v - w("l02", "stays"), 1435, 430, 150, "STAYS PUT")
    t = end

    # ---- S2 the fall, replayed, then the chat line that proves it
    v = t + 0.3
    end = T.say("l03", v) + 0.5
    cut2 = v + w("l03", "it", 2) - 0.1                       # "It is an old bug"
    T.clip("before", t, cut2 - t, frm=VOID_B - (v + w("l03", "void") - t) * 0.5, speed=0.5, zoom=(1.0, 1.1))
    T.kin(t + 0.1, cut2 - t - 0.1, "0.95.58: OFF ITS OWN BRIDGE", "replay x0.5 · y -60 → -248", pos="tl", color="red")
    T.clip("beforechat", cut2, end - cut2, frm=11.3, zoom=None)
    T.card(cut2, end - cut2, '<div id="chatbox" class="chatbox"><span>FELL OUT OF THE WORLD</span></div>', z=55)
    T.t(f"tl.fromTo('#chatbox',{{opacity:0,scale:1.3}},{{opacity:1,scale:1,duration:0.3,ease:'back.out(2)'}},{cut2 + 0.2:.3f});")
    T.kin(cut2 + 0.1, end - cut2 - 0.1, "FOUND BY THE 0.95.58 TESTS", "the bug is older than 0.95.58", pos="tl", color="teal")
    T.stamp(v + w("l03", "tested"), end - v - w("l03", "tested"), "VOID: TESTED", x=1380, y=560, rot=-8, size=110)
    t = end

    # ---- S3 the fix (keys), then the numbers
    v = t + 0.25
    end = T.say("l04", v) + 0.5
    s3 = v + w("l04", "six") - 0.15
    T.card(t, end - t, '<div class="paper"></div>', z=0)
    keys = ('<div class="keys"><div class="kh">WHEN A BRIDGE IS STOPPED</div>'
            '<div class="krow khead"><span class="kc0"></span><span class="kc1">0.95.58</span><span class="kc2">0.95.59</span></div>'
            '<div class="krow"><span class="kc0"><span class="cap">SHIFT</span><span class="kn">sneak</span></span>'
            '<span class="kc1 good">released</span><span class="kc2 good">released</span></div>'
            '<div class="krow"><span class="kc0"><span class="cap">S</span><span class="kn">move back</span></span>'
            '<span class="kc1 badk">still held</span><span id="knew" class="kc2 good">released</span></div>'
            '<div class="snote">the bridge walks backwards while it places; its stop forgot that key</div></div>')
    T.card(t, s3 - t, keys, z=5)
    T.t(f"tl.fromTo('.keys .krow',{{opacity:0,x:-40}},{{opacity:1,x:0,duration:0.3,stagger:0.12}},{t + 0.05:.3f});")
    T.t(f"tl.fromTo('#knew',{{opacity:0,scale:1.8}},{{opacity:1,scale:1,duration:0.3,ease:'back.out(2)'}},{v + w('l04', 'back'):.3f});")
    stat = ('<div class="stat"><div class="sth">STOPPED MID-BUILD · 0.95.59 CANDIDATE</div>'
            '<div class="srow"><span class="sl">bridge + pillar stops</span><div id="sa1" class="sbar good"></div><span id="sv1" class="sv good">6 / 6</span></div>'
            '<div class="srow"><span class="sl">stop mid-gap, walled 6 blocks</span><div id="sa2" class="sbar good"></div><span id="sv2" class="sv good">3 / 3</span></div>'
            '<div class="srow"><span class="sl">15-min playthrough</span><div id="sa3" class="sbar good"></div><span id="sv3" class="sv good">0 deaths</span></div>'
            '<div class="snote">drift after the stop under 0.04 blocks · 30 FPS · health 20 · no speed or FPS gain claimed</div></div>')
    T.card(s3, end - s3, stat, z=5)
    for i, word in enumerate(("six", "three", "fifteen"), 1):
        at = v + w("l04", word) - 0.1
        T.t(f"tl.fromTo('#sa{i}',{{width:0}},{{width:560,duration:0.5,ease:'power3.out'}},{at:.3f});")
        T.t(f"tl.fromTo('#sv{i}',{{opacity:0}},{{opacity:1,duration:0.2}},{at + 0.35:.3f});")
    t = end

    # ---- S4 close
    v = t + 0.4
    ve = T.say("l05", v)
    end = ve + 3.0
    d = end - t
    T.card(t, d, '<div class="paper dark"></div>', z=0)
    close = (f'<div class="closebox"><div id="cl-badge">{LOGO}</div><div id="cl-lab" class="labname big">3NDETZ LABS</div>'
             '<div id="cl-t" class="closet">unionclef 0.95.59</div><div id="cl-u" class="closeu">github.com/3ndetz/unionclef</div>'
             '<div id="cl-f" class="closef">open source · GPL-3.0 · Minecraft 1.21 · Fabric</div></div>'
             '<div id="cl-credit" class="credit">Music: “Hep Cats” by Kevin MacLeod (incompetech.com), licensed under CC BY 4.0</div>')
    T.card(t, d, close, z=5)
    T.t(f"tl.fromTo('#cl-badge',{{scale:0,rotation:180}},{{scale:1,rotation:0,duration:0.8,ease:'back.out(1.5)'}},{t + 0.1:.3f});")
    T.t(f"tl.fromTo('#cl-lab',{{opacity:0,y:30}},{{opacity:1,y:0,duration:0.5}},{t + 0.3:.3f});")
    T.t(f"tl.fromTo('#cl-t',{{opacity:0,scale:1.6}},{{opacity:1,scale:1,duration:0.5,ease:'expo.out'}},{v + w('l05', 'version'):.3f});")
    T.t(f"tl.fromTo(['#cl-u','#cl-f'],{{opacity:0,y:20}},{{opacity:1,y:0,duration:0.4,stagger:0.2}},{v + w('l05', 'out'):.3f});")
    T.stamp(ve + 0.15, end - ve - 0.15, "STAYS PUT", x=1480, y=830, rot=-8, size=100)
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
.chatbox {{ position:absolute; left:4px; top:908px; width:500px; height:76px; border:8px solid #e3a73a; border-radius:12px;
            box-shadow:0 0 0 4px rgba(29,42,47,0.6); }}
.chatbox span {{ position:absolute; left:0; bottom:100%; transform:translateY(-10px); white-space:nowrap; font-family:"Bebas";
                 font-size:56px; letter-spacing:3px; color:#1d2a2f; background:#e3a73a; padding:2px 16px 0; }}
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
        filt.append(f"[{n + j + 1}:a]aresample=48000,volume=0.22,adelay={ms}|{ms},apad[s{j}]")
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
    filt.append("[vo][duck][sfx]amix=inputs=3:normalize=0,loudnorm=I=-16:TP=-3:LRA=9,aresample=48000[aout]")
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
    final = OUT / "cj-0.95.59-clean.mp4"
    mix(T, total, silent, final)
    print(f"final: {final} ({final.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
