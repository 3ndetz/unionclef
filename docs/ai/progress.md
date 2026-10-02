# Progress

## 2026-10-02 — medium-specific pillar input: native integration and adjacent audit

### Investigate
- End goal remains the full @gamer playthrough on tungsten. Original full59
  lava-column collection, generated terrain and C4 world/mining/global-budget
  thread safety remain open. No controller/COMPLEX/SWARM changes.
- Complete shelter release, wiki repair, food-bank diagnosis and isolated
  experiment history is retained in
  [archive/02-10-2026-shelter-release-and-food-bank-evidence.md](archive/02-10-2026-shelter-release-and-food-bank-evidence.md).
  Older history is in the shelter-snapshot and full59-landing archives.
- Published0.95.55 assetSHAfa9a57f4fb8f35090cd68a4343f957a53bac79eace6cac50ef8140b94278a393
  carries2212 classes byte-identical to the audited shelter candidate. Release
  report delivered asTelegram9619; do not resend. Shelter input is closed.
- Published055 raw food checkpoint controls are intermittent: one diagnostic
  with extra geometry observation acquires/cooks food; a tick-only5min replay
  stays grounded in source water holding JUMP+SNEAK and acquires none.
  Original checkpointcp1002-1301-t644 fingerprint:
  582e74bda330ac87f5e28c3ac91322307f78181a61a17818eaa1cd7bc533df64.
  Never use synthetic healing/inventory for this native comparison.
- Baritone Movement.java125-127 retains JUMP in liquid; MovementPillar187-200
  separates water ascent from land placement. Its land branch also initially
  sneaks. Published055 healthy isolated source water has100 grounded JUMP+SNEAK
  ticks with no rise, justifying a measured difference from that land input.
  Agent.java459,506 apply opposed water sneak/jump impulses.
- Experimental PillarTask uses JUMP without SNEAK while touching water, then
  restores the land pose. Existing real-pose, cell-clearance, crosshair,
  protection, centering and placement-rate gates are retained.
  pillarUsesSwimInputInWater remainsfalse by default pending full validation.

### Plan
- Finish first candidate true-pin raw5min food replay, then its matched false
  arm from the identical original checkpoint, with tick-only observation.
  Repeat balanced pairs; retain all results and independently observe input
  in both arms. Check actual food, survival, FPS and the recordings.
- Prepared but NOT EXECUTED:36 existing interaction/clearance/refusal trials
  (furnace, vine, ceiling, two cave-vine ray heights, break-policy refusal),
  and42 navigation gates (water construction, flat, stairs, descend, water,
  wall2, bridge). Each fixture is rebuilt, flags read back, exact candidate
  checked, first unexpected red/invalid/runtime failure stops the campaign.
