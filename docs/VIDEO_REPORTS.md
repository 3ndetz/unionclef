# Video reports

The operator forwards these to people as "what changed in this release", so they are edited videos,
not raw bench recordings. Tooling: `reports/build_report.py` (storyboard JSON -> ffmpeg cuts ->
HyperFrames composition -> MP4 -> Telegram), modelled on `C:/repos/pet/vidsmith` (HyperFrames
project in `hf/`, rules in `vidsmith/docs/CUT_RULES.md` and `mineswarm/docs/VIDEO_RULES.md`).

## When

- **Every release** gets one, sent with the release (the caption lists what changed, in English,
  and links the GitHub release).
- **Every visible milestone** in between: a fight that now works, a death that stopped happening, a
  new overlay. Anything that looks good on screen is shown -- that is what the report is for.
- During long autonomous work, at least one report a day even without a release.

## What goes in

1. **Before / after on the same checkpoint.** The strongest shot is the same situation twice: the
   old build failing, the new one handling it. Resume both from one checkpoint
   (`gamer_smoke.py N --from NAME --raw-resume --record`) and cut the matching moments.
2. **Every clip names its event.** Title = what happens ("Night with no bed"), caption = the measured
   fact ("health 20 -> 1.7 in four minutes"). A viewer who reads nothing else must still know
   what they saw.
3. **Numbers on a stat card**, not in a caption: was -> now, and where it was measured.
4. **The bot acting.** Cut standing, menus and loading. Speed up long stretches (x4-x16, the badge
   shows it); keep fights and the moment of the fix at x1-x2.
5. **Visuals on.** Record with `;settings visuals all 1` so routes, break/place boxes and mining
   progress are on screen -- they explain what the bot is doing better than any caption.
6. No emoji in cards or captions (typographic arrows and dots are fine), no self-praise: say what
   it does.
7. **English only**, cards, captions and the Telegram caption alike. Record with the bench
   clients on `lang:en_us` so the game's own chat on screen is English too.
8. **Not a slideshow.** Open on the payoff (`hook`: the best 5-10 s, one big line), show
   before/after SIDE BY SIDE (`split`) rather than one after the other, point at the thing that
   matters (`callouts`: a ring and a word at a second of the clip), and give numbers as bars
   (`before_n`/`after_n` on a stat card). Clips get a slow push-in by default (`zoom`). Keep text
   cards short -- four lines at most -- and the whole report around a minute.

## How

```bash
# 1. keep the recordings you will show (gamer_smoke overwrites artifacts/gamer_run1.mp4)
mv deploy/runner/artifacts/gamer_run1.mp4 reports/footage/<run>.mp4
#    stopping a run early: this stops EVERY gamer_smoke process and keeps the recording --
#    killing the shell does not stop the runner, and a stale one keeps driving the client
python deploy/runner/stop_run.py <run>

# 2. find the moments: RUNG / LAVA/DEATH / pinned lines in the run log give t= seconds, and the
#    recording starts with the run, so a log time is a video time (check one frame first)

# 3. write reports/stories/<version>.json (format at the top of build_report.py): title card,
#    clips with from/to/speed/title/caption, stat and text cards

# 4. render, look, send
python reports/build_report.py reports/stories/<version>.json          # -> reports/out/<version>.mp4
ffmpeg -i reports/out/<version>.mp4 -vf "fps=1/4,scale=480:-1,tile=4x3" -frames:v 1 sheet.png
python reports/build_report.py reports/stories/<version>.json --send   # when the sheet looks right
```

Look at the contact sheet before sending: text overlapping the game HUD, a clip that shows nothing
happening, a cut that starts after the event -- fix the storyboard and render again.

## Cave Johnson films: audio and timing lessons (0.95.62)

Pipeline: `reports/hf/cave-johnson-<ver>/` (copy the latest folder). Measured on 0.95.62:

- **STT word times run late**, 0.1-0.6 s, worst on the first word of a take. Visual cues keyed to words
  are fine at that error, but a line's first subtitle must start on the measured voice onset
  (`add_subs.py: onset()`), not on the STT time.
