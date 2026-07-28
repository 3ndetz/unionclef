#!/usr/bin/env python3
"""Stop hook for unionclef autonomous work.

While AUTONOMOUS MODE is active this refuses to let a turn finalise and re-injects the
work procedure. It does NOTHING in normal conversational mode.

ACTIVATION (opt-in, local only): the file `.claude/autonomy_active.flag` exists.
  - Create it to start a run:  touch .claude/autonomy_active.flag
  - End it with `.claude/autonomy_stop.flag` — ONLY when all TODO is closed+tested,
    or on hardware failure (AGENTS #8).

Contract: exit 0 + {"decision":"block","reason":...} blocks the stop and feeds `reason`
back to the model; exit 0 with no block allows it. Any error -> allow (never brick a session).
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
            "reason": ("[autonomy] Runaway backstop ({} blocks). If you truly cannot make "
                       "progress: write .claude/autonomy_stop.flag, send the TG report with "
                       "the blocker, stop. Otherwise the counter reset — continue with a "
                       "DIFFERENT concrete step.").format(n)
        }))
        try:
            counter.write_text("0")
        except Exception:
            pass
        return 0

    reason = "\n".join([
        "[autonomy] DO NOT STOP. Do THESE STEPS IN ORDER, right now.",
        "",
        "STEP 1. RE-ENTER THE TASK. Re-read docs/CHECKLIST.md, docs/NAVIGATION.md, TODOS.md.",
        "        RE-OPEN the source files for the current task — do not recall them, read them.",
        "",
        "STEP 2. MEASURE PROGRESS: `git log --oneline -10`.",
        "        How many of the last 10 commits MOVED THE SUITE SCORE? Write the number down.",
        "",
        "STEP 3. TWO ITERATIONS IN A ROW WITHOUT MOVING THE SCORE = YOU ARE TREADING WATER.",
        "        CHANGING APPROACH IS NOT CHANGING TARGET. You finish what you started —",
        "        the course goes GREEN. What changes is HOW: stop patching and guessing,",
        "        go back and RE-READ the sources end to end until you understand the",
        "        mechanism, then make ONE correct fix. Abandoning the target is not allowed.",
        "        Diagnosis with no fix is also an iteration without movement.",
        "",
        "STEP 4. Tired / guessing / editing from memory? That is NOT permission to stop and NOT",
        "        a licence for 'one more small step'. It is an order to do STEP 1 in full.",
        "",
        "STEP 5. Then work: ONE CORE FIX -> build -> run the target course AND the three",
        "        baselines -> video on success -> commit and push. No band-aids, no hardcode.",
        "",
        "Stopping is allowed ONLY when everything in TODOS.md is closed and tested, or on",
        "hardware failure. Then: .claude/autonomy_stop.flag + the final TG report.",
    ])
    print(json.dumps({"decision": "block", "reason": reason}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
