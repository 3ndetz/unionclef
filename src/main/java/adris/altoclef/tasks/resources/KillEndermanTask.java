package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;
import java.util.function.Predicate;

public class KillEndermanTask extends ResourceTask {

    private final int _count;

    private final TimerGame _lookDelay = new TimerGame(0.2);

    private final boolean _anyDimension;

    public KillEndermanTask(int count) {
        this(count, false);
    }

    /** {@code anyDimension}: hunt where the bot is (the bench arenas are in the Overworld). */
    public KillEndermanTask(int count, boolean anyDimension) {
        super(new ItemTarget(Items.ENDER_PEARL, count));
        _count = count;
        _anyDimension = anyDimension;
        if (!anyDimension) forceDimension(Dimension.NETHER);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Dimension
        if (!mod.getEntityTracker().entityFound(EndermanEntity.class)) {
            if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
                if (_anyDimension) {
                    setDebugState("Waiting for endermen");
                    return null;
                }
                return getToCorrectDimensionTask(mod);
            }
            //nearest warped forest related block
            Optional<BlockPos> nearest = mod.getBlockScanner().getNearestBlock(Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.WARPED_HYPHAE, Blocks.WARPED_NYLIUM);
            if (nearest.isPresent()) {
                if (WorldHelper.inRangeXZ(nearest.get(), mod.getPlayer().getBlockPos(), 40)) {
                    setDebugState("Waiting for endermen to spawn...");
                    return null;
                }

                setDebugState("Getting to warped forest biome");
                return new GetWithinRangeOfBlockTask(nearest.get(), 35);
            }

            setDebugState("Warped forest biome not found");
            return new TimeoutWanderTask();
        }


        Predicate<Entity> belowNetherRoof = (entity) -> WorldHelper.getCurrentDimension() != Dimension.NETHER || entity.getY() < 125;
        final int TOO_FAR_AWAY = WorldHelper.getCurrentDimension() == Dimension.NETHER ? 10 : 256;


        // ⛔ FIGHT FROM UNDER A ROOF WHEN THERE ARE BLOCKS FOR ONE (2026-09-25). Chasing an enderman in
        // the open lost 12 health in 23 s on the n43 run -- pillaring after one on a ledge while it
        // hit -- and endermen are most of the nether deaths. See EndermanShelterTask for the reach
        // geometry. Healing still comes first: the shelter is where the next fight starts.
        boolean shelter = adris.altoclef.tasks.entity.EndermanShelterTask.hasBlocks(mod);
        boolean anyAngry = false;
        for (EndermanEntity entity : mod.getEntityTracker().getTrackedEntities(EndermanEntity.class)) {
            if (belowNetherRoof.test(entity) && entity.isAngry() && entity.getPos().isInRange(mod.getPlayer().getPos(), TOO_FAR_AWAY)) {
                anyAngry = true;
            }
        }
        if (shelter && (anyAngry || adris.altoclef.tasks.entity.EndermanShelterTask.healthyEnoughToProvoke(mod)
                || adris.altoclef.tasks.entity.EndermanShelterTask.onPillar(mod))) {
            return new adris.altoclef.tasks.entity.EndermanShelterTask(belowNetherRoof);
        }

        // Kill the angry one
        for (EndermanEntity entity : mod.getEntityTracker().getTrackedEntities(EndermanEntity.class)) {

            if (belowNetherRoof.test(entity) && entity.isAngry() && entity.getPos().isInRange(mod.getPlayer().getPos(), TOO_FAR_AWAY)) {
                return new KillEntityTask(entity);
            }
        }

        // ⛔ DO NOT PICK A FIGHT THAT TWO HITS END. An enderman hits for 7 on normal (4.5 easy, 10.5
        // hard). A 40-minute nether run on 0.95.41 went 2 -> 10 of 14 pearls, then kept provoking
        // endermen at 4 hp with nothing but rotten flesh to eat -- 45 s without regeneration -- and
        // died. An angry one is fought above regardless (it teleports to you; running does not
        // help); a NEW one is only provoked when the body can take two of its hits. Otherwise heal
        // first: stand if hunger allows regeneration, let FoodChain eat if there is food, and fetch
        // a little food if there is none.
        float hp = mod.getPlayer().getHealth();
        if (hp <= 2 * endermanHit(mod) + 1) {
            int hunger = mod.getPlayer().getHungerManager().getFoodLevel();
            if (hunger >= 18 || mod.getFoodChain().hasFood()) {
                setDebugState("Healing before provoking the next enderman (hp " + (int) hp + ")");
                return null;
            }
            setDebugState("No food and too hurt to provoke an enderman: getting food first");
            return new adris.altoclef.tasks.resources.CollectFoodTask(40);
        }

        // Attack the closest one
        return new KillEntitiesTask(belowNetherRoof, EndermanEntity.class);
    }

    /** An enderman's melee damage at the world's difficulty (vanilla: 7 on normal, x1.5 hard, easy 4.5). */
    private static float endermanHit(AltoClef mod) {
        return switch (mod.getWorld().getDifficulty()) {
            case PEACEFUL -> 0f;
            case EASY -> 4.5f;
            case NORMAL -> 7f;
            case HARD -> 10.5f;
        };
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof KillEndermanTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Hunting endermen for pearls - " + AltoClef.getInstance().getItemStorage().getItemCount(Items.ENDER_PEARL) + "/" + _count;
    }
}