package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.List;

public class LootDesertTempleTask extends Task {
    public final Vec3i[] CHEST_POSITIONS_RELATIVE = {
            new Vec3i(2, 0, 0),
            new Vec3i(-2, 0, 0),
            new Vec3i(0, 0, 2),
            new Vec3i(0, 0, -2)
    };
    private final BlockPos temple;
    private final List<Item> wanted;
    private Task lootTask;
    private short looted = 0;

    public LootDesertTempleTask(BlockPos temple, List<Item> wanted) {
        this.temple = temple;
        this.wanted = wanted;
    }

    @Override
    protected void onStart() {
        AltoClef.getInstance().avoidWalkingThrough(Blocks.STONE_PRESSURE_PLATE);
    }

    @Override
    protected Task onTick() {
        if (lootTask != null) {
            if (!lootTask.isFinished()) {
                setDebugState("Looting a desert temple chest");
                return lootTask;
            }
            looted++;
        }
        if (AltoClef.getInstance().getWorld().getBlockState(temple).getBlock() == Blocks.STONE_PRESSURE_PLATE) {
            setDebugState("Breaking pressure plate");
            return new DestroyBlockTask(temple);
        }
        if (looted < 4) {
            setDebugState("Looting a desert temple chest");
            lootTask = new LootContainerTask(temple.add(CHEST_POSITIONS_RELATIVE[looted]), wanted);
            return lootTask;
        }
        setDebugState("Why is this still running? Report this");
        return null;
    }

    @Override
    protected void onStop(Task task) {
        AltoClef.getInstance().allowWalkingThrough(Blocks.STONE_PRESSURE_PLATE);
    }

    @Override
    protected boolean isEqual(Task other) {
        // ⛔ FIXED 2026-09-05: was `== temple` (reference equality) on a BlockPos, a value type with
        // its own equals(). Currently masked because every construction site
        // (RavageDesertTemplesTask, BeatMinecraft2Task, MarvionBeatMinecraftTask) caches the
        // constructed instance in a field and returns that SAME reference on every later tick
        // rather than building a fresh one, so identity happened to coincide with equality. Still a
        // latent bug: two different BlockPos objects for the same coordinates would wrongly compare
        // unequal, which is exactly the contract equals() exists to get right.
        return other instanceof LootDesertTempleTask && ((LootDesertTempleTask) other).getTemplePos().equals(temple);
    }

    @Override
    public boolean isFinished() {
        return looted == 4;
    }

    @Override
    protected String toDebugString() {
        return "Looting Desert Temple";
    }

    public BlockPos getTemplePos() {
        return temple;
    }
}