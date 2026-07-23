package kaptainwutax.tungsten.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.agent.TungstenPlayerInput;
import kaptainwutax.tungsten.task.BlockPathWalker;
import kaptainwutax.tungsten.task.FollowEntityTask;
import kaptainwutax.tungsten.task.FollowPlayerTask;
import kaptainwutax.tungsten.task.PunkPlayerTask;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockSpacePathFinder;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity extends AbstractClientPlayerEntity {

	// MC 1.21: AbstractClientPlayerEntity constructor takes (ClientWorld, GameProfile) only
	// PlayerPublicKey was removed in MC 1.20.5
	public MixinClientPlayerEntity(ClientWorld world, GameProfile profile) {
		super(world, profile);
	}

	@Inject(method = "tick", at = @At("HEAD"))
	public void start(CallbackInfo ci) {
		if (TungstenMod.runKeyBinding == null) return; // tungsten not initialized yet
		//#if MC < 12111
		//$$ FollowEntityTask.tick(this.getWorld(), (ClientPlayerEntity)(Object)this);
		//$$ FollowPlayerTask.tick(this.getWorld(), (ClientPlayerEntity)(Object)this);
		//$$ PunkPlayerTask.tick(this.getWorld(), (ClientPlayerEntity)(Object)this);
		//$$ kaptainwutax.tungsten.task.RunAwayTask.tick(this.getWorld(), (ClientPlayerEntity)(Object)this);
		//#else
		FollowEntityTask.tick(this.getEntityWorld(), (ClientPlayerEntity)(Object)this);
		FollowPlayerTask.tick(this.getEntityWorld(), (ClientPlayerEntity)(Object)this);
		PunkPlayerTask.tick(this.getEntityWorld(), (ClientPlayerEntity)(Object)this);
		kaptainwutax.tungsten.task.RunAwayTask.tick(this.getEntityWorld(), (ClientPlayerEntity)(Object)this);
		//#endif

		// BFS walker: immediate movement while physics A* computes
		BlockPathWalker.tick((ClientPlayerEntity)(Object)this);

		// bow-shot primitive (aim + charge + release)
		kaptainwutax.tungsten.task.BowShooter.tick((ClientPlayerEntity)(Object)this);

		// shield-block primitive (hold use for N ticks)
		kaptainwutax.tungsten.task.ShieldBlocker.tick((ClientPlayerEntity)(Object)this);

		// sneak-bridge primitive (epic parkour block placing)
		kaptainwutax.tungsten.task.BridgeTask.tick((ClientPlayerEntity)(Object)this);

		// pillar-up primitive (place under self + jump to reach a raised goal — #46)
		kaptainwutax.tungsten.task.PillarTask.tick((ClientPlayerEntity)(Object)this);

		if(TungstenModDataContainer.isExecutorRunning()) {
			try {
				TungstenModDataContainer.EXECUTOR.tick((ClientPlayerEntity)(Object)this, MinecraftClient.getInstance().options);
			} catch (Exception e) {
				Debug.logMessage("Tungsten executor tick failed, stopping executor to prevent freeze.");
				e.printStackTrace();
				TungstenModDataContainer.EXECUTOR.stop = true;
			}
		}

		// While FLEEING, the pathfinder executor sprint-jumps toward the flee point
		// and can overshoot off a rim (no combat clamp runs). Apply the shared void
		// guard as the final word so a flee never carries the bot into the void.
		// Flee AND punk-approach — normal goto/get keep the executor's decisions.
		if (kaptainwutax.tungsten.task.RunAwayTask.isActive()
				|| kaptainwutax.tungsten.task.PunkPlayerTask.isActive()) {
			kaptainwutax.tungsten.combat.VoidGuard.protect((ClientPlayerEntity)(Object)this, this.getEntityWorld());
		}

		if(!this.getAbilities().flying) {
			Agent.INSTANCE = Agent.of((ClientPlayerEntity)(Object)this, MinecraftClient.getInstance().options);
			// This per-tick physics simulation exists ONLY to feed the verbose drift log
			// (the non-executor compare below has no side effect). Running it every tick
			// pegged the client at ~400% CPU for nothing when not debugging. The executor's
			// OWN drift correction uses the precomputed path-node agents (Node.agent), not
			// this INSTANCE, so gating it on the debug flag is safe.
			if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) {
				//#if MC < 12111
				//$$ Agent.INSTANCE.tick(this.getWorld());
				//#else
				Agent.INSTANCE.tick(this.getEntityWorld());
				//#endif
			}
		}

		if(TungstenMod.runKeyBinding.isPressed() && !TungstenModDataContainer.PATHFINDER.active.get() && !TungstenModDataContainer.isExecutorRunning()) {
			//#if MC < 12111
			//$$ TungstenModDataContainer.PATHFINDER.find(this.getWorld(), TungstenMod.TARGET, TungstenMod.mc.player);
			//#else
			TungstenModDataContainer.PATHFINDER.find(this.getEntityWorld(), TungstenMod.TARGET, TungstenMod.mc.player);
			//#endif
		}
		if(TungstenMod.runBlockSearchKeyBinding.isPressed() && !TungstenModDataContainer.PATHFINDER.active.get()) {
			//#if MC < 12111
			//$$ BlockSpacePathFinder.find(getWorld(), TungstenMod.TARGET, TungstenMod.mc.player);
			//#else
			BlockSpacePathFinder.find(getEntityWorld(), TungstenMod.TARGET, TungstenMod.mc.player);
			//#endif
		}
		if (TungstenMod.pauseKeyBinding.isPressed()) {
			try {

	        	if((TungstenModDataContainer.PATHFINDER.active.get() || TungstenModDataContainer.isExecutorRunning())) {
	        		TungstenModDataContainer.PATHFINDER.stop.set(true);
	        		if (TungstenModDataContainer.EXECUTOR != null) TungstenModDataContainer.EXECUTOR.stop = true;
					Debug.logMessage("Stopped!");
	    		} else {
					Debug.logMessage("Nothing to stop.");
	    		}


			} catch (Exception e) {
				// TODO: handle exception
			}
		}

		if (TungstenMod.pauseKeyBinding.isPressed()) {
			TungstenModDataContainer.PATHFINDER.stop.set(true);
		}
		if (TungstenMod.createGoalKeyBinding.isPressed()) {
			BlockPos cameraBlockPos = TungstenMod.mc.gameRenderer.getCamera().getBlockPos();
			TungstenMod.TARGET = new Vec3d(cameraBlockPos.getX() + 0.5, cameraBlockPos.getY() - 1, cameraBlockPos.getZ() + 0.5);
		}
	}

	@Inject(method = "tick", at = @At(value = "RETURN"))
	public void end(CallbackInfo ci) {
		ClientPlayerEntity self = (ClientPlayerEntity)(Object)this;
		//#if MC < 12111
		//$$ // MC 1.21: Input has no playerInput field; build TungstenPlayerInput from input fields
		//$$ TungstenPlayerInput currentInput = new TungstenPlayerInput(
			//$$ self.input.movementForward > 0,
			//$$ self.input.movementForward < 0,
			//$$ self.input.movementSideways > 0,
			//$$ self.input.movementSideways < 0,
			//$$ self.input.jumping,
			//$$ self.input.sneaking,
			//$$ self.isSprinting()
		//$$ );
		//#else
		TungstenPlayerInput currentInput = new TungstenPlayerInput(
		    self.input.playerInput.forward(),
		    self.input.playerInput.backward(),
		    self.input.playerInput.left(),
		    self.input.playerInput.right(),
		    self.input.playerInput.jump(),
		    self.input.playerInput.sneak(),
		    self.input.playerInput.sprint()
		);
		//#endif
		if (TungstenModDataContainer.isExecutorRunning() && TungstenModDataContainer.EXECUTOR.getCurrentTick() > 0) {
			TungstenModDataContainer.EXECUTOR.getPath().get(TungstenModDataContainer.EXECUTOR.getCurrentTick() - 1).agent.compare(self, currentInput, true);
		} else if(!this.getAbilities().flying && Agent.INSTANCE != null
				&& kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging) {
			Agent.INSTANCE.compare(self, currentInput, false);   // debug drift log only
		}
	}

	@Inject(method="getPitch", at=@At("RETURN"), cancellable = true)
	public void getPitch(float tickDelta, CallbackInfoReturnable<Float> ci) {
		if(TungstenModDataContainer.isExecutorRunning()) {
			ci.setReturnValue(super.getPitch(tickDelta));
		}
	}

	@Inject(method="getYaw", at=@At("RETURN"), cancellable = true)
	public void getYaw(float tickDelta, CallbackInfoReturnable<Float> ci) {
		if(TungstenModDataContainer.isExecutorRunning()) {
			ci.setReturnValue(super.getYaw(tickDelta));
		}
	}

}