- **No single-pass `loudnorm` on the final mix.** It is dynamic: it lifted the music and the stamp
  thuds in the pauses to speech level (gap peaks -3 dBFS, same as the voice). Static gain plus a
  limiter (`volume=5dB,alimiter=limit=0.708`) keeps voice peaks at ~-3 dBFS and the pauses ~10 dB under.
- **Check the mix with stems**, not by ear: `work/stems.py` writes pre-limiter voice / ducked music / SFX
  and prints music under each line vs in the gaps (0.95.62: -40 vs -32 dB RMS, voice -23) and where
  each SFX peak lands. SFX are dropped automatically if they fall inside a line.
- **The mp4 audio stream starts at ~0.28 s.** Decoding it raw makes everything look early; measure with
  `-af aresample=async=1:first_pts=0`.
- **Short events can last two frames** (the crafted item sits in the output slot for 0.13 s at 15 fps).
  Step the source frame by frame and hold the frame as a still under the ring.

Added on 0.95.63:

- **HUD shots go in the framed inset** (`cls="framed"`, 1600x900 at 160,40), not full frame. Full frame puts the
  hearts row at y~1000, under the burned-in subtitles; framed, it is at y~873. Ring position = 160 + x*1.873, 40 + y*1.875.
- **Write glyphs literally in the CSS.** `content:"\\2014"` passed through a Python string came out as an octal
  escape plus a stray "4" on screen. Check list markers in a full-size frame.
- **Subtitles can orphan a label word** ("...a zombie. After:"). Add the pair to `protected` in `add_subs.py`.
- **The speed badge and the reel share the top-right corner.** Move one of them when both are on screen.

Added on 0.95.64:

- **A run clock on a timelapse** makes a long run readable (x293 for 30 min in 6 s). `cut()` burns it in with
  `drawtext=textfile=...` before `setpts`; the text file holds `%{eif:trunc((t+START)/60):d:2}:%{eif:mod(trunc(t+START),60):d:2}`.
  A textfile avoids the filter-escaping of `:`; `+START` is needed because input `-ss` resets pts to 0. Run ffmpeg
  with `cwd` = the report folder and relative font/text paths (Windows drive colons break the filter string).
- **A card line shown for under a second is not shown.** "now: client left the game = FAIL" appeared 0.6 s before
  the next card; it moved onto the stat card. Check each reveal against the next cut time, not just the word time.
- **The crash run's recording simply ends** when the client dies (457 s of a 30-min run). Step the last seconds frame by
  frame: last gameplay frame, "Saving world", black. Put a black box behind a framed clip that runs out early.
- **Shell vs Python paths on this host:** the Write tool's `/tmp` is not Git Bash's `/tmp`, and Windows Python does not
  see either. Keep scratch files inside the report folder (`work/` is git-ignored).

Added on 0.95.66:

- **CLIPS.md times are a guide, not a cut list.** "~0:30-1:10 crafts a stone pickaxe" was the pickaxe breaking at 0:36;
  the crafting GUI was at 1:55, the item in the slot for 0.4 s. Contact-sheet the whole clip, then frame-step the event.
- **A small GUI needs its own crop.** The crafting table fills a sixth of an 854x480 frame; a second FOOT entry with
  `crop=320:180:...` (x5 in the framed inset) makes the grid and the output slot readable. The still uses the same crop.
- **STT drops a repeated word** ("point six six" came back as "point six" on every take). Check the take's energy
  envelope instead of re-rolling it, and let `display()` write the version as digits.
- **A verdict sits on the event, not near it.** "OUT · 43.7 S" over a sped-up clip whose last frames were still in the
  lava was false for most of its 1.5 s; end the clip on the exit and hold a still under the verdict.
- **Telegram goes through le_bot's proxy:** `TG_PROXY` in `C:/repos/pet/health/.env` (mineswarm's `TG_BOT_PROXY` is
  empty; direct api.telegram.org times out). `cave-johnson-0.95.66/send_tg.py` does it and writes the receipt.

## Housekeeping

`reports/footage/`, `reports/out/` and `reports/hf/videos/` are git-ignored. Delete footage once
its report is sent; the disk rule of `docs/CHECKLIST.md` (0a) applies to recordings too.
