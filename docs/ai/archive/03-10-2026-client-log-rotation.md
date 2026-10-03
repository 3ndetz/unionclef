# Client errors across midnight log rotation

## 2026-10-03 — retain client errors across midnight log rotation

### Investigate
- Actual candidate gate crosses midnight. Client latest.log changes from4889
  lines to the new daily file; old line-index collection raises even though
  nav_bridge passes. Matching the same number of lines would also silently
  omit errors if a new file grew past the old count. Client/container remains
  healthy; restarting either would not repair the log boundary.

### Plan
- Identify the starting file by exact byte prefix, keep newly rolled gzip
  archives plus frozen end snapshot, and fail on missing/ambiguous history.
  Retain any incomplete starting line in full, preserving boundary errors.
- Use the quiet subprocess adapter for read-only Docker calls; preserve clients,
  original failed evidence, gates, sampling and Linux compatibility.

### Implement
- New uctest/client_logs.py provides a conservative retained ClientLogWindow;
  the actual-default continuation and prepared dry fixtures now use it.
  Original frozen failed helper stays unchanged for provenance.
- Nine meaningful boundary tests pass on actual Windows Python3.14 and Linux
  Python3.11: append, real midnight shape/errors on both sides, numeric size
  rotations, partial error lines, equal-count replacement, missing/ambiguous
  history, missing intermediate archive and rollover during collection.
  Linux/read-only live client check16452/log030827 ended0 at00:08:34.698UTC.
  Live snapshot prefix/window retained without altering the client; retained
  real midnight archive independently validates the production byte collector.
- ASSESS: gameplay score is unchanged; trustworthy cross-midnight error
  measurement advances the playthrough process. This fixes evidence collection,
  never gameplay or FPS. Native food140/full59/combat remain open; continue the
  pending actual-default audit and originally unexecuted cases immediately.
