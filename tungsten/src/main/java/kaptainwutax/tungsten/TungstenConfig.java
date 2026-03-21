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

    /** If true: log per-node timing breakdown to stdout.
     *  Shows where PathFinder spends time: child generation, filtering,
     *  openSet ops, heuristic updates, block-space search, etc. */
    public boolean debugTime = false;

    /** Minimum mismatch magnitude to log in verbose debug mode.
     *  Hides float/double precision noise (typically ~1e-10).
     *  Set to 0 to log everything. */
    public double mismatchLogThreshold = 1e-6;

    /** If true: pathfinder continues computing while executor runs partial path.
     *  If false: pathfinder waits for executor to finish before resuming (original behavior). */
    public boolean parallelPathfinding = false;

    /** Closed-loop yaw correction strength. 0 = off (open-loop), 1.0 = full correction.
     *  Blends between pre-computed yaw and corrected yaw based on position drift.
     *  Recommended: 0.3-0.6. Higher = more aggressive correction but less smooth. */
    public float closedLoopStrength = 0.4F;

    /** Air strafe speed multiplier. Vanilla uses 0.02 (walk) / 0.026 (sprint).
     *  Higher values = more air control = pathfinder finds longer jumps.
     *  Set to 1.0 for vanilla-accurate simulation.
     *  Set to 3.0 for the old tungsten behavior (more aggressive jumps). */
    public float airStrafeMultiplier = 1.0F;

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
}
