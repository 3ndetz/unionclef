# Unattended bench children without Windows console windows

## Investigate

- Operator reported desktop console windows from the native chain
  `check_food_bank_trace.py -> gamer_smoke.py -> docker.exe` in Windows Session1.
  `gamer_smoke.sh` and the other test wrappers used stdlib subprocess directly.
- AST audit covered all185 previously tracked Python files:157 bench files
  imported subprocess, with189 run calls, four Popen calls and three call calls.
  No bench call explicitly requested a console, startupinfo, shell or positional
  Popen options. The12 remaining calls belong to report-authoring scripts.
  Embedded container-side Python snippets are data, not host imports.
- The native food-bank diagnostic and its extraction had already ended naturally
  before this repair. No running test was killed or Docker service/container
  stopped to suppress windows. Changes take effect in subsequent Python launches.

## Plan

- Centralize the bench subprocess policy without patching stdlib globally.
  On Windows, add CREATE_NO_WINDOW while retaining existing flags, streams,
  environment, directories, return codes, checked errors and timeout behavior.
- Preserve intentional interaction via interactive=True, CREATE_NEW_CONSOLE,
  DETACHED_PROCESS or explicit visible STARTUPINFO. Leave POSIX launches alone.
- Test real OS children, the actual gamer helper/Docker chain and POSIX behavior;
  preserve the separately pending gamer lifetime and Java release changes.

## Implement

- Added `deploy/runner/uctest/process.py`; run/call/check_call/check_output delegate
  to stdlib and Popen remains a subclass supporting communicate/context managers.
  Replaced only subprocess imports in all157 bench files. Existing arguments and
  container snippet contents are unchanged. No graphical application is hidden.
- Updated55 ignored local artifact helpers, including the reported outer wrapper,
  build, deployment and film-review helpers. These are local session artifacts,
  not tracked repository changes. Verified their runner-path import ordering.
- `python deploy/runner/test_process.py`:13 cases, Windows12 passed/one POSIX skip;
  actual Linux Python3.12 container11 passed/two Windows skips. Tests cover binary
  and text streams, input/env/cwd, nonzero/checked errors, timeout child cleanup,
  Popen, merged/file output, older APIs, shell, missing executable, positional
  flags, preserved console intent and POSIX session/signal semantics.
- Real Windows child reports GetConsoleWindow()==0. Read-only Session1 probe
  through gamer_smoke.sh executes six docker-version queries and one docker-exec
  output/error/exit7 query. All results preserved;221 desktop console samples
  observed no new visible ConsoleWindowClass window. Sampling is not a claim
  of continuous observation of every possible unrelated desktop event.
- Evidence: ignored `artifacts/quiet-process-windows-proof.json`,
  `quiet-process-linux-proof.txt`, `quiet-process-migration-proof.json`.
  AST equivalence proves156 import-only changed tracked wrappers retain their
  executable logic and snippet data; gamer import reviewed separately because
  its already tested recording-lifetime repair remains pending in the worktree.
  Syntax checks cover273 runner/uctest/top-level artifact files; diff check passes.
- Future direct subprocess imports in bench wrappers are rejected by the
  regression audit. Existing invalid-escape SyntaxWarning is unrelated and
  unchanged. Deliberately visible launches are checked with mocked dispatch,
  avoiding opening interactive test windows on the operator's desktop.

## Follow-up: inherited output is also an OS contract

- An actual nested Bash/Docker probe found that CREATE_NO_WINDOW loses inherited
  default output when Windows Popen receives stdin/stdout/stderr allNone. Local
  Python3.14 subprocess source confirms the allNone fast path supplies no handles
  or STARTF_USESTDHANDLES. Captured and redirected-output tests already passed;
  this was a separately missing inherited-stream case.
- The adapter now explicitly passes valid CRT stdout/stderr descriptors only on
  that Windows fast path. Stdlib still duplicates/owns the handles. Absent pythonw
  descriptors and caller-provided STARTF_USESTDHANDLES remain untouched.
  run's automatic input/capture pipes and check_output's owned stdout are retained;
  POSIX and intentionally interactive launches are unchanged.
- Expanded regression checks:14 cases, actual Windows13 passed/one skip and
  actual Linux12 passed/two skips. Nested run/call/Popen output and exit7 are
  tested without child redirection arguments. Read-only Bash and Docker probes
  independently retain inherited stdout/stderr and codes. Session1 console probe
  repeated:197 samples/no new visible console, child GetConsoleWindow()==0.
- Actual final365e1fdb candidate deployment completed through the canonical
  script with captured diagnostics (exit0 and loaded SHA verified). Its first
  inherited-stream attempt exited1 before deployment; retained as a process/
  harness limitation, not a Java/course failure. No benchmark was killed.

## Follow-up: attached parent consoles retain their output too

- Actual hidden noninteractive console fixture found a separate contract gap:
  CREATE_NO_WINDOW child exits7 and writes without error, but neither stream
  reaches the parent's real console buffer. Earlier inherited-pipe/file checks
  do not cover this. Original observation retained separately; no human window
  was hidden and the fixture was hidden from creation.
- An already attached parent's child normally inherits the existing console
  without creating another window. The adapter now retains that normal launch;
  detached bench parents still add CREATE_NO_WINDOW. Explicit caller flags and
  intentional interaction remain unchanged. The real fixture verifies run,
  call and Popen output/exit7, the same console HWND, and captured stdout with
  inherited console stderr. No stdout forwarding threads or global patching.
- Expanded15-case suite: actual Windows14 passed/one skip; actual Linux12
  passed/three Windows skips. The real private console remains invisible.
  Evidence: quiet-console-inheritance-windows.log/linux.log and the original
  quiet-hidden-console-original-observation.json. The currently running native
  pair retains its loaded adapter; this correction takes effect on later starts.
  No gamer/Docker/container was terminated to hide windows.

## Follow-up: report helpers use the same unattended process contract

- Five remaining direct imports in reports/build_report.py and pitch's
  build_pitch.py, add_subtitles.py, sheet.py and tts.py launched noninteractive
  ffmpeg/ffprobe/npm children outside the bench directory. Replaced their
  imports with the same adapter and added their directories to the regression
  audit. Commands, arguments, streams, timeouts and checked-error behavior are
  unchanged; executable ASTs match for all five after excluding imports and
  runner-path setup. No existing media, composition or provider request changed.
- Fresh Windows suite:14 passed/one POSIX skip. Actual Windows child through
  the imported report module retains stderr and exit7 with console HWND0;
  ffmpeg/ffprobe version queries succeed. Actual Linux suite:12 passed/three
  Windows skips; the same report module preserves separate streams and exit7.
  No render, TTS/STT request or Telegram send was needed for these checks.
- First Linux checker attempt failed because runpy did not add deploy/runner
  to sys.path. Its log and exit1 remain retained; the subsequent checker uses
  the normal script entry point. Logged job221810-155524/source9e92f5dc exits0
  naturally at19:18:24.363173UTC. Evidence: quiet-report-imports-proof.json,
  quiet-report-platform-proof.json and quiet-report-linux-contract-v2.log.
- This extends the completed Windows repair. No useful test, Docker service
  or existing container was stopped; already loaded processes are unaffected.
