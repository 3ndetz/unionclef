package kaptainwutax.tungsten.path;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import kaptainwutax.tungsten.agent.TungstenPlayerInput;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.List;

public class PathExecutor {

    protected List<Node> path;
    protected int tick = 0;
    protected boolean allowedFlying = false;
    public boolean stop = false;
    public Runnable cb = null;
    public long startTime;
    public List<BlockNode> blockPath = null;
    private boolean isClient;

    /** Passage cells to mine open once the replay reaches the end of the
     *  current path segment (set by PathFinder from the block path's break
     *  plan). The path is held "unfinished" while mining so the search
     *  thread's continuation machinery waits for the opened wall. */
    public List<net.minecraft.util.math.BlockPos> breakQueue = null;
    private int breakingTicks = 0;
    private int settleTicks = 0;

    /** Support cells to PLACE (bridge floor) once the replay reaches the segment end
     *  (set by PathFinder from the block path's place plan) — the mirror of breakQueue. */
    public List<net.minecraft.util.math.BlockPos> placeQueue = null;
    private int placingTicks = 0;

    public PathExecutor(boolean isClient) {
    	this.isClient = isClient;
    	try {
    		this.startTime = System.currentTimeMillis();
			if (isClient)
	        	this.allowedFlying = TungstenMod.mc.player.getAbilities().allowFlying;
		} catch (Exception e) {
			this.allowedFlying = true;
		}
	}

	/**
	 * A path may be rooted AHEAD of the player (the search is seeded at a future
	 * waypoint so it can compute while the walker is still travelling). Replaying
	 * such a path immediately is nonsense: the very first comparison sees a
	 * 20-block gap and aborts on drift, forever. So an out-of-reach path is held
	 * ARMED — the walker keeps driving — and replay begins when the bot actually
	 * arrives at the root.
	 */
	/**
	 * How close to the path root the bot must be before replay may start.
	 *
	 * <p>This used to be a fixed 2.0 while the executor ABORTS on a simulation drift of
	 * {@code driftThreshold} (0.8). Anything rooted between those two numbers was therefore
	 * NOT armed, began replaying immediately, and was killed by the drift check on tick 1 —
	 * a guaranteed-failure band. Observed on the parkour courses:
	 * {@code Path stopped: drift 1.723 blocks (threshold 0.8) at tick 1}, every single time.
	 *
	 * <p>Tied to the drift threshold now, and deliberately STRICTER than it, so replay can
	 * never begin already in violation of the rule that ends it.
	 */
	private static double armTolerance() {
		return TungstenConfig.get().driftThreshold * 0.5;
	}
	private boolean armed = false;

	public void setPath(List<Node> path) {
		// NOTE: the completion callback is deliberately PRESERVED. This used to do
		// `this.cb = null`, which destroyed the ;goto retry callback the moment the very
		// first physics path was emitted — so MAX_RETRIES never ran, "Finished!" never
		// fired, and a goto that needed more than one physics leg simply stopped forever.
		// addPath() has always preserved cb; this was a one-line asymmetry between them.
		this.startTime = System.currentTimeMillis();
		if (isClient)
			this.allowedFlying = TungstenMod.mc.player.getAbilities().allowFlying;
	    stop = false;
    	this.path = path;
    	this.tick = 0;
    	this.armed = false;
    	if (isClient && path != null && !path.isEmpty() && TungstenMod.mc.player != null) {
    		double toRoot = TungstenMod.mc.player.getEntityPos()
    				.distanceTo(path.get(0).agent.getPos());
    		// Arming exists for ONE reason: the walker is still travelling toward the root,
    		// so replaying now would compare against a position the bot has not reached yet.
    		// If the walker is NOT running, nobody is going to bring the bot there — arming
    		// is then a deadlock, not a wait. That is exactly what stalled every ladder run:
    		// the hand-off stops the walker, the physics path armed 2.2 blocks ahead, and
    		// both sides waited for each other until the navigator gave up.
    		if (toRoot > armTolerance() && kaptainwutax.tungsten.task.BlockPathWalker.isRunning()) {
    			this.armed = true;   // wait for the bot to reach the root
    			kaptainwutax.tungsten.Debug.logMessage(String.format(
    					"Path armed %.1f blocks ahead — walker drives until we reach it", toRoot));
    		}
    	}
    	RenderHelper.renderPathCurrentlyExecuted();
	}

