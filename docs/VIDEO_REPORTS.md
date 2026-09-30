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

## Housekeeping

`reports/footage/`, `reports/out/` and `reports/hf/videos/` are git-ignored. Delete footage once
its report is sent; the disk rule of `docs/CHECKLIST.md` (0a) applies to recordings too.
