#!/usr/bin/env python3
"""Speed up a screen recording sitting in the client container and send it to Telegram.

Used for playthrough clips: an 8-10 min @gamer run recorded to /mc-data/full_run.mp4 is
unwatchable at 1x, so this time-compresses it (default 12x) and posts the result. The ffmpeg
re-encode runs INSIDE the client container (that is where ffmpeg lives); only the small output
is copied out and uploaded.

    python deploy/runner/tg_speedup.py /mc-data/full_run.mp4 12 "проход @gamer, ускорено 12x"

Telegram creds come from mineswarm/.env (TG_BOT_TOKEN, OPERATOR_CHAT_ID); the token is never
printed. Set UC_CLIENT / UC_ENV to override the container / env path.
"""
import json, os, re, subprocess, sys, uuid, urllib.request, urllib.error

CLIENT = os.environ.get("UC_CLIENT", "uctest-mc-tester1")
ENV_PATH = os.environ.get("UC_ENV", r"C:/repos/pet/mineswarm/.env")


def load_tg():
    env = {}
    for ln in open(ENV_PATH, encoding="utf-8", errors="replace"):
        m = re.match(r'\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$', ln)
        if m:
            env[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    return env["TG_BOT_TOKEN"], env["OPERATOR_CHAT_ID"]


def speed_up(src_in_container, factor):
    """Re-encode src at `factor`x speed inside the container; return the local copied path."""
    out_c = "/mc-data/_sped_%dx.mp4" % int(factor)
    # setpts divides timestamps -> faster playback; re-encode small (crf 30), drop audio.
    cmd = (f'ffmpeg -y -i {src_in_container} -filter:v "setpts=PTS/{int(factor)}" -an '
           f'-c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p '
           f'-movflags +faststart {out_c} >/tmp/sp.log 2>&1; echo exit=$?')
    r = subprocess.run(["docker", "exec", CLIENT, "sh", "-c", cmd],
                       capture_output=True, text=True, timeout=600)
    if "exit=0" not in r.stdout:
        tail = subprocess.run(["docker", "exec", CLIENT, "sh", "-c", "tail -5 /tmp/sp.log"],
                              capture_output=True, text=True).stdout
        raise RuntimeError("speedup ffmpeg failed: " + tail[-300:])
    dst = os.path.join(os.environ.get("TEMP", "."), "uc_sped_%s.mp4" % uuid.uuid4().hex[:8])
    subprocess.run(["docker", "cp", f"{CLIENT}:{out_c}", dst], check=True, capture_output=True)
    if not (os.path.exists(dst) and os.path.getsize(dst) > 1000):
        raise RuntimeError("sped-up file came out empty")
    return dst


def send(path, caption):
    tok, chat = load_tg()
    boundary = "----tg" + uuid.uuid4().hex

    def field(name, val):
        return (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"'
                f'\r\n\r\n{val}\r\n').encode("utf-8")

    body = field("chat_id", chat) + field("caption", caption) + field("supports_streaming", "true")
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="video"; '
             f'filename="run.mp4"\r\nContent-Type: video/mp4\r\n\r\n').encode("utf-8")
    body += open(path, "rb").read() + b"\r\n" + f"--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(f"https://api.telegram.org/bot{tok}/sendVideo", data=body,
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=300))
        return bool(d.get("ok")), (d.get("result") or {}).get("message_id")
    except urllib.error.HTTPError as e:
        return False, "HTTP %d %s" % (e.code, e.read().decode()[:200])


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else "/mc-data/full_run.mp4"
    factor = int(sys.argv[2]) if len(sys.argv) > 2 else 12
    caption = sys.argv[3] if len(sys.argv) > 3 else ""
    print(f"speeding {src} up {factor}x inside {CLIENT}...")
    local = speed_up(src, factor)
    print(f"sped-up size {os.path.getsize(local)//1024} KB; sending...")
    ok, info = send(local, caption)
    print("TG ok:", ok, "|", info)
    sys.exit(0 if ok else 1)
