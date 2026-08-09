#!/usr/bin/env bash
# Make a bot say something out loud, in game, through the virtual microphone.
#
#   deploy/speak.sh <container> "текст"
#   deploy/speak.sh uctest-mc-tester1 "привет, я бот"
#
# Requires deploy/voice_setup.sh to have run once for that container.
#
# The TTS comes from the personal ops endpoint (POST /tts, see ~/.claude/codex-docs.env for the URL
# and key — the key is never printed here and never lands in the container). The audio is piped
# straight into the container and played into the null sink that SVC listens to, so nothing is
# written to disk on either side.
set -euo pipefail

CONTAINER="${1:?usage: speak.sh <container> \"text\"}"
TEXT="${2:?usage: speak.sh <container> \"text\"}"

ENV_FILE="$HOME/.claude/codex-docs.env"
[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE — no TTS endpoint configured"; exit 1; }
set -a; . "$ENV_FILE"; set +a
: "${CODEX_DOCS_URL:?CODEX_DOCS_URL not set}"
: "${CODEX_DOCS_KEY:?CODEX_DOCS_KEY not set}"

# --fail so an auth error becomes a non-zero exit rather than an mp3-shaped error page that paplay
# would cheerfully play as noise.
curl -sS --fail -X POST "$CODEX_DOCS_URL/tts" \
     -H "Authorization: Bearer $CODEX_DOCS_KEY" \
     -H "Content-Type: application/json" \
     -d "$(printf '{"text":%s}' "$(printf '%s' "$TEXT" | python -c 'import json,sys; print(json.dumps(sys.stdin.read()))')")" \
| docker exec -i "$CONTAINER" sh -c '
    PACAT=$(command -v paplay || echo /opt/base/bin/paplay)
    FFMPEG=$(command -v ffmpeg || true)
    if [ -n "$FFMPEG" ]; then
        # SVC wants 48 kHz mono; let ffmpeg do the resampling rather than hoping the TTS matches.
        "$FFMPEG" -loglevel error -i pipe:0 -ar 48000 -ac 1 -f wav pipe:1 | "$PACAT" --device=botmic
    else
        "$PACAT" --device=botmic
    fi
'

echo "spoke: $TEXT"
