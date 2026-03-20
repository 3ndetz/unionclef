package kaptainwutax.tungsten.agent;

import java.util.*;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;

import it.unimi.dsi.fastutil.floats.FloatArraySet;
import it.unimi.dsi.fastutil.floats.FloatArrays;
import it.unimi.dsi.fastutil.floats.FloatSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.mixin.AccessorEntity;
import kaptainwutax.tungsten.mixin.AccessorLivingEntity;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BubbleColumnBlock;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CauldronBlock;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.HayBlock;
import net.minecraft.block.HoneyBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.MagmaBlock;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.block.SlimeBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.TripwireBlock;
import net.minecraft.block.TurtleEggBlock;
import net.minecraft.block.WitherRoseBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import kaptainwutax.tungsten.agent.TungstenPlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;

public class Agent {

    public static Agent INSTANCE;

    public static final EntityDimensions STANDING_DIMENSIONS = EntityDimensions.changing(0.6f, 1.8f);
    public static final EntityDimensions SLEEPING_DIMENSIONS = EntityDimensions.fixed(0.2f, 0.2f);

    public static final Map<EntityPose, EntityDimensions> POSE_DIMENSIONS =
        ImmutableMap.<EntityPose, EntityDimensions>builder()
        .put(EntityPose.STANDING, STANDING_DIMENSIONS)
        .put(EntityPose.SLEEPING, SLEEPING_DIMENSIONS)
        .put(EntityPose.FALL_FLYING, EntityDimensions.changing(0.6f, 0.6f))
        .put(EntityPose.SWIMMING, EntityDimensions.changing(0.6f, 0.6f))
        .put(EntityPose.SPIN_ATTACK, EntityDimensions.changing(0.6f, 0.6f))
        .put(EntityPose.CROUCHING, EntityDimensions.changing(0.6f, 1.5f))
        .put(EntityPose.DYING, EntityDimensions.fixed(0.2f, 0.2f)).build();

    public boolean keyForward;
    public boolean keyBack;
    public boolean keyLeft;
    public boolean keyRight;
    public boolean keyJump;
    public boolean keySneak;
    public boolean keySprint;
    public AgentInput input = new AgentInput(this);

    public EntityPose pose;
    public boolean inSneakingPose;
    public boolean isDamaged = false;
    public boolean usingItem;

    public float sidewaysSpeed;
    public float upwardSpeed;
    public float forwardSpeed;
    public float yaw;
    public float pitch;

    public double posX, posY, posZ;
    public int blockX, blockY, blockZ;
    public double velX, velY, velZ;
    public double mulX, mulY, mulZ;

    public Object2DoubleMap<TagKey<Fluid>> fluidHeight = new Object2DoubleArrayMap<>(2);
    private final Set<TagKey<Fluid>> submergedFluids = new HashSet<>();
    public boolean firstUpdate = true;

    public EntityDimensions dimensions;
    public Box box;
    public float standingEyeHeight;

    public boolean onGround;
    public boolean sleeping;
    public boolean sneaking; //flag 1
    public boolean sprinting; //flag 3
    public boolean swimming; //flag 4
    public boolean fallFlying; //flag 7
    public float stepHeight = 0.6F;
    public float fallDistance;
    public boolean touchingWater;
    public boolean isSubmergedInWater;
    public boolean horizontalCollision;
    public boolean verticalCollision;
    public boolean collidedSoftly;
    public boolean slimeBounce;

    public boolean jumping;

    public int speed = -1;
    public int blindness = -1;
    public int jumpBoost = -1;
    public int slowFalling = -1;
    public int dolphinsGrace = -1;
    public int levitation = -1;

    public int depthStrider;

    public HungerManager hunger = new HungerManager();
    public double health;
    public float movementSpeed;
    public float airStrafingSpeed;
    public int jumpingCooldown;
    public int ticksToNextAutojump;
    private List<String> extra = new ArrayList<>();
    private int scannedBlocks;

    public Vec3d getPos() {
        return new Vec3d(this.posX, this.posY, this.posZ);
    }
    
    public BlockPos getBlockPos() {
        return new BlockPos(this.blockX, this.blockY, this.blockZ);
    }
    
    public BlockState getBlockState(WorldView world) {
    	return world.getBlockState(getBlockPos());
    }

