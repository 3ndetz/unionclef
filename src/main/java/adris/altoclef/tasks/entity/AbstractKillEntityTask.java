package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.KillAuraHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import kaptainwutax.tungsten.path.movements.Input;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import adris.altoclef.tasks.movement.GetToEntityTask;

import net.minecraft.entity.LivingEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Attacks an entity, but the target entity must be specified.
 * For PlayerEntity targets: uses smooth look (WindMouse) only — no instant rotation (anti-cheat).
 * For mob targets: keeps original instant-aim behavior so speedrun is not broken.
 */
public abstract class AbstractKillEntityTask extends AbstractDoToEntityTask {
    private static final double OTHER_FORCE_FIELD_RANGE = 2;

    // Not the "striking" distance, but the "ok we're close enough, lower our guard for other mobs and focus on this one" range.
    private static final double CONSIDER_COMBAT_RANGE = 10;

    // Player PvP strategy fields (player-only)
    private static final TimerGame _attackStrategyTimer = new TimerGame(15);

    /** Tungsten's duelling engine -- aim, spacing, strafe, crit timing, disengage. One per task. */
    private final kaptainwutax.tungsten.combat.CombatController _combat =
            new kaptainwutax.tungsten.combat.CombatController();
    /** When we entered striking range with the target's health unchanged; 0 = not in reach. */
    private long _inReachSinceMs = 0L;
    /** How long in reach without the target losing health before we blame the angle. Set to match
     *  the old HITS_BEFORE_REPOSITION at roughly a swing a second, so giving up keeps its former
     *  patience rather than acquiring a new one by accident. */
    private static final long NO_DAMAGE_MS = 4000L;
    /** Ticks the fight ran on tungsten. Counted so "are mobs on tungsten" is a number. */
    public static volatile int kaTungstenTicks;
    /** Ticks this task ran at all, and ticks it was within reach. A probe put a zombie in front of
     *  the bot, told it to kill, watched it die in four seconds -- and read kaTungstenTicks=0. So
     *  the kill came from somewhere else. These two separate the candidates: no ticks at all means
     *  the task never ran (something else did the killing), ticks without reach means it never got
     *  close enough, and reach without tungsten ticks means equipWeapon held the branch. */
    public static volatile int kaTaskTicks, kaCanHitTicks, kaEquipTicks;
    private static boolean _aggressiveAttackStrategy = true;

    // No-damage detection: reposition when attacks aren't connecting
    private static final int HITS_BEFORE_REPOSITION = 15;
    private static final int REPOSITION_CYCLES_FOR_IMMUNITY = 5; // 5 × 15 = 75 total hits
    private static final long IMMUNITY_DURATION_MS = 5 * 60 * 1000; // 5 minutes
    private static int _swingCount = 0;
    private static float _targetHealthAtFirstSwing = -1;
    private static int _repositionEntityId = -1; // which entity we're repositioning against
    private static boolean _repositioning = false;
    private static final TimerGame _repositionCooldown = new TimerGame(8); // don't reposition too often
    private static long _lastSwingCountedMs = 0; // min interval between counted swings
    private static final long MIN_SWING_INTERVAL_MS = 500; // 0.5 sec — don't count spam clicks
    // Per-entity reposition cycle counter (entityId → how many times we repositioned without dealing damage)
    private static final Map<Integer, Integer> _repositionCycles = new HashMap<>();

    // ── Combat immunity: 5 reposition cycles without damage → 5 min ignore ──
    private static final Map<Integer, ImmunityRecord> immuneEntities = new HashMap<>();

