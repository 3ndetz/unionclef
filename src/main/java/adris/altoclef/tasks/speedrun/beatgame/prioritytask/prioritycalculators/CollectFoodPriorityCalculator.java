package adris.altoclef.tasks.speedrun.beatgame.prioritytask.prioritycalculators;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;
import java.util.function.Predicate;

import static adris.altoclef.tasks.resources.CollectFoodTask.*;

/**
 * partial copy of CollectFoodTask.java, might be a good idea to somehow use the methods there instead of copying them
 * this class is needed because if we calculate the priority to something and then the tasks goes somewhere else it can cause it to get stuck
 */

public class CollectFoodPriorityCalculator extends ItemPriorityCalculator {

    private final AltoClef mod;
    private final double foodUnits;
    private final double minFoodUnits;

    public CollectFoodPriorityCalculator(AltoClef mod, double foodUnits, double minFoodUnits) {
        super(Integer.MAX_VALUE,Integer.MAX_VALUE);
        this.mod = mod;
        this.foodUnits = foodUnits;
        this.minFoodUnits = minFoodUnits;
    }

    /** Hunger remains urgent even when discovery has no known food target yet. */
    public static boolean needsEmergencyFood(AltoClef mod) {
        return mod.getPlayer() != null
                && (mod.getPlayer().getHungerManager().getFoodLevel() <= 10
                    || mod.getPlayer().getHealth() <= 10.0f)
                && CollectFoodTask.calculateFoodPotential(mod) < 10;
    }

    public double calculatePriority(int count) {
        // Do not let the unknown-distance fallback demote a starving, empty-handed
        // food search below optional ore or chest collection. Movement and combat
        // emergencies still belong to the higher-priority survival/defence chains.
        if (needsEmergencyFood(mod)) return Double.POSITIVE_INFINITY;
        double distance = getDistance(mod);

        double multiplier = 1;
        double foodPotential = CollectFoodTask.calculateFoodPotential(mod);

        // ⛔ AN UNREACHABLE TOP-UP MUST NOT DEADLOCK THE NETHER (G106 completion, 2026-09-19).
        // When no food source is reachable (distance infinite) and the reserve is short of the
        // top-up target, this used to return 0.1 UNCONDITIONALLY -- a weak-but-positive priority
        // that keeps CollectFood selected over the "Going to Nether" fall-through (gated on every
        // gather <= 0, BeatMinecraftTask), so on food-depleted terrain the bot wanders for food
        // that is not there FOR EVER and never builds the portal (measured: resume of the
        // nether-reach checkpoint stalled on "Collect 140 food / Wander for Infinity", 0 portal).
        // The G106 intent was already "proceed with a solid buffer it tops up, not hoard": minFood-
        // Units (120) is that buffer. So keep blocking the nether (0.1, and keep exploring) only
        // while the reserve is BELOW the survival floor; once it is adequate but the top-up is
        // unreachable, stand down (NEGATIVE_INFINITY) so progression fires. A reachable food source
        // (finite distance) still tops up to foodUnits normally, and needsEmergencyFood (+inf above)
        // and the low-reserve ramp still guard survival.
        if (Double.isInfinite(distance) && foodPotential < foodUnits) {
            return foodPotential < minFoodUnits ? 0.1d : Double.NEGATIVE_INFINITY;
        }

        // A hay bale is efficient food (9 wheat -> 9 bread, ~54 hunger), so grabbing one is worth
        // a boost. But x50 applied to the WHOLE food priority -- and for a hay merely within 75
        // blocks, not the food actually being collected -- drove the goal to ~1293 (measured on the
        // 2026-09-18 day-locked run near a village), which buries diamond/nether progression under
        // food for the whole stage (G106). A hay bale is worth ~7 animal kills; boost by that, not
        // by thirty. Emergencies and a genuinely low reserve still ramp hard via needsEmergencyFood
        // (+inf) and the foodPotential<10 branch below.
        Optional<BlockPos> hay = mod.getBlockScanner().getNearestBlock(Blocks.HAY_BLOCK);
        if ((hay.isPresent() && WorldHelper.inRangeXZ(hay.get(),mod.getPlayer().getBlockPos(),75))|| mod.getEntityTracker().itemDropped(Items.HAY_BLOCK)) {
            multiplier = 7;
        }

        if (foodPotential > foodUnits) {
            if (foodPotential > foodUnits+20) return Double.NEGATIVE_INFINITY;

            if (distance > 10 && hay.isEmpty()) return Double.NEGATIVE_INFINITY;

            return 17 / distance * (30 / (count / 2d))*multiplier;
        }

        if (foodPotential < 10) {
            multiplier = Math.max(11d / foodPotential,22);
        }
        return 33 / distance * 37 * multiplier;
    }

