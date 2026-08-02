package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.slots.Slot;
import kaptainwutax.tungsten.path.movements.Rotation;
import kaptainwutax.tungsten.path.movements.RotationHelper;
import kaptainwutax.tungsten.util.WindMouseRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.Objects;
import java.util.Optional;

/**
 * Helper functions to interpret and change our player's look direction
 *
 * <p>The aim maths live in tungsten's {@link RotationHelper}, which is the line-for-line port of
 * baritone's {@code RotationUtils} / {@code RayTraceUtils} / {@code IPlayerContext} accessors, so
 * every reach test and every rotation computed here is the same number it always was. The value
 * type is tungsten's {@link Rotation} — a verbatim copy of baritone's, same constructor order
 * (yaw, pitch), same helpers.
 *
 * <p>The one behaviour that did NOT survive the move is baritone's {@code
 * LookBehavior.updateTarget}: see {@link #lookAt(AltoClef, Rotation, boolean)} for why it was a
 * no-op-with-a-side-effect in this mod's configuration and what replaced it.
 */
public interface LookHelper {
    /**
     * Calculate the reachable rotation for a given target and side.
     *
     * @param target the target block position
     * @param side   the side direction
     * @return an optional rotation if reachable, otherwise empty
     */
    static Optional<Rotation> getReach(BlockPos target, Direction side) {
        // The reach test only ever needed the player and a reach distance; the baritone player
        // context this used to go through was just a wrapper around mc.player. RotationHelper's
        // blockReachDistance() is the same 4.5 default, clamped against the vanilla attribute so a
        // server handing out a longer reach can't make this gate optimistic.
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            // The old path would NPE here. Nothing is reachable without a player, so say so.
            return Optional.empty();
        }
        double blockReachDistance = RotationHelper.blockReachDistance(player);

        // Declare the reachable rotation variable
        Optional<Rotation> reachableRotation;

        // Check if the side is null
        if (side == null) {
            // Calculate the reachable rotation from the player's position to the target position
            reachableRotation = RotationHelper.reachable(player, target, blockReachDistance);
        } else {
            // Calculate the center offset vector based on the side direction
            Vec3i sideVector = side.getVector();
            Vec3d centerOffset = new Vec3d(0.5 + sideVector.getX() * 0.5, 0.5 + sideVector.getY() * 0.5,
                    0.5 + sideVector.getZ() * 0.5);

            // Calculate the side point based on the center offset and target position
            Vec3d sidePoint = centerOffset.add(target.getX(), target.getY(), target.getZ());

            // Calculate the reachable rotation from the player's position to the side point
            reachableRotation = RotationHelper.reachableOffset(player, target, sidePoint,
                    blockReachDistance, false);

            // Check if the reachable rotation is present
            if (reachableRotation.isPresent()) {
                // Calculate the camera position and vector to player position
                Vec3d cameraPos = player.getCameraPosVec(1.0F);
                Vec3d vecToPlayerPos = cameraPos.subtract(sidePoint);

                // Calculate the dot product between the vector to player position and the side vector
                double dotProduct = vecToPlayerPos.normalize().dotProduct(
                        new Vec3d(sideVector.getX(), sideVector.getY(), sideVector.getZ()));

                // Check if the dot product is less than 0
                if (dotProduct < 0) {
                    // Return an empty optional rotation
                    return Optional.empty();
                }
            }
        }

