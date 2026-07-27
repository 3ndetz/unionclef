#!/usr/bin/env python3
"""Send stand artifacts (clips + a caption) to the operator's Telegram.

The existing mineswarm helper only does sendMessage, but the useful evidence from a
stand run is the CLIP — a text verdict is exactly the kind of self-reported claim the
user has been burned by before. This uploads the actual video.

  python3 deploy/runner/tg_send.py --text-file report.txt clip1.mp4 clip2.mp4
  python3 deploy/runner/tg_send.py --text "melee PASS" run/melee_basic.mp4

Credentials come from mineswarm/.env (TG_BOT_TOKEN, OPERATOR_CHAT_ID) and are never
printed. Telegram's bot upload cap is 50 MB per file; larger clips are reported and
skipped rather than failing the whole send.
"""
import argparse
import json
import mimetypes
import os
import sys
import urllib.parse
import urllib.request
import uuid

DEFAULT_ENV = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "..", "..", "..", "mineswarm", ".env")
TG_MAX_BYTES = 50 * 1024 * 1024


def load_env(path):
    d = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip().strip('"').strip("'")
    return d


def _multipart(fields, files):
    """Build a multipart/form-data body. files: [(field, filename, bytes)]."""
    boundary = "----uctest" + uuid.uuid4().hex
    out = bytearray()
    for k, v in fields.items():
        out += f"--{boundary}\r\n".encode()
        out += f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode()
        out += f"{v}\r\n".encode()
    for field, filename, blob in files:
        ctype = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        out += f"--{boundary}\r\n".encode()
        out += (f'Content-Disposition: form-data; name="{field}"; '
                f'filename="{filename}"\r\n').encode()
        out += f"Content-Type: {ctype}\r\n\r\n".encode()
        out += blob + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def api(token, method, fields, files=None, timeout=300):
    url = f"https://api.telegram.org/bot{token}/{method}"
    if files:
        body, ctype = _multipart(fields, files)
        req = urllib.request.Request(url, data=body, headers={"Content-Type": ctype})
    else:
        req = urllib.request.Request(url, data=urllib.parse.urlencode(fields).encode())
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("clips", nargs="*", help="video files to upload")
    ap.add_argument("--text", help="caption / message text")
    ap.add_argument("--text-file", help="read the message text from a file (utf-8)")
    ap.add_argument("--env", default=DEFAULT_ENV)
    args = ap.parse_args()

    env = load_env(args.env)
    token, chat = env.get("TG_BOT_TOKEN"), env.get("OPERATOR_CHAT_ID")
    if not token or not chat:
        print("missing TG_BOT_TOKEN / OPERATOR_CHAT_ID", file=sys.stderr)
        return 2

    text = args.text or ""
    if args.text_file:
        with open(args.text_file, encoding="utf-8") as f:
            text = f.read()

    if text:
        # Telegram caps a single message at 4096 chars; split on line boundaries.
        chunk, chunks = "", []
        for line in text.splitlines(keepends=True):
            if len(chunk) + len(line) > 3900:
                chunks.append(chunk)
                chunk = ""
            chunk += line
        chunks.append(chunk)
        for c in chunks:
            if c.strip():
                api(token, "sendMessage",
                    {"chat_id": chat, "text": c, "disable_web_page_preview": "true"})
        print(f"sent text ({len(chunks)} message(s))")

    for path in args.clips:
        if not os.path.exists(path):
            print(f"missing: {path}", file=sys.stderr)
            continue
        size = os.path.getsize(path)
        if size > TG_MAX_BYTES:
            print(f"skip {os.path.basename(path)}: {size/1e6:.1f} MB > 50 MB bot limit",
                  file=sys.stderr)
            continue
        with open(path, "rb") as f:
            blob = f.read()
        name = os.path.basename(path)
        r = api(token, "sendVideo",
                {"chat_id": chat, "caption": name, "supports_streaming": "true"},
                [("video", name, blob)])
        print(f"sent {name} ({size/1e6:.1f} MB): ok={r.get('ok')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
