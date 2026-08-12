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
		// TRIED: advancing the aim on the TICK path as well as the render frame. MEASURED WORSE,
		// REVERTED. The tick-vs-frame ratio is real (6 fps = 0.30 aim steps per game tick, so the
		// crosshair is unchanged on most gate checks), but acting on it here does not help:
		//     baseline            angle 83/113 (73%)  76/137 (55%)   landed 4, 5
		//     aim stepped on tick angle 70/87  (80%)  183/211 (87%)  landed 3, 3
		// Plausibly the extra calls fight WindMouse's own velocity/wind model — smaller, more
		// frequent steps are not the same motion as fewer larger ones. Whatever the reason, the
		// frequency story does not survive its own test.
		//
		// THREE aim changes have now measured flat or worse on this path: setTargetFast for melee,
		// a narrower bow release window, and this. Stop guessing at the aim. The datum nobody has
		// is what the yaw ACTUALLY does between a setTarget and the gate check — instrument that
		// residual before touching this again.
		//#if MC < 12111
		//$$ FollowEntityTask.tick(this.getWorld(), (ClientPlayerEntity)(Object)this);
		//$$ FollowPlayerTask.tick(this.getWorld(), (ClientPlayerEntity)(Object)this);
		//$$ PunkPlayerTask.tick(this.getWorld(), (ClientPlayerEntity)(Object)this);
		//$$ kaptainwutax.tungsten.task.RunAwayTask.tick(this.getWorld(), (ClientPlayerEntity)(Object)this);
		//#else
		// Damage accounting FIRST and unconditionally: altoclef's own tracker hangs off its chain
		// loop, which never runs on a course the agent drives with tungsten primitives — bow_flee
		// reported dmgTaken=0.0 hits=0/0/0/0 through five deaths.
		kaptainwutax.tungsten.combat.DamageWatch.tick((ClientPlayerEntity)(Object)this);
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

		// MEASURED WORSE, REVERTED. Ticking the executor whenever it merely HOLDS a route — armed
		// or not — is the obvious cure for the armed deadlock (an armed path is never ticked, and
		// only the tick can disarm it). It is also a regression: nav went 12/12 -> 9/12 with two
		// gate failures, because an armed path that used to WAIT now runs its expiry and replay
		// logic and takes the body from the walker that was bringing it to the root. Whatever the
		// right cure is, it is not "tick it anyway"; see C5.15.
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
		// ...BUT NOT WHILE A PORTED MOVEMENT OWNS THE TICK. That movement is the only writer
		// of the movement keys (Movement.update(): release everything, then press exactly what
		// this tick declared), and VoidGuard is a per-tick key writer that was never added to the
		// exemption the walker, bridge, pillar and executor all have above. So on every punk tick
		// it had the LAST word: at the lip of a PLANNED three-block drop it read back the
		// MOVE_FORWARD MovementFall had just pressed, measured fallHeight 4 one step ahead against
		// its hardcoded maxSafeFall of 3, released forward and forced SNEAK — and vanilla ledge
		// protection pinned the body on the lip until the queue's cost+100 timeout. Measured:
		// 25 of 26 chains, 161 ticks each, feet never leaving the source cell. Only drops of 3+
		// trip it, which is exactly why nothing else in the queue ever showed this.
		//
		// The guard is for FREE-FORM movement, as VoidDetector.edgeAhead says itself: pathfinder
		// moves are not gated by it, because they may descend deliberately. A queued step never
		// aims at the void — FastPlanner only emits a destination it proved standable.
		// KEEP MOVING WHILE THE NEXT PATH IS BEING COMPUTED. A flee search takes several ticks and
		// the executor has nothing to replay meanwhile, so the bot STANDS — free at nine blocks,
		// fatal at four. Measured on bow_flee: net displacement is only 41-57% of what sprinting
		// gives, and flee searches occupy 40-45% of the course; the two match, so the escape runs
		// at ~2.8 blocks/s against a SLOWED pursuer's ~4.8. Hence 23 hits a course at a mean gap of
		// 3.95 blocks, and an episode that ends only when the bot dies.
		//
		// HERE, not in RunAwayTask.tick, and that distinction is the whole point. The first attempt
		// drove these keys from the task, which ticks BEFORE MovementQueue and the walker — both of
		// which release every key and press their own. It measured 22 hits against 23 and was filed
		// as refuted; it had simply never run. That is pitfall P1, documented above, and this is the
		// established position for a final-word writer: after every owner, gated on the same
		// !movementOwnsTick exemption, and BEFORE VoidGuard so the guard can still veto a step off
		// the rim.
		if (kaptainwutax.tungsten.task.RunAwayTask.isActive()
				&& !tungsten$movementOwnsTick
				&& !TungstenModDataContainer.isExecutorRunning()
				&& !kaptainwutax.tungsten.task.BlockPathWalker.isRunning()) {
			kaptainwutax.tungsten.task.RunAwayTask.driveAwayRaw(this.getEntityWorld(), (ClientPlayerEntity)(Object)this);
		}

		// THE ARROW DODGE HAD THE SAME DEFECT AS THE FLEE KEYS ABOVE, AND NOBODY HAD MOVED IT YET.
		// MobDefenseChain pressed SPRINT/MOVE_FORWARD/JUMP from altoclef's task runner, which ticks
		// BEFORE MovementQueue and the walker -- and Movement.update() releases every key and
		// presses only what its own tick declared. So on every tick the walker drove the approach,
		// the dodge was erased before the game read it. Four dodge hypotheses were measured against
		// that and every one came back indistinguishable from baseline; none of them ever ran.
		//
		// UNLIKE the flee keys this is NOT gated on the movement exemptions. That exemption protects
		// a planned leg from a writer that would fight it for the whole leg; this one lasts about
		// six ticks and exists precisely to interrupt the approach while an arrow is in the air. The
		// queue's own timeout absorbs a few overridden ticks -- it cannot absorb walking into a shot.
		// ⛔ CORRECTION -- I WROTE "still BEFORE VoidGuard, so a sidestep can never carry the bot
		// off a rim" AND THAT IS FALSE. Being before the guard is worthless when the guard is
		// not ARMED: VoidGuard.protect below is gated on RunAwayTask || PunkPlayerTask ||
		// BowShooter, and ProjectileDodge is in none of them. So on any course where none of
		// those is active -- every mob course -- an arrow dodge drives sprint and direction keys
		// with NO void protection at all.
		//
		// It has not bitten because the mob arenas are flat fields, which is luck, not design.
		// The fix is to add ProjectileDodge.isActive() to that condition -- but the guard's own
		// comment warns against widening it carelessly (it must not arm during BridgeTask,
		// PillarTask or the walker, which stand at a rim ON PURPOSE), so it wants a measurement
		// on a course with an actual drop rather than a one-line edit made on sight.
		kaptainwutax.tungsten.task.ProjectileDodge.tick((ClientPlayerEntity)(Object)this);

		// BowShooter added 2026-08-09: THE RANGED PHASE HAD NO VOID GUARD AT ALL.
		// Sampling both guards every 15 s through an allround run:
		//     t+0..60    punkActive=0     vgCalls=0      <- seventy-five seconds unguarded
		//     t+75       punkActive=173   vgCalls=185
		//     t+90..120  punkActive=272   vgCalls=272
		// Neither guard covered the opening phase: PunkPlayerTask's own returns at `if (!active)`,
		// and this call site asked only for punk or runaway. That is precisely the phase the bot
		// spends kiting and backing away with the bow, and the bench attributes the falls
		// `SELF (walked off)` rather than knockback (scenario.py:161-166).
		// Deliberately NOT widened to `tungsten$driving`: that would arm the guard during
		// BridgeTask/PillarTask/the walker, which stand at a rim ON PURPOSE, and those courses were
		// not measured here. Cover the phase the evidence points at, nothing more.
		//
		// ⛔ MEASURED AFTERWARDS AND IT DID NOT HELP. allround, like-for-like (--no-early-stop):
		//     deaths=16 at 8.4 fps, samples=18   vs baseline 11 and 13 at 4.1-4.5 fps, samples=13-14
		// Normalising by run length (samples as the duration proxy) that is ~0.89 deaths/sample
		// against ~1.0 — no movement either way. The suite score did not move.
		// WHY IT PROBABLY CANNOT, and this was foreseeable from BowShooter's own lifecycle: `active`
		// is set by shootArrowAt and cleared by stop() on release/abort, while allround fires every
		// 2.5 s. So this covers the seconds AROUND each shot and leaves the gaps between them
		// unguarded — partial cover of the phase, not cover of it.
		// The next attempt should arm the guard for the whole time a combat TARGET is engaged rather
		// than per-shot, and must still keep BridgeTask/PillarTask/the walker out of it.
		// Also note: this run logged NO `fall:` lines at all despite 16 deaths, so its deaths were
		// not attributed by scenario.py:161-166 — do not reuse the earlier "SELF (walked off)"
		// attribution for this run without re-establishing it.
		// Exposure is counted here, not inside RunAwayTask, because the task returns early on
		// its hold and search paths -- so counting there measured only the ticks that reached
		// the bottom of it, and read 0 on a run whose closest approach was 2.72 blocks.
		kaptainwutax.tungsten.task.RunAwayTask.countExposure((ClientPlayerEntity)(Object)this);

		if ((kaptainwutax.tungsten.task.RunAwayTask.isActive()
				|| kaptainwutax.tungsten.task.PunkPlayerTask.isActive()
				|| kaptainwutax.tungsten.task.BowShooter.isActive())
				&& !tungsten$movementOwnsTick) {
			kaptainwutax.tungsten.combat.VoidGuard.protect((ClientPlayerEntity)(Object)this, this.getEntityWorld());
		}

		// Keys AFTER the guard: the state the player ticks with, not the state the drive asked for.
		kaptainwutax.tungsten.task.RunAwayTask.countKeysAfterGuard((ClientPlayerEntity)(Object)this);

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
		// "The target is done being used for this game tick" (LookBehavior.java:126). The motion
		// frame a ported Movement declared at HEAD has been consumed by travel()/updateVelocity by
		// now, and it must not survive into a tick nobody set it for.
		kaptainwutax.tungsten.path.movements.Movement.clearMotionFrame();
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
