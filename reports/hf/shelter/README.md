# Shelter report — 0.95.55

Six scenes, 60 seconds, 1920×1080 at 30 fps, deliberately silent. The project
uses local fonts, GSAP and recorded gameplay; the CLI is pinned to 0.8.111.

The report compares published 0.95.54 with the tested shelter candidate on
checkpoint `cp1002-0229-t656`, with the original inventory and default pathing.
One previous-release control found no site in 90 seconds. Six candidate
saved-entry repetitions reached and held a sealed shelter. The separate
51/51 arena campaign covers destination refresh, condition ownership,
underwater escape, navigation and crafting. These scopes are distinct.

The morning clip comes from the raw playthrough. Its later food pursuit still
stalls; the previous published release also stalls on that food checkpoint.
The food task, lava-column investigation and full playthrough remain open.
Player inputs are captured before worker calculation; live-world, mining and
global-budget thread safety remain separate work.

## Verified outputs

- Master: `../../out/shelter-0.95.55.mp4`, 45,268,423 bytes,
  SHA256 `451e5cd6cd3576bbc9509b5f6698acbba69f6a02558f2184bc73b64d26158c06`.
- Telegram copy: `../../out/shelter-0.95.55-tg.mp4`, 7,957,285 bytes,
  SHA256 `28557e764b7f6dfac3fff808441532b50d6a88087f821ffc66fe0695c45f8676`.
- Both files are H.264, 60 seconds, 1080p/30 fps, without an audio stream.
  Every page of their complete two-second review sheets was inspected.
- Final composition check: zero lint, runtime or layout issues; contrast
  29/29. Six scene midpoint snapshots and the final release frame were viewed.
- Final animation map: 20/36 mapped tweens, 16 micro-tweens omitted, no
  degenerate targets. Eight fast entries match the selected cascade recipes;
  held text accompanies the continuing gameplay during the map's dead zones.
- Telegram acknowledged message **9619**. The compact file was also displayed
  in HAPI. The master exceeded HAPI's upload limit.

Publication: https://github.com/3ndetz/unionclef/releases/tag/v0.95.55

The published Minecraft 1.21.11 JAR has SHA256
`fa9a57f4fb8f35090cd68a4343f957a53bac79eace6cac50ef8140b94278a393`.
All 2,212 classes, including nested dependencies, match the audited candidate.

`BRIEF.md` and `STORYBOARD.md` retain scene intent and source intervals. Large
footage, renders, review sheets and runtime caches are ignored. Source assets
are adopted locally with the media ledger; recover the recorded source files
from the retained runner artifacts before rerendering.
