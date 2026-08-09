package kaptainwutax.tungsten.combat;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

/**
 * Every hit the bot takes, recorded from the CLIENT TICK so it cannot be switched off by whoever
 * happens to be driving.
 *
 * <p>WHY THIS EXISTS WHEN altoclef ALREADY TRACKS DAMAGE. {@code MobDefenseChain} has a thorough
 * version of this — total damage, hit count, which side the hits land on, the range they land at —
 * fed from {@code getPriority()}. On the pvp courses that call tungsten primitives directly
 * ({@code runAwayPlayer}, {@code shootArrowAt}) the altoclef chain loop never runs, so
 * {@code mdCalls} reads 0 and with it every one of those counters. Measured on bow_flee: five
 * deaths in a single course and {@code dmgTaken=0.0 hits=0/0/0/0}. The instrument was not saying
 * "nothing hit the bot"; it was saying nothing was measuring.
 *
 * <p>That is a structural hole rather than a bow_flee detail: ANY question of the form "what is
 * damaging the bot" is unanswerable on a course the agent drives itself, which is the direction
 * this project is heading. So the reading lives beside the tungsten tasks, on the tick that always
 * fires.
 *
 * <p>The gap at the moment of the hit is the number the flee work actually needs. Average distance
 * says how well the bot keeps away in general; it moved from 6.1 to 9.4 across tonight's changes
 * without deaths shifting from 5. What matters is how close the attacker was when it connected, and
 * how many separate times that happened.
 */
public final class DamageWatch {

    /** Hits taken and total health lost this run. */
    public static volatile int hits;
    public static volatile double damage;
    /** Centre-to-centre gap to the nearest other living entity when a hit landed. */
    public static volatile double gapSum, gapMax;
    /** Hits that landed while the gap was already beyond melee reach — i.e. not a sword. */
    public static volatile int rangedHits;
    /**
     * Health reaching zero, counted here so the damage total can be checked against reality.
     *
     * <p>It does not currently add up. With regeneration off and respawn restoring full health,
     * thirteen deaths on allround need at least 13 x 20 = 260 damage, and this class reported 137.
     * Either it misses hits, or it misses the final drop, or the bench and the client disagree about
     * what a death is. Rather than pick one of those and build on it, count the deaths this class
     * can SEE and compare with the scoreboard the bench reads: equal counts move the discrepancy
     * onto the per-hit accounting, unequal counts move it onto the death path itself.
     */
    public static volatile int deathsSeen;

    private static float lastHealth = -1f;

    /**
     * WHERE AND WHEN THE BOT GOES OVER THE RIM — recorded UNCONDITIONALLY, on the tick that always
     * fires.
     *
     * <p>Two fixes were spent on the void guard without this. Both failed, and both failed the same
     * way: every edge counter that existed lived inside PunkPlayerTask, below its {@code if
     * (!active)} return, so all of them were blind during the phase the falls were most likely
     * happening in. Guarding harder is not the next move; knowing the event is.
     *
     * <p>{@code voidTicks} counts ticks spent airborne over a bottomless column (fallHeight > 20,
     * this file's established "genuine void" discriminator). {@code voidEntries} counts distinct
     * departures rather than ticks. {@code lastFall} captures the state at the first tick of a
     * departure — position, velocity, whether we were on the ground the tick before, and which
     * tasks were live — which is the datum no counter so far has produced.
     */
    public static volatile int voidTicks, voidEntries;
    /**
     * THE HURT WINDOW, COUNTED WHERE IT CANNOT BE MISSED.
     * hurtTime is non-zero for ~10 ticks after every hit. A first attempt put this inside
     * CombatController's reach-control branch and read 12 ticks against 30 hits taken — it sat
     * behind an early return and in a method that does not run every combat tick, so the split it
     * produced described an unknown slice. Caught by comparing against dwHits before concluding.
     * Here it rides the unconditional client tick, same as the fall recorder, so hurtWindow is the
     * true total; hurtWhileControlled counts the subset where the close-quarters controller also
     * ran, which makes the coverage visible instead of hiding it.
     */
    public static volatile int hurtWindow, hurtWhileControlled;
    /**
     * AN HONEST REASON TO BREAK CONTACT — computed here, deliberately NOT wired to behaviour yet.
     *
     * <p>The bot has none today: combat reads no hit event at all and reacts only to hp <= LOW_HP,
     * which measurement showed fires about eleven ticks before each death. Its only caution comes
     * from an INFLATED knockback estimate reading a phantom cliff, and removing that was measured
     * harmful twice (16 -> 23 and 15 -> 19 deaths) — it is load-bearing. So the order has to be: add
     * a real trigger, prove it fires at sensible moments, THEN correct the fake one.
     *
     * <p>losing = more hits taken than landed inside a rolling window. disengageTicks counts how long
     * that has been true, so the next pass can see WHEN it would have fired before anything acts on
     * it. Nothing reads these yet, on purpose.
     */
    public static volatile int disengageTicks, disengageSpells;
    private static int winTaken, winLanded, winAge;
    private static boolean losingPrev;
    /** ~3 s at 20 tps: long enough to be a trade rather than a single unlucky hit. */
    private static final int WINDOW_TICKS = 60;
    public static volatile String lastFall = "";
    private static int lastLanded = 0;
    private static boolean overVoid = false;
    private static boolean wasOnGround = true;

