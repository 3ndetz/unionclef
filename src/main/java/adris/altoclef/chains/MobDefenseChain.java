package adris.altoclef.chains;

import adris.altoclef.control.Nav;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.KillAura;
import adris.altoclef.multiversion.entity.LivingEntityVer;
import adris.altoclef.multiversion.versionedfields.Entities;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.tasks.construction.ProjectileProtectionWallTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.entity.CombatTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasks.movement.DodgeProjectilesTask;
import adris.altoclef.tasks.movement.IdleTask;
import adris.altoclef.tasks.movement.RunAwayFromCreepersTask;
import adris.altoclef.tasks.movement.RunAwayFromEntitiesTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.tasks.speedrun.DragonBreathTracker;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.*;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import kaptainwutax.tungsten.path.movements.Rotation;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.*;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;


import java.util.*;


// TODO: Optimise shielding against spiders and skeletons

public class MobDefenseChain extends SingleTaskChain {

    /** How often this chain is asked, wins, flees or fights; read over py4j in placeStats(). */
    public static volatile int mdPriorityCalls, mdWon, mdFlee, mdFight;
    /**
     * One counter per positive-priority exit of getPriorityInner.
     *
     * <p>The chain wins the tick on 41% of ticks yet never flees and never fights, so it is
     * leaving through some other door -- and there are eight. Counting them apart is the only
     * way to know which, and guessing has gone one for eight this session.
     */
    public static volatile int mdRet0, mdRet1, mdRet2, mdRet3, mdRet4, mdRet5, mdRet6, mdRet7, mdRet8, mdRet9;
    /** Times the flee reflex was declined because the thing endangering us shoots. */
    public static volatile int mdFleeShooter;
    /** Committed-fight ticks where the target was beyond the 4.5 gate, and the last such gap (x1000). */
    public static volatile int mdFarTicks, mdFarGapMilli;
    /**
     * WHERE THE SKELETON SHOOTS FROM, which is not the same question as where its arrows land.
     *
     * <p>Measured on the repaired course, arrows that HIT land at a gap of 3.79-5.03 with a
     * maximum of 6.30 — point blank, past what any dodge can beat at an arrow's speed. But a
     * skeleton shoots from up to fifteen blocks, and the bot spends most of the fight further out
     * than six. So one of two things is true and the landing figure cannot tell them apart:
     * either the distant shots MISS (the bot's jitter has already been measured as a defence), or
     * the skeleton is not shooting at range at all.
     *
     * <p>The difference decides what is worth fixing. If distant shots miss, the lever is TIME
     * SPENT INSIDE the killing band and nothing else. If it holds fire until close, then the band
     * is where it always fights and closing faster buys nothing — the lever is denying the shot.
     *
     * <p>onProjectileLaunched fires at the moment of release, when the gap is still the gap it was
     * fired from. Counted in millis for the same reason mdFarGapMilli is: these are ints crossing
     * py4j, and a mean of a few blocks needs the resolution.
     */
    public static volatile int mdArrows, mdArrowGapMilli, mdArrowGapMaxMilli;
    /**
     * THE BOW DRAW, which is the only warning that exists at this range.
     *
     * <p>At five blocks an arrow crosses in under two ticks, so a dodge triggered by the arrow
     * cannot work — and every dodge built here has been triggered by the arrow. A skeleton's draw
     * takes about twenty ticks and is visible client-side through isUsingItem(), so if these
     * counters show a draw of that length before each of the 2-3 shots a fight costs, the dodge
     * finally has something to act on.
     *
     * <p>What each one answers, because a single number would not settle it:
     * mdDraws — how many draw episodes happen at all (it should track the arrow count);
     * mdDrawMaxTicks and mdDrawTicks — how LONG the warning lasts, which is the whole question;
     * mdDrawGapMilli — the range at the moment the draw starts, to be compared with the 4.4-5.6
     * the shots are released from.
     *
     * <p>⛔ ANSWERED, 2026-08-12, and the warning is real. Six runs:
     * <pre>
     *   arrows n/mean/max      draws n/ticks/MAX/meanGap     min_hp
     *   4 / 5.62 / 6.28        4 / 79  / 20 / 7.30            11
     *   7 / 6.27 / 8.19        8 / 151 / 20 / 6.05            17
     *   20 / 9.45 / 12.30      21 / 409 / 20 / 9.25           16
     *   4 / 4.61 / 5.22        5 / 87  / 20 / 6.40             8
     *   2 / 4.30 / 5.42        3 / 45  / 20 / 7.57            16
     *   6 / 5.30 / 6.35        6 / 120 / 20 / 6.47            16
     * </pre>
     * Draw count tracks arrow count, so EVERY shot is preceded by a draw. The longest draw is
     * exactly 20 ticks in all six runs — a full second, every time, not a lucky sample. And it
     * BEGINS at 6.0-9.3 blocks while the shot leaves at 4.3-6.3: the skeleton starts aiming while
     * the bot is still out, and the bot closes under an already-drawn bow.
     *
     * <p>So the reaction window is one second wide and starts at a comfortable range, against the
     * under-two-ticks a fired arrow allows. That is the difference between a dodge that cannot
     * work and one that can.
     */
    /**
     * EXPOSURE: ticks spent inside the band a shooter actually fires from.
     *
     * <p>Measured today and it is the quantity that decides this course, not fight length. A
     * skeleton releases only from 4.3-6.3 blocks and hits 50-70% there, against ~15% at ten. So
     * "shorter fight" and "less time under fire" are DIFFERENT numbers here, and they can move in
     * opposite directions: removing the circle-strafe cut wasted swing ticks by a third and made
     * arrows WORSE, because the orbit had been holding the bot at the band's edge.
     *
     * <p>Counted from 2.5 to 7.0 blocks — from inside our own reach out past the furthest release
     * seen — so the ratio arrows/bandTicks reads as "shots per tick of exposure" and separates the
     * two effects that have been confounded all day.
     *
     * <p>⛔ FIRST READING, 2026-08-12:
     * <pre>
     *   arrows n/mean/max     draws        band   min_hp
     *   3 / 4.53 / 5.73       3 / 60 / 20   146     17
     *   4 / 4.97 / 6.04       4 / 80 / 20   215     16
     *   3 / 4.33 / 5.98       3 / 60 / 21   141     16
     *   4 / 6.63 / 10.42      5 / 85 / 20   169     16
     *   2 / 4.09 / 5.51       3 / 42 / 20   111     16
     *   6 / 8.49 / 14.91      7 / 121 / 22  179      9
     *   33 / 9.44 / 12.95     33 / 661 / 22 179      4   <- never closed; shot from ~9.4
     * </pre>
     * The bot spends 111-215 ticks — five and a half to eleven seconds — inside the band, and takes
     * 2-4 shots there: about 0.02 shots per tick of exposure, one every two and a half seconds,
     * which reconciles with a 20-tick draw plus aiming.
     *
     * <p>THAT IS THE TARGET, stated in a measurable quantity for the first time: not "kill faster"
     * and not "dodge", but TIME FROM ENTERING SEVEN BLOCKS TO THE KILL. Zero arrows landed needs
     * that cut by roughly two thirds — from ~150 ticks to ~50, i.e. down to a single shot — and
     * any change should be judged on band ticks first, arrows second.
     *
     * <p>⛔ HOW MUCH ROOM THERE IS, AND WHAT "GREEN" CAN EVEN MEAN HERE. From measured constants
     * only — vanilla sprint 5.6 blocks/s, an iron sword's 6 damage against 20 HP, a 0.625 s
     * cooldown:
     * <pre>
     *   closing 7.0 -> 3.0 at sprint        14 ticks
     *   four swings at 0.625 s              50 ticks
     *   THEORETICAL FLOOR                   64 ticks in band  -> ~1.3 shots
     *   measured                           111-215, median 169 -> ~3.4 shots
     * </pre>
     * There is enormous room — the bot spends 2.6x the floor, about five seconds of slack. But the
     * floor itself still costs ABOUT ONE SHOT, and a close shot lands 50-70% of the time.
     *
     * <p>So this gate, which demands ZERO arrows landed, is a COIN at roughly 40% even for perfect
     * execution, unless the bot also kills in three swings (one crit) or denies that last shot.
     * Worth knowing before another pass is spent expecting a deterministic green: a course whose
     * ceiling is a coin cannot be turned green by tuning alone, and the honest goal is to push the
     * pass rate toward that ceiling rather than to 12/12.
     *
     * <p>⛔ CORRECTION TO MY OWN FLOOR, from the crit rate rather than an assumption. Over 39 landed
     * swings today, 15 were crits — 38%. Three swings kill on ONE crit (9+6+6 = 21 against 20 HP),
     * and P(at least one crit in three) at that rate is 77%. So the four-swing kill I used for the
     * floor is the pessimistic case, not the normal one: the real floor is 14 + 37.5 = ~51 ticks,
     * about ONE shot, and the ceiling is correspondingly better than 40%.
     *
     * <p>AND A CLAIM OF MINE FROM AN HOUR AGO, WITHDRAWN BEFORE IT WAS BUILT ON. I wrote that the
     * bot is "already OVERKILLING" because landed=4 crits=1 computes to ~27 damage against a 20 HP
     * skeleton. That treats gateStats' `passed` as damaging hits, and it is not: it counts swings
     * that passed the GATE, i.e. attacks sent. Minecraft gives a mob ~10 ticks of invulnerability
     * after a hit, and mdTung is a PAIR — the committed fight AND the force field's strike — so
     * when both fire, one lands inside those frames and is absorbed. The damage line above says so
     * itself ("an estimate with a soft edge"), and I then quoted it as proof.
     *
     * <p>So there are two live readings and they need different fixes: the bot swings after the
     * kill is in (stop early, save a 12.5-tick cooldown inside the band), or two strikers are
     * hitting the same mob and half the swings are eaten by i-frames (make them one). The second
     * would ALSO explain why `landed` runs high while fights stay long.
     *
     * <p>TO SEPARATE THEM, and it is cheap: compare the target's health drop against swings sent.
     * Health is client-visible; the counter for it is one line next to the ones already here.
     */
    public static volatile int mdBandTicks;
    /**
     * Band ticks elapsed before our FIRST swing passed the gate. -1 until it happens.
     *
     * <p>⛔ WHY THIS AND NOT mdBandTicks. The third engage-band series was pre-registered on
     * corr(band ticks, arrows) = +0.43 over 33 runs, read as "the controller is starved of band
     * ticks, give it more". The trivial reading was never excluded: a fight that takes longer has
     * both more band ticks AND more arrows, because the skeleton keeps shooting throughout. The
     * series then settled it -- the flag tripled controller ticks, band ticks went 122 -> 192, and
     * arrows went 1.12 -> 1.58. Band time is a CONSEQUENCE of a slow kill, not a lever on it, and
     * a correlation with a shared cause is what it looks like.
     *
     * <p>What the gate actually prices is time the skeleton shoots for free. A kill needs about
     * three swings at a ~12-tick cooldown, so the fight itself is ~36 ticks; anything beyond that
     * is approach, and at 40 ticks a shot cycle every extra 40 is another arrow. Ticks-to-first-
     * swing measures exactly that and cannot be inflated by a long fight after contact, which is
     * the confound that made the other number unreadable.
     *
     * <p>A companion "band ticks to the KILL" was drafted and dropped: this loop iterates living
     * hostiles, so the target's death is exactly when it stops being visible here, and stamping it
     * would have needed state this instrument does not carry. The course already times the kill.
     * A counter that cannot be stamped honestly is the dead-instrument problem with extra steps.
     */
    public static volatile int mdBandToFirstSwing = -1;
    /** Draw-dodge ticks declined because a swing was in hand. The mechanism gate for
     *  {@link kaptainwutax.tungsten.TungstenConfig#combatDodgeYieldsToSwing}. */
    public static volatile int mdDodgeYielded;
    /** Ticks the arrow-avoidance PATHING task owned the legs, and the gap while it did. */
    public static volatile int mdDodgeTaskTicks, mdDodgeTaskGapMilli;
    /**
     * Damage ACTUALLY taken off the target, in tenths, against the swings we sent.
     *
     * <p>gateStats' passed count is attacks SENT, not damaging hits, and I quoted it as damage once
     * already. Minecraft grants a mob ~10 invulnerability ticks after a hit, and mdTung is a pair —
     * the committed fight AND the force field — so when both fire, one swing is absorbed. That
     * gives two very different readings of the same "landed=4 against 20 HP": the bot swings after
     * the kill is in, or half its swings are eaten.
     *
     * <p>Summing the target's health DROPS settles it without a theory: damage/swings near the
     * weapon's 6 means the swings land, far below it means they are being absorbed.
     *
     * <p>⛔ READ THE CONFOUND BEFORE QUOTING THIS. The sum is bounded by the target's 20 HP and the
     * target always dies, so the total is near-constant BY CONSTRUCTION -- first three runs read
     * 18.0, 18.0, 18.0 against swing counts 4, 3, 2 and crit counts 2, 0, 1. A quantity that does
     * not move when its inputs move is not measuring those inputs. It gave exactly ONE clean datum:
     * the run with passed=3, crits=0, dealt=18.0 is 3 x 6 exactly, so in that run every swing
     * landed and none was eaten by invulnerability.
     *
     * <p>The non-degenerate version is ATTRIBUTION, not a sum: mark each swing and check whether a
     * health drop follows it within ~2 ticks, then report hits/swings. That number is free of the
     * HP ceiling because it never adds past the individual swing. Build that before making any
     * claim about swing efficiency; do not resolve the absorption question with this field.
     */
    public static volatile int mdDamageDealtTenths;

