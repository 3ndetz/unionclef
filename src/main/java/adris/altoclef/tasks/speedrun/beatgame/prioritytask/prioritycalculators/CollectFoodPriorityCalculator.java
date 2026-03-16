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

    public CollectFoodPriorityCalculator(AltoClef mod ,double foodUnits) {
        super(Integer.MAX_VALUE,Integer.MAX_VALUE);
        this.mod = mod;
        this.foodUnits = foodUnits;
    }

    public double calculatePriority(int count) {
        double distance = getDistance(mod);

        double multiplier = 1;
        double foodPotential = CollectFoodTask.calculateFoodPotential(mod);

        //prevents from going to the nether without any food
        if (Double.isInfinite(distance) && foodPotential < foodUnits) return 0.1d;

        Optional<BlockPos> hay = mod.getBlockScanner().getNearestBlock(Blocks.HAY_BLOCK);
        if ((hay.isPresent() && WorldHelper.inRangeXZ(hay.get(),mod.getPlayer().getBlockPos(),75))|| mod.getEntityTracker().itemDropped(Items.HAY_BLOCK)) {
            multiplier = 50;
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