    private static class ImmunityRecord {
        final long expiresAt;
        float lastKnownHealth;
        ImmunityRecord(float health) {
            this.expiresAt = System.currentTimeMillis() + IMMUNITY_DURATION_MS;
            this.lastKnownHealth = health;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /** Returns true if entity has combat immunity (5 reposition cycles without damage → 5 min ignore). */
    public static boolean hasImmunity(Entity entity) {
        if (entity == null) return false;
        ImmunityRecord rec = immuneEntities.get(entity.getId());
        if (rec == null) return false;
        if (rec.isExpired()) {
            immuneEntities.remove(entity.getId());
            return false;
        }
        return true;
    }

    /** Grant 5-minute combat immunity after confirmed invulnerability. */
    private static void grantImmunity(Entity entity, float health) {
        immuneEntities.put(entity.getId(), new ImmunityRecord(health));
        Debug.logMessage("[Combat] " + entity.getType().getName().getString()
                + " granted 5 min immunity (" + REPOSITION_CYCLES_FOR_IMMUNITY + " reposition cycles, no damage)");
    }

    /** Clear immunity for a specific entity (e.g. it attacked us or its HP dropped). */
    public static void clearImmunity(Entity entity) {
        if (entity == null) return;
        if (immuneEntities.remove(entity.getId()) != null) {
            Debug.logMessage("[Combat] " + entity.getType().getName().getString() + " immunity cleared");
        }
    }

    /**
     * Call once per tick from MobDefenseChain.
     * Clears immunity if: expired, HP dropped, entity got hurt by someone.
     */
    public static void tickImmunityWakeups(List<Entity> nearbyEntities) {
        if (immuneEntities.isEmpty()) return;
        Iterator<Map.Entry<Integer, ImmunityRecord>> it = immuneEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ImmunityRecord> entry = it.next();
            ImmunityRecord rec = entry.getValue();
            if (rec.isExpired()) {
                it.remove();
                continue;
            }
            // Check if the immune entity is still nearby and its HP dropped
            for (Entity e : nearbyEntities) {
                if (e.getId() == entry.getKey() && e instanceof LivingEntity living) {
                    if (living.getHealth() < rec.lastKnownHealth || living.hurtTime > 0) {
                        Debug.logMessage("[Combat] " + e.getType().getName().getString()
                                + " immunity cleared (took damage)");
                        it.remove();
                    }
                    break;
                }
            }
        }
    }

    protected AbstractKillEntityTask() {
        this(CONSIDER_COMBAT_RANGE, OTHER_FORCE_FIELD_RANGE);
    }

    protected AbstractKillEntityTask(double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    protected AbstractKillEntityTask(double maintainDistance, double combatGuardLowerRange, double combatGuardLowerFieldRadius) {
        super(maintainDistance, combatGuardLowerRange, combatGuardLowerFieldRadius);
    }

    // --- Weapon helpers ---

    /**
     * Attack damage of an item. One reading, both versions -- see {@link ItemHelper#meleeDamageOf}.
     *
     * <p>The version split that used to live here returned 0 on 1.21.11 for EVERY item, because
     * its 1.21.11 half was an empty stub. Everything downstream that compared weapons therefore
     * compared zeroes.
     */
    public static float getAttackDamage(Item item) {
        return ItemHelper.meleeDamageOf(item);
    }

    /**
     * The best melee weapon in the pack, or what is already held if nothing beats it.
     *
     * <p>ON 1.21.11 THIS USED TO RETURN WHATEVER WAS IN THE HAND, ALWAYS. The inventory scan was
     * written against {@code instanceof SwordItem} and lived entirely inside the {@code
     * MC < 12111} branch; the 1.21.11 half was a TODO comment, so the loop simply did not exist
     * and the method degenerated to "read the equip slot". A bot with a diamond sword in its pack
     * and dirt in its hand went to fight with the dirt.
     *
     * <p>Now ranked by {@link ItemHelper#meleeDps} with no version split and no class test. Damage
     * per second rather than raw damage is what makes this correct without a sword class: it
     * prefers a sword to an axe the way the numbers do, not by naming the type.
     */
    public static Item bestWeapon(AltoClef mod) {
        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);

        Item bestItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        float bestDps = ItemHelper.meleeDps(bestItem);

        for (ItemStack invStack : invStacks) {
            Item item = invStack.getItem();
            float dps = ItemHelper.meleeDps(item);
            if (dps > bestDps) {
                bestItem = item;
                bestDps = dps;
            }
        }

        return bestItem;
    }

    /**
     * Find the best weapon, optionally preferring an axe (to break shields).
     */
    public static Item bestWeapon(AltoClef mod, boolean preferAxe) {
        if (!preferAxe) return bestWeapon(mod);

        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);
        Item bestItem = null;
        float bestDamage = Float.NEGATIVE_INFINITY;
        boolean hasAxe = false;

        for (ItemStack invStack : invStacks) {
            Item item = invStack.getItem();
            // Anything that hits harder than a fist counts as a candidate. The old test named the
            // two classes it would accept, which on 1.21.11 narrowed to axes only -- so preferAxe
            // could not fall back to a sword when no axe was carried, and the shield-breaking
            // caller silently got nothing. Asking for damage instead of for a type keeps the axe
            // preference below intact while letting swords back in.
            if (ItemHelper.meleeDamageOf(item) <= 0) continue;

            if (item instanceof AxeItem) {
                if (!hasAxe) {
                    bestItem = item;
                    bestDamage = getAttackDamage(item);
                    hasAxe = true;
                }
                // prefer any axe when preferAxe=true; take highest-damage axe
                float dmg = getAttackDamage(item);
                if (dmg > bestDamage) { bestItem = item; bestDamage = dmg; }
            } else if (!hasAxe) {
                float dmg = getAttackDamage(item);
                if (dmg > bestDamage) { bestItem = item; bestDamage = dmg; }
            }
        }

