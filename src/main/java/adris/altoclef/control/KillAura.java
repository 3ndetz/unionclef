package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.versionedfields.Entities;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StlHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import kaptainwutax.tungsten.path.movements.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.entity.mob.ZoglinEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controls and applies killaura
 */
public class KillAura {
    // Smart aura data
    private final List<Entity> targets = new ArrayList<>();
    boolean shielding = false;
    private double forceFieldRange = Double.POSITIVE_INFINITY;
    private Entity forceHit = null;
    public boolean attackedLastTick = false;

    /**
     * Put the best melee weapon in the pack into the hand.
     *
     * <p>THIS METHOD DID NOTHING AT ALL ON 1.21.11. Its entire body was inside the {@code
     * MC < 12111} half of a preprocessor split, because it was written around {@code instanceof
     * SwordItem} and that class is gone; the 1.21.11 half was a TODO comment. So the force field
     * -- which MobDefenseChain's own measurements say is what actually kills things -- swung with
     * whatever happened to already be in the hand, sword in the pack or not.
     *
     * <p>Rewritten without any version split: rank every stack in the pack by
     * {@link ItemHelper#meleeDps}, which asks the ITEM for its damage and swing speed instead of
     * asking its class what it is. Nothing is equipped unless it beats what is already held, so a
     * bot holding the best weapon it owns is left alone rather than re-equipping every tick.
     */
    public static void equipWeapon(AltoClef mod) {
        List<ItemStack> invStacks = mod.getItemStorage().getItemStacksPlayerInventory(true);
        if (invStacks.isEmpty()) {
            return;
        }
        Item handItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        Item best = handItem;
        float bestDps = ItemHelper.meleeDps(handItem);
        for (ItemStack invStack : invStacks) {
            Item item = invStack.getItem();
            float dps = ItemHelper.meleeDps(item);
            if (dps > bestDps) {
                best = item;
                bestDps = dps;
            }
        }
        // An empty hand and a pack of blocks is not a reason to equip a block: meleeDps returns the
        // bare-fist number for anything without weapon modifiers, so nothing wins by accident.
        if (best != null && best != handItem) {
            mod.getSlotHandler().forceEquipItem(best);
        }
    }

    public void tickStart() {
        targets.clear();
        forceHit = null;
        attackedLastTick = false;
    }

    public void applyAura(Entity entity) {
        targets.add(entity);
        // Always hit ghast balls.
        if (entity instanceof FireballEntity) forceHit = entity;
    }

    public void setRange(double range) {
        forceFieldRange = range;
    }

