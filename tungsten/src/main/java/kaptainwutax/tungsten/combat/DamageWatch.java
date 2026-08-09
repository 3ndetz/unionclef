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

    private static float lastHealth = -1f;

    /** Beyond this a melee weapon cannot reach, so the damage came from something else. */
    private static final double MELEE_REACH = 4.5;

    private DamageWatch() {}

    public static void reset() {
        hits = 0;
        damage = 0;
        gapSum = 0;
        gapMax = 0;
        rangedHits = 0;
        lastHealth = -1f;
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
        if (lastHealth >= 0f && hp < lastHealth) {
            hits++;
            damage += lastHealth - hp;
            double gap = nearestLivingGap(player);
            if (gap >= 0) {
                gapSum += gap;
                if (gap > gapMax) gapMax = gap;
                if (gap > MELEE_REACH) rangedHits++;
            }
        }
        lastHealth = hp;
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
