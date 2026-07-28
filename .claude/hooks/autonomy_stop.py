#!/usr/bin/env python3
"""Stop hook for unionclef autonomous work.

Keeps the agent on the checklist: while AUTONOMOUS MODE is active it refuses to let the
turn "finalise" at a milestone and re-injects the checklist directive (audit -> next
focused pass). It does NOTHING in normal conversational mode, so ordinary Q&A turns end
normally.

ACTIVATION (opt-in, local only): the file `.claude/autonomy_active.flag` exists.
  - Create it to start a relentless autonomous run:  touch .claude/autonomy_active.flag
  - Remove it (or write `.claude/autonomy_stop.flag`) to end the run — do this ONLY when
    ALL TODO (incl. child/emergent) is closed+tested, or on hardware failure, per AGENTS #8.

The flag is git-ignored (session state, not repo content). Safety net: a per-run block
counter caps runaway loops; if it trips the hook allows the stop and says why.

Contract: exit 0 + `{"decision":"block","reason":...}` blocks the stop and feeds `reason`
back to the model; exit 0 with no block allows the stop. Any error -> allow (never brick
the session).
"""
import json
import os
import sys
import pathlib

MAX_CONSECUTIVE_BLOCKS = 120  # runaway backstop; resets when the flag is (re)created

def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        return 0  # unparseable input -> allow stop (safe)

    cwd = data.get("cwd") or os.getcwd()
    claude_dir = pathlib.Path(cwd) / ".claude"
    active = claude_dir / "autonomy_active.flag"
    done = claude_dir / "autonomy_stop.flag"
    counter = claude_dir / ".autonomy_block_count"

    # Not in autonomous mode -> normal behaviour (allow stop).
    if not active.exists():
        return 0

    # Explicit end-of-run signal wins (all TODO closed / hardware fail).
    if done.exists():
        for p in (done, active, counter):
            try:
                p.unlink()
            except Exception:
                pass
        return 0

    # Runaway backstop: reset the counter whenever the flag is newer than the counter
    # (i.e. the run was (re)started), else increment and cap.
    try:
        n = int(counter.read_text().strip()) if counter.exists() else 0
    except Exception:
        n = 0
    try:
        if not counter.exists() or active.stat().st_mtime > counter.stat().st_mtime:
            n = 0
    except Exception:
        pass
    n += 1
    try:
        counter.write_text(str(n))
    except Exception:
        pass
    if n > MAX_CONSECUTIVE_BLOCKS:
        print(json.dumps({
            "decision": "block",
            "reason": ("[autonomy] Runaway backstop tripped ({} consecutive stop-blocks). "
                       "You appear stuck without progress. RE-ASSESS honestly: if you truly "
                       "cannot make progress, write .claude/autonomy_stop.flag, send the TG "
                       "report explaining the blocker, and stop. Otherwise the counter has "
                       "reset — continue with a DIFFERENT concrete next step.").format(n)
        }))
        try:
            counter.write_text("0")  # reset so the next genuine attempt isn't immediately capped
        except Exception:
            pass
        return 0

    reason = (
        "[autonomy] DO NOT STOP — you are in autonomous mode (.claude/autonomy_active.flag). "
        "

*** IF YOU FEEL TIRED / LOW ON CONTEXT / LIKE YOU ARE GUESSING: this is NOT "
        "permission to take 'one more small step', and NOT a reason to stop either. "
        "It is the trigger for docs/CHECKLIST.md phase 7b (CONTEXT REFRESH). "
        "It is STRICTLY FORBIDDEN to keep working a task while tired — that is how the "
        "off-by-one errors and the reverted 'fixes' happened. Do a FULL FRESH RE-ENTRY "
        "instead: re-read docs/NAVIGATION.md, docs/CHECKLIST.md, TODOS.md, RE-OPEN the "
        "actual source files (never from memory), restate PROVEN / ASSUMED / UNKNOWN in "
        "writing, restate the end goal and the score, and only then act. Re-entering the "
        "task from zero IS the work — compaction refreshes the session automatically. "
        "Never 'quickly finish' something while tired. ***

"
        "Follow docs/CHECKLIST.md + AGENTS.md. A milestone is NOT a stop (rule #11). "
        "Right now: (1) if you just hit a milestone, run its AUDIT regression; (2) then "
        "IMMEDIATELY pick the next-priority TODOS.md task — or a new/emergent one (e.g. the "
        "core_bridge physics-search-target fix / the #1.6.1 block-space search reliability) — "
        "and do a FULL focused pass: decompose in your Task tool -> implement the CORE fix "
        "(no band-aids, Tungsten-first) -> TEST on the deploy/ Mac stand (your fn + adjacent "
        "regressions, e.g. break_test as the guard) -> audit -> release stable work. "
        "Discovered-mid-run tasks are taken into work immediately. Keep git clean + pushed "
        "(author = owner, no Co-Authored-By, proxy-bypass). The ONLY valid final stops are: "
        "ALL TODO (incl. child/emergent) closed AND tested, or hardware failure — and only "
        "then create .claude/autonomy_stop.flag + send the final TG report. Otherwise continue "
        "the next focused pass NOW. Do not pause 'to be safe' — that shames the closed loop."
    )
    print(json.dumps({"decision": "block", "reason": reason}))
    return 0

if __name__ == "__main__":
    sys.exit(main())