    public void setPos(double x, double y, double z) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.blockX = MathHelper.floor(x);
        this.blockY = MathHelper.floor(y);
        this.blockZ = MathHelper.floor(z);
//		this.setBoundingBox(this.calculateBoundingBox());
    }
    
    public final void setBoundingBox(Box boundingBox) {
		this.box = boundingBox;
	}
    
    protected Box calculateBoundingBox() {
		return this.dimensions.getBoxAt(this.getPos());
	}

    public Agent tick(WorldView world) {
        this.tickPlayer(world);
        return this;
    }

    public void tickPlayer(WorldView world) {
        //Sleeping code
        this.updateWaterSubmersionState();
        this.tickLiving(world);
        //Hunger stuff
        //Turtle helmet
        //Item cooldown
        this.updateSize(world);
    }

    private void tickLiving(WorldView world) {
        this.baseTickLiving(world);
        //more sleep stuff
        this.tickMovementClientPlayer(world);

        if(this.sleeping) {
            this.pitch = 0.0F;
        }
    }

    private void baseTickLiving(WorldView world) {
        this.baseTickEntity(world);
        //Suffocate in walls
        //Drown in water
        //Soulspeed and frost walker
        //Update potion effects
    }

    private void baseTickEntity(WorldView world) {
        this.updateWaterState(world);
        this.updateSubmergedInWaterState(world);
        this.updateSwimming(world);

        if(this.isInLava()) {
            this.fallDistance *= 0.5F;
        }

        this.firstUpdate = false;
    }

    public boolean isSubmergedIn(TagKey<Fluid> tag) {
        return this.submergedFluids.contains(tag);
    }

    public boolean updateWaterState(WorldView world) {
        this.fluidHeight.clear();
        this.checkWaterState(world);
        double d = world.getDimension().ultrawarm() ? 0.007D : 0.0023333333333333335D;
        boolean bl = this.updateMovementInFluid(world, FluidTags.LAVA, d);
        return this.touchingWater || bl;
    }

    private void updateSubmergedInWaterState(WorldView world) {
        this.isSubmergedInWater = this.isSubmergedIn(FluidTags.WATER);
        this.submergedFluids.clear();
        double d = this.getEyeY();

        BlockPos blockPos = new BlockPos(MathHelper.floor(this.posX), MathHelper.floor(d), MathHelper.floor(this.posZ));
        FluidState fluidState = world.getFluidState(blockPos);
        double e = (float)blockPos.getY() + fluidState.getHeight(world, blockPos);

        if(e > d) {
            fluidState.streamTags().forEach(this.submergedFluids::add);
        }
    }

    public double getEyeY() {
        return this.posY + (double)this.standingEyeHeight;
    }

    public void updateSwimming(WorldView world) {
        if(this.swimming) {
            this.swimming = this.sprinting && this.touchingWater;
        } else {
            FluidState fluid = world.getFluidState(new BlockPos(this.blockX, this.blockY, this.blockZ));
            this.swimming = this.sprinting && this.isSubmergedInWater && fluid.isIn(FluidTags.WATER);
        }
    }

    public void updateWaterSubmersionState() {
        this.isSubmergedInWater = this.isSubmergedIn(FluidTags.WATER);
    }

    public void updateSize(WorldView world) {
        if(!this.wouldPoseNotCollide(world, EntityPose.SWIMMING)) return;
        EntityPose newPose;

        if(this.fallFlying) {
            newPose = EntityPose.FALL_FLYING;
        } else {
            if(this.sleeping) {
                newPose = EntityPose.SLEEPING;
            } else {
                if(this.swimming) {
                    newPose = EntityPose.SWIMMING;
                } else {
                    if(this.input.playerInput.sneak()) {
                        newPose = EntityPose.CROUCHING;
                    } else {
                        newPose = EntityPose.STANDING;
                    }
                }
            }
        }

        if(!this.wouldPoseNotCollide(world, newPose)) {
            if(this.wouldPoseNotCollide(world, EntityPose.CROUCHING)) {
                newPose = EntityPose.CROUCHING;
            } else {
                newPose = EntityPose.SWIMMING;
            }
        }

        this.setPose(world, newPose);
    }

    public void setPose(WorldView world, EntityPose pose) {
        this.pose = pose;
        this.calculateDimensions(world);
    }

    public void calculateDimensions(WorldView world) {
        EntityDimensions oldDimensions = this.dimensions;
        this.dimensions = POSE_DIMENSIONS.getOrDefault(this.pose, STANDING_DIMENSIONS);

        this.standingEyeHeight = this.getEyeHeight(this.pose, this.dimensions);

        if(this.dimensions.width() < oldDimensions.width()) {
            double d = (double)this.dimensions.width() / 2.0;
            this.box = new Box(this.posX - d, this.posY, this.posZ - d, this.posX + d,
                this.posY + (double)this.dimensions.height(), this.posZ + d);
            return;
        }

        this.box = new Box(this.box.minX, this.box.minY, this.box.minZ, this.box.minX + (double)this.dimensions.width(),
            this.box.minY + (double)this.dimensions.height(), this.box.minZ + (double)this.dimensions.width());

        if(this.dimensions.width() > oldDimensions.width() && !this.firstUpdate) {
            float f = oldDimensions.width() - this.dimensions.width();
            this.move(world, MovementType.SELF, f, 0.0D, f);
        }
    }

    public final float getEyeHeight(EntityPose pose, EntityDimensions dimensions) {
         return switch(pose) {
            case SWIMMING, FALL_FLYING, SPIN_ATTACK -> 0.4F;
            case CROUCHING -> 1.27F;
            case SLEEPING -> 0.2F;
            default -> 1.62F;
        };
    }

    public void tickMovementClientPlayer(WorldView world) {
        boolean prevSneaking = this.input.playerInput.sneak();
        boolean wasWalking = this.isWalking();

        this.inSneakingPose = !this.swimming && this.wouldPoseNotCollide(world, EntityPose.CROUCHING)
            && (this.input.playerInput.sneak() || !this.sleeping && !this.wouldPoseNotCollide(world, EntityPose.STANDING));
        this.input.tick();

        if(this.ticksToNextAutojump > 0) {
            --this.ticksToNextAutojump;
            this.input.playerInput = new TungstenPlayerInput(
            		this.input.playerInput.forward(),
            		this.input.playerInput.backward(),
            		this.input.playerInput.left(),
            		this.input.playerInput.right(),
        			true,
        			this.input.playerInput.sneak(),
        			this.input.playerInput.sprint()
        		);
        }

        double width = this.dimensions.width();
        this.pushOutOfBlocks(world, this.posX - width * 0.35D, this.posZ + width * 0.35D);
        this.pushOutOfBlocks(world, this.posX - width * 0.35D, this.posZ - width * 0.35D);
        this.pushOutOfBlocks(world, this.posX + width * 0.35D, this.posZ - width * 0.35D);
        this.pushOutOfBlocks(world, this.posX + width * 0.35D, this.posZ + width * 0.35D);


        if(this.sprinting) {
            if(this.swimming) {
            	if (this.shouldStopSwimSprinting()) {
					this.setSprinting(false);
				}
            } else if (this.shouldStopSprinting()) {
				this.setSprinting(false);
			}
        }
        
        if(this.canStartSprinting()) {
            if(this.keySprint) {
                this.setSprinting(true);
            }
        }

        if(this.touchingWater && this.input.playerInput.sneak()) {
            this.velY -= 0.04F;
        }

        this.tickMovementPlayer(world);
    }

    public void tickMovementPlayer(WorldView world) {
        this.tickMovementLiving(world);

        this.airStrafingSpeed = 0.02F;

        if(this.sprinting) {
            this.airStrafingSpeed += 0.006F;
        }

        // Vanilla recalculates movementSpeed from attributes here.
        // For per-tick validation: Agent.of(player) already captured the
        // correct value, so we don't touch it — setSprinting() handles
        // updates when sprint state changes during the tick.
        // For pathfinding: setSprinting() recalculates from base + modifiers.
    }

    public void tickMovementLiving(WorldView world) {
        if(this.jumpingCooldown > 0) {
            --this.jumpingCooldown;
        }
        
        Vec2f vec2f = this.applyMovementSpeedFactors(this.input.getMovementInput());
		this.sidewaysSpeed = vec2f.x;
		this.forwardSpeed = vec2f.y;
        this.jumping = this.input.playerInput.jump();

        
        if(Math.abs(this.velX) < 0.003) this.velX = 0.0;
        if(Math.abs(this.velY) < 0.003) this.velY = 0.0;
        if(Math.abs(this.velZ) < 0.003) this.velZ = 0.0;

        if(this.jumping) {
            double k = this.isInLava() ? this.getFluidHeight(FluidTags.LAVA) : this.getFluidHeight(FluidTags.WATER);
            boolean bl = this.touchingWater && k > 0.0;
            double l = (double)this.standingEyeHeight < 0.4D ? 0.0D : 0.4D;

            if(bl && (!this.onGround || k > l)) {
                this.velY += 0.04F;
            } else if(!this.isInLava() || this.onGround && !(k > l)) {
                if((this.onGround || bl) && this.jumpingCooldown == 0) {
                    this.jump(world);
                    this.jumpingCooldown = 10;
                }
            } else {
                this.velY += 0.04F;
            }
        } else {
            this.jumpingCooldown = 0;
        }
        


        this.travelPlayer(world);

        /* Entity pushing
        if(this.riptideTicks > 0) {
            --this.riptideTicks;
            this.tickRiptide(box, this.getBoundingBox());
        }
        this.tickCramming();*/
    }

    public void jump(WorldView world) {
        float newY = this.getJumpVelocity(world);

        if(this.jumpBoost >= 0) {
            newY += 0.1F * (float)(this.jumpBoost + 1);
        }

        this.velY = newY;

        if(this.sprinting) {
            float g = this.yaw * (float)(Math.PI / 180);
            this.velX += (double)(-MathHelper.sin(g)) * 0.2;
            this.velZ +=  (double)MathHelper.cos(g) * 0.2;
        }
    }

    public float getJumpVelocity(WorldView world) {
        return 0.42F * this.getJumpVelocityMultiplier(world);
    }

    public float getJumpVelocityMultiplier(WorldView world) {
        BlockPos pos1 = new BlockPos(this.blockX, this.blockY, this.blockZ);
        BlockPos pos2 = new BlockPos(this.blockX, MathHelper.floor(this.box.minY - 0.500001D), this.blockZ);
        float f = world.getBlockState(pos1).getBlock().getJumpVelocityMultiplier();
        float g = world.getBlockState(pos2).getBlock().getJumpVelocityMultiplier();
        return (double)f == 1.0D ? g : f;
    }

    public void updateVelocity(float speed) {
        double squaredMagnitude = (double)this.sidewaysSpeed * (double)this.sidewaysSpeed
                                + (double)this.upwardSpeed * (double)this.upwardSpeed
                                + (double)this.forwardSpeed * (double)this.forwardSpeed;

        if (squaredMagnitude < 1.0E-7) return;

        double sideways = this.sidewaysSpeed, upward = this.upwardSpeed, forward = this.forwardSpeed;

        if(squaredMagnitude > 1.0D) {
            double magnitude = Math.sqrt(squaredMagnitude);
            if (magnitude < 1.0E-4) { return; }
            else { sideways /= magnitude; upward /= magnitude; forward /= magnitude; }
        }

        sideways *= speed; upward *= speed; forward *= speed;
        float f = MathHelper.sin(yaw * (float) (Math.PI / 180.0));
		float g = MathHelper.cos(yaw * (float) (Math.PI / 180.0));

        this.velX += sideways * (double)g - forward * (double)f;
        this.velY += upward;
        this.velZ += forward * (double)g + sideways * (double)f;
    }

    public void travelPlayer(WorldView world) {
        if(this.swimming) {
            float g = -MathHelper.sin(this.pitch * ((float)Math.PI / 180));
            double h = g < -0.2 ? 0.085 : 0.06;

            BlockPos pos = new BlockPos(this.blockX, MathHelper.floor(this.posY + 1.0D - 0.1D), this.blockZ);

            if(g <= 0.0D || this.jumping || !world.getBlockState(pos).getFluidState().isEmpty()) {
                this.velY += (g - this.velY) * h;
            }
        }

        this.travelLiving(world);
    }

    public void travelLiving(WorldView world) {
        boolean falling = this.velY <= 0.0D;
        double fallSpeed = 0.08D;

        if(falling && this.slowFalling >= 0) {
            fallSpeed = 0.01D;
            this.fallDistance = 0.0F;
        }

        if(this.touchingWater) {
            double startY = this.posY;
            float swimSpeed = this.sprinting ? 0.9F : 0.8F;
            float speed = 0.02F;

            float h = this.depthStrider;
            if(h > 3.0F) h = 3.0F;
            if(!this.onGround) h *= 0.5F;

            if(h > 0.0F) {
                swimSpeed += (0.54600006F - swimSpeed) * h / 3.0F;
                speed += (this.movementSpeed - speed) * h / 3.0F;
            }

            if(this.dolphinsGrace >= 0) {
                swimSpeed = 0.96F;
            }

            this.updateVelocity(speed);
            this.move(world, MovementType.SELF, this.velX, this.velY, this.velZ);

            if(this.horizontalCollision && this.isClimbing(world)) { this.velY = 0.2D; }
            this.velX *= swimSpeed; this.velY *= 0.8F; this.velZ *= swimSpeed;

            this.method_26317(fallSpeed, falling);

            boolean moveNoCollisions = this.doesNotCollide(world, this.velX, this.velY + (double)0.6F - this.posY + startY, this.velZ);

            if(this.horizontalCollision && moveNoCollisions) {
                this.velY = 0.3F;
            }
        } else if(this.isInLava()) {
            double startY = this.posY;
            this.updateVelocity(0.02F);

            this.move(world, MovementType.SELF, this.velX, this.velY, this.velZ);

            if(this.getFluidHeight(FluidTags.LAVA) <= ((double)this.standingEyeHeight < 0.4D ? 0.0D : 0.4D)) {
                this.velX *= 0.5D; this.velY *= 0.8F; this.velZ *= 0.5D;
                this.method_26317(fallSpeed, falling);
            } else {
                this.velX *= 0.5D; this.velY *= 0.5D; this.velZ *= 0.5D;
            }

            this.velY -= fallSpeed / 4.0D;

            boolean moveNoCollisions = this.doesNotCollide(world, this.velX, this.velY + (double)0.6F - this.posY + startY, this.velZ);

            if(this.horizontalCollision && moveNoCollisions) {
                this.velY = 0.3F;
            }
        } else if(this.fallFlying) {
            //No elytra controls
            if(this.velY > -0.5D) {
                this.fallDistance = 1.0F;
            }

            float cYaw = MathHelper.cos(-this.yaw * 0.017453292F);
            float sYaw = MathHelper.sin(-this.yaw * 0.017453292F);
            float cPitch = MathHelper.cos(this.pitch * 0.017453292F);
            float sPitch = MathHelper.sin(this.pitch * 0.017453292F);
            double facingX = sYaw * cPitch;
            double facingY = -sPitch;
            double facingZ = cYaw * cPitch;

            double facingHM = Math.sqrt(facingX * facingX + facingZ * facingZ);
            double velHM = Math.sqrt(this.velX * this.velX + this.velZ * this.velZ);

            float cPitchSq = (float)((double)cPitch * (double)cPitch);
            this.velY += fallSpeed * (-1.0D + (double)cPitchSq * 0.75D);

            if(this.velY < 0.0D && facingHM > 0.0D) {
                double q = this.velY * -0.1D * (double)cPitchSq;
                this.velX += facingX * q / facingHM;
                this.velY += q;
                this.velZ += facingZ * q / facingHM;
            }

            if(this.pitch < 0.0F && facingHM > 0.0D) {
                double q = velHM * facingY * 0.04D;
                this.velX -= facingX * q / facingHM;
                this.velY += q * 3.2D;
                this.velZ -= facingZ * q / facingHM;
            }

            if(facingHM > 0.0D) {
                this.velX += (facingX / facingHM * velHM - this.velX) * 0.1D;
                this.velZ += (facingZ / facingHM * velHM - this.velZ) * 0.1D;
            }

            this.velX *= 0.9900000095367432D;
            this.velY *= 0.9800000190734863D;
            this.velZ *= 0.9900000095367432D;
            this.move(world, MovementType.SELF, this.velX, this.velY, this.velZ);

            //Mojang why? WHYYYYYYYYYYYYYYY???
            if(this.onGround /*&& !world.isClient*/) {
                this.fallFlying = false;
            }
        } else {
            BlockPos pos = this.getVelocityAffectingPos(world);
            float slipperiness = world.getBlockState(pos).getBlock().getSlipperiness();
            float xzDrag = this.onGround ? slipperiness * 0.91F : 0.91F;
            double ajuVelY = this.applyMovementInput(world, slipperiness);

            if (this.levitation >= 0) {
                ajuVelY += (0.05D * (double)(this.levitation + 1) - ajuVelY) * 0.2D;
                this.fallDistance = 0.0F;
            } else {
                ajuVelY -= fallSpeed;
            }

            this.velX *= xzDrag;
            this.velY = ajuVelY * (double)0.98F;
            this.velZ *= xzDrag;
        }
    }

    public double applyMovementInput(WorldView world, float f) {
        this.updateVelocity(this.getMovementSpeed(f));
        this.applyClimbingSpeed(world);

        this.move(world, MovementType.SELF, this.velX, this.velY, this.velZ);

        if((this.horizontalCollision || this.jumping) && this.isClimbing(world)) {
            return 0.2D;
        }

        return this.velY;
    }

    private float getMovementSpeed(float slipperiness) {
        if(this.onGround) {
            return this.movementSpeed * (0.21600002F / (slipperiness * slipperiness * slipperiness));
        }	

        return this.sprinting ? 0.025999999F : 0.02F;
    }

    private void applyClimbingSpeed(WorldView world) {
        if(this.isClimbing(world)) {
            this.fallDistance = 0.0f;
            this.velX = MathHelper.clamp(this.velX, -0.15000000596046448D, 0.15000000596046448D);
            this.velY = Math.max(this.velY, -0.15000000596046448D);
            this.velZ = MathHelper.clamp(this.velZ, -0.15000000596046448D, 0.15000000596046448D);

            BlockState state = world.getBlockState(new BlockPos(this.blockX, this.blockY, this.blockZ));

            if(this.velY < 0.0D && !state.isOf(Blocks.SCAFFOLDING) && this.input.playerInput.sneak()) {
                this.velY = 0.0D;
            }
        }
    }

    public Iterable<VoxelShape> getBlockCollisions(WorldView world, Box box) {
        return () -> new AgentBlockCollisions(world, this, box);
    }

    public boolean isSpaceEmpty(WorldView world, Box box) {
        for(VoxelShape voxelShape : this.getBlockCollisions(world, box)) {
            if(!voxelShape.isEmpty()) {
                return false;
            }
        }

        return world.getEntityCollisions(null, box).isEmpty();
    }

    private boolean doesNotCollide(WorldView world, double offsetX, double offsetY, double offsetZ) {
        Box box = this.box.offset(offsetX, offsetY, offsetZ);
        return this.isSpaceEmpty(world, box) && !world.containsFluid(box);
    }

    public void method_26317(double fallSpeed, boolean falling) {
        if(!this.sprinting) {
            boolean b = falling && Math.abs(this.velY - 0.005D) >= 0.003D && Math.abs(this.velY - fallSpeed / 16.0D) < 0.003D;
            this.velY = b ? -0.003D : this.velY - fallSpeed / 16.0D;
        }
    }

    public void move(WorldView world, MovementType type, double movX, double movY, double movZ) {
        //if(type == MovementType.PISTON && (movement = this.adjustMovementForPiston(movement)).equals(Vec3d.ZERO)) {
        //    return;
        //}

        if(this.mulX * this.mulX + this.mulY * this.mulY + this.mulZ * this.mulZ > 0.0000001D) {
            movX *= this.mulX; movY *= this.mulY; movZ *= this.mulZ;
            this.mulX = 0; this.mulY = 0; this.mulZ = 0;
            this.velX = 0; this.velY = 0; this.velZ = 0;
        }


        Vec3d vec1 = this.adjustMovementForSneaking(world, type, new Vec3d(movX, movY, movZ));
        movX = vec1.x; movY = vec1.y; movZ = vec1.z;

        Vec3d vec2 = this.adjustMovementForCollisions(world, new Vec3d(movX, movY, movZ));
        double ajuX = vec2.x, ajuY = vec2.y, ajuZ = vec2.z;

        double magnitudeSq = ajuX * ajuX + ajuY * ajuY + ajuZ * ajuZ;

		if(magnitudeSq > 0.0000001D) {
            if(this.fallDistance != 0.0F && magnitudeSq >= 1.0D) {
                RaycastContext context = new AgentRaycastContext(this.getPos(), this.getPos().add(new Vec3d(ajuX, ajuY, ajuZ)),
                    RaycastContext.ShapeType.FALLDAMAGE_RESETTING, RaycastContext.FluidHandling.WATER, this);
                BlockHitResult result = world.raycast(context);

                if(result.getType() != HitResult.Type.MISS) {
                    this.fallDistance = 0.0F;
                }
            }

            this.setPos(this.posX + ajuX, this.posY + ajuY, this.posZ + ajuZ);
			this.box = this.dimensions.getBoxAt(this.posX, this.posY, this.posZ);
        }

        boolean xSimilar = !MathHelper.approximatelyEquals(movX, ajuX);
        boolean zSimilar = !MathHelper.approximatelyEquals(movZ, ajuZ);
        this.horizontalCollision = xSimilar || zSimilar;
        this.verticalCollision = movY != ajuY;
        this.collidedSoftly = this.horizontalCollision && this.hasCollidedSoftly(ajuX, ajuY, ajuZ);
        this.onGround = this.verticalCollision && movY < 0.0D;

        BlockPos landingPos = this.getLandingPos(world);
        BlockState landingState = world.getBlockState(landingPos);

        this.fall(world, ajuY, landingState);

        if(this.horizontalCollision) {
            if(xSimilar) this.velX = 0.0D;
            if(zSimilar) this.velZ = 0.0D;
        }

        Block block = landingState.getBlock();

        if(movY != ajuY) {
            if(block instanceof SlimeBlock && !this.input.playerInput.sneak()) {
                if(this.velY < 0.0D) {
                	this.velY *= -1;
                	this.slimeBounce = true;
                }
            } else if(block instanceof BedBlock) {
                if(this.velY < 0.0D) this.velY *= -1 * (double)0.66F;
            } else {
                this.velY = 0;
            }
        }

        if(this.onGround && !this.input.playerInput.sneak()) {
            if(block instanceof MagmaBlock) {
                //damage the entity
            } else if(block instanceof SlimeBlock) {
                double d = Math.abs(this.velY);

                if(d < 0.1D) {
                    this.velX *= 0.4D + d * 0.2D;
                    this.velZ *= 0.4D + d * 0.2D;
                	this.slimeBounce = true;
                }
            } else if(block instanceof TurtleEggBlock) {
                //eggs can break (1/100)
            }
        }

        this.checkBlockCollision(world);

        float i = this.getVelocityMultiplier(world);
        this.velX *= i; this.velZ *= i;

		/*
		if (this.world.method_29556(this.getBoundingBox().contract(0.001))
		.noneMatch(blockState -> blockState.isIn(BlockTags.FIRE) || blockState.isOf(Blocks.LAVA)) && this.fireTicks <= 0) {
			this.setFireTicks(-this.getBurningDuration());
		}*/
    }

    private boolean hasCollidedSoftly(double ajuX, double ajuY, double ajuZ) {
        float f = this.yaw * ((float)Math.PI / 180);
        double d = MathHelper.sin(f);
        double e = MathHelper.cos(f);
        double g = (double)this.sidewaysSpeed * e - (double)this.forwardSpeed * d;
        double h = (double)this.forwardSpeed * e + (double)this.sidewaysSpeed * d;
        double i = MathHelper.square(g) + MathHelper.square(h);
        double j = MathHelper.square(ajuX) + MathHelper.square(ajuZ);

        if(i < (double)1.0E-5F || j < (double)1.0E-5F) {
            return false;
        }

        double k = g * ajuX + h * ajuZ;
        double l = Math.acos(k / Math.sqrt(i * j));
        return l < 0.13962633907794952D;
    }

    public Vec3d adjustMovementForSneaking(WorldView world, MovementType type, Vec3d movement) {
        if(this.input.playerInput.sneak() && (type == MovementType.SELF || type == MovementType.PLAYER)
            && (this.onGround || this.fallDistance < this.stepHeight
            && !this.isSpaceEmpty(world, this.box.offset(0.0, this.fallDistance - this.stepHeight, 0.0)))) {
            double d = movement.x;
            double e = movement.z;

            while(d != 0.0 && this.isSpaceEmpty(world, this.box.offset(d, -this.stepHeight, 0.0))) {
                if(d < 0.05 && d >= -0.05) { d = 0.0; continue; }
                if(d > 0.0) { d -= 0.05; continue; }
                d += 0.05;
            }

            while(e != 0.0 && this.isSpaceEmpty(world, this.box.offset(0.0, -this.stepHeight, e))) {
                if(e < 0.05 && e >= -0.05) { e = 0.0; continue; }
                if(e > 0.0) { e -= 0.05; continue; }
                e += 0.05;
            }

            while(d != 0.0 && e != 0.0 && this.isSpaceEmpty(world, this.box.offset(d, -this.stepHeight, e))) {
                d = d < 0.05 && d >= -0.05 ? 0.0 : (d > 0.0 ? (d -= 0.05) : (d += 0.05));
                if(e < 0.05 && e >= -0.05) { e = 0.0; continue; }
                if(e > 0.0) { e -= 0.05; continue; }
                e += 0.05;
            }

            movement = new Vec3d(d, movement.y, e);
        }

        return movement;
    }

    private Vec3d adjustMovementForCollisions(WorldView world, Vec3d movement) {
        Box box = this.box;
        List<VoxelShape> list = world.getEntityCollisions(null, box.stretch(movement));
        Vec3d vec3d = movement.lengthSquared() == 0.0 ? movement : this.adjustMovementForCollisions(movement, box, world, list);
        boolean bl = movement.x != vec3d.x;
        boolean bl2 = movement.y != vec3d.y;
        boolean bl3 = movement.z != vec3d.z;
		boolean bl4 = bl2 && movement.y < 0.0;
        boolean bl5 = this.onGround || bl4;

        if(this.stepHeight > 0.0f && bl5 && (bl || bl3)) {
//            Vec3d vec3d2 = this.adjustMovementForCollisions(new Vec3d(movement.x, this.stepHeight, movement.z), box, world, list);
//            Vec3d vec3d3 = this.adjustMovementForCollisions(new Vec3d(0.0, this.stepHeight, 0.0), box.stretch(movement.x, 0.0, movement.z), world, list);
//            Vec3d vec3d4 = this.adjustMovementForCollisions(new Vec3d(movement.x, 0.0, movement.z), box.offset(vec3d3), world, list).add(vec3d3);
//
//            if(vec3d3.y < (double)this.stepHeight && vec3d4.horizontalLengthSquared() > vec3d2.horizontalLengthSquared()) {
//                vec3d2 = vec3d4;
//            }
//
//            if(vec3d2.horizontalLengthSquared() > vec3d.horizontalLengthSquared()) {
//                return vec3d2.add(this.adjustMovementForCollisions(new Vec3d(0.0, -vec3d2.y + movement.y, 0.0), box.offset(vec3d2), world, list));
//            }
        	Box box2 = bl4 ? box.offset(0.0, vec3d.y, 0.0) : box;
			Box box3 = box2.stretch(movement.x, (double)this.stepHeight, movement.z);
			if (!bl4) {
				box3 = box3.stretch(0.0, -1.0E-5F, 0.0);
			}

			List<VoxelShape> list2 = this.findCollisionsForMovement(world, list, box3);
			float f = (float)vec3d.y;
			float[] fs = collectStepHeights(box2, list2, this.stepHeight, f);

			for (float g : fs) {
				Vec3d vec3d2 = adjustMovementForCollisions(new Vec3d(movement.x, (double)g, movement.z), box2, list2);
				if (vec3d2.horizontalLengthSquared() > vec3d.horizontalLengthSquared()) {
					double d = box.minY - box2.minY;
					return vec3d2.add(0.0, -d, 0.0);
				}
			}
        }

        return vec3d;
    }
    
    private List<VoxelShape> findCollisionsForMovement(WorldView world, List<VoxelShape> regularCollisions, Box movingEntityBoundingBox
    	) {
    		Builder<VoxelShape> builder = ImmutableList.builderWithExpectedSize(regularCollisions.size() + 1);
    		if (!regularCollisions.isEmpty()) {
    			builder.addAll(regularCollisions);
    		}

    		builder.addAll(this.getBlockCollisions(world, movingEntityBoundingBox));
    		return builder.build();
    	}
    
    private static float[] collectStepHeights(Box collisionBox, List<VoxelShape> collisions, float f, float stepHeight) {
		FloatSet floatSet = new FloatArraySet(4);

		for (VoxelShape voxelShape : collisions) {
			for (double d : voxelShape.getPointPositions(Axis.Y)) {
				float g = (float)(d - collisionBox.minY);
				if (!(g < 0.0F) && g != stepHeight) {
					if (g > f) {
						break;
					}

					floatSet.add(g);
				}
			}
		}

		float[] fs = floatSet.toFloatArray();
		FloatArrays.unstableSort(fs);
		return fs;
	}

    public Vec3d adjustMovementForCollisions(Vec3d movement, Box entityBoundingBox, WorldView world, List<VoxelShape> entityCollisions) {
        ImmutableList.Builder<VoxelShape> builder = ImmutableList.builderWithExpectedSize(entityCollisions.size() + 1);

        if(!entityCollisions.isEmpty()) {
            builder.addAll(entityCollisions);
        }

        builder.addAll(this.getBlockCollisions(world, entityBoundingBox.stretch(movement)));
        return this.adjustMovementForCollisions(movement, entityBoundingBox, builder.build());
    }

    private Vec3d adjustMovementForCollisions(Vec3d movement, Box entityBoundingBox, List<VoxelShape> collisions) {
        if(collisions.isEmpty()) {
            return movement;
        }

        double d = movement.x;
        double e = movement.y;
        double f = movement.z;
        boolean bl = Math.abs(d) < Math.abs(f);

        if(e != 0.0D) {
            e = VoxelShapes.calculateMaxOffset(Direction.Axis.Y, entityBoundingBox, collisions, e);
            if (e != 0.0D) entityBoundingBox = entityBoundingBox.offset(0.0D, e, 0.0D);
        }

        if(bl && f != 0.0D) {
            f = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, entityBoundingBox, collisions, f);
            if(f != 0.0D) entityBoundingBox = entityBoundingBox.offset(0.0D, 0.0D, f);
        }

        if(d != 0.0D) {
            d = VoxelShapes.calculateMaxOffset(Direction.Axis.X, entityBoundingBox, collisions, d);
            if(!bl && d != 0.0D) entityBoundingBox = entityBoundingBox.offset(d, 0.0D, 0.0D);
        }

        if (!bl && f != 0.0D) {
            f = VoxelShapes.calculateMaxOffset(Direction.Axis.Z, entityBoundingBox, collisions, f);
        }

        return new Vec3d(d, e, f);
    }

    public boolean isClimbing(WorldView world) {
        BlockState state = world.getBlockState(new BlockPos(this.blockX, this.blockY, this.blockZ));
        if(state.isIn(BlockTags.CLIMBABLE)) return true;
        if(state.getBlock() instanceof TrapdoorBlock && this.canEnterTrapdoor(world, state)) return true;
        return false;
    }

    private boolean canEnterTrapdoor(WorldView world, BlockState trapdoor) {
        if(!trapdoor.get(TrapdoorBlock.OPEN)) return false;

        BlockState ladder = world.getBlockState(new BlockPos(this.blockX, this.blockY - 1, this.blockZ));

        if(ladder.isOf(Blocks.LADDER) && ladder.get(LadderBlock.FACING) == trapdoor.get(TrapdoorBlock.FACING)) {
            return true;
        }

        return false;
    }

    void checkWaterState(WorldView world) {
        if(this.updateMovementInFluid(world, FluidTags.WATER, 0.014D)) {
            this.fallDistance = 0.0F;
            this.touchingWater = true;
        } else {
            this.touchingWater = false;
        }
    }

    public boolean updateMovementInFluid(WorldView world, TagKey<Fluid> tag, double d) {
        int n;
        Box box = this.box.contract(0.001D);
        int i = MathHelper.floor(box.minX);
        int j = MathHelper.ceil(box.maxX);
        int k = MathHelper.floor(box.minY);
        int l = MathHelper.ceil(box.maxY);
        int m = MathHelper.floor(box.minZ);

        if (!world.isRegionLoaded(i, k, m, j, l, n = MathHelper.ceil(box.maxZ))) {
            return false;
        }

        double e = 0.0;
        boolean bl2 = false;
        Vec3d vec3d = Vec3d.ZERO;
        int o = 0;
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for(int p = i; p < j; ++p) {
            for(int q = k; q < l; ++q) {
                for(int r = m; r < n; ++r) {
                    double f;
                    mutable.set(p, q, r);
                    FluidState fluidState = world.getFluidState(mutable);
                    if (!fluidState.isIn(tag) || !((f = (float)q + fluidState.getHeight(world, mutable)) >= box.minY)) continue;
                    bl2 = true;
                    e = Math.max(f - box.minY, e);

                    Vec3d vec3d2 = fluidState.getVelocity(world, mutable);

                    if(e < 0.4) {
                        vec3d2 = vec3d2.multiply(e);
                    }

                    vec3d = vec3d.add(vec3d2);
                    ++o;
                }
            }
        }

        if(vec3d.length() > 0.0) {
            if(o > 0) {
                vec3d = vec3d.multiply(1.0 / (double)o);
            }

            Vec3d vec3d3 = new Vec3d(this.velX, this.velY, this.velZ);
            vec3d = vec3d.multiply(d);

            if(Math.abs(vec3d3.x) < 0.003 && Math.abs(vec3d3.z) < 0.003 && vec3d.length() < 0.0045000000000000005) {
                vec3d = vec3d.normalize().multiply(0.0045000000000000005);
            }

            this.velX += vec3d.x;
            this.velY += vec3d.y;
            this.velZ += vec3d.z;
        }

        this.fluidHeight.put(tag, e);
        return bl2;
    }

    public void fall(WorldView world, double heightDifference, BlockState landedState) {
        if(!this.touchingWater) {
            this.checkWaterState(world);
        }

        //add soulspeed movement boost

        if(this.onGround) {
            if(this.fallDistance > 0.0F) {
                if(landedState.getBlock() instanceof BedBlock) {
                    this.handleFallDamage(this.fallDistance * 0.6F, 1.0F);
                } else if(landedState.getBlock() instanceof FarmlandBlock) {
                    //grief
                } else if(landedState.getBlock() instanceof HayBlock) {
                    this.handleFallDamage(this.fallDistance, 0.2F);
                } else if(landedState.getBlock() instanceof SlimeBlock) {
                    this.handleFallDamage(this.fallDistance, this.input.playerInput.sneak() ? 1.0F : 0.0F);
                } else if(landedState.getBlock() instanceof TurtleEggBlock) {
                    //eggs can break (1/3)
                    this.handleFallDamage(this.fallDistance, 1.0F);
                } else {
                    this.handleFallDamage(this.fallDistance, 1.0F);
                }
            }

            this.fallDistance = 0.0F;
        } else if(heightDifference < 0.0D) {
            this.fallDistance -= (float)heightDifference;
        }

        if(this.touchingWater) {
            this.fallDistance = 0F;
        }
    }

    public boolean handleFallDamage(double fallDistance, float damageMultiplier) {
        int i = this.computeFallDamage(fallDistance, damageMultiplier);

        if(i > 0) {
            //this.damage(DamageSource.FALL, i);
        	this.isDamaged = true; 
        	this.velX = 0;
        	this.velZ = 0;
            return true;
        }

        return false;
    }

    public int computeFallDamage(double fallDistance, float damageMultiplier) {
    	if (TungstenModDataContainer.player == null) return 0;
    	if (TungstenModDataContainer.player.getType().isIn(EntityTypeTags.FALL_DAMAGE_IMMUNE)) {
    		return 0;
    	}
        float f = this.jumpBoost < 0 ? 0.0F : (float)(this.jumpBoost + 1);
        return MathHelper.ceil((fallDistance - 3.0f - f) * damageMultiplier);
    }

    public void checkBlockCollision(WorldView world) {
    	double minOffset = 0.001; // default 0.001
    	double maxOffset = 0.001; // default 0.001
        BlockPos blockPos = new BlockPos(MathHelper.floor(this.box.minX + minOffset), MathHelper.floor(this.box.minY + minOffset), MathHelper.floor(this.box.minZ + minOffset));
        BlockPos blockPos2 = new BlockPos(MathHelper.floor(this.box.maxX - maxOffset), MathHelper.floor(this.box.maxY - maxOffset), MathHelper.floor(this.box.maxZ - maxOffset));
        BlockPos.Mutable pos = new BlockPos.Mutable();

        if(world.isRegionLoaded(blockPos, blockPos2)) {
            for(int i = blockPos.getX(); i <= blockPos2.getX(); ++i) {
                for(int j = blockPos.getY(); j <= blockPos2.getY(); ++j) {
                    for(int k = blockPos.getZ(); k <= blockPos2.getZ(); ++k) {
                        pos.set(i, j, k);
                        BlockState state = world.getBlockState(pos);

                        if(state.getBlock() instanceof AbstractFireBlock) {
                            //damage the entity
                        } else if(state.getBlock() instanceof AbstractPressurePlateBlock) {
                            //change block state
                        } else if(state.getBlock() instanceof BubbleColumnBlock) {
                            BlockState surface = world.getBlockState(pos.up());
                            boolean drag = surface.contains(BubbleColumnBlock.DRAG) && surface.get(BubbleColumnBlock.DRAG);

                            if(surface.isAir()) {
                                this.velY = drag ? Math.max(-0.9D, this.velY - 0.03D) : Math.min(1.8D, this.velY + 0.1D);
                            } else {
                                this.velY = drag ? Math.max(-0.3D, this.velY - 0.03D) : Math.min(0.7D, this.velY + 0.06D);
                                this.fallDistance = 0.0F;
                            }
                        } else if(state.getBlock() instanceof CactusBlock) {
                            //damage the entity
                        } else if(state.getBlock() instanceof CampfireBlock) {
                            //damage the entity
                        } else if(state.getBlock() instanceof CampfireBlock) {
                            //damage the entity
                        } else if(state.getBlock() instanceof CauldronBlock) {
                            //extinguish the entity
                        } else if(state.getBlock() instanceof CobwebBlock) {
                            this.fallDistance = 0.0F;
                            this.mulX = 0.25D; this.mulY = 0.05F; this.mulZ = 0.25D;
                        } else if(state.getBlock() instanceof EndPortalBlock) {
                            //fuck
                        } else if(state.getBlock() instanceof HoneyBlock) {
                            if(this.isSliding(pos)) {
                                if(this.velY < -0.13D) {
                                    double m = -0.05D / this.velY;
                                    this.velX *= m; this.velY = -0.05D; this.velZ *= m;
                                } else {
                                    this.velY = -0.05D;
                                }

                                this.fallDistance = 0.0F;
                            }
                        } else if(state.getBlock() instanceof NetherPortalBlock) {
                            //eh?
                        } else if(state.getBlock() instanceof SweetBerryBushBlock) {
                            this.mulX = 0.8f; this.mulY = 0.75D; this.mulZ = 0.8F;
                            //damage the entity
                        } else if(state.getBlock() instanceof TripwireBlock) {
                            //change block state
                        } else if(state.getBlock() instanceof WitherRoseBlock) {
                            //damage the entity
                        }
                    }
                }
            }
        }
    }

    private boolean isSliding(BlockPos pos) {
        if(this.onGround) return false;
        if(this.posY > (double)pos.getY() + 0.9375D - 1.0E-07D) return false;
        if(this.velY >= -0.08D) return false;

        double d = Math.abs((double)pos.getX() + 0.5D - this.posX);
        double e = Math.abs((double)pos.getZ() + 0.5D - this.posZ);
        double f = 0.4375D + (double)(this.dimensions.width() / 2.0F);
        return d + 1.0E-7D > f || e + 1.0E-7D > f;
    }

    public float getVelocityMultiplier(WorldView world) {
    	BlockState blockState = world.getBlockState(new BlockPos(this.blockX, this.blockY, this.blockZ));
		float f = blockState.getBlock().getVelocityMultiplier();
		if (!blockState.isOf(Blocks.WATER) && !blockState.isOf(Blocks.BUBBLE_COLUMN)) {
			return (double)f == 1.0 ? world.getBlockState(this.getLandingPos(world)).getBlock().getVelocityMultiplier() : f;
		} else {
			return f;
		}
    }
    
    protected static Vec3d movementInputToVelocity(Vec3d movementInput, float speed, float yaw) {
		double d = movementInput.lengthSquared();
		if (d < 1.0E-7) {
			return Vec3d.ZERO;
		} else {
			Vec3d vec3d = (d > 1.0 ? movementInput.normalize() : movementInput).multiply(speed);
			float f = MathHelper.sin(yaw * (float) (Math.PI / 180.0));
			float g = MathHelper.cos(yaw * (float) (Math.PI / 180.0));
			return new Vec3d(vec3d.x * g - vec3d.z * f, vec3d.y, vec3d.z * g + vec3d.x * f);
		}
	}

    /**
     * Matches vanilla Entity.getPosWithYOffset(0.500001f) logic.
     * Vanilla uses cached supportingBlockPos with fence/wall special case.
     * We don't cache, but we replicate the fence/wall/gate check so that
     * friction is calculated from the correct block when standing on fences.
     */
    private static boolean isFenceLike(BlockState state) {
        return state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS)
                || state.getBlock() instanceof FenceGateBlock;
    }

    public BlockPos getVelocityAffectingPos(WorldView world) {
        BlockPos pos = new BlockPos(MathHelper.floor(this.posX),
            MathHelper.floor(this.posY - 0.5000001D), MathHelper.floor(this.posZ));
        // Only do the fence check when on ground — in air, friction doesn't
        // matter and we save 2 getBlockState calls per tick per node.
        if (!this.onGround) return pos;
        BlockState state = world.getBlockState(pos);
        if (isFenceLike(state)) return pos;
        BlockPos below = pos.down();
        if (isFenceLike(world.getBlockState(below))) return below;
        return pos;
    }

    public BlockPos getLandingPos(WorldView world) {
        BlockPos pos = new BlockPos(this.blockX, MathHelper.floor(this.posY - (double)0.2F), this.blockZ);
        
        if(!world.getBlockState(pos).isAir()) {
            return pos;
        }

        BlockState state = world.getBlockState(pos.down());

        if(state.getBlock() instanceof FenceGateBlock || state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS)) {
            return pos.down();
        }

        return pos;
    }
    
    public boolean canSprint() {
		return this.hunger.getFoodLevel() > 6.0F;
	}
    
    public boolean shouldSlowDown() {
		return this.sneaking || this.keySneak;
	}
    

	private Vec2f applyMovementSpeedFactors(Vec2f input) {
		if (input.lengthSquared() == 0.0F) {
			return input;
		} else {
			Vec2f vec2f = input.multiply(0.98F);
			if (this.usingItem) {
				vec2f = vec2f.multiply(0.2F);
			}

			if (this.shouldSlowDown()) {
				float f = 0.3F;
				vec2f = vec2f.multiply(f);
			}
			
			// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
			// !! VERSIONING: applyDirectionalMovementSpeedFactors (diagonal  !!
			// !! movement normalization, MC-271065) was added in MC 1.21.4+. !!
			// !! In 1.21.1 and below, diagonal input is NOT normalized —     !!
			// !! enabling this causes simulation drift on diagonal movement.  !!
			// !! When tungsten gets preprocessor support, gate this with:     !!
			// !!   //#if MC >= 12104                                          !!
			// !!   vec2f = applyDirectionalMovementSpeedFactors(vec2f);       !!
			// !!   //#endif                                                   !!
			// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
			// vec2f = applyDirectionalMovementSpeedFactors(vec2f);

			return vec2f;
		}
	}

	private static Vec2f applyDirectionalMovementSpeedFactors(Vec2f vec) {
		float f = vec.length();
		if (f <= 0.0F) {
			return vec;
		} else {
			Vec2f vec2f = vec.multiply(1.0F / f);
			float g = getDirectionalMovementSpeedMultiplier(vec2f);
			float h = Math.min(f * g, 1.0F);
			return vec2f.multiply(h);
		}
	}

	private static float getDirectionalMovementSpeedMultiplier(Vec2f vec) {
		float f = Math.abs(vec.x);
		float g = Math.abs(vec.y);
		float h = g > f ? f / g : g / f;
		return MathHelper.sqrt(1.0F + MathHelper.square(h));
	}
    
    private boolean canStartSprinting() {
		return Math.abs(this.forwardSpeed) > -0.1
			&& this.canSprint()
			&& this.keyForward
			&& !this.horizontalCollision
			&& !this.usingItem
			&& this.blindness < 0
			&& (!this.shouldSlowDown() || this.isSubmergedInWater)
			&& (!this.touchingWater || this.isSubmergedInWater);
	}

	private boolean shouldStopSprinting() {
		
		return this.forwardSpeed == 0
			|| !this.keyForward
			|| !this.canSprint()
			|| this.horizontalCollision && !this.collidedSoftly
			|| this.touchingWater && !this.isSubmergedInWater;
	}

	private boolean shouldStopSwimSprinting() {
		return !this.touchingWater
			|| !this.input.hasForwardMovement() && !this.onGround && !this.input.playerInput.sneak()
			|| !this.canSprint();
	}

    private boolean isWalking() {
        return this.input.hasForwardMovement();
    }

    public Box calculateBoundsForPose(EntityPose pose) {
        EntityDimensions size = POSE_DIMENSIONS.getOrDefault(pose, STANDING_DIMENSIONS);
        float f = size.width() / 2.0F;
        Vec3d min = new Vec3d(this.posX - (double)f, this.posY, this.posZ - (double)f);
        Vec3d max = new Vec3d(this.posX + (double)f, this.posY + (double)size.height(), this.posZ + (double)f);
        return new Box(min, max);
    }

    public boolean wouldPoseNotCollide(WorldView world, EntityPose pose) {
        return this.isSpaceEmpty(world, this.calculateBoundsForPose(pose).contract(1.0E-7));
    }

    private void pushOutOfBlocks(WorldView world, double x, double d) {
        Direction[] directions = new Direction[] { Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH };
        BlockPos blockPos = new BlockPos(MathHelper.floor(x), MathHelper.floor(this.posY), MathHelper.floor(d));
        if (!this.wouldCollideAt(world, blockPos)) {
            return;
        }
        double e = x - (double)blockPos.getX();
        double f = d - (double)blockPos.getZ();
        Direction direction = null;
        double g = Double.MAX_VALUE;

        for (Direction direction2 : directions) {
            double i;
            double h = direction2.getAxis().choose(e, 0.0D, f);
            double d2 = i = direction2.getDirection() == Direction.AxisDirection.POSITIVE ? 1.0D - h : h;
            if (!(i < g) || this.wouldCollideAt(world, blockPos.offset(direction2))) continue;
            g = i;
            direction = direction2;
        }

        if(direction != null) {
            if(direction.getAxis() == Direction.Axis.X) {
                this.velX = 0.1D * (double)direction.getOffsetX();
            } else {
                this.velZ = 0.1D * (double)direction.getOffsetZ();
            }
        }
    }

    public boolean canCollide(WorldView world, Box box) {
        AgentBlockCollisions collisions = new AgentBlockCollisions(world, this, box, true);
        
        while (collisions.hasNext()) {
			if (!collisions.next().isEmpty()) {
				return true;
			}
		}

		return false;

//        if(!collisions.hasNext()) {
//            this.scannedBlocks += collisions.scannedBlocks;
//            return false;
//        }
//
//        while(collisions.next().isEmpty()) {
//            if(!collisions.hasNext()) {
//                this.scannedBlocks += collisions.scannedBlocks;
//                return false;
//            }
//        }
//
//        this.scannedBlocks += collisions.scannedBlocks;
//        return true;
    }

    private boolean wouldCollideAt(WorldView world, BlockPos pos) {
    	Box box = this.box;
//		Box box2 = new Box((double)pos.getX(), box.minY, (double)pos.getZ(), (double)pos.getX() + 1.0, box.maxY, (double)pos.getZ() + 1.0).contract(1.0E-7);
        return this.canCollide(world, box);
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        // Recalculate movementSpeed when sprint changes.
        // Vanilla does this via attribute modifiers; we approximate.
        // For per-tick validation: Agent.of(player) captured the correct
        // value, and sprint doesn't change during validation ticks.
        // For pathfinding: this keeps movementSpeed in sync across nodes.
        this.movementSpeed = 0.1F;
        if(sprinting) {
            this.movementSpeed *= 1.3F;
        }
        if(this.speed >= 0) {
            this.movementSpeed = 0.1F + 0.02F * (this.speed + 1);
            if(sprinting) {
                this.movementSpeed *= 1.3F;
            }
        }
    }

    public double getFluidHeight(TagKey<Fluid> fluid) {
        return this.fluidHeight.getDouble(fluid);
    }

    public boolean isInLava() {
        return !this.firstUpdate && this.fluidHeight.getDouble(FluidTags.LAVA) > 0.0D;
    }

    /*

    public void tickCramming(WorldView world) {
        List<Entity> list = world.getOtherEntities(this, this.box, EntityPredicates.canBePushedBy(this));

        if(!list.isEmpty()) {
            int j;
            int i = this.world.getGameRules().getInt(GameRules.MAX_ENTITY_CRAMMING);
            if (i > 0 && list.size() > i - 1 && this.random.nextInt(4) == 0) {
                j = 0;
                for (int k = 0; k < list.size(); ++k) {
                    if (list.get(k).hasVehicle()) continue;
                    ++j;
                }
                if (j > i - 1) {
                    this.damage(DamageSource.CRAMMING, 6.0f);
                }
            }
            for (j = 0; j < list.size(); ++j) {
                Entity entity = list.get(j);
                this.pushAway(entity);
            }
        }
    }

    public void pushAway(Entity entity) {
        double e;
        double d = this.posX - entity.getX();
        double f = MathHelper.absMax(d, e = this.posZ - entity.getZ());

        if (f >= (double)0.01F) {
            f = MathHelper.sqrt(f);
            d /= f;
            e /= f;

            double g = 1.0 / f;
            if(g > 1.0) g = 1.0;

            d *= g;
            e *= g;
            d *= 0.05F;
            e *= 0.05F;
            d *= 1.0F - entity.pushSpeedReduction;
            e *= 1.0F - entity.pushSpeedReduction;

            entity.addVelocity(-d, 0.0, -e);
            this.velX += d;
            this.velZ += e;
        }
    }

    public void tickRiptide(Box a, Box b) {
        Box box = a.union(b);
        List<Entity> list = this.world.getOtherEntities(this, box);
        if (!list.isEmpty()) {
            for (int i = 0; i < list.size(); ++i) {
                Entity entity = list.get(i);
                if (!(entity instanceof LivingEntity)) continue;
                this.attackLivingEntity((LivingEntity)entity);
                this.riptideTicks = 0;
                this.setVelocity(this.getVelocity().multiply(-0.2));
                break;
            }
        } else if (this.horizontalCollision) {
            this.riptideTicks = 0;
        }
        if (!this.world.isClient && this.riptideTicks <= 0) {
            this.setLivingFlag(4, false);
        }
    }
    */

    /** Check if two doubles differ by more than the logging threshold. */
    private static boolean mismatch(double a, double b) {
        return Math.abs(a - b) > TungstenConfig.get().mismatchLogThreshold;
    }

    public void compare(PlayerEntity player, TungstenPlayerInput playerInput, boolean executor) {
        List<String> values = new ArrayList<>();
        double logThreshold = TungstenConfig.get().mismatchLogThreshold;

        double posDrift = Math.abs(this.posX - player.getX())
                        + Math.abs(this.posY - player.getY())
                        + Math.abs(this.posZ - player.getZ());
        if(posDrift > logThreshold) {
            values.add(String.format("Position mismatch (drift=%.2e)\n  real: %20.12f %20.12f %20.12f\n  sim:  %20.12f %20.12f %20.12f",
                posDrift,
                player.getX(), player.getY(), player.getZ(),
                this.posX, this.posY, this.posZ));
            if (TungstenModDataContainer.EXECUTOR.isRunning()) {
                double drift = player.getPos().distanceTo(new Vec3d(this.posX, this.posY, this.posZ));
                if (drift > kaptainwutax.tungsten.TungstenConfig.get().driftThreshold) {
                    // Try to reconnect to a nearby node on the path first.
                    // Only stop executor if reconnection fails.
                    if (!TungstenModDataContainer.EXECUTOR.tryReconnect(player.getPos())) {
                        Debug.logMessage(String.format(
                            "§c[Tungsten] Path stopped: drift %.3f blocks (threshold %.1f) at tick %d. " +
                            "Expected (%.2f, %.2f, %.2f), actual (%.2f, %.2f, %.2f). Reconnect failed — recalculating.",
                            drift, kaptainwutax.tungsten.TungstenConfig.get().driftThreshold,
                            TungstenModDataContainer.EXECUTOR.getCurrentTick(),
                            this.posX, this.posY, this.posZ,
                            player.getX(), player.getY(), player.getZ()));
                        TungstenModDataContainer.EXECUTOR.stop = true;
                        TungstenModDataContainer.PATHFINDER.stop.set(true);
                    }
                }
                if (TungstenModRenderContainer.ERROR.size() > 1000) TungstenModRenderContainer.ERROR.clear();
            }
        }
        
        double velDrift = Math.abs(this.velX - player.getVelocity().x)
                        + Math.abs(this.velY - player.getVelocity().y)
                        + Math.abs(this.velZ - player.getVelocity().z);
        if(velDrift > logThreshold) {
            values.add(String.format("Velocity mismatch (drift=%.2e)\n  real: %20.12f %20.12f %20.12f\n  sim:  %20.12f %20.12f %20.12f",
                velDrift,
                player.getVelocity().x, player.getVelocity().y, player.getVelocity().z,
                this.velX, this.velY, this.velZ));
            // Do not call setVelocity() — that overrides server-authoritative velocity and
            // causes position divergence leading to rubber-band teleports.
            // Log the mismatch only; path will self-correct on next recalc if needed.
            if (TungstenModDataContainer.EXECUTOR.isRunning()) {
                values.add(String.format("Velocity mismatch by (%s, %s, %s)",
                        player.getVelocity().x - this.velX,
                        player.getVelocity().y - this.velY,
                        player.getVelocity().z - this.velZ));
                Node node = TungstenModDataContainer.EXECUTOR.getCurrentNode();
                if (TungstenModRenderContainer.ERROR.size() > 1000) TungstenModRenderContainer.ERROR.clear();
                if (node != null) {
                    RenderHelper.renderNode(node, TungstenModRenderContainer.ERROR);
                }
            }
        }

        if(this.mulX != ((AccessorEntity)player).getMovementMultiplier().x
            || this.mulY != ((AccessorEntity)player).getMovementMultiplier().y
            || this.mulZ != ((AccessorEntity)player).getMovementMultiplier().z) {
            values.add(String.format("Movement Multiplier mismatch (%s, %s, %s) vs (%s, %s, %s)",
                ((AccessorEntity)player).getMovementMultiplier().x,
                ((AccessorEntity)player).getMovementMultiplier().y,
                ((AccessorEntity)player).getMovementMultiplier().z,
                ((AccessorEntity)player).getMovementMultiplier().x == this.mulX ? "x" : this.mulX,
                ((AccessorEntity)player).getMovementMultiplier().y == this.mulY ? "y" : this.mulY,
                ((AccessorEntity)player).getMovementMultiplier().z == this.mulZ ? "z" : this.mulZ));
        }

        if(mismatch(this.forwardSpeed, player.forwardSpeed) || mismatch(this.sidewaysSpeed, player.sidewaysSpeed) || mismatch(this.upwardSpeed, player.upwardSpeed)) {
            values.add(String.format("Input Speed mismatch (%s, %s, %s) vs (%s, %s, %s)",
                player.forwardSpeed == this.forwardSpeed ? "f" : player.forwardSpeed,
                player.upwardSpeed == this.upwardSpeed ? "u" : player.upwardSpeed,
                player.sidewaysSpeed == this.sidewaysSpeed ? "s" : player.sidewaysSpeed,
                player.forwardSpeed == this.forwardSpeed ? "f" : this.forwardSpeed,
                player.upwardSpeed == this.upwardSpeed ? "u" : this.upwardSpeed,
                player.sidewaysSpeed == this.sidewaysSpeed ? "s" : this.sidewaysSpeed));
        }

        if(mismatch(this.movementSpeed, player.getMovementSpeed())) {

        	if (TungstenModDataContainer.LOG_DEBUG_DATA) {
        		Node node = TungstenModDataContainer.EXECUTOR.getCurrentNode();
            	if (node != null) {
    	        	StringBuilder string = new StringBuilder();
    	
    	    		string.append("{\n");
    	    		if (node.input.forward != playerInput.forward()) {
    	        		string.append("forward: ");
    	        		string.append(playerInput.forward());
    	        		string.append(" vs ");
    	        		string.append(node.input.forward);
    	        		string.append("\n");
    	    		}
    	    		if (node.input.back != playerInput.backward()) {
    	        		string.append("back: ");
    	        		string.append(playerInput.backward());
    	        		string.append(" vs ");
    	        		string.append(node.input.back);
    	        		string.append("\n");
    	    		}
    	    		if (node.input.right != playerInput.right()) {
    	        		string.append("right: ");
    	        		string.append(playerInput.right());
    	        		string.append(" vs ");
    	        		string.append(node.input.right);
    	        		string.append("\n");
    	    		}
    	    		if (node.input.left != playerInput.left()) {
    	        		string.append("left: ");
    	        		string.append(playerInput.left());
    	        		string.append(" vs ");
    	        		string.append(node.input.left);
    	        		string.append("\n");
    	    		}
    	    		if (node.input.jump != playerInput.jump()) {
    	        		string.append("jump: ");
    	        		string.append(playerInput.jump());
    	        		string.append(" vs ");
    	        		string.append(node.input.jump);
    	        		string.append("\n");
    	    		}
    	    		if (node.input.sneak != playerInput.sneak()) {
    	        		string.append("sneak: ");
    	        		string.append(playerInput.sneak());
    	        		string.append(" vs ");
    	        		string.append(node.input.sneak);
    	        		string.append("\n");
    	    		}
    	    		if (node.input.sprint != playerInput.sprint()) {
    	        		string.append("sprint: ");
    	        		string.append(playerInput.sprint());
    	        		string.append(" vs ");
    	        		string.append(node.input.sprint);
    	        		string.append("\n");
    	    		}
    	    		if (node.input.pitch != player.getPitch()) {
    	        		string.append("pitch: ");
    	        		string.append(player.getPitch());
    	        		string.append(" vs ");
    	        		string.append(node.input.pitch);
    	        		string.append("\n");
    	    		}
    	    		if (node.input.yaw != player.getYaw()) {
    	        		string.append("yaw: ");
    	        		string.append(player.getYaw());
    	        		string.append(" vs ");
    	        		string.append(node.input.yaw);
    	        		string.append("\n");
    	    		}
    	    		if (node.agent.onGround != player.isOnGround()) {
    	        		string.append("isOnGround: ");
    	        		string.append(player.isOnGround());
    	        		string.append(" vs ");
    	        		string.append(node.agent.onGround);
    	        		string.append("\n");
    	    		}
    	    		string.append("\n");
    	    		string.append("}");
    	
    	        	if (string.toString().length() > 4) {
    		        	Debug.logMessage("------------");
    		        	Debug.logMessage(string.toString());
    		        	Debug.logMessage("Current Movement Speed: " + player.getMovementSpeed() + " \nExpected to be: " + this.movementSpeed + "");
    		        	Debug.logMessage("------------");
    	        	} else {
    		        	Debug.logMessage("Current Movement Speed: " + player.getMovementSpeed() + " \nExpected to be: " + this.movementSpeed + "");
    	        	}
            	}	
        	}
        	
            values.add(String.format("Movement Speed mismatch\n  real: %.10f\n  sim:  %.10f", player.getMovementSpeed(), this.movementSpeed));
        }

        if(this.pose != player.getPose()) {
            values.add(String.format("Pose mismatch %s vs %s", player.getPose(), this.pose));
        }

        if(this.isSubmergedInWater != player.isSubmergedInWater()) {
            values.add(String.format("Sprinting mismatch %s vs %s", player.isSprinting(), this.sprinting));
        }

        if(this.touchingWater != player.isTouchingWater()) {
            values.add(String.format("Touching water mismatch %s vs %s", player.isTouchingWater(), this.touchingWater));
        }

        if(this.isSubmergedInWater != player.isSubmergedInWater()) {
            values.add(String.format("Submerged in water mismatch %s vs %s", player.isSubmergedInWater(), this.isSubmergedInWater));
        }

	    if(this.input.playerInput.sneak() != playerInput.sneak()) {
		    values.add(String.format("Sneaking mismatch %s vs %s", playerInput.sneak(), this.input.playerInput.sneak()));
	    }

        if(this.swimming != player.isSwimming()) {
            values.add(String.format("Swimming mismatch %s vs %s", player.isSwimming(), this.swimming));
        }

        if(this.standingEyeHeight != player.getStandingEyeHeight()) {
            values.add(String.format("Eye height mismatch %s vs %s", player.getStandingEyeHeight(), this.standingEyeHeight));
        }

        if(mismatch(this.fallDistance, player.fallDistance)) {
            values.add(String.format("Fall distance mismatch %s vs %s", player.fallDistance, this.fallDistance));
        }

        if(this.horizontalCollision != player.horizontalCollision) {
            values.add(String.format("Horizontal Collision mismatch %s vs %s", player.horizontalCollision, this.horizontalCollision));
        }

        if(this.verticalCollision != player.verticalCollision) {
            values.add(String.format("Vertical Collision mismatch %s vs %s", player.verticalCollision, this.verticalCollision));
        }

        if(this.collidedSoftly != player.collidedSoftly) {
            values.add(String.format("Soft Collision mismatch %s vs %s", player.collidedSoftly, this.collidedSoftly));
        }

        if(this.jumping != ((AccessorLivingEntity)player).getJumping()) {
            values.add(String.format("Jumping mismatch %s vs %s", ((AccessorLivingEntity)player).getJumping(), this.jumping));
        }

        if(this.jumpingCooldown != ((AccessorLivingEntity)player).getJumpingCooldown()) {
            values.add(String.format("Jumping Cooldown mismatch %s vs %s", ((AccessorLivingEntity)player).getJumpingCooldown(), this.jumpingCooldown));
        }

//        if(this.airStrafingSpeed != player.airStrafingSpeed) {
//            values.add(String.format("Air Strafe Speed mismatch %s vs %s", player.airStrafingSpeed, this.airStrafingSpeed));
//        }
        
//        if(!this.box.equals(player.getBoundingBox())) {
//            values.add(String.format("Bounding box mismatch %s vs %s", player.getBoundingBox(), this.box));
//            this.box = player.getBoundingBox();
//        }


        if(this.firstUpdate != ((AccessorEntity)player).getFirstUpdate()) {
            values.add(String.format("First Update mismatch %s vs %s", ((AccessorEntity)player).getFirstUpdate(), this.firstUpdate));
        }

        if(!this.submergedFluids.equals(((AccessorEntity)player).getSubmergedFluidTag())) {
            values.add(String.format("Submerged Fluids mismatch %s vs %s", ((AccessorEntity)player).getSubmergedFluidTag(), this.submergedFluids));
        }

        if(!values.isEmpty() && TungstenConfig.get().verboseDebugLogging) {
            System.out.println("Tick " + player.age + " ===========================================");
            values.forEach(System.out::println);
        }
    }


    public static Agent of(PlayerEntity player) {
        Agent agent = new Agent();
        agent.keyForward = TungstenPlayerInput.DEFAULT.forward();
        agent.keyBack = TungstenPlayerInput.DEFAULT.backward();
        agent.keyLeft = TungstenPlayerInput.DEFAULT.left();
        agent.keyRight = TungstenPlayerInput.DEFAULT.right();
        agent.keyJump = TungstenPlayerInput.DEFAULT.jump();
        agent.keySneak = TungstenPlayerInput.DEFAULT.sneak();
        agent.keySprint = TungstenPlayerInput.DEFAULT.sprint();

        agent.pose = player.getPose();
        agent.sprinting = player.isSprinting();
        agent.inSneakingPose = player.isInSneakingPose();
        agent.usingItem = player.isUsingItem();
        agent.sidewaysSpeed = player.sidewaysSpeed;
        agent.upwardSpeed = player.upwardSpeed;
        agent.forwardSpeed = player.forwardSpeed;
        agent.yaw = player.getYaw();
        agent.pitch = player.getPitch();
        agent.posX = player.getX();
        agent.posY = player.getY();
        agent.posZ = player.getZ();
        agent.blockX = player.getBlockPos().getX();
        agent.blockY = player.getBlockPos().getY();
        agent.blockZ = player.getBlockPos().getZ();
        agent.velX = player.getVelocity().x;
        agent.velY = player.getVelocity().y;
        agent.velZ = player.getVelocity().z;
        agent.mulX = ((AccessorEntity)player).getMovementMultiplier().x;
        agent.mulY = ((AccessorEntity)player).getMovementMultiplier().y;
        agent.mulZ = ((AccessorEntity)player).getMovementMultiplier().z;
        agent.fluidHeight.put(FluidTags.WATER, player.getFluidHeight(FluidTags.WATER));
        agent.fluidHeight.put(FluidTags.LAVA, player.getFluidHeight(FluidTags.LAVA));
        agent.submergedFluids.addAll(((AccessorEntity)player).getSubmergedFluidTag());
        agent.firstUpdate = ((AccessorEntity)player).getFirstUpdate();
        agent.box = player.getBoundingBox();
        agent.dimensions = player.getDimensions(player.getPose());
        agent.standingEyeHeight = player.getStandingEyeHeight();
        agent.onGround = player.isOnGround();
        agent.sleeping = player.isSleeping();
        agent.sneaking = player.isSneaky();
        agent.hunger = player.getHungerManager();
        agent.sprinting = player.isSprinting();
        agent.swimming = player.isSwimming();
        agent.fallFlying = player.getAbilities().flying;
        agent.stepHeight = player.getStepHeight();
        agent.fallDistance = player.fallDistance;
        agent.touchingWater = player.isTouchingWater();
        agent.isSubmergedInWater = player.isSubmergedInWater();
        agent.horizontalCollision = player.horizontalCollision;
        agent.verticalCollision = player.verticalCollision;
        agent.collidedSoftly = player.collidedSoftly;
        agent.jumping = ((AccessorLivingEntity)player).getJumping();
        agent.speed = player.hasStatusEffect(StatusEffects.SPEED) ? player.getStatusEffect(StatusEffects.SPEED).getAmplifier() : -1;
        agent.blindness = player.hasStatusEffect(StatusEffects.BLINDNESS) ? player.getStatusEffect(StatusEffects.BLINDNESS).getAmplifier() : -1;
        agent.jumpBoost = player.hasStatusEffect(StatusEffects.JUMP_BOOST) ? player.getStatusEffect(StatusEffects.JUMP_BOOST).getAmplifier() : -1;
        agent.slowFalling = player.hasStatusEffect(StatusEffects.SLOW_FALLING) ? player.getStatusEffect(StatusEffects.SLOW_FALLING).getAmplifier() : -1;
        agent.dolphinsGrace = player.hasStatusEffect(StatusEffects.DOLPHINS_GRACE) ? player.getStatusEffect(StatusEffects.DOLPHINS_GRACE).getAmplifier() : -1;
        agent.levitation = player.hasStatusEffect(StatusEffects.LEVITATION) ? player.getStatusEffect(StatusEffects.LEVITATION).getAmplifier() : -1;
        agent.movementSpeed = player.getMovementSpeed();
        agent.airStrafingSpeed = agent.sprinting ? 0.026F : 0.02F;
        agent.jumpingCooldown = ((AccessorLivingEntity)player).getJumpingCooldown();
        agent.hunger.setFoodLevel(player.getHungerManager().getFoodLevel());
        agent.hunger.setSaturationLevel(player.getHungerManager().getSaturationLevel());
        //TODO: frame.ticksToNextAutojump
        return agent;
    }


    public static Agent of(PlayerEntity player, TungstenPlayerInput playerInput) {
        Agent agent = new Agent();
        agent.keyForward = playerInput.forward();
        agent.keyBack = playerInput.backward();
        agent.keyLeft = playerInput.left();
        agent.keyRight = playerInput.right();
        agent.keyJump = playerInput.jump();
        agent.keySneak = playerInput.sneak();
        agent.keySprint = playerInput.sprint();

        agent.pose = player.getPose();
        agent.sprinting = player.isSprinting();
        agent.inSneakingPose = player.isInSneakingPose();
        agent.usingItem = player.isUsingItem();
        agent.sidewaysSpeed = player.sidewaysSpeed;
        agent.upwardSpeed = player.upwardSpeed;
        agent.forwardSpeed = player.forwardSpeed;
        agent.yaw = player.getYaw();
        agent.pitch = player.getPitch();
        agent.posX = player.getX();
        agent.posY = player.getY();
        agent.posZ = player.getZ();
        agent.blockX = player.getBlockPos().getX();
        agent.blockY = player.getBlockPos().getY();
        agent.blockZ = player.getBlockPos().getZ();
        agent.velX = player.getVelocity().x;
        agent.velY = player.getVelocity().y;
        agent.velZ = player.getVelocity().z;
        agent.mulX = ((AccessorEntity)player).getMovementMultiplier().x;
        agent.mulY = ((AccessorEntity)player).getMovementMultiplier().y;
        agent.mulZ = ((AccessorEntity)player).getMovementMultiplier().z;
        agent.fluidHeight.put(FluidTags.WATER, player.getFluidHeight(FluidTags.WATER));
        agent.fluidHeight.put(FluidTags.LAVA, player.getFluidHeight(FluidTags.LAVA));
        agent.submergedFluids.addAll(((AccessorEntity)player).getSubmergedFluidTag());
        agent.firstUpdate = ((AccessorEntity)player).getFirstUpdate();
        agent.box = player.getBoundingBox();
        agent.dimensions = player.getDimensions(player.getPose());
        agent.standingEyeHeight = player.getStandingEyeHeight();
        agent.onGround = player.isOnGround();
        agent.sleeping = player.isSleeping();
        agent.sneaking = player.isSneaky();
        agent.hunger = player.getHungerManager();
        agent.sprinting = player.isSprinting();
        agent.swimming = player.isSwimming();
        agent.fallFlying = player.getAbilities().flying;
        agent.stepHeight = player.getStepHeight();
        agent.fallDistance = player.fallDistance;
        agent.touchingWater = player.isTouchingWater();
        agent.isSubmergedInWater = player.isSubmergedInWater();
        agent.horizontalCollision = player.horizontalCollision;
        agent.verticalCollision = player.verticalCollision;
        agent.collidedSoftly = player.collidedSoftly;
        agent.jumping = ((AccessorLivingEntity)player).getJumping();
        agent.speed = player.hasStatusEffect(StatusEffects.SPEED) ? player.getStatusEffect(StatusEffects.SPEED).getAmplifier() : -1;
        agent.blindness = player.hasStatusEffect(StatusEffects.BLINDNESS) ? player.getStatusEffect(StatusEffects.BLINDNESS).getAmplifier() : -1;
        agent.jumpBoost = player.hasStatusEffect(StatusEffects.JUMP_BOOST) ? player.getStatusEffect(StatusEffects.JUMP_BOOST).getAmplifier() : -1;
        agent.slowFalling = player.hasStatusEffect(StatusEffects.SLOW_FALLING) ? player.getStatusEffect(StatusEffects.SLOW_FALLING).getAmplifier() : -1;
        agent.dolphinsGrace = player.hasStatusEffect(StatusEffects.DOLPHINS_GRACE) ? player.getStatusEffect(StatusEffects.DOLPHINS_GRACE).getAmplifier() : -1;
        agent.levitation = player.hasStatusEffect(StatusEffects.LEVITATION) ? player.getStatusEffect(StatusEffects.LEVITATION).getAmplifier() : -1;
        agent.movementSpeed = player.getMovementSpeed();
        agent.airStrafingSpeed = agent.sprinting ? 0.026F : 0.02F;
        agent.jumpingCooldown = ((AccessorLivingEntity)player).getJumpingCooldown();
        agent.hunger.setFoodLevel(player.getHungerManager().getFoodLevel());
        agent.hunger.setSaturationLevel(player.getHungerManager().getSaturationLevel());
        //TODO: frame.ticksToNextAutojump
        return agent;
    }

    
    public static Agent of(ClientPlayerEntity player, GameOptions options) {
        Agent agent = new Agent();
        agent.keyForward = options.forwardKey.isPressed();
        agent.keyBack = options.backKey.isPressed();
        agent.keyLeft = options.leftKey.isPressed();
        agent.keyRight = options.rightKey.isPressed();
        agent.keyJump = options.jumpKey.isPressed();
        agent.keySneak = options.sneakKey.isPressed();
        agent.keySprint = options.sprintKey.isPressed();

        agent.pose = player.getPose();
        agent.sprinting = player.isSprinting();
        agent.inSneakingPose = player.isInSneakingPose();
        agent.usingItem = player.isUsingItem();
        agent.sidewaysSpeed = player.sidewaysSpeed;
        agent.upwardSpeed = player.upwardSpeed;
        agent.forwardSpeed = player.forwardSpeed;
        agent.yaw = player.getYaw();
        agent.pitch = player.getPitch();
        agent.posX = player.getX();
        agent.posY = player.getY();
        agent.posZ = player.getZ();
        agent.blockX = player.getBlockPos().getX();
        agent.blockY = player.getBlockPos().getY();
        agent.blockZ = player.getBlockPos().getZ();
        agent.velX = player.getVelocity().x;
        agent.velY = player.getVelocity().y;
        agent.velZ = player.getVelocity().z;
        agent.mulX = ((AccessorEntity)player).getMovementMultiplier().x;
        agent.mulY = ((AccessorEntity)player).getMovementMultiplier().y;
        agent.mulZ = ((AccessorEntity)player).getMovementMultiplier().z;
        agent.fluidHeight.put(FluidTags.WATER, player.getFluidHeight(FluidTags.WATER));
        agent.fluidHeight.put(FluidTags.LAVA, player.getFluidHeight(FluidTags.LAVA));
        agent.submergedFluids.addAll(((AccessorEntity)player).getSubmergedFluidTag());
        agent.firstUpdate = ((AccessorEntity)player).getFirstUpdate();
        agent.box = player.getBoundingBox();
        agent.dimensions = player.getDimensions(player.getPose());
        agent.standingEyeHeight = player.getStandingEyeHeight();
        agent.onGround = player.isOnGround();
        agent.sleeping = player.isSleeping();
        agent.sneaking = player.isSneaky();
        agent.hunger = player.getHungerManager();
        agent.sprinting = player.isSprinting();
        agent.swimming = player.isSwimming();
        agent.fallFlying = player.getAbilities().flying;
        agent.stepHeight = player.getStepHeight();
        agent.fallDistance = player.fallDistance;
        agent.touchingWater = player.isTouchingWater();
        agent.isSubmergedInWater = player.isSubmergedInWater();
        agent.horizontalCollision = player.horizontalCollision;
        agent.verticalCollision = player.verticalCollision;
        agent.collidedSoftly = player.collidedSoftly;
        agent.jumping = ((AccessorLivingEntity)player).getJumping();
        agent.speed = player.hasStatusEffect(StatusEffects.SPEED) ? player.getStatusEffect(StatusEffects.SPEED).getAmplifier() : -1;
        agent.blindness = player.hasStatusEffect(StatusEffects.BLINDNESS) ? player.getStatusEffect(StatusEffects.BLINDNESS).getAmplifier() : -1;
        agent.jumpBoost = player.hasStatusEffect(StatusEffects.JUMP_BOOST) ? player.getStatusEffect(StatusEffects.JUMP_BOOST).getAmplifier() : -1;
        agent.slowFalling = player.hasStatusEffect(StatusEffects.SLOW_FALLING) ? player.getStatusEffect(StatusEffects.SLOW_FALLING).getAmplifier() : -1;
        agent.dolphinsGrace = player.hasStatusEffect(StatusEffects.DOLPHINS_GRACE) ? player.getStatusEffect(StatusEffects.DOLPHINS_GRACE).getAmplifier() : -1;
        agent.levitation = player.hasStatusEffect(StatusEffects.LEVITATION) ? player.getStatusEffect(StatusEffects.LEVITATION).getAmplifier() : -1;
        agent.movementSpeed = player.getMovementSpeed();
        agent.airStrafingSpeed = agent.sprinting ? 0.026F : 0.02F;
        agent.jumpingCooldown = ((AccessorLivingEntity)player).getJumpingCooldown();
        agent.hunger.setFoodLevel(player.getHungerManager().getFoodLevel());
        agent.hunger.setSaturationLevel(player.getHungerManager().getSaturationLevel());
        //TODO: frame.ticksToNextAutojump
        return agent;
    }

    public static Agent of(Agent other, boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint, float pitch, float yaw) {
        Agent agent = new Agent();
        agent.keyForward = forward;
        agent.keyBack = back;
        agent.keyLeft = left;
        agent.keyRight = right;
        agent.keyJump = jump;
        agent.keySneak = sneak;
        agent.keySprint = sprint;
        agent.hunger.setFoodLevel(other.hunger.getFoodLevel());
        agent.hunger.setSaturationLevel(other.hunger.getSaturationLevel());
        agent.sprinting = other.sprinting;
        agent.pose = other.pose;
        agent.inSneakingPose = other.inSneakingPose;
        agent.usingItem = other.usingItem;
        agent.sidewaysSpeed = other.sidewaysSpeed;
        agent.upwardSpeed = other.upwardSpeed;
        agent.forwardSpeed = other.forwardSpeed;
        agent.yaw = yaw;
        agent.pitch = pitch;
        agent.posX = other.posX;
        agent.posY = other.posY;
        agent.posZ = other.posZ;
        agent.blockX = other.blockX;
        agent.blockY = other.blockY;
        agent.blockZ = other.blockZ;
        agent.velX = other.velX;
        agent.velY = other.velY;
        agent.velZ = other.velZ;
        agent.mulX = other.mulX;
        agent.mulY = other.mulY;
        agent.mulZ = other.mulZ;
        agent.fluidHeight.put(FluidTags.WATER, other.getFluidHeight(FluidTags.WATER));
        agent.fluidHeight.put(FluidTags.LAVA, other.getFluidHeight(FluidTags.LAVA));
        agent.submergedFluids.addAll(other.submergedFluids);
        agent.firstUpdate = other.firstUpdate;
        agent.dimensions = other.dimensions;
        agent.box = other.box;
        agent.standingEyeHeight = other.standingEyeHeight;
        agent.onGround = other.onGround;
        agent.sleeping = other.sleeping;
        agent.sneaking = other.sneaking;
        agent.sprinting = other.sprinting;
        agent.swimming = other.swimming;
        agent.fallFlying = other.fallFlying;
        agent.stepHeight = other.stepHeight;
        agent.fallDistance = other.fallDistance;
        agent.touchingWater = other.touchingWater;
        agent.isSubmergedInWater = other.isSubmergedInWater;
        agent.horizontalCollision = other.horizontalCollision;
        agent.verticalCollision = other.verticalCollision;
        agent.collidedSoftly = other.collidedSoftly;
        agent.jumping = other.jumping;
        agent.speed = other.speed;
        agent.blindness = other.blindness;
        agent.jumpBoost = other.jumpBoost;
        agent.slowFalling = other.slowFalling;
        agent.dolphinsGrace = other.dolphinsGrace;
        agent.levitation = other.levitation;
        agent.depthStrider = other.depthStrider;
        agent.movementSpeed = other.movementSpeed;
        agent.airStrafingSpeed = other.airStrafingSpeed;
        agent.jumpingCooldown = other.jumpingCooldown;
        //TODO: frame.ticksToNextAutojump
        return agent;
    }

    public static Agent of(Agent other, AgentInput input) {
    	PathInput pI = input.toPathInput();
    	return of(other, pI);
    }

    public static Agent of(Agent agent, PathInput input) {
        return of(agent, input.forward, input.back, input.left, input.right, input.jump, input.sneak, input.sprint, input.pitch, input.yaw);
    }

}