        // Return the reachable rotation
        return reachableRotation;
    }

    /**
     * Gets the reach for a given target position.
     *
     * @param target The target position.
     * @return An Optional containing the Rotation if reach is possible, or an empty Optional otherwise.
     */
    static Optional<Rotation> getReach(BlockPos target) {
        // Log the target position
        Debug.logInternal("Target: " + target);

        // Delegate to the overloaded method with a null entity
        return getReach(target, null);
    }

    /**
     * Calculates a raycast from one entity to another.
     *
     * @param from          The entity from which the raycast originates.
     * @param to            The entity at which the raycast is aimed.
     * @param reachDistance The maximum distance the raycast can reach.
     * @return The result of the raycast.
     */
    static EntityHitResult raycast(Entity from, Entity to, double reachDistance) {
        // Get the starting position of the raycast
        Vec3d start = getCameraPos(from);

        // Get the ending position of the raycast
        Vec3d end = getCameraPos(to);

        // Calculate the direction of the raycast
        Vec3d direction = end.subtract(start).normalize().multiply(reachDistance);

        // Get the bounding box of the target entity
        Box box = to.getBoundingBox();

        // Perform the raycast and return the result
        return ProjectileUtil.raycast(from, start, start.add(direction), box, entity -> entity.equals(to), 0);
    }

    /**
     * Check if an entity can see a player within a certain range, taking into account entity and player offsets.
     *
     * @param entity       The entity to check.
     * @param player       The player entity to check against.
     * @param maxRange     The maximum range within which the entity can see the player.
     * @param entityOffset The offset of the entity.
     * @param playerOffset The offset of the player.
     * @return True if the entity can see the player, false otherwise.
     */
    static boolean seesPlayer(Entity entity, Entity player, double maxRange, Vec3d entityOffset, Vec3d playerOffset) {
        return seesPlayerOffset(entity, player, maxRange, entityOffset, playerOffset)
                || seesPlayerOffset(entity, player, maxRange, entityOffset, playerOffset.add(0, -1, 0));
    }

    /**
     * Determines if the given entity can see the player within the specified range.
     *
     * @param entity   the entity to check visibility from
     * @param player   the player entity to check visibility to
     * @param maxRange the maximum range within which the player can be seen
     * @return true if the player is visible within the specified range, false otherwise
     */
    static boolean seesPlayer(Entity entity, Entity player, double maxRange) {
        return seesPlayer(entity, player, maxRange, new Vec3d(0, 0, 0), new Vec3d(0, 0, 0));
    }

    /**
     * Checks if there is a clear line of sight between the start and end points for the given entity.
     *
     * @param entity   The entity to check line of sight for.
     * @param start    The starting position of the line of sight.
     * @param end      The ending position of the line of sight.
     * @param maxRange The maximum range for the line of sight.
     * @return true if there is a clear line of sight, false otherwise.
     */
    static boolean cleanLineOfSight(Entity entity, Vec3d start, Vec3d end, double maxRange) {
        // Perform a raycast between the start and end points with the given max range
        HitResult result = raycast(entity, start, end, maxRange);

        // Check the type of the hit result to determine if there is a clear line of sight
        return result.getType() == HitResult.Type.MISS;
    }

    /**
     * Checks if there is a clear line of sight between an entity and a specified location.
     *
     * @param entity   The entity from which to check the line of sight.
     * @param end      The end location to check the line of sight to.
     * @param maxRange The maximum range at which the line of sight can be checked.
     * @return True if there is a clear line of sight, false otherwise.
     */
    static boolean cleanLineOfSight(Entity entity, Vec3d end, double maxRange) {
        // Get the starting position of the line of sight
        Vec3d start = getCameraPos(entity);

        // Check if there is a clear line of sight between the starting and end positions,
        // within the maximum range
        return cleanLineOfSight(entity, start, end, maxRange);
    }

    /**
     * Checks if there is a clear line of sight between the player and a given point.
     *
     * @param end      The end point to check for line of sight.
     * @param maxRange The maximum range to check for line of sight.
     * @return True if there is a clear line of sight, false otherwise.
     */
    static boolean cleanLineOfSight(Vec3d end, double maxRange) {
        MinecraftClient minecraftClient = MinecraftClient.getInstance();
        PlayerEntity playerEntity = minecraftClient.player;
        return cleanLineOfSight(playerEntity, end, maxRange);
    }

    /**
     * Checks if there is a clear line of sight between an entity and a block position within a given maximum range.
     *
     * @param entity   The entity from which the line of sight is checked.
     * @param block    The block position to check the line of sight to.
     * @param maxRange The maximum range to check for line of sight.
     * @return True if there is a clear line of sight, false otherwise.
     */
    static boolean cleanLineOfSight(Entity entity, BlockPos block, double maxRange) {
        // Convert the block position to a Vec3d
        Vec3d targetPosition = WorldHelper.toVec3d(block);

        // Perform a raycast from the entity's camera position to the target position with the specified max range
        BlockHitResult hitResult = raycast(entity, getCameraPos(entity), targetPosition, maxRange);

        // Check the result of the raycast
        if (hitResult == null) {
            // No hit result, clear line of sight
            return true;
        } else {
            return switch (hitResult.getType()) {
                case MISS ->
                    // Missed the target, clear line of sight
                        true;
                case BLOCK ->
                    // Hit a block, check if it's the same as the target block
                        hitResult.getBlockPos().equals(block);
                case ENTITY ->
                    // Hit an entity, line of sight blocked
                        false;
            };
        }
    }

    /**
     * Convert a Rotation object to a Vec3d object.
     *
     * @param rotation the Rotation object to convert
     * @return the corresponding Vec3d object
     * @throws NullPointerException if the rotation is null
     */
    static Vec3d toVec3d(Rotation rotation) throws NullPointerException {
        // make sure rotation is not null
        Objects.requireNonNull(rotation, "Rotation cannot be null");

        // calculate the look direction from the rotation
        return calcLookDirectionFromRotation(rotation);
    }

    static Vec3d calcLookDirectionFromRotation(Rotation rotation) {
        // Was an inlined copy of baritone's RotationUtils.calcLookDirectionFromRotation with the
        // constants written out (0.017453292F is DEG_TO_RAD, 3.1415927F is pi). Delegated so the
        // raytraces done here and the ones RotationHelper does inside reachable() cannot drift
        // apart — a direction vector that disagrees with the reach gate is invisible until an
        // aimed click misses.
        return RotationHelper.calcLookDirectionFromRotation(rotation);
    }

    /**
     * Performs a raycast from the start point to the end point within a maximum range.
     *
     * @param entity   the entity performing the raycast
     * @param start    the starting point of the raycast
     * @param end      the ending point of the raycast
     * @param maxRange the maximum range of the raycast
     * @return the result of the raycast
     */
    static BlockHitResult raycast(Entity entity, Vec3d start, Vec3d end, double maxRange) {
        // Calculate the direction vector
        Vec3d direction = end.subtract(start);

        // Check if the direction vector length exceeds the maximum range
        if (direction.lengthSquared() > maxRange * maxRange) {
            // If it does, normalize the direction vector and multiply it by the maximum range
            direction = direction.normalize().multiply(maxRange);
            // Update the end point of the raycast to the new calculated position
            end = start.add(direction);
        }

        // Get the world of the entity
        World world = entity.getWorld();

        // Create a raycast context with the start and end points, shape type, fluid handling, and entity performing the raycast
        RaycastContext context = new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity);

        // Perform the raycast in the world and return the result
        return world.raycast(context);
    }

    /**
     * Performs a raycast from the entity's camera position to the specified end point
     * with a maximum range.
     *
     * @param entity   The entity performing the raycast
     * @param end      The end point of the raycast
     * @param maxRange The maximum range of the raycast
     * @return The result of the raycast
     */
    static BlockHitResult raycast(Entity entity, Vec3d end, double maxRange) {
        Vec3d start = getCameraPos(entity);
        return raycast(entity, start, end, maxRange);
    }

    /**
     * Get the look rotation of an entity.
     *
     * @param entity the entity to get the look rotation for
     * @return the look rotation of the entity
     */
    static Rotation getLookRotation(Entity entity) {
        float pitch = entity.getPitch();
        float yaw = entity.getYaw();
        return new Rotation(yaw, pitch);
    }

    /**
     * Retrieves the look rotation of the player.
     * If the player is null, returns a default rotation of (0, 0).
     *
     * @return The look rotation of the player.
     */
    static Rotation getLookRotation() {
        // Retrieve the player instance
        PlayerEntity player = MinecraftClient.getInstance().player;

        // If the player is null, return a default rotation
        if (player == null) {
            return new Rotation(0, 0);
        }

        // Get the look rotation of the player
        return getLookRotation(player);
    }

    /**
     * Retrieves the camera position of the given entity.
     * If the entity is a player and is sneaking, the sneaking eye position is inferred.
     * Otherwise, the default camera position of the entity is returned.
     *
     * @param entity The entity for which to retrieve the camera position.
     * @return The camera position of the entity.
     */
    static Vec3d getCameraPos(Entity entity) {
        boolean isPlayerSneaking = entity instanceof PlayerEntity && entity.isSneaking();

        // If the entity is a player and is sneaking, infer the sneaking eye position
        if (isPlayerSneaking) {
            // Same hardcoded 1.27 sneaking eye as baritone's RayTraceUtils — deliberately NOT the
            // live pose height, so the answer is about the tick the bot will be sneaking in.
            return RotationHelper.inferSneakingEyePosition(entity);
        } else {
            // Otherwise, return the default camera position of the entity
            return entity.getCameraPosVec(1.0F);
        }
    }

    /**
     * Retrieves the camera position vector of the player.
     *
     * @param mod The instance of the AltoClef mod.
     * @return The camera position vector.
     */
    static Vec3d getCameraPos(AltoClef mod) {
        // The baritone player context this used to walk through resolved to mc.player, which is
        // the same object mod.getPlayer() returns.
        return mod.getPlayer().getCameraPosVec(1);
    }

    /**
     * Calculates the closeness between an entity's look direction and a given position.
     *
     * @param entity The entity to calculate the closeness for.
     * @param pos    The position to compare the look direction to.
     * @return The closeness value between the look direction and the position.
     */
    static double getLookCloseness(Entity entity, Vec3d pos) {
        // Get the direction that the entity is facing
        Vec3d rotDirection = entity.getRotationVecClient();

        // Get the starting position of the entity's line of sight
        Vec3d lookStart = getCameraPos(entity);

        // Calculate the vector from the look start position to the given position
        Vec3d deltaToPos = pos.subtract(lookStart);

        // Normalize the delta vector to get the direction
        Vec3d deltaDirection = deltaToPos.normalize();

        // Calculate the dot product of the rotation direction and the delta direction
        return rotDirection.dotProduct(deltaDirection);
    }

    /**
     * Tries to avoid colliding with an interactable object.
     * If a collision is detected, the function randomly changes the orientation and returns false.
     * If no collision is detected, the function returns true.
     *
     * @param mod The AltoClef object.
     * @return True if no collision is detected, false otherwise.
     */
    static boolean tryAvoidingInteractable(AltoClef mod) {
        if (isCollidingInteractable(mod)) {
            randomOrientation();
            return false;
        }
        return true;
    }

    /**
     * Determines whether an entity can see another entity with specified offsets.
     *
     * @param entity       The entity that is trying to see the player.
     * @param player       The player entity that is being looked at.
     * @param maxRange     The maximum range within which the player can be seen.
     * @param offsetEntity The offset of the camera position for the entity.
     * @param offsetPlayer The offset of the camera position for the player.
     * @return True if the entity can see the player, false otherwise.
     */
    private static boolean seesPlayerOffset(Entity entity, Entity player, double maxRange, Vec3d offsetEntity, Vec3d offsetPlayer) {
        // Calculate the camera positions for the entity and player
        Vec3d entityCameraPos = getCameraPos(entity).add(offsetEntity);
        Vec3d playerCameraPos = getCameraPos(player).add(offsetPlayer);

        // Check if there is a clean line of sight between the entity and player within the specified range
        return cleanLineOfSight(entity, entityCameraPos, playerCameraPos, maxRange);
    }

    /**
     * Checks if the player is colliding with an interactable object.
     *
     * @param mod The instance of the AltoClef mod.
     * @return True if the player is colliding with an interactable object, false otherwise.
     */
    private static boolean isCollidingInteractable(AltoClef mod) {
        // If a container/GUI screen is open, report as "colliding" but do NOT close it.
        // Closing screens here is a side effect that breaks SignShop, autojoin menus, etc.
        if (!(mod.getPlayer().currentScreenHandler instanceof PlayerScreenHandler)) {
            return true;
        }

        // Get the crosshair target
        HitResult result = MinecraftClient.getInstance().crosshairTarget;

        // Check if the crosshair target is null
        if (result == null) {
            return false;
        }

        // Check if the crosshair target is a block
        if (result.getType() == HitResult.Type.BLOCK) {
            // Get the block position from the crosshair target
            Vec3i resultGetPosOrigin = new Vec3i((int) result.getPos().getX(), (int) result.getPos().getY(), (int) result.getPos().getZ());
            // Check if the block is an interactable block
            return WorldHelper.isInteractableBlock(new BlockPos(resultGetPosOrigin));
        }
        // Check if the crosshair target is an entity
        else if (result.getType() == HitResult.Type.ENTITY && result instanceof EntityHitResult) {
            // Get the entity from the crosshair target
            Entity entity = ((EntityHitResult) result).getEntity();
            // Check if the entity is a merchant
            return entity instanceof MerchantEntity;
        }

        return false;
    }

    /**
     * Sets a random orientation for the given mod.
     */
    static void randomOrientation() {
        // Generate random rotation angles
        float randomRotationX = (float) (Math.random() * 360f);
        float randomRotationY = -90 + (float) (Math.random() * 180f);

        // Create a new Rotation object with the random angles
        Rotation r = new Rotation(randomRotationX, randomRotationY);

        // Set the mod to look at the rotation
        lookAt(r);
    }

    /**
     * Checks if the given rotation is close to the current look rotation.
     *
     * @param mod      The instance of the AltoClef class.
     * @param rotation The rotation to compare with the current look rotation.
     * @return True if the rotation is close to the current look rotation, false otherwise.
     */
    static boolean isLookingAt(AltoClef mod, Rotation rotation) {
        return rotation.isReallyCloseTo(getLookRotation());
    }

    /**
     * Check if the player is looking at a specific block position.
     *
     * @param mod The instance of the AltoClef mod.
     * @param pos The block position to check.
     * @return True if the player is looking at the given block position, false otherwise.
     */
    static boolean isLookingAt(AltoClef mod, BlockPos pos) {
        // A LIVE raytrace along the player's current rotations, not mc.crosshairTarget: the cached
        // crosshair is a tick or two stale at low framerates, and callers use this as the "safe to
        // click now" gate. RotationHelper.isLookingAt is baritone's IPlayerContext.isLookingAt
        // verbatim (getSelectedBlock().equals(Optional.of(pos))), including honouring the current
        // fluid-handling mode set by BotBehaviour.
        return RotationHelper.isLookingAt(mod.getPlayer(), pos);
    }

    /**
     * Snaps the player's look direction to a rotation, immediately. Callers rely on that: the
     * usual shape is {@code lookAt(...)} and then a click in the SAME tick, so this must not
     * become a request that arrives a few frames later.
     *
     * <p>WHY THE {@code withBaritone} FLAG NO LONGER DOES ANYTHING. It used to add
     * {@code getLookBehavior().updateTarget(rotation, true)} on top of the snap below. In THIS
     * mod's configuration that bought nothing and cost accuracy:
     * <ul>
     *   <li>{@code AltoClef.initializeBaritoneSettings} sets {@code freeLook = false}, so the
     *       target resolved to CLIENT mode — one tick later baritone re-applied the very rotation
     *       this method had already written, then dropped the target. A one-tick echo.</li>
     *   <li>Every altoclef call site passed {@code blockInteract = true}, which made LookBehavior
     *       snap its render smoother to the same value, so there was no visual smoothing either.</li>
     *   <li>The echo went through {@code AimProcessor.peekRotation}, whose {@code nudgeToLevel}
     *       fires when the requested pitch equals the current pitch — which the snap below makes
     *       true by construction. So the rotation actually SENT drifted 1 degree toward level
     *       whenever the pitch was outside [-20, 10]. Aiming straight down for an MLG went out as
     *       89, not 90. That was a bug riding on the coupling, not a feature.</li>
     * </ul>
     *
     * <p>Tungsten's {@link WindMouseRotation} is deliberately NOT armed here. It is a LEASE — it
     * holds a target for 600 ms and keeps steering the camera back to it every render frame —
     * whereas baritone's target lasted exactly one tick. Arming it from a snap would hand this
     * helper an ownership it never had, and it would fight {@link #updateWindMouseRotation}, which
     * drives the combat aim through {@link #lookAtForced} on the same camera. Use
     * {@code WindMouseRotation.INSTANCE.setTarget(...)} directly where a lease is what you want
     * (request an aim, poll {@link #isLookingAt(AltoClef, BlockPos)}, then click).
     *
     * @param mod      The instance of AltoClef.
     * @param rotation The desired rotation to look at.
     * @param withBaritone Retained so the ~50 call sites keep compiling; inert since the
     *                     baritone/shredder look path was cut. Both values snap.
     */
    static void lookAt(AltoClef mod, Rotation rotation, boolean withBaritone) {
        // Set the player's yaw and pitch
        mod.getPlayer().setYaw(rotation.getYaw());
        mod.getPlayer().setPitch(rotation.getPitch());
    }

    /**
     * Updates the player's look direction and rotation.
     *
     * @param rotation The desired rotation to look at.
     */
    static void lookAt(Rotation rotation) {
        // Snap only — see lookAt(AltoClef, Rotation, boolean) for why the baritone look target
        // that used to sit here was dropped rather than re-pointed at tungsten.
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        player.setYaw(rotation.getYaw());
        player.setPitch(rotation.getPitch());
    }

    /**
     * Adjusts the player's look direction to the specified target position.
     *
     * @param mod    The AltoClef instance.
     * @param toLook The position to look at.
     * @param withBaritone Forwarded to {@link #lookAt(AltoClef, Rotation, boolean)}; inert.
     * @throws IllegalArgumentException if mod or toLook is null.
     */
    static void lookAt(AltoClef mod, Vec3d toLook, boolean withBaritone) {
        if (mod == null || toLook == null) {
            throw new IllegalArgumentException("mod and toLook cannot be null");
        }

        Rotation targetRotation = getLookRotation(mod, toLook);
        lookAt(mod, targetRotation, withBaritone);
    }

    /**
     * Adjusts the player's look direction to the specified target position.
     *
     * @param mod    The AltoClef instance.
     * @param toLook The position to look at.
     * @throws IllegalArgumentException if mod or toLook is null.
     */
    static void lookAt(AltoClef mod, Vec3d toLook) {
        if (mod == null || toLook == null) {
            throw new IllegalArgumentException("mod and toLook cannot be null");
        }

        Rotation targetRotation = getLookRotation(mod, toLook);
        lookAt(mod, targetRotation, true);
    }

    /**
     * Adjusts the player's view to look at a specific location from a specific direction.
     *
     * @param mod    The AltoClef mod instance.
     * @param toLook The position to look at.
     * @param side   The direction to look from.
     * @param withBaritone Forwarded to {@link #lookAt(AltoClef, Rotation, boolean)}; inert.
     */
    static void lookAt(AltoClef mod, BlockPos toLook, Direction side, boolean withBaritone) {
        // Calculate the center coordinates of the target location
        double centerX = toLook.getX() + 0.5;
        double centerY = toLook.getY() + 0.5;
        double centerZ = toLook.getZ() + 0.5;

        // Adjust the center coordinates based on the specified side
        if (side != null) {
            double offsetX = side.getVector().getX() * 0.5;
            double offsetY = side.getVector().getY() * 0.5;
            double offsetZ = side.getVector().getZ() * 0.5;
            centerX += offsetX;
            centerY += offsetY;
            centerZ += offsetZ;
        }

        // Create a target vector based on the adjusted center coordinates
        Vec3d target = new Vec3d(centerX, centerY, centerZ);

        // Adjust the player's view to look at the target location
        lookAt(mod, target, withBaritone);
    }

    /**
     * Adjusts the player's view to look at a specific location from a specific direction.
     *
     * @param mod    The AltoClef mod instance.
     * @param toLook The position to look at.
     * @param side   The direction to look from.
     */
    static void lookAt(AltoClef mod, BlockPos toLook, Direction side) {
        // Calculate the center coordinates of the target location
        double centerX = toLook.getX() + 0.5;
        double centerY = toLook.getY() + 0.5;
        double centerZ = toLook.getZ() + 0.5;

        // Adjust the center coordinates based on the specified side
        if (side != null) {
            double offsetX = side.getVector().getX() * 0.5;
            double offsetY = side.getVector().getY() * 0.5;
            double offsetZ = side.getVector().getZ() * 0.5;
            centerX += offsetX;
            centerY += offsetY;
            centerZ += offsetZ;
        }

        // Create a target vector based on the adjusted center coordinates
        Vec3d target = new Vec3d(centerX, centerY, centerZ);

        // Adjust the player's view to look at the target location
        lookAt(mod, target, true);
    }

    /**
     * Looks at the specified block position.
     *
     * @param mod    The AltoClef instance.
     * @param toLook The block position to look at.
     * @param withBaritone Forwarded to {@link #lookAt(AltoClef, Rotation, boolean)}; inert.
     */
    static void lookAt(AltoClef mod, BlockPos toLook, boolean withBaritone) {
        lookAt(mod, toLook, null, withBaritone);
    }

    /**
     * Looks at the specified block position.
     *
     * @param mod    The AltoClef instance.
     * @param toLook The block position to look at.
     */
    static void lookAt(AltoClef mod, BlockPos toLook) {
        lookAt(mod, toLook, null, true);
    }

    /**
     * Calculates the rotation needed for a player to look at a specified point.
     *
     * @param mod    The instance of the main mod class.
     * @param toLook The coordinates to look at.
     * @return The rotation needed to look at the specified point.
     */
    public static Rotation getLookRotation(AltoClef mod, Vec3d toLook) {
        // Get the player's head position. NOT getEyePos(): playerHead() is feet + STANDING eye
        // height, so the answer doesn't change when the bot happens to be crouching mid-aim.
        Vec3d playerHead = RotationHelper.playerHead(mod.getPlayer());

        // Get the player's current rotations
        Rotation playerRotations = RotationHelper.playerRotations(mod.getPlayer());

        // Calculate the rotation needed to look at the specified point. Passing the current
        // rotations is what wraps the yaw relatively, so +179 -> -179 is a 2 degree turn rather
        // than 358 — dropping that argument would make every aim take the long way round.
        return RotationHelper.calcRotationFromVec3d(playerHead, toLook, playerRotations);
    }

    /**
     * Returns the rotation needed to look at a specified position.
     *
     * @param mod    The AltoClef mod instance.
     * @param toLook The position to look at, specified by its BlockPos.
     * @return The Rotation object representing the rotation needed to look at the position.
     */
    static Rotation getLookRotation(AltoClef mod, BlockPos toLook) {
        // Convert BlockPos to Vec3d
        Vec3d targetPosition = WorldHelper.toVec3d(toLook);

        // Delegate to the overloaded version of getLookRotation
        return getLookRotation(mod, targetPosition);
    }

    public static double getLookingProbability(PlayerEntity plyFrom, PlayerEntity plyTo) {
        return getLookingProbability(plyFrom.getEyePos(), plyTo.getEyePos(), plyFrom.getRotationVec(0));
    }

    public static double getLookingProbability(Vec3d eyeFrom, Vec3d eyeTo, Vec3d rotationFrom) {
        if (eyeFrom == null || eyeTo == null || rotationFrom == null) return 0d;
        Vec3d toEntity = eyeTo.subtract(eyeFrom);
        return toEntity.normalize().dotProduct(rotationFrom);
    }

    // --- Smooth look methods (ported from autoclef) ---

    static void lookAtForced(AltoClef mod, Rotation rotation) {
        mod.getInputControls().forceLook(rotation.getYaw(), rotation.getPitch());
    }

    public static float normalizeAngle(float angle) {
        angle = angle % 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        else if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static Vec3d getClosestPointOnBoundingBox(Vec3d playerPos, Box boundingBox) {
        double closestX = clamp(playerPos.x, boundingBox.minX, boundingBox.maxX);
        double closestY = clamp(playerPos.y, boundingBox.minY, boundingBox.maxY);
        double closestZ = clamp(playerPos.z, boundingBox.minZ, boundingBox.maxZ);
        return new Vec3d(closestX, closestY, closestZ);
    }

    public static Vec3d getClosestPointOnEntityHitbox(AltoClef mod, Entity entity) {
        return getClosestPointOnBoundingBox(mod.getPlayer().getEyePos(), entity.getBoundingBox());
    }

    public static boolean canHitEntity(AltoClef mod, Entity entity, float range) {
        Vec3d closestPoint = getClosestPointOnEntityHitbox(mod, entity);
        double distance = mod.getPlayer().getPos().distanceTo(entity.getPos());
        return cleanLineOfSight(closestPoint, distance) && distance < range;
    }

    public static boolean canHitEntity(AltoClef mod, Entity entity) {
        return canHitEntity(mod, entity, 4.5f);
    }

    public static Vec3d getOptimalAimPoint(AltoClef mod, Entity entity) {
        Vec3d closestPoint = getClosestPointOnEntityHitbox(mod, entity);
        double distanceSq = mod.getPlayer().getEyePos().squaredDistanceTo(closestPoint);
        if (distanceSq < 4.0) {
            closestPoint = closestPoint.add(0, 0.3 * (1.0 - distanceSq / 4.0), 0);
        }
        return closestPoint;
    }

    public static boolean isLookingAtEntity(AltoClef mod, Entity entity, double maxDistance, double precision) {
        if (entity == null || !entity.isAlive()) return false;
        Vec3d eyePos = mod.getPlayer().getEyePos();
        Vec3d lookVec = mod.getPlayer().getRotationVec(1.0F);
        Box entityBox = entity.getBoundingBox();
        Vec3d ray = lookVec.multiply(maxDistance);
        Optional<Vec3d> hitOptional = entityBox.raycast(eyePos, eyePos.add(ray));
        if (hitOptional.isEmpty()) return false;
        Vec3d hitPoint = hitOptional.get();
        Rotation hitRotation = getLookRotation(mod, hitPoint);
        Rotation currentRotation = getLookRotation(mod.getPlayer());
        float yawDiff = Math.abs(normalizeAngle(hitRotation.getYaw() - currentRotation.getYaw()));
        float pitchDiff = Math.abs(hitRotation.getPitch() - currentRotation.getPitch());
        return yawDiff <= precision && pitchDiff <= precision;
    }

    public static boolean isLookingAtEntity(AltoClef mod, Entity entity) {
        return isLookingAtEntity(mod, entity, 6.0, 2.0);
    }

    // WindMouse smooth look state
    public static class WindMouseState {
        public static boolean isRotating = false;
        public static double windX = 0, windY = 0, veloX = 0, veloY = 0;
        public static double currentX = 0, currentY = 0;
        public static Rotation targetRotation = null;
        public static Rotation startRotation = null;
        public static Entity targetEntity = null;
        public static float speed = 1.0f;
        public static long lastUpdateTime = System.currentTimeMillis();
        public static long lastUpdateTimeInternal = System.currentTimeMillis();
        public static final long ROTATION_TIMEOUT = 1000;
        // Flick state: for large angle changes, do a single fast burst then settle
        public static boolean flickInjected = false;
    }

    public static boolean isCloseRotations(Rotation startRot, Rotation newRot) {
        // tolerance=1000 means this essentially never returns true (max angular diff is 360).
        // Kept for API compatibility with autoclef.
        float closenessTolerance = 1000;
        return (Math.abs(normalizeAngle(startRot.getYaw() - newRot.getYaw())) > closenessTolerance ||
                Math.abs(startRot.getPitch() - newRot.getPitch()) > closenessTolerance);
    }

    private static void smoothLookInternal(AltoClef mod, Rotation targetRot, Entity targetEntity, float speed) {
        long currentTime = System.currentTimeMillis();
        boolean isNewRotation = !WindMouseState.isRotating ||
                currentTime - WindMouseState.lastUpdateTime > WindMouseState.ROTATION_TIMEOUT;

        boolean shouldReset = isNewRotation;
        if (targetEntity != null) {
            shouldReset = shouldReset || WindMouseState.targetEntity == null
                    || !WindMouseState.targetEntity.equals(targetEntity);
        }

        if (shouldReset || (WindMouseState.targetRotation != null && !isCloseRotations(targetRot, WindMouseState.targetRotation))) {
            WindMouseState.isRotating = true;
            WindMouseState.targetEntity = targetEntity;
            WindMouseState.startRotation = getLookRotation(mod.getPlayer());
            WindMouseState.flickInjected = false;
        }
        WindMouseState.targetEntity = targetEntity;
        WindMouseState.isRotating = true;
        WindMouseState.targetRotation = targetRot;
        WindMouseState.speed = speed;
        WindMouseState.lastUpdateTime = currentTime;
    }

    public static boolean updateWindMouseRotation(AltoClef mod) {
        long currentTime = System.currentTimeMillis();
        // CRITICAL: always advance the clock even when not rotating — prevents huge timeDelta spike on restart
        double timeDelta = (currentTime - WindMouseState.lastUpdateTimeInternal) / 1000.0;
        timeDelta *= WindMouseState.speed;
        WindMouseState.lastUpdateTimeInternal = currentTime;

        if (!WindMouseState.isRotating) return true;

        if (currentTime - WindMouseState.lastUpdateTime > WindMouseState.ROTATION_TIMEOUT) {
            WindMouseState.isRotating = false;
            WindMouseState.targetEntity = null;
            WindMouseState.currentX = 0; WindMouseState.currentY = 0;
            WindMouseState.windX = 0; WindMouseState.windY = 0;
            WindMouseState.veloX = 0; WindMouseState.veloY = 0;
            WindMouseState.flickInjected = false;
            return true;
        }

        if (WindMouseState.targetEntity != null && WindMouseState.targetEntity.isAlive()) {
            WindMouseState.targetRotation = getLookRotation(mod, getClosestPointOnEntityHitbox(mod, WindMouseState.targetEntity));
        }
        if (WindMouseState.targetRotation == null) return false;

        // Don't fight whoever else is steering the camera. This used to test baritone's
        // CustomGoalProcess; tungsten's equivalent is stronger and covers more: an active
        // WindMouseRotation target means the path executor, the walker, a combat controller, a
        // bridge/pillar task or the py4j agent is driving the aim through the vanilla mouse
        // pipeline right now. Two drivers writing rotation in the same tick is visible jitter, so
        // the loser yields. The lease auto-expires after 600 ms, so this can never wedge.
        if (WindMouseRotation.INSTANCE.hasTarget()) {
            return false;
        }

        Rotation currentRotation = getLookRotation(mod.getPlayer());
        double deltaYaw = normalizeAngle(WindMouseState.targetRotation.getYaw() - currentRotation.getYaw());
        double deltaPitch = WindMouseState.targetRotation.getPitch() - currentRotation.getPitch();
        double distanceToTarget = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distanceToTarget < 0.01) { WindMouseState.isRotating = false; return true; }

        // FLICK: for large angle changes, apply a single fast burst (human mouse flick) then settle normally.
        // Covers 70–85 % of the distance in one tick with a tiny lateral deviation — no magnitude jitter,
        // just the slight directional imprecision of a real mouse flick.
        if (!WindMouseState.flickInjected && distanceToTarget > 30.0) {
            WindMouseState.flickInjected = true;
            double coverFraction = 0.70 + (Math.random() * 0.15); // 70–85 %
            double flickDist = distanceToTarget * coverFraction;
            // Small perpendicular noise: ±3 % of distance — makes trajectory slightly curved, not a laser line
            double lateralFraction = (Math.random() - 0.5) * 0.06;
            double nYaw   = deltaYaw   / distanceToTarget * flickDist + (-deltaPitch / distanceToTarget) * lateralFraction * flickDist;
            double nPitch = deltaPitch / distanceToTarget * flickDist + ( deltaYaw   / distanceToTarget) * lateralFraction * flickDist;
            lookAtForced(mod, new Rotation(
                normalizeAngle(currentRotation.getYaw()   + (float) nYaw),
                clamp((float)(currentRotation.getPitch() + nPitch), -90f, 90f)
            ));
            // Reset velocity so the settle phase starts from zero
            WindMouseState.veloX = 0; WindMouseState.veloY = 0;
            WindMouseState.windX = 0; WindMouseState.windY = 0;
            return false;
        }

        double baseWind = 1000, baseGravity = 8000, baseMaxStep = 1000000;
        double actualWind = baseWind * timeDelta, actualGravity = baseGravity * timeDelta, actualMaxStep = baseMaxStep * timeDelta;

        // distanceFactor = min(1.0, distanceToTarget) — always <= 1.0, so dampening below always applies (matches autoclef)
        double distanceFactor = Math.min(1.0, distanceToTarget);

        WindMouseState.windX = WindMouseState.windX / Math.sqrt(3) + ((Math.random() - 0.5) * actualWind * 2) / Math.sqrt(5);
        WindMouseState.windY = WindMouseState.windY / Math.sqrt(3) + ((Math.random() - 0.5) * actualWind * 2) / Math.sqrt(5);
        WindMouseState.veloX += WindMouseState.windX;
        WindMouseState.veloY += WindMouseState.windY;
        WindMouseState.veloX += deltaYaw * actualGravity;
        WindMouseState.veloY += deltaPitch * actualGravity;
        double veloMag = Math.sqrt(WindMouseState.veloX * WindMouseState.veloX + WindMouseState.veloY * WindMouseState.veloY);
        if (veloMag > actualMaxStep) {
            double randomDist = actualMaxStep / 2.0 + (Math.random() * actualMaxStep) / 2;
            WindMouseState.veloX = (WindMouseState.veloX / veloMag) * randomDist;
            WindMouseState.veloY = (WindMouseState.veloY / veloMag) * randomDist;
        }
        if (distanceFactor < 2) { // always true since distanceFactor <= 1.0 — dampening always active
            WindMouseState.veloX *= Math.pow(0.3, timeDelta * 60);
            WindMouseState.veloY *= Math.pow(0.3, timeDelta * 60);
        }
        double moveX = WindMouseState.veloX * timeDelta;
        double moveY = WindMouseState.veloY * timeDelta;
        lookAtForced(mod, new Rotation(normalizeAngle(currentRotation.getYaw() + (float)moveX), clamp((float)currentRotation.getPitch() + (float)moveY, -90f, 90f)));
        return false;
    }

    public static void smoothLook(AltoClef mod, Entity entity) {
        Rotation targetRot = getLookRotation(mod, getClosestPointOnEntityHitbox(mod, entity));
        smoothLookInternal(mod, targetRot, entity, 1.0f);
    }

    public static void smoothLook(AltoClef mod, Entity entity, float speed) {
        Rotation targetRot = getLookRotation(mod, getClosestPointOnEntityHitbox(mod, entity));
        smoothLookInternal(mod, targetRot, entity, speed);
    }

    public static void smoothLook(AltoClef mod, Vec3d pos) {
        smoothLookInternal(mod, getLookRotation(mod, pos), null, 1.0f);
    }

    public static void smoothLook(AltoClef mod, Rotation targetRot, float speed) {
        smoothLookInternal(mod, targetRot, null, speed);
    }

    public static void smoothLook(AltoClef mod, Rotation targetRot) {
        smoothLook(mod, targetRot, 1.0f);
    }

    public static void smoothLookAt(AltoClef mod, Vec3d position, float speed) {
        smoothLook(mod, getLookRotation(mod, position), speed);
    }

    public static void smoothLookAt(AltoClef mod, Vec3d position) {
        smoothLookAt(mod, position, 1.0f);
    }

    public static void smoothLookAt(AltoClef mod, Entity entity) {
        smoothLookAt(mod, entity.getEyePos(), 1.0f);
    }

    public static void randomOrientation(AltoClef mod) {
        float randomRotationX = (float)(Math.random() * 360f);
        float randomRotationY = -90 + (float)(Math.random() * 180f);
        smoothLook(mod, new Rotation(randomRotationX, randomRotationY));
    }

}