        return bestItem != null ? bestItem : bestWeapon(mod);
    }

    public static boolean equipWeapon(AltoClef mod) {
        Item bestWeapon = bestWeapon(mod);
        Item equippedWeapon = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        if (bestWeapon != null && bestWeapon != equippedWeapon) {
            mod.getSlotHandler().forceEquipItem(bestWeapon);
            return true;
        }
        return false;
    }

    public static boolean equipWeapon(AltoClef mod, boolean preferAxe) {
        Item bestWeapon = bestWeapon(mod, preferAxe);
        Item equippedWeapon = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        if (bestWeapon != null && bestWeapon != equippedWeapon) {
            mod.getSlotHandler().forceEquipItem(bestWeapon);
            return true;
        }
        return false;
    }

    // --- Entity interaction ---

    @Override
    protected Task onEntityInteract(AltoClef mod, Entity entity) {
        if (entity instanceof PlayerEntity playerEntity) {
            // Player PvP: smooth look only, no instant rotation
            return onPlayerInteract(mod, playerEntity);
        }
        // Non-player mobs: existing behavior unchanged (instant lookAt is fine for speedrun)
        if (!equipWeapon(mod)) {
            float hitProg = mod.getPlayer().getAttackCooldownProgress(0);
            if (hitProg >= 1 && (mod.getPlayer().isOnGround()
                    || mod.getPlayer().getVelocity().getY() < 0
                    || mod.getPlayer().isTouchingWater())) {
                LookHelper.lookAt(mod, entity.getEyePos());
                mod.getControllerExtras().attack(entity);

                // No-damage tracking for mobs too
                if (entity instanceof LivingEntity living) {
                    if (_repositionEntityId != entity.getId()) {
                        _repositionEntityId = entity.getId();
                        _swingCount = 0;
                        _targetHealthAtFirstSwing = living.getHealth();
                    }
                    // Only count hits toward immunity at ≥0.5s intervals (don't let CPS spam inflate the counter)
                    long now = System.currentTimeMillis();
                    if (now - _lastSwingCountedMs < MIN_SWING_INTERVAL_MS) {
                        // Too fast — still attack, just don't count for immunity detection
                    } else {
                        _lastSwingCountedMs = now;
                        _swingCount++;
                    }
                    if (living.getHealth() < _targetHealthAtFirstSwing) {
                        _swingCount = 0;
                        _targetHealthAtFirstSwing = living.getHealth();
                        _repositionCycles.remove(entity.getId()); // damage dealt, reset cycles
                    } else if (_swingCount >= HITS_BEFORE_REPOSITION && _repositionCooldown.elapsed()) {
                        _swingCount = 0;
                        _repositionCooldown.reset();
                        int cycles = _repositionCycles.merge(entity.getId(), 1, Integer::sum);
                        if (cycles >= REPOSITION_CYCLES_FOR_IMMUNITY) {
                            // 5 reposition cycles without damage → 5 min immunity
                            _repositionCycles.remove(entity.getId());
                            grantImmunity(entity, living.getHealth());
                        }
                        mod.getEntityTracker().requestEntityUnreachable(entity);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Player-specific PvP interaction.
     * Uses smoothLook (WindMouse) — no instant rotation, no ban risk.
     * Matches autoclef behavior: canHit check, GoJump, shield→axe, hurtTime guard.
     */
    private Task onPlayerInteract(AltoClef mod, PlayerEntity player) {
        // Alternate between aggressive and passive strategy periodically
        if (_attackStrategyTimer.elapsed()) {
            _aggressiveAttackStrategy = !_aggressiveAttackStrategy;
            _attackStrategyTimer.reset();
        }

        kaTaskTicks++;
        boolean canHit = LookHelper.canHitEntity(mod, player);
        if (canHit) {
            kaCanHitTicks++;
        }
        boolean directViewing = LookHelper.cleanLineOfSight(player.getBoundingBox().getCenter(), 50.0);
        double dist = player.distanceTo(mod.getPlayer());
        double yDelta = player.getY() - mod.getPlayer().getY();

        // ── No-damage reposition: if we've swung N times and target HP hasn't changed,
        // wander for a bit to change angle, then re-engage ──
        // Sync baseline HP whenever it drops (DamageTracker-equivalent: real-time health check)
        if (_repositionEntityId == player.getId() && player.getHealth() < _targetHealthAtFirstSwing) {
            _swingCount = 0;
            _inReachSinceMs = 0L;   // it IS taking damage; the angle is fine
            _targetHealthAtFirstSwing = player.getHealth();
            _repositioning = false;
        }

        if (_repositioning && _repositionEntityId == player.getId()) {
            // Cancel when far enough away (repositioned successfully) or timed out
            if (dist > 7.0 || _repositionCooldown.elapsed()) {
                _repositioning = false;
                _swingCount = 0;
            } else {
                // Strafe sideways to change angle — no subtask, no loop
                KillAuraHelper.stopCombatMovement(mod);
                mod.getInputControls().hold(Input.MOVE_LEFT);
                mod.getInputControls().hold(Input.SPRINT);
                setDebugState("Repositioning — " + _swingCount + " hits, no damage");
                return null;
            }
        }

        // Track target entity changes — reset counters on new target
        if (_repositionEntityId != player.getId()) {
            _repositionEntityId = player.getId();
            _swingCount = 0;
            _targetHealthAtFirstSwing = player.getHealth();
            _repositioning = false;
        }

        // AIM BELONGS TO WHOEVER IS FIGHTING.
        // In reach, tungsten's controller aims (WindMouse, at a PREDICTED position); steering the
        // camera from here as well would mean two writers per tick and a crosshair that lands
        // between them. Out of reach there is no controller running, so this is the only aim.
        if (!canHit) {
            LookHelper.smoothLook(mod, player);
        }

        // Detect whether target is blocking with a shield → prefer axe to break it
        boolean preferAxe = false;
        if (player.isUsingItem()) {
            //#if MC >= 12111
            //$$ for (ItemStack stack : java.util.List.of(player.getMainHandStack(), player.getOffHandStack())) {
            //#else
            for (ItemStack stack : player.getHandItems()) {
            //#endif
                if (stack.isOf(Items.SHIELD)) {
                    preferAxe = true;
                    break;
                }
            }
        }

        // Edge-aware combat: don't sprint-jump into the void
        boolean nearEdge = isNearDangerousDrop(mod, player);

        // PRIORITY 1: If we can hit, attack immediately — edge caution is for MOVEMENT only
        if (canHit) {
            // THE FIGHT ITSELF RUNS ON TUNGSTEN.
            // What stood here was a hand-rolled duel: swing on cooldown, sprint-jump when far
            // enough, shuffle when near an edge. Tungsten's CombatController already does that job
            // and more -- aim at a predicted position, hold a striking distance, circle-strafe,
            // time crit jumps, and break contact below half a bar -- and it is what the PvP side
            // has fought with since the duel work took it from 7/12 to 9/12. Mobs stayed on the
            // old code for no better reason than that nothing had rewired them.
            //
            // Approach is untouched: below this branch, altoclef's pathing still walks us in.
            // This is only the part where the target is already within reach.
            //
            // ⛔ AND THAT IS EXACTLY WHY mob_trio CANNOT PASS. MEASURED 2026-08-11.
            // MOB_STRIKE_DISTANCE is 2.9, chosen expressly to sit OUTSIDE a zombie's ~2.0 arm --
            // CombatController's own comment calls it "a band that hits without being hit". A
            // policy whose entire purpose is to keep the bot out of the arm can only run here,
            // once the bot is ALREADY within reach, i.e. already inside the arm. It cannot prevent
            // the first hits, and mob_trio's gate is ZERO damage.
            //
            // The counters agree: on that course ctl reads 0 and 10 over fights of 220-300 ticks,
            // with hurt=0/0/0, while TriggerBot evaluates 161-181 times and passes 11-12. The
            // spacing engine is present for a handful of ticks at the end of an approach somebody
            // else drove.
            //
            // This is also why eight combat hypotheses died on that course judged on min_hp: they
            // were tuning constants that only take effect after the damage they exist to prevent.
            //
            // The fix is not widening this `if`. It is deciding who owns movement OUTSIDE reach,
            // because altoclef's pathing owns it today and adding a second writer is the exact bug
            // shape found twice elsewhere tonight (two writers on the aim; two on the movement
            // keys). Hand-off first, then the controller can hold its distance on the way in.
            if (equipWeapon(mod, preferAxe)) {
                kaEquipTicks++;
            } else {
                _combat.tick(mod.getPlayer(), player, mod.getWorld());
                kaTungstenTicks++;

                // NO-DAMAGE DETECTION HAS TO CHANGE ITS YARDSTICK.
                // It used to count OUR attack calls, which only exist while we do the swinging.
                // With the controller swinging, that counter would sit at zero for ever and the
                // machinery that gives up on an unreachable target would go quietly blind. Time
                // spent in reach measures the same thing without assuming who swings: if we have
                // been in range this long and its health has not moved, the angle is wrong.
                long now = System.currentTimeMillis();
                if (_inReachSinceMs == 0L) {
                    _inReachSinceMs = now;
                }
                if (now - _inReachSinceMs >= NO_DAMAGE_MS && !_repositioning
                        && _repositionCooldown.elapsed()) {
                    _repositioning = true;
                    _repositionCooldown.reset();
                    _inReachSinceMs = 0L;
                    int cycles = _repositionCycles.merge(player.getId(), 1, Integer::sum);
                    if (cycles >= REPOSITION_CYCLES_FOR_IMMUNITY) {
                        _repositionCycles.remove(player.getId());
                        grantImmunity(player, player.getHealth());
                        mod.getEntityTracker().requestEntityUnreachable(player);
                    }
                }
                setDebugState("Fighting (tungsten)");
            }
            return null;
        }

        // PRIORITY 2: Can't hit — movement to close the gap
        // When target is elevated (>1 block above), the bot needs to be directly underneath
        // to have any chance of hitting. Pathfind very close instead of spinning in place.
        boolean targetElevated = yDelta > 1.0;

        if (targetElevated) {
            // Target above us — must get directly below, sprint-jumping won't help
            KillAuraHelper.stopCombatMovement(mod);
            setDebugState("Target above — pathfinding underneath");
            return new GetToEntityTask(player, 0.5);
        } else if (nearEdge) {
            if (dist < 5.0) {
                KillAuraHelper.stopCombatMovement(mod);
                setDebugState("Edge: pathfinding closer (can't hit)");
                return new GetToEntityTask(player, 0.5);
            } else {
                KillAuraHelper.stopCombatMovement(mod);
                setDebugState("Edge: pathfinding to target");
                return new GetToEntityTask(player, 2);
            }
        } else if (dist < 5.0) {
            // Close but can't hit — keep rushing
            KillAuraHelper.GoJump(mod, true, true);
            setDebugState("Closing gap — can't hit yet");
        } else if (!directViewing || !_aggressiveAttackStrategy) {
            // Can't see target — fall back to pathfinding
            KillAuraHelper.stopCombatMovement(mod);
            setDebugState("Cannot hit player, getting closer");
            return new GetToEntityTask(player, 1);
        } else {
            KillAuraHelper.GoJump(mod, false, true);
            setDebugState("Leaping at player!");
        }
        return null;
    }

    // ── Edge / void detection ────────────────────────────────────────────────

    private static final int DANGEROUS_DROP = 5;

    /**
     * Returns true if the bot or the path toward the target has a dangerous drop (>5 blocks).
     * Checks: blocks under the bot (2-block forward strip toward target) and under the target.
     */
    private boolean isNearDangerousDrop(AltoClef mod, Entity target) {
        BlockPos botFeet = mod.getPlayer().getBlockPos();

        // Check under the bot itself
        if (getDropBelow(botFeet) > DANGEROUS_DROP) return true;

        // Check under the target
        BlockPos targetFeet = target.getBlockPos();
        if (getDropBelow(targetFeet) > DANGEROUS_DROP) return true;

        // Check 1-2 blocks ahead toward the target
        Vec3d dir = target.getPos().subtract(mod.getPlayer().getPos()).normalize();
        for (int i = 1; i <= 2; i++) {
            BlockPos ahead = botFeet.add((int) Math.round(dir.x * i), 0, (int) Math.round(dir.z * i));
            if (getDropBelow(ahead) > DANGEROUS_DROP) return true;
        }
        return false;
    }

    /** How many air blocks are below this position before hitting solid ground. */
    private int getDropBelow(BlockPos pos) {
        for (int dy = 1; dy <= DANGEROUS_DROP + 1; dy++) {
            if (WorldHelper.isSolidBlock(pos.down(dy))) return dy - 1;
        }
        return DANGEROUS_DROP + 1;
    }
}