    /** Health drops that followed one of our swings closely enough to be ours. */
    public static volatile int mdSwingHits;

    /** Zeroes the damage ledger between bench runs; the per-entity map must go with it. */
    public static void resetDamageLedger() {
        mdDamageDealtTenths = 0;
        mdSwingHits = 0;
        lastTargetHp.clear();
    }
    private static final java.util.Map<Integer, Float> lastTargetHp =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    public static volatile int mdDraws, mdDrawTicks, mdDrawMaxTicks, mdDrawGapMilli;
    private static final java.util.Map<Integer, Integer> drawTicksById =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    public static final java.util.Set<Integer> seenArrowIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final double DANGER_KEEP_DISTANCE = 30;
    private static final double CREEPER_KEEP_DISTANCE = 10;
    private static final double ARROW_KEEP_DISTANCE_HORIZONTAL = 2;
    private static final double ARROW_KEEP_DISTANCE_VERTICAL = 10;
    // Wider detection radius for arrow approach (from autoclef: horizontalDistanceSq < 1000)
    private static final double ARROW_DETECT_HORIZONTAL_SQ = 1000;
    /**
     * How much of the dodge is spent closing on the shooter rather than leaving the arrow's line.
     *
     * <p>At 0 the sidestep is purely perpendicular: it survives the arrow and holds the range open
     * for ever, which against a ranged mob is a draw the bot always loses on damage. At 1 it is a
     * 45-degree advance. Below 1 the perpendicular component stays dominant, so leaving the line is
     * still the primary motion and the approach is what it does with the rest.
     */
    private static final double DODGE_PRESS_BIAS = 0.6;
    /**
     * Inside this, a dodge is pure sidestep -- the closing half of the heading is dropped.
     *
     * <p>Set at our own melee reach: past it there is range to close and the bias earns its keep;
     * inside it the fight is already joined, an arrow arrives in under a tick, and the only thing
     * that can make the shot miss is lateral speed across the shooter's aim.
     */
    private static final double DODGE_PRESS_MIN_RANGE = 4.0;
    // ⛔ AND THE 2.1-SIGMA CLAIM FOR THIS SOFTENED WITH MORE DATA. A confirmation series on the
    // SAME build read 1.35 arrows (n=13) against the 1.10 (n=27) it was shipped on. Pooled over all
    // 40 runs the shipped state is 1.18 against a 1.53 baseline (n=53) -- about 1.7 sigma, not 2.1.
    // Directionally positive and NOT established at the 2-sigma bar this repo uses.
    //
    // That is the second time in this work a figure has weakened on more data (the other: a 5/13
    // pass rate that a following n=12 read as 1/12). The lesson is not about this change: at
    // n=12-27 on this course, a single series moves the headline by more than the effects being
    // chased. Quote the POOLED figure, and treat any first series as provisional.
    private static final double SAFE_KEEP_DISTANCE = 8;
    private static final List<Class<? extends Entity>> ignoredMobs = List.of(Entities.WARDEN, WitherEntity.class, EndermanEntity.class, BlazeEntity.class,
            WitherSkeletonEntity.class, HoglinEntity.class, ZoglinEntity.class, PiglinBruteEntity.class, VindicatorEntity.class, MagmaCubeEntity.class);

    private static boolean shielding = false;
    private final DragonBreathTracker dragonBreathTracker = new DragonBreathTracker();
    private final KillAura killAura = new KillAura();
    /** Tungsten's duelling engine, used for the fight this chain has decided to take. */
    private final kaptainwutax.tungsten.combat.CombatController tungstenCombat =
            new kaptainwutax.tungsten.combat.CombatController();
    /** When the controller last drove, so the aura can stand off that target for a moment. */
    private long tungstenDrivingMs = 0L;
    /** Ticks the committed fight ran on tungsten. Read over py4j as mdTung. */
    public static volatile int mdTungstenTicks;
    /** Ticks the force field's nearest target was struck by tungsten's trigger bot. */
    public static volatile int mdAuraTungstenTicks;
    /** Times height was taken against a crowd. Read as mdPillarD. */
    public static volatile int mdPillarDefence;
    /** Two blocks puts a zombie's arm out of the question and keeps our own swing in range. */
    private static final int PILLAR_HEIGHT = 2;
    /** Climb only while the nearest is still this far off -- the climb must not cost health. */
    private static final double CLIMB_EARLY_RANGE = 6.0;
    /** Ticks a crowd was answered with the bow instead of the sword. Read as mdBow. */
    public static volatile int mdBowTicks;
    /** Closer than this a bow is the wrong weapon -- they arrive before the draw finishes. */
    private static final double BOW_MIN_RANGE = 5.0;
    private Entity targetEntity;
    private boolean doingFunkyStuff = false;
    private boolean wasPuttingOutFire = false;
    private CustomBaritoneGoalTask runAwayTask;
    /** Flight needs a plan B. See the flee branch for what happens without one. */
    private net.minecraft.util.math.BlockPos fleeAnchor = null;
    private int fleeStuckTicks = 0;
    private int fleeSuppressedTicks = 0;
    /**
     * Times flight was abandoned because it was going nowhere. Read over py4j as mdFleeStuck.
     *
     * <p>This javadoc said exactly that while the counter was NOT in placeStats at all — incremented,
     * reset, and unreadable. Written hours after RULE FOUR went into the checklist, which forbids
     * precisely this: a checkable claim about the running system, stated in a comment, never checked.
     * A counter nobody can read is not instrumentation, it is a comment that flatters itself.
     */
    public static volatile int mdFleeStuck;
    private float prevHealth = 20;
    private boolean needsChangeOnAttack = false;
    private Entity lockedOnEntity = null;
    // Player threat tracking (ported from autoclef)
    public Task _killTask = null;
    private final TimerGame _runAwayTimer = new TimerGame(2);
    /**
     * When something last took health off us.
     *
     * <p>The difference between HURT and UNDER ATTACK, which is the whole of the low-health rule.
     * Two hearts left and being hit right now is a fight to disengage from; two hearts left and
     * untouched for ten minutes is just a bot that needs to go eat.
     */
    private long lastDamageMs = 0L;
    /** How long after a hit we still count as being in a fight. */
    private static final long RECENT_DAMAGE_MS = 5000L;
    // Projectile pre-dodge (ported from autoclef, DISABLED — kept for future use)
    /**
     * The dodge heading, in world space, or (0,0) for "no arrow to dodge".
     *
     * <p>Was a {@link Rotation}, because the old executor turned the head and walked forward. The
     * primitive that replaced it strafes in the player's own frame, so a direction is the whole
     * requirement and the camera stays on the target.
     */
    private double suggestedDodgeX, suggestedDodgeZ;
    /** How long one sidestep runs. An arrow crosses twelve blocks in about eight ticks. */
    private static final int DODGE_HOLD_TICKS = 6;
    /**
     * How long THIS sidestep should run, derived from how far the arrow actually has to travel.
     *
     * <p>⛔ THE CONSTANT ABOVE WAS DERIVED FOR TWELVE BLOCKS AND IS APPLIED AT FIVE. Its own comment
     * says so: "an arrow crosses twelve blocks in about eight ticks". On mob_skeleton the shots are
     * released at a mean of 5.4-5.7 blocks (the arrows= counter), and an arrow covers ~2.65 blocks a
     * tick, so the flight is about two ticks. The hold is six. The remaining four are spent
     * sidestepping something that has already arrived or already missed -- and the dodge primitive
     * OVERRIDES the approach for every one of them, by design and at the final-word position.
     *
     * <p>Measured scale: dodgeDrive runs ~43 ticks a fight against ~52 ticks of combat control and
     * ~109 band ticks, so this is not a rounding error in the approach; it is a large share of it.
     *
     * <p>Clamped so it can only ever RETURN ticks: never longer than {@link #DODGE_HOLD_TICKS},
     * never shorter than 2 (one tick of margin on a sub-tick flight, since the sidestep has to be
     * moving BEFORE the arrow arrives to make it miss). Behind
     * {@link kaptainwutax.tungsten.TungstenConfig#combatDodgeHoldByRange} and off by default.
     */
    private int suggestedDodgeTicks = DODGE_HOLD_TICKS;
    /** Blocks an arrow covers per tick, measured on this bench rather than assumed. */
    private static final double ARROW_BLOCKS_PER_TICK = 2.65;
    private final TimerGame preProjectileTimer = new TimerGame(0.3);
    private final TimerGame projectileTimer = new TimerGame(0.7);

    private float cachedLastPriority;

    public MobDefenseChain(TaskRunner runner) {
        super(runner);
    }

    public static double getCreeperSafety(Vec3d pos, CreeperEntity creeper) {
        double distance = creeper.squaredDistanceTo(pos);
        float fuse = creeper.getClientFuseTime(1);

        // Not fusing.
        if (fuse <= 0.001f) return distance;
        return distance * 0.2; // less is WORSE
    }