    /** Beyond this a melee weapon cannot reach, so the damage came from something else. */
    private static final double MELEE_REACH = 4.5;

    private DamageWatch() {}

    public static void reset() {
        hits = 0;
        damage = 0;
        gapSum = 0;
        gapMax = 0;
        rangedHits = 0;
        deathsSeen = 0;
        lastHealth = -1f;
        voidTicks = 0;
        voidEntries = 0;
        hurtWindow = 0;
        hurtWhileControlled = 0;
        disengageTicks = 0;
        disengageSpells = 0;
        winTaken = 0; winLanded = 0; winAge = 0; lastLanded = 0; losingPrev = false;
        lastFall = "";
        overVoid = false;
        wasOnGround = true;
    }

    /** Called every client tick from MixinClientPlayerEntity, before anything can decline to run. */
    public static void tick(ClientPlayerEntity player) {
        if (player == null) {
            lastHealth = -1f;
            return;
        }
        float hp = player.getHealth();
        // A respawn restores health upward, which is not a hit; only drops are counted. lastHealth
        // is re-seeded every tick so a death simply starts the next life's accounting.
        if (lastHealth > 0f && hp <= 0f) deathsSeen++;
        if (lastHealth >= 0f && hp < lastHealth) {
            hits++;
            winTaken++;
            damage += lastHealth - hp;
            double gap = nearestLivingGap(player);
            if (gap >= 0) {
                gapSum += gap;
                if (gap > gapMax) gapMax = gap;
                if (gap > MELEE_REACH) rangedHits++;
            }
        }
        lastHealth = hp;
        // rolling exchange window — taken vs landed, both from counters that already work
        if (++winAge >= WINDOW_TICKS) { winAge = 0; winTaken = 0; winLanded = 0; }
        int landedNow = kaptainwutax.tungsten.combat.TriggerBot.lifetimeHits;
        if (landedNow > lastLanded) winLanded += landedNow - lastLanded;
        lastLanded = landedNow;
        boolean losing = winTaken > winLanded && winTaken >= 2;
        if (losing) {
            disengageTicks++;
            if (!losingPrev) disengageSpells++;
        }
        losingPrev = losing;
        if (player.hurtTime > 0) {
            hurtWindow++;
            if (kaptainwutax.tungsten.combat.CombatController.controlledThisTick) hurtWhileControlled++;
        }
        // CONSUME THE FLAG, or it latches true after the first controlled tick and the coverage
        // ratio reads 100% forever — a counter lying in a new way instead of the old one. This tick
        // runs BEFORE the controller in MixinClientPlayerEntity, so the value read above is last
        // tick's, which is exactly the right question ("was the controller running around then").
        kaptainwutax.tungsten.combat.CombatController.controlledThisTick = false;
        recordVoid(player);
    }

    /** Unconditional rim recorder — see the field docs. Runs whatever task is or is not driving. */
    private static void recordVoid(ClientPlayerEntity player) {
        boolean airborne = !player.isOnGround();
        boolean bottomless = airborne
                && VoidDetector.fallHeight(player.getEntityPos(), player.getEntityWorld()) > 20;
        if (bottomless) {
            voidTicks++;
            if (!overVoid) {
                voidEntries++;
                net.minecraft.util.math.Vec3d p = player.getEntityPos();
                net.minecraft.util.math.Vec3d v = player.getVelocity();
                lastFall = String.format(
                        "pos=%.1f,%.1f,%.1f vel=%.3f,%.3f,%.3f wasOnGround=%b sneak=%b"
                                + " punk=%b flee=%b bow=%b exec=%b walker=%b",
                        p.x, p.y, p.z, v.x, v.y, v.z, wasOnGround,
                        net.minecraft.client.MinecraftClient.getInstance().options.sneakKey.isPressed(),
                        kaptainwutax.tungsten.task.PunkPlayerTask.isActive(),
                        kaptainwutax.tungsten.task.RunAwayTask.isActive(),
                        kaptainwutax.tungsten.task.BowShooter.isActive(),
                        kaptainwutax.tungsten.TungstenModDataContainer.isExecutorRunning(),
                        kaptainwutax.tungsten.task.BlockPathWalker.isRunning());
            }
        }
        overVoid = bottomless;
        wasOnGround = player.isOnGround();
    }

    /** Distance to the closest other living entity, or -1 when there is none to blame. */
    private static double nearestLivingGap(ClientPlayerEntity player) {
        double best = -1;
        // ClientWorld, not World: only the client side exposes the entity list here.
        net.minecraft.client.world.ClientWorld world =
                net.minecraft.client.MinecraftClient.getInstance().world;
        if (world == null) return -1;
        for (Entity e : world.getEntities()) {
            if (e == player || !(e instanceof LivingEntity) || !e.isAlive()) continue;
            double d = e.getEntityPos().distanceTo(player.getEntityPos());
            if (best < 0 || d < best) best = d;
        }
        return best;
    }
}
