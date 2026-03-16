package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.SearchWithinBiomeTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;

import java.util.List;

public class RavageDesertTemplesTask extends Task {
    public final Item[] LOOT = {
            Items.BONE,
            Items.ROTTEN_FLESH,
            Items.GUNPOWDER,
            Items.SAND,
            Items.STRING,
            Items.SPIDER_EYE,
            Items.ENCHANTED_BOOK,
            Items.SADDLE,
            Items.GOLDEN_APPLE,
            Items.GOLD_INGOT,
            Items.IRON_INGOT,
            Items.EMERALD,
            Items.IRON_HORSE_ARMOR,
            Items.GOLDEN_HORSE_ARMOR,
            Items.DIAMOND,
            Items.DIAMOND_HORSE_ARMOR,
            Items.ENCHANTED_GOLDEN_APPLE
    };
    private BlockPos currentTemple;
    private Task lootTask;
    private Task pickaxeTask;

    public RavageDesertTemplesTask() {

    }

    @Override
    protected void onStart() {
        AltoClef.getInstance().getBehaviour().push();
    }

    @Override
    protected Task onTick() {
        if (pickaxeTask != null && !pickaxeTask.isFinished()) {
            setDebugState("Need to get pickaxes first");
            return pickaxeTask;
        }
        if (lootTask != null && !lootTask.isFinished()) {
            setDebugState("Looting found temple");
            return lootTask;
        }
        if (StorageHelper.miningRequirementMetInventory(MiningRequirement.WOOD)) {
            setDebugState("Need to get pickaxes first");
            pickaxeTask = new CataloguedResourceTask(new ItemTarget(Items.WOODEN_PICKAXE, 2));
            return pickaxeTask;
        }
        currentTemple = WorldHelper.getADesertTemple();
        if (currentTemple != null) {
            lootTask = new LootDesertTempleTask(currentTemple, List.of(LOOT));
            setDebugState("Looting found temple");
            return lootTask;
        }
        return new SearchWithinBiomeTask(BiomeKeys.DESERT);
    }

    @Override
    protected void onStop(Task task) {
        AltoClef.getInstance().getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof RavageDesertTemplesTask;
    }

    @Override
    public boolean isFinished() {
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Ravaging Desert Temples";
    }
}
