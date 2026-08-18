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
     */
    public boolean pathStartMustSucceed = false;

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
