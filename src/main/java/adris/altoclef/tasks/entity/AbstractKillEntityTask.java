package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.KillAuraHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import adris.altoclef.tasks.movement.GetToEntityTask;

import java.util.List;

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
        if (item instanceof SwordItem sword) return sword.getMaterial().getAttackDamage();
        if (item instanceof AxeItem axe) return axe.getMaterial().getAttackDamage();
        return 0;
    }

    public static Item bestWeapon(AltoClef mod) {
        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);

        Item bestItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        float bestDamage = Float.NEGATIVE_INFINITY;

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
            if (!(item instanceof SwordItem) && !(item instanceof AxeItem)) continue;

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

        // Always smooth-aim at player during PvP
        LookHelper.smoothLook(mod, player);

        // Detect whether target is blocking with a shield → prefer axe to break it
        boolean preferAxe = false;
        if (player.isUsingItem()) {
            for (ItemStack stack : player.getHandItems()) {
                if (stack.isOf(Items.SHIELD)) {
                    preferAxe = true;
                    break;
                }
            }
        }

        // Always sprint-jump toward target during PvP (tick-based, no delays)
        if (dist > 0.5) {
            KillAuraHelper.GoJump(mod, dist < 4.4, true);
        }

        if (canHit) {
            // Attack when cooldown ready and target not already flashing (hurtTime)
            if (!equipWeapon(mod, preferAxe)) {
                float hitProg = mod.getPlayer().getAttackCooldownProgress(0);
                if (hitProg >= 0.99 && player.hurtTime <= 0) {
                    mod.getControllerExtras().attack(player);
                    setDebugState("ATTACKING PLAYER");
                } else {
                    setDebugState("Waiting for cooldown (PvP)");
                }
            }
        } else if (!directViewing || !_aggressiveAttackStrategy) {
            // Can't see target — fall back to pathfinding
            KillAuraHelper.stopCombatMovement(mod);
            setDebugState("Cannot hit player, getting closer");
            return new GetToEntityTask(player);
        } else {
            setDebugState("Leaping at player!");
        }
        return null;
    }
}
