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
