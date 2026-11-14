"""One captioned video from bench clips and text cards (the way docs/VIDEO_REPORTS.md asks: open on the
payoff, every clip names its event, before/after side by side, numbers as bars, English only).

    python report.py storyboard.json out.mp4

storyboard.json: a list of
    {"hook": "path.mp4", "from": 0, "to": 8, "line": "one big line", "sub": "small line", "speed": 1}
    {"clip": "path.mp4", "from": 0, "to": 25, "title": "...", "caption": "...", "speed": 1, "zoom": 0.06}
    {"split": [{"clip": "a.mp4", "from": 0, "label": "before ...", "caption": "..."},
               {"clip": "b.mp4", "from": 0, "label": "after ...", "caption": "..."}],
     "title": "...", "seconds": 12, "speed": 1}
    {"stats": [["label", was, now, "unit"], ...], "title": "...", "where": "where it was measured", "seconds": 6}
    {"card": ["title line", "line", ...], "seconds": 4}
"""
import json
import pathlib
import subprocess
import sys
import tempfile

from bench import FFMPEG

FONT = "C\\:/Windows/Fonts/segoeui.ttf"
FONT_B = "C\\:/Windows/Fonts/segoeuib.ttf"
W, H, FPS = 1920, 1080, 30
# the VibeCADing navy and ink; pink only for what the machine does now
PAPER, INK, MUTED, PINK, WAS = "0x0b1330", "0xe6ecfa", "0x9aa6c4", "0xff6fb0", "0x4b5675"


def textfile(tmp, name, text):
    p = pathlib.Path(tmp) / name
    p.write_text(text, encoding="utf-8")
    return p.as_posix().replace(":", "\\:")


def encode(args, out):
    subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", *args, "-an",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-r", str(FPS),
                    str(out)], check=True)
    return out


def blank(seconds):
    return ["-f", "lavfi", "-i", f"color=c={PAPER}:s={W}x{H}:r={FPS}:d={seconds}"]


def card(tmp, i, lines, seconds):
    draws = []
    y0 = H // 2 - 60 * len(lines) // 2
    for j, line in enumerate(lines):
        f = textfile(tmp, f"c{i}_{j}.txt", line)
        size, font, col = (64, FONT_B, INK) if j == 0 else (40, FONT, MUTED)
        draws.append(f"drawtext=fontfile='{font}':textfile='{f}':fontsize={size}:fontcolor={col}:"
                     f"x=(w-text_w)/2:y={y0 + j * 70 + (0 if j == 0 else 30)}")
    return encode([*blank(seconds), "-vf", ",".join(draws)], pathlib.Path(tmp) / f"{i:02d}.mp4")


def push_in(seconds, zoom):
    """A slow push-in over the clip: scaled up by zoom at its end, cropped to the frame's centre."""
    if not zoom:
        return f"scale={W}:{H}"
    return (f"scale={W}:{H},scale=eval=frame:w='trunc({W}*(1+{zoom}*t/{seconds:.2f})/2)*2':h=-2,"
            f"crop={W}:{H}")


def badge(tmp, i, speed):
    b = textfile(tmp, f"s{i}.txt", f"x{speed:g}")
    return f",drawtext=fontfile='{FONT_B}':textfile='{b}':fontsize=40:fontcolor={PINK}:x=w-text_w-40:y=34"


def clip(tmp, i, c):
    speed = c.get("speed", 1)
    seconds = (c.get("to", 30) - c.get("from", 0)) / speed
    t = textfile(tmp, f"t{i}.txt", c["title"])
    cap = textfile(tmp, f"k{i}.txt", c.get("caption", ""))
    vf = (f"setpts=PTS/{speed},fps={FPS},{push_in(seconds, c.get('zoom', 0.06))},"
          f"drawbox=x=0:y=0:w=iw:h=118:color={PAPER}@0.72:t=fill,"
          f"drawtext=fontfile='{FONT_B}':textfile='{t}':fontsize=46:fontcolor={INK}:x=40:y=14,"
          f"drawtext=fontfile='{FONT}':textfile='{cap}':fontsize=30:fontcolor={MUTED}:x=40:y=72")
    if speed != 1:
        vf += badge(tmp, i, speed)
    return encode(["-ss", str(c.get("from", 0)), "-to", str(c.get("to", 9999)), "-i", c["clip"], "-vf", vf],
                  pathlib.Path(tmp) / f"{i:02d}.mp4")


def hook(tmp, i, c):
    """The payoff first: the best seconds, one big line over them."""
    speed = c.get("speed", 1)
    seconds = (c.get("to", 8) - c.get("from", 0)) / speed
    line = textfile(tmp, f"h{i}.txt", c["line"])
    sub = textfile(tmp, f"hs{i}.txt", c.get("sub", ""))
    vf = (f"setpts=PTS/{speed},fps={FPS},{push_in(seconds, c.get('zoom', 0.08))},"
          f"drawbox=x=0:y=ih-250:w=iw:h=250:color={PAPER}@0.72:t=fill,"
          f"drawtext=fontfile='{FONT_B}':textfile='{line}':fontsize=62:fontcolor={INK}:x=(w-text_w)/2:y=h-215,"
          f"drawtext=fontfile='{FONT}':textfile='{sub}':fontsize=36:fontcolor={MUTED}:x=(w-text_w)/2:y=h-110")
    return encode(["-ss", str(c.get("from", 0)), "-to", str(c.get("to", 9999)), "-i", c["hook"], "-vf", vf],
                  pathlib.Path(tmp) / f"{i:02d}.mp4")


