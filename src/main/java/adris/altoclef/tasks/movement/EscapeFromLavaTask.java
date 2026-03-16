package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.multiversion.FoodComponentWrapper;
import adris.altoclef.multiversion.item.ItemVer;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

import java.util.Optional;
import java.util.function.Predicate;

public class EscapeFromLavaTask extends CustomBaritoneGoalTask {

    private final float strength;
    private int ticks = 0;
    private final Predicate<BlockPos> avoidPlacingRiskyBlock;

    public EscapeFromLavaTask(AltoClef mod,float strength) {
        this.strength = strength;
        avoidPlacingRiskyBlock = (blockPos -> mod.getPlayer().getBoundingBox().intersects(new Box(blockPos))
                && (mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().down()).getBlock() == Blocks.LAVA || mod.getPlayer().isInLava()));
    }


    public EscapeFromLavaTask(AltoClef mod) {
        this(mod, 100);
    }

    @Override
    protected void onStart() {
        AltoClef mod = AltoClef.getInstance();

        mod.getBehaviour().push();
        mod.getClientBaritone().getExploreProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getBehaviour().allowSwimThroughLava(true);
        // Encourage placing of all blocks!
        mod.getBehaviour().setBlockPlacePenalty(0);
        mod.getBehaviour().setBlockBreakAdditionalPenalty(0); // Normally 2
        // do NOT ever wander
        checker = new MovementProgressChecker((int) Float.POSITIVE_INFINITY);

        // avoid trying to place block right under us if there is lava
        mod.getExtraBaritoneSettings().avoidBlockPlace(avoidPlacingRiskyBlock);
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

       // if (mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().up()).getBlock().equals(Blocks.LAVA)) {
            mod.getInputControls().hold(Input.JUMP);
            mod.getInputControls().hold(Input.SPRINT);
           /* setDebugState("escaping submerged lava");
            return null;
        }*/

        Optional<Item> food = calculateFood(mod);
        if (food.isPresent() && mod.getPlayer().getHungerManager().getFoodLevel() < 20) {
            // can be interrupted by block placing, but we should try to eat whenever we can
            if (mod.getPlayer().isBlocking()) {
                mod.log("want to eat, trying to stop shielding...");
                mod.getInputControls().release(Input.CLICK_RIGHT);
            } else {
                mod.getSlotHandler().forceEquipItem(new Item[]{food.get()}, true);
                mod.getInputControls().hold(Input.CLICK_RIGHT);
            }
        }


        // Sprint through lava + jump, it's faster
        if (mod.getPlayer().isInLava() || mod.getWorld().getBlockState(mod.getPlayer().getBlockPos().down()).getBlock() == Blocks.LAVA) {

            setDebugState("run away from lava");

            BlockPos steppingPos = mod.getPlayer().getSteppingPos();
            if (!mod.getWorld().getBlockState(steppingPos.east()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.west()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.south()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.north()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.east().north()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.east().south()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.west().north()).getBlock().equals(Blocks.LAVA) ||
                    !mod.getWorld().getBlockState(steppingPos.west().south()).getBlock().equals(Blocks.LAVA)) {

                return super.onTick();
            }

            if (mod.getPlayer().isBlocking()) {
                mod.log("want to place block, trying to stop shielding...");
                mod.getInputControls().release(Input.CLICK_RIGHT);
            }

            for (float pitch = 25; pitch < 90; pitch += 1f) {
                for (float yaw = -180; yaw < 180; yaw += 1f) {
                    HitResult result = raycast(mod, 4, pitch, yaw);
                    if (result.getType() == HitResult.Type.BLOCK) {
                        BlockHitResult blockHitResult = (BlockHitResult) result;
                        BlockPos pos = blockHitResult.getBlockPos();

                        if (pos.getY() > mod.getPlayer().getSteppingPos().getY()) continue;

                        Direction facing = blockHitResult.getSide();

                        if (facing == Direction.UP) continue;
                        LookHelper.lookAt(new Rotation(yaw,pitch));

                        if (mod.getItemStorage().hasItem(Items.NETHERRACK)) {
                            mod.getSlotHandler().forceEquipItem(Items.NETHERRACK);
                        } else {
                            mod.getSlotHandler().forceEquipItem(mod.getClientBaritoneSettings().acceptableThrowawayItems.value.toArray(new Item[0]));
                        }
                        mod.log(pos+"");
                        mod.log(facing+"");


                        mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                        return null;
                    }
                }
            }

        }

        return super.onTick();
    }

    // similar to FoodChain
    // TODO add config for this
    private Optional<Item> calculateFood(AltoClef mod) {
        Item bestFood = null;
        double bestFoodScore = Double.NEGATIVE_INFINITY;
        ClientPlayerEntity player = mod.getPlayer();

        float hunger = player != null ? player.getHungerManager().getFoodLevel() : 20;
        float saturation = player != null ? player.getHungerManager().getSaturationLevel() : 20;
        // Get best food item + calculate food total
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (ItemVer.isFood(stack)) {
                //screw that, we are in lava, eat whatever we can
                // Ignore protected items
               // if (!ItemHelper.canThrowAwayStack(mod, stack)) continue;

                // Ignore spider eyes
                if (stack.getItem() == Items.SPIDER_EYE) {
                    continue;
                }

                float score = getScore(stack, hunger, saturation);
                if (score > bestFoodScore) {
                    bestFoodScore = score;
                    bestFood = stack.getItem();
                }
            }
        }

        return Optional.ofNullable(bestFood);
    }

    private static float getScore(ItemStack stack, float hunger, float saturation) {
        FoodComponentWrapper food = ItemVer.getFoodComponent(stack.getItem());

        assert food != null;
        float hungerIfEaten = Math.min(hunger + food.getHunger(), 20);
        float saturationIfEaten = Math.min(hungerIfEaten, saturation + food.getSaturationModifier());
        float gainedSaturation = (saturationIfEaten - saturation);

        float hungerNotFilled = 20 - hungerIfEaten;
        float saturationGoodScore = gainedSaturation * 10;
        float hungerNotFilledPenalty = hungerNotFilled * 2;

        float score = saturationGoodScore - hungerNotFilledPenalty;

        if (stack.getItem() == Items.ROTTEN_FLESH) {
            score = 0;
        }
        return score;
    }


    public HitResult raycast(AltoClef mod,double maxDistance, float pitch, float yaw) {
        Vec3d cameraPos = mod.getPlayer().getCameraPosVec(MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(true));
        Vec3d rotationVector = getRotationVector(pitch,yaw);

        Vec3d vec3d3 = cameraPos.add(rotationVector.x * maxDistance, rotationVector.y * maxDistance, rotationVector.z * maxDistance);
        return mod.getPlayer().getWorld()
                .raycast(
                        new RaycastContext(
                                cameraPos, vec3d3, RaycastContext.ShapeType.OUTLINE,
                                false ? RaycastContext.FluidHandling.ANY : RaycastContext.FluidHandling.NONE, mod.getPlayer()
                        )
                );
    }
    protected final Vec3d getRotationVector(float pitch, float yaw) {
        float f = pitch * (float) (Math.PI / 180.0);
        float g = -yaw * (float) (Math.PI / 180.0);
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return new Vec3d((double)(i * j), (double)(-k), (double)(h * j));
    }


    @Override
    protected void onStop(Task interruptTask) {
        AltoClef mod = AltoClef.getInstance();

        mod.getBehaviour().pop();
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.CLICK_RIGHT);

        // remove our custom place avoider
        synchronized (mod.getExtraBaritoneSettings().getPlaceMutex()) {
            mod.getExtraBaritoneSettings().getPlaceAvoiders().remove(avoidPlacingRiskyBlock);
        }
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new EscapeFromLavaGoal();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EscapeFromLavaTask;
    }

    @Override
    public boolean isFinished() {
        ClientPlayerEntity player = AltoClef.getInstance().getPlayer();

        return !player.isInLava() && !player.isOnFire();
    }

    @Override
    protected String toDebugString() {
        return "Escaping lava";
    }

    private class EscapeFromLavaGoal implements Goal {

        private static boolean isLava(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return MovementHelper.isLava(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }

        private static boolean isLavaAdjacent(int x, int y, int z) {
            return isLava(x + 1, y, z) || isLava(x - 1, y, z) || isLava(x, y, z + 1) || isLava(x, y, z - 1)
                    || isLava(x + 1, y, z - 1) || isLava(x + 1, y, z + 1) || isLava(x - 1, y, z - 1)
                    || isLava(x - 1, y, z + 1);
        }

        private static boolean isWater(int x, int y, int z) {
            if (MinecraftClient.getInstance().world == null) return false;
            return MovementHelper.isWater(MinecraftClient.getInstance().world.getBlockState(new BlockPos(x, y, z)));
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return !isLava(x, y, z) && !isLavaAdjacent(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isLava(x, y, z)) {
                return strength;
            } else if (isLavaAdjacent(x, y, z)) {
                return strength * 0.5f;
            }
            if (isWater(x, y, z)) {
                return -100;
            }
            return 0;
        }
    }
}
