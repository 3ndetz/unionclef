package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.KillAuraHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
//#if MC < 12111
import net.minecraft.item.SwordItem;
//#endif
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

    public static float getAttackDamage(Item item) {
        //#if MC < 12111
        if (item instanceof SwordItem sword) return sword.getMaterial().getAttackDamage();
        if (item instanceof AxeItem axe) return axe.getMaterial().getAttackDamage();
        //#else
        //$$ // TODO [1.21.11] sword-class/AxeItem.getMaterial() removed — get attack damage from components
        //#endif
        return 0;
    }

    public static Item bestWeapon(AltoClef mod) {
        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);

        Item bestItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        float bestDamage = Float.NEGATIVE_INFINITY;

        //#if MC < 12111
        if (bestItem instanceof SwordItem handToolItem) {
            bestDamage = handToolItem.getMaterial().getAttackDamage();
        }

        for (ItemStack invStack : invStacks) {
            if (!(invStack.getItem() instanceof SwordItem item)) continue;
            float itemDamage = item.getMaterial().getAttackDamage();
            if (itemDamage > bestDamage) {
                bestItem = item;
                bestDamage = itemDamage;
            }
        }
        //#else
        //$$ // TODO [1.21.11] sword-class deleted — use Item.Settings attack damage component
        //#endif

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
            //#if MC < 12111
            if (!(item instanceof SwordItem) && !(item instanceof AxeItem)) continue;
            //#else
            //$$ // TODO [1.21.11] sword-class deleted — check for sword items via other means
            //$$ if (!(item instanceof AxeItem)) continue;
            //#endif

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

        boolean canHit = LookHelper.canHitEntity(mod, player);
        boolean directViewing = LookHelper.cleanLineOfSight(player.getBoundingBox().getCenter(), 50.0);
        double dist = player.distanceTo(mod.getPlayer());
        double yDelta = player.getY() - mod.getPlayer().getY();

        // ── No-damage reposition: if we've swung N times and target HP hasn't changed,
        // wander for a bit to change angle, then re-engage ──
        // Sync baseline HP whenever it drops (DamageTracker-equivalent: real-time health check)
        if (_repositionEntityId == player.getId() && player.getHealth() < _targetHealthAtFirstSwing) {
            _swingCount = 0;
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

        // Always smooth-aim at player during PvP
        LookHelper.smoothLook(mod, player);

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
            if (!equipWeapon(mod, preferAxe)) {
                float hitProg = mod.getPlayer().getAttackCooldownProgress(0);
                if (hitProg >= 0.99 && player.hurtTime <= 0) {
                    boolean didHit = mod.getControllerExtras().attack(player);
                    // Count only confirmed hits (crosshair actually connected)
                    if (didHit) {
                        float currentHP = player.getHealth();
                        if (currentHP < _targetHealthAtFirstSwing) {
                            _swingCount = 0;
                            _targetHealthAtFirstSwing = currentHP;
                            _repositionCycles.remove(player.getId());
                        } else {
                            // Only count at ≥0.5s intervals
                            long now = System.currentTimeMillis();
                            if (now - _lastSwingCountedMs >= MIN_SWING_INTERVAL_MS) {
                                _lastSwingCountedMs = now;
                                _swingCount++;
                            }
                            if (_swingCount >= HITS_BEFORE_REPOSITION && !_repositioning
                                    && _repositionCooldown.elapsed()) {
                                _repositioning = true;
                                _repositionCooldown.reset();
                                _swingCount = 0;
                                int cycles = _repositionCycles.merge(player.getId(), 1, Integer::sum);
                                if (cycles >= REPOSITION_CYCLES_FOR_IMMUNITY) {
                                    _repositionCycles.remove(player.getId());
                                    grantImmunity(player, currentHP);
                                    mod.getEntityTracker().requestEntityUnreachable(player);
                                }
                            }
                        }
                    }
                    setDebugState(didHit ? "ATTACKING PLAYER (" + _swingCount + " no-dmg hits)" : "Swinging — crosshair missed");
                } else {
                    setDebugState("Waiting for cooldown (PvP)");
                }
            }
            // Movement while attacking: strafe near edge, sprint-jump if safe
            if (nearEdge) {
                // Light strafe only, no forward rush, no jumping off edge
                if (dist > 1.5) {
                    // Close enough to swing but drifting — shuffle forward carefully
                    mod.getInputControls().hold(Input.SNEAK);
                    mod.getInputControls().hold(Input.MOVE_FORWARD);
                }
            } else if (dist > 0.5) {
                KillAuraHelper.GoJump(mod, dist < 4.4, true);
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
