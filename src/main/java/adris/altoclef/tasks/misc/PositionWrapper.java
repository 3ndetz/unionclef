package adris.altoclef.tasks.misc;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.function.BiPredicate;

/**
 * A wrapper class that can hold different types of positions
 * Supports custom validation predicates for each position type
 */
public class PositionWrapper {
    private final Vec3d position;
    private final Entity entity;
    private final BlockPos blockPos;
    private final boolean hasPosition;
    private final BiPredicate<AltoClef, Vec3d> positionValidator;
    private final BiPredicate<AltoClef, Entity> entityValidator;
    private final BiPredicate<AltoClef, BlockPos> blockPosValidator;

    private PositionWrapper(Vec3d position, Entity entity, BlockPos blockPos, boolean hasPosition,
                          BiPredicate<AltoClef, Vec3d> positionValidator,
                          BiPredicate<AltoClef, Entity> entityValidator,
                          BiPredicate<AltoClef, BlockPos> blockPosValidator) {
        this.position = position;
        this.entity = entity;
        this.blockPos = blockPos;
        this.hasPosition = hasPosition;
        this.positionValidator = positionValidator;
        this.entityValidator = entityValidator;
        this.blockPosValidator = blockPosValidator;
    }

    public static PositionWrapper ofVec3d(Vec3d pos) {
        return new PositionWrapper(pos, null, null, true,
                (mod, p) -> !mod.getBlockScanner().isUnreachable(new BlockPos((int)p.x, (int)p.y, (int)p.z)),
                null, null);
    }

    public static PositionWrapper ofVec3d(Vec3d pos, BiPredicate<AltoClef, Vec3d> validator) {
        return new PositionWrapper(pos, null, null, true, validator, null, null);
    }

    public static PositionWrapper ofEntity(Entity entity) {
        return new PositionWrapper(null, entity, null, true,
                null,
                (mod, e) -> e.isAlive() && mod.getEntityTracker().isEntityReachable(e),
                null);
    }

    public static PositionWrapper ofEntity(Entity entity, BiPredicate<AltoClef, Entity> validator) {
        return new PositionWrapper(null, entity, null, true, null, validator, null);
    }

    public static PositionWrapper ofBlockPos(BlockPos blockPos) {
        return new PositionWrapper(null, null, blockPos, true,
                null, null,
                (mod, pos) -> true);
    }

    public static PositionWrapper ofBlockPos(BlockPos blockPos, BiPredicate<AltoClef, BlockPos> validator) {
        return new PositionWrapper(null, null, blockPos, true, null, null, validator);
    }

    public static PositionWrapper empty() {
        return new PositionWrapper(null, null, null, false, null, null, null);
    }

    public boolean isValid(AltoClef mod) {
        if (position != null) {
            return positionValidator != null ? positionValidator.test(mod, position) :
                   !mod.getBlockScanner().isUnreachable(new BlockPos((int)position.x, (int)position.y, (int)position.z));
        }
        if (entity != null) {
            return entityValidator != null ? entityValidator.test(mod, entity) :
                entity != null && entity.isAlive() && mod.getEntityTracker().isEntityReachable(entity);
        }
        if (blockPos != null) {
            return blockPosValidator != null ? blockPosValidator.test(mod, blockPos) :
                   (MinecraftClient.getInstance().world != null && !MinecraftClient.getInstance().world.isAir(blockPos));
        }
        return getPos() != null;
    }

    public Vec3d getPos() {
        if (position != null) return position;
        if (entity != null) return entity.getPos();
        if (blockPos != null) return WorldHelper.toVec3d(blockPos);
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof PositionWrapper wrapper) {
            return (position == null || wrapper.position == null || position.equals(wrapper.position)) &&
                    (entity == null || wrapper.entity == null || entity.equals(wrapper.entity)) &&
                    (blockPos == null || wrapper.blockPos == null || blockPos.equals(wrapper.blockPos));
        }
        return false;
    }

    public String toString() {
        return "PositionWrapper{" +
                "position=" + position +
                ", entity=" + entity +
                ", blockPos=" + blockPos +
                ", hasPosition=" + hasPosition +
                '}';
    }

    public boolean hasPosition() {
        return hasPosition;
    }
}