    private static void startShielding(AltoClef mod) {
        shielding = true;
        if (mod.getClientBaritone() != null)
            Nav.pause();
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
        if (!mod.getPlayer().isBlocking()) {
            ItemStack handItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
            if (ItemVer.isFood(handItem)) {
                List<ItemStack> spaceSlots = mod.getItemStorage().getItemStacksPlayerInventory(false);
                for (ItemStack spaceSlot : spaceSlots) {
                    if (spaceSlot.isEmpty()) {
                        mod.getSlotHandler().clickSlot(PlayerSlot.getEquipSlot(), 0, SlotActionType.QUICK_MOVE);
                        return;
                    }
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                garbage.ifPresent(slot -> mod.getSlotHandler().forceEquipItem(StorageHelper.getItemStackInSlot(slot).getItem()));
            }
        }
        mod.getInputControls().hold(Input.SNEAK);
        mod.getInputControls().hold(Input.CLICK_RIGHT);
    }

    private static int getDangerousnessScore(List<LivingEntity> toDealWithList) {
        int numberOfProblematicEntities = toDealWithList.size();
        for (LivingEntity toDealWith : toDealWithList) {
            if (toDealWith instanceof EndermanEntity || toDealWith instanceof SlimeEntity || toDealWith instanceof BlazeEntity) {

                numberOfProblematicEntities += 1;
            } else if (toDealWith instanceof DrownedEntity
                    && LivingEntityVer.hasTrident(toDealWith)
            ) {
                // Drowned with tridents are also REALLY dangerous, maybe we should increase this??
                numberOfProblematicEntities += 5;
            }
        }
        return numberOfProblematicEntities;
    }

    /**
     * Health lost since the last counter reset, accumulated EVERY TICK.
     *
     * <p>The bench samples health every few seconds, which is far too coarse for this question:
     * a trio fight lasts ten to fifteen seconds, so five samples caught three points of a
     * nine-point loss and reported total_drop=3.0 while min_hp said 11.0. A criterion of "zero
     * damage" cannot be judged by a sampler that misses most of the hits. This is ticked with the
     * chain, so nothing is missed between polls.
     */
    public static volatile float mdDamageTaken;
    /** Where the hits land, relative to where the bot is looking. Read as hits=F/B/L/R. */
    public static volatile int mdHitFront, mdHitBack, mdHitLeft, mdHitRight;
    /** Centre-to-centre range at which hits actually land: sum, count and the worst case. */
    public static volatile double mdHitDistSum, mdHitDistMax;
    public static volatile int mdHitCount;
    private float mdLastHealth = -1f;

    private void trackDamage() {
        AltoClef mod = AltoClef.getInstance();
        if (mod == null || mod.getPlayer() == null) {
            mdLastHealth = -1f;
            return;
        }
        float hp = mod.getPlayer().getHealth();
        if (mdLastHealth >= 0f && hp < mdLastHealth) {
            mdDamageTaken += mdLastHealth - hp;
            // WHERE DID IT COME FROM? Twelve tactical hypotheses were tried without ever asking
            // this. The best run of the best policy still concedes one hit, and whether that hit
            // arrives from the front (the mob we are fighting) or from behind (one that walked
            // round while we fought) decides which fix is even relevant: aim/timing for the
            // former, positioning for the latter. Recorded as four counters rather than argued.
            net.minecraft.entity.Entity src = null;
            double best = Double.MAX_VALUE;
            try {
                for (net.minecraft.entity.Entity e : mod.getWorld().getEntities()) {
                    if (!(e instanceof net.minecraft.entity.mob.HostileEntity) || !e.isAlive()) {
                        continue;
                    }
                    double d = e.squaredDistanceTo(mod.getPlayer());
                    if (d < best) {
                        best = d;
                        src = e;
                    }
                }
            } catch (Throwable ignored) {
                src = null;
            }
            if (src != null) {
                double dx = src.getX() - mod.getPlayer().getX();
                double dz = src.getZ() - mod.getPlayer().getZ();
                double yaw = Math.toRadians(mod.getPlayer().getYaw());
                double fwd = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
                double side = Math.cos(yaw) * dx + Math.sin(yaw) * dz;
                if (Math.abs(fwd) >= Math.abs(side)) {
                    if (fwd > 0) mdHitFront++; else mdHitBack++;
                } else {
                    if (side > 0) mdHitLeft++; else mdHitRight++;
                }
                // AND AT WHAT RANGE. The hits come from the FRONT -- 7 of 9 -- so this is not
                // about being flanked, it is about the distance of the exchange itself. Twelve
                // hypotheses argued about that distance using an eye-to-hitbox metric, while the
                // server decides with its own centre-to-centre one. Record the real number at the
                // moment damage lands and the argument is over.
                double centre = Math.sqrt(best);
                mdHitDistSum += centre;
                mdHitCount++;
                if (centre > mdHitDistMax) {
                    mdHitDistMax = centre;
                }
            }
        }
        mdLastHealth = hp;
    }

    @Override
    public float getPriority() {
        trackDamage();
        // SIXTEEN DEATHS TO ZOMBIES IN ONE RUN. Either this chain never gets the tick, or it gets
        // it and its answer does not save the bot. Those need opposite fixes, so count them apart.
        mdPriorityCalls++;
        cachedLastPriority = getPriorityInner();
        if (cachedLastPriority > 0) mdWon++;
        // If no task was set but a non-zero priority was returned, that's an inconsistent
        // state — drop priority so we don't claim control without doing anything.
        // PUBLISH WHAT TUNGSTEN CANNOT SEE. The trace proved the legs are idle on 47% of
        // re-approach ticks with every tungsten-side driver false; these three say whether
        // altoclef thinks it is pathing, which task holds the chain, and whether the chain
        // claimed the bot at all. Diagnostic only -- read through combatTrace().
        try {
            kaptainwutax.tungsten.combat.CombatTrace.hostPathing = adris.altoclef.control.Nav.isPathing();
            kaptainwutax.tungsten.combat.CombatTrace.hostPrio = cachedLastPriority;
            kaptainwutax.tungsten.combat.CombatTrace.hostTask =
                    mainTask == null ? "-" : mainTask.getClass().getSimpleName();
        } catch (Exception ignored) {
            // an instrument must never be the thing that breaks a fight
        }
        if (mainTask == null && cachedLastPriority > 0) {
            cachedLastPriority = 0;
        }
        float nowHealth = AltoClef.getInstance().getPlayer().getHealth();
        if (nowHealth < prevHealth) {
            lastDamageMs = System.currentTimeMillis();
        }
        prevHealth = nowHealth;
        return cachedLastPriority;
    }

    private void stopShielding(AltoClef mod) {
        if (shielding) {
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (ItemVer.isFood(cursor)) {
                Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false).or(() -> StorageHelper.getGarbageSlot(mod));
                if (toMoveTo.isPresent()) {
                    Slot garbageSlot = toMoveTo.get();
                    mod.getSlotHandler().clickSlot(garbageSlot, 0, SlotActionType.PICKUP);
                }
            }
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            shielding = false;
        }
    }

    public boolean isShielding() {
        return shielding || killAura.isShielding();
    }

    private boolean escapeDragonBreath(AltoClef mod) {
        dragonBreathTracker.updateBreath(mod);
        for (BlockPos playerIn : WorldHelper.getBlocksTouchingPlayer()) {
            if (dragonBreathTracker.isTouchingDragonBreath(playerIn)) {
                return true;
            }
        }
        return false;
    }