    public void tickEnd(AltoClef mod) {
        Optional<Entity> entities = targets.stream().min(StlHelper.compareValues(entity -> entity.squaredDistanceTo(mod.getPlayer())));
        if (entities.isPresent() &&
                !mod.getEntityTracker().entityFound(PotionEntity.class) &&
                (Double.isInfinite(forceFieldRange) || entities.get().squaredDistanceTo(mod.getPlayer()) < forceFieldRange * forceFieldRange ||
                        entities.get().squaredDistanceTo(mod.getPlayer()) < 40) &&
                !mod.getMLGBucketChain().isFalling(mod) && mod.getMLGBucketChain().doneMLG() &&
                !mod.getMLGBucketChain().isChorusFruiting()) {
            PlayerSlot offhandSlot = PlayerSlot.OFFHAND_SLOT;
            Item offhandItem = StorageHelper.getItemStackInSlot(offhandSlot).getItem();
            if (entities.get().getClass() != CreeperEntity.class && entities.get().getClass() != HoglinEntity.class &&
                    entities.get().getClass() != ZoglinEntity.class && entities.get().getClass() != Entities.WARDEN &&
                    entities.get().getClass() != WitherEntity.class
                    && (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD))
                    //#if MC >= 12111
                    //$$ && !mod.getPlayer().getItemCooldownManager().isCoolingDown(new ItemStack(offhandItem))
                    //#else
                    && !mod.getPlayer().getItemCooldownManager().isCoolingDown(offhandItem)
                    //#endif
                    && (mod.getClientBaritone() == null || Nav.isSafeToCancel())) {
                LookHelper.smoothLookAt(mod, entities.get().getEyePos());
                ItemStack shieldSlot = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
                if (shieldSlot.getItem() != Items.SHIELD) {
                    mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
                } else if (!WorldHelper.isSurroundedByHostiles()) {
                    startShielding(mod);
                }
            }
            performDelayedAttack(mod);
        } else {
            stopShielding(mod);
        }
        // Run force field on map
        switch (mod.getModSettings().getForceFieldStrategy()) {
            case FASTEST:
                performFastestAttack(mod);
                break;
            case SMART:
                // Attack force mobs ALWAYS. (currently used only for fireballs)
                if (forceHit != null) {
                    attack(mod, forceHit, true);
                    break;
                }

                if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFalling(mod) &&
                        mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
                    performDelayedAttack(mod);
                }
                break;
            case DELAY:
                performDelayedAttack(mod);
                break;
            case OFF:
                break;
        }
    }

    private void performDelayedAttack(AltoClef mod) {
        if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFalling(mod) &&
                mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
            if (forceHit != null) {
                attack(mod, forceHit, true);
            }
            // wait for the attack delay
            if (targets.isEmpty()) {
                return;
            }

            Optional<Entity> toHit = targets.stream().min(StlHelper.compareValues(entity -> entity.squaredDistanceTo(mod.getPlayer())));

            if (mod.getPlayer() == null || mod.getPlayer().getAttackCooldownProgress(0) < 1) {
                return;
            }

            toHit.ifPresent(entity -> attack(mod, entity, true));
        }
    }

    private void performFastestAttack(AltoClef mod) {
        if (!mod.getFoodChain().needsToEat() && !mod.getMLGBucketChain().isFalling(mod) &&
                mod.getMLGBucketChain().doneMLG() && !mod.getMLGBucketChain().isChorusFruiting()) {
            // Just attack whenever you can
            for (Entity entity : targets) {
                attack(mod, entity);
            }
        }
    }

    private void attack(AltoClef mod, Entity entity) {
        attack(mod, entity, false);
    }

    private void attack(AltoClef mod, Entity entity, boolean equipSword) {
        if (entity == null) return;
        if (!(entity instanceof FireballEntity)) {
            double xAim = entity.getX();
            double yAim = entity.getY() + (entity.getHeight() / 1.4);
            double zAim = entity.getZ();
            LookHelper.smoothLookAt(mod, new Vec3d(xAim, yAim, zAim));
        }
        if (Double.isInfinite(forceFieldRange) || entity.squaredDistanceTo(mod.getPlayer()) < forceFieldRange * forceFieldRange ||
                entity.squaredDistanceTo(mod.getPlayer()) < 40) {
            if (entity instanceof FireballEntity) {
                mod.getControllerExtras().attack(entity);
            }
            boolean canAttack;
            if (equipSword) {
                equipWeapon(mod);
                canAttack = true;
            } else {
                // Equip non-tool
                canAttack = mod.getSlotHandler().forceDeequipHitTool();
            }
            if (canAttack) {
                // isOnGround() intentionally removed — matches autoclef (allows sprint-crit attacks while jumping)
                if (mod.getPlayer().getVelocity().getY() < 0 || mod.getPlayer().isTouchingWater()) {
                    attackedLastTick = true;
                    mod.getControllerExtras().attack(entity);
                }
            }
        }
    }

    public void startShielding(AltoClef mod) {
        shielding = true;
        if (mod.getClientBaritone() != null)
            Nav.pause();
        mod.getExtraBaritoneSettings().setInteractionPaused(true);
        if (!mod.getPlayer().isBlocking()) {
            ItemStack handItem = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot());
            if (ItemVer.isFood(handItem)) {
                List<ItemStack> spaceSlots = mod.getItemStorage().getItemStacksPlayerInventory(false);
                if (!spaceSlots.isEmpty()) {
                    for (ItemStack spaceSlot : spaceSlots) {
                        if (spaceSlot.isEmpty()) {
                            mod.getSlotHandler().clickSlot(PlayerSlot.getEquipSlot(), 0, SlotActionType.QUICK_MOVE);
                            return;
                        }
                    }
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                garbage.ifPresent(slot -> mod.getSlotHandler().forceEquipItem(StorageHelper.getItemStackInSlot(slot).getItem()));
            }
        }
        mod.getInputControls().hold(Input.SNEAK);
        mod.getInputControls().hold(Input.CLICK_RIGHT);
    }

    public void stopShielding(AltoClef mod) {
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
            mod.getInputControls().release(Input.JUMP);
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            shielding = false;
        }
    }

    public boolean isShielding() {
        return shielding;
    }

    public enum Strategy {
        OFF,
        FASTEST,
        DELAY,
        SMART
    }
}