	/** True while a spliced path waits for the bot to reach its root. */
	public boolean isArmed() { return armed; }
	
	public void addToPath(Node n) {
		this.path.add(n);
    	RenderHelper.renderPathCurrentlyExecuted();
	}
	
	public void addPath(List<Node> path) {
		if (stop) {
			setPath(path);
			return;
		}
		if (this.path == null) {
			setPath(path);
			return;
		}
		this.path.addAll(path);
    	RenderHelper.renderPathCurrentlyExecuted();
	}
	
	public List<Node> getPath() {
		return this.path;
	}
	
	public Node getCurrentNode() {
		// EMPTY path (e.g. "mining without a physics leg" — a break with no movement
		// nodes) must not index get(size-1)==get(-1) -> IndexOutOfBounds crashes the
		// whole client tick. Return null; callers already null-check.
		if (this.path == null || this.path.isEmpty()) return null;
		if (this.tick >= this.path.size()) return this.path.get(this.path.size()-1);
		return this.path.get(this.tick);
	}
	

	public int getCurrentTick() {
		return this.tick;
	}


	public boolean isRunning() {
        // An ARMED path is waiting, not running: while it waits the walker must
        // keep driving (and callers that stand down for "the executor is busy"
        // must not stand down), otherwise nothing moves the bot to the root and
        // the splice can never start.
        return this.path != null && !this.armed && this.tick <= this.path.size();
    }


    // Server-side tick disabled: requires ServerPlayerEntity.setPlayerInput() (MC 1.21.4+ only)
    // public void tick(ServerPlayerEntity player) { ... }
    