    private float getPriorityInner() {
        if (!AltoClef.inGame()) {
            return Float.NEGATIVE_INFINITY;
        }
        AltoClef mod = AltoClef.getInstance();

        if (!mod.getModSettings().isMobDefense()) {
            return Float.NEGATIVE_INFINITY;
        }

        // On PEACEFUL there are no hostile mobs — MobDefense must stand down,
        // otherwise a stale run-away task keeps it winning at prio 70 and
        // starves the user task chain (navigation never ticks). Restored.
        if (mod.getWorld().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL)
            return Float.NEGATIVE_INFINITY;

        if (needsChangeOnAttack && (mod.getPlayer().getHealth() < prevHealth || killAura.attackedLastTick)) {
            needsChangeOnAttack = false;
        }

        // Tick immunity wakeups: clear immunity if HP dropped / entity got hurt
        AbstractKillEntityTask.tickImmunityWakeups(mod.getEntityTracker().getCloseEntities());
        // If something immune attacks us, clear its immunity immediately
        Entity attacker = mod.getPlayer().getAttacker();
        if (attacker != null && AbstractKillEntityTask.hasImmunity(attacker)) {
            AbstractKillEntityTask.clearImmunity(attacker);
        }

        // Put out fire if we're standing on one like an idiot
        BlockPos fireBlock = isInsideFireAndOnFire(mod);
        if (fireBlock != null) {
            putOutFire(mod, fireBlock);
            wasPuttingOutFire = true;
        } else {
            // Stop putting stuff out if we no longer need to put out a fire.
            if (mod.getClientBaritone() != null)
                mod.getInputControls().release(Input.CLICK_LEFT);
            wasPuttingOutFire = false;
        }

        // Run away if a weird mob is close by.
        // FLIGHT USED TO HAVE NO PLAN B, AND THE BOT DIED STANDING STILL BECAUSE OF IT.
        // Measured on mob_skeleton: a skeleton 12.5 blocks away shoots the bot under 10 hp, this
        // branch commits to RunAwayFromHostilesTask and returns 70 every tick -- so nothing else in
        // the chain can act. The flee goal lands 30 blocks out, the arena is a 28x28 walled field,
        // and the drive answers `primDrive NO ROUTE ... (d30.0)` over and over. The bot stands where
        // it is and is shot dead FIVE TIMES in one run, with a sword in its hand and mdTung=0
        // because no fight was ever committed.
        // Running away is still the right first answer -- it just cannot be the ONLY answer. If the
        // body has not moved while fleeing, the flight is not happening, and holding priority for it
        // only guarantees the death it was meant to avoid. Stand down for a while and let the rest
        // of the chain (force field, the committed fight) have the tick.
        if (fleeSuppressedTicks > 0) {
            fleeSuppressedTicks--;
        }
        Optional<Entity> universallyDangerous = getUniversallyDangerousMob(mod);
        if (universallyDangerous.isPresent() && mod.getPlayer().getHealth() <= 10
                && fleeSuppressedTicks == 0) {
            net.minecraft.util.math.BlockPos here = mod.getPlayer().getBlockPos();
            if (fleeAnchor != null && fleeAnchor.equals(here)) {
                fleeStuckTicks++;
            } else {
                fleeAnchor = here;
                fleeStuckTicks = 0;
            }
            // Three seconds of "fleeing" without leaving the block is not flight.
            if (fleeStuckTicks > 60) {
                mdFleeStuck++;
                fleeStuckTicks = 0;
                fleeAnchor = null;
                fleeSuppressedTicks = 100;
                runAwayTask = null;
            } else {
                mdFlee++;
                runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                setTask(runAwayTask);
                mdRet0++; return 70;
            }
        } else if (fleeSuppressedTicks == 0) {
            fleeAnchor = null;
            fleeStuckTicks = 0;
        }

        doingFunkyStuff = false;
        PlayerSlot offhandSlot = PlayerSlot.OFFHAND_SLOT;
        Item offhandItem = StorageHelper.getItemStackInSlot(offhandSlot).getItem();
        // Run away from creepers
        CreeperEntity blowingUp = getClosestFusingCreeper(mod);
        if (blowingUp != null) {
            if ((!mod.getFoodChain().needsToEat() || mod.getPlayer().getHealth() < 9)
                    && hasShield(mod)
                    && !mod.getEntityTracker().entityFound(PotionEntity.class)
                    //#if MC >= 12111
                    //$$ && !mod.getPlayer().getItemCooldownManager().isCoolingDown(offhandItem.getDefaultStack())
                    //#else
                    && !mod.getPlayer().getItemCooldownManager().isCoolingDown(offhandItem)
                    //#endif
                    && (mod.getClientBaritone() == null || Nav.isSafeToCancel())
                    && blowingUp.getClientFuseTime(blowingUp.getFuseSpeed()) > 0.5) {
                LookHelper.lookAt(mod, blowingUp.getEyePos());
                ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
                if (shieldSlot.getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    startShielding(mod);
                }
            } else {
                doingFunkyStuff = true;
                runAwayTask = new RunAwayFromCreepersTask(CREEPER_KEEP_DISTANCE);
                setTask(runAwayTask);
                mdRet1++; return 50 + blowingUp.getClientFuseTime(1) * 50;
            }
        }
        if (mod.getFoodChain().needsToEat() || mod.getFoodChain().isTryingToEat()
                || mod.getMLGBucketChain().isFalling(mod)
                || !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isChorusFruiting()) {
            killAura.stopShielding(mod);
            stopShielding(mod);
            return Float.NEGATIVE_INFINITY;
        }

        boolean projectileIsClose = isProjectileClose(mod);
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            // Raise shield only when no active dodge task (matching autoclef: _runAwayTask == null)
            // This prevents startShielding from calling requestPause() during active DodgeProjectilesTask
            if (mod.getModSettings().isDodgeProjectiles()
                    && hasShield(mod)
                    && runAwayTask == null
                    //#if MC >= 12111
                    //$$ && !mod.getPlayer().getItemCooldownManager().isCoolingDown(offhandItem.getDefaultStack())
                    //#else
                    && !mod.getPlayer().getItemCooldownManager().isCoolingDown(offhandItem)
                    //#endif
                    && (mod.getClientBaritone() == null || Nav.isSafeToCancel())
                    && !mod.getEntityTracker().entityFound(PotionEntity.class) && projectileIsClose) {
                ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
                if (shieldSlot.getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else {
                    startShielding(mod);
                }
            } else if (blowingUp == null && !projectileIsClose) {
                stopShielding(mod);
            }
        }

        // Force field
        doForceField(mod);

        // Dodge projectiles (ported from autoclef: direct sprint+jump sideways, or baritone in danger zones)
        if (mod.getModSettings().isDodgeProjectiles() && projectileIsClose) {
            doingFunkyStuff = true;
            if (WorldHelper.isDangerZone(mod, mod.getPlayer().getBlockPos())) {
                // Danger zone (void/lava/edge): use baritone pathfinding to dodge safely
                // ⛔ THIS IS THE SUSPECTED FEEDBACK LOOP, AND THIS COUNTS IT.
                // DodgeProjectilesTask is a PATHING task whose job is to hold ARROW_KEEP_DISTANCE
                // away from projectiles — it drives the bot AWAY. Bad runs on mob_skeleton share a
                // signature: mdFar 1979-2012, dodgeDrive 597-621, 28-42 arrows fired from a mean of
                // ~9.5 blocks, ready~0. Six hundred ticks of dodging and no approach.
                //
                // The loop: an arrow arrives -> this task drives the bot away -> it never closes ->
                // the skeleton keeps shooting -> the task fires again. Nothing breaks it until the
                // skeleton loses interest, which is why those runs end at min_hp 3-5 while ordinary
                // ones end at 12-17.
                //
                // And the dodge cannot even pay for itself here: releases come from 4.3-6.3 blocks,
                // where an arrow crosses in under two ticks. It is buying nothing with the approach.
                //
                // mdDodgeTask counts the ticks it owns and the gap while it does, so the loop is a
                // number rather than a story before anything is changed.
                //
                // ⛔ FIRST READING, 2026-08-12, and it is NOT a verdict. Six runs:
                //     dodgeTask = 2, 8, 1, 0, 4, 0 ticks   (min_hp 20, 16, 16, 16, 12, 16)
                // In an ordinary fight this task owns the legs for single-digit ticks, not six
                // hundred. BUT NOT ONE OF THOSE SIX WAS A CATASTROPHIC RUN — they ended at min_hp
                // 12-20, while the hypothesis is about the runs that end at 3-5 with thirty-odd
                // arrows fired from ~9.5 blocks.
                //
                // So this reads UNTESTED, not refuted: the regime simply did not occur in the
                // sample. Absence of the phenomenon is not evidence against a mechanism inside it,
                // and writing "refuted" here would be the same error this file has already paid for
                // four times today with dead counters. The counter now stands and will catch the
                // regime when it happens — the next pass should run until at least two bad runs are
                // in hand before judging.
                mdDodgeTaskTicks++;
                mdDodgeTaskGapMilli += (int) Math.round(
                        mod.getPlayer().getPos().distanceTo(projectileClosestPos(mod)) * 1000.0);
                runAwayTask = new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL);
                setTask(runAwayTask);
            } else if (suggestedDodgeX != 0 || suggestedDodgeZ != 0) {
                // ⛔ THESE KEY PRESSES NEVER REACHED THE GAME. This branch used to call
                // lookAt + tryPress(SPRINT/MOVE_FORWARD/JUMP) from here, and this chain ticks
                // BEFORE MovementQueue and BlockPathWalker, both of which release every key and
                // press their own (Movement.update()). Every tick the walker drove the approach --
                // which is every tick of the approach -- the dodge was wiped before it was read.
                //
                // That is pitfall P1, and the flee keys were fixed for exactly this a while ago:
                // driven from RunAwayTask.tick they measured 22 hits against 23 and were filed as
                // refuted, having never run. The dodge kept the defect, which is the best
                // explanation on record for four dodge hypotheses that each measured flat.
                //
                // The heading goes to a primitive that ticks at the final-word position instead.
                // It strafes in the player's own frame rather than turning the head, so the bot
                // keeps facing what it is fighting -- the swing gate refuses past 40 degrees, and a
                // dodge that looks away cannot also attack.
                kaptainwutax.tungsten.task.ProjectileDodge.hold(
                        suggestedDodgeX, suggestedDodgeZ, suggestedDodgeTicks);
            }
            mdRet2++; return 65;
        }
        // Projectile threat gone — clear stale dodge task so it doesn't block other chains
        if (runAwayTask instanceof DodgeProjectilesTask && !projectileIsClose) {
            runAwayTask = null;
        }
        // Dodge all mobs cause we boutta die son
        if (isInDanger(mod) && !escapeDragonBreath(mod) && !mod.getFoodChain().isShouldStop()) {
            if (targetEntity == null || WorldHelper.isSurroundedByHostiles()) {
                // ⛔ YOU CANNOT OUTRUN AN ARROW, AND THIS BRANCH HAS BEEN TRYING TO.
                //
                // Fleeing is a defence against a MELEE threat: break contact and the damage stops.
                // Against a shooter it stops nothing -- the projectile follows -- and it hands the
                // attacker the one thing it wants, which is range. This branch bids 70 and so
                // out-bids the fight branch's 65, meaning an arrow that lands takes health under
                // the isInDanger threshold and the bot then runs INSTEAD of closing, stays at
                // range, gets shot again, and stays under the threshold. That is a spiral with no
                // exit, and it is why mob_skeleton has been 0/N since it was written.
                //
                // Measured, six runs, flee ticks against damage taken:
                //     mdRet3    10   21   111   332   356   356
                //     dmgTaken   8    4    16    36    16    25
                // The two runs that barely fled took the least damage in the series.
                //
                // RangedAttackMob is vanilla's own marker for "attacks from a distance", so this
                // asks a property rather than naming a mob. When the thing endangering us shoots,
                // we decline to flee HERE and let the branches below decide: the fight branch takes
                // it at 65 if it judges the mob beatable, and if it does not, its own retreat at 80
                // still fires. The safety valve is kept; only the reflex is removed.
                if (!endangeredByShooter(mod)) {
                    runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                    setTask(runAwayTask);
                    mdRet3++; return 70;
                }
                mdFleeShooter++;
            }
        }

        // Player threat: avoid threatening players
        Optional<Entity> avoidTarget = getAvoidTarget(mod);
        if (avoidTarget.isPresent()) {
            if (!LookHelper.WindMouseState.isRotating) {
                Entity avoid = avoidTarget.get();
                runAwayTask = new RunAwayFromPlayersTask(avoid, SAFE_KEEP_DISTANCE + 5);
                setTask(runAwayTask);
                mdRet4++; return 55;
            }
        }

        // Player threat: attack players marked for attack
        Optional<Entity> toAttackPlayer = getAttackPlayer(mod);
        if (toAttackPlayer.isPresent() && toAttackPlayer.get() instanceof PlayerEntity player) {
            _killTask = new CombatTask(player.getName().getString(), false, true);
            mdFight++;
            setTask(_killTask);
            mdRet5++; return 65;
        } else {
            _killTask = null;
        }

        if (mod.getModSettings().shouldDealWithAnnoyingHostiles()) {
            // Deal with hostiles because they are annoying.
            List<LivingEntity> hostiles = mod.getEntityTracker().getHostiles();

            List<LivingEntity> toDealWithList = new ArrayList<>();

            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                for (LivingEntity hostile : hostiles) {
                    boolean isRangedOrPoisonous = (hostile instanceof SkeletonEntity
                            || hostile instanceof WitchEntity || hostile instanceof PillagerEntity
                            || hostile instanceof PiglinEntity || hostile instanceof StrayEntity
                            || hostile instanceof CaveSpiderEntity);
                    int annoyingRange = 10;

                    if (isRangedOrPoisonous) {
                        annoyingRange = 20;
                        if (!hasShield(mod)) {
                            annoyingRange = 35;
                        }
                    }

                    // Give each hostile a timer, if they're close for too long deal with them.
                    if (hostile.isInRange(mod.getPlayer(), annoyingRange) && LookHelper.seesPlayer(hostile, mod.getPlayer(), annoyingRange)) {

                        // Skip entities with combat immunity (5 reposition cycles, no damage → 5 min ignore)
                        if (AbstractKillEntityTask.hasImmunity(hostile)) continue;

                        boolean isIgnored = false;
                        for (Class<? extends Entity> ignored : ignoredMobs) {
                            if (ignored.isInstance(hostile)) {
                                isIgnored = true;
                                break;
                            }
                        }

                        // do not go and "attack" these mobs, just hit them if on low HP, or they are close
                        if (isIgnored) {
                            if (mod.getPlayer().getHealth() <= 10) {
                                toDealWithList.add(hostile);
                            }
                        } else {
                            toDealWithList.add(hostile);
                        }
                    }
                }
            }

            // attack entities closest to the player first
            // (focus-firing the WEAKEST was measured instead -- 9/12/17 damage against 8.0 for
            // nearest-first -- and is worse: the bot walks between targets instead of hitting the
            // one in front of it, which lengthens the fight, which is what costs health)
            toDealWithList.sort(Comparator.comparingDouble((entity) -> mod.getPlayer().distanceTo(entity)));

