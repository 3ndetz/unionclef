package adris.altoclef.chains;

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
import baritone.Baritone;
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
//#if MC < 12111
import net.minecraft.item.SwordItem;
//#endif
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
    private static final double DANGER_KEEP_DISTANCE = 30;
    private static final double CREEPER_KEEP_DISTANCE = 10;
    private static final double ARROW_KEEP_DISTANCE_HORIZONTAL = 2;
    private static final double ARROW_KEEP_DISTANCE_VERTICAL = 10;
    // Wider detection radius for arrow approach (from autoclef: horizontalDistanceSq < 1000)
    private static final double ARROW_DETECT_HORIZONTAL_SQ = 1000;
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
    private Rotation suggestedProjectileRotation;
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
            mod.getClientBaritone().getPathingBehavior().requestPause();
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

    @Override
    public float getPriority() {
        // SIXTEEN DEATHS TO ZOMBIES IN ONE RUN. Either this chain never gets the tick, or it gets
        // it and its answer does not save the bot. Those need opposite fixes, so count them apart.
        mdPriorityCalls++;
        cachedLastPriority = getPriorityInner();
        if (cachedLastPriority > 0) mdWon++;
        // If no task was set but a non-zero priority was returned, that's an inconsistent
        // state — drop priority so we don't claim control without doing anything.
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
        Optional<Entity> universallyDangerous = getUniversallyDangerousMob(mod);
        if (universallyDangerous.isPresent() && mod.getPlayer().getHealth() <= 10) {
            mdFlee++;
            runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
            setTask(runAwayTask);
            mdRet0++; return 70;
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
                    && (mod.getClientBaritone() == null || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())
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
                    && (mod.getClientBaritone() == null || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())
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
                runAwayTask = new DodgeProjectilesTask(ARROW_KEEP_DISTANCE_HORIZONTAL, ARROW_KEEP_DISTANCE_VERTICAL);
                setTask(runAwayTask);
            } else if (suggestedProjectileRotation != null) {
                // Safe ground: instant sprint+jump perpendicular to arrow (from autoclef)
                LookHelper.lookAt(mod, suggestedProjectileRotation, false);
                mod.getInputControls().tryPress(Input.SPRINT);
                mod.getInputControls().tryPress(Input.MOVE_FORWARD);
                mod.getInputControls().tryPress(Input.JUMP);
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
                runAwayTask = new RunAwayFromHostilesTask(DANGER_KEEP_DISTANCE, true);
                setTask(runAwayTask);
                mdRet3++; return 70;
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
            toDealWithList.sort(Comparator.comparingDouble((entity) -> mod.getPlayer().distanceTo(entity)));

            if (!toDealWithList.isEmpty()) {

                // Depending on our weapons/armor, we may choose to straight up kill hostiles if we're not dodging their arrows.
                //#if MC < 12111
                SwordItem bestSword = getBestSword(mod);
                //#else
                //$$ var bestSword = getBestSword(mod);
                //#endif

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
                    if (mod.getControllerExtras().inRange(toKill)) {
                        kaptainwutax.tungsten.combat.WeaponSelector.equipBestMelee(mod.getPlayer());
                        tungstenCombat.tick(mod.getPlayer(), toKill, mod.getWorld());
                        tungstenDrivingMs = System.currentTimeMillis();
                        mdTungstenTicks++;
                    }

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
                Rotation targetRotation = LookHelper.getLookRotation(mod, player.getPos());
                float invertedYaw = (targetRotation.getYaw() - 90) % 360;
                if (invertedYaw < 0) invertedYaw += 360;
                suggestedProjectileRotation = new Rotation(invertedYaw, 0f);
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

    //#if MC < 12111
    private static SwordItem getBestSword(AltoClef mod) {
        Item[] SWORDS = new Item[]{Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD,
                Items.STONE_SWORD, Items.WOODEN_SWORD};

        SwordItem bestSword = null;
        for (Item item : SWORDS) {
            if (mod.getItemStorage().hasItem(item)) {
                bestSword = (SwordItem) item;
                break;
            }
        }
        return bestSword;
    }
    //#else
    //$$ // TODO [1.21.11] sword item class deleted — return Item and get damage from component
    //$$ private static Item getBestSword(AltoClef mod) {
    //$$     Item[] SWORDS = new Item[]{Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD,
    //$$             Items.STONE_SWORD, Items.WOODEN_SWORD};
    //$$     for (Item item : SWORDS) {
    //$$         if (mod.getItemStorage().hasItem(item)) {
    //$$             return item;
    //$$         }
    //$$     }
    //$$     return null;
    //$$ }
    //#endif

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
        if (item == null) {
            return 0;
        }
        try {
            ItemStack stack = new ItemStack(item);
            var comp = stack.get(net.minecraft.component.DataComponentTypes.ATTRIBUTE_MODIFIERS);
            if (comp == null) {
                return 0;
            }
            float sum = 0;
            for (var entry : comp.modifiers()) {
                String path = entry.attribute().getKey()
                        .map(k -> k.getValue().getPath()).orElse("");
                if ("attack_damage".equals(path)) {
                    sum += (float) entry.modifier().value();
                }
            }
            return sum;
        } catch (Throwable t) {
            // A reading that throws must not decide a fight. Say "no weapon" and let the bot flee,
            // which is the safe half of the choice.
            return 0;
        }
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
            Baritone b = mod.getClientBaritone();
            if (LookHelper.isLookingAt(mod, pos)) {
                if (b != null) {
                    b.getPathingBehavior().requestPause();
                    AltoClef.getInstance().getInputControls().hold(Input.CLICK_LEFT);
                }
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

    private boolean isProjectileClose(AltoClef mod) {
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
                                && (mod.getClientBaritone() == null || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
                            if (mod.getClientBaritone() != null)
                                mod.getClientBaritone().getPathingBehavior().requestPause();
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
                        // Calculate dodge direction: sprint perpendicular to arrow trajectory
                        Rotation targetRotation = LookHelper.getLookRotation(mod, expectedHit);
                        float invertedYaw = (targetRotation.getYaw() + 180) % 360;
                        if (invertedYaw < 0) invertedYaw += 360;
                        suggestedProjectileRotation = new Rotation(invertedYaw, 0f);

                        if (runAwayTask == null && (mod.getClientBaritone() == null || mod.getClientBaritone().getPathingBehavior().isSafeToCancel())) {
                            if (mod.getClientBaritone() != null)
                                mod.getClientBaritone().getPathingBehavior().requestPause();
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