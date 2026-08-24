package kaptainwutax.tungsten;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;

/**
 * Simple JSON config saved to .minecraft/config/tungsten.json
 * Load once on startup via TungstenConfig.load().
 * Save after changing values via TungstenConfig.save().
 */
public class TungstenConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("tungsten.json");

    private static TungstenConfig INSTANCE = new TungstenConfig();

    // ---- settings (edit defaults here) ----

    /**
     * Bumped whenever the SHIPPED defaults change in a way that must reach machines
     * that already have a tungsten.json.
     *
     * <p>Why this exists: {@link #load()} used to re-{@link #save()} the whole object on
     * every startup, so once the file existed it contained EVERY key — and a key present
     * in the file always wins over a new shipped default. A stand therefore kept running
     * months-old combat tuning and visualisation switched off, while the code said
     * otherwise, and nobody could tell from reading the source. Raising this number makes
     * the next load discard the stale file once and adopt the current defaults.
     */
    public int configVersion = CURRENT_CONFIG_VERSION;

    /** Raise this when new shipped defaults must override existing tungsten.json files. */
    private static final int CURRENT_CONFIG_VERSION = 1;

    /** If true: on position mismatch > driftThreshold, setPosition() to simulation value.
     *  If false: stop executor and let path recalculate from real position. */
    public boolean driftCorrectionEnabled = false;

    /** Blocks of drift before triggering correction or executor stop. */
    public double driftThreshold = 0.8;
    /**
     * Extra drift tolerated per tick of replay, blocks.
     *
     * <p>The threshold above is an ABSOLUTE number, but the quantity it guards GROWS: every
     * replayed tick adds a little integration error between the simulated chain and the real
     * player, and at this stand's ~10 fps each tick covers twice the ground it was tuned for.
     * A fixed bound therefore means "the longer the path runs correctly, the more likely it is
     * to be thrown away". Measured on a live @gamer run: {@code Path stopped: drift 0.830 blocks
     * (threshold 0.8) at tick 14} — a path abandoned for three centimetres, fourteen ticks in.
     *
     * <p>The allowance grows with the tick index so the guarantee at the START is unchanged —
     * the historical failure this check exists for is {@code drift 1.723 at tick 1}, which still
     * aborts — while a path that has been tracking well for a while is no longer punished for it.
     */
    public double driftPerTick = 0.05;

    /** If true: enable trail-following when target escapes (dist>20).
     *  If false: always pathfind directly to target position. */
    public boolean enableTrailing = false;

    /** If true: enable LEAP mode (sprint+jump without A* at close range).
     *  If false: always use A* pathfinding even at short distances. */
    public boolean enableLeap = false;

    /** If true: log verbose tick/drift/position messages to console.
     *  Keep false in normal use to reduce noise. */
    public boolean verboseDebugLogging = false;

    // ---- visualization (render toggles) ----

    /** FAST-FIRST NAVIGATION (default ON). Plan a cheap, physically-real block
     *  path first (FastPlanner) and START WALKING it immediately, then let the
     *  physics engine refine/execute the hard parts. Off = the old behaviour,
     *  where nothing moves until the physics search produces a path.
     *  Toggle live: ;settings fastBlockFirst false — or py4j setFastBlockFirst. */
    public boolean fastBlockFirst = true;

    /**
     * Guide the physics engine with an INCOMPLETE fast route (default OFF).
     *
     * <p>The rule this bypasses accepts a fast route only when it is complete, or when it already
     * arrives, on the grounds that the fast move set has no slime bounce, ladder, vine or swim move
     * and a partial route through such terrain hides those options. It cites the fastBlockFirst
     * toggle as proof — "OFF passes, ON fails" — and that is now exactly backwards: re-measured
     * 2026-08-10, OFF fails nav_water 0/3 and nav_slime 0/3 while ON passes 3/3 and 2/3.
     *
     * <p>MEASURED WITH IT, three runs an arm, all at a healthy frame rate:
     * <pre>
     *   arm                                   nav_slime  nav_ladder  nav_water
     *   fastBlockFirst=false (old behaviour)     0/3         3/3        0/3
     *   default (fast-first + this rule)         2/3         3/3        3/3
     *   fastGuidePartial=true (rule relaxed)     3/3         3/3        0/3
     * </pre>
     *
     * <p>The rule earns its keep on nav_water and nowhere else that was measured: relaxing it
     * costs that course 3/3. Its supposed cost on nav_slime is NOT established — the 2/3 above is
     * one series and a later three at the default read 3/3, so slime is 5/6 with the rule against
     * 6/6 relaxed, which no series this size can separate.
     *
     * <p>A conditional version was then built and reverted: reject a partial route only when the
     * remainder crosses fluid, on a straight walk from the route's end to the goal. It scored 6/9 —
     * nav_water 0/3 — identical to relaxing the rule outright, so the discriminator never fires on
     * the course it was written for. Whatever makes a partial route fatal there is not fluid on
     * that line, and the next attempt should ASK FastPlanner which move it lacked rather than infer
     * it from geometry.
     *
     * <p>So the default stays: 8/9 against 6/9 for both relaxations. This flag stays as the
     * instrument that produced the table.
     */
    public boolean fastGuidePartial = false;

    /** Wall-clock budget for the fast block plan (ms). It is time-sliced and
     *  returns its best chain, so this is a latency knob, not a quality cliff. */
    public long fastPlanBudgetMs = 250;

    /** Master render toggle. false = draw NOTHING from tungsten (paths, goal,
     *  parkour moves, combat trajectory, break plan). Turn off to keep the view
     *  clean or shave render cost. Toggle live: ;settings renderVisualization false */
    public boolean renderVisualization = true;

    /** Draw the path / parkour-move renderers (block path, running path, node
     *  search). false hides parkour-move visualization while keeping combat/
     *  break overlays. Toggle: ;settings renderPathMoves false */
    public boolean renderPathMoves = true;

    /** Draw the mining break plan (queued=orange, current=red). */
    public boolean renderBreakPlan = true;

    /** Draw the placing plan (cells about to be placed — bridge/fill/build). */
    public boolean renderPlacePlan = true;

    /** Draw combat aim/trajectory overlays. */
    public boolean renderCombat = true;

    /** Max time (ms) for A* input search before emitting bestSoFar.
     *  Higher = better routes on parkour, lower = faster response.
     *  Upstream default: 1800. Our default: 15000 (parkour needs more time). */
    public long searchTimeoutMs = 15000L;

    /** If true: log per-node timing breakdown to stdout.
     *  Shows where PathFinder spends time: child generation, filtering,
     *  openSet ops, heuristic updates, block-space search, etc. */
    public boolean debugTime = false;

    /** Minimum mismatch magnitude to log in verbose debug mode.
     *  Hides float/double precision noise (typically ~1e-10).
     *  Set to 0 to log everything. */
    public double mismatchLogThreshold = 1e-6;

    /** Use parallel threads for node creation in A* search.
     *  Faster on multi-core CPUs but Agent.tick/WorldView may not be fully
     *  thread-safe. Disable if you see rare ConcurrentModificationException. */
    public boolean enableParallelStreaming = true;


    // ---- follow settings ----

    /** Use the BFS block-path walker for IMMEDIATE movement toward a follow/combat target
     *  while the physics A* computes. ON by default (2026-07-23): without it, follow/punk
     *  depended only on the physics pathfinder, which re-plans forever on a MOVING target
     *  and the bot STANDS STILL (LIVE-A). The walker sprints from the real position toward
     *  the target every tick (drift-immune) and now face-before-moves (no spin). */
    public boolean followBlockPathFinderEnabled = true;

    /** Which ported movement classes {@code MovementQueue} may take, switchable AT RUNTIME.
     *
     *  <p>These exist because the MEASUREMENT was the blocker, not the code. chase_terrain's freeze
     *  count drifts with host state over a long session — the same build measured 15, 16 and later
     *  22 — so comparing "this batch against this morning's" compared stand states, not
     *  configurations, and I did it twice. Flags make A and B ALTERNATE within one sitting
     *  (`;settings queueClimbs false`), which is the only way an A/B of this size is worth
     *  anything here. Defaults are the configuration currently kept. */
    public boolean queueClimbs = true;
    /** Diagonals are ported but OFF: within ONE batch they measured 19/23/11, a spread of 12 where
     *  every other configuration sat at 1-3. That signal is independent of host drift. */
    public boolean queueDiagonals = false;
    /** Let {@code MovementQueue} own the WHOLE route, walking untyped edges with
     *  {@code MovementFallback} instead of abandoning the tail at the first one. OFF by default
     *  until an interleaved A/B says otherwise — see C5.18. */
    // ⛔ STAYS OFF BY DEFAULT — MEASURED 2026-08-02. Turning it on took nav 12/12 -> 11/12 with
    // nav_bridge red: whole-route mode also changes what the NAVIGATOR's build legs accept, and
    // a bridge leg is exactly where an untyped edge becoming a MovementFallback matters. The
    // chase turns it on for itself; nav is the protected baseline and does not get to regress.
    public boolean queueWholeRoute = false;
    /** Run the CHASE's block route through {@code MovementQueue} (the ported baritone movements)
     *  instead of {@code BlockPathWalker}. Until now the queue only ever received build legs, so
     *  every plain walk in the mod — nav and chase alike — was the hand-rolled walker. See C5.18. */
    public boolean chaseUsesQueue = true;
    /**
     * Run ORDINARY navigation's block route through {@code MovementQueue} too.
     *
     * <p>The sibling of {@link #chaseUsesQueue}, and the larger half by far. With that one alone,
     * the queue's only callers were the navigator's build legs and the chase, so a plain walk
     * anywhere else in the mod — including the whole {@code @gamer} playthrough — was still the
     * hand-rolled {@code BlockPathWalker}, and every ported movement class (traverse, ascend,
     * descend, diagonal, swim, fall) sat unused. Measured on the playthrough course:
     * {@code mqStarted=0} for an entire run while the search kept finding paths.
     */
    public boolean navUsesQueue = true;
    /** May a fall place a water bucket to break itself (an "MLG")? OFF, because altoclef —
     *  the brain this module runs under — calls configurePlaceBucketButDontFall(true)
     *  unconditionally at init, i.e. it owns the bucket and asks the pathfinder not to touch it.
     *  This is the fourth conjunct of MovementFall.java:99 restored under a name tungsten has. */
    public boolean allowBucketMlg = false;
    /**
     * Hold the melee distance as a function of the ATTACK COOLDOWN instead of a fixed band.
     *
     * <p>⛔ THE EVIDENCE BELOW IS DEAD, AND THE SETTING WAS MEASURED PROPERLY ON 2026-08-10.
     * The allround figures cited in the next paragraph are n=2 an arm on the course that was later
     * proven to be ending at ~20 s of its 120 s, i.e. they describe the approach and not the fight.
     * The paragraph is kept because its METHOD is right — a mirror duel cancels a symmetric change,
     * so only an asymmetric arm can measure one — and that method is what finally produced a number:
     * <pre>
     *   edge_duel, n=11 an arm, arms interleaved, starved runs excluded
     *     bot carries it, victim on the baseline   margins -7 -5 -2 0 -10 +2 -4 -2 -4 -6 -4  mean -3.82
     *     neither side carries it (the mirror)     margins  0 +2 -5 +1  +1 -1 +1 +4  0 -3 +1  mean +0.09
     * </pre>
     * 3.2 sigma, and the mirror's +0.09 is the structural zero that makes the other column readable.
     * On melee_basic, whose spread is 0.75 and which would show a shift that size at more than five
     * standard errors, the same comparison reads median 0 over n=7. So this setting is NEUTRAL on
     * open ground and expensive where a retreat is impossible — see the stand-off note in
     * {@code CombatController}, which now refuses the band change when {@code dirSafe(back)} is
     * false, and which is itself NOT yet proven.
     *
     * <p>ON since 2026-08-02, proved on the ASYMMETRIC course because melee_basic is a MIRROR duel — both fighters
     * are this same jar with the same kit — so shipping a combat change on by default hands it to
     * the opponent too and the course stays a coin flip. The only way this stand can prove the
     * change is worth anything is `run_suite.py --pin combatReachControl=true`, which applies to
     * ONE of the two fighters. Measured that way on the asymmetric course (allround), interleaved:
     * deaths 1 and 1 with it against 2 and 3 without.
     *
     * <p>It also carries this module's ONLY health awareness — below half a bar the bot breaks
     * contact instead of trading (see {@code CombatController.LOW_HP}). Before it, getHealth() was
     * read nowhere in tungsten at all.
     *
     * <p>⛔ THAT RETREAT IS NO LONGER UNCONDITIONAL, 2026-08-10. It now also requires a launcher
     * and ammunition ({@code WeaponSelector.hasRangedOption}), because its whole justification is
     * that out past reach the bow becomes the weapon — and on a sword-only kit there is no bow, so
     * the bot walked out of range, dealt nothing, could not heal, and handed over the initiative.
     *
     * <p>Note what that means for reading THIS setting's measurements: the victim in the pvp duels
     * is pinned to {@code combatReachControl=false}, so it never entered this block and never had
     * the retreat. The bot did. Every "the bot loses a symmetric duel" figure recorded before that
     * date was therefore taken with a handicap on ONE side that no longer exists — including the
     * "4:4, 4:6, 4:4, 3:4, margin -0.75" series in CombatController and the verdict above it that
     * distance tuning is exhausted. Re-measure before trusting either.
     */
    public boolean combatReachControl = true;

    /**
     * Sidestep on the BOW DRAW instead of on the arrow (default OFF, judged by a pinned pair).
     *
     * <p>Every dodge this project has built waits for the arrow, and at the range a skeleton
     * actually shoots from — measured 4.3-6.3 blocks — an arrow crosses in under two ticks. No
     * reaction can fit in that, which is the mechanism behind four "refuted" dodge experiments and
     * the reason the eight movement hypotheses on mob_skeleton all measured nothing.
     *
     * <p>The draw is the window that does exist. Measured over six runs: draw count tracks arrow
     * count one-for-one, the longest draw is exactly 20 ticks in EVERY run, and it begins at
     * 6.0-9.3 blocks. A full second of warning, before every shot, starting while the bot is still
     * outside the killing band.
     *
     * <p>⛔ MEASURED AND REFUTED, 2026-08-12. Pinned same-session pair, twelve runs an arm:
     * <pre>
     *   flag off   n=12  mean 1.42 arrows  sd 1.21
     *   flag on    n=12  mean 1.94 arrows  sd 1.29
     *   difference -0.52, SE 0.51  ->  1.02 sigma
     * </pre>
     * No effect, and the sign is the wrong way round again. Kept OFF and kept here, because the
     * MECHANISM behind it is confirmed — the draw really is a second of warning before every shot —
     * and the failure says something the arrow counters could not:
     *
     * <p>A vanilla mob does not lead its target. It aims where the target IS at the moment of
     * release, so a sidestep taken when the draw STARTS is fully compensated by the time the arrow
     * leaves. Moving early cannot work, and moving inside the last two ticks is the same
     * impossible reaction the arrow-triggered dodges already failed at. That closes DODGING as a
     * family for this course; what remains is killing in fewer swings, or denying the shot.
     *
     * <p>AND A CORRECTION TO THE ARM-SIZE NOTE THIS FLAG SHIPPED WITH: it claimed n>=5 an arm on
     * the strength of one session's sd of 0.37. This session read 1.21-1.29 and SE 0.51, so twelve
     * runs separate about one arrow, not half. The spread is itself unstable between sessions —
     * size the arm from the CURRENT session's arm, never from a remembered number.
     */
    public boolean combatDodgeOnDraw = false;

    /**
     * Lets a ready swing outrank the draw-dodge, instead of the dodge walking the bot out of its
     * own attack.
     *
     * <p>WHAT IT IS FOR, MEASURED. {@link #combatDodgeOnDraw} works on the variable that decides
     * mob_skeleton -- it cut the skeleton hit rate from 57% to 18%, and after its heading was given
     * the closing bias it still reached 24% against a 38% baseline. It has never paid, because it
     * also raises EXPOSURE: arrows fired went 3.15 -> 5.55 even once the approach was restored
     * (bandToSwing 61.9 -> 58.8), so the fight is longer AFTER first contact. The trace says why --
     * intervals between our own swings run 19-22 ticks when contact holds and 90-123 when it does
     * not, against a 12-tick cooldown.
     *
     * <p>So the dodge is buying misses with swings. This declines the sidestep on the ticks where
     * the swing is actually available -- cooldown charged and the target inside reach -- and leaves
     * it armed everywhere else. Those ticks are a small fraction of a draw (a skeleton draws for 20
     * and our cooldown is 12), so most of the avoidance should survive.
     *
     * <p>⛔ "Yield the dodge to the kill order" appears in ProjectileDodge's javadoc as one of four
     * dodge hypotheses that each measured flat -- and that same javadoc explains why none of them
     * counts: the dodge keys were being erased before the game read them, so none of the four ever
     * ran. This is the first test of the idea with a dodge that actually reaches the keys.
     *
     * <p>Off by default. Tested with combatDodgeOnDraw pinned in BOTH arms so the yield is the only
     * difference.
     *
     * <p>⛔ IT CANNOT FIRE OFTEN ENOUGH TO BE WORTH TESTING, AND THE PREMISE IS DEAD.
     * First guard (target inside REACH during a draw): fired ZERO times in 20 runs -- the series
     * was voided by its own mechanism gate. Corrected to REACH + 1.5 = 4.5 and smoke-tested:
     * dodgeYield read 1 in one fight and 0 in the next. Skeletons draw at a mean gap of 5.6 blocks
     * and back away as the bot closes, so an ACTIVE DRAW and a CHARGED SWING almost never coincide
     * at any threshold that still means "close enough to strike".
     *
     * <p>So the draw-dodge is not raising exposure by stealing swings -- it is hardly ever armed
     * when a swing was available. Kept, off, so the next person does not rediscover the idea and
     * spend a series on it. If it is ever revisited, check dodgeYield in a SMOKE TEST first: five
     * minutes there saved a hundred here.
     */
    public boolean combatDodgeYieldsToSwing = false;

    /**
     * Ends a sidestep when the arrow has actually arrived, instead of six ticks later.
     *
     * <p>THE DEFECT IS A UNIT MISMATCH, not a policy. {@code DODGE_HOLD_TICKS = 6} carries the
     * comment "an arrow crosses twelve blocks in about eight ticks" -- it was derived for a
     * twelve-block shot. On mob_skeleton the shots are released at a mean of 5.4-5.7 blocks, and an
     * arrow covers ~2.65 blocks a tick, so the flight is about two ticks. Four of the six are spent
     * sidestepping something that has already arrived or already missed.
     *
     * <p>Those ticks are not free. {@code ProjectileDodge} ticks at the final-word position and its
     * javadoc says outright that its purpose is to override the approach while an arrow is in the
     * air; it presses forward/back/left/right/sprint from the dodge heading and wipes whatever the
     * approach wanted. dodgeDrive runs ~43 ticks a fight against ~52 ticks of combat control and
     * ~109 band ticks, so the overhang is a large share of the approach, not a rounding error.
     *
     * <p>The hold is clamped to [2, DODGE_HOLD_TICKS], so this can only ever RETURN ticks to the
     * approach and never extend a dodge past today's behaviour. The floor of 2 is deliberate: the
     * sidestep has to be moving BEFORE the arrow lands to make it miss, so a sub-tick flight still
     * gets one tick of margin.
     *
     * <p>Judged on arrows at 2 sigma, n=20 an arm, interleaved, with dodgeDrive as the mechanism
     * gate -- it must FALL in the pinned arm, or the flag did nothing and the arrows are about
     * something else. Off by default until then.
     */
    public boolean combatDodgeHoldByRange = false;

    /**
     * Records a tick-by-tick trace of the fight -- distance, the keys that actually reached the
     * game, who owned the legs -- into {@link kaptainwutax.tungsten.combat.CombatTrace}.
     *
     * <p>Diagnostic only: it changes no behaviour and is read through py4j. Off by default because
     * it formats a string every tick, which is not a cost a real game should pay to answer a
     * question the bench is asking.
     *
     * <p>WHY IT WAS BUILT. Five pre-registered hypotheses about this approach returned null, each
     * judged on an aggregate, and aggregates produced four confounded totals in one day. The
     * quantity that decides the course is ~38 ticks between entering the killing band and landing
     * the first swing, and nothing on record said where they go. The surviving candidates each
     * predict a different trace, so one run separates what five series could not.
     */
    public boolean combatTrace = false;

    /**
     * Keeps the bot walking at a committed target on ticks where nothing else drives the legs.
     *
     * <p>See {@link kaptainwutax.tungsten.combat.ApproachLatch} for the measurement. In short:
     * 47% of re-approach ticks reach the game with no movement key at all, because the pathfinder
     * is mid-search; an idle tick covers 0.067 blocks against a sprint tick's 0.244, while the
     * skeleton retreats at 0.215, so those ticks are what turns a +0.029 b/t close into a -0.055
     * b/t loss and parks the bot at five blocks.
     *
     * <p>Off by default. Judged on mean arrows at 2 sigma, with latched > 0 as the mechanism gate
     * and mean per-tick displacement as the secondary that says WHY.
     */
    public boolean combatApproachLatch = false;

    /**
     * Prefers a mining target that is NOT the block under the bot's own feet.
     *
     * <p>DestroyBlockTask.canClear already refuses the underfoot block and says why -- "clearing
     * that is how a bot digs itself into a hole" -- but that guard governs clearing an OBSTRUCTION,
     * not choosing a TARGET. Target selection never knew the rule.
     *
     * <p>Traced on mine_stone by polling the bot's position once a second: it works at y=-60, digs
     * down to -63, and from t+60s sits at y=-57 -- the top of the arena rim wall -- frozen for the
     * rest of the run with path=-1 and nothing in reach. The wall is barrier, so it cannot dig back
     * down. The whole residual failure of that rung follows from the pit it dug itself.
     *
     * <p>It PREFERS rather than forbids: refusing the underfoot block outright would break
     * descending, and mine_diamond passes today precisely because the bot can dig down to ore. The
     * exclusion runs first and an empty result retries without it, so digging down survives
     * wherever it is genuinely the only way.
     *
     * <p>Off by default. Judged on mine_stone with mine_diamond watched for regression, now that
     * sweeps survive to the end again.
     */
    public boolean mineAvoidUnderfoot = false;

    /**
     * Keeps mining targets at or above standing height while the surface still has candidates.
     *
     * <p>The generalisation of {@link #mineAvoidUnderfoot}, which measured 0.40 sigma because it
     * forbade exactly ONE position: the bot descends anyway by taking a block a step aside and
     * following it down.
     *
     * <p>Polling the position through a mine_stone run shows the cost -- 75 of 120 seconds spent
     * oscillating at y=-62/-63 inside its own excavation, four blocks mined -- and the same pit is
     * what it climbs out of onto the arena wall in the other 35-45% of runs, where it strands. One
     * cause, both failure modes, which is why this is aimed at the pit rather than at either.
     *
     * <p>Descending still works when the surface runs out: the restricted search runs first and an
     * empty result retries without it. mine_diamond depends on that and is watched for regression.
     *
     * <h2>REWRITTEN 2026-08-14 -- the first version RATCHETED and that is why it measured 0.85 sigma</h2>
     *
     * It tested {@code check.getY() < feetY - 1}, which is relative to where the bot is STANDING,
     * and the floor is always feetY-1 -- so the block under its own feet always passed. Break it,
     * fall one, and the test re-anchors a level lower and passes the next one too. The guard
     * descended WITH the bot, one level per swing, which is indistinguishable from not being there.
     *
     * <p>Traced three times, once a second: {@code Destroy block at 0,-61,0} at t=0, y=-62 at 4.4 s,
     * y=-63 at 6.6 s, then {@code BFS stuck at 0,-63,0} with all eight neighbours
     * {@code feetBlocked=stone}. From the bottom of a 1x1 shaft there is no lateral move at all --
     * the only direction the search can expand is UP -- and the bot has just mined the blocks that
     * make pillaring affordable. It towers out to y=-55, six blocks above the floor, spending the
     * whole haul, and the run ends with it standing on a cobblestone column with an empty pack.
     *
     * <p>It now asks whether the bot is IN A HOLE rather than how far down it has got: solid ground
     * on all four cardinals at its own feet level. On open ground digging down stays ordinary, which
     * is what mine_diamond needs. In a pit, the blocks at feet level are the pit WALLS -- mine one
     * of those and you can step out. Stateless, so it releases the moment the bot is not enclosed,
     * and it cannot ratchet because it is a question about the world rather than about the bot.
     *
     * <h2>MEASURED TWICE, AND THE SECOND PAIR REFUTED THE FIRST -- OFF BY DEFAULT</h2>
     *
     * <pre>
     *   pair 1, CONTROL first    off 2.20 (0/5 pass)   on 5.80 (2/5)   +3.60   2.02 sigma
     *   pair 2, ARM first        off 5.00 (3/5 pass)   on 5.20 (3/5)   +0.20   nothing
     *   pooled n=10 an arm       off 3.60              on 5.50         +1.90   1.16 sigma
     * </pre>
     *
     * Both pairs interleaved, one invocation each, 25-29 fps throughout. The winner follows the
     * POSITION rather than the flag -- whichever arm ran second scored better -- which is the exact
     * failure rule 4q exists to catch, and it caught a default flip I had already made on pair 1.
     *
     * <p>What that does and does not say. The SHAFT is not in doubt: three traces show the bot
     * walling itself into a 1x1 hole and towering out of it on its own haul, and this rule provably
     * stops that. What is not established is that stopping it moves this course, and the arm still
     * fails half its runs (1, 0, 3, 3) -- so something else dominates, and until that is found the
     * outcome metric cannot resolve a 2-cobblestone difference against its own sd of 3.6.
     *
     * <h2>REFUTED ON THE DETERMINISTIC RULER TOO (2026-08-14) -- STAYS OFF</h2>
     *
     * <pre>
     *   off   n=7   mean 5.43   pass 3/7   TOWERED 1/7
     *   on    n=7   mean 4.71   pass 3/7   TOWERED 2/7
     * </pre>
     *
     * Judged on whether the bot built a tower -- a yes/no fact read out of the world afterwards,
     * not the noisy count -- and it does not move. The rewritten rule is still a BETTER rule than
     * the one that ratcheted, and it demonstrably keeps the bot out of a 1x1 shaft; it simply does
     * not decide this course.
     *
     * <p>The same series says why the whole framing was wrong: only 3 of 14 runs towered at all,
     * and runs score zero with tower=0 and even dug=0 -- one control run never broke the spawn
     * column at all. The tower is a mode, not the mode. Overall pass rate 6/14, which is the 4.32
     * of 8 this course has always had.
     *
     * <p>Off by default. Mechanism gate: the WORLD after the run -- a shaft
     * at the spawn column or no shaft. That has no spread at all, which beats eight noisy runs
     * (checklist 4b #4). Watched for regression: mine_diamond, which must still dig down.
     */
    public boolean mineStayOnSurface = false;

    /**
     * When an altoclef task ends, stop NAVIGATING as well. On by default; pin it false for the
     * control arm of the A/B.
     *
     * <h2>The bug, traced end to end on mine_stone</h2>
     *
     * The task finishing and the search finishing are two different events, and nothing joined
     * them. One run, polled once a second, with the client log beside it:
     *
     * <pre>
     *   29.0s  cobble=8   "No tasks. Time to add new!"      the job is DONE
     *   05:15:04  [Alto Clef] Поставленная задача ЗАВЕРШЕНА за 29.5 сек.
     *   05:15:06  [Tungsten] MovementQueue: 8 movement(s) 0,-63,0 -> 0,-55,0
     *   33.7s  y=-57.25  cobble=2
     *   36.0s  y=-55.00  cobble=0    and it stands there for the remaining 84 seconds
     * </pre>
     *
     * A search that was still in flight when the task ended landed TWO SECONDS LATER, and its route
     * was eight {@code MovementPillar} steps. The bot spent the entire haul it had just gathered
     * building a tower out of its own pit, and the course reads the pack at the end, so a run that
     * met its target in 29 seconds scores zero. The world confirms it: cobblestone at every y from
     * -63 to -56 in the bot's column, and nothing else placed anywhere.
     *
     * <h2>Why this is not a mine_stone fix</h2>
     *
     * Nothing above is about mining. Any task that ends while a search is in flight leaves a route
     * driving a bot that has no goal, and {@code MovementQueue} suppresses every other driver while
     * it runs -- so the zombie route also holds off whatever comes next. On a playthrough that is
     * blocks and seconds burnt between every pair of tasks.
     *
     * <p>Same shape as two defects this repo has already paid for: {@code ProjectileDodge} leaving
     * SPRINT held because "the walker releases the keys anyway" (checklist 4l -- release is not
     * someone else's job), and {@code TaskRunner.tick()} returning before it updates the state
     * everything else reads. A lifetime that ends without the things it owns being told.
     *
     * <p>Judged on mine_stone against the pooled baseline of 4.32 cobblestone (n=60, sd 3.6), with
     * nav_bridge / nav_wall2 / nav_flat watched -- those courses END on arrival, so if stopping
     * navigation at task end ever cuts a live leg short, they are where it shows. Mechanism gate:
     * navStopped must be non-zero, and navStoppedLive counts the times it tore down a route that
     * was still running -- the bug itself, counted.
     */
    public boolean navStopOnTaskEnd = true;

    /**
     * A refused break bans that BLOCK; only several agreeing refusals ban a region.
     *
     * <p>Today one failed break installs a ban on a 101x101x101 cube centred a block from the bot.
     * Traced on mine_stone: {@code breakFail=1/0/0} with {@code cb=0/260992/0/0} -- one claim, a
     * quarter of a million candidates refused after it, and the bot standing in the corner of the
     * arena for the last fifty seconds of the run with nothing it was permitted to mine. There are
     * no land claims on this stand, so every claim it has made here is a false positive.
     *
     * <p>The radius was cut 50 -> 3 once and reverted, and the note explaining why it could not
     * work is still at the constant: at ANY radius the ban is centred one block from the bot and
     * covers everything inside its 4.5-block reach. Radius is the wrong dial. The right one is how
     * much a single observation is allowed to imply.
     *
     * <p>Anti-grief behaviour is unchanged where it matters: on genuinely protected land every
     * attempt is refused, three distinct positions fail within seconds, and the regional ban
     * installs itself as before. Only the cost of being wrong ONCE changes.
     *
     * <h2>IT IS THE PLAYTHROUGH'S WALL, MEASURED ON THE REAL WORLD (2026-08-14)</h2>
     *
     * Filed this morning as "correct by inspection, never exercised" -- mine_stone's arena barely
     * triggers it. A 14-minute @gamer window on the survival world says otherwise:
     *
     * <pre>
     *   breakFail=2/0/0/0        TWO failed breaks believed to be claims
     *   cb=90/842176/5318/103    cbAvoid -- 842,176 candidates refused
     *   scan=1122936/63647/847492/0/0/0   scanNoBreak 847,492, which is the same blocks
     * </pre>
     *
     * The ladder climbs five rungs in 5.5 minutes -- wood, first craft, crafting, wood tools, stone
     * tools -- and then stalls for 160 seconds of DAYLIGHT on
     * {@code Gathering resource: [minecraft:coal] -> Mine And Collect: [[coal]]} with every drive
     * counter at zero: pdEnter+0, dbTick+0, mqStart+0. Nothing is running because nothing is
     * allowed to be mined. Two break failures banned two 101x101x101 cubes and took the coal with
     * them.
     *
     * <h2>UNPARKED AND SHIPPED ON, WITH THE RISK MEASURED AND THE BENEFIT STILL OPEN (2026-08-14)</h2>
     *
     * <pre>
     *   craft: mine_stone, craft_stone_pickaxe, smelt_iron                 3/3 PASS
     *   nav:   flat staircase descend break wall2 bridge                   6/6 PASS
     *   both flags pinned ON, 27.0-29.5 fps throughout, 0 invalid
     * </pre>
     *
     * It was parked because the ON default had never been regression-tested. It has been now, and
     * the courses that matter most are in it: nav_break MINES and then continues, nav_wall2 and
     * nav_bridge BUILD.
     *
     * <p>WHAT IS STILL NOT PROVEN, said plainly: that the LADDER moves. The benefit rests on a
     * counter chain on the real world -- two failed breaks, 842,176 candidates refused, the coal
     * rung starving for 160 seconds of daylight -- and not on a measured ladder improvement, because
     * the survival client cannot start below 12 fps and the box is held by another project. The
     * next quiet window must confirm the ladder passes stone tools. If it does not, this comes back
     * off.
     *
     * <p>And no bench here has real land claims, so the anti-grief half is argued rather than
     * measured: on protected land every attempt is refused, so three distinct positions fail within
     * seconds and the regional ban installs itself as before.
     *
     * <h2>The original park, kept because the reasoning stands (2026-08-14)</h2>
     *
     * It was flipped ON to measure exactly that, and the confirming window came back
     * {@code INVALID - client at 8 fps before the run even starts (< 12.0)}: another project's
     * containers were taking ~750% of this box (one streamer at 431%, another at 222%, four MC
     * servers besides). That is rule ZERO, and the guard did its job by refusing to produce a
     * number rather than producing a bad one.
     *
     * <p>So the ON default has never been regression-tested -- the 11/12 craft sweep and the 6/6 nav
     * sweep both ran with it OFF -- and rule ZERO's mirror says a behaviour change that cannot be
     * measured today does not ship today. Parked here with its patch, its blast radius and its
     * reason, which is what that rule asks for.
     *
     * <p>Blast radius if turned on: every break refusal anywhere, on every course and in the
     * playthrough. It NARROWS a ban, so the risk is under-banning on genuinely protected land, and
     * the escalation to the old radius after three distinct refusals is what bounds that.
     *
     * <p>To take it off the shelf: check {@code docker stats} is quiet, then run the craft sweep and
     * a @gamer window with it pinned true. Mechanism gate: cbAvoid, which reads 260992 on a run this fires in and
     * must collapse to near zero -- an effect size the gate metric cannot come close to, which is
     * the point after two series died on that gate's sd of 3.6.
     */
    public boolean breakBanEscalates = true;

    /**
     * A progress check counts a route being FOLLOWED as progress, not a search merely running.
     *
     * <p>Both give-up paths in altoclef open with the same two lines:
     *
     * <pre>
     *   if (Nav.isPathing()) { progressChecker.reset(); }
     *   if (... &amp;&amp; !progressChecker.check(mod)) { blacklist the target; try something else; }
     * </pre>
     *
     * {@code isPathing()} is true while the pathfinder is only LOOKING, and a search that fails and
     * restarts keeps it true for ever -- so the reset fires every tick and the branch beneath it can
     * never execute. The checker exists to notice "the engine is busy and the body is not moving",
     * and it was being reset for exactly that reason.
     *
     * <p>Measured on mine_stone in every failing trace: the bot stands on ONE SPOT for 50-90 s of a
     * 120-second run, the task reading {@code Approach entity item -- Tungsten pathfinding (29s
     * left)} with the countdown restarting as it expires. The drop is never blacklisted, the wander
     * never starts, mining never resumes, the run scores 0.
     *
     * <p>The machinery it disables is elaborate and correct -- three separate bugs were found and
     * fixed INSIDE that blacklist branch on mine_diamond while this one line kept the whole block
     * dead. Same silhouette as the two most expensive defects in this repo: a gate whose awake half
     * could never fail, and a dodge whose keys never reached the game.
     *
     * <p>Off by default. Mechanism gate: navSearchOnly, the number of ticks where a search was
     * running and no route was -- it must be large, or the premise is wrong. The OUTCOME gate is
     * whether the bot resumes mining after a failed pickup, which the pass rate should show.
     *
     * <p>⛔ MEASURED PAIRED (2026-08-21) AND IT DOES NOT PAY. Five comparable pairs, each arm on
     * the same resolved ground, one excluded for a 89s control run:
     *
     * <pre>
     *   rungs        -0.80   t -0.64    [-4, 0, -3, +3, 0]
     *   stall time   -45.0s  t -0.98
     *   stall share  -13.3%  t -0.97
     * </pre>
     *
     * <p>Not established in either direction, and the spread is the story: one pair lost four
     * rungs and another won three, on a flag whose premise (a search is not progress) is the
     * same one that DID pay as stallCheckNeedsMovement. So the idea is not wrong -- this
     * particular application of it is not measurable at this cost.
     *
     * <p>The mechanism gate this javadoc asked for does fire: srch read 314/66/0 on a run that
     * reached the state, and 0/0/0 on ones that did not. So the pairs are real, the effect is
     * simply not separable from the noise. Stays OFF.
     */
    public boolean progressCheckIgnoresSearch = false;

    /**
     * A tungsten search that owns the approach to an ENTITY must move the body or be released.
     *
     * <p>Same defect as {@link #progressCheckIgnoresSearch} and deliberately a SEPARATE switch.
     * That one governs MineAndCollectTask; turning it on to test this would change two behaviours
     * in one measurement, which is how a result stops meaning anything.
     *
     * <p>GetToEntityTask resets its progress checker and returns early whenever
     * {@code TungstenHelper.isActive()}, which is true while the pathfinder is merely LOOKING. So a
     * search that follows no route both hides the stall and skips the recovery below it. Measured
     * three times on the playthrough, always as "Approach entity -> Tungsten pathfinding...",
     * motionless for 70-90 s, once for a whole run that reached no rung; and it is the recorded
     * "parks 1.17 blocks from the drop" case, the drop one block below in the hole just mined.
     *
     * <p>With this on, such a search gets MovementProgressChecker's ordinary 6 seconds and is then
     * stopped so the approach can be planned afresh. Ordinary searches finish well inside that and
     * are untouched. GATE: the playthrough ladder, and mine_coal/mine_diamond whose recorded
     * failures are this same shape. Proof it ran: {@code entityReleased} in placeStats.
     */
    // ⛔ I CONVICTED THIS FLAG OF A REGRESSION AND THE CONVICTION WAS WRONG (2026-08-17).
    //
    // Two batches showed failures and releases landing on exactly the same runs -- 6 of 6, then
    // 3 of 3 -- and an A/B read 5/8 on against 8/8 off. That looked conclusive. It was not: with
    // the flag OFF BY DEFAULT the course still failed 2 of 8, and entityReleased read 0/0 on every
    // run INCLUDING both failures. So the release fires BECAUSE a run is already in trouble; it
    // does not cause the trouble. Same symptom-correlation trap already named on mine_diamond, and
    // I walked into it again -- the 8/8 was noise on a course whose base rate is around 75%.
    //
    // The real discriminator, identical in every captured failure and absent from every pass:
    //     FAIL   idrop=6122, 6172   drop=17/0   lock=1/0/0   navStop=3/2/0
    //     PASS   idrop=56-111       drop=51/0   lock=0/0/0   navStop=3/0-1/0
    // i.e. the tracker is asked ~6150 times and hands over a drop EVERY time, because the bot
    // spends the whole run pursuing one drop it never reaches. That is the thing to fix.
    /**
     * The close walk keeps the keys it pressed, instead of having them released under it.
     *
     * <p>GetToEntityTask ends its close-range walk with hold(MOVE_FORWARD) and returns. The NEXT
     * tick begins by releasing SNEAK, MOVE_BACK and MOVE_FORWARD whenever {@code Nav.isPathing()}
     * -- and that is true while the pathfinder is merely SEARCHING, which it does constantly
     * while the walk is running precisely because the walk only fires when navigation has failed.
     *
     * <p>Measured at the top of the tick, before this method touches the key:
     * {@code closeWalkFwd=241/240} -- the press survives on half the ticks and is taken away on
     * the other half, which is why 481 ticks of a correctly aimed walk moved the body on 14.
     *
     * <p>With this on, that release is skipped on a tick where the close walk drove last time.
     * Nothing else changes: the walk still only runs when the progress checker says the body is
     * not moving, so a healthy approach never reaches it.
     *
     * <p>⛔ MEASURED PAIRED AND IT DOES NOT PAY. Five pairs, each arm on the same ground:
     *
     * <pre>
     *   rungs        -0.40   t -0.59    [0, +1, -3, 0, 0]
     *   stall time  +55.6s   t +1.41    the fix stands still MORE, not less
     * </pre>
     *
     * <p>Both metrics point the same way and neither is established. The diagnosis behind it is
     * solid -- TimeoutWanderTask really does release the press, named by instrument at
     * "TimeoutWanderTask:225 x267" against closeWalkFwd=0/240/0 -- so the key IS being taken. It
     * simply does not follow that keeping it helps: holding MOVE_FORWARD into whatever stopped
     * the bot is not obviously better than letting go, and the stall numbers suggest it is worse.
     *
     * <p>Which points the next attempt at the OTHER half of this wall rather than at this one:
     * the drop lies directly BELOW the bot on 39% of close-walk ticks (closeWalkGeom=189/0/0/292),
     * where steering by atan2(x, z) is degenerate and no amount of forward can help.
     *
     * <h2>THAT REFUTATION WAS TAKEN IN A WORLD WITH A BIGGER THIEF IN IT (2026-08-23)</h2>
     *
     * The five pairs above were measured while TimeoutWanderTask was ALSO releasing MOVE_FORWARD --
     * 1290 times in a ten-minute run, against the handful this flag is about. That thief was found
     * and fixed today (wanderKeepsWalkerKeys), and re-measuring flips the sign:
     *
     * <pre>
     *   before   rungs -0.40   stall +55.6s     (five pairs, the numbers above)
     *   after    rungs +0.50   stall +11.75s    (four pairs, t=0.40)
     * </pre>
     *
     * <p>Still not established and it stays OFF. What IS established is that the earlier refutation
     * was not measuring this flag on its own -- a dominant defect elsewhere was setting the answer,
     * and the same caution applies to every other flag measured away before that fix landed.
     */
    public boolean closeWalkKeepsKeys = false;

    /**
     * The close walk JUMPS when the body will not move, instead of only pressing forward.
     *
     * <p>Measured shape of the wall, pooled over a series rather than read off one run: the drop
     * is BESIDE the bot on 721 ticks against 2 below, so this is not a vertical problem and
     * steering is not the problem either -- the aim reads 480 of 481 with 392 yaw-kept. The body
     * simply does not move: entityCloseWalkMoved is 14 of 481.
     *
     * <p>A target beside you at 0.8+ blocks, correctly aimed, with forward held, that does not
     * get closer, is a body against something. Walking cannot solve that; a step up can, and it
     * is what every other mover in this codebase does at a lip. So press JUMP once the walk has
     * spent a few ticks going nowhere.
     *
     * <p>⛔ An earlier correction belongs here: "the drop is directly below on 39% of ticks" was
     * quoted from ONE run and did not survive the series. One run is a sample.
     *
     * <p>⛔ MEASURED PAIRED AND IT DOES NOT PAY EITHER. Five comparable pairs, one excluded:
     *
     * <pre>
     *   rungs        -0.60   t -0.51   [+2, 0, 0, 0, -5]
     *   stall time  +78.0s   t +1.37   the fix stands still MORE
     *   stall share +22.7%   t +1.37
     * </pre>
     *
     * <p>Nothing established, everything pointing the same way. A jump in place appears to swap
     * one dead end for another: the bot hops against whatever stopped it instead of letting the
     * wander take the tick and try somewhere else.
     *
     * <p>THREE remedies for this wall are now eliminated with numbers -- keeping the close walk's
     * keys, jumping when stuck, and progressCheckIgnoresSearch -- while the DIAGNOSIS survives
     * all three: the walk aims correctly (480 of 481, 392 yaw-kept), the body moves on 14 ticks,
     * the drop is beside rather than below (721 against 2), and TimeoutWanderTask really does
     * take the forward key back 267 times. Something stops the body that none of these touch.
     */
    public boolean closeWalkJumpsWhenStuck = false;

    /**
     * Skip a route edge that goes nowhere instead of truncating the chain on it.
     *
     * <p>⛔ THE ROUTE CONTAINS EDGES FROM A CELL TO ITSELF, AND THEY ARE THE DOMINANT CAUSE OF
     * TRUNCATION. Named by tallying the SHAPES of every edge the queue could not classify, on a
     * 60-second reproduction of the navigation stall:
     *
     * <pre>
     *   0,0,0   x601      an edge with zero displacement
     *   0,0,-2  x166
     *   0,0,-3  x94
     *   0,0,-4  x17
     *   +-3,0,0 x35 total
     * </pre>
     *
     * <p>No movement class can execute "stay where you are", so the queue does the honest thing
     * and hands the rest of the route back -- 601 times. That is not a missing capability, it is
     * a malformed route, and it explains why queueParkour moved mqNoClass by nothing on this
     * terrain (477 against 479 with the flag verified on both sides) while the note that first
     * proposed it expected 672 -> 3.
     *
     * <p>With this on, a zero-length edge is stepped over and the chain continues.
     */
    public boolean queueSkipsNullEdges = false;

    /**
     * Drop repeated cells from a route as it enters the movement queue.
     *
     * <p>Shipped ON: a route that repeats a cell is malformed, the queue types every edge, and an
     * edge from a block to itself has no type -- so the chain was truncated on it and every step
     * after it handed back. Measured on the navigation stall's 60-second reproduction: 601
     * truncations of shape 0,0,0, all at index 1, replaced by five real edges a run.
     *
     * <p>The switch exists because it went out unconditional and mine_coal then read 3/5 where it
     * had been 19/20. A shipped change that cannot be turned off cannot be A/B'd against the
     * course it may have broken, and "probably unrelated" is not a measurement.
     *
     * <p>⭐ SETTLED, AND THE SUITE IS GREEN. craft re-ran 22/22 with this ON, so the 21/22 that
     * raised the alarm was a flake and not a regression. nav is 13/13 on the same build.
     *
     * <p>The mine_coal A/B is recorded anyway because it is one-directional and the control never
     * failed: 9/12 with this on against 11/11 with it off, across two independent batches. That is
     * p ~ 0.22 -- under this repo's own bar it is NOT an established regression, and mine_coal has
     * its own history of flaking (F P P P P P at one point, 19/20 at another).
     *
     * <p>The plausible mechanism is worth keeping in mind rather than dismissing: with routes no
     * longer truncated the QUEUE owns more of the path and the walker less, so anything the walker
     * did better is now done by the queue. If mine_coal drifts again, that interaction -- not this
     * dedupe -- is where to look.
     *
     * <p>⛔ AND ON THE PLAYTHROUGH IT IS NEUTRAL, WHICH IS THE HONEST HEADLINE. Five comparable
     * pairs, arms on the same ground:
     *
     * <pre>
     *   rungs        +0.20 for OFF   t 0.13    [0, +2, +4, 0, -5]
     *   stall time  +11.8s for OFF   t 0.26
     * </pre>
     *
     * <p>Through four pairs it looked like a clear trade -- the dedupe stalling ~50s less a run
     * and losing ~1.5 rungs for it -- and the fifth pair reversed both at once (5 rungs against 0
     * while stalling MORE). Quoting the trend at n=4 would have produced a confident story about
     * the queue owning too much of the path. It is noise.
     *
     * <p>So: the fix is correct (a route that repeats a cell is malformed), it clears a
     * DETERMINISTIC navigation stall on the 60-second repro, the suites stay green -- and it does
     * not move the ladder. That is the second time this session that removing a stall bought time
     * the bot then failed to convert; the first was stallCheckNeedsMovement, established on stall
     * seconds and flat on rungs. Whatever limits the playthrough is downstream of standing still.
     */
    public boolean queueDedupesRoute = true;

    public boolean entitySearchMustMove = true;

    /**
     * When navigation REFUSES an entity approach and the body is not moving, wander instead of
     * standing there for the rest of the run.
     *
     * <p>{@code TungstenHelper.tryPathTo} returns false permanently once {@code failCount} reaches
     * {@code MAX_FAIL_COUNT}, and GetToEntityTask gated its wander recovery behind
     * {@code !parkourMode} -- true on this bench, so the recovery never ran in the mode that ships.
     * The remaining path is a second refusal, a debug string and {@code return null}: no movement,
     * every tick, for ever.
     *
     * <p>Measured on a captured failing mine_diamond run: frozen at (6.7,-61.0,0.4) from t=8.5s for
     * the remaining ~290 s, ore still in the ground, lock=0/1/0 (one lock, scored productive, none
     * barren) while MineOrCollectTask scanned 6123 times against ~180 on a pass.
     *
     * <p>GATE: mine_diamond, whose recorded failure is this freeze. Proof it ran: the second field
     * of {@code entityReleased=released/wandered} in placeStats.
     */
    public boolean entityWanderWhenNavRefuses = true;

    /**
     * Within a few blocks of a target entity that navigation will not deliver, face it and hold
     * forward instead of wandering away from it.
     *
     * <p>Caught on goto_then_mine, and it is {@link #entityWanderWhenNavRefuses} making a case
     * worse: the bot mines its cobblestone, the drops land at its feet, the approach stalls, and
     * the wander moves it from (21.1, 1.5) -- standing ON the drops -- out to (24.3, 5.3), where
     * it freezes for the rest of the run. cobblestone=0, idrop=3697/0/0/3697 (a drop handed over
     * on every ask), entityReleased=2/2 (both recoveries fired).
     *
     * <p>A drop is collected by TOUCHING it, so at this range the useful primitive is the one a
     * human uses. It runs only after the progress checker says the body is not moving, so a
     * healthy approach never reaches it.
     *
     * <p>GATE: goto_then_mine (7/8, and its failures are this) plus the four pickup courses, which
     * must not move. Proof it ran: GetToEntityTask.entityCloseWalk.
     *
     * <p>⭐ ON by default 2026-08-18, on mine_coal, sixteen runs interleaved in one invocation:
     * <pre>
     *   off  4/8      on  7/8
     * </pre>
     * Pooled with the earlier six-pair series on the same jar: off 9/14, on 12/14. The counter
     * reads 0 in every single control-arm run, so the arms really were different code.
     *
     * <p>WHAT THE COURSE LOOKS LIKE WHEN IT FAILS, which is what finally justified this: every
     * barren lock is a coal drop ONE BLOCK DOWN in the hole the bot just dug -- h=0.7 to 2.5,
     * dy=-1.0 without exception -- and with this off the body does not move at all, m0.0, for the
     * full thirty seconds. Worst recorded control run: coal=0 of 3, four barren locks, two minutes
     * spent standing next to its own coal.
     */
    public boolean entityCloseRangeWalk = true;

    /**
     * Tell the server the hotbar slot CHANGED before swinging with it.
     *
     * <p>setSelectedSlot writes the field on the client and leaves the packet to vanilla's own
     * per-tick sync. A swing sent in that same tick therefore reaches the server while it still
     * holds the PREVIOUS slot, and is resolved with the previous item -- on allround, a bow,
     * whose attack damage is exactly 1.0.
     *
     * <p>Measured rather than reasoned: the bot books ~12 hits of exactly 1.0 hp a run there,
     * with an iron_sword drawn on the client, no sprint, and the target's hurtTime at 10 (a
     * FRESH damage event, so not the residue of a blow absorbed inside invulnerability). On
     * melee_basic, whose kit is one sword and never switches slots, the same instrument reads
     * 0 chips for both fighters and 35 flat blows against the opponent's 33 -- dead even. The
     * chips exist only where slots are switched, and only for the fighter switching them.
     */
    public boolean syncSlotToServer = false;

    /**
     * Drop sprint for the tick of a falling swing, so vanilla will actually GRANT the crit.
     *
     * <p>Vanilla's condition is cooldown > 0.9, fallDistance > 0, off the ground, not climbing,
     * not in water, not blind, not riding -- AND NOT SPRINTING. AttackTiming.isCrit checks
     * neither of the last two: it tests velocity.y &lt; 0 instead of fallDistance, and never asks
     * about sprint. So the bot counts a crit whenever it swings on the way down, and the server
     * grants none, because a bot closing distance is sprinting.
     *
     * <p>Measured, not deduced. Hit sizes on allround, bucketed by the vanilla quantities and
     * read from the RECEIVING client, which knows its own health exactly: crit-sized hits are
     * 0 and 1 per run against 22-27 swings the counter calls crits. Neither fighter collects
     * the 50%, so it costs this duel nothing -- and it is worth 50% to whoever takes it first.
     *
     * <p>This is what human pvp calls a sprint reset, and the honest cost is that the tick loses
     * its sprint knockback. Which of those is worth more is exactly what the A/B is for.
     *
     * <p>⭐ SHIPPED ON. Twelve interleaved runs of allround, six an arm (rule 4r), read from the
     * victim's OWN client:
     *
     * <pre>
     *   crit-sized hits   off [2, 3, 3, 0, 0, 1]  mean 1.5   on [10, 5, 8, 10, 9, 5]  mean 7.8
     *   kills             off [12,13,13,13,12,12] mean 12.5  on [13,13,14,14,14,13]   mean 13.5
     *   margin            off -4.00 (sd 1.10)                on -2.83 (sd 1.17)
     *   damage into them  off 267.7                          on 283.3
     * </pre>
     *
     * <p>Kills carry it: +1.0 a run at t=3.16, over this repo's 2-sigma bar, and the two series
     * touch only at 13. Margin (+1.17, t=1.78) and damage (+15.7, t=1.86) agree in direction and
     * are NOT claimed -- deaths add variance the kill count does not have. Nothing measured
     * negative on any metric, and total BLOWS are flat (38.0 against 37.3), which is the check
     * that these are the same swings upgraded rather than a different fight.
     *
     * <p>The counter reads 0 in every control arm, so the flag is what did it.
     *
     * <p>⭐ AND IT SURVIVES THE COURSE THAT SHOULD HAVE PUNISHED IT. Dropping sprint costs sprint
     * KNOCKBACK, and edge_duel is a 5x5 platform over void where knockback is how kills happen --
     * so if this trade is ever wrong, it is wrong there. Six interleaved runs on edge_duel:
     *
     * <pre>
     *   ON  (shipped)   margin -1, 0, +5    mean +1.33   gate 2/3   kills 9.67
     *   OFF (control)   margin -4, +1, -2   mean -1.67   gate 1/3   kills 7.33
     * </pre>
     *
     * <p>Better, not worse. The crit is worth more than the knockback it costs even there.
     */
    public boolean critReleasesSprint = true;

    /**
     * Re-send the held slot after every respawn, so the server stops swinging our bow for us.
     *
     * <p>See WeaponSelector.reassertSlotAfterRespawn for the measurement this comes from: ~19
     * hits a run landing 1.0 and 1.5 hp -- a bow and a bow crit -- while the attacking client
     * had an iron_sword in hand at the swing.
     */
    public boolean reassertSlotOnRespawn = false;

    /**
     * Ticks to hold the swing after a weapon switch, so the server has applied it first.
     *
     * <p>0 disables. See WeaponSelector.lastSwitchTick: the server reports a BOW in hand on 2-4
     * of every 7 rcon samples during an allround fight, and swinging into that window delivers
     * 1.0 instead of 6.0. Sending the slot packet at the switch only wins the race more often;
     * waiting a tick removes it. The cost is at most two ticks of delay on the first blow after
     * a switch, against a blow worth a sixth of what it should be.
     *
     * <p>⛔ IT WORKS AND IT BUYS NOTHING, WHICH IS THE USEFUL RESULT. Six interleaved runs at 3
     * ticks, from the victim's own client:
     *
     * <pre>
     *              chips  partial   flat   crit  |  damage   kills   margin
     *   hold 0      20.3      3.7   26.7    6.3  |   250.2   12.33    -7.67
     *   hold 3       1.0     14.3   20.0    9.0  |   252.2   12.00   -10.00
     * </pre>
     *
     * <p>The 1.0s are GONE, 20.3 to 1.0, so the diagnosis is proven: they were swings sent into
     * the window where the server still held the bow. But the damage is identical -- 250.2
     * against 252.2 -- because the withheld swings come back as PARTIALS (3.7 to 14.3) while flat
     * blows fall (26.7 to 20.0). The 1.0 hits were never lost damage; they were swings the fight
     * could not afford to place better, and delaying them costs exactly what they were worth.
     *
     * <p>So the fix is not a gate on the swing. It is to have the right weapon in hand BEFORE
     * contact, so no swing is ever near a switch -- WeaponSelector's timing, not TriggerBot's.
     * Default 0. Do not raise it expecting damage.
     */
    public int holdSwingTicksAfterSwitch = 0;

    /**
     * Equip the melee weapon while still APPROACHING, instead of on the tick contact begins.
     *
     * <p>The prediction that separates this from holdSwingTicksAfterSwitch, written before the
     * runs: both should remove the 1.0 hits, but the hold does it by throwing swings away, so
     * flat blows fell with it (26.7 to 20.0) and the damage did not move. Arming early throws
     * nothing away, so flat blows should HOLD while the chips go -- and if they do not, the
     * mechanism is not what either of us thinks.
     *
     * <p>⛔ IT DOES NOT FIRE, SO THIS A/B MEASURED NOTHING AND ITS OUTCOME IS NOT QUOTED.
     * armedEarly reads 2 and 0 across the ON arm. equipBestMelee returns false when the best
     * weapon is already held, so a zero here says the bot was ALREADY holding the sword on the
     * approach -- which makes the PREMISE of this flag wrong, not its implementation. (The
     * counter counts SWITCHES rather than attempts, so on its own it cannot separate "never ran"
     * from "nothing to do"; here the rcon evidence settles it, but the next version should
     * count both.)
     *
     * <p>Which leaves the bow being held somewhere punk's approach never sees. The server still
     * reports it on 2-4 of every 7 samples, and the drive selects slot 0 explicitly when it
     * leaves melee -- so look at the phases where punk is not driving at all.
     */
    public boolean equipMeleeOnApproach = false;

    /**
     * Fewest steps the movement queue must be able to admit before it is allowed to KEEP a route.
     *
     * <p>1 preserves today's behaviour: the queue takes anything it can start.
     *
     * <p>⛔ WHY THAT IS WRONG, MEASURED. navUsesQueue gives the queue first refusal on every route,
     * and the queue accepts a route it can only PARTLY execute -- it runs the prefix it has
     * movements for and hands the rest back. The documented stall is exactly that: 28 starts, 25
     * steps, 27 of the 28 chains truncated at an edge with no movement class. About one step per
     * plan, and the route is replanned identically next tick.
     *
     * <p>The walker never sees those routes. It only gets one whose FIRST edge is unclassifiable,
     * because anything else the queue keeps. So a route that is walkable end-to-end by a thing
     * that sprint-jumps is handed instead to a thing that will stop at edge three -- and the
     * sprint-jump is precisely what clears the gap that stopped it.
     *
     * <p>Set above 1 and a route the queue can barely start is DECLINED whole, so BlockPathWalker
     * gets it. That is the alternative to teaching the queue parkour (queueParkour), not a
     * duplicate of it: one adds a capability, this one routes around the missing capability. Both
     * target the same wall and they should be measured against each other.
     *
     * <p>⛔ MEASURED PAIRED AND NEGATIVE -- KEEP IT AT 1. Five pairs, each arm on the same
     * resolved ground, differing in this value alone:
     *
     * <pre>
     *   ground            fix(4)  ctrl(1)  delta
     *   1792 150 361         1       4      -3
     *   1192 150 661         0       0      +0
     *   592  150 661         0       0      +0
     *   292  150 661         0       0      +0
     *   -308 150 661         0       1      -1
     *   mean -0.80, never once positive
     * </pre>
     *
     * <p>The reasoning still looks right -- a route the queue can only stub SHOULD go to the
     * walker -- and it measures worse anyway, which is the whole reason this bench exists. Two
     * things are worth keeping from it. First, the naive version was strictly WORSE than the wall
     * it aimed at: stopping the queue every tick produced 446 starts and 0 steps against the untouched
     * code's 28 and 25, until a six-second walker window was added. Second, even with the window
     * the decline barely fires (short=4-29 a run against 369-6058 before), so what is left is a
     * change that rarely acts and loses when it does.
     */
    public int navQueueMinSteps = 1;

    /**
     * A stall checker may only be reset by the body MOVING, not by Nav claiming to be pathing.
     *
     * <p>⛔ THE SAME LINE APPEARS IN TWO TASKS AND DISARMS BOTH OF THEM:
     * <pre>
     *   if (Nav.isPathing()) progressChecker.reset();
     * </pre>
     * A stall is precisely the state where Nav believes it is pathing and the body does not move,
     * so the condition that defines the failure is also the condition that wipes its detector
     * every tick. Neither task can ever declare itself stuck, so neither ever hands control back.
     *
     * <p>Measured on the playthrough, both classes of stall this produces:
     * <pre>
     *   TimeoutWanderTask   wander=4406 wanderMoved=1063 wanderFail=0
     *                       4406 ticks -- 220 seconds -- to travel 10.6 blocks, and never once
     *                       called itself stuck. A healthy run reads wander=56 for 7.7 blocks,
     *                       so this is about 57x slower with the same code.
     *   DestroyBlockTask    dbTick=7568 dbUnreachMove=0 dbApproachStall=0 dbNear=0 dbFar=0
     *                       7568 ticks, every instrumented branch zero.
     * </pre>
     *
     * <p>With this on, the reset needs the body to have moved within {@link #STALL_MOVE_GRACE}
     * ticks. Nav may still say what it likes; the odometer decides.
     *
     * <p>FIRST PAIRED SERIES, six pairs, each arm on the same resolved ground:
     *
     * <pre>
     *   ground           fix  ctrl  delta  wanderDenied (fix/ctrl)
     *   592 150 -239      0    1     -1     56 / 0
     *   292 150 -539      0    0     +0      0 / 0
     *   292 150 -839      4    0     +4   3386 / 0
     *   592 150 -839      3    0     +3    216 / 0
     *   1192 150 -839     0    0     +0      0 / 0
     *   1192 150 -539     0    3     -3      0 / 0
     * </pre>
     *
     * <p>As-run: +0.50 over six pairs, sd 2.59, t=0.47. NOT established.
     *
     * <p>⛔ AND THE HONEST REFINEMENT, WITH ITS OWN CAVEAT. Three of those pairs have
     * wanderDenied=0 in the FIX arm -- the branch never fired, so they cannot say anything about
     * the flag, and that includes the -3. Restricted to the three pairs where it did fire the
     * mean is +2.00 (-1, +4, +3). That is per-protocol reasoning and it is a real statistical
     * risk: conditioning on anything after the fact can bias. It is defensible only because
     * wanderDenied is a MECHANISM counter and not the outcome, and it is recorded next to the
     * as-run number rather than instead of it.
     *
     * <p>Both readings sit on n=3-6 with a spread of 2.6. More pairs, then decide.
     *
     * <p>The two pairs it won are the interesting ones: the control scored ZERO on that ground
     * and the fix scored 4 and 3.
     *
     * <p>⭐ SHIPPED ON. Seventeen PAIRED runs across two series, each arm on the same resolved
     * ground (checklist 4a), scored with deploy/runner/paired_ab.py:
     *
     * <pre>
     *   stall time    -73.6 s per run   t -2.16   ESTABLISHED
     *   stall share   -20.9 %           t -2.13   ESTABLISHED
     *   rungs         +1.06             t +1.77   directional, NOT established
     * </pre>
     *
     * <p>So it buys back about a fifth of every run from standing still, and that clears the
     * 2-sigma bar. It does NOT establish more ladder progress, and that is not a formality:
     * removing a stall gives the bot its time back, it does not tell it what to do with it.
     * Quote the stall numbers, not the rungs.
     */
    public boolean stallCheckNeedsMovement = true;

    /**
     * Trigger the wounded disengage on LOSING THE EXCHANGE instead of on hp &lt;= LOW_HP.
     *
     * <p>The guards stay exactly as measured -- out past reach only, and only with something to
     * shoot -- because both were established by A/B and both are load-bearing. Only the trigger
     * changes, and the reason is written at the branch itself: with natural regeneration off and
     * no food, health inside one life never rises, so {@code hp <= LOW_HP} is not a threshold but
     * a ONE-WAY LATCH. Two or three hits put the bot under half, and every remaining tick of that
     * life returns to the kite whether or not it is actually behind.
     *
     * <p>DamageWatch's `losing` is the trigger that branch has been waiting for: a ROLLING window
     * of hits taken against hits landed, so it switches off again the moment the bot is ahead. On
     * allround it reads 261 ticks over 11 spells for the fighter that loses and 7 over 7 for the
     * one that wins -- it discriminates, on the same jar in the same run, which is what a trigger
     * has to do before anything is hung on it.
     *
     * <p>⛔ MEASURED AND WORSE, so the answer to the javadoc's open question is a NEGATIVE.
     * Six interleaved runs of allround:
     *
     * <pre>
     *   latch (hp &lt;= LOW_HP)     margin -5, -5, -6    kite ticks 90, 83, 85
     *   rolling (losing)         margin -8, -4, -10   kite ticks 65, 21, 51
     * </pre>
     *
     * <p>The trigger does exactly what it was built to do -- it fires and it LETS GO, so the bot
     * kites a third to a quarter as long. And it loses more. The control arm is unusually tight
     * (-5, -5, -6), which makes the -8 and -10 real rather than spread.
     *
     * <p>Which says the latch's stubbornness is not the defect it looks like. A rolling trigger
     * re-enters the fight the moment the window turns, i.e. exactly when the opponent is still
     * mid-combo, and pays for it. Whatever fixes this course is not a better disengage TRIGGER.
     */
    public boolean disengageOnLosingExchange = false;

    /**
     * A wounded bot HOLDS instead of kiting, even when it has a bow.
     *
     * <p>The branch already takes this path for sword-only kits, and its comment calls that half
     * sound: "hold, do not withdraw" -- stop walking into a fight you are losing, but do not step
     * out to a range where you deal nothing. What it never asked is whether OWNING a bow is
     * enough to make stepping out worth it.
     *
     * <p>On allround the answer looks like no, and the evidence is the opponent. It is the same
     * engine with a sword-only kit, so this branch declines for it (lowHp 0/0 against the bot's
     * 83-90 ticks) -- the ONLY fighter that kites is the one that loses, and the one forced to
     * fight wins. Separately, deleting the bow economy entirely was measured to change nothing on
     * this course, so the range the kite retreats to is not paying for itself.
     *
     * <p>An old control refuted this by removing the ARROWS, which also removes the bow's value
     * and everything else it does; and it ran in the early-stop regime where margins were -2
     * rather than -5. This isolates the kite alone, at full duration.
     */
    public boolean woundedHoldsInsteadOfKiting = false;

    /**
     * Re-issuing punk on the target we are already fighting carries on instead of restarting.
     *
     * <p>See PunkPlayerTask.start: a restart calls stop(), zeroes the counters and returns the
     * mode to APPROACH, so a driver that re-issues on every melee entry throws the fight away and
     * walks back in a dozen times a run. Keeping the fight is what the opponent gets for free by
     * being given punk exactly once.
     */
    public boolean punkRestartKeepsFight = false;

    /**
     * A drop within three blocks does not pay the anti-ping-pong tax when competing with an ore.
     *
     * <p>MineOrCollectTask.getClosestTo compares `dropSq <= blockSq` with +10 already added to the
     * DROP in squared-distance space and nothing added to the block. Ten squared is not a nudge: a
     * drop 2.6 blocks away -- the geometry measured on every failing mine_coal run -- scores 16.8,
     * so any ore within 4.1 blocks wins. Where the ore sits in a cluster, as it does here, that is
     * always true, so collection is deferred to the end of the run: exactly when the drops are
     * lying in the holes the bot just dug, which is when the barren locks happen.
     *
     * <p>The tax is right for a drop far enough away that walking to it costs mining time. It is
     * wrong for one the bot could touch in a step, where collecting costs a step and nothing else,
     * and where the drop is the objective rather than a distraction from it.
     *
     * <p>GATE: mine_coal (7/8 today, and its failures end coal=1 or coal=2 of 3 -- drops mined and
     * left behind). mine_stone and mine_diamond must not move. Proof it ran: drop's third field,
     * dropNearExempt, which counts ALWAYS and so reads non-zero in the control arm too.
     */
    public boolean dropNoPenaltyWhenNear = false;

    /**
     * Turn the search's fall-damage guard back ON. It is complete, correct, and switched off.
     *
     * <p>PathFinder.checkForFallDamage walks the parent chain of a candidate, rejects any segment
     * steeper than 2.75 blocks, and already exempts water, slime columns and slime bounces. Its
     * SECOND line is `if (ignoreFallDamage) return false`, and that field defaults to true -- so
     * the whole guard is skipped on every search, and the same field gates six other checks in
     * Node, BlockNode, RunToNode, SprintJumpMove and WalkToNode.
     *
     * <p>Measured on the playthrough: the bot goes from y=134 to y=60 and takes 25.3 damage, with
     * the damage witness attributing FOUR of four events to no living entity -- unattributedHits,
     * documented as falls, void and fire. dw=4/25.3/27.08/30.05/4/1. One run reached wood tools and
     * spent its last 150 s chipping stone on 1.5 hp; the next reached no rung at all.
     *
     * <p>WHY NO COURSE CAUGHT IT: nav_descend offers drops of 1, 2 and 3, all under the 2.75
     * threshold, so it is green either way. A course that only offers safe drops cannot test a
     * guard against unsafe ones.
     *
     * <p>GATE: the whole nav suite must not move (nav_gaps and nav_slime are the ones with a
     * plausible route through the air, and both have explicit exemptions in the guard), craft must
     * not move, and the playthrough's dmgTaken/unattributedHits should FALL. Proof it ran: turning
     * this on can only ever REMOVE candidate paths, so a search that used to fall and now does not
     * shows up as fewer unattributed damage events.
     *
     * <h2>SHIPPED ON (2026-08-23), BY ITS OWN GATE</h2>
     *
     * The gate written above is met in full: nav 14/14 with it on, craft 22/22 with it on, and the
     * playthrough's damage FELL. Eight runs paired, all eight passing:
     *
     * <pre>
     *   with     9.8  0.0  0.0  0.0     mean  2.45   deaths 0 of 4
     *   without  0.0 26.7 69.0  0.0     mean 23.9    deaths 2 of 4
     * </pre>
     *
     * <p>⛔ AND ITS GATE WAS ONE SUITE SHORT. The gate above names nav, craft and playthrough
     * damage, and those three were run before shipping. pvp was not named and was not run -- and
     * pvp contains two duels over VOID, which is exactly where a fall guard could bite. Measured
     * afterwards, interleaved, two pairs each:
     *
     * <pre>
     *   edge_duel           guard ON 2/2     guard OFF 0/2
     *   narrow_bridge_duel  guard ON 0/2     guard OFF 1/2
     * </pre>
     *
     * <p>edge_duel is BETTER with it, decisively at that size; narrow_bridge_duel leans the other
     * way by a single run. Across both, 2 of 4 against 1 of 4, so it stays on. The lesson is about
     * process rather than this flag: a gate written before the measurement did not think of duels
     * over void, and the next flag gets all four suites regardless of what its gate names.
     *
     * <p>t is 1.22, which is NOT this repo's generic bar, and it is shipped anyway for reasons that
     * are specific rather than convenient: this is not a behaviour tweak but a guard that is
     * complete and correct and was switched off by an unrelated default; turning it on can only
     * ever REMOVE unsafe candidate paths; its own gate names nav, craft and playthrough damage, and
     * all three are satisfied; and falls are the measured leading cause of death -- four of five in
     * a twenty-minute run, once deaths were counted at all.
     */
    public boolean pathAvoidsFallDamage = true;
    /**
     * A guard against fall DAMAGE must not reject a fall that deals no damage.
     *
     * <p>⛔ BOTH FALL GUARDS ARE OFF BY ONE BLOCK AGAINST VANILLA, AND IT IS EXACTLY THE DEPTH THE
     * PLAYTHROUGH KEEPS DYING ON. Vanilla fall damage is {@code floor(distance - 3)}: a three-block
     * drop costs NOTHING. Both guards reject it anyway --
     *
     * <pre>
     *   Node.java        reject when jumpHeight &lt;= -3      (3 blocks and deeper)
     *   BlockNode.java   reject when heightDiff  &lt;  -2      (3 blocks and deeper)
     * </pre>
     *
     * <p>The cost of that is measured. Two runs of the current sweep read
     * {@code fallRetry=3/5076} and {@code fallRetry=3/9378} -- nine thousand moves refused by the
     * guard against three relaxations, so in practice it is a VETO, not the preference its own note
     * argues for ("a player does not stand on a hill for five minutes rather than take three
     * hearts"). The same runs read {@code lock=6/1/4} and {@code lock=11/0/8}: eleven barren
     * thirty-second locks in one run, none productive.
     *
     * <p>And the geometry of those locks names the depth:
     *
     * <pre>
     *   cobblestone:6.5&gt;6.6, m0.2, h5.9, dy-3.0
     *   cobblestone:3.1&gt;3.7, m0.0, h2.2, dy-3.0
     *   cobblestone:7.8&gt;8.2, m0.6, h7.2, dy-4.0
     * </pre>
     *
     * The bot mines its cobblestone, the drop falls three blocks, and the search refuses every
     * route down to a fall that would not have scratched it -- so it stands over its own ore for
     * thirty seconds at a time. dy-3.0 is FREE and was refused.
     *
     * <p>This does not touch damaging falls: four blocks and deeper stay refused, and the
     * relaxation retry still exists for when nothing safe can be found.
     *
     * <p>Read fallHarmless=physics/blockspace to prove it fired. GATE: all four suites -- move
     * generation is under every course, and nav_cliff and the void duels are where a wrong
     * threshold would show up first.
     *
     * <h2>MEASURED: THE VETO IS GONE, THE CEILING IS NOT (2026-08-23)</h2>
     *
     * <pre>
     *   arm B (fix)      fallHarmless 253567   fallRetry 5/22      lock 5/1/5    stone@202.1s
     *   arm A (control)  fallHarmless 0        fallRetry 8/7717    lock 6/3/15   stone@223.6s
     *   arm B (fix)      fallHarmless 447717   fallRetry 5213/0    lock 0/0/0    stone@43.6s
     *   arm A (control)  fallHarmless 0        fallRetry 5330/686  lock 1/0/0    stone@21.4s
     * </pre>
     *
     * Hundreds of thousands of firings in the fixed arm and EXACTLY ZERO in the control, so the
     * mechanism is clean and exclusive. The guard's refusals collapse: 7717 and 686 without it,
     * 22 and 0 with it.
     *
     * <p>⛔ AND IT DOES NOT MOVE THE CEILING, WHICH IS THE PART TO SAY OUT LOUD. Every arm reaches
     * stone tools and stops there. The control run even took them FASTEST of the four, at 21.4 s,
     * so the ladder timings do not separate the arms at all -- the course's own noise is larger
     * than any effect here. Barren locks read 5 and 0 against 6 and 1, which on a metric this repo
     * has already measured at sevenfold spread on identical code is not a result either.
     *
     * <p>Ships ON as a CORRECTNESS fix, not as a playthrough fix: vanilla charges nothing for a
     * three-block drop and a damage guard must not veto it. Proven to fire, no regression (4/4
     * PASS). Claiming more than that would be claiming the noise.
     *
     * <h2>⛔ AND IT REGRESSES NAVIGATION, SO IT SHIPS OFF (2026-08-23)</h2>
     *
     * <pre>
     *   with the fix     nav_slime FAIL   nav_wall2 FAIL   nav_bridge FAIL     nav 11/14
     *   pinned false     nav_slime PASS   nav_wall2 PASS*  nav_bridge PASS
     * </pre>
     *
     * (* failed once, passed on the retry -- flaky, not broken.)
     *
     * <p>The bisect is unambiguous, and the reason is the thing I did not see: the guard was doing
     * DOUBLE DUTY. It was not only refusing damage, it was refusing DESCENT as a route choice. Make
     * a three-block drop free and the search takes it in preference to the answer each of those
     * courses is built to require -- bridging a gap, breaking through a wall, handling slime. Three
     * courses, three different intended solutions, all beaten by "just drop down".
     *
     * <p>So the arithmetic was right and the change was still wrong. Vanilla charges nothing for
     * three blocks, but this guard's threshold was also carrying the search's preference ordering,
     * and permission is the wrong instrument for a preference.
     *
     * <p>THE FIX THAT WOULD KEEP BOTH: fall depth as a COST in the search rather than a veto or a
     * permission -- a free drop allowed but PRICED, so bridging still wins where bridging is the
     * point and a drop wins where standing still for thirty seconds is the alternative. That is
     * what this file's own note has been asking for ("the guard should express a PREFERENCE, not a
     * veto") and it is the next pass. Nothing here is lost: the measurement stands, the mechanism
     * is proven to fire (253567 and 447717 against exactly zero in the controls), and the reason it
     * cannot ship as a permission is now written down.
     */
    public boolean fallGuardAllowsHarmlessDrop = false;
    /**
     * Price a descent instead of forbidding it -- the guard as a PREFERENCE, which is what this
     * file has been asking for since the fall-guard note was written.
     *
     * <p>⛔ WHY THE CHEAP VERSION FAILED, AND WHY THIS IS THE SAME IDEA DONE RIGHT.
     * fallGuardAllowsHarmlessDrop simply permitted the three-block drop vanilla charges nothing
     * for. It fired hundreds of thousands of times and took the guard's spurious refusals from
     * 7717 to 22 -- and it cost nav_slime, nav_wall2 and nav_bridge, because the threshold was
     * carrying the search's ROUTE PREFERENCE as well as its damage rule. Free descent beats
     * bridging, breaking and every other intended answer.
     *
     * <p>A price fixes exactly that asymmetry. Where a bridge exists, a priced drop loses to it and
     * the course keeps its intended solution. Where the alternative is a thirty-second barren lock
     * over the bot's own dropped ore, any finite price wins. Permission cannot express that;
     * a number can.
     *
     * <p>Priced in BOTH layers on purpose. Block-space runs first and hands the physics search a
     * corridor, so pricing only the physics layer would leave the coarse route already committed to
     * going down -- which is precisely how nav_bridge broke.
     *
     * <p>The precedent is next door: {@code breakCostMultiplier} prices mining onto the same edge
     * ({@code child.actionCost += ticks * 0.15 * ...}), for the same reason.
     *
     * <p>Read fallPriced=physics/blockspace to prove it fired. GATE: nav in full -- specifically
     * the three courses the permission version broke -- then the playthrough for the locks.
     *
     * <h2>MEASURED: KEEPS NAVIGATION, COSTS THE PLAYTHROUGH -- SHIPS OFF (2026-08-24)</h2>
     *
     * <p>It does the job it was built for. The three courses the permission version broke come
     * back, and the whole suite holds:
     *
     * <pre>
     *   permission version   nav 11/14   slime FAIL   wall2 FAIL   bridge FAIL
     *   priced version       nav 14/14   all green
     * </pre>
     *
     * <p>And then the playthrough says no, in both pairs, on both numbers:
     *
     * <pre>
     *   pair 1   priced   fallPriced 907/552921   lock 6/0/10   stone@314.5s
     *            control  fallPriced 0/0          lock 1/0/1    stone@155.7s
     *   pair 2   priced   fallPriced 0/20433      lock 3/0/0    stone@469.5s
     *            control  fallPriced 0/0          lock 1/0/1    stone@227.2s
     * </pre>
     *
     * More barren locks and slower stone tools with the price on, twice, with the mechanism firing
     * hundreds of thousands of times and exactly zero in the controls. Two pairs agreeing in the
     * same direction is not the lock metric's usual noise.
     *
     * <p>THE LIKELY REASON, WORTH TESTING CHEAPLY BEFORE ANYONE REVIVES THIS: a price KEEPS nodes
     * the veto used to prune. 552921 priced descents are 552921 extra nodes carried in the open
     * set, so the search gets bigger and slower exactly where the bot needs it fastest. If that is
     * right, the fix is not a better price but a CHEAPER TEST -- prune the descent as before and
     * admit it only when the search would otherwise fail, which is what the relaxation retry
     * already does and what fallRetry=5213 says it can do at scale.
     *
     * <p>So the whole line closes negative: the guard's threshold IS arithmetically wrong (vanilla
     * charges nothing for three blocks), and both ways of correcting it cost more than they return
     * -- permission breaks navigation, price slows the playthrough. Recorded rather than deleted,
     * because the next person to notice the off-by-one deserves the two measurements that follow it.
     */
    public boolean fallDepthPriced = false;
    /**
     * When the search GIVES UP, retry it once with the fall guard relaxed before salvaging a
     * partial route.
     *
     * <p>⛔ THE RELAXATION EXISTS AND IT IS WIRED TO THE WRONG EXIT. PathFinder.search ends in one
     * of three branches: the hard give-up, a stop, or an exhausted open set. The fall-guard
     * relaxation -- search safely, and if that fails take the damaging route -- sits ONLY on the
     * exhausted branch. And the comment on the hard give-up says why that branch is the wrong place
     * to put it: "on open ground the physics openSet never empties". The exit that actually fires
     * on real terrain never gets the retry.
     *
     * <p>That is what the two failed fall-guard attempts point at. Permitting the free drop broke
     * navigation, because permission carries route preference. Pricing it kept navigation and slowed
     * the playthrough, because a price KEEPS half a million nodes the veto used to prune. Both tried
     * to change what the search EXPLORES. This changes only what happens when it has already
     * failed -- the pruning stays exactly as it is, so there are no extra nodes and no preference
     * shift, and the guard is relaxed only for a search that was about to return nothing.
     *
     * <p>Read gaveUpFallRetry to prove it fired, and fallRetry -- whose first field already reads
     * 5213 on a playthrough, which is the exhaustion branch doing this at scale and proof the
     * retry mechanism itself is sound. GATE: nav in full, then the playthrough for the locks.
     */
    // DEFAULT CORRECTED (2026-08-24): its whole branch reads gaveUp=0/0 on every playthrough, so it can neither help nor be tested.
    // Found by the new stand-flag dump, which listed it as live on the bench while its own
    // note said otherwise. An unvalidated default is a measurement waiting to be corrupted.
    public boolean gaveUpRetriesWithFallGuardRelaxed = false;

    /** Price per block of descent, on the same edge as the walk and mining costs. */
    public double fallCostPerBlock = 30.0;

    /** Beyond this many blocks a fall is still refused outright, priced or not. */
    public int fallDepthHardLimit = 10;

    /**
     * Let the movement queue admit and play a RUNNING JUMP -- {@link
     * kaptainwutax.tungsten.path.movements.MovementParkour}.
     *
     * <p>THE LAST MISSING EDGE SHAPE, and on real terrain it is the one that decides the run.
     * Measured on a stalled playthrough, chain on {@code <Getting to block 74,127,-57>}:
     * <pre>
     *   mqStarted=28   mqSteps=25   mqTicks=3207   qNoMove=25   mqNoClass=27
     * </pre>
     * 27 of 28 chains truncated at an edge with no movement class, 25 steps advanced in 160
     * seconds, the identical route replanned each time. The shape was already recorded in the
     * queue from a live run: {@code {90,134,-36} -> {86,135,-34}}, four across and one up.
     *
     * <p>Both ADMISSION and dispatch are gated together. The pillar note in isSupportedEdge records
     * why: an edge that dispatch understands but admission does not still ends the prefix, so
     * wiring one half buys nothing.
     *
     * <p>GATE: nav_gaps and nav_steep, plus the three baselines -- ALL PASS with this on. Then the
     * playthrough, which is what it was for.
     *
     * <p>⛔ MEASURED AND NOT SHIPPED. Twelve playthrough runs, arms interleaved and every pin
     * verified:
     * <pre>
     *   parkour ON    3/6 PASS   mean rungs 2.0   mqSteps 44   mqNoClass 3
     *   parkour OFF   5/6 PASS   mean rungs 1.8   mqSteps 31   mqNoClass 672
     * </pre>
     * The class does precisely what it was built to do -- truncations collapse from 672 to 3 and
     * the queue advances 42% more steps -- and the VERDICT does not improve: rungs move 1.8 to 2.0,
     * which is noise at this spread, while PASS goes 5/6 to 3/6.
     *
     * <p>⭐ AND THAT IS THE FINDING, not the flag. mqNoClass averaging 672 in the control arm is
     * twenty-five times the single capture this work was built on, so truncation is far more
     * pervasive than one stalled run suggested -- and removing almost all of it did not convert
     * into rungs. The missing movement class was therefore NOT the binding constraint on the
     * playthrough. Something downstream of "the queue can play the route" is.
     *
     * <p>Same standing as MovementAscend had for a while: a complete, compiling, gated port that
     * waits for the rest of the picture rather than shipping on a mechanism that does not pay.
     */
    public boolean queueParkour = false;

    /**
     * Put the missing cells back into a compressed route before the queue tries to classify it.
     *
     * <p>The shapes that truncate a chain were counted rather than assumed, and every one of them
     * is a straight line along one axis: 0,0,-6 two hundred and eight times, then 0,0,-4, 0,0,4,
     * -4,0,0 and the rest. A six-block hop is not a move this game has, so those edges were never
     * movements -- they are waypoints with the corridor between them omitted, handed to a queue
     * that only knows unit steps.
     *
     * <p>This is why {@link #queueParkour} was measured as worthless (mqNoClass 477 against 479):
     * it added a capability for a shape that was not the one arriving. The cells are.
     *
     * <p>Expansion is refused wherever an intermediate cell has no headroom, so a cell buried in
     * rock is still left unclassified instead of being walked into.
     *
     * <h2>MEASURED AND REFUTED. It stays off.</h2>
     *
     * First guard also demanded a floor under every cell and refused ONE HUNDRED PERCENT of the
     * runs it saw, which measures nothing; relaxed to headroom only, matching isTraverseEdge,
     * which admits a unit step on geometry alone because MovementTraverse bridges and breaks its
     * own way. Then, on the first A/B taken after the counters were made to reset at all:
     *
     *     expand=false   mqStarted=0     mqSteps=1    mqNoClass=0
     *     expand=true    mqStarted=730   mqSteps=18   mqExpand=6555/2918/0/5100
     *     expand=false   mqStarted=0     mqSteps=1    mqNoClass=0
     *     expand=true    mqStarted=863   mqSteps=3    mqNoClass=863
     *
     * With it off the queue is not used at all on this ground -- the drive is the walker, and
     * mqStarted is zero. With it on, six thousand cells are expanded, seven hundred routes start,
     * and eighteen steps come out. Four fifths of the expanded cells have no floor (the fourth
     * number), so the queue is handed a corridor of bridges the executor must build with an empty
     * inventory. Busier is not closer.
     *
     * <p>What it did establish is worth keeping: the truncating shapes are NOT parkour. They are
     * straight multi-block runs and short ascending hops over gaps, which means the planner is
     * handing the queue coarse waypoints and the queue is right to refuse them. The next question
     * is therefore not "teach the queue another movement class" but "why does BlockPathWalker,
     * which correctly gets the route instead, fail to arrive" -- and that is where to look next.
     */
    public boolean queueExpandsStraightRuns = false;

    /**
     * Keep a leg's "this is a bridge" flag alive until the MovementQueue has actually taken it.
     *
     * <p>FastNavigator cleared nextLegBridge one line BEFORE asking the queue, so a refused build
     * leg arrived at the fallback with the flag already false and went to BlockPathWalker instead
     * of BridgeTask. The walker walks the cells it is handed and cannot place a block, and the
     * cells of a bridge are exactly the ones with nothing underneath: four fifths of them measured
     * floorless. The bot sprints along them and falls -- y=127 down to y=118, health 20 to 5.5, in
     * one fifty-second repro, with the target at y=127 throughout.
     *
     * <p>The old comment there treated refusal as a rare race ("the plan changed shape under us").
     * Single runs measure qShort=4397 and qNoClass=1204 against ten accepted starts, so refusal is
     * the ordinary case and that fallback is the main path, not an edge one.
     *
     * <p>Read navBridgeRescued to check the fix fired at all before believing any comparison.
     */
    public boolean navBridgeSurvivesQueueRefusal = false;

    /**
     * Give the BFS half of BlockPathWalker the hole check the DIRECT half already has.
     *
     * <p>tickDirect consults SafetySystem (isJumpLandingSafe, hasHolesOnPath) before it commits to
     * a sprint. tickBFS consults nothing: it steers at the waypoint it was handed and presses
     * forward. That is safe only while those cells have floors, and a build leg refused by the
     * MovementQueue lands here with cells that by construction do not -- four fifths of them
     * measured floorless.
     *
     * <p>Refused only when the waypoint is LEVEL with the bot or above it, so a planned descent
     * (nav_descend drops three on purpose, which is exactly what hasHolesOnPath trips on) is
     * untouched and only an unannounced gap on a flat run is held.
     *
     * <p>Held means standing, which this file measures as the better of the two states: 11.0
     * standing against 22.5 and 22.5 for the runs that fell.
     *
     * <p>Check walkerHoleHeld before believing any comparison -- zero means it never fired.
     */
    public boolean walkerRefusesHoleOnLevelRun = false;

    /**
     * Let an ascending MovementDiagonal report UNREACHABLE once it is walled at the destination's
     * height, instead of holding forward against it for the rest of the run.
     *
     * <p>Traced on a playthrough, one line repeating for five minutes:
     *
     * <pre>
     *   MV (85,124,-54)->(84,125,-55) st=RUNNING feet=(85,125,-54) pos=85.30,125.00,-53.70
     *   stuck=[on:stone in:air head:air coll:Y v:0.00 fwd:Y jump:n Diagonal/RUNNING/idx0of5]
     * </pre>
     *
     * <p>Three mechanisms hold that open at once. calculateValidPositions lists src.above() for an
     * ascend and the body is standing on it, so playerInValidPosition stays true and the movement
     * is never UNREACHABLE. The ascend-jump gate tests the body against SRC, which it has already
     * climbed off, so it cannot fire again. And corners() vetted the corner columns at the START
     * height, one below the body it now has, so whatever is in the way was never the block checked.
     *
     * <p>The run that produced it: mqStarted=64 against mqSteps=9, dbTargets=12/0, no rungs.
     *
     * <p>Deliberately not a nudge, a shove or an extra jump -- which corner is blocked is not known
     * here, and guessing it is how the last three remedies in this area were built. Giving up hands
     * the chain back and lets the navigator replan.
     *
     * <p>Read diagonalWalled before trusting any comparison: zero means it never fired.
     */
    public boolean diagonalGivesUpWhenWalled = false;

    /**
     * Plan the next leg from the bot's actual block position when it disagrees with the remembered
     * tail of the previous leg.
     *
     * <p>A tail is a prediction; the body's cell is a fact. They diverge by one block and every
     * leg after that is built from somewhere the bot is not. Traced to the block on a stall that
     * reproduces on demand:
     *
     * <pre>
     *   src        (85,124,-54)  AIR          the leg started here
     *   srcAbove   (85,125,-54)  air          the feet are actually here
     *   cornerA    (85,125,-55)  grass_block  SOLID, at the body's real level
     *   cornerB    (84,125,-54)  dirt         SOLID, at the body's real level
     *   cornerAlow (85,124,-55)  air          and this is the corner that got vetted
     * </pre>
     *
     * <p>MovementDiagonal vets its corner columns at the START cell's height. One block low, it
     * cleared a corner that is air a level down while the body sat boxed between two solid ones,
     * and pressed forward at zero velocity for the whole run -- mqStarted=64 against mqSteps=9,
     * dbTargets=12/0, no rungs.
     *
     * <p>Treating the symptom does not work and was measured: making the diagonal give up fires
     * reliably (diagonalWalled=22 a run, control arms 0) and leaves the bot exactly as pinned,
     * because the planner re-emits the same edge from the same wrong cell.
     *
     * <p>Read navPlannedFromStaleTail: zero means the tail and the body never disagreed.
     */
    public boolean planFromActualPosition = false;

    /**
     * Stop a wedged MovementQueue from gagging the navigator's stall watchdog.
     *
     * <p>FastNavigator treats {@code MovementQueue.isRunning()} as "building", and building resets
     * stallTicks every tick. So a leg that is RUNNING and going nowhere switches off the only
     * mechanism that could rescue it. Traced end to end: a MovementDiagonal built from a cell one
     * block below the body, boxed between two solid corners, holding forward at v=0.00, with the
     * navigator beside it for five minutes and pdPlan=0/0 -- it never replanned once.
     *
     * <p>Same defect as the one fixed in MovementProgressChecker ("we broke it, so that is
     * progress"), and the queue's own null-route note warns of it directly: a queue that
     * perpetually runs a route to nowhere means the checker cannot trip.
     *
     * <p>The exemption is not removed, it is put on a clock. Standing still IS legitimate while a
     * block is being placed; three seconds of no horizontal motion is not.
     *
     * <p>Read navWatchdogUngagged: zero means no queue ever held the watchdog shut.
     */
    public boolean stallWatchdogNeedsMotion = false;

    /**
     * Measure the MovementQueue's "body will not leave its cell" stall HORIZONTALLY.
     *
     * <p>The check compares the feet CELL against last tick's, and a body that hops in place
     * defeats it: on a repro that pins the bot at 85.3,-53.7 the y reads 125.2, 124.8, 124.5,
     * 124.0, 125.3, so the feet cell alternates between (85,124,-54) and (85,125,-54) and
     * ticksNotMoving resets every other tick. The chain is therefore never dropped.
     *
     * <p>That matters far more than one chain, because of who else is waiting. While a chain is
     * RUNNING the mixin returns early and NOTHING else ticks -- not BlockPathWalker, not the build
     * primitives, not the physics executor -- and FastNavigator counts isRunning() as "building"
     * and so never replans either. One wedged chain freezes every engine at once. Measured on the
     * repro: 36 chains started, ZERO steps taken, the walker ticked 36 times and was inactive on
     * every one, pdWalking=0 against pdEnter=733.
     *
     * <p>Costs nothing while the body is genuinely moving; MAX_TICKS_NOT_MOVING is unchanged.
     */
    public boolean queueStallIsHorizontal = false;

    /**
     * Make the MovementQueue check, at its door, that a leg starts where the body actually is.
     *
     * <p>The playthrough's worst stall is an off-by-one built exactly there. A leg is planned from
     * legTail -- the tail the PREVIOUS leg predicted -- and when the body ends up one block above
     * it, the chain's first movement starts from a cell the bot is not in:
     *
     * <pre>
     *   cells.get(0) (85,124,-54)  AIR          the movement's start
     *   feet         (85,125,-54)  air          where the body actually is
     *   cornerA      (85,125,-55)  grass_block  SOLID at the body's level
     *   cornerB      (84,125,-54)  dirt         SOLID at the body's level
     *   cornerAlow   (85,124,-55)  air          the corner that got vetted, a level down
     * </pre>
     *
     * <p>MovementDiagonal vets its corners at its START cell's height, so one block low it clears a
     * corner open below while the body is boxed between two solid ones, then holds forward at
     * v=0.00 for the rest of the run. And a RUNNING chain makes the mixin return early, so nothing
     * else ticks either: walkMode=36/0/0, pdWalking=0 against pdEnter=733, while FastNavigator
     * counts isRunning() as "building" and never replans.
     *
     * <p>Fixing this at the planner cannot work -- during the stall planAhead is not called at all
     * (staleTail=0 on every arm). The door is the place, the same choke point the route dedupe
     * uses.
     *
     * <p>Rebases where it can (stale leading cells are dropped if the body appears further along)
     * and refuses only when the body is nowhere on the route, which sends the leg to the walker.
     * Read qRebased and qOffRoute; both zero means nothing was ever off-route.
     */
    public boolean queueRebasesToFeet = false;

    /**
     * Ask the navigator to replan from the BODY when a movement reports UNREACHABLE or FAILED.
     *
     * <p>Two sibling handlers in the same method already do this -- the teleport check and the
     * body-has-not-moved check both call FastNavigator.replanFromHere() after stop(). The
     * status-failure branch only stopped. So a movement that honestly reports it cannot be done
     * ends its chain and asks for nothing, and the navigator carries on from legTail, the tail the
     * PREVIOUS leg predicted, and plans the identical route again.
     *
     * <p>Measured on the stall repro: MovementDiagonal reported UNREACHABLE twenty-two times in one
     * run (diagonalWalled=22, control arms 0) and the bot did not move a block. Giving up ended the
     * chain without changing the answer.
     *
     * <p>Why replanning from the BODY specifically is the fix: FastPlanner.expand already refuses
     * to cut a corner -- both orthogonal cells must be passable -- so from the body's real cell,
     * one block up, the diagonal that wedged is rejected outright and a different route comes back.
     * From the stale tail a level below, the same corner reads open and the same route returns.
     *
     * <p>Read qUnreachReplan: zero means no movement ever reported a status failure.
     */
    public boolean unreachableReplansFromBody = false;

    /**
     * Stop the grid BFS offering a diagonal that squeezes past a solid corner.
     *
     * <p>This is the root of the playthrough's worst stall. CombatPathfinder's HORIZONTAL set
     * includes the four diagonals, and the only test applied to one was whether the DESTINATION is
     * walkable -- nothing looked at the two cells the body must pass between. So the grid routes a
     * diagonal through a notch with solid blocks on both sides, a step vanilla cannot make.
     * FastPlanner has always refused exactly this (expand() calls sideClear on both orthogonals);
     * this producer never did, and it is the one driving the playthrough ("primDrive gridBFS sz13"
     * in the log).
     *
     * <p>The cost, traced end to end: the queue ACCEPTS the diagonal, MovementDiagonal cannot
     * execute it and holds forward at v=0.00, and a RUNNING chain makes the mixin return early so
     * NOTHING else ticks -- walkMode=36/0/0, pdWalking=0 against pdEnter=733 -- while FastNavigator
     * counts isRunning() as "building" and never replans. mqStarted=64 against mqSteps=9,
     * dbTargets=12/0, no rungs.
     *
     * <p>The blocks at the traced spot, read rather than assumed: (85,125,-55) grass_block and
     * (84,125,-54) dirt, both full, with the diagonal between them offered as a route.
     *
     * <p>Read gridCornerRefused: zero means no diagonal was ever offered past a corner.
     *
     * <h2>SHIPPED ON (2026-08-22)</h2>
     *
     * nav 14/14 and craft 22/22, both against baselines of the same score on the same build, so it
     * costs nothing where it does not help. pvp went 10/12 to 11/12 -- edge_duel, one of the red
     * courses, now passes. On the restored-notch repro the control arm wedged 2 of 3 and this arm
     * 0 of 3, with three to ten times the steps.
     */
    public boolean gridBfsRefusesCornerCut = true;

    /**
     * A give-up FAR from a target block buys a retry, not a blacklisting.
     *
     * <p>DestroyBlockTask already split its give-ups into near and far -- "those need opposite
     * fixes" -- and then sent both to requestBlockUnreachable. Measured on the playthrough:
     * dbFar=18 against dbNear=0, with the give-ups landing at a MEAN DISTANCE OF 46 BLOCKS. The
     * bot loses its progress check out on the walk (a detour round an obstacle is enough, since
     * the checker wants the distance to keep shrinking) and condemns a tree it has never stood
     * next to.
     *
     * <p>It cascades. The scanner hands back the next-nearest log, which is further, so the walk is
     * longer and the checker is likelier to trip again. One run burned through TWENTY-SIX targets
     * and reached NONE (dbTargets=26/0), ending up aimed at a log fifty-eight blocks away while
     * standing in a forest with a hundred and fifty within forty.
     *
     * <p>Three strikes on the SAME block before it is condemned, so a genuine dead end is still
     * dropped -- just not on the first stumble. Read dbFarRetried and dbFarCondemned.
     */
    public boolean farGiveUpRetriesFirst = false;

    /**
     * Never hand a goal to the legacy engine. Ask tungsten for it instead, or wait a tick.
     *
     * <p>CustomBaritoneGoalTask has one line left where shredder still moves the bot: when the
     * tungsten drive declines a tick, the goal goes to getCustomGoalProcess().setGoalAndPath. The
     * comment there reasoned the count was inflated by finished tasks poking it on the way out --
     * true, and not the whole story. Nine genuine hand-offs remain on a twenty-minute playthrough
     * (pdLegacy=9), and they are visible in a recording: the bot switches to shredder's own route
     * in the middle of a tungsten run.
     *
     * <p>Deleting the legacy engine is the point of this project, so this is not a better fallback
     * but no fallback. The goal is offered to TungstenHelper.tryPathTo -- the same call the
     * stuck-recovery path above already makes -- and if tungsten will not take it, the tick is
     * spent waiting rather than moving on the engine being removed.
     *
     * <p>Read pdLegacyToTungsten and pdLegacyDeclined: the first is the hand-off saved, the second
     * is tungsten refusing as well, which is the number that says whether waiting costs anything.
     *
     * <h2>SHIPPED ON (2026-08-22)</h2>
     *
     * Measured on a twenty-minute playthrough: pdLegacy=16 with pdLegacyTung=0/16 -- sixteen
     * hand-offs to the legacy engine PREVENTED, tungsten declining all sixteen, so the cost is
     * sixteen ticks of waiting in twenty minutes. The run passed with five rungs to stone tools.
     * nav is 14/14 with it on. The user saw baritone driving in a recording; it no longer does.
     */
    public boolean neverHandOffToLegacy = true;

    /**
     * Let BlockPathWalker stand aside while a block-breaking task owns the aim and the keys.
     *
     * <p>The placer already has this claim (EXECUTOR.placingNow) and the walker honours it. The
     * miner never did. Reported from a RECORDING, not a counter, which is why it survived a whole
     * session of reading numbers: the camera swings toward the walker's waypoint while the block
     * being broken is elsewhere, and the body shuffles on the spot because DestroyBlockTask holds
     * MOVE_BACK and SNEAK within two blocks of its target while the walker holds MOVE_FORWARD in
     * the same tick. Two writers, last one wins, every tick -- pitfall P1 of
     * docs/BARITONE-PORT-SPEC.md, in the half of the code the mixin's early return does not cover.
     *
     * <p>The claim is a TIMESTAMP, not a latch, so an interrupted mine lapses on its own instead of
     * freezing the walk for the rest of the run.
     *
     * <p>Read walkerYieldedToMiner: zero means the two never contended and this changed nothing.
     */
    public boolean walkerYieldsToMiner = false;
    /**
     * A target within walking distance must not be held by a SEARCH LOCK that drives nothing.
     *
     * <p>⛔ THE BIGGEST MEASURED LOSS IN THE PLAYTHROUGH, AND IT IS AN ORDERING MISTAKE.
     * Four playthroughs scored 13 barren locks against 1 productive. A lock runs 30 s
     * (LOCK_DURATION_MS), so that is on the order of 390 s of standing still per sweep. The
     * geometry of one of them:
     *
     * <pre>
     *   spruce_log:3.5&gt;3.3, m0.0, h3.1, dy+1.0
     * </pre>
     *
     * A drop three and a half blocks away, and the body moved ZERO. Nothing about that is a
     * pathfinding problem -- at that range no route is needed.
     *
     * <p>The primitive that handles it already exists twenty lines below: "face it and hold
     * forward. No search, no route, no lock", written after this same defect was traced to a bot
     * standing 1.17 blocks from a drop. It is UNREACHABLE while a lock is held, because
     * {@code if (TungstenHelper.isActive())} returns null on every branch it has -- the task's only
     * two outcomes under a lock are "do nothing" and "release". So the lock does not merely fail to
     * drive the body: it shuts out the one thing that would.
     *
     * <p>The decision "search or walk" is being taken AFTER the lock. It belongs before it. When
     * the target is inside close-walk range and nothing is actually driving, drop the lock and let
     * the walk below have the tick.
     *
     * <p>Read nearLockDropped to prove it fired, and lock=barren/productive/refused for whether it
     * paid. GATE: playthrough plus nav, craft and pvp -- entity approach is on every course.
     */
    // DEFAULT CORRECTED (2026-08-24): measured 0/0/0/0 -- never fires; the default was left true when the verdict was written.
    // Found by the new stand-flag dump, which listed it as live on the bench while its own
    // note said otherwise. An unvalidated default is a measurement waiting to be corrupted.
    public boolean nearTargetDropsLockForWalk = false;

    /**
     * While a block-breaking task holds the aim, the path executor must not steer the camera.
     *
     * <p>⛔ THIS IS THE CAMERA THIEF, AND THE CLAIM WAS ALREADY BEING WRITTEN TO NOBODY.
     * DestroyBlockTask stamps {@code minerAimUntilMs} every tick it aims at a block. Exactly one
     * component read that stamp -- BlockPathWalker, under walkerYieldsToMiner -- and the walker is
     * not what moves the camera. {@link kaptainwutax.tungsten.path.PathExecutor} is: it replays
     * {@code player.setYaw(node.input.yaw)} on EVERY node of a live path. So the miner aimed at the
     * block, the executor aimed at the next waypoint, and the last writer of the tick won.
     *
     * <p>That is the defect the operator described from a recording, in his words: "it swings the
     * camera one way while the block breaks somewhere else", plus a bot that "twitches back and
     * forth". Both are one mechanism -- two owners writing the same two fields every tick.
     *
     * <p>THE SAME FILE ALREADY SOLVED THIS FOR THE BOW. The block above the setYaw call explains
     * that a drawn arrow never converged because movement overwrote the aim each tick, and fixes it
     * by keeping the camera with the aimer and re-expressing the movement keys into the frame the
     * bot is actually facing ({@code reframeMovement}), which preserves the WORLD-SPACE direction
     * of travel. The machinery is proven; it was simply never extended past BowShooter.isActive().
     *
     * <p>It also explains why walkerYieldsToMiner measured nothing and was switched off: it yielded
     * the wrong component. That negative was real, and it was aimed at the wrong target.
     *
     * <p>Read execYieldMiner to prove it fired. GATE: playthrough plus nav, craft and pvp -- the
     * executor is on every path in every suite, so this one does not ship on mining courses alone.
     *
     * <h2>MEASURED: REFUTED -- THE MECHANISM ESSENTIALLY NEVER FIRES (2026-08-23)</h2>
     *
     * <pre>
     *   execYieldMiner per run   0   0   1   0      (arms B A B A; B is this flag ON)
     * </pre>
     *
     * Zero and one, in the two arms where it was ON. The overlap this guards against -- a miner
     * aiming WHILE the path executor replays -- does not happen, because the mining branch calls
     * Nav.clearGoal() in the same block that stamps the claim: it kills the path before it aims.
     * So the diagnosis "these two fight over the camera" is WRONG, however well it fitted the
     * operator's description. Whatever swings the camera in the recording, it is not this pair.
     *
     * <p>Ships OFF. Kept, with its numbers, because the reasoning was sound and only the pairing
     * was wrong -- and because the counter is what proved it, in one sweep, rather than three more
     * passes of argument.
     *
     * <p>The ladders that sweep produced (-, crafting, wood tools, stone tools) say nothing about
     * this flag either way: with the mechanism firing 0-1 times, the spread is the course's own
     * noise, which this repository has already measured at sevenfold on identical code.
     */
    public boolean executorYieldsAimToMiner = false;

    /**
     * The legacy engine must yield input control to the block-space WALKER too, not only to the
     * path executor.
     *
     * <p>⛔ THE ARBITRATION ALREADY EXISTS AND IT IS HALF-WIRED. baritone's InputOverrideHandler
     * decides each tick whether to install its own PlayerMovementInput, whose forced keys are all
     * zero while it is not pathing. Installing it over a live tungsten movement ZEROES tungsten's
     * key presses -- the comment at that site says so in as many words: "nullifies tungsten's key
     * presses -- the bot freezes under altoclef tasks even though tungsten's executor is simulating
     * forward motion".
     *
     * <p>Its guard asks only {@code isExecutorRunning()}. But BlockPathWalker drives the body
     * WITHOUT the executor, pressing keys directly, and on those ticks the guard reads false, the
     * legacy engine takes control back, and the walker's keys are zeroed by an engine that is not
     * even pathing.
     *
     * <p>This is the operator's third video complaint -- "baritone keeps activating and breaking
     * the route" -- and it is the same defect as the other two: two owners of one field, no
     * arbitration. Same answer as {@code wanderKeepsWalkerKeys}, which measured well at the
     * TimeoutWanderTask site: ask whether the walker is driving before taking its keys away.
     *
     * <p>Read legacyYieldWalker to prove it fired. GATE: all four suites -- this sits on the input
     * path of every task in every course.
     */
    // DEFAULT CORRECTED (2026-08-24): shipped ON and never measured -- my own gate rule, broken by me.
    // Found by the new stand-flag dump, which listed it as live on the bench while its own
    // note said otherwise. An unvalidated default is a measurement waiting to be corrupted.
    // ⛔ BACK ON, AND SWITCHING IT OFF WAS MY MISTAKE. It went off in a sweep of
    // 'unvalidated defaults' on the grounds that it had never been measured. That was
    // backwards: this flag makes the LEGACY engine stand down, so disabling it hands
    // the body back to baritone. The counter says exactly that -- legacyYieldWalk reads
    // 0 across seventeen samples taken since, and 1018-1223 before.
    //
    // And the damage is measured: legacyDrive=path/overlap/explore reads 8558/18/9384,
    // 8079/2134/8603, 7988/610/7952 -- the legacy engine EXECUTING A PATH for eight
    // thousand ticks a run, up to 2134 of them while the tungsten executor is running
    // too. Two engines on one body is what the operator keeps seeing as freezing and
    // looking one way while acting another.
    public boolean legacyYieldsToWalker = true;

    /**
     * Let the close-range walk to a dropped item release SNEAK.
     *
     * <p>The walk presses forward and never touched sneak, while DestroyBlockTask holds SNEAK
     * whenever it is within two blocks of the block it is breaking -- which it always is, having
     * just mined the drop being walked to. Sneaking cannot leave a ledge, by design, and the drop
     * that fails mine_coal sits ONE BLOCK DOWN in the hole the bot dug. So the bot paces the rim.
     *
     * <p>The failing run's numbers agree with this and with nothing else:
     *
     * <pre>
     *   entityCloseWalk=241/125/9/126/0/240/1   moved 125, closer 9, retargeted ONCE, yaw held 240
     *   lock=...coal:1.6>2.0,m0.0,h1.8,dy-1.0   the gap GREW, 1.8 across and one block down
     * </pre>
     *
     * <p>The leading explanation in the source -- that the bot alternates between the three coal
     * drops and zigzags -- is refuted by that retarget count of one.
     *
     * <p>GATE: mine_coal must go green (it is 0/12 across two interleaved A/Bs), and nav_cliff,
     * nav_gaps and pickup_ledge must not move -- those are the courses where letting go of sneak
     * beside a drop could cost a fall. Read closeWalkSneakReleased for proof it ran.
     */
    public boolean closeWalkReleasesSneak = false;

    /**
     * Let the close walk take down a live WindMouse lease before it snaps the camera at the drop.
     *
     * <p>WindMouseRotation holds a target for 600 ms and steers the camera back to it every render
     * frame. GetToEntityTask's note records that lease as the obvious suspect for the aim not
     * surviving, and that it MEASURED ZERO. It does not any more:
     *
     * <pre>
     *   entityCloseWalk=604/15/15/428/360/277/3      leased = 360 of 604 ticks
     * </pre>
     *
     * <p>Three fifths of the walk runs against a live lease pulling the camera off the drop between
     * snaps. LookHelper warns that arming the lease from a snap fights the snap path on the same
     * camera; this is that fight from the other side, and the user saw it as the crosshair pointing
     * somewhere other than the block being worked.
     *
     * <p>Read closeWalkLeaseCleared for proof it ran, and entityCloseWalk's fifth slot (leased) for
     * whether the contention actually falls.
     */
    public boolean closeWalkClearsCameraLease = false;

    /**
     * Let the close walk CLAIM the aim, so BlockPathWalker stops steering at its own waypoint.
     *
     * <p>Clearing the lease was tried first and is useless: with that pinned on, cwLease read 120
     * of 120 ticks -- it fired every tick -- and the lease still read LIVE on all 120, with the
     * body moving zero times. The owner puts its target back between our snaps, so taking it down
     * once a tick buys nothing.
     *
     * <p>The owner is BlockPathWalker. It already knows how to stand aside: it yields to the placer
     * and, since today, to the miner. This makes the close walk claim the aim the same way, through
     * the same timestamp, so the walk that is trying to reach a drop is the one owner of the camera
     * for that tick. Requires walkerYieldsToMiner, which is what reads the claim.
     *
     * <p>Read closeWalkAimClaimed for proof it ran, and entityCloseWalk's fifth slot (leased) for
     * whether the contention actually falls.
     */
    public boolean closeWalkClaimsAim = false;

    /**
     * Make the close walk decline a drop that is three or more blocks straight down.
     *
     * <p>It steers by atan2(x, z) and presses forward. With the drop almost directly underneath
     * that direction is degenerate, and the press only holds the body against the rim of the hole
     * it is standing on. The geometry counter shows this is not a corner case:
     *
     * <pre>
     *   closeWalkGeom=236/0/0/368   below on 236 of 604 ticks
     *   deep=0/0/236                ALL of them three blocks or deeper
     * </pre>
     *
     * <p>Not one tick at one block down, not one at two. Two earlier attempts were aimed at the
     * other half of this and measured accordingly: releasing sneak moved the body on 15 ticks of
     * 604, and fixing the camera took the aim from 86% to 99.5% while moving it on 14 of 602.
     *
     * <p>Declining hands the tick back to navigation, which CAN descend -- nav_descend and
     * nav_cliff are green -- instead of overriding it with a press that cannot work.
     *
     * <p>GATE: pickup_pit and pickup_ledge are the courses with a drop below and must not move.
     * Read closeWalkDeepDeclined for proof it ran.
     */
    public boolean closeWalkDeclinesDeepDrop = false;

    /**
     * Keep the item in the cursor when a slot cannot be resolved, instead of dropping it.
     *
     * <p>SlotHandler answers an unresolvable slot by clicking PlayerSlot.UNDEFINED, and a click
     * OUTSIDE the window is vanilla's drop action -- so the cursor's contents end up on the ground.
     * The counter beside it exists to watch for that and reads shThrown=7 on a twenty-minute
     * playthrough.
     *
     * <p>What it cost on that run: the bot dropped its WOODEN PICKAXE and spent the rest of the
     * twenty minutes chasing it into a cave --
     *
     * <pre>
     *   lock=wooden_pickaxe:41.6>41.6, m0.0, h34.0, dy-24.0 @bot[605.61,106.00,-245.35]
     *   end inv: dirt, brown_mushroom     blockedBy=dripstone_block
     * </pre>
     *
     * <p>Forty-one blocks out and twenty-four down, no wood gathered, ladder zero. Doing nothing is
     * strictly better: the cursor keeps the item and the caller asks again next tick. A slot that
     * cannot be resolved this tick usually can the next; an item on a cave floor usually cannot be
     * recovered at all.
     *
     * <p>GATE: the craft suite is where slot handling lives and must not move; watch shThrown fall
     * to zero and shUnresolvedKept take its place.
     */
    public boolean unresolvedSlotKeepsItem = false;

    /**
     * Stop the interaction-fix chain dropping a stack it has just been told it may not drop.
     *
     * <p>PlayerInteractionFixChain unsticks a cursor stack in four steps: put it in the inventory,
     * throw it away IF canThrowAwayStack allows, use a garbage slot, and then -- unconditionally --
     * throw it away. Reaching that last line means canThrowAwayStack said NO and there was no
     * garbage slot, and it drops the stack anyway. Slot.UNDEFINED is index -999, vanilla's click
     * outside the window.
     *
     * <p>Measured: shThrown=70 in a twelve-minute run. On a twenty-minute one the item lost this way
     * was the bot's WOODEN PICKAXE, and it spent the remainder chasing it into a cave --
     * lock=wooden_pickaxe:41.6>41.6, h34.0, dy-24.0 -- finishing with dirt and a mushroom, no wood
     * and no rungs, on a run with ZERO deaths and 39 of 53 targets reached.
     *
     * <p>Keeping the stack costs a tick; the first branch places it as soon as a slot frees.
     * Throwing it costs the item and sometimes the run.
     *
     * <p>GATE: craft is where slot handling lives and must not move. Read fixKeptCursor for proof
     * it ran and shThrown for whether the drops actually stop.
     */
    public boolean neverThrowWhatCannotBeThrown = false;

    /**
     * Put a ceiling on what a single dropped item may cost before it is written off.
     *
     * <p>PickupDroppedItemTask gives up on a drop when its PROGRESS CHECKER trips, and a bot walking
     * steadily toward something forty blocks away is making progress the whole way. So it never
     * trips: a twenty-minute run was spent following the bot's own wooden pickaxe into a cave --
     * lock=wooden_pickaxe:41.6>41.6, h34.0, dy-24.0 -- ending with dirt and a mushroom and no rungs.
     *
     * <p>The instrument settled what this is NOT. Deep picks read 15324 on that run and 0 on the two
     * after it, and every sampled choice had a single candidate (of=1, of=1, of=2), taking the
     * cheaper of the two when there were two. The ranking is sound and the descent price is sound;
     * it is ONE target, chosen once, pursued for fifteen thousand ticks because nothing bounds it.
     *
     * <p>Two minutes, deliberately generous: a drop worth a minute of walking is worth having, and
     * one that has taken two is not.
     *
     * <p>GATE: the pickup courses (pickup_flat, side, ledge, pit, vs_mine, after_goto) all finish
     * well inside the budget and must not move. Read dropBudgetSpent for proof it fired, and
     * deepPicks for whether the long pursuits actually stop.
     *
     * <h2>SHIPPED ON (2026-08-23), AND THE MEASUREMENT SETTLES THE OTHER HALF TOO</h2>
     *
     * <pre>
     *   deepPicks=9485/65   only 65 of 9485 deep picks had ANY alternative
     *   dropBudget=1        the budget fired
     *   ladder ... stone tools@228.0s, run PASSED
     * </pre>
     *
     * <p>Ninety-nine point three per cent of deep picks were the only candidate, which retires the
     * idea that the descent price was choosing them -- there was nothing to choose. The pursuit had
     * no ceiling, and now it has one.
     *
     * <p>Gate met: the six pickup courses read 6/6 with it on.
     */
    public boolean dropPursuitHasBudget = true;

    /**
     * The same ceiling as dropPursuitHasBudget, for chasing an ENTITY rather than an item.
     *
     * <p>AbstractDoToEntityTask gives up when its progress checker trips, and a bot walking toward a
     * sheep sixty-seven blocks away is progressing the whole way. After stone tools the playthrough
     * wants a BED, which wants WOOL, which wants SHEEP:
     *
     * <pre>
     *   lock=13/4/18@sheep:67.6>67.6, m0.0, h67.5 @bot[372.71,96.00,72.50]
     *                sheep:41.2>41.2, m0.0, h41.2 @bot[422.35,95.00,113.30]
     *   avoidSrc=...@PlaceBedAndSetSpawnTask.onStart:147
     * </pre>
     *
     * <p>Thirteen barren locks against four productive, the ladder frozen at stone tools for the
     * last fifteen minutes of a twenty-minute run, and an empty pack at the end.
     *
     * <p>Ninety seconds on one entity, then it is written off. Generous for a chase, far short of a
     * run.
     *
     * <p>GATE: mob_melee and the chase courses live on entity approach and must not move, and this
     * time ALL FOUR suites are run before shipping, whatever the gate names -- the fall guard's
     * gate omitted pvp and pvp is where it had to be checked. Read entityBudgetSpent for proof.
     */
    public boolean entityPursuitHasBudget = false;

    /**
     * Stop DestroyBlockTask retreating while the block under its own feet is what blocks the aim.
     *
     * <p>The self-floor branch counts the case where the reach ray is stopped by the bot's own
     * floor -- canClear rightly refuses to dig it, since that drops the bot -- and the note beside
     * it says the answer is to MOVE so the line opens. Nothing moved. Worse, the task then holds
     * MOVE_BACK whenever the target is within two blocks, and for a target BELOW the bot that is
     * exactly the wrong direction: retreating keeps the floor between the eyes and the block.
     *
     * <p>Not a rare corner. One twenty-minute run reads dbBlocked=617/0/0 -- six hundred and
     * seventeen self-floor refusals -- alongside noReach=1357. That frequency is the point: the
     * rare-scenario fixes of the last few passes each needed hours to measure and returned "not
     * established", because their case appeared in one run out of six to twenty.
     *
     * <p>GATE: mine_stone, mine_coal, mine_diamond and goto_then_mine are the mining courses and
     * must not move; all four suites run before shipping. Read dbNoRetreat for proof it fires.
     *
     * <h2>MEASURED, FIRES OFTEN, AND DOES NOT PAY (2026-08-23)</h2>
     *
     * <pre>
     *   dbNoRetreat=666  in a twelve-minute run, with dbBlocked=256/0/2 -- a common path, as hoped
     *   mine_coal        control 2/3   fix 2/3
     *   mine_diamond     control 3/3   fix 2/3
     * </pre>
     *
     * <p>So it executes, and it executes constantly, and the courses are no better for it -- if
     * anything a shade worse, inside the noise at three pairs. That is a cleaner negative than most
     * of today's: the branch provably ran, which several others never did.
     *
     * <p>The diagnosis it rests on is unaffected and still worth someone's time: the reach ray IS
     * stopped by the bot's own floor six hundred times a run, and retreating from a target below
     * you cannot help. Not retreating simply is not enough on its own.
     */
    public boolean noRetreatWhenOwnFloorBlocks = false;

    /**
     * Step TOWARD a target that is below when the bot's own floor blocks the aim.
     *
     * <p>noRetreatWhenOwnFloorBlocks does half the job and measures accordingly: it fires 666 times
     * in a twelve-minute run and buys nothing (mine_coal 2/3 against 2/3, mine_diamond 2/3 against
     * 3/3). Standing still leaves the floor where it was.
     *
     * <p>The self-floor note in DestroyBlockTask has said the answer since it was written: MOVE so
     * the line opens. For a target BELOW the bot that means standing OVER it, from where the look
     * is straight down and the floor is behind. This aims and holds forward for the tick; the
     * mining branch takes over the instant the ray lands.
     *
     * <p>Requires noRetreatWhenOwnFloorBlocks, which is what detects the geometry.
     *
     * <p>GATE: the four mining courses, and all four suites before shipping. Read dbStepOver.
     *
     * <h2>MEASURED: NO DIFFERENCE (2026-08-23)</h2>
     *
     * <pre>
     *   mine_coal      control 3/3   fix 2/3
     *   mine_diamond   control 1/3   fix 2/3
     *   total          4/6           4/6
     * </pre>
     *
     * <p>Neither better nor worse. Together with noRetreatWhenOwnFloorBlocks -- which fires 666
     * times a run and also changes nothing -- that closes the self-floor line of attack for now:
     * the geometry is real and common, and neither refusing to retreat nor stepping over it moves
     * a course.
     *
     * <p>What has NOT been tried is the third reading: that the aim is fine and the target is
     * simply the wrong block. dbTargetAir and dbBestDist are the numbers for that.
     */
    public boolean stepOverWhenOwnFloorBlocks = false;

    /**
     * Stop TimeoutWanderTask taking MOVE_FORWARD out of BlockPathWalker's hands.
     *
     * <p>That task releases SNEAK, MOVE_BACK and MOVE_FORWARD whenever Nav.isPathing(), guarded by
     * a check for the close walk to an ITEM -- and by nothing else. BlockPathWalker drives almost
     * everything else the bot does, and it was never asked about.
     *
     * <p>Named by the instrument rather than guessed, after the user found the defect by watching a
     * recording:
     *
     * <pre>
     *   TimeoutWanderTask:238 x1290   DestroyBlockTask:594 x24   DestroyBlockTask:686 x8
     * </pre>
     *
     * <p>Twelve hundred and ninety steals in ten minutes against thirty-two from the miner. This is
     * the shuffling a viewer sees. It stayed invisible through a whole session of counter-reading
     * because the thief instrument only watched the close walk, so the dominant case could not
     * appear in it by construction.
     *
     * <p>GATE: wander_recovery is the course that exists for this task and must not move; nav and
     * craft must not move. Proof it ran: forwardStealers() should lose the TimeoutWanderTask entry
     * almost entirely.
     *
     * <h2>SHIPPED ON (2026-08-23)</h2>
     *
     * The thief list loses the entry entirely: TimeoutWanderTask:238 x1290 before, absent after,
     * and the run that measured it reached STONE TOOLS at 66.3s -- the fastest of the series.
     * craft 22/22 with wander_recovery among them, nav 14/14 (one INVALID re-run 3/3). The same
     * guard covers DestroyBlockTask:407, which the instrument named next at x220.
     */
    public boolean wanderKeepsWalkerKeys = true;

    /**
     * Let DestroyBlockTask give up on a block it is MOVING near but never APPROACHING.
     *
     * <p>The task resets its progress checker when the distance improves -- correct -- but the only
     * thing that can DECLARE failure is MovementProgressChecker, which asks whether the body moved
     * 0.1 blocks in six seconds. A bot circling a target satisfies that for ever, so the
     * approach-based reset never gets to matter and the block is never given up on.
     *
     * <p>Measured on the playthrough: dbTick=5791 with dbNearTick=0 (never once inside four blocks
     * of the target) and dbUnreachMove=0 (the checker never called it stuck). The run ends inside
     * this task. Same shape mine_coal showed from the other side: 482 close-walk ticks, 286 with
     * movement, thirteen that closed ground.
     *
     * <p>Window is three times the checker's own, deliberately generous: this file records that
     * over-eager giving-up cost 21 blacklistings in eight minutes with the bot touring eighteen
     * good trees and felling none.
     *
     * <p>GATE: the playthrough. Regression watch: chop_tree and chop_canopy, the courses that
     * blacklisting hurt last time, plus mine_coal -- ALL PASS with this on.
     *
     * <p>⛔ MEASURED AND REFUTED. Twelve playthrough runs, interleaved, pins verified:
     * <pre>
     *   ON    4/6 PASS   mean rungs 1.8   fired in 5 of 6 runs
     *   OFF   4/6 PASS   mean rungs 1.7   fired in 0 of 6 runs
     * </pre>
     * The condition is live -- it fires in five runs out of six and never once in the control, so
     * the arms genuinely differ -- and the outcome does not move. The defect it names is real
     * (dbTick=5791 with dbNearTick=0 and dbUnreachMove=0: thousands of ticks, never within four
     * blocks, never called stuck) and fixing it changes nothing about how far the run gets.
     *
     * <p>Stays OFF. The mechanism and its refutation both belong here, because 'moving is not
     * approaching' is measured in two places now and remains worth knowing even though acting on
     * it here buys nothing.
     */
    public boolean breakNeedsApproach = false;

    /**
     * Approach a block to be BROKEN by getting within reach of it, not by standing inside it.
     *
     * <p>⛔ DestroyBlockTask navigates with GetToBlockTask, which goals to AltoGoal.block(pos), and
     * that goal is reached only when the bot OCCUPIES the cell:
     * {@code at.getX()==pos.getX() && at.getY()==pos.getY() && at.getZ()==pos.getZ()}. While the
     * block is solid that cannot happen, so arrival is never reported, the route is planned into an
     * occupied cell, and the bot circles it.
     *
     * <p>MEASURED, and it is the sharpest split found on the playthrough: closest approach to the
     * target separates PASS from FAIL 6/6 with no overlap -- every passing run inside 1.3 blocks,
     * no failing run ever inside 4.4, i.e. never inside the 4.5 reach. Fifteen of the eighteen
     * zero-rung runs ended on this leaf at full health. dbTick=5791 with dbNearTick=0 says it from
     * the other side: thousands of ticks, never within four blocks.
     *
     * <p>Range 3 keeps the body inside the 4.5 reach with room to stand, and matches the distSq<=16
     * that this file's own near-accounting has always used.
     *
     * <p>GATE: the four courses whose whole job is breaking blocks -- chop_tree, chop_canopy,
     * mine_coal, mine_stone -- ALL PASS with this on. Then the playthrough on arrival rate.
     *
     * <p>⛔ MEASURED NEUTRAL AT n=8, and my own framing above needed correcting to explain why.
     * <pre>
     *   ON    arrival median 20%   0 0 7 9 31 54 79 86    6/8 PASS   mean rungs 1.5
     *   OFF   arrival median 16%   0 0 12 15 16 23 39 50  7/8 PASS   mean rungs 1.4
     * </pre>
     * A lead at n=4 (16% vs 8%) did not survive doubling -- the third time that has happened today.
     *
     * <p>THE CORRECTION: the occupy-the-cell goal is not IMPOSSIBLE, it is unreachable UNTIL THE
     * BLOCK IS BROKEN, and the block gets broken whenever the route happens to carry the bot within
     * reach on its way. So the old goal arrives by side effect often enough, which is exactly why
     * replacing it measures neutral rather than transformative. The defect is real -- a goal that
     * cannot be satisfied at the moment it is set is wrong, and it is why arrival looks accidental
     * -- but it is not the thing capping the playthrough.
     *
     * <p>Stays OFF: neutral on the target, one PASS worse on a thin sample, and this project does
     * not ship on principle without a number.
     */
    public boolean breakGoalIsReach = false;

    /**
     * A movement gives up when the thing in its way can never be aimed at.
     *
     * <p>⛔ Movement.prepared() sets {@code somethingInTheWay = true} and then returns false on
     * BOTH paths, so the {@code if (somethingInTheWay) -> UNREACHABLE} after the loop is dead code.
     * A movement therefore cannot report UNREACHABLE for an obstruction it cannot clear: it aims
     * at the block centre, holds CLICK_LEFT at something the crosshair does not land on, and stays
     * PREPPING for ever. In PREPPING every subclass returns before its own logic, so the body is
     * held without a single movement key.
     *
     * <p>That is the measured zero-rung run: 44 qNoMove per failing run against 0 per scoring one,
     * body inside a seven-block patch at ONE altitude, empty inventory after five minutes, and a
     * captured scene reading Descend/PREPPING/idx0of4 with fwd:n on open ground.
     *
     * <p>Only the BLIND case changes -- mining a block we can see is untouched. Three seconds is
     * past any honest swing and under MovementQueue's six-second chain timeout, so this speaks
     * first and the planner gets to route around instead of returning the same route.
     *
     * <p>GATE: nav_break and nav_wall2 (the courses whose whole job is breaking a way through),
     * plus the three baselines, then the playthrough on arrival rate. Proof it ran: blindPrepGaveUp.
     */
    public boolean prepFailsWhenBlind = false;

    /**
     * The bow gives the camera back once the target is inside melee reach.
     *
     * <p>BowShooter.isAimCritical() answered "yes, whenever I am active", with no notion of range,
     * and CombatController hands the aim over on that answer. The combat file's own note already
     * named the consequence: "the bot is not out-fought, it is out-TICKED: it spends the run
     * standing still to shoot while an opponent that never stops walks in with the initiative."
     *
     * <p>MEASURED on allround -- the ONE gate the pvp suite fails, kills=12 against deaths=16 with
     * every other criterion on the course green. Both sides run the same controller and differ in
     * exactly two counters:
     * <pre>
     *   bot     aim: enemy=504 reposition=1  bowYield=61  reachMean=4.25  swings passed 72/566
     *   victim  aim: enemy=586 reposition=48 bowYield=0   reachMean=4.22  swings passed 75/636
     * </pre>
     * Same distance, same aim angle (77.6 against 78.9, both far past the 40 threshold), and the
     * side that never hands its camera to a bow is the side that wins the exchange.
     *
     * <p>Threshold is AttackTiming.reach() = 3.0, the same distance the swing gate judges against,
     * not a number invented for this. BowShooter already has a "too close" idea but it only
     * applies while a flee order is live, which is not this case.
     *
     * <p>⛔ MEASURED NEUTRAL, AND THE READING THAT MOTIVATED IT WAS A MISREAD. Four interleaved
     * runs on allround, control arm clean at bowGaveBack=0:
     * <pre>
     *   OFF   kills=13 deaths=14   kills=12 deaths=17    gaveBack 0, 0
     *   ON    kills=13 deaths=16   kills=13 deaths=16    gaveBack 14, 8
     * </pre>
     * No improvement, marginally worse on deaths, and the course is red in all four runs of both
     * arms -- so the bow yield is not what makes allround fail.
     *
     * <p>⛔ AND THE PREMISE WAS WRONG IN THE WAY TriggerBot ALREADY WARNS ABOUT. I read
     * "angleMean 77.6 against a threshold of 40" as "the crosshair is 78 degrees off", and
     * "reachMean 4.25" as "it fights at 4.25 blocks". TriggerBot's own comment says every one of
     * those sums accumulates INSIDE ITS OWN FAILURE BRANCH, so they are means over REFUSED swings
     * and describe only the misses. The honest half is the counts: angle refused 5 swings of 566,
     * reach refused 300, cooldown 413. Aim is very nearly never the reason -- distance and
     * cooldown are. That comment records the same misreading happening three times in one earlier
     * session; this is the fourth.
     *
     * <p>Stays OFF. Kept, with its numbers, because the range-blind aim arbiter is still a real
     * thing and the next attempt should start from the counts rather than from the means.
     */
    public boolean bowYieldsInsideMelee = false;

    /**
     * A knockback simulation ends when the body lands back on the surface it left.
     *
     * <p>⛔ The existing exit asks {@code fallHeight} AT THE FLYING POINT: "stop once the body is
     * within a block of the ground". The instant that point clears a platform edge, fallHeight
     * there is the scan maximum, the test can never be satisfied, and the body flies the full
     * fifteen ticks. The exit is defeated by exactly the case it exists for.
     *
     * <p>MEASURED on allround, every DANGER_BATTLE firing:
     * <pre>
     *   danger=32  pred 30.0  true 1.4  fly 7.2
     *   danger=10  pred 30.0  true 1.0  fly 7.6
     *   danger=42  pred 30.0  true 1.5  fly 7.0
     * </pre>
     * The prediction saturates at the 30-block scan cap while the ground under the fighter is one
     * to one-and-a-half blocks, and the simulated body travels seven. Real knockback moves a player
     * two to three. The horizontal velocity is set once and decayed by AIR friction 0.91 every
     * tick, summing to roughly 0.8/(1-0.91) = six to nine blocks -- predicted from the arithmetic
     * before the measurement, and the measurement landed inside it.
     *
     * <p>The fix asks about the COLUMN the body is over at the height it left. Over a real ledge
     * that column is empty, the body keeps going, and the estimate reports the genuine fall -- so
     * this is not the caution being removed (removing it measured harmful twice: deaths 16 to 23
     * and 15 to 19). It is the caution being given a number it can act on.
     *
     * <p>GATE: edge_duel and narrow_bridge_duel -- the two courses fought over a real drop, where a
     * wrongly-shortened estimate would cost lives -- then allround, which is the one it is for.
     *
     * <p>⛔ NOT SHIPPED, AND THE FIRST VERDICT ON IT WAS OVERSTATED. I recorded "measured harmful"
     * on this, and the second batch withdrew it:
     * <pre>
     *   sweep 1 (n=3/arm)   ON 1/3 PASS   OFF 3/3 PASS
     *   sweep 2 (n=2/arm)   ON 2/2 PASS   OFF 2/2 PASS
     *   pooled              ON 3/5        OFF 5/5
     * </pre>
     * narrow_bridge_duel is FLAKY -- it went FAIL then PASS on its retry inside the full pvp suite
     * the same day -- so three runs of it cannot carry a verdict. Pooled it still leans against the
     * flag, and that is all that can be said. NOT established as harmful, NOT established as safe.
     *
     * <p>The concern remains the reason to leave it off: this file records that removing the
     * caution measured deaths 16->23 and 15->19, and landing the body early is a weaker form of the
     * same removal. A flag that leans the wrong way on the one course fought over a real drop does
     * not ship on a tie.
     *
     * <p>⭐ THE DEFECT IT WAS BUILT FOR IS STILL REAL AND STILL UNFIXED: the estimate saturates at
     * the 30-block scan cap on flat ground (pred 30.0 against 1.0-1.5 of true drop, body flying
     * 7.0-7.6 blocks against a real knockback of two to three). What is now also known is that the
     * obvious correction is not the answer. A second guess is NOT to be stacked on this one --
     * whatever comes next has to explain why the bridge case behaves differently, before it is
     * built.
     */
    public boolean kbLandsOnSurface = false;

    /**
     * The bow refuses to START a draw at an opponent already inside melee-preferred range.
     *
     * <p>A draw is about a second of standing still. The combat file names the cost directly: "the
     * bot is not out-fought, it is out-TICKED: it spends the run standing still to shoot while an
     * opponent that never stops walks in with the initiative."
     *
     * <p>MEASURED on allround -- the one pvp gate that fails, and it fails in EVERY run seen, six
     * of six, kills 12-13 against deaths 14-17. The bot books bowYield 25-54 a run; the victim
     * carries no bow, books zero, and wins.
     *
     * <p>DISTINCT FROM bowYieldsInsideMelee, which moved only the CAMERA and left the draw running,
     * and measured neutral. Handing the aim back does not give the second back. This refuses the
     * draw itself, before any state is disturbed, so a draw already in flight at long range is
     * untouched.
     *
     * <p>⛔ INERT AS WRITTEN, AND THE REASON WAS IN THE COURSE DESCRIPTION ALL ALONG. Four
     * interleaved runs on allround: declinedClosing read 0, 0, 2, 0 -- the refusal essentially
     * never fires, so both arms ran identical code and all four runs failed at kills=12 against
     * deaths 16-17.
     *
     * <p>allround's own description says the driver "shootArrowAt while the closing enemy is FAR,
     * punk once he is inside 10 blocks". The caller already stops shooting at ten; a refusal at
     * eight sits INSIDE that gate and can never trigger. Reading the course before choosing the
     * threshold would have caught it in a minute, and did not.
     *
     * <p>⭐ WHAT THAT SETTLES ANYWAY: the bot does NOT shoot at melee range on this course, so
     * "it stands still shooting while the opponent walks in" cannot be what loses the exchange in
     * the form I assumed. Any remaining cost of the bow is paid at range, while the enemy closes --
     * a question about TIME TO CONTACT against draw length, not about distance. That is the shape
     * a third attempt would have to take, and it needs the draw/approach timings measured first.
     */
    public boolean bowRefusesWhenClosing = false;

    /**
     * Count barren locks PER TARGET rather than as one streak on the last entity.
     *
     * <p>{@code tryPathToEntity} zeroes {@code barrenStreak} when the entity changes, which is
     * right for a genuinely new target and wrong for one the bot keeps returning to. mine_diamond
     * wants TWO diamonds: alternating between two drops it cannot reach wipes the streak on every
     * switch, so {@code MAX_BARREN_LOCKS} is never reached and navigation never refuses. Measured
     * lock=49/0/50 -- forty-nine barren locks, no refusal -- with the bot parked and the run lost.
     *
     * <p>This is the half that makes the other two add up: with the streak surviving alternation,
     * {@code entitySearchMustMove} releases an idle lock, the escalation actually converges, and
     * {@code entityWanderWhenNavRefuses} finally has a refusal to react to.
     *
     * <p>GATE (mechanism, readable at small n unlike this course pass rate): barren locks should
     * collapse from ~49 to single figures and the second field of {@code entityReleased} should
     * stop being zero.
     */
    public boolean barrenStreakPerEntity = true;

    /**
     * Collect a target that is already lying on the floor instead of first crafting the tool that
     * would be needed to MINE it.
     *
     * <p>MineAndCollectTask turns ResourceTask's pickup block off ("picking up is controlled by a
     * separate task here") and then returns SatisfyMiningRequirementTask before that separate task
     * can run. While the requirement is unmet the bot therefore has NO pickup path at all.
     *
     * <p>Measured on pickup_pit: RTGATE targets=[[diamond]] avoid=true dropped=true -- the tracker
     * held the diamond and the pickup block was skipped anyway -- with idrop=0/0/0/0 and scan=0
     * proving the separate task never ran, while the bot hunted wood on a stone arena for a whole
     * run to craft an iron pickaxe it did not need.
     *
     * <p>GATE: pickup_flat, pickup_side and pickup_pit, which fail today for this reason; the three
     * green nav baselines must not move. Proof it ran: MineAndCollectTask.toolGateSkipped.
     */
    public boolean collectDropsBeforeTools = true;

    /**
     * Do not put an ingredient back in the pack while a crafting slot is still waiting for it.
     *
     * <p>CraftGenericManuallyTask ends its tick by clearing the cursor whenever it does not stack
     * with the output. If the cursor holds an ingredient the grid still wants, that IS the carousel
     * the class documents: picked up, put back, picked up again.
     *
     * <p>Measured on a 14-minute playthrough that reached wood tools and then did nothing for ten
     * minutes: mc=3080/0/0/2/0 -- it FILLS three thousand times -- with ciReceive=25, mcFlight=73,
     * and CURSORBACK manualTail firing on the exact ingredient wanted (15x stick, 10x planks)
     * while MOVEMISMATCH read holding=planks want=[stick] on the same slot.
     *
     * <p>GATE: the playthrough ladder, plus craft 20/20 unchanged. Proof it ran: mcKept.
     */
    public boolean craftKeepWantedCursor = false;

    /**
     * Put what the cursor is ALREADY holding into the slot that wants it, before choosing another.
     *
     * <p>CraftGenericManuallyTask hands its mover to the first UNSATISFIED slot. When that slot's
     * ingredient is in the cursor -- picked up last tick, halfway to the grid --
     * {@code hasItemInventoryOnly} reads false for it, the guard continues past, and a LATER slot
     * gets the mover. That mover finds the wrong item held, puts it away, and the first slot is
     * empty again. The craft rides that round and round.
     *
     * <p>Measured on the stalled playthrough: mcFilled=3080 against ciReceive=25, MOVEMISMATCH
     * holding=planks want=[stick] on slot 4, CURSORBACK returning stick, planks and log by turns,
     * and mcInFlight -- the guard written for this -- catching only 73 of those 3080 ticks.
     *
     * <p>Distinct from {@link #craftKeepWantedCursor}, which held the cursor instead of moving the
     * target and measured no benefit on nine-minute windows. This moves the TARGET to the cursor.
     *
     * <p>⛔ MEASURED AND HARMFUL, KEEP IT OFF. Nine-minute windows, arms interleaved, three each:
     *
     * <pre>
     *   ARM A off   5 rungs / 5 rungs / 5 rungs    stone tools in ALL THREE
     *   ARM B on    1 rung  / 3 rungs / FAIL       stone tools in NONE
     * </pre>
     *
     * That is the clearest arm separation measured on this course all day, and it runs against the
     * fix. craft stayed 20/21 with it pinned on (the one red being goto_then_mine's own flake), so
     * it breaks nothing on the arena -- it simply makes the survival ladder worse. The reading:
     * forcing the held item into whichever slot wants it overrides the order the craft intended,
     * and a 3x3 recipe cares about which slot gets what.
     *
     * <p>The MECHANISM it was built from is still real and still unfixed -- mcFilled=3080 against
     * ciReceive=25 with MOVEMISMATCH holding=planks want=[stick] -- but neither holding the cursor
     * (craftKeepWantedCursor) nor moving the target (this) is the answer.
     */
    public boolean craftFinishMoveInFlight = false;

    /**
     * Test the DISTANCE before the chunk lookup when scanning for the nearest block.
     *
     * <p>Both orders return the same answer -- {@code nearest} only advances on a candidate that
     * passed every check, so anything at or beyond it cannot win either way -- so this is not a
     * behaviour switch. It exists so the SAVING can be measured, because a semantically identical
     * change has nothing to A/B against otherwise.
     *
     * <p>The old order did a world getBlockState per tracked position, per block type, per tick.
     * Measured on the playthrough stall corpus: scanAccepted 95k, 136k, 514k and 1,346,059 in
     * nine-minute runs, against 200-400 on a passing arena course. Reordering took the same runs to
     * 53k, 65k, 34k and 0. Whether that buys FRAMES on a world where frames are the binding
     * constraint is the question this flag exists to answer; delete it once the number exists.
     */
    // ⭐ EQUIVALENCE MEASURED, NOT ARGUED, AND THE ORDER IS BACK ON (2026-08-18).
    //
    // I first shipped this saying the answer "cannot change by construction". mine_coal then read
    // 1/5 against 3/4 for the old order, so it went off -- a proof that disagrees with the bench
    // is a proof with an unexamined assumption, and the candidate was that isValidTest is called
    // for EVERY position in one order and only for a new best in the other.
    //
    // scanEquivCheck settles it by computing BOTH orders and comparing the chosen block:
    //
    //     SCANDIFF = 0 across mine_coal, mine_stone and chop_tree, six runs
    //
    // The orders pick the same block every time. And mine_coal failed once in that same batch
    // WITH THE OLD ORDER in effect, so its failures are independent of this -- the 1/5 was that
    // course flaking, which it has done all day at a true rate around 70-80%.
    //
    // The saving is real: scanAccepted 95k/136k/514k/1,346,059 down to 53k/65k/34k/0 on the same
    // nine-minute runs. No fps gain is established (16/16/22 against 14/12/20 at three runs an
    // arm, overlapping), so what is claimed is the work removed and nothing more.
    public boolean scanCheapTestFirst = true;

    /**
     * Audit the scan reorder by computing BOTH orders and comparing the block chosen.
     *
     * <p>Exists because I shipped the reorder claiming the answer cannot change, and mine_coal then
     * read 1/5 against 3/4 for the old order. A mismatch here proves the orders differ and names the
     * block; zero across a full suite says that reading was noise. Expensive -- it walks the
     * candidates twice -- so it is a diagnostic, never a default.
     */
    public boolean scanEquivCheck = false;

    /**
     * A flee destination must be somewhere the bot can STAND, not a projected coordinate.
     *
     * <p>Upstream's GoalRunAway is a heuristic over the whole search space -- any cell far enough
     * from the danger satisfies it -- so the search finds a reachable one itself. The port collapsed
     * that to one computed point, and AltoGoal.Flee's own note admits the tension: fleeing is a
     * DIRECTION, but a drive steers at something. What it did not say is what happens when the
     * something is inside a wall.
     *
     * <p>Which is the ordinary case for the caller that matters. DestroyBlockTask flees the block it
     * has just mined and passes that block's Y, so a bot at the bottom of its pit is sent "three
     * blocks that way, at the depth I am digging" -- inside solid stone, with void beneath. The
     * search cannot reach it, burns its budget, restarts; and from a 1x1 shaft the only direction a
     * best-effort route can expand is UP.
     *
     * <p>Classified over 35 mine_stone runs: 16 clean passes, 13 TOWERED, 5 partial, 1 banned.
     * Every towered run scores exactly zero, so 13 of the 19 failures are this.
     *
     *
     * <p>Off by default. Mechanism gate: fleeSpot=relocated/none. Relocated must be non-zero or the
     * projected point was already standable and the premise is wrong.
     */
    public boolean fleePicksStandableSpot = false;

    /**
     * Only resume a goto after mining when a goto was actually REQUESTED.
     *
     * <p>{@code TungstenMod.TARGET} is the module-global goto destination and it is INITIALISED to
     * {@code (0.5, 10.0, 0.5)} -- a leftover from when the mod was driven by hand. It is written by
     * ;goto, the create-goal keybinding, follow-entity and a few py4j primitives, and by nothing
     * else. The altoclef task drive never writes it: it calls {@code FastNavigator.start(gp)}
     * directly. So through an entire altoclef-driven run TARGET holds y=10, seventy-one blocks above
     * the arena floor.
     *
     * <p>{@code PathExecutor.resumeGotoAfterMining} fires whenever a mining segment completes, so
     * EVERY task that breaks a block hands the navigator that constant. Traced three times,
     * identical each run:
     *
     * <pre>
     *   [Tungsten] Mining done - passage open
     *   MovementQueue: 9 movement(s) 0,-63,0 -> 0,-54,0 CLIMB+9 for goal=(0.5,10.0,0.5)
     * </pre>
     *
     * From the bottom of its own pit the only way toward y=10 is up, so the bot spends the
     * cobblestone it just mined building a tower and stands on it for the rest of the run.
     * Classified over 35 runs: 13 of the 19 failures end exactly like that, every one scoring zero.
     *
     * <p>Six mechanisms were proposed for that tower and five refuted, none of them this -- because
     * the goal was never printed beside the route. The flee goal being served at the same instant
     * reads {@code away=0.5,-60.0,-4.5}, which is perfectly sensible, and it was blamed twice.
     *
     * <h2>MEASURED (2026-08-14), interleaved, fps 27.8-29.8 on every run of both arms</h2>
     *
     * <pre>
     *   off   n=5   mean 4.80   pass 2/5   TOWERED 2/5   cobble = 0, 8, 0, 9, 7
     *   on    n=5   mean 9.00   pass 5/5   TOWERED 0/5   cobble = 9, 9, 9, 9, 9
     * </pre>
     *
     * The sigma (2.12) is the least interesting number there. The pre-registered MECHANISM gate --
     * a tower in the world afterwards, which has no spread at all -- went 2/5 to 0/5, and the arm
     * has ZERO variance: five runs, all nine. That is what removing a bimodal failure looks like,
     * as against the mean wobbling, and this course has already killed two series measured on its
     * mean (an inert flag once "moved" it by 6.25).
     *
     * <p>ON by default after the order-swapped replication.
     */
    public boolean gotoResumeNeedsRealTarget = true;

    /**
     * A 30-second navigation lock that got nowhere counts as a FAILURE, so the existing limit works.
     *
     * <p>{@code TungstenHelper} takes an exclusive 30s lock to path at something. While it holds,
     * {@code GetToEntityTask} returns null every tick after resetting its progress checker: it
     * drives nothing and cannot give up. When the lock expires, the very next tick sees no lock and
     * takes a FRESH thirty seconds. Nothing asks whether the last thirty accomplished anything.
     *
     * <p>That renewal is the countdown in every stall traced on this project -- {@code Tungsten
     * pathfinding (29s left)} ticking down and starting over. 90 seconds of a 120-second mine_stone
     * run; 160 seconds of daylight on the @gamer playthrough, on
     * {@code Mine And Collect: [[coal]]}, with pdEnter+0 dbTick+0 mqStart+0.
     *
     * <p>{@code MAX_FAIL_COUNT = 5} exists for exactly this and CANNOT FIRE: {@code failCount++}
     * appears only in a catch block, so it counts exceptions, and a search that honestly finds no
     * path is not an exception. The limit never sees the failure it was written for -- the same
     * shape as a gate whose awake half could never fail, which this repo has paid for three times.
     *
     * <p>With this on, a lock that expires without the bot getting at least half a block closer
     * increments that counter, and real progress resets it. After five barren locks tryPathTo
     * returns false, the caller stops being told "tungsten has it", and the give-up path it already
     * owns -- progress checker, wander, blacklist -- can finally run.
     *
     * <p>Off by default and UNMEASURED: the box is at ~600-750% from another project and the last
     * confirming window came back INVALID at 8 fps, so rule ZERO says it does not ship today.
     * Mechanism gate when it can be run: lock=barren/productive, which must show barren>0 on a
     * stalling course.
     */
    public boolean barrenLockCountsAsFailure = true;

    /**
     * Keep walking at a dropped item until it is TOUCHED, instead of stopping a block short.
     *
     * <p>{@code GetToEntityTask} stops driving as soon as {@code isInRange(entity, closeEnough)} is
     * true, and the default is 1.0. Collection is a physical collision, so stopping at one block
     * guarantees the collision never happens -- the bot parks on the rim and waits for something
     * that can only occur if it keeps walking.
     *
     * <p>Traced on mine_coal, the course written for the rung the playthrough dies on. Ore at
     * (14,-61,4); bot frozen at (14.79,-60.00,5.03), about 1.17 blocks from the drop in the hole it
     * had just mined, from t=78s to the end of the run. coal=0, and the tracker reported the drop
     * 2393 times ({@code drop=2438/2393}). No ban, no barren lock, {@code cb=0/0/0/0}. It could see
     * the coal throughout and had simply stopped being driven at it.
     *
     * <p>The opposite direction is already on record as tried and useless: raising the radius to
     * 1.75 changed nothing, and that note concluded "a bigger radius only makes the bot stop FURTHER
     * OUT and never touch the drop". Vanilla collects on box overlap, roughly a third of a block.
     *
     * <h2>⛔ REFUTED ON ITS FIRST A/B, AND THE HARM HAS A MECHANISM (2026-08-15)</h2>
     *
     * <pre>
     *   control (1.0)   coal 3, 3, 3, 3    4/4 pass
     *   fix     (0.1)   coal 1, 2, 3, 3    2/4 pass
     * </pre>
     *
     * Interleaved, same invocation. Tighter is WORSE, and the reason is in the same method the
     * diagnosis came from: that distance is not only a stop condition, it is what triggers
     * {@code TungstenHelper.stop()}. At 0.1 the bot essentially never reaches it, so it keeps
     * re-pathing at a target it is already standing on instead of holding still long enough for the
     * collision to happen. The stop was doing work I had read as pure obstruction.
     *
     * <p>So the 1.17-block park is NOT this. The bot was outside 1.0 and the walk branch should have
     * been firing; why it was not is still open, and the next pass starts there rather than here.
     *
     * <p>NOTE ON THE BASELINE: the control arm was 4/4 in this series and the course read 2/3 an
     * hour earlier. Its own rate is not established, so "red 1 in 3" was itself thin evidence.
     *
     * <p>Stays off. Gates were: mine_coal, red 1 run
     * in 3 today, and mine_diamond, whose recorded failure is the same shape ("closest approach
     * 1.35, 2.45 and 3.57 blocks, never collected, three ores of three").
     */
    public boolean pickupClosesToContact = false;

    /**
     * A refused search is not a started one: {@code tryPathTo} returns false instead of locking.
     *
     * <p>{@code PathFinder.find} opens with {@code if (active.get() || thread != null) return false}
     * -- it will not start while a previous search thread is alive, and {@code TungstenHelper.stop()}
     * raises the stop flag without joining that thread. So after every stop and every completed
     * search there is a window in which find() simply declines.
     *
     * <p>That return value was discarded. {@code tryPathTo} took a THIRTY-SECOND exclusive lock and
     * returned true regardless, so {@code GetToEntityTask} was told "tungsten has it", returned null
     * and drove nothing -- on behalf of a search that never started. Both of its walk branches are
     * guarded on this method returning true, so one false yes stops the bot dead for the lock.
     *
     * <p>Traced on mine_coal: the bot parked 1.17 blocks from a drop it could see -- the tracker
     * reported it 2393 times -- and never closed, with no ban, no barren lock, and nothing wrong
     * with the search itself.
     *
     * <p>Off by default. Mechanism gate: {@code lock}'s third field, findRefused, which must be
     * non-zero on a stalling run or the premise is wrong and the series says so before the outcome.
     *
     * <h2>MEASURED: ONE LIVE PAIR, MIXED -- NOT DEMONSTRATED (2026-08-24)</h2>
     *
     * <pre>
     *   pair 1   fix       lock 9/1/984    5 rungs, stone@179.0s
     *            control   lock 11/6/10    4 rungs, stone@154.3s
     *   pair 2   fix       lock 1/0/0      3 rungs          &lt;- mechanism did NOT fire
     *            control   lock 4/0/2      4 rungs, stone@177.5s
     * </pre>
     *
     * <p>The admission test this note demanded is satisfied -- findRefused is non-zero on stalling
     * runs (4, 10, 15, 2 across the day) -- so the premise stands. And in pair 1 the mechanism is
     * unmistakably live: 984 refusals honoured against 10, because an honest "no" makes the caller
     * ask again every tick instead of standing under a lock taken for a search that never started.
     *
     * <p>But pair 1 is MIXED: fewer barren locks (9 against 11) and fewer PRODUCTIVE ones (1
     * against 6), with a slower ladder. And pair 2 measured nothing at all -- findRefused reads 0
     * in its fixed arm, so by rule 4a1 that pair is void for this flag whatever its ladder did.
     *
     * <p>One informative pair, pointing both ways, is not a result. Stays OFF. What it needs is
     * more pairs in which findRefused is non-zero on BOTH arms -- which is a scheduling problem,
     * not a code one: the refusal window opens after a stop or a completed search, so a run that
     * never stalls never tests this.
     *
     * <p>Worth keeping precisely because the mechanism is real and rare: the trace it was written
     * from -- parked 1.17 blocks from a drop seen 2393 times -- is still the clearest single
     * failure this file records.
     */
    public boolean pathStartMustSucceed = false;
    /**
     * Do not replan a route that is still being walked, unless the target has actually moved.
     *
     * <p>⛔ MEASURED FIRST THIS TIME, AND IT OVERTURNS FOUR EARLIER ATTEMPTS. A per-tick anatomy of
     * what runs during a lock, over two playthroughs:
     *
     * <pre>
     *   lockAnat = total / search / exec / walk / queue / IDLE / MOVED
     *   run 1      2882 / 1744 / 1684 / 71 / 69 /   7 / 1185
     *   run 2      2264 / 1303 / 1311 /  0 /  5 /   5 /  700
     * </pre>
     *
     * IDLE is 7 ticks of 2882 and 5 of 2264 -- two tenths of one per cent. Something is running
     * essentially always, so "nobody drives the body" is FALSE, and that assumption is what the
     * camera yield, the lock drop, the walker yield and the give-up retry were all built on.
     *
     * <p>And the body MOVES on 41% and 31% of lock ticks. A barren lock is not a freeze. It is
     * motion that nets to zero -- which is exactly what the operator described from a recording
     * ("the bot twitches back and forth") and what {@code m0.0} in the barren geometry means: 41%
     * of ticks spent moving, ending where it started.
     *
     * <p>The search is active on 58-60% of those ticks, which is what continuous replanning looks
     * like. And the lock branch replans every {@code RETARGET_INTERVAL_MS} = 3 s, so a
     * thirty-second lock throws away and rebuilds its route TEN TIMES. Each new plan starts from
     * wherever the body has got to and sends it somewhere else; the executor walks a prefix of
     * each. Ten prefixes of ten different routes is a bot going back and forth.
     *
     * <p>Retargeting exists to chase a target that MOVES -- a drop that got kicked, an entity
     * walking away. When the target has not moved, replanning gains nothing and costs the route in
     * progress. So: skip it while a route is actually executing and the target is where it was.
     *
     * <p>Counted ALWAYS, acted on only when flagged, so the control arm can report how often this
     * fires -- the mistake this file has already paid for three times.
     *
     * <p>Read lockRetarget=done/skipped. GATE: playthrough for the locks, then nav, craft and pvp.
     *
    public boolean lockKeepsRouteWhileTargetStands = false;
     *
     * <pre>
     *   pair 1   fix       lockRetarget 43/9   lock 15/3/14   4 rungs, stone@110.7s
     *            control   lockRetarget 23/0   lock  7/1/4    4 rungs, NO stone tools
     *   pair 2   fix       lockRetarget  1/0   lock  0/0/0    3 rungs   &lt;- never fired
     *            control   lockRetarget  7/0   lock  4/1/1    3 rungs, stone@199.8s
     * </pre>
     *
     * <p>Clean exclusivity where it fired -- 9 skips against 0 -- and pair 2 is void for this flag
     * by rule 4a1, its fixed arm having skipped nothing. So one informative pair, and it points
     * both ways: more barren locks with the guard (15 against 7), but the ladder reached stone
     * tools while the control did not get there at all.
     *
     * <p>Stays OFF. One mixed pair is not a result, and this file has already paid for treating one
     * as though it were.
     *
     * <p>WHAT THE NUMBERS DO SUPPORT, for whoever takes this next: the skip rate is LOW -- 9 of the
     * 52 retarget opportunities in that run. The guard also requires {@code Nav.isExecutingRoute()},
     * and the anatomy says the executor holds only 38-58% of lock ticks, so by construction it can
     * never touch the rest. The search, meanwhile, is active on 58-72% of them. A stronger variant
     * skips the replan whenever the target STANDS, whatever is or is not executing, on the grounds
     * that a fresh search to an unchanged target from a slightly different spot is nearly the same
     * search. That is the next thing to try, and unlike four attempts before it, it has a measured
     * quantity behind it rather than a story.
     */
    public boolean lockKeepsRouteWhileTargetStands = true;

    /**
     * How far a target must move to justify throwing away the route being walked, in blocks.
     *
     * <p>⛔ 1.5 WAS TOO COARSE AND IT COST chase_terrain. The retarget interval is 3 seconds, and a
     * duel opponent circling or strafing can stay within 1.5 blocks of where it was three seconds
     * ago while never once standing still. The guard read that as "the target stands", kept a stale
     * route, and the course failed -- measured directly, control PASS against fix FAIL on a paired
     * run of that one course.
     *
     * <p>The playthrough case needs nothing like that much slack. A dropped item that has settled
     * moves EXACTLY zero, which is the whole reason replanning to it is pointless. So a threshold
     * just above sampling jitter keeps every bit of the benefit -- barren locks 5 against 19 across
     * two pairs -- while any entity that is actually moving replans every interval as before.
     */
    public double lockRetargetMoveBlocks = 0.35;
    /**
     * Skip the replan whenever the target STANDS, whatever is or is not executing.
     * <p>The strong form of lockKeepsRouteWhileTargetStands, and the reason for it is a number
     * rather than a story. That guard skipped 9 of 52 retarget opportunities, because it also
     * demanded {@code Nav.isExecutingRoute()} -- and the lock anatomy says the executor holds only
     * 38-58% of lock ticks, so by construction it could never touch the rest. The SEARCH is the
     * thing that is busy: 58-72% of those same ticks.
     * <p>A replan to a target that has not moved, from a position a metre or two along, is very
     * nearly the same search. Running it does not find a better route; it discards the one being
     * walked and starts the body over. Whether some other component happens to be executing at that
     * instant has no bearing on that.
     * <p>Read lockRetarget=done/skipped: this should push the skipped side far above 9, and if it
     * does not, the premise is wrong and the series says so before the outcome does.
     * <p>GATE: playthrough for the locks, then nav, craft and pvp.
     *
     * <pre>
     *   threshold 1.5    barren locks  5 against 19       chase_terrain FAILS
     *   threshold 0.35   barren locks 10 against  8       chase_terrain PASSES
     * </pre>
     *
     * <p>Both sweeps clean -- zero skips in every control arm, which took a third measurement to
     * achieve after a stale flag in the same or-condition quietly gave the control the behaviour it
     * was supposed to lack.
     *
     * <p>So the whole benefit comes from skipping aggressively enough to also strand a chase, and a
     * threshold tight enough to keep the chase green returns nothing. That is not a tuning problem.
     * "Has the target moved less than X" is simply the WRONG DISCRIMINATOR: it cannot tell a
     * settled item, which never moves at all, from an opponent circling inside the same radius,
     * which never stops.
     *
     * <p>The distinction that matters is the target's KIND, and it is absolute rather than
     * threshold-shaped -- see lockSkipsReplanForSettledDrops. Both of these stay OFF.
     */
    public boolean lockSkipsReplanWhileTargetStands = false;

    /**
     * Do not replan toward a DROPPED ITEM that is lying still. Living targets always replan.
     *
     * <p>The discriminator the threshold experiments were reaching for and could not express. A
     * dropped item that has settled moves exactly zero for ever, so a fresh search to it finds the
     * same route and only discards the one being walked -- ten times per lock, which the anatomy
     * showed is what the twitching is. A player or a mob is the opposite case: it may sit inside a
     * small radius while never standing still, which is precisely how a 1.5-block threshold lost
     * chase_terrain.
     *
     * <p>Kind, not distance. An ItemEntity that is on the ground and barely moving is a fixed
     * point; anything alive is not, whatever its instantaneous displacement says.
     *
     * <p>Read lockRetarget=done/skipped, and the gate is both halves: barren locks on the
     * playthrough AND chase_terrain, which is the course that caught the threshold version.
     *
     * <h2>MEASURED: FIRES HARD, GATE GREEN, LADDER LEANS POSITIVE (2026-08-24)</h2>
     *
     * <pre>
     *   pair 1   fix      lockRetarget 13/7   lock 6/1/17   4 rungs, stone@43.9s
     *            control  lockRetarget 14/0   lock 5/1/5    5 rungs, stone@178.5s
     *   pair 2   fix      lockRetarget  0/25  lock 9/1/7    5 rungs, stone@315.7s
     *            control  lockRetarget 19/0   lock 6/1/5    ONE rung, no stone tools
     * </pre>
     *
     * <p>Clean exclusivity -- zero skips in both controls -- and it fires hard where it applies: 25
     * of 25 replans skipped in one run, because every target there was a settled drop.
     *
     * <p>Stone tools reached in BOTH fixed runs against one of two controls, and the fastest run of
     * the four (43.9 s) is a fixed one. Barren locks go the other way, 15 against 11. Four runs on
     * this course is inside its own noise, so this is a direction and not a proof -- but the ladder
     * is the thing the playthrough is graded on and the lock count is a proxy for it.
     *
     * <p>GATE, and it is the relevant one: craft 14/14 with no failures, including every course
     * that picks a drop up -- mine_diamond, mine_coal, goto_then_mine, chop_tree. Those are the only
     * courses this can touch. chase_terrain, which killed both threshold versions, is untouched BY
     * CONSTRUCTION here: a player is not an ItemEntity, so a chase always replans.
     *
     * <p>Ships ON. It is the one form of this idea that is principled rather than tuned: the
     * property it tests -- a settled drop never moves again -- is true absolutely, not within some
     * radius that a circling opponent can hide inside.
     *
     *
     * <p>The verdict above shipped this ON after two pairs. A six-run sweep says that was
     * premature:
     *
     * <pre>
     *   pair 1   fix  lockRetarget 4/5   NOTHING reached (FAIL)  |  control 12/0  stone@89.2s
     *   pair 2   fix  lockRetarget 7/4   stone@21.5s             |  control 22/0  stone@313.5s
     *   pair 3   fix  lockRetarget 0/0   no stone tools          |  control 26/0  stone@426.8s
     * </pre>
     *
     * Pair 3 is void for this flag by rule 4a1 -- it never fired. Of the rest, stone tools were
     * reached by the fixed arm ONCE in three and by the control THREE times in three. Adding the
     * earlier sweep gives 3 of 5 against 4 of 5: a wash, or slightly against.
     *
     * <p>The fastest run of everything measured today is still a fixed one (21.5 s), and the gate
     * stays green -- craft 14/14, chases untouched by construction. So this neither helps nor
     * harms, measurably, and "principled" is not the same as "demonstrated". It goes OFF, and the
     * two pairs that convinced me stay written down next to the four that did not.
     *
     * <p>Shipping on two pairs of a course this repository has already measured at sevenfold spread
     * was the error, not the idea. The bar is pairs where the mechanism FIRES on both arms, and
     * enough of them to outvote the ground.
     */
    public boolean lockSkipsReplanForSettledDrops = false;

    /**
     * The stone toolset must not outrank ORE, which is what a flat priority against a
     * distance-scaled one guarantees today.
     *
     * <p>⛔ THE CEILING, AND IT IS ARITHMETIC. Two priorities of different KINDS are compared as if
     * they were the same number:
     *
     * <pre>
     *   ore        DistanceItemPriorityCalculator:  priority = (1 / distance) * 1050
     *   toolset    StaticItemPriorityCalculator:    priority = 520, flat
     * </pre>
     *
     * Measured on the bench: coal is visible on 841 of 847 samples and iron on 847 of 847, with the
     * NEAREST coal 2.5 blocks away and the nearest iron 4.6. Put those in:
     *
     * <pre>
     *   coal at 2.5 blocks   1050 / 2.5  =  420      loses to 520
     *   coal at 10 blocks    1050 / 10   =  105      loses to 520
     *   coal at 50 blocks    1050 / 50   =   21      loses to 520
     * </pre>
     *
     * <p>For ore to win it must be closer than 1050/520 = 2.02 blocks. So the "priority 1050" ore
     * task, twice the toolset's number on paper, essentially never runs -- and the bot spends 69%
     * of every post-stone-tools run collecting a stone axe, sword, shovel and hoe it does not need,
     * with iron ore four blocks away.
     *
     * <p>That is the whole ceiling. It explains the 69%, it explains ore being visible and ignored,
     * and it explains why the ladder stops at stone tools in run after run.
     *
     * <p>THE FIX IS NOT A BIGGER NUMBER FOR ORE. Scaling ore up keeps the two kinds incomparable
     * and just moves the crossover. The toolset is a CONVENIENCE and ore is PROGRESSION, so the
     * convenience yields whenever progression is actually available: the toolset's flat priority
     * drops to a value ore beats at a working radius. At 150, ore wins out to 7 blocks; at 60, out
     * to 17.
     *
     * <p>Read the post-rung task share, which is the number this is aimed at: 69% stone_axe today.
     * GATE: playthrough for the ladder, then craft -- the toolset lives there.
     *
     * <h2>MEASURED: IT REDIRECTS THE BOT EXACTLY AS INTENDED (2026-08-24)</h2>
     *
     * <p>The number this was aimed at is the share of post-rung samples spent on the toolset. The
     * historical baseline, over 25 runs, is 69% stone_axe. With the fix, in the one run of the
     * paired sweep that produced enough post-rung samples to read:
     *
     * <pre>
     *   run 3 (fix)   after stone tools:  stone_axe 0%,  COAL 80%  (8 of 10 samples)
     * </pre>
     *
     * The bot stopped collecting a stone hoe and went mining coal, which is the next rung. That is
     * the mechanism doing precisely what the arithmetic predicted once the flat 520 stopped
     * outranking (1 / distance) * 1050.
     *
     * <p>Ladder, two pairs: 4 rungs against 3, and 5 against 4 -- the fixed arm ahead in both, and
     * neither arm past stone tools inside a 14-minute run. Reaching stone tools at 156 s leaves
     * about ten minutes to smelt and mine, so the rung not moving here says little; the task share
     * says a great deal.
     *
     * <p>The other three runs produced 0, 9 and 1 post-rung samples, which is too few to read. That
     * is the honest limit of this measurement: one run's worth of evidence for the redirection,
     * and it agrees with the arithmetic exactly.
     */
    public boolean toolsetYieldsToOre = true;

    /** Flat priority for the stone toolset once ore may compete. Ore beats it out to 1050/this blocks. */
    public int toolsetPriorityWhenOreMatters = 150;
    /**
     * Do not sleep through the night when there is no bed and no way to get one cheaply.
     *
     * <p>⛔ FOUND BY THE FIRST 30-MINUTE RUN, AND IT IS THE SAME PRIORITY INVERSION AS THE TOOLSET.
     * Every measurement in this repository ran for 14 minutes, so night never arrived and this
     * never showed. In a 30-minute run:
     *
     * <pre>
     *   t= 551s  pos -320.7,109.2,-212.3  items=4  ladder EMPTY
     *   t=1094s  pos -320.1,108.0,-214.5  items=4  ladder EMPTY
     * </pre>
     *
     * Nine minutes, two blocks of movement, no rung. The task chain:
     *
     * <pre>
     *   Beating the game -&gt; Sleeping through night
     *     -&gt; Placing a bed nearby + resetting spawn point -&gt; Getting a bed first
     *     -&gt; Crafting bed: [white_bed, ...]
     * </pre>
     *
     * <p>Night falls, the sleep task takes over, it wants a bed, the bed wants three WOOL, and the
     * wool wants sheep -- which is the barren-lock case this file has been chasing all day, now
     * blocking the whole run instead of a corner of it.
     *
     * <p>The branch below it already asks the right question: getOneBedTask runs only if a bed is
     * actually VISIBLE and breakable. The sleep branch asks nothing -- it returns the sleep task on
     * any night, with no bed, no wool and no sheep in sight, and then blocks on crafting one from
     * scratch.
     *
     * <p>Sleeping is an OPTIMISATION: it skips the night. Spending the night failing to build the
     * thing that would let you skip it is worse than simply working through the dark. So sleep only
     * when a bed is already held or one is visible to take.
     *
     * <p>Read sleepDeclined to prove it fired. GATE: a 30-minute playthrough -- 14 minutes cannot
     * see this at all, which is the whole reason it survived a day of measurement.
     *
     * <h2>MEASURED: THE CEILING IS BROKEN (2026-08-24)</h2>
     *
     * <p>Two 30-minute runs on the shipped defaults, against two on the same window without this:
     *
     * <pre>
     *   baseline  run 1   nothing reached
     *   baseline  run 2   first craft, crafting, wood tools, wood      -- NO stone tools
     *
     *   fixed     run 1   wood, first craft, crafting, stone tools@44.3s, wood tools
     *                     sleepDeclined 434
     *   fixed     run 2   first craft@21.6s, crafting@21.6s, wood tools@43.9s,
     *                     stone tools@111.8s, wood@134.2s,
     *                     COAL@247.5s, FURNACE@406.7s, FOOD@905.4s
     *                     sleepDeclined 9915
     * </pre>
     *
     * <p>Coal, furnace and food are rungs this playthrough has NEVER reached. The ceiling that
     * stood through every measurement of this session -- stone tools, and then the run runs out --
     * is past.
     *
     * <p>It took two fixes together, and neither would have done it alone: toolsetYieldsToOre, so
     * ore stops losing to a flat 520 by arithmetic, and this one, so the night stops being spent
     * building the bed that would let the bot skip the night. The first frees the bot to go for
     * coal; the second lets it keep the time to do so.
     *
     * <p>NOT CLAIMED: that the run is finished, or that two runs settle a course measured at
     * sevenfold spread. What is claimed is that three rungs which had never appeared, appeared --
     * and that the mechanism behind them fired 434 and 9915 times.
     */
    /**
     * Planning is not progress, so it must not reset the stall watchdog.
     *
     * <p>⛔ THE BRANCH THAT FAILS IS THE BRANCH THAT SILENCES THE ALARM. The tungsten-primary drive
     * has a branch for "no block path yet -- kick the async search", and it ends with
     * {@code checker.reset()} on every tick it runs. That checker is the stall detector: it trips
     * after six seconds without the body covering a tenth of a block, and every recovery in the
     * task hangs off it. Resetting it there means a branch which by definition produced NO movement
     * tells the watchdog everything is fine.
     *
     * <p>Measured: pdPlan reads 8171/34 and 4305/17 -- eight thousand planning ticks against
     * thirty-four give-ups, roughly seven minutes of a run spent in a branch that moves nothing.
     * And with the legacy engine removed there is no longer a second engine to pick the goal up, so
     * the same spot now reproduces exactly:
     *
     * <pre>
     *   bot     1219.5, 104.1, -843.5   unchanged from t=854s to t=1432s
     *   target  1205,   104,   -846     14 blocks horizontally, SAME level
     *   state   "Tungsten (primary) planning..."
     * </pre>
     *
     * <p>Ten minutes planning a flat fourteen-block route, with the alarm being reset every tick of
     * it. The existing PLAN_GIVE_UP_MS bound does fire, but handing the tick back changes nothing
     * while the checker says the bot is fine: the next tick re-enters planning with a fresh timer.
     *
     * <p>Normal planning is untouched -- it takes a second or two and the checker's window is six.
     *
     * <p>Read pdPlanNoReset to prove it fired. GATE: nav and craft, then the 30-minute playthrough
     * at that coordinate.
     *
     * <h2>MEASURED: FIRES HARD, CHANGES NOTHING -- SHIPS OFF (2026-08-25)</h2>
     *
     * <pre>
     *   baseline  3 rungs              pdPlan 5098/28
     *   baseline  5 rungs, stone@796.6s   pdPlan 4381/27
     *   fixed     2 rungs              pdPlan 1088/8/36342
     *   fixed     5 rungs, stone@1260.9s  pdPlan 11726/56/1304
     * </pre>
     *
     * <p>The mechanism is unmistakably live -- 36342 and 1304 planning ticks that declined to reset
     * the watchdog. And the ladder does not move: 2 and 5 rungs against 3 and 5, with the fixed arm
     * reaching stone tools LATER (1260.9s against 796.6s). One fixed run even spent MORE ticks
     * planning than either baseline.
     *
     * <p>So the diagnosis was right about the code -- a branch that produces no movement really was
     * resetting the stall detector -- and letting the alarm ring changes nothing useful. The
     * recoveries it wakes (wander, replan) are apparently no better at that spot than sitting still
     * was, which points the next pass at the RECOVERY rather than the detector.
     *
     * <p>Stays OFF with its numbers. The reproducible case at 1219.5,104.1,-843.5 -> 1205,104,-846
     * is still open and still the right target: fourteen flat blocks that tungsten will not plan.
     */
    /**
     * The "already at the wall" shortcut may only fire when there is still a wall.
     *
     * <p>⛔ THIS IS THE STALL, AND IT IS A LOOP RATHER THAN A FAILURE TO PLAN. Reproduced on demand
     * at 1219.5,104.1,-843.5 heading for 1205,104,-846 -- fourteen flat blocks -- with
     * repro_stall.py, ninety seconds of zero movement per attempt:
     *
     * <pre>
     *   pdEnter=1921  pdWalking=0  mqStarted=0  bs=23/22
     *   primDrive robustPath present=false sz=0 fresh=true   (every tick)
     *   [Tungsten] Mining done -- passage open               (repeatedly, 636 ms each)
     *   "Failed! No block path"                              ZERO times
     * </pre>
     *
     * <p>So a path IS found, mining DOES run and finish, and the drive still sees nothing. The loop
     * closes in PathFinder.search: the branch for "already standing at the wall" hands the executor
     * an EMPTY physics path on purpose ("Skip the physics leg"), starts the break, clears
     * PathFinder.blockPath and returns, trusting the goto retry to drive the rest. The retry finds
     * another truncated path to the same wall and takes the same branch.
     *
     * <p>Nothing walks the body through the opening it just made. And "Mining done" arriving in
     * 636 ms is the tell: the blocks are already air, so the break completes instantly and the
     * shortcut has skipped the physics leg for no work at all.
     *
     * <p>The guard is unarguable: the shortcut trades the physics leg for a break, so it may only
     * be taken when a break is actually outstanding. With every planned block already air, fall
     * through and let the physics search route through the hole.
     *
     * <p>Read wallSkipRefused to prove it fired. GATE: the 90-second repro first, then nav, craft
     * and the playthrough.
     *
     * <h2>REFUTED BY ITS OWN COUNTER, IN NINETY SECONDS (2026-08-25)</h2>
     *
     * <pre>
     *   wallSkipRefused = 0        the guard never fired
     *   "Mining done"   = 135      in three minutes
     *   mqSteps = 0,  pdWalking = 0
     * </pre>
     *
     * <p>The premise was that the shortcut fires with every planned block already air, skipping the
     * physics leg for no work. It does not: wallSkipRefused is zero, so there is a REAL block to
     * break every single time.
     *
     * <p>What the repro did establish, and it is sharper than the guess: the bot completes 135
     * breaks in three minutes while taking ZERO steps. It is mining real blocks, one after another,
     * and never moving. So the question is not "does the shortcut skip work" but "why does mining
     * an actual obstacle never turn into a step" -- and that is a different mechanism, in the
     * executor rather than in this branch.
     *
     * <p>Off. Kept with its numbers because the loop it describes is real -- the branch does clear
     * the path and rely on a retry -- and only the "already air" half of the story was wrong.
     */
    public boolean wallShortcutNeedsAWall = false;
    public boolean planningIsNotProgress = false;
    public boolean sleepNeedsAnObtainableBed = true;
    /**
     * Wander with TUNGSTEN instead of handing the job to the legacy explore process.
     *
     * <p>⛔ THE LAST LIVE STARTER OF THE ENGINE WE ARE DELETING. TimeoutWanderTask calls
     * {@code getExploreProcess().explore(...)}, and the counters say what that costs:
     *
     * <pre>
     *   legacyDrive = path / overlap / explore
     *                 8558 /   18    / 9384
     *                 8079 /  2134   / 8603
     *                 7988 /   610   / 7952
     * </pre>
     *
     * The legacy engine executes a path for eight thousand ticks a run -- around seven minutes of a
     * fourteen-minute run -- and its explore process is active for nine thousand. Up to 2134 of
     * those ticks it is driving WHILE the tungsten executor is driving.
     *
     * <p>The note at that call refuses a one-line swap, and it is right to: replacing it with a
     * single GetToXZTask measured LESS ground covered (24.6 against 42.6). But that measured the
     * wrong thing. Ground covered by a wander is not the goal; the goal is a playthrough that is
     * not being steered by two engines at once.
     *
     * <p>So this is a real port rather than a substitution: pick a destination on the wander circle,
     * drive it with tungsten, and pick another when it is reached or refused -- which is what
     * exploration IS, rather than a call into a dead engine that then takes the body.
     *
     * <p>Read wanderTung=picked/driven and legacyDrive, which must fall. GATE: craft, where
     * wander_recovery lives, then the playthrough.
     */
    public boolean wanderUsesTungsten = true;



    /**
     * A temporary break/place ban does not outlive the job that learnt it.
     *
     * <p>The ban installed on a suspected land claim is TEMPORARY by design -- WorldSurvivalChain
     * clears it when a 60-second timer elapses. That timer is consulted only inside getPriority(),
     * and chains do not tick while the task runner is off (checklist RULE TWO). So a ban installed
     * near the end of a job is never cleared, and the next job inherits it: BotBehaviour keeps it
     * explicitly "outside push/pop stack" and applyState() re-adds it every time.
     *
     * <p>Measured on mine_coal, on a run that PASSED: {@code avoidSrc=0/0/1/0} -- one break-avoider
     * predicate present with ZERO registrations that run. It refused nothing then, because the
     * leaked ban was centred elsewhere. When it lands near the arena the same course reads
     * {@code cb=0/818/0/0} with {@code breakFail=0/0/0/0/0}: 818 blocks refused as unbreakable with
     * no break having failed at all -- a combination that is unexplainable until you know the ban is
     * inherited from an earlier run.
     *
     * <p>Same shape as RULE SEVEN's spectator leak, which survived a rebuild and two later runs and
     * looked exactly like a broken bot. Re-learning a real claim costs one failed break, which is
     * what it cost the first time.
     *
     * <p>Off by default. Mechanism gate: {@code avoidSrc}'s third field (predicates registered) must
     * be 0 at the START of every run; today it is intermittently 1 with a caller stamp of "-",
     * which is the leak's signature -- present but registered by nobody this run.
     */
    public boolean clearBansOnTaskEnd = false;

    /**
     * Look twice before believing a failed break is a land claim.
     *
     * <p>The claim test is a LATENCY RACE. {@code onBlockBroken} fires from a mixin on
     * {@code Block.onBreak}, which the client runs optimistically, and the verdict is taken half a
     * second later by asking whether the block is air. A slow round-trip, a chunk still loading or a
     * server resync are all indistinguishable from a claim through that test.
     *
     * <p>On the flat arena -- local server, 28 fps, no terrain to stream -- the block is always air
     * in time and {@code breakFail} reads 0/0/0/0 across every run. On the survival world the same
     * code read {@code breakFail=2}, and those two false claims banned 842,176 candidate blocks
     * ({@code cb=90/842176/5318/103}) out from under {@code Mine And Collect: [[coal]]} -- which is
     * exactly where the @gamer ladder stops.
     *
     * <p>So the first failure only re-arms the timer; the claim is made on the SECOND look. One
     * extra half-second on a rare path against a hundred-block ban on a common one.
     *
     * <p>Off by default. Mechanism gate: breakFail's fifth field, breakFailRetried -- it must be
     * non-zero where claims occur, and where it is non-zero, breakFailClaimed should fall.
     */
    public boolean claimNeedsSecondLook = false;

    /**
     * Lets the unstuck chain act on a bot that has a GOAL, no PATH and has not moved.
     *
     * <p>UnstuckChain skips any bot pressing no movement keys, on the sound reasoning that
     * standing still is usually deliberate -- crafting, a menu, waiting on a search -- and
     * shimmying through that breaks the action. But a stranded bot presses nothing either.
     *
     * <p>Measured on mine_stone: it digs a pit, climbs onto the arena wall at y=-57 and stands
     * there to the end of the run. In the n=20 baseline that is six ZEROS against eight runs of
     * 8-9 -- the entire remaining failure of the rung. All three of UnstuckChain's early guards
     * are true in that state, so the rescue can never fire.
     *
     * <p>This does not remove a guard; it separates two states the guard treats as one. Chests,
     * crafting, menus and combat are still excluded by the checks around it.
     *
     * <p>Off by default. Judged against the measured 8/20 baseline on mine_stone, with
     * strandedRescues as the mechanism gate and the craft ladder watched for regression.
     */
    public boolean unstuckWhenGoalButNoPath = false;

    /**
     * Drop the circle-strafe while a swing is READY and the target is OUT of reach (default off).
     *
     * <p>The bot holds a strafe key in about 55% of those ticks, together with forward and sprint,
     * so it travels DIAGONALLY: a 45-degree diagonal leaves ~70% of the speed pointing at the
     * target, closing at ~3.9 blocks/s against a skeleton that retreats at about 5. It loses ground
     * while holding every key correctly, which is why four earlier counters could see the waste
     * (nine ready ticks in ten spent out of reach) and none could explain it.
     *
     * <p>On mob_skeleton arrows landed is a function of FIGHT LENGTH — the skeleton fires once per
     * second of draw and never at the approach — so seconds added by orbiting are paid in arrows.
     *
     * <p>⛔ MEASURED, INTERLEAVED, 2026-08-12 — and it took the pre-declared branch:
     * <pre>
     *   strafe held during wasted ticks   A 30/17/21/16/29/37   B 0/0/0/0/4/6
     *   wasted ticks, total               A 231                 B 156
     *   ready near-share                  A 0.133               B 0.184
     *   arrows landed                     A 1.38                B 1.92   (-0.54, 1.48 sigma)
     * </pre>
     * The change DOES what it was written to do — the orbit is gone, the bot wastes a third fewer
     * ticks, and the near-share rises by 38% relative. The outcome moved the OTHER way.
     *
     * <p>So the last link of the chain is wrong, and the explanation is already in the earlier
     * measurements: the skeleton only shoots inside 4-6 blocks, where it hits 50-70%, against ~15%
     * at range. The orbit was holding the bot at the EDGE of that band. Removing it shortens the
     * fight and simultaneously drives the bot deeper under fire — the two effects oppose, and the
     * bench cannot tell them apart at this n.
     *
     * <p>KEPT OFF. The next experiment is not another movement flag: it is to measure exposure
     * directly — ticks spent inside 4-6 blocks, and shots fired per those ticks — because "shorter
     * fight" and "less exposure" have now been shown to be different quantities on this course.
     */
    public boolean combatCloseOverOrbit = false;

    /**
     * Run combat across the whole killing band, not only inside 4.5 blocks (default off).
     *
     * <p>MobDefenseChain ticks tungsten only when {@code getControllerExtras().inRange(toKill)},
     * which on flat ground is distance &lt; 4.5. A skeleton's band — measured — is 2.5 to 7.0 and it
     * releases from 4.3-6.3. So between 7.0 and 4.5 the bot is under fire with NO combat running:
     * no swing gate, no cooldown-aware approach. Measured: 129-253 ticks inside the band against
     * 62-89 where the gate is evaluated, which is exactly the ~105 ticks of slack over the 64-tick
     * floor, and the reason twelve hypotheses aimed at the other 40% all measured nothing.
     *
     * <p>This is NOT a distance tweak — it changes who drives the legs during the approach on every
     * mob course, so mob_melee and mob_trio are part of its acceptance, not an afterthought. Judge
     * on BAND TICKS first and arrows second: today proved those two can move in opposite directions.
     *
     * <p>⛔ MEASURED, INTERLEAVED, and it fails its OWN primary criterion:
     * <pre>
     *   band ticks   A 77 140 116 182 138 65  (mean ~120)   B 187 217 130 306 243 160 (mean ~207)
     *   arrows       A 1.96                                 B 1.13   (+0.83, 1.6 sigma)
     * </pre>
     * Exposure nearly DOUBLED. Engaging earlier does not shorten the time under fire, it lengthens
     * it. Arrows fell, but at 1.6 sigma that is under the bar, and by the rule written above band
     * ticks are judged first. KEPT OFF.
     *
     * <p>AND THE MODEL BEHIND THREE EXPERIMENTS IS NOW DEAD. "Fewer ticks in the band means fewer
     * arrows" has been contradicted in BOTH directions: combatCloseOverOrbit cut exposure by a
     * third and arrows got worse; this doubled exposure and arrows got better. Arrows landed is not
     * a function of time under fire. Note also arm B is remarkably even (min_hp 16,16,16,12,16,17)
     * against a ragged arm A (3 to 17) — whatever is really happening looks more like VARIANCE
     * being suppressed than exposure being traded, and that is the thread worth pulling next.
     */
    /**
     * Stop vanilla throttling this client to 10 fps for looking idle. Bench only.
     *
     * <p>The bot drives through the mod, not through the window, so InactivityFpsLimiter concludes
     * nobody has touched the keyboard and drops the client to exactly 10 fps -- under the bench's
     * 14 fps validity floor. That single condition was discarding about 40% of every series, which
     * is why a twelve-run arm had been costing twenty launches.
     *
     * <p>Off by default: an idle Minecraft on a human's machine SHOULD stop burning a core.
     *
     * <p>⛔ THE FLAG IS HERE BUT NOTHING READS IT YET, AND HERE IS WHY (2026-08-13). The mixin that
     * would honour it targets net.minecraft.client.option.InactivityFpsLimiter, which exists in the
     * 1.21.11 yarn mappings and NOT in the 1.21 base this source set compiles against -- the class
     * arrived after the base version. A first attempt failed to compile on exactly that, twice over
     * (the import and the annotation).
     *
     * <p>This repo handles such gaps with the inline preprocessor, e.g. {@code //#if MC >= 12111} as
     * used in CameraMixin and ChatReadMixin; there is no version-specific source directory to drop
     * the class into. So the implementation must guard the whole mixin behind that condition, and be
     * BUILT to verify it, rather than referenced by a compile-time import. Shipping it broken would
     * have cost more than leaving it undone.
     *
     * <p>The finding it serves is solid and independent of the implementation: the slow cluster is
     * EXACTLY 10.0 fps, which is vanilla's idle limit, not a load. See the note in compose.test.yml.
     */
    public boolean botFpsNoIdleThrottle = false;

    /**
     * Walk STRAIGHT at a target that is out of reach instead of orbiting it.
     *
     * <p>Measured, four runs: 69% of orbit ticks happen while the target is out of reach (138 far
     * against 63 near). A 45-degree line costs about 30% of closing speed, which on mob_skeleton is
     * roughly one whole skeleton shot -- the right size against a ~1.9 arrow gap, where the engage
     * band measured 0.88 at full power and stayed off.
     *
     * <p>Deliberately wider than {@code combatCloseOverOrbit}, which only suppressed the strafe once
     * a swing had matured. That is a minority of ticks, so it never touched the bulk of the orbit,
     * and its "made arrows worse" result was never evidence against this.
     *
     * <p>⛔ JUDGED AND REFUTED, 26 interleaved runs, 1 invalid (2026-08-13):
     *     A (orbit kept) n=13 mean 1.02 arrows, 3 passes, strafeFar averaged 31.5
     *     B (orbit off)  n=12 mean 1.19 arrows, 2 passes, strafeFar averaged 0.0
     *     difference -0.17 arrows at 0.58 sigma -- noise, and the sign is against the hypothesis.
     *
     * <p>THE VALUE OF THIS RESULT IS THAT THE MECHANISM WORKED AND THE EFFECT DID NOT FOLLOW.
     * strafeFar fell from 31.5 to exactly zero, so the straight approach really happened; the arrows
     * simply did not care. The false link was "diagonal costs time, time costs arrows". Closing
     * speed rose and the skeleton's shot count did not move.
     *
     * <p>Which fits a fact measured earlier and not carried to its conclusion: the skeleton fires
     * 2-3 times a fight and EVERY release happens at 4.3-6.3 blocks — already at close quarters.
     * Arrows are generated by time spent in the exchange, not by the length of the walk. Any future
     * hypothesis aimed at the approach is aimed at the wrong phase.
     */
    /**
     * Keep walking at a combat target until the SWING gate can fire, not until inRange says arrived.
     *
     * <p>Approach and strike are exclusive branches of one if in AbstractDoToEntityTask: while
     * inRange is false the body walks, and the moment it turns true the walk stops. inRange is 4.5
     * blocks; the swing gate's REACH is 3.0. The block and a half between them is time the bot
     * spends "arrived" and unable to hit -- 34.8 ticks a fight on mob_skeleton across 25 runs, the
     * largest swing refusal, ahead of cooldown at 19.3.
     *
     * <p>Combat entities only. Shearing and milking keep the old test: their interaction range is
     * not the sword's, and widening theirs would be a different change with different evidence.
     *
     * <p>⛔ IT DOES THE OPPOSITE OF ITS PURPOSE. Halfway through its own series (8 runs, 4 an arm):
     *     arrows 1.50 vs 1.50   band 106 -> 259   reach 26 -> 71
     * The change was meant to CUT the ticks spent in the band unable to hit; it tripled them.
     *
     * <p>The reason is in the branch it guards: onEntityInteract is what hands control to tungsten's
     * combat controller. Gating it at 3.0 instead of 4.5 takes the controller OUT of the very zone
     * where the last blocks have to be closed, and leaves the pathing task there instead --
     * repathing at a target that knockback keeps pushing away. The premise ("arrived and can-hit are
     * different distances") was right; the remedy removed the only component that can cross the gap.
     *
     * <p>So the 34.8 ticks are real and still unexplained-by-fix: whoever takes this next should
     * make the CONTROLLER close inside 4.5 rather than make the task wait until 3.0.
     *
     * <p>Off by default and staying off.
     */
    public boolean combatCloseToReach = false;

    public boolean combatApproachNoOrbit = false;

    /**
     * Lets the combat controller tick across the whole killing band (7.0 blocks) instead of only
     * inside {@code inRange}, ~4.5.
     *
     * <p>⛔ REFUTED AND CLOSED, 2026-08-13, after three series. The last was 40 interleaved
     * launches with 0 invalid, scored twice (summary and console log, agreeing exactly):
     * <pre>
     *     arm A (off)  n=20  mean 1.32 arrows  sd 0.79
     *     arm B (on)   n=20  mean 1.62 arrows  sd 0.93
     *     difference  -0.30   SE 0.27   1.10 sigma
     * </pre>
     * Under the pre-registered 2-sigma bar and in the WORSE direction. The two earlier series read
     * +0.83 and +0.88 at n=3-6 an arm; the sign REVERSED once n reached 20. Do not reopen this on
     * the strength of those two -- they are what a sub-threshold reading looks like when it is
     * noise, and this course has now produced four of them.
     *
     * <p>THE MECHANISM FIRED AND WAS THE WRONG ONE. Controller ticks went 55 -> 175 exactly as the
     * flag intends, and the bot ended up FURTHER from the target: reachMean 3.55 -> 4.56,
     * corr(controller ticks, reachMean) = +0.91, skeleton shots 2.3 -> 4.0. Every added tick landed
     * beyond the REACH+1.0 arbitration line in CombatController, where the legs belong to the
     * pursue walk and combat does not drive. So the flag supplied ticks that could not be used.
     *
     * <p>It is kept, off, because {@link #combatCloseOwnsBand} needs it: that one makes the ticks
     * drivable, and is inert without ticks to drive. They are tested as a PAIR, and that pair is a
     * different hypothesis from this flag alone -- which is refuted.
     */
    public boolean combatEngageBand = false;

    /**
     * Lets close-quarters combat own the legs across the killing band, instead of surrendering them
     * to the pursue walk at 4.0 blocks.
     *
     * <p>THE LINE THIS CHANGES. {@code CombatController.tick} hands movement to the safety stage
     * whenever {@code eyeToHitbox > REACH + 1.0}, and {@code closeQuarters()} -- the only code that
     * presses toward strike distance, sprints, and knows what REACH means -- runs solely in the
     * else. Above 4.0 blocks the legs therefore belong to a BFS path-follower walking at the block
     * the target occupied when the path was computed. It has no notion of strike distance and it is
     * chasing a square a retreating skeleton has already left.
     *
     * <p>MEASURED, engage-band series, n=7 an arm, counting (mdTung - cqEntry) per run:
     * <pre>
     *     flag off   safety won  5-10 of 32-83     9-20%   reachMean 3.36-3.69
     *     flag on    safety won  6-205 of 67-249   9-82%   reachMean 3.74-5.08
     * </pre>
     * corr(controller ticks, reachMean) = +0.91. Ticking the controller earlier made the bot stand
     * FURTHER OUT, because every added tick landed on the far side of the 4.0 test. reposition=0
     * and brake=0 throughout, so the claim beating combat to the legs was never a safety event --
     * it was the plain PURSUE walk, holding a claim it does not need.
     *
     * <p>This also explains three sub-threshold {@link #combatEngageBand} series (+0.83, +0.88 at
     * 1.90 sigma). That flag widens WHEN the controller ticks; this one decides WHETHER those ticks
     * can drive. On its own the first was inert by construction, and the two belong together.
     *
     * <p>SCOPED, because PURSUE is not useless -- it is the obstacle avoidance. It keeps the legs
     * unless there is line of sight and the target is inside the killing band, which is where a
     * straight approach is what closing means and a path around scenery is not. Genuine safety
     * stages (braking, repositioning, narrow terrain, escape) are untouched: this only declines the
     * claim of a plain chase.
     *
     * <p>It is the fix {@link #combatCloseToReach}'s javadoc asked for in as many words -- "make the
     * CONTROLLER close inside 4.5 rather than make the task wait until 3.0" -- once the reason the
     * controller could not was found. Off by default until a 40-launch interleaved series says
     * otherwise; the bar is 2 sigma on mean arrows, and mob_melee and mob_trio are re-run before it
     * ships, because this changes who drives the legs on every mob course.
     *
     * <p>⛔⛔ REFUTED, 2026-08-13, AND IT REFUTES MORE THAN ITSELF. 40 interleaved launches, 0
     * invalid, the pre-registered mechanism gate passed cleanly (cqTookFromPursue 0 in all 20 arm-A
     * runs, 8-213 in all 20 arm-B runs), so the flag did exactly what it was written to do:
     * <pre>
     *     arrows       A 0.88  B 1.23    -0.35, SE 0.32, 1.11 sigma   (under the bar, WORSE)
     *     passes       A 6/20  B 6/20
     *     ctl          A 52    B 166     combat drove 3x more
     *     cqTookFrom-  A 0     B 113     the pursue claim was declined this often
     *     reachMean    A 3.53  B 4.71    ...and the bot stood a FULL BLOCK further out
     *     inReachRate  A 0.375 B 0.143   fraction of control ticks inside 3.0 -- more than halved
     *     bandToSwing  A 53    B 84      longer before the first swing landed
     * </pre>
     *
     * <p>THE FINDING IS THE OPPOSITE OF THE HYPOTHESIS, and it is much larger than the arrows
     * result: {@code closeQuarters()} is a WORSE closer than the BFS pursue walk it was written to
     * displace. Every closing metric moved the wrong way when it took the legs. The premise -- that
     * the path-follower cannot close because it chases a vacated square -- is simply wrong: it
     * closes better than the range-band controller does, on this course, by a wide margin.
     *
     * <p>So the whole "the controller should own the approach" line is closed, as pre-registered.
     * Both this and {@link #combatEngageBand} stay off. What replaces it: the quantity that
     * actually predicts the result is the share of control ticks spent inside reach --
     * corr(inReachRate, arrows) = -0.40 over 40 runs, -0.52 within arm B -- and the approach that
     * maximises it is the pathfinder, not this.
     *
     * <p>⛔ AND ONE NUMBER FROM THIS SERIES MUST NOT BE QUOTED: corr(strafeFar, reachMean) = +0.93
     * looked like the circle-strafe diluting the approach. It is an identity.
     * {@code strafeFarTicks} counts strafe ticks taken BEYOND reach, so per control tick it is one
     * minus the in-reach rate -- corr(strafeRate, inReachRate) came out exactly -1.00, which is the
     * giveaway. It measures distance, not strafing, and cannot test the orbit at all.
     */
    public boolean combatCloseOwnsBand = false;

    /**
     * Hold sprint against a RETREATING SHOOTER until the swing, instead of dropping it at REACH.
     *
     * <p>RESTORED after being deleted, because the refutation judged the wrong quantity. It was
     * measured on ARROWS LANDED — -0.50 arrows, 1.77 sigma, SE 0.51 — a coarse outcome three steps
     * downstream, a handful of integers a run. The mechanism it targets now has its own counter:
     * TriggerBot's ready=far/near reads about 10:1, i.e. nine ticks in ten with a matured swing are
     * spent outside 3.0, because reach control computes its closing time from OUR speed while the
     * skeleton retreats at the same speed and the sprint is cut inside REACH.
     *
     * <p>⛔ CLOSED, 2026-08-12, by an INTERLEAVED pair (A,B,A,B — checklist rule 4r):
     * <pre>
     *   arm A (off)  n=4  arrows 2.12   ready near-share 0.165
     *   arm B (on)   n=6  arrows 1.83   ready near-share 0.161
     *   difference +0.29 arrows, SE 0.64  ->  0.46 sigma
     * </pre>
     * Nothing, on EITHER measure. The ratio it was restored to move did not move at all, so the
     * flag does not even do the thing it was written to do. Dead for good, by its own pre-declared
     * test.
     *
     * <p>KEEP IT OFF AND KEEP THIS HISTORY: the same flag read -0.60, +1.92 (3.18 sigma, which
     * this repo's own bar calls real) and -0.31 across three BLOCKED pairs before the interleaved
     * one settled it at 0.46. Blocked arms would have shipped a finding here.
     */
    public boolean combatHoldContactOnShooter = false;
    /** Allow sprint-jumping during follow (BFS walker + direct sprint).
     *  If false, only walks (no jumps) — safer but slower. */
    public boolean followJumpingEnabled = true;

    /** EXPERIMENTAL (#1.6.1): generate block-space neighbours via the tungsten-native
     *  SmartMoves (Traverse/Ascend/Descend/Parkour) instead of the blind r=8 scan.
     *  Fewer, already-valid neighbours -> the search routes stepped/gap terrain within
     *  its node budget.
     *
     *  STAYS OFF BY DEFAULT, and now for a MEASURED reason rather than an old caution. Turning it
     *  on shipped-wide failed nav_water 3 runs out of 3, each ending at exactly final_dist=25.5
     *  with 11-12 client freezes -- the same distance every time, i.e. the search never routes the
     *  crossing at all rather than trying and drifting. The mechanism is in SmartMoves.generate:
     *  it emits walk, jump-up, descend and parkour, and NOTHING else. Water is not passable to any
     *  of them, so replacing the blind r=8 scan with these neighbours removes the bot's ability to
     *  swim; the freezes are the search spending its whole budget every replan and finding nothing.
     *  (Break and place survive the switch because they are separate hooks, not neighbours, which
     *  is why nav_break and nav_bridge pass either way.)
     *
     *  Making this the default therefore needs SmartMoves to cover what the blind scan covers --
     *  water first. Until then the pairing "primary + smartMoves" that setTungstenPathing turns on
     *  is a deliberate agent lever, not the shipped configuration.
     *
     *  ⛔ THE MECHANISM ABOVE IS OUT OF DATE, CHECKED BY READING THE SOURCE 2026-08-08.
     *  "It emits walk, jump-up, descend and parkour, and NOTHING else" is no longer true:
     *  SmartMoves.generate has WATER MOVES -- entering from the bank (the edge whose absence the
     *  note describes, "the search stops at the shore"), strokes once wet, and vertical up/down --
     *  with a waterMoves counter published as smWater. So the stated reason this flag is off has
     *  been fixed since the reason was written.
     *
     *  ...AND THE DEFAULT STILL DOES NOT MOVE, NOW FOR A CURRENT MEASUREMENT RATHER THAN A STALE
     *  ONE. Ran the A/B back to back on the same host, minutes apart:
     *
     *      smartMoves OFF   PASS   reached goal 12.9 s, final_dist=0.9, 0 freezes    8.0 fps
     *      smartMoves ON    FAIL   final_dist=25.5, 9 freezes                        7.5 fps
     *
     *  final_dist=25.5 is the SAME failure distance recorded above from before the water moves
     *  existed. Adding them changed the outcome not at all.
     *
     *  AND THE COUNTER RULES OUT THE OBVIOUS EXPLANATION. I predicted smWater would read zero --
     *  edges present but never generated for this course. It reads 3810. The swim moves are being
     *  emitted in bulk and the search still cannot produce a route the bot follows, so the fault is
     *  neither "water is not modelled" nor "the edges are never offered". Both of my candidates
     *  were wrong, which is exactly what the counter was added to settle.
     *
     *  Next investigation starts there: 9 freezes with 3810 water moves generated says the search
     *  is doing work and throwing it away. Compare against the r=8 blind scan on the SAME course --
     *  it passes in 12.9 s -- rather than reasoning about the move set again.
     *
     *  Reproduce with (the bench verifies the flag really applied before running):
     *
     *      python deploy/runner/run_suite.py nav --only nav_water --pin smartMoves=true */
    public boolean smartMoves = false;

    /**
     * Blocks -> ticks for the BLOCK-SPACE search's heuristic (BlockSpacePathFinder).
     *
     * <p>Every edge in that search is priced in TICKS (ActionCosts: a walk step is 4.633),
     * while the heuristic measures BLOCKS. f = g + h only means something if both halves use
     * one unit, so the heuristic is multiplied by this. The number decides the search's whole
     * character: below the walk cost A* investigates alternatives and is optimal but broad;
     * above it, upstream's own note (baritone Settings.java:406-409) is that it "will result in
     * it going straight at its goal, and not investigating alternatives" — fast and suboptimal.
     *
     * <p>WHY IT IS A KNOB. Before C5.21 this search had no accumulated g at all (f was h + 1),
     * i.e. it ran greedy — the limit of this scale going to infinity. The first C5.21 attempt
     * made the costs live and pinned the scale at 4.633 by argument, not measurement: nav went
     * 12/12 -> 8/12 (nav_steep, nav_gaps, nav_slime red, nav_break lost to a death). The scale
     * was bundled with six other fixes, so that run cannot say which one cost the courses.
     * Default is upstream's costHeuristic (baritone Settings.java:413). Pin it per run:
     * {@code run_suite.py --pin searchHeuristicScale=<x>}.
     */
    public double searchHeuristicScale = 3.563;

    // ---- block breaking ----

    /** Allow the block-space pathfinder to plan breaking through breakable walls. */
    public boolean allowBreak = true;

    /** Multiplier on the mining-time cost of planned breaks (higher = prefer detours). */
    public double breakCostMultiplier = 1.0;

    /** Scales the cost of bridging with a placed block. Raise it to make the bot prefer
     *  going around; lower it to make it build more readily. */
    public double placeCostMultiplier = 1.0;

    /** Block ids the pathfinder must NEVER mine (e.g. "minecraft:diamond_block").
     *  Blocks with block entities (chests, spawners, furnaces) are always denied. */
    public java.util.List<String> breakDenyBlocks = new java.util.ArrayList<>();

    /** No-mining zones: [x1,y1,z1,x2,y2,z2] boxes (inclusive, any corner order). */
    public java.util.List<int[]> breakDenyZones = new java.util.ArrayList<>();

    /** Allow the mod to place blocks at all (bridge/build/fill/schematic). */
    public boolean allowPlace = true;

    /** Let the block-space pathfinder plan BRIDGING as a first-class move (place a floor
     *  across a gap as part of the route — the mirror of allowBreak's break-through).
     *  Default OFF: opt-in, and altoclef enables it only when the bot has a placeable
     *  block, so parkour/walk routing without blocks is completely unaffected. */
    public boolean planPlaceMoves = true;   // shipping placement on — see C5.5

    /** Hand a slime pad to {@link kaptainwutax.tungsten.task.SlimeBounceTask} — one manoeuvre
     *  that holds heading and sprint across the whole bounce chain, instead of the walker
     *  re-deciding at every waypoint.
     *
     *  <p>Default OFF because it is UNFINISHED, and the numbers say so plainly: the task is
     *  verified to run (15 starts, 7 bounces counted over py4j in one run) but its policy —
     *  constant full sprint at the far target — drives the bot off the pad, 5 to 7 void
     *  deaths per run against 8.6 blocks short and zero deaths with it off. The architecture
     *  is right and the executor stays; the throttle policy across a chain is the open work.
     *  Turn it on to work on that: {@code ;settings slimeCrossing true}. */
    public boolean slimeCrossing = false;

    /** Raise the shield while the attack cooldown recharges.
     *
     *  <p>Default OFF because it MEASURES WORSE, and by a lot. The idea is sound on paper —
     *  blocking and attacking are mutually exclusive in vanilla, so the recharge window looks
     *  free — but on the stand it collapses the offence: melee went from 15-19 landed swings
     *  per fight to ONE in two runs of three, and the trade from 4:5 to 0:5 and 0:6. Holding
     *  the use key evidently costs the swing that follows it. The kit now carries a shield so
     *  this is testable at all; fix the timing, prove it on melee_basic, then turn it on. */
    public boolean combatShieldEnabled = false;

    /**
     * Does Nav's mid-hop ground test ask for a COLLISION SHAPE instead of merely "not air"?
     *
     * <p>ON is the shipped behaviour. Pin it false to get the old !isAir() test back for an A/B.
     *
     * <p>The defect it addresses is real: {@code !isAir()} counts LAVA, water, tall grass, torches
     * and flowers as something to land on, so a bot falling toward lava two blocks down reads
     * "ground is close" and {@code isSafeToCancel} returns SAFE -- permitting the interruption of
     * exactly the fall the guard exists to protect. Fluids and decoration have empty collision
     * shapes, so one test covers all of it, and it is the idiom the trigger's line-of-sight raycast
     * already uses ("COLLIDERS only -- tall grass has no collision shape").
     *
     * <p>A FLAG BECAUSE THE FIRST ATTEMPT COULD NOT BE JUDGED. Built and baselined it read nav
     * 12/12 (nav_hazard PASS) and craft 12/12 (escape_lava PASS), but mob went 3/4 -> 2/4 and a
     * follow-up mob_trio read 0/6 -- which is INSIDE mob_trio's known range (it read 0/6 on an
     * unchanged build earlier the same day) and therefore cannot be told apart from a regression
     * across builds. Settle it in ONE session instead:
     *     run_suite.py mob --only mob_trio --repeat 20
     *     run_suite.py mob --only mob_trio --repeat 20 --pin navGroundCollisionCheck=true
     * Keep it only if the pinned arm is no worse; this predicate gates about ten callers.
     */
    public boolean navGroundCollisionCheck = true;

    /** No-placing zones: [x1,y1,z1,x2,y2,z2] boxes (inclusive, any corner order).
     *  Protected areas (claims/privates) — the mod never places here. Paired with
     *  breakDenyZones so markProtectedArea can lock both mining and building. */
    public java.util.List<int[]> placeDenyZones = new java.util.ArrayList<>();

    // ---- combat settings ----

    /** Enable trigger bot (auto-click when crosshair is on target). */
    public boolean combatTriggerBotEnabled = true;

    /** Enable auto-rotation toward target in combat. */
    public boolean combatRotatesEnabled = true;

    /** Enable combat movement (legs: sprint-jump, chase, strafe).
     *  Off by default made the bot stand still in combat range and get kited —
     *  the only approach-pressing code sits behind this flag. */
    public boolean combatMovementsEnabled = true;


    /** Enable safety system (edge detection, anti-fall braking, escape jump). */
    public boolean combatSaverEnabled = true;

    /** Reject paths that cross fence/wall connection bars.
     *  On ViaVersion servers, adjacent fences may have invisible connection
     *  collisions that the 1.21 client doesn't render. */
    public boolean avoidStuckFence = true;

    /** Reject paths that approach anvils from the side.
     *  On ViaVersion servers the anvil collision box may differ from
     *  what the 1.21 client shows — side approach gets stuck.
     *  Jumping on top of anvils is still allowed. */
    public boolean avoidStuckAnvil = true;

    /** Simulate velocity drag when touching damage blocks (cactus, fire,
     *  berry bush, etc.). Vanilla applies hurtTicks=10 which drags XZ
     *  velocity, affecting jump trajectories near these blocks. */
    public boolean predictDamageFromBlocks = true;

    /** Use changeLookDirection (pixel-quantized) instead of setYaw/setPitch.
     *  When true, rotation goes through vanilla's mouse math: delta is
     *  rounded to integer mouse pixels, then applied via changeLookDirection.
     *  This makes rotation indistinguishable from a real mouse for anti-cheat.
     *  When false, uses direct setYaw/setPitch (legacy behavior). */
    public boolean enableNativeRotation = true;

    /** Adjust pitch (vertical look angle) while executing paths.
     *  When true, the bot looks toward upcoming path nodes — more
     *  human-like than staring at a fixed angle. Purely cosmetic:
     *  pitch does not affect ground/air horizontal physics. */
    public boolean enablePitchChange = true;

    /** How many nodes ahead to look when computing pitch direction.
     *  Higher = smoother pitch changes, lower = more reactive. */
    public int pitchLookAheadNodes = 5;

    /** WindMouse gravity — pull toward target per render frame.
     *  Higher = faster convergence, lower = more human wobble.
     *  Bumped 2.0→3.2: the bot was reported "turning slowly" and its aim lagged
     *  a strafing/knocked-back target (trigger gate angle hit 90°); faster ramp
     *  closes the gap so swings actually land. */
    public double combatWindMouseGravity = 12.0;   // 5.5->12 (user 2026-07-24 round 3: "юзеры крутят мышь РЕЗКО"). Real players flick, they do not glide. Overshoot is handled by the close-range direct-settle zone, so pull hard.

    /** WindMouse wind — random perturbation magnitude per frame.
     *  Higher = more jitter/overshoot. */
    public double combatWindMouseWind = 0.15;   // 0.8 -> 0.35 -> 0.15: wind IS the wobble/circling the user hates. Keep a trace (anti-cheat plausibility), not a spiral.

    /** WindMouse max step — max degrees per render frame.
     *  Caps rotation speed. Lower = slower, more human-like.
     *  Bumped 4.0→7.0 for snappier close-range tracking (effective PvP over
     *  human-like slowness — this is a combat aura, not a legit-look aimer). */
    public double combatWindMouseMaxStep = 25.0;   // 4->7->10->25 deg/frame: at 60fps that is a human flick (~1500 deg/s peak, real players hit that); the settle zone still lands it cleanly

    /** Distance (degrees) below which wind noise decays.
     *  Below this angle the mouse "settles" toward target. */
    public double combatWindMouseWindDist = 15.0;

    /** Snap threshold — degrees. Below this, snap to target exactly. */
    public double combatWindMouseDoneThreshold = 0.4;

    /** Distance scaling for max step. At far angles, maxStep is multiplied
     *  by up to this factor for fast flick. 1.0 = no scaling. */
    public double combatWindMouseFlickScale = 3.0;

    // ── combat anti-shake + bunny-hop (LIVE-tunable; the shake is a live-only symptom) ──
    /** Aim low-pass blend toward the raw aim per tick (0..1). LOWER = smoother/less shake but
     *  more lag; HIGHER = snappier but shakier. If the aim still shakes on a live target, lower
     *  this (e.g. 0.35); if it lags behind a strafer, raise it (e.g. 0.7). */
    public double combatAimSmoothing = 0.5;
    /** Enemy-velocity EMA weight for the NEW sample (0..1). Lower = steadier lead (less shake). */
    public double combatVelSmoothing = 0.4;
    /** Bunny-hop cadence: min ms + random ms between combat jumps. Lower = hops more. */
    public long combatBunnyHopMinMs = 280;
    public long combatBunnyHopRandMs = 320;

    // ----------------------------------------

    public static TungstenConfig get() {
        return INSTANCE;
    }

    public static void load() {
        java.io.File file = CONFIG_FILE.toFile();
        if (!file.exists()) {
            // Nothing saved yet: run on the shipped defaults and do NOT create a file.
            // Writing one here is what made every future default unreachable — the file
            // would contain all keys, and a present key always beats a new default.
            return;
        }
        try (FileReader r = new FileReader(file)) {
            TungstenConfig loaded = GSON.fromJson(r, TungstenConfig.class);
            if (loaded == null) return;
            if (loaded.configVersion < CURRENT_CONFIG_VERSION) {
                TungstenMod.LOG.warn("tungsten.json is from an older config version ("
                        + loaded.configVersion + " < " + CURRENT_CONFIG_VERSION
                        + ") — discarding it and using the shipped defaults.");
                INSTANCE = new TungstenConfig();
                save();
                return;
            }
            INSTANCE = loaded;
        } catch (Exception e) {
            TungstenMod.LOG.warn("Failed to load tungsten.json, using defaults: " + e.getMessage());
            INSTANCE = new TungstenConfig();
        }
    }

    public static void save() {
        try (FileWriter w = new FileWriter(CONFIG_FILE.toFile())) {
            GSON.toJson(INSTANCE, w);
        } catch (Exception e) {
            TungstenMod.LOG.warn("Failed to save tungsten.json: " + e.getMessage());
        }
    }

    /**
     * Drop every persisted value and go back to the SHIPPED defaults.
     *
     * The file is rewritten in full on each `;settings x y`, so any field ever
     * touched keeps its old value forever — new defaults in the code never reach a
     * machine that has a tungsten.json. That silently ran test stands with combat
     * tuning and visualisation from months ago. Levers: `;settings reset` and py4j
     * resetTungstenConfig().
     */
    public static void resetToDefaults() {
        INSTANCE = new TungstenConfig();
        save();
    }
}