            if (!toDealWithList.isEmpty()) {

                // Depending on our weapons/armor, we may choose to straight up kill hostiles if we're not dodging their arrows.
                Item bestSword = getBestSword(mod);

                int armor = mod.getPlayer().getArmor();
                // ASK THE ITEM WHAT IT HITS FOR. ONE ANSWER, BOTH VERSIONS.
                // This used to be a version split whose 1.21.11 half was `float damage = 0` with a
                // TODO, because getMaterial().getAttackDamage() was removed there. The consequence
                // was not a missing nicety: canDealWith = ceil(armor*3.6/20 + damage*0.8 + shield)
                // came out ZERO for any bot without armour, one zombie scores 1, so
                // canDealWith >= dangerousness was NEVER true and the chain took the else branch --
                // RunAwayFromHostilesTask at priority 80. Measured with a probe: the bot fled a
                // single zombie while holding an iron sword at full health, and the kill task never
                // got a tick (kaTaskTicks=0), which is why the tungsten combat wiring looked dead.
                // The damage lives in the item's attribute modifiers on both 1.21.1 and 1.21.11,
                // so reading it there removes the divergence instead of papering over one side.
                float damage = bestSword == null ? 0 : meleeDamageOf(bestSword) + 1;

                int shield = hasShield(mod) && bestSword != null ? 3 : 0;

                int canDealWith = (int) Math.ceil((armor * 3.6 / 20.0) + (damage * 0.8) + (shield));

                if (canDealWith >= getDangerousnessScore(toDealWithList) || needsChangeOnAttack) {
                    // we just decided to attack, so we should either get it, or hit something before running away again
                    if (!(mainTask instanceof KillEntitiesTask)) {
                        needsChangeOnAttack = true;
                    }

                    // We can deal with it.
                    runAwayTask = null;
                    Entity toKill = toDealWithList.get(0);
                    lockedOnEntity = toKill;

                    // THE COMMITTED FIGHT RUNS ON TUNGSTEN.
                    // Measured, and not what anyone assumed: mobs were never killed by a kill task
                    // at all. With a zombie four blocks away the task sat in "Approaching target"
                    // while AbstractDoToEntityTask's interact gate was never even REACHED
                    // (dte=0/0/0/0/0/0) -- and the zombie still lost health steadily, 18 -> 15 ->
                    // 11 -> 7 -> 4 -> dead. The killer was the force field: KillAura swinging every
                    // tick, no spacing, no kiting, and the bot down to 3 HP for one zombie.
                    //
                    // This branch is the one place where the bot has DECIDED to fight: it has
                    // weighed weapon, armour and shield against the mob's dangerousness and it
                    // returns 65, out-bidding the user chain, so the movement keys are the chain's
                    // to use. That makes it the right owner for tungsten's duelling controller --
                    // aim, striking distance, circle-strafe, crit timing, disengage below half a
                    // bar. The passive force field elsewhere stays exactly as it was, because it
                    // must keep swatting without stealing the legs of a bot that is chopping wood.
                    // ONE OWNER OF THE LEGS AT A TIME, SPLIT BY DISTANCE.
                    // Ticking the controller while the kill task walks gives the movement keys two
                    // writers pulling opposite ways -- the task closing, the controller holding its
                    // striking distance -- and the bot never arrives. Measured: mdRet6=57 and
                    // mdTung=57 (it does commit to the fight now), but the interact gate saw reach
                    // on 36 of 690 ticks and the zombie sat at a full 20.0 HP throughout, stuck in
                    // "Approaching target".
                    // So the approach belongs to the task, and the strike belongs to tungsten.
                    // HOW FAR IS IT WHEN THE GATE REFUSES? mob_trio runs this branch on 99% of
                    // ticks (mdRet6=2405 of mdCalls=2431) and the committed half of mdTung stays at
                    // ZERO, so inRange -- which on flat ground reduces to distance < 4.5 -- is
                    // false every single time, while the force field kills all three anyway. Either
                    // the bot never closes or the test is wrong about what it measures, and those
                    // want opposite fixes. Record the distance rather than argue about it.
                    double gapToKill = mod.getPlayer().distanceTo(toKill);
                    if (gapToKill > 4.5) {
                        mdFarTicks++;
                        mdFarGapMilli = (int) (gapToKill * 1000);
                    }
                    // ⛔ THIS GATE IS WHY HALF THE TIME UNDER FIRE HAS NO FIGHT IN IT.
                    // inRange reduces to distance < 4.5 on flat ground (see the note above), while
                    // a skeleton's killing band — measured — is 2.5 to 7.0 and it releases from
                    // 4.3-6.3. So from 7.0 down to 4.5 the bot is being SHOT AT while the combat
                    // controller is not ticking at all: no swing gate, no cooldown-aware approach,
                    // nothing. Measured on mob_skeleton: 129-253 ticks inside the band against
                    // 62-89 ticks where the gate is evaluated.
                    //
                    // That is where the ~105 ticks of slack above the 64-tick floor live, and every
                    // hypothesis this course has consumed was aimed at the 40% where combat DOES
                    // run. mdTung reading 50-80 a fight was taken as healthy for weeks; against the
                    // exposure it is the symptom of this line.
                    //
                    // DO NOT widen it casually: the approach from 7 to 4.5 currently belongs to
                    // KillEntitiesTask, and handing it to tungsten changes who drives the legs on
                    // every mob course, not just this one. Judge on band ticks first (rule: today
                    // proved band ticks and arrows can move in opposite directions), with an
                    // interleaved pair, and watch mob_melee and mob_trio for regression.
                    // THE FIX, behind a pin: let combat run across the whole killing band, not
                    // just the last 4.5 blocks. Off by default; judged interleaved on band ticks
                    // first, then arrows, with mob_melee and mob_trio watched for regression.
                    boolean engage = mod.getControllerExtras().inRange(toKill)
                            || (kaptainwutax.tungsten.TungstenConfig.get().combatEngageBand
                                && gapToKill <= 7.0);
                    if (engage) {
                        kaptainwutax.tungsten.combat.WeaponSelector.equipBestMelee(mod.getPlayer());
                        tungstenCombat.tick(mod.getPlayer(), toKill, mod.getWorld());
                        tungstenDrivingMs = System.currentTimeMillis();
                        mdTungstenTicks++;
                    }

                    // Tell the approach latch what we are committed to. It only fires on ticks
                    // where nothing else drove the legs, and only for a few ticks after this
                    // stamp, so it dies with the fight instead of lingering.
                    kaptainwutax.tungsten.combat.ApproachLatch.stamp(toKill.getPos());
                    setTask(new KillEntitiesTask(toKill.getClass()));
                    mdRet6++; return 65;
                } else {
                    // We can't deal with it
                    runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                    setTask(runAwayTask);
                    mdRet7++; return 80;
                }
            }
        }
        // By default, if we aren't "immediately" in danger but were running away, keep
        // running away until we're good.
        if (runAwayTask != null && !runAwayTask.isFinished()) {
            // THE SUSPECT: this re-sets an OLD flee task and returns the PREVIOUS priority, so the
            // chain keeps the tick without choosing to flee or fight again -- which is exactly what
            // was measured (mdWon in the thousands, mdFlee and mdFight both zero, ~17 deaths a run).
            mdRet8++;
            setTask(runAwayTask);
            return cachedLastPriority;
        } else {
            runAwayTask = null;
        }

        if (needsChangeOnAttack && lockedOnEntity != null && lockedOnEntity.isAlive()) {
            mdRet9++;
            setTask(new KillEntitiesTask(lockedOnEntity.getClass()));
            return 65;
        } else {
            needsChangeOnAttack = false;
            lockedOnEntity = null;
        }

        return 0;
    }

    /** Called from ProjectileEvent subscription — instant projectile detection. */
    public void onProjectileLaunched(AltoClef mod, ProjectileEntity arrowEntity, boolean sticked) {
        if (!sticked)
            mod.getEntityTracker().addProjectile(arrowEntity);
        // ⛔ DO NOT RECORD THE RELEASE RANGE HERE — THIS HOOK BARELY FIRES.
        // It was tried, and read arrows=0 in six runs out of six while the bot was visibly being
        // shot. ClientPlayNetworkHandlerMixin only publishes ProjectileEvent when a tracked-data
        // update arrives with EXACTLY one value whose id is 8 and whose payload is a Byte — the
        // "using item" flag, which a skeleton's arrow essentially never sends. The counting now
        // lives in noticeArrows(), off the tracker's per-tick scan, which is the same source the
        // dodge already trusts.
    }

    /**
     * Called from ItemUseEvent subscription — detect players aiming bows at us.
     * DISABLED for now (kept from autoclef for future activation).
     */
    @SuppressWarnings("unused")
    public void onPlayerItemUse(AltoClef mod, Entity entity, boolean released) {
        // DISABLED FOR NOW — ported from autoclef, was disabled there too.
        // Enable by removing the `if (false &&` guard when ready to test.
        if (false && entity instanceof PlayerEntity player && mod.getPlayer() != null) {
            double prob = LookHelper.getLookingProbability(player, mod.getPlayer());

            if (prob > 0.96) {
                // Sidestep across the line from the player who is aiming at us. Kept as a vector
                // for the same reason as the live path above: the primitive strafes, it does not
                // steer. Still dead code behind `if (false &&` -- converted rather than left
                // referring to a field that no longer exists.
                Vec3d away = mod.getPlayer().getPos().subtract(player.getPos());
                Vec3d flat = new Vec3d(away.x, 0, away.z);
                if (flat.lengthSquared() > 1.0e-6) {
                    Vec3d across = new Vec3d(-flat.z, 0, flat.x).normalize();
                    suggestedDodgeX = across.x;
                    suggestedDodgeZ = across.z;
                }
                projectileTimer.reset();
                if (entity.getName() != null) {
                    Debug.logMessage("Dodging ranged attack from " + entity.getName().getString());
                }
            }
        }
    }

    private static boolean hasShield(AltoClef mod) {
        return mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD);
    }

    /**
     * The hardest-hitting melee weapon in the pack, or null if we are carrying none.
     *
     * <p>NULL IS LOAD-BEARING HERE and is why this is not just {@code bestWeapon(mod)}: the caller
     * reads null as "unarmed", which zeroes the damage term AND withdraws the shield bonus. A
     * method that falls back to whatever is in the hand can never say "unarmed", so an empty-handed
     * bot would score as armed.
     *
     * <p>It used to walk a hardcoded list of the six vanilla swords in a fixed order, which is both
     * a hardcode this project's design rules reject and slightly wrong -- it ranked GOLDEN (4
     * damage) above STONE (5), so a bot carrying both estimated its own damage low. Asking the
     * items for their numbers is shorter, needs no version split, and covers weapons the list never
     * knew about.
     */
    private static Item getBestSword(AltoClef mod) {
        Item best = null;
        float bestDamage = 0;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            float damage = adris.altoclef.util.helpers.ItemHelper.meleeDamageOf(stack.getItem());
            if (damage > bestDamage) {
                best = stack.getItem();
                bestDamage = damage;
            }
        }
        return best;
    }

    /**
     * Attack damage the item adds, straight from its own attribute modifiers.
     *
     * <p>Matched by the attribute's registry PATH rather than by an EntityAttributes constant: the
     * constant is spelled GENERIC_ATTACK_DAMAGE on one of the two versions this file builds for and
     * ATTACK_DAMAGE on the other, and the whole point here is to stop maintaining two spellings of
     * the same question. The registry id is "attack_damage" in both.
     *
     * <p>Returns 0 for anything that is not a weapon, which the caller reads as "we cannot deal
     * with it" -- an unarmed, unarmoured bot fleeing a zombie is correct; an armed one fleeing is
     * the bug this fixes.
     */
    private static float meleeDamageOf(Item item) {
        return adris.altoclef.util.helpers.ItemHelper.meleeDamageOf(item);
    }

    /** Anything in the pack we can stand on. Without one, height is not an option. */
    private static boolean hasPillarBlock(AltoClef mod) {
        for (net.minecraft.item.Item b : new net.minecraft.item.Item[]{
                Items.COBBLESTONE, Items.DIRT, Items.STONE, Items.NETHERRACK, Items.OAK_PLANKS}) {
            if (mod.getItemStorage().hasItem(b)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos isInsideFireAndOnFire(AltoClef mod) {
        boolean onFire = mod.getPlayer().isOnFire();
        if (!onFire) return null;
        BlockPos p = mod.getPlayer().getBlockPos();
        BlockPos[] toCheck = new BlockPos[]{
                p,
                p.add(1,0,0),
                p.add(1,0,-1),
                p.add(0,0,-1),
                p.add(-1,0,-1),
                p.add(-1,0,0),
                p.add(-1,0,1),
                p.add(0,0,1),
                p.add(1,0,1)
        };
        for (BlockPos check : toCheck) {
            Block b = mod.getWorld().getBlockState(check).getBlock();
            if (b instanceof AbstractFireBlock) {
                return check;
            }
        }
        return null;
    }

    private void putOutFire(AltoClef mod, BlockPos pos) {
        Optional<Rotation> reach = LookHelper.getReach(pos);
        if (reach.isPresent()) {
            if (LookHelper.isLookingAt(mod, pos)) {
                // Nav.pause() is the same requestPause, behind the seam that owns the engine.
                Nav.pause();
                AltoClef.getInstance().getInputControls().hold(Input.CLICK_LEFT);
                return;
            }
            LookHelper.lookAt(reach.get());
        }
    }

    private void doForceField(AltoClef mod) {
        killAura.tickStart();

        // Hit all hostiles close to us.
        List<Entity> entities = mod.getEntityTracker().getCloseEntities();
        // WHICH ONE IS THE FIGHT? The closest living hostile -- that is the one tungsten strikes;
        // the rest keep the old broad swat, because a force field's job is breadth.
        Entity nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (Entity e : entities) {
            if (!(e instanceof net.minecraft.entity.LivingEntity) || !e.isAlive()) {
                continue;
            }
            double d2 = e.squaredDistanceTo(mod.getPlayer());
            if (d2 < nearestSq) {
                nearestSq = d2;
                nearest = e;
            }
        }
        try {
            for (Entity entity : entities) {
                boolean shouldForce = false;
                if (mod.getBehaviour().shouldExcludeFromForcefield(entity)) continue;
                if (AbstractKillEntityTask.hasImmunity(entity)) continue;
                if (entity instanceof MobEntity) {
                    if (EntityHelper.isProbablyHostileToPlayer(mod, entity)) {
                        if (LookHelper.seesPlayer(entity, mod.getPlayer(), 10)) {
                            shouldForce = true;
                        }
                    }
                } else if (entity instanceof FireballEntity) {
                    // Ghast ball
                    shouldForce = true;
                }

                if (shouldForce) {
                    // ONE WRITER PER TARGET. The controller in the committed-fight branch aims
                    // with WindMouse at a predicted point; letting the aura smooth-look at the
                    // same mob in the same tick leaves the crosshair somewhere between the two.
                    if (entity == lockedOnEntity && tungstenDrivingMs > 0
                            && System.currentTimeMillis() - tungstenDrivingMs < 500) {
                        continue;
                    }
                    // THE NEAREST MOB IS STRUCK BY TUNGSTEN, WHATEVER ELSE IS HAPPENING.
                    // The committed-fight branch only fires when the chain gets that far, and
                    // measurement says it often does not: over four fights it committed twice,
                    // fled first once (mdRet3=81) and returned by an uncounted path once -- yet
                    // the zombie died every time. The killer, every time, was this force field.
                    // It is the one piece that runs on EVERY priority evaluation, so it is the
                    // only place a change reaches every fight.
                    //
                    // TriggerBot only SWINGS -- vanilla cooldown model, crit window, reach and
                    // angle gates -- and never touches the movement keys. That is precisely the
                    // property that kept this method off tungsten until now: the bot must stay
                    // able to chop wood while swatting. Aim still comes from the aura, and the
                    // angle gate simply refuses until it lands.
                    if (nearest != null && entity == nearest) {
                        LookHelper.smoothLook(mod, (net.minecraft.entity.LivingEntity) entity);
                        kaptainwutax.tungsten.combat.CombatController.triggerBot.tick(
                                mod.getPlayer(), entity);
                        mdAuraTungstenTicks++;
                        continue;
                    }
                    killAura.applyAura(entity);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        killAura.tickEnd(mod);
    }


    private CreeperEntity getClosestFusingCreeper(AltoClef mod) {
        double worstSafety = Float.POSITIVE_INFINITY;
        CreeperEntity target = null;
        try {
            List<CreeperEntity> creepers = mod.getEntityTracker().getTrackedEntities(CreeperEntity.class);
            for (CreeperEntity creeper : creepers) {
                if (creeper == null) continue;
                if (creeper.getClientFuseTime(1) < 0.001) continue;

                // We want to pick the closest creeper, but FIRST pick creepers about to blow
                // At max fuse, the cost goes to basically zero.
                double safety = getCreeperSafety(mod.getPlayer().getPos(), creeper);
                if (safety < worstSafety) {
                    worstSafety = safety;
                    target = creeper;
                }
            }
        } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            // IDK why but these exceptions happen sometimes. It's extremely bizarre and I
            // have no idea why.
            Debug.logWarning("Weird Exception caught and ignored while scanning for creepers: " + e.getMessage());
            return target;
        }
        return target;
    }

    /**
     * Count each incoming arrow ONCE, at the range it was fired from.
     *
     * <p>The tracker rebuilds its projectile list every tick from a world scan, so identity is
     * what separates "a new shot" from "the same arrow, one tick later" — hence
     * {@link CachedProjectile#entityId}. The first tick an arrow is visible is within a tick of
     * the release, so the gap between the bot and the arrow at that moment IS the range it was
     * shot from, without needing the shooter.
     *
     * <p>WHY IT MATTERS: dw already says where arrows LAND (3.79-5.03, max 6.30 — point blank).
     * It says nothing about the shots that missed. If the distant ones miss, the only lever left
     * is time inside the killing band; if the skeleton holds fire until close, closing faster buys
     * nothing at all. The two readings point at opposite fixes, and this is the counter that
     * separates them.
     *
     * <p>⛔ ANSWERED, 2026-08-12 — IT HOLDS FIRE. Six runs on the repaired course:
     * <pre>
     *   arrows fired (n/mean/max)   min_hp   landed
     *   2 / 5.56 / 5.58               20       0
     *   2 / 4.36 / 6.37               16       1
     *   3 / 5.56 / 6.15               13       2
     *   2 / 4.54 / 5.33               13       2
     *   3 / 5.43 / 8.48               15       1
     *   41 / 10.38 / 13.07             4       6    <- the bot never closed (mdFar 1979)
     * </pre>
     * A normal fight costs the bot 2-3 shots, ALL fired from 4.4-5.6 blocks, of which 1-2 land.
     * Nothing is fired at the approach, so every hypothesis about shortening time-under-fire while
     * closing was aimed at a phase that does not exist — eight of them are now closed, and this is
     * why none could have worked. The outlier is the other regime: stuck at range the skeleton
     * fires 41 and lands 6, about 15% against 50-70% point blank, which is the bot's jitter working
     * exactly as earlier passes measured and failing inside six blocks.
     *
     * <p>WHAT THAT LEAVES. At five blocks an arrow crosses in under two ticks, so reacting to the
     * ARROW cannot work — and every dodge this repo has built reacts to the arrow. The bow draw
     * takes TWENTY ticks and is visible client-side as the using-item flag (tracked data id 8, the
     * same flag whose event hook was too narrow to notice arrows at all). A full second of warning
     * is sitting there unread. The next instrument counts it; the fix after that acts on it.
     */
    private void noticeArrows(AltoClef mod) {
        try {
            ClientPlayerEntity self = mod.getPlayer();
            if (self == null) return;
            Vec3d plyPos = self.getPos();
            for (CachedProjectile projectile : mod.getEntityTracker().getProjectiles()) {
                if (projectile.entityId < 0 || projectile.position == null) continue;
                if (!seenArrowIds.add(projectile.entityId)) continue;
                int gap = (int) Math.round(Math.sqrt(projectile.position.squaredDistanceTo(plyPos)) * 1000.0);
                mdArrows++;
                mdArrowGapMilli += gap;
                if (gap > mdArrowGapMaxMilli) mdArrowGapMaxMilli = gap;
            }
            // The set is per-run state, and a run is bounded; clearing it on reset (with the
            // counters) is what keeps it from growing across a whole session.
        } catch (Exception ignored) {
            // an instrument must never be the thing that breaks a fight
        }
    }

    /**
     * Watch every nearby shooter's draw, one episode at a time.
     *
     * <p>Counted per entity id so two skeletons cannot blur into one episode, and only for
     * RangedAttackMob — the same vanilla property the flee guard asks — so a zombie raising a
     * shield or a villager eating never lands in these numbers.
     */
    private void noticeDraws(AltoClef mod) {
        try {
            ClientPlayerEntity self = mod.getPlayer();
            if (self == null) return;
            java.util.Set<Integer> stillDrawing = new java.util.HashSet<>();
            boolean inBand = false;
            for (LivingEntity e : mod.getEntityTracker().getHostiles()) {
                if (!(e instanceof net.minecraft.entity.ai.RangedAttackMob)) continue;
                if (e == null || !e.isAlive()) continue;
                double gap = e.distanceTo(self);
                if (gap >= 2.5 && gap <= 7.0) inBand = true;
                // Health drops, summed per entity: what our swings ACTUALLY removed.
                Float prev = lastTargetHp.put(e.getId(), e.getHealth());
                if (prev != null && prev > e.getHealth()) {
                    mdDamageDealtTenths += (int) Math.round((prev - e.getHealth()) * 10.0);
                    // ATTRIBUTION: a drop arriving within ~3 ticks of our swing is our swing. This
                    // is the ceiling-free half of the ledger -- mdSwingHits over gPassed is the
                    // fraction of swings that actually removed health, and unlike the sum it keeps
                    // moving after the target is nearly dead.
                    if (System.currentTimeMillis()
                            - kaptainwutax.tungsten.combat.TriggerBot.lastSwingMs <= 150) {
                        mdSwingHits++;
                    }
                }
                if (!e.isUsingItem()) continue;
                int id = e.getId();
                stillDrawing.add(id);
                Integer had = drawTicksById.get(id);
                if (had == null) {
                    // the tick the draw STARTS: record how far away it began
                    mdDraws++;
                    mdDrawGapMilli += (int) Math.round(e.distanceTo(self) * 1000.0);
                    drawTicksById.put(id, 1);
                    // ⛔ ACT ON THE DRAW, NOT ON THE ARROW (behind a pin, default off).
                    // This is the tick the warning starts. Step ACROSS the shooter's line: the
                    // arrow is aimed where we are now, so lateral movement is what the aim cannot
                    // follow, and it keeps the bot facing what it is fighting — the swing gate
                    // refuses past 40 degrees, so a dodge that turns away cannot also attack.
                    // The side alternates with the draw count so a fight does not walk the bot
                    // steadily off one edge of the island.
                    // A SWING IN HAND OUTRANKS A SIDESTEP. The dodge is buying misses with
                    // swings: it reaches 24% hit rate against a 38% baseline, and still does not
                    // pay because arrows FIRED go 3.15 -> 5.55 with the approach unchanged, i.e.
                    // the fight runs longer after contact. Intervals between our own swings read
                    // 19-22 ticks when contact holds and 90-123 when it does not, against a
                    // 12-tick cooldown.
                    //
                    // Declining the sidestep only where the swing is actually available costs
                    // little avoidance -- a skeleton draws for 20 ticks and our cooldown is 12, so
                    // these are a minority of draw ticks -- and stops the dodge walking the bot
                    // out of its own attack.
                    // ⛔ THE FIRST VERSION OF THIS GUARD NEVER FIRED ONCE IN 20 RUNS, and the
                    // mechanism gate is the only reason that was noticed rather than reported as a
                    // null. It required the target inside REACH (3.0) while a skeleton was DRAWING
                    // -- and a skeleton draws at range and backs away as you close, mean gap 5.6
                    // blocks at draw. The condition was close to unreachable by construction.
                    //
                    // The zone that matters is not "can hit right now" but "close enough that a
                    // sidestep costs a swing", which is the same 4.5 the rest of this file already
                    // uses for inRange. Charged cooldown still required: yielding while the swing
                    // is recharging would give up avoidance for nothing.
                    boolean swingInHand = false;
                    if (kaptainwutax.tungsten.TungstenConfig.get().combatDodgeYieldsToSwing) {
                        swingInHand = self.getAttackCooldownProgress(0f) > 0.9f
                                && kaptainwutax.tungsten.combat.TriggerBot.eyeToHitbox(self, e)
                                        <= kaptainwutax.tungsten.combat.TriggerBot.REACH + 1.5;
                    }
                    if (swingInHand) mdDodgeYielded++;
                    if (kaptainwutax.tungsten.TungstenConfig.get().combatDodgeOnDraw && !swingInHand) {
                        Vec3d fromShooter = self.getPos().subtract(e.getPos());
                        Vec3d flat = new Vec3d(fromShooter.x, 0, fromShooter.z);
                        if (flat.lengthSquared() > 1.0e-6) {
                            Vec3d b = flat.normalize();
                            double side = (mdDraws % 2 == 0) ? 1.0 : -1.0;
                            // ⛔ THIS PATH PASSED A PURE PERPENDICULAR AND THE OTHER ONE DOES NOT,
                            // AND DODGE_PRESS_BIAS's OWN JAVADOC PREDICTS WHAT THAT COSTS: "at 0 the
                            // sidestep is purely perpendicular: it survives the arrow and holds the
                            // range open for ever, which against a ranged mob is a draw the bot
                            // always loses on damage." The in-flight dodge blends the bias in; this
                            // one never did, so the two dodges disagreed about the same question.
                            //
                            // MEASURED, 40 launches, 20 an arm, mechanism gate passed on the median
                            // (dodgeDrive 18 -> 64):
                            //     arrows fired at the bot   2.65 -> 7.80    three times the exposure
                            //     arrows landed             1.51 -> 1.44    unchanged
                            //     => skeleton hit rate       57% -> 18%     the sidestep WORKS
                            //     ticks to first swing      50.7 -> 106.7   and the approach doubles
                            // Exactly the draw the javadoc describes: the arrows miss, and the bot
                            // stands in the open long enough to be shot three times as often.
                            //
                            // So the heading gets the same closing component the in-flight dodge
                            // has. The perpendicular stays dominant at 0.6, so leaving the line is
                            // still the primary motion and the approach is what it does with the
                            // rest -- which is the constant's stated design, applied where it was
                            // missing rather than re-tuned.
                            Vec3d perp = new Vec3d(-b.z * side, 0, b.x * side);
                            Vec3d dodgeDir = perp
                                    .add(b.multiply(-DODGE_PRESS_BIAS))   // b points AWAY; close in
                                    .normalize();
                            kaptainwutax.tungsten.task.ProjectileDodge.hold(
                                    dodgeDir.x, dodgeDir.z, DODGE_HOLD_TICKS);
                        }
                    }
                } else {
                    drawTicksById.put(id, had + 1);
                }
                mdDrawTicks++;
                int len = drawTicksById.get(id);
                if (len > mdDrawMaxTicks) mdDrawMaxTicks = len;
            }
            // a draw that stopped is an episode ended -- release or cancel, both end the warning
            drawTicksById.keySet().removeIf(id -> !stillDrawing.contains(id));
            if (inBand) mdBandTicks++;
            // Stamped from the SAME tick loop that owns mdBandTicks, so the two share a clock.
            // Reading a per-run counter against one that resets elsewhere is how "in reach 259
            // ticks but only 71 evaluations" was once taken as evidence of a broken offence.
            if (mdBandToFirstSwing < 0 && kaptainwutax.tungsten.combat.TriggerBot.gPassed > 0) {
                mdBandToFirstSwing = mdBandTicks;
            }
        } catch (Exception ignored) {
            // an instrument must never be the thing that breaks a fight
        }
    }

    /** Nearest living hostile's position, or our own when there is none — a zero gap then. */
    private net.minecraft.util.math.Vec3d projectileClosestPos(AltoClef mod) {
        try {
            ClientPlayerEntity self = mod.getPlayer();
            net.minecraft.util.math.Vec3d best = self.getPos();
            double bestSq = Double.MAX_VALUE;
            for (LivingEntity e : mod.getEntityTracker().getHostiles()) {
                if (e == null || !e.isAlive()) continue;
                double d = e.getPos().squaredDistanceTo(self.getPos());
                if (d < bestSq) { bestSq = d; best = e.getPos(); }
            }
            return best;
        } catch (Exception ignored) {
            return mod.getPlayer().getPos();
        }
    }

    private boolean isProjectileClose(AltoClef mod) {
        noticeArrows(mod);
        noticeDraws(mod);
        List<CachedProjectile> projectiles = mod.getEntityTracker().getProjectiles();
        Vec3d plyPos = mod.getPlayer().getPos();
        try {
            for (CachedProjectile projectile : projectiles) {
                double sqDist = projectile.position.squaredDistanceTo(plyPos);
                if (sqDist < 150) {
                    boolean isGhastBall = projectile.projectileType == FireballEntity.class;
                    if (isGhastBall) {
                        Optional<Entity> ghastBall = mod.getEntityTracker().getClosestEntity(FireballEntity.class);
                        Optional<Entity> ghast = mod.getEntityTracker().getClosestEntity(GhastEntity.class);
                        if (ghastBall.isPresent() && ghast.isPresent() && runAwayTask == null
                                && (mod.getClientBaritone() == null || Nav.isSafeToCancel())) {
                            if (mod.getClientBaritone() != null)
                                Nav.pause();
                            LookHelper.lookAt(mod, ghast.get().getEyePos());
                        }
                        return false;
                    }
                    if (projectile.projectileType == DragonFireballEntity.class) {
                        continue;
                    }
                    if (projectile.projectileType == ArrowEntity.class || projectile.projectileType == SpectralArrowEntity.class || projectile.projectileType == SmallFireballEntity.class) {
                        PlayerEntity player = mod.getPlayer();
                        if (player.squaredDistanceTo(projectile.position) < player.squaredDistanceTo(projectile.position.add(projectile.velocity))) {
                            continue;
                        }
                    }

                    Vec3d expectedHit = ProjectileHelper.calculateArrowClosestApproach(projectile, mod.getPlayer());
                    Vec3d delta = plyPos.subtract(expectedHit);
                    double horizontalDistanceSq = delta.x * delta.x + delta.z * delta.z;
                    double verticalDistance = Math.abs(delta.y);

                    // Skip stale projectiles with near-zero velocity (despawned/ground-stuck)
                    if (projectile.velocity.lengthSquared() < 0.001) continue;

                    // Use getLookingProbability + wide detection (from autoclef)
                    double lookProb = LookHelper.getLookingProbability(projectile.position, plyPos, projectile.velocity.normalize());
                    if (lookProb > 0.7 && horizontalDistanceSq < ARROW_DETECT_HORIZONTAL_SQ
                            && verticalDistance < ARROW_KEEP_DISTANCE_VERTICAL) {
                        // ⛔ THE COMMENT HERE SAID "PERPENDICULAR" AND THE ARITHMETIC SAID "+180".
                        //
                        // It took the rotation toward expectedHit and inverted it, which is
                        // "directly away from where the arrow will land" -- not perpendicular. And
                        // a skeleton AIMS AT US, so expectedHit sits practically on top of plyPos:
                        // the vector being inverted is near zero, and the yaw of a near-zero vector
                        // is numerically unstable. The dodge direction was therefore NOISE, which
                        // is why the bot never closed on mob_skeleton -- closest_gap=6.83 over a
                        // whole 300 s course, in range on 105 of 940 ticks, 74 damage taken.
                        //
                        // It also explains the three refutations already on record: removing the
                        // dodge doubled the damage (17.25 -> 32.8) because moving ANYWHERE beats
                        // standing in arrow fire, and the three geometry variants measured the same
                        // (17.25 / 19.0 / 17.2) because all three still steered by this vector.
                        //
                        // The arrow's VELOCITY is well-conditioned whatever the range, so the
                        // sidestep is taken from that. Perpendicular leaves the line for the least
                        // distance travelled; the bias toward the shooter is what turns a dodge
                        // that merely survives into one that also closes, and closing is the only
                        // thing that ends a fight against something that outranges us.
                        // ⛔ KNOWN EDGE CASE, RECORDED NOT PATCHED: A STEEP ARROW GIVES NO HEADING.
                        // perp is built from the arrow's HORIZONTAL components, so for a steeply
                        // arcing shot both approach zero and this normalize() returns Vec3d.ZERO
                        // (Minecraft zeroes below ~1e-4). The heading then collapses, ProjectileDodge
                        // sees fwd=0 and side=0, presses nothing, and the dodge SILENTLY does not
                        // happen -- the same near-zero-vector instability this block was written to
                        // remove, reappearing in a different variable.
                        // It does not bite on this course (a skeleton at 12 blocks shoots flat), which
                        // is why it has not shown up, and it is exactly the kind of silent no-op that
                        // has cost this repository whole investigations. The fix is to fall back to a
                        // heading derived from the shooter's bearing when the horizontal flight is
                        // degenerate -- it needs a course where arrows actually arc before it can be
                        // measured, so it is written down rather than guessed at.
                        Vec3d flight = projectile.velocity.normalize();
                        Vec3d perp = new Vec3d(-flight.z, 0, flight.x).normalize();
                        // A STEEP ARROW HAS NO HORIZONTAL FLIGHT AND perp COLLAPSES TO ZERO.
                        // normalize() returns Vec3d.ZERO below ~1e-4, so on an arcing shot fwd and
                        // side both come out 0, ProjectileDodge presses nothing, and the dodge
                        // SILENTLY does not happen -- the same near-zero-vector failure this block
                        // was written to remove, in a different variable.
                        //
                        // STRICTLY DOMINANT: this branch can only run where the code above produced
                        // NO heading at all, so no working case changes and there is no arm for it
                        // to lose. It needs a regression check, not an A/B.
                        //
                        // (It shipped once before and was lost in the revert of the key-release
                        // change, which was committed alongside it. Re-applied on its own.)
                        if (perp.lengthSquared() < 1.0e-6) {
                            Vec3d fromShooter = plyPos.subtract(projectile.position);
                            Vec3d flat = new Vec3d(fromShooter.x, 0, fromShooter.z);
                            if (flat.lengthSquared() > 1.0e-6) {
                                Vec3d b = flat.normalize();
                                perp = new Vec3d(-b.z, 0, b.x);
                            }
                        }

                        // Which side? Keep going the way we are already off the line -- that is the
                        // shorter way out of it. When the arrow is dead-on that offset is the same
                        // near-zero vector as above, so it only breaks the tie and never steers.
                        if (perp.dotProduct(delta) < 0) {
                            perp = perp.multiply(-1);
                        }

                        // The shooter is back up the flight line. Pure perpendicular holds the
                        // range open for ever, so blend in the approach -- BUT ONLY WHILE THERE IS
                        // RANGE LEFT TO CLOSE.
                        //
                        // ⛔ AT POINT-BLANK THE PRESS BIAS IS ACTIVELY WRONG. The remaining damage on
                        // this course is one arrow landing at gap 2.29-3.80, where flight time is
                        // under a tick and no sidestep can outrun it. What makes a point-blank shot
                        // MISS is lateral speed across the shooter's aim, and every unit of the
                        // forward bias is a unit taken out of that -- while the dodge is also
                        // overriding the controller's circle-strafe for those ticks. So the closing
                        // half of the heading fades out as the shot gets close, leaving a pure
                        // sidestep exactly where a sidestep is the only thing that can work.
                        Vec3d toShooter = projectile.position.subtract(plyPos);
                        toShooter = new Vec3d(toShooter.x, 0, toShooter.z);
                        double shotRange = toShooter.length();
                        // ⛔ REVERTED ON A PROPERLY CONTROLLED A/B. The point-blank special case
                        // -- drop the closing bias inside melee reach, on the argument that an
                        // arrow fired that close arrives in under a tick and only lateral speed can
                        // make it miss -- measured as NOTHING when both arms ran in ONE session:
                        //     with it     mean 1.17 arrows  sd 1.07  n=21
                        //     without it  mean 1.16 arrows  sd 0.87  n=22
                        // A difference of 0.01. The "2.1 sigma" it was once shipped on, and the 1.7
                        // it was corrected to, were BOTH artefacts of comparing arms built hours
                        // apart -- exactly what checklist rule 4j was written for, and the cleanest
                        // demonstration of it in the repository.
                        double bias = DODGE_PRESS_BIAS;
                        Vec3d dodgeDir = toShooter.lengthSquared() < 1.0e-6
                                ? perp
                                : perp.add(toShooter.normalize().multiply(bias)).normalize();

                        // The heading is kept as a VECTOR now, not a yaw. The primitive that
                        // executes it strafes in the player's own frame, so nothing here has to
                        // decide where the head points.
                        suggestedDodgeX = dodgeDir.x;
                        suggestedDodgeZ = dodgeDir.z;
                        // shotRange is already computed above for the press bias; the hold length
                        // is the one other thing it can answer, and until now nothing asked.
                        suggestedDodgeTicks = kaptainwutax.tungsten.TungstenConfig.get()
                                .combatDodgeHoldByRange
                                ? Math.max(2, Math.min(DODGE_HOLD_TICKS,
                                        (int) Math.ceil(shotRange / ARROW_BLOCKS_PER_TICK) + 1))
                                : DODGE_HOLD_TICKS;

                        if (runAwayTask == null && (mod.getClientBaritone() == null || Nav.isSafeToCancel())) {
                            if (mod.getClientBaritone() != null)
                                Nav.pause();
                        }
                        return true;
                    }
                }
            }

        } catch (ConcurrentModificationException e) {
            Debug.logWarning(e.getMessage());
        }

        // TODO refactor this into something more reliable for all mobs
        for (SkeletonEntity skeleton : mod.getEntityTracker().getTrackedEntities(SkeletonEntity.class)) {
            if (skeleton.distanceTo(mod.getPlayer()) > 10 || !skeleton.canSee(mod.getPlayer())) continue;

            // when the skeleton is about to shoot (it takes 5 ticks to raise the shield)
            if (skeleton.getItemUseTime() > 15) {
                return true;
            }
        }

        return false;
    }

    private Optional<Entity> getUniversallyDangerousMob(AltoClef mod) {
        // Wither skeletons are dangerous because of the wither effect. Oof kinda obvious.
        // If we merely force field them, we will run into them and get the wither effect which will kill us.

        Class<?>[] dangerousMobs = new Class[]{Entities.WARDEN, WitherEntity.class, WitherSkeletonEntity.class,
                HoglinEntity.class, ZoglinEntity.class, PiglinBruteEntity.class, VindicatorEntity.class};

        double range = SAFE_KEEP_DISTANCE - 2;

        for (Class<?> dangerous : dangerousMobs) {
            Optional<Entity> entity = mod.getEntityTracker().getClosestEntity(dangerous);

            if (entity.isPresent()) {
                if (entity.get().squaredDistanceTo(mod.getPlayer()) < range * range && EntityHelper.isAngryAtPlayer(mod, entity.get())) {
                    return entity;
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Is the thing endangering us something that attacks from a distance?
     *
     * <p>Asked by the flee branch, which is a melee defence being applied to a ranged attack. Only
     * hostiles close enough to be the reason we are in danger count -- a shooter across the map is
     * not why {@link #isInDanger} said yes, and letting it veto the flee would leave the bot
     * standing in a crowd of zombies. Angry-at-us is required for the same reason.
     *
     * <p>A crowd containing ANY melee attacker still flees: the case this turns off is the pure
     * shooting gallery, where closing is the only thing that ends it.
     */
    private boolean endangeredByShooter(AltoClef mod) {
        try {
            ClientPlayerEntity player = mod.getPlayer();
            if (player == null) return false;
            boolean sawShooter = false;
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                for (LivingEntity entity : mod.getEntityTracker().getHostiles()) {
                    if (entity == null || !entity.isAlive()) continue;
                    if (!entity.isInRange(player, SAFE_KEEP_DISTANCE * 1.5)) continue;
                    if (mod.getBehaviour().shouldExcludeFromForcefield(entity)) continue;
                    if (!EntityHelper.isAngryAtPlayer(mod, entity)) continue;
                    // RESOLVED, AND THE EARLIER NOTE HERE WAS WRONG -- DO NOT WIDEN THIS. It used
                    // to read: the scan reaches SAFE_KEEP_DISTANCE * 1.5 = 12.0 while mob_skeleton
                    // spawns at 12.5, so the guard is "narrower than its own test case" and a
                    // shooter at 14 would exercise the reflex this is meant to suppress.
                    //
                    // It cannot. isInDanger's hostile scan uses `reach = (health <= 10 &&
                    // !witchNearby) ? SAFE_KEEP_DISTANCE * 1.5 : SAFE_KEEP_DISTANCE` -- 12.0 in
                    // exactly the state where the flee branch bids, and 8.0 otherwise. This guard
                    // is therefore never narrower than the test it guards, and is wider in the
                    // common case. A shooter outside 12.0 does not make isInDanger true either, so
                    // there is no flee bid at that range for the guard to decline. Nothing to fix;
                    // widening it would only make the two radii disagree.
                    if (entity instanceof net.minecraft.entity.ai.RangedAttackMob) {
                        sawShooter = true;
                    } else {
                        // Something in the danger radius hits us with its hands. Flee is still the
                        // right answer to that, so one melee attacker settles it.
                        return false;
                    }
                }
            }
            return sawShooter;
        } catch (Exception e) {
            // The flee reflex is the safe default: if we cannot tell, do what we did before.
            return false;
        }
    }

    private boolean isInDanger(AltoClef mod) {
        boolean witchNearby = mod.getEntityTracker().entityFound(WitchEntity.class);
        float health = mod.getPlayer().getHealth();

        // DANGER REQUIRES A THREAT.
        // Low health alone used to return true right here, with nothing hostile anywhere. The
        // chain then bid 70, out-bid the user task's 50 for EVERY tick, and ran
        // RunAwayFromHostilesTask away from an empty field. Standing still, the bot never ate and
        // never gathered, so health never recovered and the bid never dropped. Measured on @gamer:
        // ten minutes frozen on one block at 2.5 hearts, "Mob Defense, priority: 70.0" every
        // sample, the beat-the-game task alive the whole time and never once ticked. That also
        // explains the older "no food, six minutes at 1.1 HP" observation — same deadlock.
        // Health is a MULTIPLIER on danger, not danger itself: it now widens the radius at which a
        // real mob counts, and a real mob is still required.
        if (mod.getPlayer().hasStatusEffect(StatusEffects.WITHER) ||
                (mod.getPlayer().hasStatusEffect(StatusEffects.POISON) && !witchNearby)) {
            // Damage over time IS an active threat with no mob to point at, so it stays.
            return true;
        }
        // Low and STILL BEING HIT — disengage. This half is load-bearing in a duel, where the
        // thing hurting us is a player and therefore not in getHostiles() at all: dropping it
        // outright took the pvp duels from 9/12 to 1/12 over 12 repeats, which is far too one-sided
        // to be the coin-flip a mirror duel usually is. The clause that had to go was the one that
        // fired with NOTHING hitting us, which is the @gamer freeze.
        if (health <= 10 && System.currentTimeMillis() - lastDamageMs < RECENT_DAMAGE_MS) {
            return true;
        }
        if (WorldHelper.isVulnerable() || health <= 10) {
            // If hostile mobs are nearby...
            try {
                ClientPlayerEntity player = mod.getPlayer();
                List<LivingEntity> hostiles = mod.getEntityTracker().getHostiles();

                synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                    // Hurt and unarmoured, a mob one step further away is just as lethal, so the
                    // radius grows as health falls rather than the rule short-circuiting.
                    double reach = (health <= 10 && !witchNearby)
                            ? SAFE_KEEP_DISTANCE * 1.5 : SAFE_KEEP_DISTANCE;
                    for (Entity entity : hostiles) {
                        if (entity.isInRange(player, reach)
                                && !mod.getBehaviour().shouldExcludeFromForcefield(entity)
                                && EntityHelper.isAngryAtPlayer(mod, entity)) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                Debug.logWarning("Weird multithread exception. Will fix later. " + e.getMessage());
            }
        }
        return false;
    }

    // --- Player threat helpers (ported from autoclef) ---

    public Optional<Entity> getAvoidTarget(AltoClef mod) {
        try {
            return mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                    entity -> {
                        if (entity == null) return false;
                        if (mod.getBehaviour().shouldExcludeFromAttack(entity)) return false;
                        if (mod.getPlayer() != null
                                && entity.distanceTo(mod.getPlayer()) > SAFE_KEEP_DISTANCE) return false;
                        if (targetEntity != null && entity == targetEntity) return false;
                        if (entity.getName() == null) return false;
                        String playerName = entity.getName().getString();
                        return mod.getDamageTracker().getThreatTable().shouldAvoid(playerName)
                                && !mod.getDamageTracker().getThreatTable().shouldAttack(playerName);
                    },
                    PlayerEntity.class);
        } catch (Exception e) {
            Debug.logWarning("Weird multithread exception in getAvoidTarget: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<Entity> getAttackPlayer(AltoClef mod) {
        try {
            return mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(),
                    entity -> entity != null
                            && entity.getName() != null
                            && !mod.getBehaviour().shouldExcludeFromAttack(entity)
                            && entity.distanceTo(mod.getPlayer()) < DANGER_KEEP_DISTANCE
                            && mod.getEntityTracker().isEntityReachable(entity)
                            && mod.getEntityTracker().isPlayerLoaded(entity.getName().getString())
                            && mod.getDamageTracker().getThreatTable().shouldAttack(entity.getName().getString()),
                    PlayerEntity.class);
        } catch (Exception e) {
            Debug.logWarning("Weird multithread exception in getAttackPlayer: " + e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Runs away from a single threatening player entity until distance is sufficient.
     */
    public static class RunAwayFromPlayersTask extends RunAwayFromEntitiesTask {
        private final Entity _avoidEntity;
        private final double _distanceToRun;
        private boolean _finished = false;

        public RunAwayFromPlayersTask(Entity toRunAwayFrom, double distanceToRun) {
            super(() -> List.of(toRunAwayFrom), distanceToRun, true, 0.1);
            _avoidEntity = toRunAwayFrom;
            _distanceToRun = distanceToRun;
        }

        @Override
        protected Task onTick() {
            AltoClef mod = AltoClef.getInstance();
            if (_avoidEntity != null && mod != null) {
                if (_avoidEntity.distanceTo(mod.getPlayer()) >= _distanceToRun) {
                    _finished = true;
                } else {
                    _finished = false;
                    return super.onTick();
                }
            }
            setDebugState("NO RUNAWAY TARGET / MAYBE BUG");
            return new IdleTask();
        }

        @Override
        public boolean isFinished() {
            return super.isFinished() || _finished;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RunAwayFromPlayersTask task && task._avoidEntity == _avoidEntity;
        }

        @Override
        protected String toDebugString() {
            if (_avoidEntity != null && _avoidEntity.getName() != null)
                return "Run away from " + _avoidEntity.getName().getString();
            return "Run away from players (NO TARGET)";
        }
    }

    public void setTargetEntity(Entity entity) {
        targetEntity = entity;
    }

    public void resetTargetEntity() {
        targetEntity = null;
    }

    public void setForceFieldRange(double range) {
        killAura.setRange(range);
    }

    public void resetForceField() {
        killAura.setRange(Double.POSITIVE_INFINITY);
    }

    public boolean isDoingAcrobatics() {
        return doingFunkyStuff;
    }

    public boolean isPuttingOutFire() {
        return wasPuttingOutFire;
    }

    @Override
    public boolean isActive() {
        // We're always checking for mobs
        return true;
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // Task is done, so I guess we move on?
    }

    @Override
    public String getName() {
        return "Mob Defense";
    }
}