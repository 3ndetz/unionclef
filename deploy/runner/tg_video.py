#!/usr/bin/env python3
"""Send a video (or any file) to the operator's Telegram.

tg_report.py in the mineswarm repo sends TEXT only, and a report about a bot that walks is worth
more with a clip attached. This lives HERE rather than as an edit over there because the other
repo backs the user's live streamer stack and does not need a change for our reporting.

Credentials come from the same place tg_report.py reads them and are never printed.

    python deploy/runner/tg_video.py <file> ["caption"]
"""
import json
import mimetypes
import os
import sys
import urllib.request
import uuid

ENV = os.path.join(os.path.dirname(__file__), "..", "..", "..", "mineswarm", ".env")


def load_env():
    d = {}
    with open(ENV, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip().strip('"').strip("'")
    return d


def _multipart(fields, filename, payload, field_name):
    """Build a multipart/form-data body. urllib has no uploader, and pulling in requests for one
    call is not worth a dependency."""
    boundary = "----uc" + uuid.uuid4().hex
    out = bytearray()
    for k, v in fields.items():
        out += f"--{boundary}\r\n".encode()
        out += f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode()
        out += f"{v}\r\n".encode()
    ctype = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    out += f"--{boundary}\r\n".encode()
    out += (f'Content-Disposition: form-data; name="{field_name}"; '
            f'filename="{os.path.basename(filename)}"\r\n').encode()
    out += f"Content-Type: {ctype}\r\n\r\n".encode()
    out += payload
    out += f"\r\n--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def send(token, chat, path, caption, proxy=None):
    # sendVideo renders inline in the client; sendDocument is the fallback for anything Telegram
    # declines to transcode, so a clip never fails to arrive merely for being an odd container.
    method, field = ("sendVideo", "video") if path.lower().endswith(
        (".mp4", ".mov", ".m4v")) else ("sendDocument", "document")
    with open(path, "rb") as fh:
        payload = fh.read()
    fields = {"chat_id": chat, "caption": caption[:1024], "supports_streaming": "true"}
    body, ctype = _multipart(fields, path, payload, field)
    if proxy:
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"https": proxy, "http": proxy}))
    else:
        opener = urllib.request.build_opener()
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/{method}", data=body)
    req.add_header("Content-Type", ctype)
    with opener.open(req, timeout=300) as r:
        return json.loads(r.read().decode())


def main():
    env = load_env()
    token = env.get("TG_BOT_TOKEN")
    chat = env.get("OPERATOR_CHAT_ID") or env.get("TELEGRAM_CHAT_ID")
    proxy = env.get("TG_BOT_PROXY") or None
    if not token or not chat:
        print("ERROR: missing TG_BOT_TOKEN / OPERATOR_CHAT_ID")
        sys.exit(1)
    path = sys.argv[1]
    caption = sys.argv[2] if len(sys.argv) > 2 else ""
    size_mb = os.path.getsize(path) / (1024 * 1024)
    if size_mb > 49:
        print(f"ERROR: {path} is {size_mb:.1f} MB; Telegram bots cap uploads at 50 MB")
        sys.exit(1)
    for attempt, px in enumerate([None, proxy]):
        if attempt == 1 and not proxy:
            break
        try:
            res = send(token, chat, path, caption, px)
            print(json.dumps({"ok": res.get("ok"), "via": "proxy" if px else "direct",
                              "msg_id": res.get("result", {}).get("message_id"),
                              "mb": round(size_mb, 1)}, ensure_ascii=False))
            return
        except Exception as exc:                       # noqa: BLE001 -- report and try the proxy
            print(f"attempt {'proxy' if px else 'direct'} failed: {str(exc)[:160]}")
    sys.exit(1)


if __name__ == "__main__":
    main()
