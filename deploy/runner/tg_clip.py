#!/usr/bin/env python3
"""Record a short screen clip of the test client and send it to the operator's Telegram.

Grabs display :0 inside the client container with ffmpeg (the same capture the smoke
runner uses), copies the file out, and posts it to the operator chat as a video.

    python deploy/runner/tg_clip.py <seconds> "<caption>"
    python deploy/runner/tg_clip.py 20 "escalation working: bot digs down to the ore"

Telegram credentials are read from mineswarm/.env (TG_BOT_TOKEN, OPERATOR_CHAT_ID) --
only those two keys are parsed and the token is never printed. Set UC_ENV to point at a
different .env. Exit 0 on a delivered video, non-zero otherwise.
"""
import json, os, re, subprocess, sys, time, urllib.request, urllib.error, uuid

CLIENT = os.environ.get("UC_CLIENT", "uctest-mc-tester1")
ENV_PATH = os.environ.get("UC_ENV", r"C:/repos/pet/mineswarm/.env")


def load_tg():
    env = {}
    for ln in open(ENV_PATH, encoding="utf-8", errors="replace"):
        m = re.match(r'\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$', ln)
        if m:
            env[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    return env["TG_BOT_TOKEN"], env["OPERATOR_CHAT_ID"]


def record(secs):
    subprocess.run(["docker", "exec", CLIENT, "sh", "-c",
                    "pkill -INT ffmpeg 2>/dev/null; sleep 0.3; true"], capture_output=True)
    cmd = (f"ffmpeg -y -f x11grab -framerate 15 -i :0 -t {int(secs)} "
           "-c:v libx264 -preset ultrafast -g 15 -b:v 1100k -maxrate 1400k -bufsize 2M "
           "-pix_fmt yuv420p -movflags +frag_keyframe+empty_moov+default_base_moof "
           "/mc-data/clip_now.mp4 >/dev/null 2>&1; echo exit=$?")
    r = subprocess.run(["docker", "exec", CLIENT, "sh", "-c", cmd],
                       capture_output=True, text=True, timeout=int(secs) + 60)
    if "exit=0" not in r.stdout:
        raise RuntimeError(f"ffmpeg failed: {r.stdout.strip()} {r.stderr.strip()[-200:]}")
    dst = os.path.join(os.environ.get("TEMP", "."), "uc_clip_%d.mp4" % int(time.time()))
    subprocess.run(["docker", "cp", f"{CLIENT}:/mc-data/clip_now.mp4", dst], check=True,
                   capture_output=True)
    if not (os.path.exists(dst) and os.path.getsize(dst) > 1000):
        raise RuntimeError("recording produced nothing usable")
    return dst


def send(path, caption):
    tok, chat = load_tg()
    boundary = "----tg" + uuid.uuid4().hex

    def field(name, val):
        return (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"'
                f'\r\n\r\n{val}\r\n').encode("utf-8")

    body = field("chat_id", chat) + field("caption", caption) + field("supports_streaming", "true")
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="video"; '
             f'filename="clip.mp4"\r\nContent-Type: video/mp4\r\n\r\n').encode("utf-8")
    body += open(path, "rb").read() + b"\r\n" + f"--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(f"https://api.telegram.org/bot{tok}/sendVideo", data=body,
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=180))
        return bool(d.get("ok")), (d.get("result") or {}).get("message_id")
    except urllib.error.HTTPError as e:
        return False, "HTTP %d %s" % (e.code, e.read().decode()[:200])


if __name__ == "__main__":
    secs = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    caption = sys.argv[2] if len(sys.argv) > 2 else ""
    print(f"recording {secs}s from {CLIENT}...")
    clip = record(secs)
    print(f"recorded {os.path.getsize(clip) // 1024} KB; sending...")
    ok, info = send(clip, caption)
    print("TG ok:", ok, "|", info)
    sys.exit(0 if ok else 1)
