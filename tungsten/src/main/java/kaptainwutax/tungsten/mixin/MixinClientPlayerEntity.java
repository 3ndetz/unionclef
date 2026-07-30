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

	// Was any tungsten driver active last tick? Used to release LEAKED input once on the
	// driving->idle transition (a task can setPressed(true) and end without releasing —
	// user report: SHIFT/sneak stuck ~5s after combat near a ledge).
	@org.spongepowered.asm.mixin.Unique
	private static boolean tungsten$wasDriving = false;

	@Inject(method = "tick", at = @At("HEAD"))
	public void start(CallbackInfo ci) {
		if (TungstenMod.runKeyBinding == null) return; // tungsten not initialized yet
		// aim-jitter telemetry: record yaw each tick so a stand test can quantify shake
		kaptainwutax.tungsten.util.AimSampler.record(((ClientPlayerEntity)(Object)this).getYaw());
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

		// pipelined navigation: walk this leg while the next one is planned from
		// its tail (must tick BEFORE the walker so a finished leg is replaced in
		// the same tick and the bot never pauses at a seam)
		kaptainwutax.tungsten.task.FastNavigator.tick((ClientPlayerEntity)(Object)this);

		// PORTED BARITONE MOVEMENTS — ONE OWNER OF THE TICK. A MovementQueue leg is a chain of
		// MovementTraverse objects, each owning its whole one-block step: the body, the keys, the
		// camera and the click. Movement.update() releases every key and then presses exactly what
		// this tick declared, so a SECOND per-tick writer does not merely conflict, it silently wins
		// half the ticks — which is the measured failure this port exists to remove
		// (called=11041 inRange=11040 clicked=0, BARITONE-PORT-SPEC.md pitfall P1). Hence the early
		// return: while the queue runs, the walker, the two build primitives, the crossing and the
		// physics executor do not run at all. Ticked here, after the navigator that starts it and
		// before the walker it replaces (spec unit 2's wiring).
		kaptainwutax.tungsten.path.movements.MovementQueue.tick((ClientPlayerEntity)(Object)this);
		boolean tungsten$movementOwnsTick = kaptainwutax.tungsten.path.movements.MovementQueue.isRunning();

		if (!tungsten$movementOwnsTick) {
			// BFS walker: immediate movement while physics A* computes
			BlockPathWalker.tick((ClientPlayerEntity)(Object)this);
		}

		// bow-shot primitive (aim + charge + release)
		kaptainwutax.tungsten.task.BowShooter.tick((ClientPlayerEntity)(Object)this);

		// shield-block primitive (hold use for N ticks)
		kaptainwutax.tungsten.task.ShieldBlocker.tick((ClientPlayerEntity)(Object)this);

		if (!tungsten$movementOwnsTick) {
			// sneak-bridge primitive (epic parkour block placing)
			kaptainwutax.tungsten.task.BridgeTask.tick((ClientPlayerEntity)(Object)this);

			// pillar-up primitive (place under self + jump to reach a raised goal — #46)
			kaptainwutax.tungsten.task.PillarTask.tick((ClientPlayerEntity)(Object)this);

			// slime crossing primitive (hold heading + sprint across a whole bounce chain)
			kaptainwutax.tungsten.task.SlimeBounceTask.tick((ClientPlayerEntity)(Object)this);
		}

		if(TungstenModDataContainer.isExecutorRunning() && !tungsten$movementOwnsTick) {
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

		// LEAKED-INPUT RELEASE: a task (VoidGuard edge-sneak during flee/punk, SafetySystem's
		// combat edge-sneak) can setPressed(true) and end without releasing, leaving SHIFT /
		// sprint / etc. STUCK over the human player's control (user: sneak sticks ~5s after
		// combat near a ledge). Release the mod-controlled keys ONCE on the driving->idle
		// transition, so we clear the leak without fighting the user's own held keys mid-play.
		boolean tungsten$driving = TungstenModDataContainer.isExecutorRunning()
				|| tungsten$movementOwnsTick
				|| kaptainwutax.tungsten.task.BlockPathWalker.isRunning()
				|| kaptainwutax.tungsten.task.PunkPlayerTask.isActive()
				|| kaptainwutax.tungsten.task.RunAwayTask.isActive()
				|| kaptainwutax.tungsten.task.FollowEntityTask.isActive()
				|| kaptainwutax.tungsten.task.FollowPlayerTask.isActive()
				|| kaptainwutax.tungsten.task.BridgeTask.isActive()
				|| kaptainwutax.tungsten.task.PillarTask.isActive()
				|| kaptainwutax.tungsten.task.SlimeBounceTask.isActive()
				|| (TungstenModDataContainer.PATHFINDER != null && TungstenModDataContainer.PATHFINDER.active.get());
		if (tungsten$wasDriving && !tungsten$driving) {
			var opts = MinecraftClient.getInstance().options;
			if (opts != null) {
				// Release ONLY the keys a task forces that the human player won't be holding
				// (sneak/attack/use). Leaving WASD/sprint/jump alone avoids clobbering the
				// user's own held movement on this single transition tick — while still
				// clearing the reported stuck SHIFT.
				opts.sneakKey.setPressed(false);
				opts.attackKey.setPressed(false);
				opts.useKey.setPressed(false);
			}
		}
		tungsten$wasDriving = tungsten$driving;

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
