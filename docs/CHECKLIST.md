# How to work here

One loop. Repeated forever, autonomously, with no pause between iterations.

```
AUDIT -> PLAN -> FIX -> TEST -> VIDEO -> ASSESS -> REPORT -> next iteration
```

No questions to the user between steps. He sets big goals; he does not adjudicate details.

Language rule: instructional text and code comments are written in ENGLISH (AGENTS.md).

---

## 0. Where we are actually going

**END GOAL: `@gamer` plays the WHOLE game on tungsten, and `baritone`/`shredder` are
deleted from the project.**

Everything else is a step toward that. Work that does not move toward it is not needed.

Progress is measured by the courses — not by feelings, not by reports:

```
python deploy/runner/run_suite.py nav      # navigation, 10 courses
python deploy/runner/run_suite.py pvp      # combat
```

The score IS "how far we got".

---

## 1. AUDIT — every iteration, before a single line of code

⛔ **THE RULE: before fixing anything, PROVE BY EXPERIMENT that the code runs.**

Breaking this rule on 2026-07-27 cost a whole session: a search engine was reworked while a
different one drives the bot. It is cheap to check — pin a setting (`;settings <flag> false`)
and look for the engine's fingerprint in the log (fingerprint table: [NAVIGATION.md](NAVIGATION.md)).

Then:
- Read the CODE, not the comments or the docs — both have been wrong repeatedly.
- Every claim carries a `file:line` you actually opened. Cannot cite it? Write "unverified".
- Distinguish: runs by default / gated off / dead / missing. **A dead flag is not a detail,
  it is a missing feature** — that was the root cause four times in a row.

## 2. PLAN

- One iteration = one root cause. Fixing five things at once tells you nothing about which worked.
- Fix in the CORE. Band-aids, hardcode and reactive timeouts are banned.
- Never keep a duplicate engine "just in case" — that is exactly how four pathfinders appeared.

## 3. FIX

- Build: `.\gradlew.bat :1.21.11:build -x check` (via PowerShell).
- Deploy ONLY with `sh deploy/deploy_jar.sh`.
  Never copy by hand: the previous jar stays next to the new one, Fabric loads the wrong one,
  and a whole before/after comparison runs against old code. This already happened.

## 4. TEST

- Run the target course AND the three green baselines (`nav_flat`, `nav_staircase`,
  `nav_descend`) — otherwise you will not see a regression.
- **One good run is not a result.** Run 5-6 times and report the rate (`4/6`).
  Flaky is RED, not "mostly works".
- Diagnose a failure from that run's log, never by guessing.

## 5. VIDEO

`--record` on the run, then:

```
python deploy/runner/tg_send.py --text-file report.txt clip.mp4
```

Send one per meaningful iteration. The clip is the evidence; a text verdict is not.

## 6. ASSESS — mandatory, never skipped

Right after the test, answer in writing:

1. **Did the score move?** Was X/10, now Y/10. If unchanged — what moved in the numbers
   (distance, time, falls)?
2. **Which end goal did this advance, and by how much?**
3. **Is this the right road?** Does it match the philosophy: core over band-aids, one engine
   instead of three, primitives over policy, no server-specific hardcode?
4. **Are we treading water?** Two iterations with no movement in the numbers = CHANGE THE
   APPROACH, do not keep hammering the same spot.

If (3) is "no" — revert (`git revert`, never delete) and rethink.

## 7. REPORT — write it for someone who has never seen this code

Commit and push immediately; never leave unpushed commits.

The report is NOT a changelog. The reader does not know the class names and should not
have to. Explain the SUBSTANCE, in plain words, with a concrete example.

Required structure:

**1. What was broken — in plain language, with an example.**
Not "nextLegNeedsPhysics was never read". Instead:
> The bot walks up to a gap, and at that point the walking engine is supposed to say
> "I can't jump, physics takes it from here". It set a flag meaning exactly that — but
> nobody ever looked at the flag. So the bot arrived at the edge of the gap and just
> stood there, because the part that performs the jump was never told to start.

**2. How I know — the proof.**
Always a log line, a number or a run, never reasoning:
> The log repeated `Walker: BFS 2 wp` fifteen times and then gave up. Two waypoints means
> the plan ended at the lip of the gap every single time.

**3. What I changed — the idea, not the diff.**
> Now the landing cell on the far side is remembered and handed to the physics engine,
> which is the part that can actually jump.