    public void tick(ClientPlayerEntity player, GameOptions options) {
    	player.getAbilities().allowFlying = false;
    	if(TungstenMod.pauseKeyBinding.isPressed() || stop) {
    		// A MINING/BRIDGING segment runs with an EMPTY path (the "At the wall" and
    		// "At the gap" shortcuts): there is no recorded replay, so a drift abort —
    		// which is a statement about the REPLAY diverging from reality — has nothing
    		// to say about it. Letting `stop` fall through here wiped the whole queue,
    		// silently, and that is what made nav_break start mining and then do nothing.
    		//
    		// The abort itself is left ALONE: weakening it on the Agent side regressed
    		// nav_gaps from a stable 6/6 to failing, because the parkour hand-off depends
    		// on it firing. Only the consequence is narrowed, here, where the distinction
    		// between "abandon a replay" and "abandon the work" actually lives.
    		boolean replayInProgress = this.path != null && !this.path.isEmpty();
    		boolean explicitStop = TungstenMod.pauseKeyBinding.isPressed();
    		if (!replayInProgress && !explicitStop && (breakQueue != null || placeQueue != null)) {
    			stop = false;                 // consume the flag, keep doing the real work
    			// fall through to the normal tick so tickBreaking/tickPlacing can run
    		} else {
    		if (breakQueue != null) {
    			// Never discard a mining plan silently — that hid the nav_break failure for
    			// a whole session (mining started, then simply ceased to exist).
    			Debug.logMessage("Mining cancelled by stop flag (" + breakQueue.size() + " block(s) left)");
    			MinecraftClient.getInstance().interactionManager.cancelBlockBreaking();
    			TungstenModRenderContainer.BREAK_PLAN.clear();
    			breakQueue = null; breakingTicks = 0; settleTicks = 0;
    		}
    		if (placeQueue != null) { placeQueue = null; placingTicks = 0; }
    		// A stop mid-mine must release the attack key and the aim immediately —
    		// otherwise the bot keeps swinging and the camera stays locked on the
    		// block until the stale-aim timeout (part of the #29 frozen-camera fix).
    		options.attackKey.setPressed(false);
    		kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
    		this.tick = this.path.size();
    		// player.input.playerInput = ... // MC 1.21: Input has no playerInput field
		    options.forwardKey.setPressed(false);
		    options.backKey.setPressed(false);
		    options.leftKey.setPressed(false);
		    options.rightKey.setPressed(false);
		    options.jumpKey.setPressed(false);
		    options.sneakKey.setPressed(false);
		    options.sprintKey.setPressed(false);
		    player.getAbilities().allowFlying = allowedFlying;
		    this.path = null;
		    stop = false;
		    TungstenModRenderContainer.RUNNING_PATH_RENDERER.clear();
		    TungstenModRenderContainer.BLOCK_PATH_RENDERER.clear();
    		return;
    		}
    	}
    	// ARMED: this path starts ahead of us. Do not replay it (and do not touch
    	// the movement keys — the walker owns them until we get there). Start the
    	// moment the bot is at the root; give up if it never arrives, so a stale
    	// splice cannot pin the executor forever.
    	if (this.armed) {
    		double toRoot = player.getEntityPos().distanceTo(this.path.get(0).agent.getPos());
    		if (toRoot <= armTolerance()) {
    			this.armed = false;
    			this.startTime = System.currentTimeMillis();
    			kaptainwutax.tungsten.Debug.logMessage("Path armed -> replaying (reached root)");
    		} else {
    			if (System.currentTimeMillis() - this.startTime > 15000) {
    				kaptainwutax.tungsten.Debug.logMessage(
    						"Armed path expired (never reached its root) — dropping it");
    				this.path = null;
    				this.armed = false;
    			}
    			return;
    		}
    	}

    	if(this.tick == this.path.size()) {
    		// mine the planned wall before declaring the segment finished —
    		// the continuation search / goto retry then sees the opened world
    		if (tickBreaking(player, options)) {
    			return;
    		}
    		// pave the planned bridge floor before finishing the segment — the
    		// continuation search then sees the now-bridged world (mirror of breaking)
    		if (tickPlacing(player, options)) {
    			return;
    		}
    		long endTime = System.currentTimeMillis();
    		long elapsedTime = endTime - startTime;
    		long minutes = (elapsedTime / 1000) / 60;
            long seconds = (elapsedTime / 1000) % 60;
            long milliseconds = elapsedTime % 1000;
            
            Debug.logMessage("Time taken to execute: " + minutes + " minutes, " + seconds + " seconds, " + milliseconds + " milliseconds");
    		
		    options.forwardKey.setPressed(false);
		    options.backKey.setPressed(false);
		    options.leftKey.setPressed(false);
		    options.rightKey.setPressed(false);
		    options.jumpKey.setPressed(false);
		    options.sneakKey.setPressed(false);
		    options.sprintKey.setPressed(false);
		    player.getAbilities().allowFlying = allowedFlying;
		    this.path = null;
		    stop = false;
		    TungstenModRenderContainer.RUNNING_PATH_RENDERER.clear();
		    TungstenModRenderContainer.BLOCK_PATH_RENDERER.clear();
		    if (cb != null) {
		    	cb.run();
		    	cb = null;
		    }
	    } else {
		    Node node = this.path.get(this.tick);

		    // Drift detection is handled post-tick in MixinClientPlayerEntity.end()
		    // via Agent.compare() — it correctly compares AFTER vanilla processes
		    // the inputs, so the positions are comparable.

		    if(node.input != null) {
			    float targetYaw = node.input.yaw;
			    float targetPitch = TungstenConfig.get().enablePitchChange
			            ? calculateLookAheadPitch(node)
			            : node.input.pitch;

			    if (TungstenConfig.get().enableNativeRotation) {
			        applyNativeRotation(player, targetYaw, targetPitch);
			    } else {
			        player.setYaw(targetYaw);
			        player.setPitch(targetPitch);
			    }
			    // player.stopGliding() removed in MC 1.21
	    		options.forwardKey.setPressed(node.input.forward);
			    options.backKey.setPressed(node.input.back);
			    options.leftKey.setPressed(node.input.left);
			    options.rightKey.setPressed(node.input.right);
			    options.jumpKey.setPressed(node.input.jump);
			    options.sneakKey.setPressed(node.input.sneak);
			    options.sprintKey.setPressed(node.input.sprint);
		    }
//		    if(this.tick != 0 && options != null) {
//			    this.path.get(this.tick - 1).agent.compare(player, optionsToPlayerInput(options), true);
//		    }
		    int idx = TungstenModRenderContainer.RUNNING_PATH_RENDERER.size()-1;
		    if (!TungstenModRenderContainer.RUNNING_PATH_RENDERER.isEmpty() && this.tick != 0) {
		    	try {
			    	TungstenModRenderContainer.RUNNING_PATH_RENDERER.remove(TungstenModRenderContainer.RUNNING_PATH_RENDERER.toArray()[idx]);
			    	if (TungstenMod.renderPositonBoxes && TungstenModRenderContainer.RUNNING_PATH_RENDERER.size() > 1) {
			    		TungstenModRenderContainer.RUNNING_PATH_RENDERER.remove(TungstenModRenderContainer.RUNNING_PATH_RENDERER.toArray()[idx-1]);
			    	}
				} catch (Exception e) {
					// TODO: handle exception
				}
		    }
	    }
	    this.tick++;
    }


