#!/usr/bin/env bash
# Give a bot client a VOICE — a virtual microphone it can speak through.
#
# WHY IT IS DONE THIS WAY, because the obvious way does not exist.
# Simple Voice Chat's API has exactly the thing we want, AudioSender.send(byte[]), and it is handed
# out ONLY by the SERVER api (VoicechatServerApi.createAudioSender). The client api —
# VoicechatClientApi, which is what a client mod like ours gets — can create audio channels to PLAY
# sound and cannot create a sender. Checked against voicechat-api-2.6.0 with javap, not guessed. So
# "make the mod speak" is not available at any price, and the mod is the wrong layer anyway: it would
# break on every SVC update.
#
# What DOES work is the layer below: give the client a microphone that we control. PulseAudio can
# invent one — a null sink whose monitor is a capture source — and SVC has no idea it is not a real
# one. Anything played into the sink is heard by everyone in range, in-game, positionally.
#
#   TTS mp3/wav ──▶ paplay ──▶ [null sink "botmic"] ──▶ .monitor ──▶ SVC microphone ──▶ server
#
# Usage:  deploy/voice_setup.sh [container ...]        (default: uctest-mc-tester1)
# Then:   deploy/speak.sh <container> "текст"          to actually say something.
set -euo pipefail

CONTAINERS=("${@:-uctest-mc-tester1}")
SINK_NAME="botmic"

for c in "${CONTAINERS[@]}"; do
    echo "=== $c ==="

    docker exec "$c" sh -c '
        set -e
        PA=/opt/base/bin/pulseaudio
        [ -x "$PA" ] || { echo "  no pulseaudio in this image"; exit 1; }

        # --system=false: a per-user daemon is enough and avoids the root warnings.
        # --exit-idle-time=-1: never exit; the bot may be silent for hours between lines.
        if ! pgrep -x pulseaudio >/dev/null 2>&1; then
            $PA --start --exit-idle-time=-1 --disallow-exit >/dev/null 2>&1 || true
            sleep 1
        fi

        PACTL=$(command -v pactl || echo /opt/base/bin/pactl)
        [ -x "$PACTL" ] || { echo "  pactl missing — cannot configure the sink"; exit 1; }

        # The virtual mic. Idempotent: loading it twice would give the bot two microphones.
        if ! "$PACTL" list short sinks 2>/dev/null | grep -q "'"$SINK_NAME"'"; then
            "$PACTL" load-module module-null-sink \
                sink_name='"$SINK_NAME"' \
                sink_properties=device.description=BotMicrophone >/dev/null
        fi
        "$PACTL" set-default-sink '"$SINK_NAME"' 2>/dev/null || true
        "$PACTL" set-default-source '"$SINK_NAME"'.monitor 2>/dev/null || true
        echo "  sink + monitor ready"
    ' || { echo "  FAILED — see above"; continue; }

    # SVC must (a) listen to that source and (b) transmit on sound rather than on a key it will
    # never press. PTT is the shipped default and is why a bot is silent even with a working mic.
    docker exec "$c" sh -c '
        CFG=/mc-data/config/voicechat/voicechat-client.properties
        [ -f "$CFG" ] || { echo "  no SVC config yet — start the client once, then re-run"; exit 0; }
        cp "$CFG" "$CFG.bak.$(date +%s)" 2>/dev/null || true
        sed -i "s/^microphone_activation_type=.*/microphone_activation_type=VOICE/" "$CFG"
        grep -q "^microphone=" "$CFG" \
            && sed -i "s/^microphone=.*/microphone='"$SINK_NAME"'.monitor/" "$CFG" \
            || echo "microphone='"$SINK_NAME"'.monitor" >> "$CFG"
        # -50 dB is the shipped gate and TTS sits comfortably above it; left alone deliberately so a
        # quiet line still opens the channel rather than being clipped into silence.
        echo "  SVC set to VOICE activation on '"$SINK_NAME"'.monitor"
    ' || true
done

cat <<'NOTE'

NOT YET VERIFIED END TO END. The pieces are each checked — pulseaudio exists in the image, the SVC
config is where this expects it, and the API limitation above was read off the jar — but nobody has
yet heard the bot speak on a server. What is untested:
  * whether the client picks the new microphone up without a restart (it may cache the device list)
  * whether SVC's VOICE gate opens on TTS levels without a gain bump
  * whether the server has voice chat enabled at all (isVoiceChatConnected() answers that)
Run deploy/speak.sh next and listen. If it is silent, check those three in that order.
NOTE
