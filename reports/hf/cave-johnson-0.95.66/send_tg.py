"""Send out/cj-0.95.66-tg.mp4 with telegram-caption.txt to the operator; write telegram-receipt.json.

    python send_tg.py

Credentials: mineswarm/.env via deploy/runner/tg_send.py (TG_BOT_TOKEN, OPERATOR_CHAT_ID); never printed.
Direct api.telegram.org times out from this host, so the upload goes through the host HTTP proxy from the same
le_bot env file (TG_PROXY in C:/repos/pet/health/.env; mineswarm's TG_BOT_PROXY is empty), as for 0.95.58-0.95.64. Refuses to resend once a receipt exists.
"""
import datetime
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2] / "deploy" / "runner"))
from tg_send import DEFAULT_ENV, TG_MAX_BYTES, api, load_env  # noqa: E402

clip = HERE / "out" / "cj-0.95.66-tg.mp4"
receipt = HERE / "telegram-receipt.json"
assert not receipt.exists(), "already delivered; do not resend"
caption = (HERE / "telegram-caption.txt").read_text(encoding="utf-8").strip()
assert len(caption) <= 1024, len(caption)
blob = clip.read_bytes()
assert 0 < len(blob) <= TG_MAX_BYTES
env = load_env(DEFAULT_ENV)
LE_BOT_ENV = "C:/repos/pet/health/.env"
proxy = env.get("TG_BOT_PROXY") or (load_env(LE_BOT_ENV).get("TG_PROXY") if Path(LE_BOT_ENV).exists() else None)
if proxy:
    urllib.request.install_opener(urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": proxy, "https": proxy})))
r = api(env["TG_BOT_TOKEN"], "sendVideo",
        {"chat_id": env["OPERATOR_CHAT_ID"], "caption": caption, "supports_streaming": "true",
         "width": "1920", "height": "1080", "duration": "68"},
        [("video", clip.name, blob)], timeout=600)
assert r.get("ok"), r.get("description")
out = {"delivered_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "ok": True,
       "message_id": r["result"]["message_id"], "video_sha256": hashlib.sha256(blob).hexdigest(),
       "via": "host http proxy (le_bot TG_PROXY)" if proxy else "direct"}
receipt.write_text(json.dumps(out, indent=2), encoding="utf-8")
print("delivered, message_id", out["message_id"])
