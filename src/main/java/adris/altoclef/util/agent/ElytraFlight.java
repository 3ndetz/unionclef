package adris.altoclef.util.agent;

import adris.altoclef.multiversion.entity.PlayerVer;
import adris.altoclef.util.helpers.InputHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Map;

/**
 * Elytra flight to a point: equip the elytra, jump, open the wings, climb on fireworks to a
 * cruise height above the terrain, hold course, glide down and land near the target.
 *
 * <p>The pathfinder (tungsten) has no flight at all, and baritone's ElytraBehavior is not
 * compiled and is built around a native nether pathfinder. This is a small open-air controller
 * for the overworld. Flying itself is ElytraPilot: flight-path angle held like a plane, rate-
 * limited controls, rockets as throttle, a glide slope and a flare. It does not route around
 * mountains; it looks 120 blocks ahead and climbs over them.
 */
public final class ElytraFlight {

    public enum Phase { IDLE, EQUIP, TAKEOFF, FLYING, DONE, FAILED }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String reason = "";
    private static double tx, ty, tz;
    private static int ticks, takeoffTicks, boostCooldown, rocketsUsed;
    private static int glideWait;
    private static volatile int lastRocketTick = -1000;
    private static volatile kaptainwutax.tungsten.elytra.ElytraSolver.Plan plan; // the solver's last
    private static int planTick = -100;
    private static boolean solverSteered; // the solver had the controls last tick
    // Rings: route points are ring centres, flown through in order by the solver; a ring counts
    // when the flight crosses its plane (square to the way in), the miss is how far off centre.
    private static volatile boolean rings;
    private static final java.util.List<Double> ringMiss = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static double lastX, lastY, lastZ;
    private static double[] ringStart = new double[3]; // where the gliding began: the way into ring 1
    private static net.minecraft.util.math.Vec3d throughNext; // the ring centre the next plan must pass
    // Route search (elytraRouteSearch): the corners come from a worker; until then it circles.
    private static volatile java.util.concurrent.CompletableFuture<java.util.List<net.minecraft.util.math.Vec3d>> search;
    private static volatile String searchState = "";
    private static boolean landAtTarget;      // rings from a search: land at the target, not 60 on
    private static double ringBest;           // closest to the current ring so far, and when
    private static int ringBestTick, researches, ringTurnTicks;
    private static double finalX, finalY, finalZ;
    private static final java.util.concurrent.ExecutorService SEARCH = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "elytra-route");
        t.setDaemon(true);
        return t;
    });
    // The second layer (AirPlan): a flight the physics can fly, searched inside the corridor the cells
    // give, tick by tick from tick airPlanT0 -- looks and rockets planned seconds ahead, where a climb
    // needs the speed. A new one is planned from the state predicted PLAN_LAT ticks on (the old plan's
    // looks held till then) and takes over at that tick; it is planned again whenever the glider is
    // more than PLAN_DEV blocks off it (a rocket pulls 20 to 31 ticks, the plan cannot know which).
    private static volatile kaptainwutax.tungsten.elytra.AirRoute.Corridor corridor;
    private static volatile java.util.concurrent.CompletableFuture<PlanJob> planJob;
    private static volatile java.util.List<kaptainwutax.tungsten.elytra.AirPlan.Tick> airPlan;
    private static volatile int airPlanT0;
    private static int planJobAt, planMisses, replans, firedAt, planMs, planMsMax;
    private static volatile double planMsAvg; // how long a plan takes here, kept across flights
    // what the plans did, for the status: the last few events (a flight's story, not a log file)
    private static final java.util.Deque<String> planNotes = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static boolean planGaveUp;
    private static net.minecraft.util.math.Vec3d planGoal;
    private static double planBest;
    private static int planBestTick;
    private static float holdYaw, holdPitch;
    private static final int PLAN_LAT = 6, PLAN_LAT_FIRST = 16, PLAN_PULL = 25, ROCKET_LEAD = 1, PLAN_SAFE = 12;
    private static final double PLAN_DEV = 1.5, PLAN_REACHED = 10;
    // The landing on the solver (tungsten LandingPlan): searched when the approach begins, flown tick
    // by tick, searched again from where the glider is when it is off by LAND_DEV. No landing to be
    // had (LAND_MISSES searches in a row): the pilot lands, as before.
    private static volatile java.util.concurrent.CompletableFuture<kaptainwutax.tungsten.elytra.LandingPlan.Plan> landJob;
    private static java.util.List<kaptainwutax.tungsten.elytra.AirPlan.Tick> landPlan;
    private static int landPlanT0, landJobAt, landMisses, landPlans, landMs;
    private static volatile double landMsAvg;
    private static boolean landGaveUp;
    private static String landState = "";
    // Off by LAND_DEV, or LAND_DEV_END in the plan's last LAND_END ticks: the touchdown is where a
    // small miss shows -- a plan touching sinking 0.16 met the ground a block lower than the bot was,
    // and it floated 10 blocks past the spot (08.11).
    private static final double LAND_DEV = 1.5, LAND_DEV_END = 0.8, LAND_FROM = 60;
    private static final int LAND_END = 20;
    // Near the ground and the fall adding up: nose up, the plan's look or not.
    private static final double LAND_GUARD_FALL = 1.5, LAND_GUARD_HEIGHT = 8;
    private static kaptainwutax.tungsten.elytra.LandingPlan.Touchdown landTouch;
    private static int landTouchAt;
    private static double landVy;
    private static final int LAND_MISSES = 3;
    private static final ElytraPilot pilot = new ElytraPilot();
    private static boolean pilotReady;
    private static volatile ElytraPilot.Out lastOut;
    // Fall watch: the wings open in a long fall, and a glide nobody steers is landed. "armed"
    // marks a glide that is ours (we opened the wings, or our flight was stopped) -- a player
    // flying this account by hand is never taken over.
    private static volatile boolean fallGlide = true;
    private static boolean armed, rescue;
    // Hands on the controls: a player steering this account by hand (W/A/S/D)
    // takes over at once. The pilot lets go and the fall watch stays off until the feet are down.
    // The fall watch also needs the agent to be attached: playing alone, nothing is taken over.
    private static volatile long manualMs, agentMs;
    private static boolean handsOn;
    // Wingman flight: the leader's name, and their velocity estimated from positions -- the
    // client does not get other players' velocity, only where they are.
    private static volatile String leader;
    private static double lastLx, lastLy, lastLz, lvx, lvy, lvz;
    // The leader's rockets seen this flight: a firework spawned on the leader, by entity id.
    private static final java.util.Set<Integer> leaderRocketIds = new java.util.HashSet<>();
    private static int leaderRockets;
    private static boolean landingAfterFollow; // the leader set down: this flight is the landing
    private static boolean leaderSeen;
    private static int leaderDownTicks;
    private static double slotSide;           // which side of the leader's track this pursuer keeps
    private static net.minecraft.util.math.Vec3d slotSmooth;
    // A creative leader hanging in one place (building, AFK): where they stopped and for how long.
    // Orbiting them would go on for ever, so after a while the bot lands beside them instead.
    private static double stillX, stillY, stillZ;
    private static int leaderStillTicks;
    private static final int LEADER_STILL_LAND = 300; // 15 s
    /** Pursuit: within this many blocks of the leader the pilot keeps formation, beyond it the solver
     *  catches up, paying less for a rocket the further behind (see the pursuit in fly). */
    private static final double FORMATION = 14;
    private static net.minecraft.util.math.BlockPos openSky; // where to walk for a take-off
    // Progress watchdog: the best distance to the target and when it last improved.
    private static double bestDist;
    private static int bestTick;
    private static int deployCooldown;
    private static boolean glideSent;
    private static volatile boolean lowLevel; // бреющий: a few blocks over the ground all the way
    private static int tricksWanted;          // figures to fly on the way (пилотаж)
    private static double[][] route;          // waypoints {x, y, z}, the last one is the landing spot
    private static int routeIdx;
    private static boolean registered;
    private static int handCheck;
    // A favourite thing to hold on the ground (setFavorite): rockets put away after a landing used
    // to land on whatever block sat in the hotbar -- obsidian, near home -- and the character took
    // it up as his lucky stone. null: the old pick, an empty slot, else a block.
    private static volatile Item favorite;

    private static final int TIMEOUT_TICKS = 20 * 180;

    private ElytraFlight() {}

    /** Hook the client tick once: flights, and the fall watch between flights. */
    public static synchronized void register() {
        if (!registered) {
            ClientTickEvents.END_CLIENT_TICK.register(ElytraFlight::tick);
            ClientTickEvents.END_CLIENT_TICK.register(CreativeWingman::tick);
            ClientTickEvents.END_CLIENT_TICK.register(FlightViz::tick);
            ClientTickEvents.END_CLIENT_TICK.register(FlightLog::tick);
            registered = true;
        }
    }

    /** The agent called in; the fall watch works only while one is attached. */
    public static void agentSeen() { agentMs = System.currentTimeMillis(); }

    /** Milliseconds since the player last steered by hand, -1 if never. */
    public static long msSinceManual() {
        long t = manualMs;
        return t == 0 ? -1 : System.currentTimeMillis() - t;
    }

    /** Fall watch on/off (on by default): open the wings in a long fall and land. */
    public static void setFallGlide(boolean on) { fallGlide = on; }

    /** Fly beside a player who is flying; call on the client thread. */
    public static synchronized Map<String, Object> startFollow(String nick) {
        var lead = findPlayer(MinecraftClient.getInstance(), nick);
        if (lead == null) {
            Map<String, Object> m = status();
            m.put("phase", "failed");
            m.put("reason", "not in sight");
            return m;
        }
        Map<String, Object> m = start(lead.getX(), lead.getY(), lead.getZ());
        leader = lead.getName().getString();
        slotSide = 0;
        slotSmooth = null;
        leaderSeen = false;
        leaderRocketIds.clear();
        leaderRockets = 0;
        landingAfterFollow = false;
        leaderDownTicks = 0;
        leaderStillTicks = 0;
        stillX = lead.getX();
        stillY = lead.getY();
        stillZ = lead.getZ();
        return m;
    }

    private static net.minecraft.entity.player.PlayerEntity findPlayer(MinecraftClient client, String nick) {
        if (client.world == null || nick == null) return null;
        for (var pl : client.world.getPlayers()) {
            if (pl != client.player && pl.getName().getString().equalsIgnoreCase(nick)) return pl;
        }
        return null;
    }

    /** Start a flight; call on the client thread. */
    public static synchronized Map<String, Object> start(double x, double y, double z) {
        return start(x, y, z, false);
    }

    /** Start a flight, low-level (бреющий) when low; call on the client thread. */
    public static synchronized Map<String, Object> start(double x, double y, double z, boolean low) {
        return start(x, y, z, low, 0);
    }

    /** A flight with figures on the way (tricks: how many); call on the client thread. */
    public static synchronized Map<String, Object> start(double x, double y, double z, boolean low, int tricks) {
        route = null;
        return begin(x, y, z, low, tricks);
    }

    /** A route: fly through each point without landing, land at the last; call on the client thread. */
    public static synchronized Map<String, Object> startRoute(double[][] points, boolean low, int tricks) {
        if (points.length == 0) {
            Map<String, Object> m = status();
            m.put("phase", "failed");
            m.put("reason", "no points");
            return m;
        }
        Map<String, Object> m = begin(points[0][0], points[0][1], points[0][2], low, tricks);
        route = points;
        routeIdx = 0;
        m.put("waypoint", "1/" + points.length);
        return m;
    }

    private static Map<String, Object> begin(double x, double y, double z, boolean low, int tricks) {
        register();
        // A creative hover left on (hoverTo, hoverFollow) turns creative flight back on every tick,
        // and the take-off turns it off every tick: the wings never opened (01.11, after the agent
        // was stopped mid-hover, until the client was restarted).
        if (CreativeWingman.active()) CreativeWingman.stop("elytra flight");
        lowLevel = low;
        tricksWanted = Math.max(0, Math.min(tricks, 8));
        rescue = false;
        landingAfterFollow = false;
        leader = null;
        tx = x;
        ty = y;
        tz = z;
        ticks = takeoffTicks = boostCooldown = rocketsUsed = glideWait = 0;
        glideSent = false;
        openSky = null;
        pilotReady = false;
        bestDist = Double.MAX_VALUE;
        bestTick = 0;
        lastOut = null;
        lastRocketTick = -1000;
        plan = null;
        solverSteered = false;
        rings = false;
        ringMiss.clear();
        search = null;
        searchState = "";
        landAtTarget = false;
        researches = 0;
        corridor = null;
        planGoal = null;
        planJob = null;
        airPlan = null;
        planMisses = replans = planMs = planMsMax = 0;
        planNotes.clear();
        firedAt = -1;
        planGaveUp = false;
        landJob = null;
        landPlan = null;
        landMisses = landPlans = 0;
        landGaveUp = false;
        landTouch = null;
        landState = "";
        reason = "";
        phase = Phase.EQUIP;
        FlightViz.newFlight();
        return status();
    }

    /** Fly through rings: points are ring centres {x, y, z}, in order; after the last one it lands
     *  60 blocks on. Steered by the solver whatever the elytraSolver setting. Call on the client thread. */
    public static synchronized Map<String, Object> startRings(double[][] centres) {
        if (centres.length == 0) {
            Map<String, Object> m = status();
            m.put("phase", "failed");
            m.put("reason", "no rings");
            return m;
        }
        Map<String, Object> m = begin(centres[0][0], centres[0][1], centres[0][2], false, 0);
        route = centres;
        routeIdx = 0;
        rings = true;
        ringTurnTicks = 0;
        ringBest = Double.MAX_VALUE;
        ringBestTick = Integer.MAX_VALUE; // set on the first gliding tick
        return status();
    }

    /** One tick through the rings: aim a little past the centre along the way in, count crossings. */
    private static void flyRings(MinecraftClient client, ClientPlayerEntity p) {
        double[][] r = route;
        double[] c = r[routeIdx];
        double[] from = routeIdx > 0 ? r[routeIdx - 1] : ringStart;
        double nx = c[0] - from[0], ny = c[1] - from[1], nz = c[2] - from[2];
        double nl = Math.max(Math.sqrt(nx * nx + ny * ny + nz * nz), 1e-6);
        nx /= nl;
        ny /= nl;
        nz /= nl;
        double ox = lastX, oy = lastY, oz = lastZ, px = p.getX(), py = p.getY() + 0.3, pz = p.getZ();
        double before = (ox - c[0]) * nx + (oy - c[1]) * ny + (oz - c[2]) * nz;
        double now = (px - c[0]) * nx + (py - c[1]) * ny + (pz - c[2]) * nz;
        lastX = px;
        lastY = py;
        lastZ = pz;
        if (before < 0 && now >= 0) { // through the plane: where, against the centre
            double t = before / (before - now);
            double qx = ox + (px - ox) * t - c[0], qy = oy + (py - oy) * t - c[1], qz = oz + (pz - oz) * t - c[2];
            // off centre as a square frame sees it: the larger of up/down and sideways
            double hl = Math.max(Math.hypot(nx, nz), 1e-6);
            double side = Math.abs(qx * -nz / hl + qz * nx / hl);
            ringMiss.add(Math.round(Math.max(Math.abs(qy), side) * 10) / 10.0);
            if (++routeIdx >= r.length) { // the last ring: land 60 blocks on along the way out
                rings = false;
                route = null;
                tx = c[0] + nx / Math.max(Math.hypot(nx, nz), 1e-6) * 60;
                tz = c[2] + nz / Math.max(Math.hypot(nx, nz), 1e-6) * 60;
                ty = c[1];
                if (landAtTarget) { // a searched route ends over the target: land there
                    tx = finalX;
                    ty = finalY;
                    tz = finalZ;
                }
                pilot.resetForLanding(p.getYaw(), p.getPitch());
                solverSteered = false;
                return;
            }
            c = r[routeIdx];
            ringBest = Double.MAX_VALUE;
            ringBestTick = ticks;
            ringTurnTicks = 0;
        }
        // No nearer to the ring for 5 s: boxed in (once it burned 120 rockets in the top corner of a
        // hall). A searched way is searched again from here, twice; then, or for rings, it gives up.
        double dRing = Math.sqrt((px - c[0]) * (px - c[0]) + (py - c[1]) * (py - c[1]) + (pz - c[2]) * (pz - c[2]));
        // A ring behind the way it flies is a turn under way, not a box: a U-turn to the next ring
        // goes away from it first ("blocked" in the turn at the far end of a course, 07.11). Ten
        // seconds of that per ring at most, so circling for ever still ends.
        boolean turning = (px - ox) * (c[0] - px) + (pz - oz) * (c[2] - pz) < 0 && ringTurnTicks++ < 200;
        if (dRing < ringBest - 1 || ringBestTick > ticks || turning) {
            ringBest = dRing;
            ringBestTick = ticks;
        } else if (ticks - ringBestTick > 100) {
            ringBest = Double.MAX_VALUE;
            ringBestTick = ticks;
            if (landAtTarget && researches < 2) {
                researches++;
                rings = false;
                route = null;
                landAtTarget = false;
                search = null;
                tx = finalX;
                ty = finalY;
                tz = finalZ;
                searchState = "searching again";
                return;
            }
            rings = false;
            route = null;
            fail("blocked");
            return;
        }
        tx = c[0];
        ty = c[1];
        tz = c[2];
        ElytraPilot.Out o = new ElytraPilot.Out();
        o.mode = ElytraPilot.Mode.TRICK;
        o.yaw = p.getYaw();
        o.pitch = p.getPitch();
        // On towards the ring after this one, passing this one's centre -- if that ring lies on past
        // this one along the way in. One behind it (a course that turns back) is not a point to head
        // for yet: turning for it early saved more way than the miss cost, and the ring before the
        // turn was passed 65 blocks off or circled till the watchdog gave up (07.11). Then, as after
        // the last ring, straight on through this one first.
        double[] nxt = routeIdx + 1 < r.length ? r[routeIdx + 1] : null;
        if (nxt == null || (nxt[0] - c[0]) * nx + (nxt[1] - c[1]) * ny + (nxt[2] - c[2]) * nz <= 0) {
            nxt = new double[]{c[0] + nx * 40, c[1], c[2] + nz * 40};
        }
        throughNext = new net.minecraft.util.math.Vec3d(c[0], c[1], c[2]);
        steerTo(client, p, o, new net.minecraft.util.math.Vec3d(nxt[0], nxt[1], nxt[2]), 0.3, 0.6);
        throughNext = null;
        lastOut = o;
        p.setYaw(o.yaw);
        p.setPitch(o.pitch);
        if (o.boost && !boost(client, p)) pilot.boostFailed();
    }

    /** What the flight drawing needs, read on the client thread (FlightViz). */
    record View(boolean on, double tx, double ty, double tz, double[][] route, int routeIdx, String leader,
                ElytraPilot.Mode mode, int ticksSinceRocket, kaptainwutax.tungsten.elytra.ElytraSolver.Plan plan,
                java.util.List<kaptainwutax.tungsten.elytra.AirPlan.Tick> airPlan, int airPlanIdx) {
    }

    static View view() {
        Phase ph = phase;
        ElytraPilot.Out o = lastOut;
        boolean on = ph == Phase.EQUIP || ph == Phase.TAKEOFF || ph == Phase.FLYING;
        var pl = solverSteered ? plan : null;
        var ap = airPlan;
        return new View(on, tx, ty, tz, route, routeIdx, leader, o == null ? null : o.mode, ticks - lastRocketTick, pl,
                ap, ticks - airPlanT0);
    }

    public static void stop() {
        if (phase != Phase.DONE && phase != Phase.FAILED) {
            phase = Phase.IDLE;
            reason = "stopped";
            armed = true; // still gliding: the fall watch lands it close by
            MinecraftClient.getInstance().execute(ElytraFlight::putRocketsAway);
        }
    }

    public static Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        m.put("reason", reason);
        m.put("rocketsUsed", rocketsUsed);
        if (leader != null) m.put("leaderRockets", leaderRockets);
        if (lowLevel) m.put("low", true);
        var r = route;
        if (r != null) m.put("waypoint", (routeIdx + 1) + "/" + r.length);
        if (rings || !ringMiss.isEmpty()) m.put("ringMiss", new java.util.ArrayList<>(ringMiss));
        if (!searchState.isEmpty()) m.put("routeSearch", searchState);
        if (replans > 0) {
            m.put("plans", replans);
            m.put("planMs", planMsMax);
        }
        if (!planNotes.isEmpty()) m.put("planNotes", new java.util.ArrayList<>(planNotes));
        var tr = pilot.trick();
        if (tr != null) m.put("trick", tr.name().toLowerCase(java.util.Locale.ROOT));
        m.put("figures", pilot.tricksDone());
        var sky = openSky;
        if (phase == Phase.FAILED && sky != null) m.put("openSky", sky.getX() + "," + sky.getY() + "," + sky.getZ());
        if (leader != null) m.put("leader", leader);
        if (!landState.isEmpty()) m.put("landing", landState);
        m.put("target", String.format(java.util.Locale.ROOT, "%.0f,%.0f,%.0f", tx, ty, tz));
        var p = MinecraftClient.getInstance().player;
        if (p != null) {
            m.put("pos", String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", p.getX(), p.getY(), p.getZ()));
            m.put("distance", Math.round(Math.hypot(tx - p.getX(), tz - p.getZ())));
            m.put("gliding", adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p));
        }
        ElytraPilot.Out o = lastOut;
        if (o != null) { // flight telemetry, for tuning from the agent's log
            m.put("mode", o.mode.name().toLowerCase(java.util.Locale.ROOT));
            m.put("speed", Math.round(o.speed * 100) / 100.0);
            m.put("pitch", Math.round(o.pitch));
            m.put("climbAngle", Math.round(o.gamma));
        }
        return m;
    }

    private static void fail(String why) {
        phase = Phase.FAILED;
        reason = why;
        putRocketsAway();
    }

    private static void finish(String why) {
        phase = Phase.DONE;
        reason = why;
        putRocketsAway();
    }

    /** Leave a harmless stack in hand. With rockets selected, the next right click the pathfinder
     *  makes (placing a block, a fall clutch) launches a firework instead -- and with a full
     *  inventory the mod cannot de-equip it on its own. */
    private static void putRocketsAway() {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return;
        var inv = p.getInventory();
        if (!inv.getStack(PlayerVer.getSelectedSlot(inv)).isOf(Items.FIREWORK_ROCKET)) return;
        if (selectFavorite(p)) return; // harmless too, and on purpose
        // An empty slot, else a block, else anything that is not a rocket: with a hotbar of tools
        // and food the rockets stayed in hand, and every click on a door or a sign on the way
        // launched one off the ground.
        int block = -1, other = -1;
        for (int i = 0; i < 9; i++) {
            var st = inv.getStack(i);
            if (st.isEmpty()) {
                PlayerVer.setSelectedSlot(inv, i);
                return;
            }
            if (block < 0 && st.getItem() instanceof net.minecraft.item.BlockItem) block = i;
            if (other < 0 && !st.isOf(Items.FIREWORK_ROCKET)) other = i;
        }
        if (block >= 0) PlayerVer.setSelectedSlot(inv, block);
        else if (other >= 0) PlayerVer.setSelectedSlot(inv, other);
    }

    public static void setFavorite(Item item) {
        favorite = item;
    }

    public static Item favorite() {
        return favorite;
    }

    /** Select the favourite when it is carried: its hotbar slot, else it is swapped from the main
     *  inventory into an empty hotbar slot. False when none is set, none is carried, or the hotbar
     *  has no room (a full hotbar is not reshuffled for it). */
    private static boolean selectFavorite(ClientPlayerEntity p) {
        Item fav = favorite;
        if (fav == null) return false;
        var inv = p.getInventory();
        int empty = -1;
        for (int i = 0; i < 9; i++) {
            var st = inv.getStack(i);
            if (st.isOf(fav)) {
                PlayerVer.setSelectedSlot(inv, i);
                return true;
            }
            if (empty < 0 && st.isEmpty()) empty = i;
        }
        var mc = MinecraftClient.getInstance();
        // An open container has its own handler: a click on the player's would be ignored.
        if (empty < 0 || mc.interactionManager == null || p.currentScreenHandler != p.playerScreenHandler) return false;
        for (int i = 9; i < 36; i++) {
            if (!inv.getStack(i).isOf(fav)) continue;
            // A number key over the slot in the inventory screen; main 9..35 keeps its index there.
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, empty, SlotActionType.SWAP, p);
            PlayerVer.setSelectedSlot(inv, empty);
            return true;
        }
        return false;
    }

    /** Take the favourite in hand now, client thread. Null when it is in hand, else why not: only
     *  on the ground and between flights -- never instead of the rockets of a flight or a salute,
     *  or the sword of a fight. */
    public static String holdFavorite() {
        var p = MinecraftClient.getInstance().player;
        Item fav = favorite;
        if (p == null) return "not in game";
        if (fav == null) return "no favorite";
        if (p.getMainHandStack().isOf(fav)) return null;
        Phase ph = phase;
        if (ph != Phase.IDLE && ph != Phase.DONE && ph != Phase.FAILED) return "flying";
        if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p) || !p.isOnGround()) return "in the air";
        if (Salute.status().get("phase").equals("firing")) return "salute";
        if (defending()) return "defending";
        return selectFavorite(p) ? null : "not carried";
    }

    /** Mob defense has the controls: it picks the weapon, the favourite must not take its place. */
    private static boolean defending() {
        var mod = adris.altoclef.AltoClef.getInstance();
        return mod != null && mod.getTaskRunner() != null
                && mod.getTaskRunner().getCurrentTaskChain() == mod.getMobDefenseChain();
    }

    private static void tick(MinecraftClient client) {
        Phase ph = phase;
        if (manualInput(client)) {
            long last = manualMs;
            manualMs = System.currentTimeMillis();
            // A new spell of steering by hand while the agent drives: stop the walk, the follow and
            // a show at once. The pathfinder writes the movement keys over the player's, so
            // waiting for the agent's next poll (and its own @stop) felt like a fight for the keys.
            if (manualMs - last > 3000 && manualMs - agentMs < 30_000) stopEverything();
            if (client.player != null && !client.player.isOnGround()) handsOn = true;
            if (ph == Phase.EQUIP || ph == Phase.TAKEOFF || ph == Phase.FLYING) {
                // No putRocketsAway: the player flying on by hand wants the rockets in hand.
                phase = Phase.FAILED;
                reason = "by hand";
                rescue = armed = false;
                leader = null;
                return;
            }
        }
        if (ph == Phase.IDLE || ph == Phase.DONE || ph == Phase.FAILED) {
            watchFall(client);
            keepRocketsOutOfHand(client);
            return;
        }
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.interactionManager == null) {
            fail("not in game");
            return;
        }
        // A wingman flies as long as the leader does. Out of time in the air: glide down rather
        // than let go (that is a fall).
        if (++ticks > TIMEOUT_TICKS && leader == null) {
            if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p) && !rescue) {
                startRescue(client, p, p.getY() - top(client, p.getX(), p.getZ()));
                return;
            }
            fail("timeout");
            return;
        }
        if (boostCooldown > 0) boostCooldown--;

        switch (ph) {
            case EQUIP -> {
                if (p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
                    if (findItem(p, Items.FIREWORK_ROCKET) < 0) {
                        fail("no fireworks");
                        return;
                    }
                    phase = Phase.TAKEOFF;
                    return;
                }
                if (ticks > 40) {
                    fail("could not equip elytra");
                    return;
                }
                // Right-clicking an elytra in hand puts it on (and swaps out a chestplate).
                if (holdInHotbar(client, p, Items.ELYTRA)) {
                    client.interactionManager.interactItem(p, Hand.MAIN_HAND);
                } else {
                    fail("no elytra");
                }
            }
            case TAKEOFF -> {
                // Creative flight (double jump) blocks gliding: make sure it is off.
                if (p.getAbilities().flying) {
                    p.getAbilities().flying = false;
                    p.sendAbilitiesUpdate();
                }
                // Wings do not open in water: say so at once instead of jumping for 12 seconds.
                if (p.isTouchingWater() || p.isInLava()) {
                    fail("in water");
                    return;
                }
                // A roof over the head (a bunker, a house): a take-off there glides into the ceiling
                // and burns rockets against it. Walk out first.
                if (!adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)
                        && top(client, p.getX(), p.getZ()) > p.getY() + 2.5) {
                    openSky = nearestOpenSky(client, p, 16); // a forest canopy is a roof too
                    fail("under a roof");
                    return;
                }
                if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) {
                    phase = Phase.FLYING; // the pilot fires the first rocket on its first tick
                    return;
                }
                if (++takeoffTicks > 20 * 12) {
                    fail("could not take off");
                    return;
                }
                aim(p, tx - p.getX(), tz - p.getZ(), -10);
                if (p.isOnGround()) {
                    // The rocket in hand before the jump: a swap from the main inventory lands a
                    // tick later, and a glide that waited for it touched the ground -- the server
                    // closes the wings on a touch, and the boost then carried a falling bot.
                    if (!p.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) {
                        holdInHotbar(client, p, Items.FIREWORK_ROCKET);
                        return;
                    }
                    p.jump();
                    glideSent = false;
                    glideWait = 0;
                } else if (!glideSent && p.getVelocity().y < 0) {
                    // Past the top of the jump: open the wings ONCE. The server answers a repeat
                    // while already gliding with stopGliding(), and the client sees the gliding
                    // flag a tick or two late -- sending every tick closed the wings again.
                    p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                    glideSent = true;
                } else if (glideSent && ++glideWait > 10) {
                    glideSent = false; // no confirmation this jump: try again on the next one
                    glideWait = 0;
                }
            }
            case FLYING -> fly(client, p);
            default -> { }
        }
    }

    private static void fly(MinecraftClient client, ClientPlayerEntity p) {
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double dist = Math.hypot(dx, dz);
        if (!adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) {
            // Down within 30 blocks: good enough, the agent walks the rest. Further: fly again.
            // A searched way counts from the target, not from the corner it was making for.
            if (landAtTarget) dist = Math.hypot(finalX - p.getX(), finalZ - p.getZ());
            // Rings or waypoints still ahead are not an arrival, however near the target (a ring
            // course that comes back to its start "landed" at the first ring, 25 blocks out, 07.11).
            boolean ahead = route != null && (rings || routeIdx < route.length - 1);
            if (p.isOnGround() && dist < 30 && !ahead) {
                noteTouch(p);
                finish("landed");
            } else if (p.isOnGround()) {
                takeoffTicks = 0;
                phase = Phase.TAKEOFF;
            }
            return;
        }
        if (!pilotReady) {
            pilot.reset(p.getYaw(), p.getPitch());
            pilot.setTricks(rescue ? 0 : tricksWanted);
            pilotReady = true;
            lastX = p.getX();
            lastY = p.getY() + 0.3;
            lastZ = p.getZ();
            ringStart = new double[]{lastX, lastY, lastZ};
        }
        if (rings && !rescue && route != null) {
            flyRings(client, p);
            return;
        }
        if (routeSearchOn() && !rescue && route == null && leader == null && !landAtTarget
                && (routePlanOn() && !planGaveUp ? flyAirPlan(client, p) : searchRoute(client, p))) {
            return; // circling while the way is searched, or flying the plan
        }
        // A route: a waypoint passed within 20 blocks -- on to the next one, no landing.
        var r = route;
        if (r != null && !rescue && routeIdx < r.length - 1 && dist < 20) {
            routeIdx++;
            tx = r[routeIdx][0];
            ty = r[routeIdx][1];
            tz = r[routeIdx][2];
            dx = tx - p.getX();
            dz = tz - p.getZ();
            dist = Math.hypot(dx, dz);
            bestDist = Double.MAX_VALUE;
            bestTick = ticks;
        }
        pilot.setPassThrough(r != null && !rescue && routeIdx < r.length - 1);
        pilot.setLowLevel(lowLevel && !rescue);
        if (lowLevel && !rescue) pilot.setLowGuide(lowGuide(client, p));
        var v = p.getVelocity();
        if (leader != null && wingman(client, p, v)) return;
        ElytraPilot.Out o = pilot.step(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z,
                tx, landingY(client), tz, terrainAhead(client, p), top(client, p.getX(), p.getZ()),
                runwayClear(client, p), obstacleAhead(client, p));
        // The landing: from the approach on, a touchdown searched on the physics, when there is one.
        boolean landing = landingDue(o, dist) && flyLanding(client, p, o);
        // En route only: from the pilot's descent on, the approach is its own (handed over at cruise
        // height it lands; handed over low and fast over the spot it circled a block over the
        // ground for half a minute, a rocket a second).
        boolean enRoute = o.mode == ElytraPilot.Mode.CRUISE || o.mode == ElytraPilot.Mode.CLIMB
                || o.mode == ElytraPilot.Mode.AVOID;
        if (landing) {
            solverSteered = false;
        } else if (solverOn() && !rescue && enRoute && dist > 70) {
            steer(client, p, o, goalAhead(client, p, dist));
        } else if (solverSteered) {
            solverSteered = false;
            pilot.resetForLanding(p.getYaw(), p.getPitch()); // the pilot's integrator saw none of it
        }
        lastOut = o;
        p.setYaw(o.yaw);
        p.setPitch(o.pitch);
        // No progress for 6 s outside a landing (boxed in, a ceiling, a wall): give up rather than
        // burn rockets against it -- 36 went into a bunker roof once.
        boolean landingPhase = o.mode == ElytraPilot.Mode.FLARE || o.mode == ElytraPilot.Mode.SINK
                || o.mode == ElytraPilot.Mode.FINAL || o.mode == ElytraPilot.Mode.TRICK // figures circle on purpose
                || landing; // a landing high over the spot circles its height away
        if (dist < bestDist - 3 || landingPhase) {
            bestDist = Math.min(bestDist, dist);
            bestTick = ticks;
        } else if (ticks - bestTick > 120) {
            // In the air, giving up is letting go of a glide: it fell into a lake from the middle
            // of a figure once. Glide down ahead instead, as the fall watch does; a rescue glide
            // ends on the ground whatever its progress.
            if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) {
                if (!rescue) startRescue(client, p, p.getY() - top(client, p.getX(), p.getZ()));
                bestTick = ticks;
                return;
            }
            fail("blocked");
            return;
        }
        // Down after a pursuit: a landing sheds energy, a rocket adds it -- none unless the ground is
        // right there.
        if (o.boost && landingAfterFollow && p.getY() - top(client, p.getX(), p.getZ()) > 3) o.boost = false;
        if (o.boost && !rescue && !boost(client, p)) pilot.boostFailed(); // a rescue glides, no rockets
        if (o.arrived) finish(rescue ? "glided down" : "arrived");
    }

    /** Between flights, while the agent drives: no rockets in hand on the ground, once a second,
     *  and an empty hand takes the favourite. Not while the player steers by hand -- they may be
     *  about to take off themselves. */
    private static void keepRocketsOutOfHand(MinecraftClient client) {
        var p = client.player;
        if (p == null || ++handCheck % 20 != 0) return;
        long manual = msSinceManual();
        boolean agentDrives = System.currentTimeMillis() - agentMs < 30_000 && (manual < 0 || manual > 45_000);
        if (!agentDrives || Salute.status().get("phase").equals("firing")) return;
        if (adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) return;
        if (p.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) putRocketsAway();
        else if (p.getMainHandStack().isEmpty() && favorite != null && p.isOnGround() && !defending()) selectFavorite(p);
    }

    /** Between flights: open the wings in a long fall; land a glide that is ours. */
    private static void watchFall(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (!fallGlide || p == null || client.world == null) return;
        if (deployCooldown > 0) deployCooldown--;
        boolean gliding = adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p);
        if (p.isOnGround() || p.isTouchingWater() || p.isInLava() || p.hasVehicle() || p.isClimbing()
                || p.getAbilities().flying || !p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            armed = false;
            if (!p.getAbilities().flying) handsOn = false; // feet down: watch again from here
            return;
        }
        if (handsOn || System.currentTimeMillis() - agentMs > 30_000) {
            armed = false;
            // Steering by hand (or no agent at all): no autopilot. In survival a long fall still
            // gets the wings opened -- a parachute; where to glide stays the player's call.
            boolean survival = !p.getAbilities().creativeMode;
            if (survival && !gliding && p.getVelocity().y < -0.9 && p.getY() - top(client, p.getX(), p.getZ()) > 8
                    && deployCooldown == 0) {
                p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                deployCooldown = 10;
            }
            return;
        }
        double height = p.getY() - top(client, p.getX(), p.getZ());
        if (gliding) {
            if (armed) startRescue(client, p, height);
            return;
        }
        // About 6 blocks of free fall so far and more to go: a bucket clutch at this speed is a
        // gamble, wings are not.
        if (p.getVelocity().y < -0.6 && height > 4 && deployCooldown == 0) {
            p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            deployCooldown = 5;
            armed = true;
        }
    }

    /** Hands the controls back: tungsten's navigation and follow, altoclef's task, a show. */
    private static void stopEverything() {
        try {
            kaptainwutax.tungsten.TungstenMod.stopNavigation();
            if (kaptainwutax.tungsten.task.PunkPlayerTask.isActive()) kaptainwutax.tungsten.task.PunkPlayerTask.stop();
            if (kaptainwutax.tungsten.task.FollowPlayerTask.isActive()) kaptainwutax.tungsten.task.FollowPlayerTask.stop();
            else if (kaptainwutax.tungsten.task.FollowEntityTask.isActive()) kaptainwutax.tungsten.task.FollowEntityTask.stop();
            adris.altoclef.AltoClef.getInstance().getCommandExecutor().executeWithPrefix("stop");
        } catch (Exception e) {
            adris.altoclef.Debug.logInternal("hand stop: " + e);
        }
        Salute.stop();
        CreativeWingman.stop("by hand");
    }

    /** Physical input from the player: W/A/S/D held, read from the window, so keys the bot
     *  presses itself do not count. Not the mouse: looking around while the bot walks or flies
     *  is watching, not taking over (and closing a screen jumps the cursor, which read as a flick).
     *  Not Esc, menus, the map or any other key either. */
    static boolean manualInput(MinecraftClient client) {
        if (client.player == null || client.currentScreen != null || !client.isWindowFocused()) return false;
        var o = client.options;
        for (KeyBinding kb : new KeyBinding[]{o.forwardKey, o.backKey, o.leftKey, o.rightKey}) {
            InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(kb);
            if (key.getCategory() == InputUtil.Type.KEYSYM && key.getCode() > 0 && InputHelper.isKeyPressed(key.getCode())) {
                return true;
            }
        }
        return false;
    }

    /** Land the glide at a spot ahead along the way it is going. */
    private static void startRescue(MinecraftClient client, ClientPlayerEntity p, double height) {
        var v = p.getVelocity();
        double hs = Math.hypot(v.x, v.z);
        double ahead = Math.max(14, Math.min(60, height * 2.5));
        double dirX, dirZ;
        if (hs > 0.1) {
            dirX = v.x / hs;
            dirZ = v.z / hs;
        } else { // straight down: wherever the bot faces
            double yawRad = Math.toRadians(p.getYaw());
            dirX = -Math.sin(yawRad);
            dirZ = Math.cos(yawRad);
        }
        tx = p.getX() + dirX * ahead;
        tz = p.getZ() + dirZ * ahead;
        ty = top(client, tx, tz);
        ticks = takeoffTicks = boostCooldown = rocketsUsed = glideWait = 0;
        bestDist = Double.MAX_VALUE;
        bestTick = 0;
        pilot.resetForLanding(p.getYaw(), p.getPitch());
        pilotReady = true;
        lastOut = null;
        rescue = true;
        lowLevel = false;
        route = null;
        tricksWanted = 0;
        armed = false;
        reason = "rescue";
        phase = Phase.FLYING;
    }

    /** One wingman tick; false when the leader landed or vanished and a normal landing follows. */
    private static boolean wingman(MinecraftClient client, ClientPlayerEntity p, net.minecraft.util.math.Vec3d v) {
        var lead = findPlayer(client, leader);
        if (lead == null) { // out of render distance: land where they were last seen
            leader = null;
            return false;
        }
        double lx = lead.getX(), ly = lead.getY(), lz = lead.getZ();
        if (leaderSeen) { // smoothed: position updates come in steps
            lvx += ((lx - lastLx) - lvx) * 0.3;
            lvy += ((ly - lastLy) - lvy) * 0.3;
            lvz += ((lz - lastLz) - lvz) * 0.3;
        } else {
            lvx = lvy = lvz = 0;
            leaderSeen = true;
        }
        lastLx = lx;
        lastLy = ly;
        lastLz = lz;
        tx = lx;
        ty = ly;
        tz = lz;
        // Up there on an elytra or in creative flight; landed only when neither (with a second's
        // grace: creative flight touches nothing for a tick between hops).
        boolean up = adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(lead)
                || CreativeWingman.hovering(client, lead);
        leaderDownTicks = up ? 0 : leaderDownTicks + 1;
        boolean gliding = adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(lead);
        if (gliding || Math.hypot(Math.hypot(lx - stillX, lz - stillZ), ly - stillY) > 3) {
            stillX = lx;
            stillY = ly;
            stillZ = lz;
            leaderStillTicks = 0;
        } else {
            leaderStillTicks++;
        }
        if (leaderDownTicks > 20 || leaderStillTicks > LEADER_STILL_LAND) {
            // The leader landed, or hangs in one place: put down a few blocks to their right, on
            // the ground there.
            double h = Math.max(Math.hypot(lvx, lvz), 1e-6);
            tx = lx - lvz / h * 4;
            tz = lz + lvx / h * 4;
            // A pursuer low and fast by the leader when the leader sets down had the pilot circle a
            // block over the ground on a rocket a second (watched on 07.11): this flight is a landing
            // now, and a landing gets no rockets (see the end of fly). Moving the spot on along the
            // track to where a plane-like approach fits put the pursuers down 90-180 blocks from the
            // leader instead: not flying together.
            landingAfterFollow = true;
            ty = top(client, tx, tz);
            leader = null;
            pilot.resetForLanding(p.getYaw(), p.getPitch());
            return false;
        }
        ElytraPilot.Out o = pilot.stepFollow(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, lx, ly, lz, lvx, lvy, lvz,
                terrainAhead(client, p), top(client, p.getX(), p.getZ()), obstacleAhead(client, p));
        double gapNow = Math.hypot(Math.hypot(lx - p.getX(), lz - p.getZ()), ly - p.getY());
        countLeaderRockets(client, lead);
        // Flying with someone is spending what they spend: a rocket is energy, and the leader's
        // rockets are what keeps it ahead. Priced as on a trip of its own the solver let the leader
        // go (38-49 blocks behind on 9 rockets against the leader's 6-7, 01.11), and a price that
        // fell with the gap alone did not bring it back (36-60 behind, 07.11). So the leader's
        // rockets set the budget: while the bot has fired fewer, a rocket is all but free and the
        // solver puts it where it gains the most; even with the leader, it pays the trip's price,
        // less the further behind (two over the leader at most); the pilot in formation fires no
        // more than one over the leader.
        int owed = leaderRockets - rocketsUsed;
        if (solverOn() && gapNow > FORMATION) {
            double scale = owed > 0 ? 0.1 : -owed < 2 ? Math.min(1, FORMATION / gapNow) : 1;
            // The slot's height is part of the slot: a block off it costs a block of the way.
            steerTo(client, p, o, followSlot(client, p, lx, ly, lz), 1.0, 1.5, scale);
        } else if (solverSteered) {
            solverSteered = false;
        }
        if (o.boost && !solverSteered && solverOn() && owed < 0) o.boost = false;
        lastOut = o;
        p.setYaw(o.yaw);
        p.setPitch(o.pitch);
        if (o.boost && !boost(client, p)) pilot.boostFailed();
        return true;
    }

    /** Nearest column within radius with open sky at about the bot's level (no canopy, no roof,
     *  ground within 3 blocks up or down): a place to take off from. In creative, failing that,
     *  an open column lower down. Null when none. */
    private static net.minecraft.util.math.BlockPos nearestOpenSky(MinecraftClient client, ClientPlayerEntity p, int radius) {
        var level = nearestOpenSky(client, p, radius, false);
        if (level != null || !p.getAbilities().creativeMode) return level;
        // Creative takes no fall damage: from blocks hanging under a platform every column
        // around drops far down, and walking off that edge is a fine way out to the sky.
        return nearestOpenSky(client, p, radius, true);
    }

    /** One pass of nearestOpenSky; drops accepts open columns any depth below the bot. */
    private static net.minecraft.util.math.BlockPos nearestOpenSky(MinecraftClient client, ClientPlayerEntity p,
                                                                     int radius, boolean drops) {
        int px = p.getBlockX(), pz = p.getBlockZ();
        for (int r = 1; r <= radius; r++) {
            net.minecraft.util.math.BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = px + dx, z = pz + dz;
                    if (!client.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                    int topY = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                    // Open: nothing above a one-block step over the feet (a low roof next to the
                    // bot is no way out), and not a pit far below (unless drops are fine).
                    if (topY > p.getY() + 1 || (!drops && topY < p.getY() - 3)) continue;
                    // No ground at all is the void, not a way out.
                    if (drops && topY <= client.world.getBottomY()) continue;
                    var ground = client.world.getBlockState(new net.minecraft.util.math.BlockPos(x, topY - 1, z));
                    if (!ground.getFluidState().isEmpty() || ground.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) continue;
                    double d = dx * dx + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = new net.minecraft.util.math.BlockPos(x, topY, z);
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Ground height at x,z from the heightmap; the world bottom where the chunk is not loaded. */
    private static double top(MinecraftClient client, double x, double z) {
        return client.world.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
    }

    /** Land on the ground at the target when its chunk is loaded, else at the height asked for.
     *  The ground is the top of the column -- unless the route search is on and there is a roof
     *  over the height asked for: then the floor under that height (the way in is searched). The
     *  top alone made a target inside a hall a landing on its roof, and the maze "passed" (01.11). */
    private static double landingY(MinecraftClient client) {
        int cx = (int) Math.floor(tx) >> 4, cz = (int) Math.floor(tz) >> 4;
        if (!client.world.getChunkManager().isChunkLoaded(cx, cz)) return ty;
        double top = top(client, tx, tz);
        if (!routeSearchOn() || top <= ty + 3) return top;
        int x = (int) Math.floor(tx), z = (int) Math.floor(tz);
        for (int y = (int) Math.floor(ty) + 2; y > ty - 16; y--) {
            if (solidAt(client, x, y - 1, z) && !solidAt(client, x, y, z) && !solidAt(client, x, y + 1, z)) return y;
        }
        return top;
    }

    private static boolean solidAt(MinecraftClient client, int x, int y, int z) {
        var pos = new net.minecraft.util.math.BlockPos(x, y, z);
        return !client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty();
    }

    /** Nothing standing into a 7-degree approach over the last 50 blocks: land like a plane. */
    private static boolean runwayClear(MinecraftClient client, ClientPlayerEntity p) {
        double ly = landingY(client);
        double dx = p.getX() - tx, dz = p.getZ() - tz, d = Math.max(Math.hypot(dx, dz), 1e-6);
        double tan = Math.tan(Math.toRadians(7));
        for (double k = 2; k <= Math.min(50, d); k += 2) {
            double h = top(client, tx + dx / d * k, tz + dz / d * k);
            // Level ground is the runway itself; anything a block up, or into the slope, is not.
            if (h > ly + 0.9 && h > ly + k * tan - 1.5) return false;
        }
        return true;
    }

    /** Highest ground in a 5-block corridor every 2 blocks: 120 blocks along the line to the
     *  target and 60 along the current direction of flight (a turn flies an arc), and the target.
     *  Sparser sampling let a thin tower slip between two samples. */
    private static double terrainAhead(MinecraftClient client, ClientPlayerEntity p) {
        double dx = tx - p.getX(), dz = tz - p.getZ();
        var vel = p.getVelocity();
        if (lowLevel && !rescue) { // only the next second or so of the course: it hugs the ground
            double dist = Math.hypot(dx, dz), hs = Math.hypot(vel.x, vel.z), reach = Math.max(16, hs * 25);
            double m = corridor(client, p.getX(), p.getZ(), dx, dz, Math.min(reach, dist));
            if (hs > 0.2) m = Math.max(m, corridor(client, p.getX(), p.getZ(), vel.x, vel.z, reach));
            return dist < 60 ? Math.max(m, landingY(client)) : m;
        }
        double m = landingY(client);
        m = Math.max(m, corridor(client, p.getX(), p.getZ(), dx, dz, Math.min(120, Math.hypot(dx, dz))));
        var v = p.getVelocity();
        if (Math.hypot(v.x, v.z) > 0.2) m = Math.max(m, corridor(client, p.getX(), p.getZ(), v.x, v.z, 60));
        return m;
    }

    /** Low-level flight: the steepest flight-path angle that passes 4 blocks over every point of
     *  the ground in the next ~1.2 s along the way it flies (as ElytraPilotSim.lowGuide). */
    private static double lowGuide(MinecraftClient client, ClientPlayerEntity p) {
        var v = p.getVelocity();
        double hs = Math.hypot(v.x, v.z);
        double dx = hs > 0.2 ? v.x : tx - p.getX(), dz = hs > 0.2 ? v.z : tz - p.getZ();
        double d = Math.max(Math.hypot(dx, dz), 1e-6), ux = dx / d, uz = dz / d;
        double reach = Math.max(16, hs * 25), best = -90;
        for (double k = 2; k <= reach; k += 2) {
            double g = -1e9;
            for (double w = -2; w <= 2; w += 2) {
                double sx = p.getX() + ux * k - uz * w, sz = p.getZ() + uz * k + ux * w;
                if (!client.world.getChunkManager().isChunkLoaded((int) Math.floor(sx) >> 4, (int) Math.floor(sz) >> 4)) continue;
                g = Math.max(g, top(client, sx, sz));
            }
            if (g > -1e8) best = Math.max(best, Math.toDegrees(Math.atan2(g + 4 - p.getY(), k)));
        }
        return best;
    }

    private static double corridor(MinecraftClient client, double x, double z, double dx, double dz, double len) {
        double d = Math.max(Math.hypot(dx, dz), 1e-6), ux = dx / d, uz = dz / d, m = -1e9;
        for (double k = 0; k <= len; k += 2) {
            for (double w = -2; w <= 2; w += 2) {
                double sx = x + ux * k - uz * w, sz = z + uz * k + ux * w;
                if (!client.world.getChunkManager().isChunkLoaded((int) Math.floor(sx) >> 4, (int) Math.floor(sz) >> 4)) continue;
                m = Math.max(m, top(client, sx, sz));
            }
        }
        return m;
    }

    /** Distance to the first solid block along the velocity (rays through real block shapes, so
     *  bridges, overhangs and treetops count), ~1.5 s of flight ahead; the landing ground near
     *  the target does not count. Infinity when clear. */
    private static double obstacleAhead(MinecraftClient client, ClientPlayerEntity p) {
        var v = p.getVelocity();
        double sp = v.length();
        if (sp < 0.05) return Double.POSITIVE_INFINITY;
        var dir = v.multiply(1 / sp);
        double look = Math.max(12, sp * 30);
        double ly = landingY(client), best = Double.POSITIVE_INFINITY;
        for (double up : new double[] {0.1, 0.5, 0.9}) { // the gliding hitbox is 0.6 high: three rays
            var from = p.getPos().add(0, up, 0);
            var hit = client.world.raycast(new net.minecraft.world.RaycastContext(from, from.add(dir.multiply(look)),
                    net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, p));
            if (hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) continue;
            var at = hit.getPos();
            boolean landingGround = at.y <= ly + 1.5 && Math.hypot(tx - at.x, tz - at.z) < 30;
            if (!landingGround) best = Math.min(best, at.distanceTo(from));
        }
        return best;
    }

    private static void aim(ClientPlayerEntity p, double dx, double dz, float pitch) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYaw(yaw);
        p.setPitch(pitch);
    }

    /** Fire a rocket; false when it could not be used this tick (the pilot asks again). */
    private static boolean routeSearchOn() {
        if (!solverOn()) return false;
        if (routeSearchOverride != null) return routeSearchOverride;
        var mod = adris.altoclef.AltoClef.getInstance();
        return mod != null && mod.getModSettings().isElytraRouteSearch();
    }

    /** The way to the target through the air: started on a worker at the first gliding tick, circled
     *  out meanwhile; when it comes, its corners are flown as rings and it lands at the target. False
     *  once there is nothing to wait for (found, none, or failed: then the plain solver flies). */
    private static boolean searchRoute(MinecraftClient client, ClientPlayerEntity p) {
        var s = search;
        if (s == null) {
            finalX = tx;
            finalY = landingY(client);
            finalZ = tz;
            var world = client.world;
            var from = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
            var to = new net.minecraft.util.math.Vec3d(finalX, finalY + 10, finalZ);
            int minY = world.getBottomY(), maxY = (int) Math.max(p.getY(), finalY) + 80;
            searchState = "searching";
            search = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> kaptainwutax.tungsten.elytra.AirRoute.find(world, from, to, minY, maxY), SEARCH);
            return circle(client, p);
        }
        if (!s.isDone()) return circle(client, p);
        java.util.List<net.minecraft.util.math.Vec3d> corners;
        try {
            corners = s.getNow(null);
        } catch (Exception e) {
            corners = null;
        }
        if (corners == null || corners.isEmpty()) {
            searchState = "no way found";
            landAtTarget = true; // do not search again; fly the plain way
            return false;
        }
        double[][] pts = new double[corners.size()][];
        for (int i = 0; i < pts.length; i++) {
            var c = corners.get(i);
            pts[i] = new double[]{c.x, c.y, c.z};
        }
        searchState = pts.length + " corners";
        ringBest = Double.MAX_VALUE;
        ringBestTick = ticks;
        route = pts;
        routeIdx = 0;
        rings = true;
        landAtTarget = true;
        ringStart = new double[]{p.getX(), p.getY() + 0.3, p.getZ()};
        lastX = p.getX();
        lastY = p.getY() + 0.3;
        lastZ = p.getZ();
        return false;
    }

    /** Hold a wide turn at this height: a point 25 blocks off, 60 degrees right of the way it flies. */
    private static boolean circle(MinecraftClient client, ClientPlayerEntity p) {
        var v = p.getVelocity();
        double a = Math.atan2(v.z, v.x) + Math.toRadians(60);
        ElytraPilot.Out o = new ElytraPilot.Out();
        o.mode = ElytraPilot.Mode.CRUISE;
        o.yaw = p.getYaw();
        o.pitch = p.getPitch();
        steer(client, p, o, new net.minecraft.util.math.Vec3d(p.getX() + Math.cos(a) * 25, p.getY(), p.getZ() + Math.sin(a) * 25));
        lastOut = o;
        p.setYaw(o.yaw);
        p.setPitch(o.pitch);
        if (o.boost && !boost(client, p)) pilot.boostFailed();
        return true;
    }

    private static final int ROCKET_MIN = kaptainwutax.tungsten.elytra.ElytraPhysics.ROCKET_MIN_TICKS;

    /** A plan and the corridor it was searched in (a new corridor when the glider was off the old). */
    private record PlanJob(kaptainwutax.tungsten.elytra.AirPlan.Plan plan, kaptainwutax.tungsten.elytra.AirRoute.Corridor cor,
                           net.minecraft.util.math.Vec3d to) {
    }

    /** One tick of a planned flight (the route search's second layer). A worker searches the cells,
     *  their corridor and a plan in it, from the state a held look leads to; the look is held till
     *  the plan comes. False once it hands over: the goal reached (the pilot lands), or no plan to
     *  be had (the corner rings fly instead, with their own watchdog). */
    private static boolean flyAirPlan(MinecraftClient client, ClientPlayerEntity p) {
        trackPos(p);
        var here = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        if (planGoal == null) {
            finalX = tx;
            finalY = landingY(client);
            finalZ = tz;
            // 10 over the spot, but under whatever roof is over it: a forest's 9 blocks up put the goal
            // in its leaves, the cells made for the nearest open air, over the roof, and the bot came
            // in under it from the side, for the landing only (08.11).
            double gy = finalY + 10;
            for (int y = (int) Math.floor(finalY) + 2; y <= finalY + 12; y++) {
                if (solidAt(client, (int) Math.floor(finalX), y, (int) Math.floor(finalZ))) {
                    gy = Math.min(gy, Math.max(finalY + 1, y - 2.5));
                    break;
                }
            }
            planGoal = new net.minecraft.util.math.Vec3d(finalX, gy, finalZ);
            searchState = "searching";
            planBest = Double.MAX_VALUE;
            planBestTick = ticks;
        }
        if (here.distanceTo(planGoal) < PLAN_REACHED) { // over the goal: the pilot lands
            handOverToLanding(p);
            searchState = "flown, " + replans + " plans";
            return false;
        }
        // Progress: nearer the goal by 2 blocks every 6 s, or this is not working -- the corner
        // rings have their own watchdog and give up when boxed in (once 117 rockets went into
        // replanning from off the corridor). Nearer along the way, not as the crow flies: the way
        // back to a hall's door after a look along its outside leads away from the goal.
        var corNow = corridor;
        double d = here.distanceTo(planGoal);
        if (corNow != null && corNow.toGo(here) >= 0) { // the way left, and from a frontier on as the crow flies
            var end = corNow.goalCell();
            d = corNow.toGo(here) + (end.distanceTo(planGoal) < 4 ? 0 : end.distanceTo(planGoal));
        }
        if (d < planBest - 2) {
            planBest = d;
            planBestTick = ticks;
        } else if (ticks - planBestTick > 120) {
            return giveUpPlan("no progress");
        }
        // A plan that came in takes over at the tick it was planned from.
        var pj = planJob;
        if (pj != null && pj.isDone() && ticks >= planJobAt) {
            PlanJob r;
            try {
                r = pj.getNow(null);
            } catch (Exception e) {
                r = null;
            }
            planJob = null;
            var np = r == null ? null : r.plan();
            if (r != null && r.cor() != null && r.cor() != corridor) {
                corridor = r.cor();
                planBest = Double.MAX_VALUE; // a new way: progress counted along it from here
                planBestTick = ticks;
            }
            if (np != null && np.ok()) {
                airPlan = np.ticks();
                airPlanT0 = planJobAt;
                planMisses = 0;
                replans++;
                searchState = "plan: " + np.ticks().size() + " ticks, " + np.rockets() + " rockets";
                var e0 = np.ticks().get(0).pos();
                var e1 = np.ticks().get(np.ticks().size() - 1).pos();
                var e2 = np.ticks().get(Math.min(40, np.ticks().size() - 1)).pos();
                planNote(ticks + " plan " + np.ticks().size() + "t " + np.rockets() + "r " + np.expanded() + "s " + planMs + "ms"
                        + String.format(java.util.Locale.ROOT, " from %.0f,%.0f,%.0f via %.0f,%.0f,%.0f to %.0f,%.0f,%.0f cor %d",
                        e0.x, e0.y, e0.z, e2.x, e2.y, e2.z, e1.x, e1.y, e1.z, r.cor() == null ? -1 : r.cor().size())
                        + (r.cor() != null && r.cor().unknown() > 0 ? " unknown " + r.cor().unknown() : ""));
            } else {
                searchState = r == null ? "plan error" : r.cor() == null ? "no way found"
                        : "plan missed (" + (np == null ? "?" : np.expanded() + " states; " + np.why()) + ")";
                planNote(ticks + " " + searchState);
                // still flying an older plan (the safety check on it): a few misses are only that
                if (++planMisses >= 8) return giveUpPlan(searchState); // circling or on an older plan: both safe
            }
        }
        var ap = airPlan;
        int k = ticks - airPlanT0;
        if (ap == null) {
            // The first plan is planned from the state a held look leads to, and the look is held
            // till it comes. Where holding it would hit a block, circle on and try again.
            if (planJob == null) {
                holdYaw = p.getYaw();
                holdPitch = p.getPitch();
                if (!holdSafe(client, p)) {
                    if (ticks % 10 == 0) planNote(ticks + " hold unsafe, circling");
                    return circle(client, p);
                }
                planNote(ticks + " first plan asked");
                requestPlan(client, p, null, 0);
            }
            if (holdSafe(client, p)) {
                lastOut = cruiseOut(p);
                return true;
            }
            planJob = null; // the held look turned unsafe: what it was planned from will not be
            return circle(client, p);
        }
        double off = here.distanceTo(planAt(ap, k).pos());
        boolean ending = k + PLAN_LAT + 2 >= ap.size();
        var cor = corridor;
        boolean fresh = cor != null && cor.unknown() > 0 && k >= 40;
        if (planJob == null && (off > PLAN_DEV || ending || fresh)) {
            planNote(ticks + (ending ? " end of plan" : fresh ? " unknown cells " + cor.unknown()
                    : String.format(java.util.Locale.ROOT, " off %.1f at %d", off, k)));
            requestPlan(client, p, ap, k);
        }
        // Safety: the plan's next looks from where the glider really is. If they hit a block, the
        // solver steers this tick, through the plan a second on, at the plan two seconds on.
        int left = rocketLeftGuess();
        boolean safe = !planHits(client, p, ap, k, left);
        ElytraPilot.Out o = cruiseOut(p);
        if (safe) {
            var a = planAt(ap, k + 1);
            o.yaw = a.yaw();
            o.pitch = a.pitch();
            solverSteered = false;
            // the rocket in hand before it is due; used a tick early, as it pulls from the tick after
            for (int i = k + 1; i <= k + 4 && i < ap.size(); i++) {
                if (ap.get(i).fire()) holdInHotbar(client, p, Items.FIREWORK_ROCKET);
            }
            for (int i = k + 1; i <= k + 1 + ROCKET_LEAD && i < ap.size(); i++) {
                if (ap.get(i).fire() && firedAt != airPlanT0 + i && ticks - lastRocketTick >= ROCKET_MIN) {
                    if (boost(client, p)) firedAt = airPlanT0 + i;
                    break;
                }
            }
        } else {
            planNote(ticks + " unsafe at " + k);
            throughNext = planAt(ap, k + 20).pos();
            steerTo(client, p, o, planAt(ap, k + 40).pos(), 0.4, 1.0);
            throughNext = null;
            if (o.boost && !boost(client, p)) pilot.boostFailed();
            if (planJob == null) requestPlan(client, p, ap, k);
        }
        lastOut = o;
        p.setYaw(o.yaw);
        p.setPitch(o.pitch);
        return true;
    }

    /** The approach has begun: within LAND_FROM of the spot on the last leg, or over it. Not from the
     *  pilot's descent alone: it began 200 blocks out once, and three searches from there ran out of
     *  states (08.11). Not in a rescue, a pursuit, a ring course, or once no landing was to be had. */
    private static boolean landingDue(ElytraPilot.Out o, double dist) {
        if (!solverOn() || rescue || leader != null || rings || landGaveUp) return false;
        var r = route;
        if (r != null && routeIdx < r.length - 1) return false;
        if (landPlan != null || landJob != null) return true;
        return dist < LAND_FROM || o.mode == ElytraPilot.Mode.FLARE || o.mode == ElytraPilot.Mode.SINK;
    }

    /** Fly the landing plan this tick (true: the look in o is the plan's), asking for a new one when
     *  off it or at its end. Till the first plan comes the look it was planned from is held. */
    private static boolean flyLanding(MinecraftClient client, ClientPlayerEntity p, ElytraPilot.Out o) {
        trackPos(p);
        landVy = realVel(p).y;
        var lj = landJob;
        if (lj != null && lj.isDone() && ticks >= landJobAt) {
            kaptainwutax.tungsten.elytra.LandingPlan.Plan r;
            try {
                r = lj.getNow(null);
            } catch (Exception e) {
                r = null;
            }
            landJob = null;
            if (r != null && r.ok()) {
                landPlan = r.ticks();
                landPlanT0 = landJobAt;
                landMisses = 0;
                landPlans++;
                var t = r.touch();
                landTouch = t;
                landTouchAt = landJobAt + r.ticks().size() - 1;
                landState = String.format(java.util.Locale.ROOT, "plan %d: %d ticks, touch sinking %.2f, fall %.1f, stops %.1f off",
                        landPlans, r.ticks().size() - 1, t.vel().y, t.fall(), Math.hypot(t.stop().x - tx, t.stop().z - tz));
                planNote(ticks + " landing " + landState + " (" + r.expanded() + " states, " + landMs + " ms)");
            } else {
                landState = "no landing: " + (r == null ? "error" : r.why());
                planNote(ticks + " " + landState + " (" + landMs + " ms)");
                if (++landMisses >= LAND_MISSES) {
                    landGaveUp = true;
                    landPlan = null;
                    landState += "; the pilot lands";
                    pilot.resetForLanding(p.getYaw(), p.getPitch());
                    return false;
                }
            }
        }
        var lp = landPlan;
        if (lp == null) {
            // The first plan starts from the state the held look leads to: hold it while it comes,
            // unless holding it meets a block -- then the pilot flies and it is asked again.
            if (landJob == null) {
                holdYaw = p.getYaw();
                holdPitch = p.getPitch();
                requestLanding(client, p, null, 0);
            }
            // Near the ground a long held look always meets it: only the next half second counts here.
            var held = kaptainwutax.tungsten.elytra.ElytraPhysics.predict(client.world,
                    new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ()), realVel(p), holdYaw, holdPitch, 10, 0);
            if (held.hit()) return false; // the pilot flies; the plan, from a little off, is searched again when it comes
            o.yaw = holdYaw;
            o.pitch = holdPitch;
            o.boost = false;
            return true;
        }
        int k = ticks - landPlanT0;
        // Safety: the plan's next looks, flown from where the glider really is. Into a block across the
        // way (off the plan, a wall it was to clear): the solver flies this tick, through the plan a
        // second on (past the wall), and a new plan is asked for from the look flown now, not the
        // plan's. Flown blind, a plan 3.4 blocks off took the bot into a wall 12 blocks short of the
        // spot, 5.8 health; handed to the pilot, it flew into the wall twice in four runs, 13.7 health
        // and a death; asked from the plan's looks, every move from the new start hit the wall (08.11).
        if (landHits(client, p, lp, k)) {
            planNote(ticks + " landing unsafe at " + k);
            throughNext = planAt(lp, k + 20).pos();
            steerTo(client, p, o, planAt(lp, k + 40).pos(), 0.4, 1.0);
            throughNext = null;
            if (landJob == null) requestLanding(client, p, null, 0);
            return true;
        }
        var here = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        double off = here.distanceTo(planAt(lp, k).pos());
        boolean ending = k + 2 >= lp.size();
        double dev = k + LAND_END >= lp.size() ? LAND_DEV_END : LAND_DEV;
        if (landJob == null && (off > dev || ending)) {
            planNote(ticks + (ending ? " end of the landing plan" : String.format(java.util.Locale.ROOT, " off the landing %.1f at %d", off, k)));
            requestLanding(client, p, lp, k);
        }
        var a = planAt(lp, k + 1);
        o.yaw = a.yaw();
        o.pitch = a.pitch();
        o.boost = false; // a landing sheds energy
        // Past the touch and still up (it touches 2-3 ticks after the plan): the plan's last look,
        // a flare, held at speed lifted the bot 4 blocks and it floated 20 past the spot (08.11).
        // Nose a little down till the next plan comes: a block or two over the ground cannot hurt.
        if (k + 1 >= lp.size()) o.pitch = Math.max(o.pitch, 5f);
        // The plan said soft and the fall adds up anyway, a few blocks over the ground: pull the nose
        // up (the speed turns into lift) and ask for a plan from here.
        if (p.fallDistance > LAND_GUARD_FALL && realVel(p).y < -0.5
                && p.getY() - top(client, p.getX(), p.getZ()) < LAND_GUARD_HEIGHT) {
            o.pitch = Math.min(o.pitch, -20f);
            if (landJob == null) {
                planNote(String.format(java.util.Locale.ROOT, "%d landing sinking %.2f, fall %.1f: nose up", ticks, realVel(p).y, p.fallDistance));
                requestLanding(client, p, lp, k);
            }
        }
        return true;
    }

    /** Down from a landing plan: where and when against where and when the plan touched, for the bench. */
    private static void noteTouch(ClientPlayerEntity p) {
        var t = landTouch;
        if (t == null || landGaveUp) return;
        landTouch = null;
        double h = Math.max(Math.hypot(t.vel().x, t.vel().z), 1e-6);
        double dx = p.getX() - t.at().x, dz = p.getZ() - t.at().z;
        planNote(String.format(java.util.Locale.ROOT, "%d touched %d ticks %s the plan, %.1f along and %.1f across its touch, sinking %.2f (plan %.2f)",
                ticks, Math.abs(ticks - landTouchAt), ticks >= landTouchAt ? "after" : "before",
                (dx * t.vel().x + dz * t.vel().z) / h, (dz * t.vel().x - dx * t.vel().z) / h, landVy, t.vel().y));
    }

    /** The landing plan's looks for the next PLAN_SAFE ticks, flown from where the glider is, meet a
     *  block across the way, or come down on one above the plan's touchdown. The ground at the plan's
     *  height is no danger: that is a touchdown, early or not. Anything higher is: the wings close on
     *  a touch, and the bot that grazed the top of an 8 block wall slid off it and fell, 5 health (08.11). */
    private static boolean landHits(MinecraftClient client, ClientPlayerEntity p,
                                    java.util.List<kaptainwutax.tungsten.elytra.AirPlan.Tick> lp, int k) {
        var t = landTouch;
        double ground = t != null ? t.at().y : landingY(client);
        var pos = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        var vel = realVel(p);
        int left = landRocketLeft(client, p);
        for (int i = 1; i <= PLAN_SAFE; i++) {
            var a = planAt(lp, k + i);
            var st = kaptainwutax.tungsten.elytra.ElytraPhysics.tick(vel, a.yaw(), a.pitch(), left > 0);
            vel = st.motion();
            if (left > 0) left--;
            var next = pos.add(st.move());
            if (kaptainwutax.tungsten.elytra.ElytraPhysics.blocked(client.world, pos, next)) {
                return st.move().y >= 0 || pos.y > ground + 1 || kaptainwutax.tungsten.elytra.ElytraPhysics.blocked(client.world, pos,
                        new net.minecraft.util.math.Vec3d(next.x, pos.y, next.z));
            }
            pos = next;
        }
        return false;
    }

    /** Search a landing from the state the glider will be in when the search is done: the plan's looks
     *  (or the look held) flown on for as long as a search takes here. */
    private static void requestLanding(MinecraftClient client, ClientPlayerEntity p,
                                       java.util.List<kaptainwutax.tungsten.elytra.AirPlan.Tick> lp, int k) {
        var pos = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        var vel = realVel(p);
        float yaw = p.getYaw(), pitch = p.getPitch();
        int left = landRocketLeft(client, p); // the take-off's rocket on a short hop: flown out, not ignored
        int lat = Math.max(PLAN_LAT, Math.min(20, (int) Math.ceil(landMsAvg / 50.0) + 2));
        for (int i = 1; i <= lat; i++) {
            if (lp != null) {
                var a = planAt(lp, k + i);
                yaw = a.yaw();
                pitch = a.pitch();
            }
            var st = kaptainwutax.tungsten.elytra.ElytraPhysics.tick(vel, yaw, pitch, left > 0);
            vel = st.motion();
            if (left > 0) left--;
            var next = pos.add(st.move());
            if (kaptainwutax.tungsten.elytra.ElytraPhysics.blocked(client.world, pos, next)) break;
            pos = next;
        }
        var start = new kaptainwutax.tungsten.elytra.AirPlan.State(pos, vel, yaw, pitch, left);
        double fall = p.fallDistance;
        var spot = new net.minecraft.util.math.Vec3d(tx, landingY(client), tz);
        var world = client.world;
        // Each miss in a row doubles the reach: a soft landing farther off before none at all. Low and
        // slow over the spot every soft touch was 4-10 blocks off it, and the pilot landed 9 off (08.11).
        var opts = new kaptainwutax.tungsten.elytra.LandingPlan.Options();
        if (landPlans > 0) opts.reach *= 1 << Math.min(landMisses, 2); // not for a miss on the take-off
        landJobAt = ticks + lat;
        landJob = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            var r = kaptainwutax.tungsten.elytra.LandingPlan.plan(world, start, fall, spot, opts);
            landMs = (int) ((System.nanoTime() - t0) / 1_000_000);
            landMsAvg = landMsAvg * 0.7 + landMs * 0.3;
            return r;
        }, SEARCH);
    }

    /** No plan to be had: the searched way's corners as rings, as before the plans. */
    private static boolean giveUpPlan(String why) {
        planNote(ticks + " gave up: " + why);
        planGaveUp = true;
        airPlan = null;
        planJob = null;
        searchState = why + ", corners";
        return true;
    }

    /** The glider's motion as it really moved last tick when that is much less than its velocity:
     *  pressed against a wall, getVelocity() kept 0.85 a tick while it did not move at all, and every
     *  plan started from beyond the wall (01.11, the compact maze). */
    private static net.minecraft.util.math.Vec3d realVel(ClientPlayerEntity p) {
        var v = p.getVelocity();
        if (posPrev == null || posTick != ticks) return v;
        var moved = posNow.subtract(posPrev);
        return moved.length() < v.length() * 0.5 ? moved : v;
    }

    private static net.minecraft.util.math.Vec3d posPrev, posNow;
    private static int posTick = -1;

    /** Where the glider is this tick and was the last (once a tick). */
    private static void trackPos(ClientPlayerEntity p) {
        if (posTick == ticks) return;
        var here = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        posPrev = posTick == ticks - 1 ? posNow : null;
        posNow = here;
        posTick = ticks;
    }

    private static void planNote(String s) {
        var p = MinecraftClient.getInstance().player;
        if (p != null) {
            var v = p.getVelocity();
            s += String.format(java.util.Locale.ROOT, " @%.0f,%.0f,%.0f v%.2f", p.getX(), p.getY(), p.getZ(), v.length());
        }
        planNotes.addLast(s);
        while (planNotes.size() > 400) planNotes.pollFirst();
    }

    private static kaptainwutax.tungsten.elytra.AirPlan.Tick planAt(java.util.List<kaptainwutax.tungsten.elytra.AirPlan.Tick> ap, int i) {
        return ap.get(Math.max(0, Math.min(i, ap.size() - 1)));
    }

    /** Ticks the last rocket still pulls, as far as the bot can tell: it pulls 20 to 31. */
    /** Ticks the last rocket still pulls, for a landing: none once it is gone from the player, else
     *  as long as a rocket can (31 ticks). Its life is 20 to 31, and a guess short by one tick is the
     *  bad miss: the plan flares, nose up, where the rocket still pulls, and the bot climbed 1.5
     *  blocks and landed 20-30 off on a 50 block hop, two runs in three (08.11). Guessed long, the
     *  bot is lower and slower than the plan: it is off it, a new plan comes, and short is soft. */
    private static int landRocketLeft(MinecraftClient client, ClientPlayerEntity p) {
        int since = ticks - lastRocketTick;
        if (since >= ROCKET_MAX || !FlightLog.rocketOn(client, p)) return 0;
        return ROCKET_MAX - since;
    }

    private static final int ROCKET_MAX = kaptainwutax.tungsten.elytra.ElytraPhysics.ROCKET_MAX_TICKS;

    private static int rocketLeftGuess() {
        int since = ticks - lastRocketTick;
        return since < PLAN_PULL ? PLAN_PULL - since : 0;
    }

    /** Plan from the state the glider will be in PLAN_LAT ticks on, flying the old plan's looks
     *  (or holding the current one when there is none yet). The cells and their corridor are
     *  searched again from there when that state is off the corridor in use. */
    private static void requestPlan(MinecraftClient client, ClientPlayerEntity p,
                                    java.util.List<kaptainwutax.tungsten.elytra.AirPlan.Tick> ap, int k) {
        var pos = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        var vel = realVel(p);
        float yaw = p.getYaw(), pitch = p.getPitch();
        int left = rocketLeftGuess();
        // As far ahead as a plan takes to come: 6 ticks on an idle machine; three game clients and a
        // recording on one took 300-360 ms a plan, they came late and the glider was off them (01.11)
        int lat = Math.max(ap == null ? PLAN_LAT_FIRST : PLAN_LAT, Math.min(20, (int) Math.ceil(planMsAvg / 50.0) + 2));
        for (int i = 1; i <= lat; i++) {
            if (ap != null) {
                var a = planAt(ap, k + i);
                yaw = a.yaw();
                pitch = a.pitch();
            }
            var st = kaptainwutax.tungsten.elytra.ElytraPhysics.tick(vel, yaw, pitch, left > 0);
            vel = st.motion();
            if (left > 0) left--;
            var next = pos.add(st.move());
            if (kaptainwutax.tungsten.elytra.ElytraPhysics.blocked(client.world, pos, next)) { // stopped there
                vel = net.minecraft.util.math.Vec3d.ZERO;
                break;
            }
            pos = next;
        }
        var start = new kaptainwutax.tungsten.elytra.AirPlan.State(pos, vel, yaw, pitch, left);
        var goal = planGoal;
        var world = client.world;
        var cor0 = corridor;
        int minY = world.getBottomY(), maxY = (int) Math.max(p.getY(), finalY) + 40;
        double rocketTicks = rocketCost(p) / 1.5; // blocks of progress -> ticks of flight
        planJobAt = ticks + lat;
        planJob = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            long t0 = System.nanoTime();
            var to = cor0 == null || cor0.goalCell().distanceTo(goal) < 4 ? goal : cor0.goalCell();
            var cor = cor0;
            if (cor == null || cor.toGo(start.pos()) < 0 || cor.unknown() > 0) {
                var chain = kaptainwutax.tungsten.elytra.AirRoute.cells(world, start.pos(), goal, minY, maxY, true);
                if (chain == null) return new PlanJob(null, null, goal);
                cor = new kaptainwutax.tungsten.elytra.AirRoute.Corridor(world, chain, 2);
                // the goal, or the frontier when it is out of sight
                to = cor.goalCell().distanceTo(goal) < 4 ? goal : cor.goalCell();
            }
            var o = new kaptainwutax.tungsten.elytra.AirPlan.Options();
            o.rocketTicks = rocketTicks;
            o.pull = PLAN_PULL;
            o.weight = 2.5;
            // Room kept around the body: plenty first -- a plan along the edge of a gap was flown off
            // its edge by the 20..31 ticks of a rocket -- less only when there is no other way.
            kaptainwutax.tungsten.elytra.AirPlan.Plan r = null;
            for (double m : new double[]{1.2, 0.6, 0.3}) {
                o.margin = m;
                r = kaptainwutax.tungsten.elytra.AirPlan.plan(start, to, cor, o);
                if (r.ok()) break;
            }
            planMs = (int) ((System.nanoTime() - t0) / 1_000_000);
            planMsMax = Math.max(planMsMax, planMs);
            planMsAvg = planMsAvg * 0.7 + planMs * 0.3;
            return new PlanJob(r, cor, to);
        }, SEARCH);
    }

    /** The held look of the first plan, flown on from here, keeps clear of blocks. */
    private static boolean holdSafe(MinecraftClient client, ClientPlayerEntity p) {
        p.setYaw(holdYaw);
        p.setPitch(holdPitch);
        var path = kaptainwutax.tungsten.elytra.ElytraPhysics.predict(client.world,
                new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ()), realVel(p), holdYaw, holdPitch,
                PLAN_SAFE + PLAN_LAT_FIRST, rocketLeftGuess());
        return !path.hit();
    }

    /** The plan's looks for the next PLAN_SAFE ticks, flown from where the glider is, hit a block. */
    private static boolean planHits(MinecraftClient client, ClientPlayerEntity p,
                                    java.util.List<kaptainwutax.tungsten.elytra.AirPlan.Tick> ap, int k, int left) {
        var pos = new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ());
        var vel = realVel(p);
        for (int i = 1; i <= PLAN_SAFE; i++) {
            var a = planAt(ap, k + i);
            var st = kaptainwutax.tungsten.elytra.ElytraPhysics.tick(vel, a.yaw(), a.pitch(), left > 0);
            vel = st.motion();
            if (left > 0) left--;
            var next = pos.add(st.move());
            if (kaptainwutax.tungsten.elytra.ElytraPhysics.blocked(client.world, pos, next)) return true;
            pos = next;
        }
        return false;
    }

    private static ElytraPilot.Out cruiseOut(ClientPlayerEntity p) {
        ElytraPilot.Out o = new ElytraPilot.Out();
        o.mode = ElytraPilot.Mode.CRUISE;
        o.yaw = p.getYaw();
        o.pitch = p.getPitch();
        return o;
    }

    /** The way flown: over the target, the pilot's approach and landing from here. */
    private static void handOverToLanding(ClientPlayerEntity p) {
        landAtTarget = true;
        tx = finalX;
        ty = finalY;
        tz = finalZ;
        airPlan = null;
        planJob = null;
        solverSteered = false;
        pilot.resetForLanding(p.getYaw(), p.getPitch());
    }

    // Runtime overrides (MCP flySettings), never saved: several game clients share one
    // altoclef_settings.json, and @set saves all of a client's settings from its memory, so on a
    // bench of three clients the flags flipped under each other (and the MCP token with them).
    private static volatile Boolean solverOverride, routeSearchOverride, routePlanOverride;
    private static volatile Double rocketCostOverride;

    /** Set any of solver, routeSearch, rocketCost for this client only, not saved; null leaves one
     *  as it is. Returns what is in force now. */
    public static Map<String, Object> flySettings(Boolean solver, Boolean routeSearch, Boolean routePlan, Double rocketCost) {
        if (solver != null) solverOverride = solver;
        if (routeSearch != null) routeSearchOverride = routeSearch;
        if (routePlan != null) routePlanOverride = routePlan;
        if (rocketCost != null) rocketCostOverride = rocketCost;
        Map<String, Object> m = new HashMap<>();
        m.put("solver", solverOn());
        m.put("routeSearch", routeSearchOn());
        m.put("routePlan", routePlanOn());
        var p = MinecraftClient.getInstance().player;
        if (p != null) m.put("rocketCost", rocketCost(p));
        return m;
    }

    /** The route search flies a physics plan (on unless switched off for a comparison). */
    private static boolean routePlanOn() {
        return routePlanOverride == null || routePlanOverride;
    }

    private static boolean solverOn() {
        if (solverOverride != null) return solverOverride;
        var mod = adris.altoclef.AltoClef.getInstance();
        return mod != null && mod.getModSettings().isElytraSolver();
    }

    /** Where to chase a leader to: where they will be in a second, 6 blocks behind on their track and
     *  5 to one side -- the side this pursuer is already on (it changes only 3 blocks past the
     *  track), so two pursuers keep apart without crossing (on one point they flew wing in wing and
     *  landed 0.2 blocks apart, 01.11). Another glider within 10 blocks pushes the slot sideways only,
     *  6 blocks at most: pushed up and down as well, the pursuers climbed on rockets and fell 48
     *  blocks behind. The slot is smoothed, as the leader's position comes in steps. */
    private static net.minecraft.util.math.Vec3d followSlot(MinecraftClient client, ClientPlayerEntity p,
                                                            double lx, double ly, double lz) {
        double sp = Math.max(Math.hypot(Math.hypot(lvx, lvz), lvy), 1e-6), hs = Math.max(Math.hypot(lvx, lvz), 1e-6);
        double px = -lvz / hs, pz = lvx / hs; // across the leader's track
        double lat = (p.getX() - lx) * px + (p.getZ() - lz) * pz;
        if (slotSide == 0) slotSide = lat != 0 ? Math.signum(lat) : (p.getName().getString().hashCode() & 1) == 0 ? 1 : -1;
        else if (lat * slotSide < -3) slotSide = -slotSide;
        double side = 5 * slotSide;
        for (var other : client.world.getPlayers()) {
            if (other == p || other.getName().getString().equalsIgnoreCase(leader)
                    || !adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(other)) continue;
            double dx = p.getX() - other.getX(), dy = p.getY() - other.getY(), dz = p.getZ() - other.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 10 && d > 1e-3) {
                double across = dx * px + dz * pz;
                side += (across != 0 ? Math.signum(across) : slotSide) * Math.min(6, (10 - d) * 0.6);
            }
        }
        // Where the slot will be when the solver's look ahead ends: it scores where a flight is
        // after HORIZON ticks, and against a slot only 20 ticks ahead every fast way overshot it,
        // so the best it found was to climb the speed away -- 70 blocks over the leader (07.11).
        int h = kaptainwutax.tungsten.elytra.ElytraSolver.HORIZON;
        double gx = lx + lvx * h - lvx / sp * 6 + px * side;
        double gy = ly + lvy * h - lvy / sp * 6;
        double gz = lz + lvz * h - lvz / sp * 6 + pz * side;
        var raw = new net.minecraft.util.math.Vec3d(gx, gy, gz);
        var s = slotSmooth;
        slotSmooth = s == null || s.squaredDistanceTo(raw) > 400 ? raw : s.add(raw.subtract(s).multiply(0.25));
        return slotSmooth;
    }

    /** A firework rocket on the leader that was not there before is one the leader fired: the client
     *  sees a rocket used in flight ride along at its user's position. Only one nearer the leader than
     *  to anyone else counts: with two pursuers flying close, theirs were counted as the leader's,
     *  the debt never ran out, and one pursuer burned 28 rockets to the leader's 6 (07.11). */
    private static void countLeaderRockets(MinecraftClient client, net.minecraft.entity.player.PlayerEntity lead) {
        for (var r : client.world.getEntitiesByClass(net.minecraft.entity.projectile.FireworkRocketEntity.class,
                lead.getBoundingBox().expand(1.5), e -> true)) {
            double d = r.squaredDistanceTo(lead);
            boolean theirs = d < 1.5 * 1.5;
            for (var other : client.world.getPlayers()) {
                if (other != lead && r.squaredDistanceTo(other) < d) theirs = false;
            }
            if (theirs && leaderRocketIds.add(r.getId())) leaderRockets++;
        }
    }

    /** A rocket's price for the solver, in blocks of progress (see Settings.elytraRocketCost). */
    private static double rocketCost(ClientPlayerEntity p) {
        var mod = adris.altoclef.AltoClef.getInstance();
        Double over = rocketCostOverride;
        double set = over != null ? over : mod == null ? -1 : mod.getModSettings().getElytraRocketCost();
        if (set >= 0) return set;
        return p.getAbilities().creativeMode ? 12 : 40;
    }

    /** En route: a point up to 60 blocks ahead on the way to the target, at the pilot's cruise height
     *  over the highest ground of the corridor (4 blocks over it flying low), so it takes over the
     *  approach from where it would have been itself. */
    private static net.minecraft.util.math.Vec3d goalAhead(MinecraftClient client, ClientPlayerEntity p, double dist) {
        double dx = tx - p.getX(), dz = tz - p.getZ(), k = Math.min(60, dist) / Math.max(dist, 1e-6);
        double y = terrainAhead(client, p) + (lowLevel ? 4 : ElytraPilot.CLEARANCE);
        return new net.minecraft.util.math.Vec3d(p.getX() + dx * k, y, p.getZ() + dz * k);
    }

    /** The solver flies this tick: a fresh plan every other tick, the look turned towards it at a
     *  hand's pace, a rocket only when the plan just asked for one. */
    private static void steer(MinecraftClient client, ClientPlayerEntity p, ElytraPilot.Out o,
                              net.minecraft.util.math.Vec3d goal) {
        steerTo(client, p, o, goal, 0.4, 1.5);
    }

    private static void steerTo(MinecraftClient client, ClientPlayerEntity p, ElytraPilot.Out o,
                                net.minecraft.util.math.Vec3d goal, double heightWeight, double clearance) {
        steerTo(client, p, o, goal, heightWeight, clearance, 1);
    }

    /** rocketScale: the share of the usual rocket price this flight pays (pursuit, behind). */
    private static void steerTo(MinecraftClient client, ClientPlayerEntity p, ElytraPilot.Out o,
                                net.minecraft.util.math.Vec3d goal, double heightWeight, double clearance,
                                double rocketScale) {
        boolean fresh = false;
        if (plan == null || ticks - planTick >= 2) {
            var g = new kaptainwutax.tungsten.elytra.ElytraSolver.Goal();
            g.point = goal;
            g.rocketCost = rocketCost(p) * rocketScale;
            g.heightWeight = heightWeight;
            g.clearance = clearance;
            g.through = throughNext;
            int since = ticks - lastRocketTick;
            g.mayFire = findItem(p, Items.FIREWORK_ROCKET) >= 0
                    && since >= kaptainwutax.tungsten.elytra.ElytraPhysics.ROCKET_MIN_TICKS;
            int left = Math.max(0, kaptainwutax.tungsten.elytra.ElytraPhysics.ROCKET_MIN_TICKS - since);
            var pl = kaptainwutax.tungsten.elytra.ElytraSolver.solve(client.world,
                    new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ()), p.getVelocity(), left, g);
            if (pl != null) {
                plan = pl;
                planTick = ticks;
                fresh = true;
            }
        }
        if (plan == null) return;
        solverSteered = true;
        o.yaw = p.getYaw() + Math.max(-25, Math.min(25,
                net.minecraft.util.math.MathHelper.wrapDegrees(plan.yaw() - p.getYaw())));
        o.pitch = p.getPitch() + Math.max(-15, Math.min(15, plan.pitch() - p.getPitch()));
        // Boxed in, every candidate hits: the one hitting latest is flown, a rocket preferred -- but
        // not one every half second (120 went into a hall's corner).
        o.boost = fresh && plan.fire() && (!plan.cornered() || ticks - lastRocketTick >= 40);
        // Under 0.9 blocks a tick a glide stalls within seconds, whatever the score says: with
        // waypoints 28 blocks apart a rocket's extra reach counted for nothing and it sank from
        // 1.65 to 0.2 on the climb to a high gap (01.11, the maze).
        var hv = p.getVelocity();
        if (fresh && !o.boost && Math.hypot(hv.x, hv.z) < 0.9 && ticks - lastRocketTick >= ROCKET_MIN
                && findItem(p, Items.FIREWORK_ROCKET) >= 0) {
            o.boost = true;
        }
    }

    private static boolean boost(MinecraftClient client, ClientPlayerEntity p) {
        // A rocket used on the ground just flies off on its own: only while gliding.
        if (boostCooldown > 0 || !adris.altoclef.multiversion.entity.LivingEntityVer.isGliding(p)) return false;
        if (!holdInHotbar(client, p, Items.FIREWORK_ROCKET)) {
            reason = "out of fireworks";
            return false;
        }
        // The swap into the hotbar lands next tick: use the rocket only once it is in hand.
        if (!p.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) return false;
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        rocketsUsed++;
        lastRocketTick = ticks;
        FlightViz.rocket(p.getX(), p.getY(), p.getZ());
        boostCooldown = 10; // the pilot paces rockets; this only stops double clicks
        return true;
    }

    /** Inventory index (0..35) of the item, or -1. */
    private static int findItem(ClientPlayerEntity p, Item item) {
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    /** Make the item the selected hotbar stack, moving it from the main inventory if needed. */
    private static boolean holdInHotbar(MinecraftClient client, ClientPlayerEntity p, Item item) {
        int i = findItem(p, item);
        if (i < 0) return false;
        if (i >= 9) {
            // Swap the stack into hotbar slot 8: player screen slot i, hotbar button 8.
            client.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, 8, SlotActionType.SWAP, p);
            i = 8;
        }
        PlayerVer.setSelectedSlot(p.getInventory(), i);
        return true;
    }
}
