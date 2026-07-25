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

    /** If true: on position mismatch > driftThreshold, setPosition() to simulation value.
     *  If false: stop executor and let path recalculate from real position. */
    public boolean driftCorrectionEnabled = false;

    /** Blocks of drift before triggering correction or executor stop. */
    public double driftThreshold = 0.8;

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

    /** Air strafe speed multiplier. Vanilla uses 0.02 (walk) / 0.026 (sprint).
     *  Higher values = more air control = pathfinder finds longer jumps.
     *  Set to 1.0 for vanilla-accurate simulation.
     *  Set to 3.0 for the old tungsten behavior (more aggressive jumps). */
    public float airStrafeMultiplier = 1.0F;

    // ---- follow settings ----

    /** Use the BFS block-path walker for IMMEDIATE movement toward a follow/combat target
     *  while the physics A* computes. ON by default (2026-07-23): without it, follow/punk
     *  depended only on the physics pathfinder, which re-plans forever on a MOVING target
     *  and the bot STANDS STILL (LIVE-A). The walker sprints from the real position toward
     *  the target every tick (drift-immune) and now face-before-moves (no spin). */
    public boolean followBlockPathFinderEnabled = true;

    /** Allow sprint-jumping during follow (BFS walker + direct sprint).
     *  If false, only walks (no jumps) — safer but slower. */
    public boolean followJumpingEnabled = true;

    /** EXPERIMENTAL (#1.6.1): generate block-space neighbours via the tungsten-native
     *  SmartMoves (Traverse/Ascend/Descend/Parkour) instead of the blind r=8 scan.
     *  Fewer, already-valid neighbours -> the search routes stepped/gap terrain within
     *  its node budget. Default OFF so course A (staircase) keeps the proven blind-scan
     *  path until SmartMoves is validated A-green. */
    public boolean smartMoves = false;

    // ---- block breaking ----

    /** Allow the block-space pathfinder to plan breaking through breakable walls. */
    public boolean allowBreak = true;

    /** Multiplier on the mining-time cost of planned breaks (higher = prefer detours). */
    public double breakCostMultiplier = 1.0;

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
    public boolean planPlaceMoves = false;

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

    /** Enable combat executor — pre-computes jump+turn+attack timeline via Agent sim.
     *  Visualization only when false (shows planned arc). */
    public boolean combatExecutorEnabled = false;

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
        if (file.exists()) {
            try (FileReader r = new FileReader(file)) {
                TungstenConfig loaded = GSON.fromJson(r, TungstenConfig.class);
                if (loaded != null) INSTANCE = loaded;
            } catch (Exception e) {
                TungstenMod.LOG.warn("Failed to load tungsten.json, using defaults: " + e.getMessage());
                INSTANCE = new TungstenConfig();
            }
        }
        save(); // write file with current values (creates it if missing)
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
