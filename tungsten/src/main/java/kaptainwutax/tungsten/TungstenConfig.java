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
