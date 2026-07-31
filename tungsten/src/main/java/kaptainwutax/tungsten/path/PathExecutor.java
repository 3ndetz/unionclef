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

    /**
     * HAND THE EXECUTOR A NEW BREAK JOB. Use this instead of assigning {@link #breakQueue}.
     *
     * <p>{@code breakingTicks} is the mining watchdog: past 300 the current block is abandoned
     * with "Mining aborted (timeout or out of reach)". It is reset only when a job FINISHES or
     * aborts inside this class — so a caller that dropped a fresh list into the field inherited
     * whatever the previous job left behind, and if that was already over the limit the new job
     * was aborted on its very first tick.
     *
     * <p>Measured on //replace, which polls and re-issues: it refilled the queue, the executor
     * aborted it instantly, it refilled again — for as long as the caller was willing to wait.
     * Three cells, `remaining: 3` after seventy polls, nothing mined. It escaped the loop only
     * when the counter happened to be low at the start, which is why the test passed one run in
     * three rather than never.
     *
     * <p>Eight call sites assigned the field directly. They all come here now, which is the only
     * way this cannot happen again.
     */
    public void startBreaking(java.util.List<net.minecraft.util.math.BlockPos> blocks) {
        breakQueue = blocks == null ? null : new java.util.ArrayList<>(blocks);
        breakingTicks = 0;
        settleTicks = 0;
        stop = false;
    }

    /** Support cells to PLACE (bridge floor) once the replay reaches the segment end
     *  (set by PathFinder from the block path's place plan) — the mirror of breakQueue. */
    public List<net.minecraft.util.math.BlockPos> placeQueue = null;
    /**
     * The placer is aiming RIGHT NOW and owns the camera. Baritone has exactly one thing
     * steering at a time — the movement sets a MovementTarget and LookBehavior applies it —
     * while tungsten had the walker re-aiming at its waypoint every tick underneath the
     * placer. That never mattered before because the placement was forged and ignored the
     * camera entirely; now that it goes through the real ray trace, the aim must converge.
     */
    public volatile boolean placingNow = false;
    public static volatile int placeCalled=0, placeDeferred=0, placeInRange=0, placeClicked=0;
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

        // "STILL THERE?" IS NOT A COLLISION-VOLUME QUESTION. This asked getShapeVolume > 0, so
        // every block with an empty collision shape — grass, torches, flowers, snow layers,
        // cobwebs — was skipped as if already gone, and the queue then reported "Mining done"
        // having mined nothing: register entry C5.4. Baritone asks whether the cell can be
        // WALKED THROUGH, which is the question that actually decides whether a dig is needed,
        // and that predicate came over with the port (MovementHelperB.canWalkThrough, from
        // MovementHelper.java:187-195 with its NO-list of exactly these blocks).
        net.minecraft.util.math.BlockPos target = null;
        for (net.minecraft.util.math.BlockPos pos : breakQueue) {
            if (!kaptainwutax.tungsten.path.movements.MovementHelperB.canWalkThrough(
                    world, pos.getX(), pos.getY(), pos.getZ())) {
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
        // MINE THE BLOCK IN THE PLAN, NOT WHATEVER IS WITHIN 12 DEGREES. The gate was an ANGLE
        // test, and vanilla's handleBlockBreaking then mines whatever the CROSSHAIR is on — so
        // any block nearer along the ray gets dug instead, and BreakRules were checked against
        // the planned cell while a different one was destroyed. That is register entry C5.3, and
        // it is the same defect the placement side had: an approximation standing in for the
        // game's own ray trace. Baritone gates on ctx.isLookingAt(pos) for exactly this reason.
        //
        // The aim above is unchanged; only the trigger is now identity rather than proximity.
        var look = mc.crosshairTarget;
        boolean onTarget = look instanceof net.minecraft.util.hit.BlockHitResult bhr
                && look.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(target);
        options.attackKey.setPressed(onTarget);
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
        // Counters over py4j: does the bot ever ARRIVE at a bridge point, or does it spend
        // the whole run deferring? The chat cannot answer this — it floods on these courses.
        placeCalled++;
        MinecraftClient mc = MinecraftClient.getInstance();
        var world = player.getEntityWorld();

        net.minecraft.util.math.BlockPos target = null;
        for (net.minecraft.util.math.BlockPos pos : placeQueue) {
            if (kaptainwutax.tungsten.helpers.BlockShapeChecker.getShapeVolume(pos, world) == 0) { target = pos; break; }
        }
        if (target == null) {                       // all placed — bridge floor is in
            options.useKey.setPressed(false);
            options.sneakKey.setPressed(false);
            placingNow = false;
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
            options.sneakKey.setPressed(false);
            placingNow = false;
            placeQueue = null; placingTicks = 0;
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            return false;
        }
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        // ONE MESSAGE FOR TWO CAUSES TELLS YOU NOTHING. "timeout or out of reach" cannot
        // distinguish "the bot never arrived" from "it arrived and the placement stalled",
        // and those need opposite fixes. Say which, and how far.
        double placeDist = Math.sqrt(eye.squaredDistanceTo(center));
        // TOO FAR IS "NOT YET", NOT "GIVE UP". The place queue is armed the moment a path is
        // handed over, and the bot is normally still walking TOWARDS the gap — so throwing the
        // plan away on distance destroyed every bridge before it could be built. Measured:
        // "aborted (OUT OF REACH) dist=13.73 ticks=1", i.e. abandoned on the very first tick
        // while the route to that spot was still being walked. Wait instead, and only count
        // the timeout once we are actually in range, so a long approach cannot expire it.
        if (placeDist > 5.5) {
            placeDeferred++;
            placingNow = false;                 // still walking there — the walker steers
            return false;                       // keep the queue; we are on our way there
        }
        placeInRange++;
        if (placingTicks++ > 200) {
            Debug.logMessage(String.format(
                    "Bridge place aborted (TIMEOUT) dist=%.2f ticks=%d target=%s",
                    placeDist, placingTicks, target.toShortString()));
            options.useKey.setPressed(false);
            options.sneakKey.setPressed(false);
            placingNow = false;
            TungstenModRenderContainer.PLACE_PLAN.clear();
            placeQueue = null; placingTicks = 0;
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            return false;
        }
        if (!kaptainwutax.tungsten.path.PlaceRules.canPlace(world, target)) {
            Debug.logMessage("Bridge place aborted (denied by place rules)");
            options.useKey.setPressed(false);
            options.sneakKey.setPressed(false);
            placingNow = false;
            placeQueue = null; placingTicks = 0;
            kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
            return false;
        }
        // find a solid neighbour to place against (its face toward the target)
        net.minecraft.util.math.BlockPos against = null;
        net.minecraft.util.math.Direction side = null;
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            net.minecraft.util.math.BlockPos n = target.offset(dir);
            // Stricter than "the collision shape is not empty" — RealPlacement.canPlaceAgainst
            // forwards to MovementHelperB's faithful port (protection, world border, blacklist),
            // ported from baritone: the question is whether a side face can actually be clicked.
            if (kaptainwutax.tungsten.helpers.RealPlacement.canPlaceAgainst(world, n)) {
                against = n; side = dir.getOpposite(); break;
            }
        }
        if (against == null) return true;           // no support yet — wait a tick

        options.attackKey.setPressed(false);
        // SNEAK WHILE BRIDGING — PORTED FROM BARITONE, NOT INVENTED. MovementTraverse holds
        // SNEAK the moment it is close to the cell it is paving and only clicks once the
        // player is actually in the sneaking pose; its cost function even prices the manoeuvre
        // separately (SNEAK_ONE_BLOCK_COST) because a backplace IS a sneak. Tungsten placed
        // without it, and releasing the movement keys does not cancel momentum: the bot slid
        // off the lip it was paving from and fell. Measured on nav_slime, twice in a row,
        // 20.7 blocks short — the void-fall signature. Sneaking is what makes an edge safe in
        // vanilla, so hold it whenever the block we are laying is BELOW our feet and we are
        // standing over it, and do not click until the pose has actually taken effect.
        boolean bridging = target.getY() < net.minecraft.util.math.MathHelper.floor(player.getY());
        double edgeDist = Math.max(Math.abs(player.getX() - (target.getX() + 0.5)),
                                   Math.abs(player.getZ() - (target.getZ() + 0.5)));
        boolean sneakToPlace = bridging && edgeDist < 1.6;
        options.sneakKey.setPressed(sneakToPlace);
        Vec3d faceCenter = Vec3d.ofCenter(against).add(Vec3d.of(side.getVector()).multiply(0.5));
        Vec3d d = faceCenter.subtract(eye);
        float wantYaw = (float) Math.toDegrees(-Math.atan2(d.x, d.z));
        float wantPitch = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));

        // THE BACKPLACE MANOEUVRE, ported from MovementTraverse.updateState
        // (baritone/.../movements/MovementTraverse.java:336-350). Only the CLICK was ported
        // before, and the click alone cannot work: standing ON a block, a ray towards that
        // block's side face hits its TOP face first, so the crosshair test can never pass and
        // nav_bridge failed at 11.6 twice over once the forged hit result was removed.
        //
        // Upstream turns ROUND. It faces back the way it came, looks down at the face of the
        // block it was just standing on, presses MOVE_BACK — which carries it forward along
        // the bridge while still looking at the face — and sneaks so stepping over the empty
        // cell does not become a fall. The new block appears beneath it. That is the whole
        // trick, and it is why the bot must walk BACKWARDS to bridge.
        // THE PLACER TAKES THE BODY ONLY WHEN THE BODY IS IN THE RIGHT CELL. Owning it from
        // 5.5 blocks out froze the bot short of the lip and it then tried to place from there:
        // "PLACEAIM want=13,-61 against=12,-61 pos=(11.43,0.62) pitch=53 hit=12,-61 side=up",
        // i.e. standing a block and a half back, aiming at the wrong face, forever — 336 ticks
        // in range and not one click. Upstream never has this problem because WALKING is a
        // separate movement that finishes first; the placement runs when the bot is already in
        // the cell it places from. So: while the bot is not standing on the block it will click,
        // keep hands off and let the walker deliver it.
        net.minecraft.util.math.BlockPos feetCell = player.getBlockPos();
        boolean onAgainst = feetCell.getX() == against.getX() && feetCell.getZ() == against.getZ()
                && feetCell.getY() == against.getY() + 1;
        placingNow = onAgainst;
        if (!onAgainst) {
            if (kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging
                    && (placingTicks % 20 == 0)) {
                Debug.logMessage(String.format(
                        "PLACEWAIT want=%s against=%s feet=%s pos=(%.2f,%.2f) onGround=%b",
                        target.toShortString(), against.toShortString(),
                        feetCell.toShortString(), player.getX(), player.getZ(),
                        player.isOnGround()));
            }
            return true;                        // still being walked there — do not touch it
        }

        if (sneakToPlace) {
            Vec3d destCentre = Vec3d.ofCenter(target);
            // yaw FROM the cell being paved TOWARDS the head — i.e. facing back up the bridge.
            Vec3d back = eye.subtract(destCentre);
            wantYaw = (float) Math.toDegrees(-Math.atan2(back.x, back.z));
            releaseMovementKeys(options);
            options.backKey.setPressed(true);     // MOVE_BACK: forward in world, facing back
            options.sneakKey.setPressed(true);
        } else {
            releaseMovementKeys(options);
        }
        kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.setTarget(wantYaw, wantPitch);
        // visualize the cell being paved (green)
        TungstenModRenderContainer.PLACE_PLAN.clear();
        TungstenModRenderContainer.PLACE_PLAN.add(new kaptainwutax.tungsten.render.Cuboid(
                new Vec3d(target.getX() + 0.1, target.getY() + 0.1, target.getZ() + 0.1),
                new Vec3d(0.8, 0.8, 0.8), new kaptainwutax.tungsten.render.Color(60, 220, 120)));
        float dYaw = net.minecraft.util.math.MathHelper.wrapDegrees(wantYaw - player.getYaw());
        float dPitch = net.minecraft.util.math.MathHelper.wrapDegrees(wantPitch - player.getPitch());
        // PLACE THROUGH THE GAME'S OWN RAY TRACE. What stood here forged a BlockHitResult
        // out of the face centre and handed it to interactBlock, so the packet claimed the
        // player had clicked a face the player was never looking at — blocks appeared through
        // block edges with the camera pointing elsewhere. It even said so in a comment: "the
        // camera is cosmetic here". It is not cosmetic, it is the whole interaction.
        //
        // Ported from baritone's MovementHelper.attemptToPlaceABlock
        // (baritone/.../MovementHelper.java:806-856): aim at the face, then accept only when
        // the player's REAL crosshair lands somewhere that would produce the wanted block,
        // and place with THAT hit result. If the aim never converges the placement does not
        // happen — which is a bug to fix in the aim, not to paper over with a forged packet.
        var realHit = kaptainwutax.tungsten.helpers.RealPlacement.readyToPlace(mc, target);
        // WHAT IS THE CROSSHAIR ACTUALLY HITTING? This should have been the FIRST thing logged,
        // not the fourth: three attempts were spent on aim ownership, the manoeuvre and key
        // ownership while "clicked=0" could have been explained by one line.
        if (realHit == null && kaptainwutax.tungsten.TungstenConfig.get().verboseDebugLogging
                && (placingTicks % 20 == 0)) {
            var ct = mc.crosshairTarget;
            String what = "null";
            if (ct instanceof net.minecraft.util.hit.BlockHitResult b
                    && ct.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                what = b.getBlockPos().toShortString() + " side=" + b.getSide()
                        + " -> would fill " + b.getBlockPos().offset(b.getSide()).toShortString();
            } else if (ct != null) {
                what = String.valueOf(ct.getType());
            }
            Debug.logMessage(String.format(
                    "PLACEAIM want=%s against=%s side=%s pos=(%.2f,%.2f) pitch=%.0f/%.0f hit=%s",
                    target.toShortString(), against.toShortString(), side,
                    player.getX(), player.getZ(), player.getPitch(), wantPitch, what));
        }
        // ...and at the rate a player can place: this runs once per client tick, so without the
        // shared gate it placed 20 blocks a second, four times what holding the use key does.
        if (realHit != null && (!sneakToPlace || player.isInSneakingPose())
                && kaptainwutax.tungsten.helpers.BlockPlaceHelper.tryPlace(realHit)) {
            placeClicked++;
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
        // THE NAVIGATOR DOES NOT NEED THE PHYSICS THREAD DEAD. Waiting for it costs up to
        // FIVE SECONDS per resume, and a bridge is a loop: place, resume, walk, place. That
        // wait is why a sixty-second run managed one or two blocks — most of it was spent
        // watching a search thread it was not going to use. Wait only when physics is the one
        // that will drive.
        if (kaptainwutax.tungsten.TungstenConfig.get().fastBlockFirst) {
            kaptainwutax.tungsten.task.FastNavigator.start(goal);
            return;
        }
        new Thread(() -> {
            try {
                TungstenModDataContainer.PATHFINDER.stop.set(true);
                for (int i = 0; i < 20 && TungstenModDataContainer.PATHFINDER.thread != null; i++) {
                    Thread.sleep(250);
                }
                TungstenModDataContainer.PATHFINDER.stop.set(false);
                // RESUME THROUGH THE ROUTE'S OWNER. This restarted the PHYSICS search on the
                // final goal, bypassing the navigator — the same mistake ;goto used to make.
                // On any route physics cannot solve it burns its full budget before giving up,
                // so a bridge that needs many blocks got one or two placements in a whole run:
                // place, hand the whole route to physics, wait 20 s, repeat. When the
                // navigator is driving, hand it back to the navigator instead.
                TungstenModDataContainer.PATHFINDER.find(player.getEntityWorld(), goal, player);
            } catch (Throwable ignored) {}
        }, "tungsten-build-resume").start();
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