- Clean final tungsten build and fresh packaging are still required after
  source comment corrections. Never override/bless a stale nested jar.
  Recheck live human debug sessions and stale versions/*/bin before building.
- Publish only stable measured behavior through scoped :1.21.11:githubRelease,
  verify branch/tag/assets/payload, edited English HyperFrames report and
  Telegram delivery, then canonical deployment/audit and the next focused pass.
  Do not close broad natural food or complete-game coverage from arena ascent.

### Implement
- Candidate build17162 exit0/7m43s; canonical tester1-only deploy90782 exit0.
  Frozen wholeSHA09232aab2ab8fecbed2c6632337b639fc871ea8d3a4664d03647f5900ad22feb.
  Recursive2212-class proof:only PillarTask/TungstenConfig changed; added[]/
  removed[]. Candidate is not the published055 asset despite its filename.
- Durable nav_water_pillar is registered/listed by the real runner. It requires
  actual server cobblestone, one spent block, grounded final height and survival
  over15s. Syntax/diff checks pass; actual runner campaign is still pending.
- Smoke29828 exit0/cleanup[]:wet-on builds/restsY-59, wet-off staysY-60/all64
  blocks; dry both build. Healthy29-30FPS/HP20. All5 actual films reviewed2s.
- Six balanced pairs per medium34523 exit0/cleanup[] in
  water-pillar-probe-20261002-160351:wet-on6/6 actual construction/restedY-59/
  63blocks, wet-off0/6 with100 JC ticks each; dry6/6 each. All30FPS/HP20.
  All25 actual13.4-13.7s films viewed2s/every page;50 actual UTC log lines,
  zero runtime matches. Synthetic mechanism, not a natural food success rate.
- Three-rung smoker31209 exit0/cleanup[] in
  water-pillar-probe-20261002-162126:wet-on3/3 builds all three cells/restsY-57/
  61blocks, wet-off0/3;dry3/3 each, all30FPS/HP20. All13 actual13.6-14.1s
  films viewed2s/every page;26 actual UTC log lines/zero runtime matches.
  GUI was closed only in final snapshots and sampled frames.
- Off-center low-roof table45127 exit0/cleanup[] in
  water-pillar-probe-20261002-163915:wet-on3/3 constructs/restsY-59/63blocks,
  wet-off0/3;dry3/3 each, measured30FPS/HP20. All22-23 separate GUI polls per
  measured case closed/noerrors. All13 actual13.1-15.4s films viewed2s/every
  page;25 actual UTC log lines/zero runtime matches. GUI coverage is sampled.
- Raw true-pin food10796 LIVE in food-bank-trace-20261002-164841. Original
  restored position/HP20/food20; start308items/cooked_mutton3. Parent and native
  observer verify exact pintrue. Partial observations:25sY68.2,50sY81.4,
  73sY89 withporkchop3/smoker cooking selected. No terminal verdict or rate yet.
  Trace begins after gamer activation; end-checkpoint work may extend its
  window. Exact fixed-body durations alone are not active-game stall scores.
- Isolated evidence committed63018b3e, atomically pushedmain/1.21.11 and local
  version branch fast-forwarded. Three remaining source/scenario edits are OWN.
  Wiki37015549344 actualSUCCESS on63018b3e, verified green16. Only two specific
  historical failing logs were reviewed; not the entire historical action log.
- First true-pin native10796 terminal:wrapper0/observer0/gamer1/cleanup[],
  original checkpoint fingerprint unchanged. Median24FPS/13 polls, sampledHP20,
  trace minHP19/zero deaths/lava ticks. Final cooked_mutton7/porkchop3/chicken3
  versus initial cooked_mutton3. General gamer verdict staysFAIL: no new rung.
  7174 ticks/8043 continuous sequence records/zero geometry snapshots or gaps;
  water-pillar36 ticks, grounded5, JUMP+SNEAK0, JUMP without SNEAK30. Actual UTC
  13:52:26-13:58:25 has2241 log lines/zero runtime matches. Actual308s capture
  SHAdfda1d565a04938a096223ec790208791b484c66610a5bbd82a5b8708c81ce67 reviewed
  at8s/all2 pages, first80s at2s/all2 pages,78-98s and260-308s at2s/all3 pages.
  Both dense holds show crafting/cooking; fixed308s capture does not cover the
  entire358.628s observer window. No visual claim about that missing tail.
  Matched false-pin native95373 is LIVE; no rate or broad food closure yet.
- Archive090ae2fb atomically pushedmain/1.21.11; real export177 unique pages.
  Wiki37016377063 actualSUCCESS on090ae2fb, verified green17. Checkpoint budget
  after this native run8858MB/15GB, disk free169GB; no evidence deleted.
- Matched false-pin95373 terminal:wrapper0/observer0/gamer1/cleanup[], original
  checkpoint unchanged; final cooked_mutton1 versus initial3, no newfood/rung,
  zero actual deaths/lava ticks. All6184 wetPillar ticks grounded JUMP+SNEAK;
  no JUMP-only ticks. Continuous7092 sequence records/7031 ticks/no geometry or
  gaps; actual UTC14:06:38-14:12:29 has566 log lines/zero runtime matches.
  Median19FPS/12 polls fails the prospective comparison floor20: retain this
  as-run failure, do not count pair1 as healthy or quote a natural success rate.
  PollsHP20 miss initial traceHP9.766668; the early injury predates the long
  pillar hold and requires attribution separate from water input.
  Actual308s filmSHA7d8da6c7f543b613c76a659fa3de843e3fcc85925310093e5ba8c0f121b94992
  viewed8s/all2 pages,0-52s/22-308s/260-308s at2s/all10 pages. Fixed body in
  source water is visible through the capture end; no food construction/kill/
  cooking during that hold. Missing43.449s observer tail has no visual claim.
  Host83% sample, only tester1 up; no unrelated container stopped. Check arena
  regressions/health before resuming the remaining prospective balanced pairs.