def split(tmp, i, c):
    """Two clips side by side, each with its label and its measured fact, one title over both."""
    speed = c.get("speed", 1)
    seconds = c.get("seconds", 12)
    a, b = c["split"]
    title = textfile(tmp, f"st{i}.txt", c.get("title", ""))
    ins, parts = [], []
    for j, side in enumerate((a, b)):
        ins += ["-ss", str(side.get("from", 0)), "-t", str(seconds * speed), "-i", side["clip"]]
        lab = textfile(tmp, f"sl{i}_{j}.txt", side["label"])
        cap = textfile(tmp, f"sc{i}_{j}.txt", side.get("caption", ""))
        col = PINK if j == 1 else MUTED
        parts.append(f"[{j + 1}:v]setpts=PTS/{speed},fps={FPS},scale=944:531,"
                     f"tpad=stop_mode=clone:stop_duration={seconds}[v{j}]")
        parts.append(f"[v{j}]drawtext=fontfile='{FONT_B}':textfile='{lab}':fontsize=34:fontcolor={col}:x=4:y=4:"
                     f"box=1:boxcolor={PAPER}@0.72:boxborderw=10[w{j}]")
        parts.append(f"[w{j}]pad=944:640:0:0:color={PAPER},"
                     f"drawtext=fontfile='{FONT}':textfile='{cap}':fontsize=28:fontcolor={INK}:x=4:y=552[s{j}]")
    graph = ";".join(parts) + (
        f";[0:v][s0]overlay=x=16:y=250[o0];[o0][s1]overlay=x=960:y=250,"
        f"drawtext=fontfile='{FONT_B}':textfile='{title}':fontsize=54:fontcolor={INK}:x=(w-text_w)/2:y=110")
    if speed != 1:
        graph += badge(tmp, i, speed)
    return encode([*blank(seconds), *ins, "-filter_complex", graph, "-t", str(seconds)],
                  pathlib.Path(tmp) / f"{i:02d}.mp4")


def stats(tmp, i, c):
    """Was and now as bars: grey for was, pink for now, the numbers at the bars' ends."""
    rows = c["stats"]
    seconds = c.get("seconds", 6)
    title = textfile(tmp, f"bt{i}.txt", c.get("title", ""))
    where = textfile(tmp, f"bw{i}.txt", c.get("where", ""))
    draws = [f"drawtext=fontfile='{FONT_B}':textfile='{title}':fontsize=58:fontcolor={INK}:x=160:y=120",
             f"drawtext=fontfile='{FONT}':textfile='{where}':fontsize=30:fontcolor={MUTED}:x=160:y=200"]
    top, full = 300, 1100
    for j, (label, was, now, unit) in enumerate(rows):
        scale = max(was, now, 1e-9)
        y = top + j * 190
        lab = textfile(tmp, f"bl{i}_{j}.txt", label)
        draws.append(f"drawtext=fontfile='{FONT_B}':textfile='{lab}':fontsize=36:fontcolor={INK}:x=160:y={y}")
        for k, (v, col, name) in enumerate(((was, WAS, "was"), (now, PINK, "now"))):
            w = max(6, int(full * v / scale))
            yy = y + 56 + k * 52
            num = textfile(tmp, f"bn{i}_{j}_{k}.txt", f"{name}  {v:g}{unit}")
            draws.append(f"drawbox=x=160:y={yy}:w={w}:h=38:color={col}:t=fill")
            draws.append(f"drawtext=fontfile='{FONT}':textfile='{num}':fontsize=30:fontcolor={INK}:x={160 + w + 20}:y={yy + 2}")
    return encode([*blank(seconds), "-vf", ",".join(draws)], pathlib.Path(tmp) / f"{i:02d}.mp4")


def main():
    board = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    final = pathlib.Path(sys.argv[2])
    with tempfile.TemporaryDirectory() as tmp:
        parts = []
        for i, s in enumerate(board):
            kind = next(k for k in ("hook", "split", "stats", "card", "clip") if k in s)
            parts.append({"hook": hook, "split": split, "stats": stats, "clip": clip}.get(kind, None)(tmp, i, s)
                         if kind != "card" else card(tmp, i, s["card"], s.get("seconds", 4)))
        lst = pathlib.Path(tmp) / "list.txt"
        lst.write_text("".join(f"file '{p.as_posix()}'\n" for p in parts), encoding="utf-8")
        subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0",
                        "-i", str(lst), "-c", "copy", str(final)], check=True)
    print(final)


if __name__ == "__main__":
    main()
