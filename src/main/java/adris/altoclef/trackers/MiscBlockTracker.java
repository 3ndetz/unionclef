package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sometimes we want to track specific block related things, like the last nether portal we used.
 * I don't want to pollute other trackers to add these.
 */
public class MiscBlockTracker {

    private final AltoClef mod;

    private final Map<Dimension, BlockPos> lastNetherPortalsUsed = new HashMap<>();

    // Make sure we only care about the nether portal we ENTERED through
    private Dimension lastDimension;
    private boolean newDimensionTriggered;

    public MiscBlockTracker(AltoClef mod) {
        this.mod = mod;
    }

    public void tick() {
        if (WorldHelper.getCurrentDimension() != lastDimension) {
            lastDimension = WorldHelper.getCurrentDimension();
            newDimensionTriggered = true;
        }

        if (AltoClef.inGame() && newDimensionTriggered) {
            for (BlockPos check : WorldHelper.scanRegion(mod.getPlayer().getBlockPos().add(-1,-1,-1), mod.getPlayer().getBlockPos().add(1,1,1))) {
                Block currentBlock = mod.getWorld().getBlockState(check).getBlock();
                if (currentBlock == Blocks.NETHER_PORTAL) {
                    // Make sure we get the lowest nether portal, as we can only really enter from the bottom.
                    while (check.getY() > 0) {
                        if (mod.getWorld().getBlockState(check.down()).getBlock() == Blocks.NETHER_PORTAL) {
                            check = check.down();
                        } else {
                            break;
                        }
                    }
                    BlockPos below = check.down();
                    if (WorldHelper.isSolidBlock(below)) {
                        lastNetherPortalsUsed.put(WorldHelper.getCurrentDimension(), check);
                        newDimensionTriggered = false;
                    }
                    break;
                }
            }
        }
    }

    public void reset() {
        lastNetherPortalsUsed.clear();
    }

    public Optional<BlockPos> getLastUsedNetherPortal(Dimension dimension) {
        if (lastNetherPortalsUsed.containsKey(dimension)) {
            BlockPos portalPos = lastNetherPortalsUsed.get(dimension);
            // Check whether our nether portal pos is invalid.
            if (mod.getChunkTracker().isChunkLoaded(portalPos)) {
                if (!mod.getBlockScanner().isBlockAtPosition(portalPos, Blocks.NETHER_PORTAL)) {
                    lastNetherPortalsUsed.remove(dimension);
                    return Optional.empty();
                }
            }
            return Optional.ofNullable(portalPos);
        }
        return Optional.empty();
    }
}