**4. What it gave — numbers before/after.**
> nav_gaps: never passed -> passes in 8.0s, 6 runs out of 6, zero falls.

**5. What is still broken.** Honestly, by name.

**6. ASSESS** (the four questions from phase 6).

**7. The closing check — MANDATORY, copy it verbatim every time:**

```
ПРОВЕРКА УСЛОВИЯ ОСТАНОВКИ:
- Работа закончена по факту?         -> нет / да
- Главная цель достигнута?           -> нет / да  (@gamer целиком на tungsten, baritone удалён)
- Заказчик говорил останавливаться?  -> нет / да
ВЫВОД: если хоть один ответ "нет" — условие остановки ПРОВАЛЕНО, работа продолжается
        немедленно, следующая итерация начинается сразу.
```

**Call red things red.** Partial success is "the course is still red, this much moved".
No victory is ever declared without runs.

**Never end a turn on the word "starting" / "начинаю".** Either the work is in the turn, or
the sentence does not belong in it. A report is a checkpoint, not a stopping point.

## 7b. CONTEXT REFRESH — when it starts feeling hard, RE-READ. Do not wind down.

⛔⛔ **IT IS STRICTLY FORBIDDEN TO WORK A TASK WHILE TIRED.**
Not "wind down", not "one more small step to finish it off" — both are banned. A tired pass
does not produce a smaller result, it produces a WRONG one: on 2026-07-28 the tired steps
were an inverted coordinate convention (reverted), a floor check one level off, and a
capability that could never fire. Every one of them cost more time than the refresh would have.

When tired you do a **FULL FRESH RE-ENTRY FROM ZERO**, and that re-entry IS the work:
re-read the docs, RE-OPEN the source files (never from memory), re-establish the whole
context out loud, then act. Compaction refreshes the session automatically — you do not
need permission and you do not need a new session; you need to actually re-enter.

Signs you need this: the same course fails three iterations in a row; you are guessing
instead of citing; you catch yourself writing "I am running low on context" or "the next
session should…"; you avoid re-opening a file because you only half-remember it.

**That feeling is NOT a reason to stop. It is the signal to re-read.** Being fuzzy on
something you read hours ago is normal — the fix is thirty seconds of reading, not a
handover. Refusing to re-open a file you half-remember is the actual failure mode: it makes
you guess, and guessing is what has cost this project entire sessions.

Do this, in order, then continue:

1. Re-read [NAVIGATION.md](NAVIGATION.md) — which engine does what, and the log fingerprints.
2. Re-read this checklist, phases 1 and 6.
3. Re-read [../TODOS.md](../TODOS.md) section 0 and the critical register — restate the END
   GOAL out loud: `@gamer` plays the whole game on tungsten, baritone deleted.
4. Re-open the actual files involved in the current problem. Not from memory. Open them.
5. Re-state, in writing: what is PROVEN, what is ASSUMED, what is UNKNOWN. Attack the
   UNKNOWN with one experiment — never with a code change.
6. Write down the vectors again: where we are (score X/10), what is red, what is next.

Then keep going with a clear head. A refresh is part of the loop, not an exit from it.

## 8. Next iteration — immediately

Reaching a milestone is not a reason to stop. Close a root cause, take the next priority,
start again from AUDIT.

Stop only on: hardware failure, or everything in `TODOS.md` closed and tested.

---

## Hard bans

- Never call something done without a stand run.
- Never fix a symptom instead of the cause.
- Never leave dead code, dead flags or duplicate engines.
- Never hardcode a specific server / slot / coordinates.
- Never put emoji in a user interface.
- Never dress up a result — the user checks it on video.

## Where things are

| file | what for |
|---|---|
| [NAVIGATION.md](NAVIGATION.md) | engine map + log fingerprint table. Read FIRST when touching pathfinding |
| [../TODOS.md](../TODOS.md) | user's goals + critical register C0-C8 |
| [ai/nav-baseline-2026-07-27.md](ai/nav-baseline-2026-07-27.md) | course baseline |
| [ai/audit-2026-07-27-tungsten-full.md](ai/audit-2026-07-27-tungsten-full.md) | full tungsten audit |
| `deploy/runner/` | stand, suites, video sending |
| [RELEASE.md](RELEASE.md) | how to release |
