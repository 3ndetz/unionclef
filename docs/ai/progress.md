# Progress

## 2026-10-02 — shelter reachability and player snapshot (in progress)

### Investigate
- Full chronological landing/shelter/failure evidence is preserved in
  [archive/02-10-2026-shelter-snapshot-investigation.md](archive/02-10-2026-shelter-snapshot-investigation.md).
  Earlier full59 investigation: [archive/02-10-2026-full59-landing-investigation.md](archive/02-10-2026-full59-landing-investigation.md).
  Historical pending/live statements in those archives are superseded below.
- Release0.95.54 is published at73b78e19, assetSHA00e866. Its byte-identical
  candidate passed42 navigation,27 mixed and6 same-entry portal checks; edited
  English54s video delivered asTelegram9570, deployed landing audit4/4PASS.
- Native cp1002-0229-t656 proves nearby safe sites outside the old search bounds
  are reachable. Initial06652c candidate passed36 arena cases and one healthy
  saved-entry sealed10s hold, but isolated discovery with general Unstuck off
  retained an obsolete single destination after a new safe site appeared.
- Refreshedf8caef passed that matched discovery input. Its first campaign retained
  a Flee fixture zombie in the only air shaft, producing one drowning death.
  Clean isolation then stopped at finalAir199 despiteHP20/no deaths and visible
  refills; keep both original reds. Round3 Flee also raised a live-player worker
  NPE in isClimbing. These are separate findings, not a universal air repair.

### Plan
- Validate the frozen unpublished e03ed747 snapshot candidate with51 repeated
  shelter/exit/Flee/air/nav/craft checks, stop first red/invalid/runtime failure.
- Then six healthy saved-entry repeats, exact inventory/checkpoint preserved,
  and raw15min @gamer integration from cp1002-0229-t656 with dense checkpoints.
- Publish stable work only after final-payload validation, through scoped
  :1.21.11:githubRelease; verify real asset, edited English HyperFrames Telegram
  report, canonical tester1-only deployment and audit, then next focused pass.
- Original full59 lava-column stall/casting deaths, entity-obstructed air escape,
  stale executor-callback ownership and complete-game coverage remain open.
  Generic world/mining/global-budget snapshot debt C4.1/C4.3 remains separate.

### Implement
- Six Java sources remain uncommitted: immutable AnyBlock goals, region ownership,
  loaded3D shelter discovery, live safety revalidation, bounded destination refresh,
  and client-thread immutable player StartState before worker dispatch. Flood/
  hazard checks, three-block shaft and morning policy are unchanged.
- Exact final-candidate deployment/contracts/current campaign are recorded below.
  No controller/COMPLEX/SWARM changes; workflow-center tracked files untouched.

## 2026-10-02 — recurring wiki sync failure (fixed and verified)

### Investigate
- Owner asked why wiki sync repeatedly fails. Latest36975493590 and historical
  35376440063 both fail on duplicate Progress.md; first also reports git rm
  treating ----.md as an option. Upstream cmbrose/github-docs-to-wiki v0.20
  (63b291ccf8b66be4233493eae236329a8c43343e) reads the input as a string and
  tests its truthiness. Default "false" is nonempty, so header naming stays on
  despite commit76232701 removing the input. Progress archives share that header.

### Plan
- Use deterministic relative-path page names, preserving headings, Home mapping,
  local doc links/anchors and source links. Validate the export before changing
  the cloned wiki; separate Git options from paths and serialize publishing.

### Implement
- Commite4d5c0bd adds the repository-owned PowerShell exporter and regression
  checks; workflow no longer invokes the broken upstream action. Passed local
  duplicate-header, Home/archive/source links, code, leading-dash Git cleanup,
  idempotence and collision-rejection checks. All174 docs export successfully.
- Pushed atomic main+1.21.11; local version branch fast-forwarded after sole
  worktree/divergence checks. Actual push run36978862343 SUCCESS,174 pages,
  wiki master6f4c4d89b8d052c64b23e81d907cb5bf8d762c5d. Explicit second run
  36979076017 SUCCESS and "Wiki already matches docs." No token was printed.
- Owner's subsequent highlight question was read-only: source defaults for all
  visuals/mining/break/place are true. MixinDebugRenderer:135-152 gates only the
  master switch; mining events are globally subscribed by PlayerExtraController
  and include manual breaking. WorldEdit selection stays until clearSelection/
  //desel. No rendering/default changes made; disclosed exact disable commands.
  Reentry also confirms the green goal box is unconditional under renderPathMoves;
  disclosed that separate switch. Vanilla crosshair outline was not investigated.

## 2026-10-02 — final snapshot payload deployed, repeated validation in progress

### Implement
- Snapshot candidate SHA256 e03ed74770ce620e12809b76e2868ce9cb6b545d8bd17224dbebaeeae21910c5
  is unpublished. Final nested freshness/bytecode checks pass: only FastPlanner,
  FastNavigator and unchanged-disassembly nest members Heap/NodeMap differ from
  archived f8caef; StartState is added. Canonical tester1-only deployment12444
  exited0 and the actual loaded JAR hash matches. No controller changes.
- Native contract69437 exited0: gateway-worker and native client-thread captures
  both return immutable StartState, valid geometry and place count2 at30FPS,
  with all movement drivers inactive. Initial no-player assertion was a missing
  test precondition; retained separately in the no-player stderr artifact.
- Campaign17649 started with persistent shelter-start-snapshot-audit-console.log,
  planned51: six each new-site/blocked/flat/exit/Flee/air, three each navigation
  flat/stair/descent/water and mixed pickaxe craft. Every case checks fixture
  absence and its own client-log window for FastNavigator planning failures;
  stop on the first behavioral/runtime red or invalid FPS. No prior incomplete
  isolation reused. Native saved-entry repeats and full @gamer remain pending.
- First three exact-payload cases validPASS: new-site/blocked/flat at28.7-29.5FPS,
  runtime windows clean; all three full clips reviewed2s (both new-site pages).
  Prepared ignored six-run saved-entry driver, syntaxPASS/NOTRUN: same retained
  inventory/checkpoint, HP20 throughout, minFPS14, sealed10s, runtime/cleanup gates.
- First full eleven-course round is validPASS, runtime windows clean. All11
  complete recordings reviewed2s, including both air/new-site pages. First air
  HP20/deaths0/finalAir300,27.14FPS; navigation and protected mixed craft pass.
  The51-case series remains live; no completed repeated rate claimed. Fresh
  fetch confirms main/1.21.11 and both remotes0/0 atc0f74803, sole worktree,
  owner3ndetz credentials. Chronological426-line evidence archived exactly.