    /**
     * Mine the queued passage cells open. Returns true while mining is in
     * progress (the caller must not finish the path). Targets the first
     * still-solid cell, so gravity blocks that fall into the passage get
     * re-mined; after everything is passable it lingers a few ticks to let
     * falling blocks settle before declaring done.
     */
    private boolean tickBreaking(ClientPlayerEntity player, GameOptions options) {
        if (breakQueue == null || breakQueue.isEmpty()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        var world = player.getEntityWorld();

        net.minecraft.util.math.BlockPos target = null;
        for (net.minecraft.util.math.BlockPos pos : breakQueue) {
            if (kaptainwutax.tungsten.helpers.BlockShapeChecker.getShapeVolume(pos, world) > 0) {
                target = pos;
                break;
            }
        }
        if (target == null) {
            if (settleTicks++ < 12) { // wait for sand/gravel to land
                releaseMovementKeys(options);
                options.attackKey.setPressed(false);
                return true;
            }
            Debug.logMessage("Mining done — passage open");
            options.attackKey.setPressed(false);
            TungstenModRenderContainer.BREAK_PLAN.clear();
            breakQueue = null; breakingTicks = 0; settleTicks = 0; kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            resumeGotoAfterMining(player);
            return false;
        }
        settleTicks = 0;

        // Re-check the policy against the LIVE world every tick — zones/hooks
        // can change and the plan may be stale.
        if (!BreakRules.canBreak(player.getEntityWorld(), target,
                player.getEntityWorld().getBlockState(target))) {
            Debug.logMessage("Mining aborted (denied by break rules)");
            options.attackKey.setPressed(false);
            mc.interactionManager.cancelBlockBreaking();
            TungstenModRenderContainer.BREAK_PLAN.clear();
            breakQueue = null; breakingTicks = 0; settleTicks = 0; kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            return false;
        }

        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        if (breakingTicks++ > 300 || eye.squaredDistanceTo(center) > 4.5 * 4.5) {
            Debug.logMessage("Mining aborted (timeout or out of reach)");
            options.attackKey.setPressed(false);
            mc.interactionManager.cancelBlockBreaking();
            TungstenModRenderContainer.BREAK_PLAN.clear();
            breakQueue = null; breakingTicks = 0; settleTicks = 0; kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            return false;
        }

        // Visualize the plan: queued blocks orange, the one being mined red.
        TungstenModRenderContainer.BREAK_PLAN.clear();
        for (net.minecraft.util.math.BlockPos pos : breakQueue) {
            boolean current = pos.equals(target);
            TungstenModRenderContainer.BREAK_PLAN.add(new kaptainwutax.tungsten.render.Cuboid(
                    new Vec3d(pos.getX(), pos.getY(), pos.getZ()).add(current ? -0.02 : 0.05, current ? -0.02 : 0.05, current ? -0.02 : 0.05),
                    current ? new Vec3d(1.04, 1.04, 1.04) : new Vec3d(0.9, 0.9, 0.9),
                    current ? new kaptainwutax.tungsten.render.Color(255, 60, 40)
                            : new kaptainwutax.tungsten.render.Color(255, 170, 40)));
        }

        releaseMovementKeys(options);
        // Inventory side (altoclef) equips the best tool for this block; the
        // hook must never be able to break mining.
        if (kaptainwutax.tungsten.TungstenModDataContainer.equipToolHook != null) {
            try {
                kaptainwutax.tungsten.TungstenModDataContainer.equipToolHook
                        .accept(target, player.getEntityWorld().getBlockState(target));
            } catch (Throwable ignored) {}
        }
        // Turn toward the block smoothly (no gaze teleport) and HOLD the attack
        // key only once the crosshair is actually on it — vanilla
        // handleBlockBreaking then drives the mining against crosshairTarget.
        // (Direct updateBlockBreakingProgress does not work: with the key up,
        // vanilla cancels the breaking progress every tick.)
        Vec3d d = center.subtract(eye);
        float wantYaw = (float) Math.toDegrees(-Math.atan2(d.x, d.z));
        float wantPitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        // Humanized aim via WindMouse (mouse pipeline) — no setYaw/setPitch that
        // anti-cheats flag. Attack only once the crosshair has actually reached
        // the block (read the real, WindMouse-converged rotation).
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(wantYaw, wantPitch);
        float dYaw = net.minecraft.util.math.MathHelper.wrapDegrees(wantYaw - player.getYaw());
        float dPitch = net.minecraft.util.math.MathHelper.wrapDegrees(wantPitch - player.getPitch());
        boolean aimed = Math.abs(dYaw) < 12f && Math.abs(dPitch) < 12f;
        options.attackKey.setPressed(aimed);
        return true;
    }

    /**
     * Pave the queued bridge-floor supports — the mirror of tickBreaking. Returns true
     * while placing is in progress (segment must not finish). Places the first still-air
     * support against an adjacent solid face; once all are solid it resumes the goto so
     * the continuation search sees the bridged world. The caller (altoclef) equips a
     * block; tungsten does not depend on the inventory layer.
     */
    private boolean tickPlacing(ClientPlayerEntity player, GameOptions options) {
        if (placeQueue == null || placeQueue.isEmpty()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        var world = player.getEntityWorld();

        net.minecraft.util.math.BlockPos target = null;
        for (net.minecraft.util.math.BlockPos pos : placeQueue) {
            if (kaptainwutax.tungsten.helpers.BlockShapeChecker.getShapeVolume(pos, world) == 0) { target = pos; break; }
        }
        if (target == null) {                       // all placed — bridge floor is in
            options.useKey.setPressed(false);
            TungstenModRenderContainer.PLACE_PLAN.clear();
            placeQueue = null; placingTicks = 0;
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            resumeGotoAfterMining(player);
            return false;
        }
        // ask altoclef to equip a build block (tungsten never touches the inventory)
        if (kaptainwutax.tungsten.TungstenModDataContainer.equipBlockHook != null) {
            try { kaptainwutax.tungsten.TungstenModDataContainer.equipBlockHook.run(); } catch (Throwable ignored) {}
        }
        if (!(player.getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem)) {
            Debug.logMessage("Bridge place aborted (no block in hand)");
            options.useKey.setPressed(false);
            placeQueue = null; placingTicks = 0;
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            return false;
        }
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        if (placingTicks++ > 200 || eye.squaredDistanceTo(center) > 5.5 * 5.5) {
            Debug.logMessage("Bridge place aborted (timeout or out of reach)");
            options.useKey.setPressed(false);
            TungstenModRenderContainer.PLACE_PLAN.clear();
            placeQueue = null; placingTicks = 0;
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            return false;
        }
        if (!kaptainwutax.tungsten.path.PlaceRules.canPlace(world, target)) {
            Debug.logMessage("Bridge place aborted (denied by place rules)");
            options.useKey.setPressed(false);
            placeQueue = null; placingTicks = 0;
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            return false;
        }
        // find a solid neighbour to place against (its face toward the target)
        net.minecraft.util.math.BlockPos against = null;
        net.minecraft.util.math.Direction side = null;
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            net.minecraft.util.math.BlockPos n = target.offset(dir);
            if (kaptainwutax.tungsten.helpers.BlockShapeChecker.getShapeVolume(n, world) > 0) {
                against = n; side = dir.getOpposite(); break;
            }
        }
        if (against == null) return true;           // no support yet — wait a tick

        releaseMovementKeys(options);
        options.attackKey.setPressed(false);
        Vec3d faceCenter = Vec3d.ofCenter(against).add(Vec3d.of(side.getVector()).multiply(0.5));
        Vec3d d = faceCenter.subtract(eye);
        float wantYaw = (float) Math.toDegrees(-Math.atan2(d.x, d.z));
        float wantPitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(wantYaw, wantPitch);
        // visualize the cell being paved (green)
        TungstenModRenderContainer.PLACE_PLAN.clear();
        TungstenModRenderContainer.PLACE_PLAN.add(new kaptainwutax.tungsten.render.Cuboid(
                new Vec3d(target.getX() + 0.1, target.getY() + 0.1, target.getZ() + 0.1),
                new Vec3d(0.8, 0.8, 0.8), new kaptainwutax.tungsten.render.Color(60, 220, 120)));
        float dYaw = net.minecraft.util.math.MathHelper.wrapDegrees(wantYaw - player.getYaw());
        float dPitch = net.minecraft.util.math.MathHelper.wrapDegrees(wantPitch - player.getPitch());
        if (Math.abs(dYaw) < 15f && Math.abs(dPitch) < 15f) {   // place only once aimed
            net.minecraft.util.hit.BlockHitResult hit =
                    new net.minecraft.util.hit.BlockHitResult(faceCenter, side, against, false);
            mc.interactionManager.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hit);
            player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
        return true;
    }

    /**
     * Seamless continuation: the wall is open, the goto target is still far —
     * restart the search immediately instead of waiting on the retry chain
     * (which sleeps and polls; the visible "task died after mining" gap).
     */
    private void resumeGotoAfterMining(ClientPlayerEntity player) {
        Vec3d goal = TungstenMod.TARGET;
        if (goal == null || player.getEntityPos().distanceTo(goal) < 2.0) return;
        new Thread(() -> {
            try {
                TungstenModDataContainer.PATHFINDER.stop.set(true);
                for (int i = 0; i < 20 && TungstenModDataContainer.PATHFINDER.thread != null; i++) {
                    Thread.sleep(250);
                }
                TungstenModDataContainer.PATHFINDER.stop.set(false);
                TungstenModDataContainer.PATHFINDER.find(player.getEntityWorld(), goal, player);
            } catch (Throwable ignored) {}
        }, "tungsten-mining-resume").start();
    }

    private static void releaseMovementKeys(GameOptions options) {
        options.forwardKey.setPressed(false);
        options.backKey.setPressed(false);
        options.leftKey.setPressed(false);
        options.rightKey.setPressed(false);
        options.jumpKey.setPressed(false);
        options.sneakKey.setPressed(false);
        options.sprintKey.setPressed(false);
    }

    /**
     * Apply rotation via pixel-quantized changeLookDirection.
     * Converts degree deltas to integer mouse pixels and back,
     * making the rotation indistinguishable from a physical mouse.
     */
    private static void applyNativeRotation(ClientPlayerEntity player, float targetYaw, float targetPitch) {
        double deltaYaw = targetYaw - player.getYaw();
        double deltaPitch = targetPitch - player.getPitch();

        double sens = MinecraftClient.getInstance().options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        double sensScale = f * f * f * 8.0;
        double degreesPerPixel = sensScale * 0.15;

        long pixelsX = Math.round(deltaYaw / degreesPerPixel);
        long pixelsY = Math.round(deltaPitch / degreesPerPixel);

        player.changeLookDirection(pixelsX * sensScale, pixelsY * sensScale);
    }

    /**
     * Look a few nodes ahead in the path and compute the pitch angle
     * from the current node toward that future position. Clamps to
     * [-90, 90] like vanilla.
     *
     * Returns the node's original pitch when the move intentionally set it
     * (swimming, climbing) — detected by checking whether the node's pitch
     * differs from its parent's. In those cases overriding pitch would
     * break the physics that depend on it.
     */
    private float calculateLookAheadPitch(Node currentNode) {
        if (this.path == null) return currentNode.input.pitch;

        // If the move explicitly changed pitch (swimming, climbing),
        // respect the pathfinder's value — it affects physics.
        if (currentNode.parent != null
                && Math.abs(currentNode.input.pitch - currentNode.parent.agent.pitch) > 0.01F) {
            return currentNode.input.pitch;
        }

        int ahead = TungstenConfig.get().pitchLookAheadNodes;
        int targetIdx = Math.min(this.tick + ahead, this.path.size() - 1);

        if (targetIdx <= this.tick) return currentNode.input.pitch;

        Vec3d from = currentNode.agent.getPos().add(0, currentNode.agent.standingEyeHeight, 0);
        Vec3d to = this.path.get(targetIdx).agent.getPos();

        float pitch = (float) DirectionHelper.calcPitchFromVec3d(from, to);
        return net.minecraft.util.math.MathHelper.clamp(pitch, -90.0F, 90.0F);
    }

    public static TungstenPlayerInput optionsToPlayerInput(GameOptions options) {
    	return new TungstenPlayerInput(options.forwardKey.isPressed(), options.backKey.isPressed(), options.leftKey.isPressed(), options.rightKey.isPressed(), options.jumpKey.isPressed(), options.sneakKey.isPressed(), options.sprintKey.isPressed());
    }

}