    private double getDistance(AltoClef mod) {
        PlayerEntity player = mod.getPlayer();

        // Pick up food items from ground
        for (Item item : ITEMS_TO_PICK_UP) {
            double dist  = this.pickupTaskOrNull(mod, item);
            if (dist != Double.NEGATIVE_INFINITY) {
                return dist;
            }
        }
        // Pick up raw/cooked foods on ground
        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            double dist = this.pickupTaskOrNull(mod, cookable.getRaw(), 20);
            if (dist == Double.NEGATIVE_INFINITY) dist = this.pickupTaskOrNull(mod, cookable.getCooked(), 40);

            if (dist != Double.NEGATIVE_INFINITY) {
                return dist;
            }
        }

        // Hay blocks
        double hayTaskBlock = this.pickupBlockTaskOrNull(mod, Blocks.HAY_BLOCK, Items.HAY_BLOCK, 300);
        if (hayTaskBlock != Double.NEGATIVE_INFINITY) {
            return hayTaskBlock;
        }
        // Crops
        for (CropTarget target : CROPS) {
            // If crops are nearby. Do not replant cause we don't care.
            double t = pickupBlockTaskOrNull(mod, target.cropBlock, target.cropItem, (blockPos -> {
                BlockState s = mod.getWorld().getBlockState(blockPos);
                Block b = s.getBlock();
                if (b instanceof CropBlock) {
                    boolean isWheat = !(b instanceof PotatoesBlock || b instanceof CarrotsBlock || b instanceof BeetrootsBlock);
                    if (isWheat) {
                        // Chunk needs to be loaded for wheat maturity to be checked.
                        if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                            return false;
                        }
                        // Prune if we're not mature/fully grown wheat.
                        CropBlock crop = (CropBlock) b;
                        return crop.isMature(s);
                    }
                }
                // Unbreakable.
                return WorldHelper.canBreak(blockPos);
                // We're not wheat so do NOT reject.
            }), 96);
            if (t != Double.NEGATIVE_INFINITY) {
                return t;
            }
        }
        // Cooked foods
        double bestScore = 0;
        Entity bestEntity = null;
        Predicate<Entity> notBaby = entity -> entity instanceof LivingEntity livingEntity && !livingEntity.isBaby();

        for (CookableFoodTarget cookable : COOKABLE_FOODS) {
            if (!mod.getEntityTracker().entityFound(cookable.mobToKill)) continue;
            Optional<Entity> nearest = mod.getEntityTracker().getClosestEntity(mod.getPlayer().getPos(), notBaby, cookable.mobToKill);
            if (nearest.isEmpty()) continue; // ?? This crashed once?
            int hungerPerformance = cookable.getCookedUnits();
            double sqDistance = nearest.get().squaredDistanceTo(mod.getPlayer());
            double score = (double) 100 * hungerPerformance / (sqDistance);
            if (cookable.isFish()) {
                score = 0;
            }
            if (score > bestScore) {
                bestScore = score;
                bestEntity = nearest.get();
            }
        }
        if (bestEntity != null) {
            return bestEntity.distanceTo(player);
        }

        // Sweet berries (separate from crops because they should have a lower priority than everything else cause they suck)
        double berryPickup = pickupBlockTaskOrNull(mod, Blocks.SWEET_BERRY_BUSH, Items.SWEET_BERRIES, 96);
        if (berryPickup != Double.NEGATIVE_INFINITY) {
            return berryPickup;
        }

        return Double.POSITIVE_INFINITY;
    }

    private double pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, double maxRange) {
        return pickupBlockTaskOrNull(mod, blockToCheck, itemToGrab, toAccept -> true, maxRange);
    }

    private double pickupBlockTaskOrNull(AltoClef mod, Block blockToCheck, Item itemToGrab, Predicate<BlockPos> accept, double maxRange) {
        Predicate<BlockPos> acceptPlus = (blockPos) -> {
            if (!WorldHelper.canBreak(blockPos)) return false;
            return accept.test(blockPos);
        };
        Optional<BlockPos> nearestBlock = mod.getBlockScanner().getNearestBlock(mod.getPlayer().getPos(), acceptPlus, blockToCheck);

        if (nearestBlock.isPresent() && !nearestBlock.get().isWithinDistance(mod.getPlayer().getPos(), maxRange)) {
            nearestBlock = Optional.empty();
        }

        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), itemToGrab);
        }


        if (nearestDrop.isPresent()) {
            return nearestDrop.get().distanceTo(mod.getPlayer());
        }
        if (nearestBlock.isPresent()) {
            return Math.sqrt(mod.getPlayer().squaredDistanceTo(WorldHelper.toVec3d(nearestBlock.get())));
        }

        return Double.NEGATIVE_INFINITY;
    }

    private double pickupTaskOrNull(AltoClef mod, Item itemToGrab) {
        return pickupTaskOrNull(mod, itemToGrab, Double.POSITIVE_INFINITY);
    }

    private double pickupTaskOrNull(AltoClef mod, Item itemToGrab, double maxRange) {
        Optional<ItemEntity> nearestDrop = Optional.empty();
        if (mod.getEntityTracker().itemDropped(itemToGrab)) {
            nearestDrop = mod.getEntityTracker().getClosestItemDrop(mod.getPlayer().getPos(), itemToGrab);
        }
        if (nearestDrop.isPresent()) {
            if (nearestDrop.get().isInRange(mod.getPlayer(), maxRange)) {
                if (mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(nearestDrop.get().getStack(), false).isEmpty()) {
                    Optional<Slot> slot = StorageHelper.getGarbageSlot(mod);

                    // tf am I supposed to do if its empty
                    if (slot.isPresent()) {
                        ItemStack stack = StorageHelper.getItemStackInSlot(slot.get());
                        if (ItemVer.isFood(stack.getItem())) {
                            // calculate priority, if the item laying on the ground has lower priority than the one we are gonna throw out because of it
                            // dont pick it up, otherwise we would get stuck in an infinite loop
                            int inventoryCost = ItemVer.getFoodComponent(stack.getItem()).getHunger() * stack.getCount();

                            double hunger = 0;
                            if (ItemVer.isFood(itemToGrab)) {
                                hunger = ItemVer.getFoodComponent(itemToGrab).getHunger();
                            } else if (itemToGrab.equals(Items.WHEAT)) {
                                hunger += ItemVer.getFoodComponent(Items.BREAD).getHunger() / 3d;
                            } else {
                                mod.log("unknown food item: " + itemToGrab);
                            }
                            int groundCost = (int) (hunger * nearestDrop.get().getStack().getCount());

                            if (inventoryCost > groundCost) return Double.NEGATIVE_INFINITY;
                        }
                    }
                }

                return nearestDrop.get().distanceTo(mod.getPlayer());
            }
        }
        return Double.NEGATIVE_INFINITY;
    }


}
