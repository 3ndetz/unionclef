package kaptainwutax.tungsten.path;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
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

	public void setPath(List<Node> path) {
		this.cb = null;
		this.startTime = System.currentTimeMillis();
		if (isClient)
			this.allowedFlying = TungstenMod.mc.player.getAbilities().allowFlying;
	    stop = false;
    	this.path = path;
    	this.tick = 0;
    	RenderHelper.renderPathCurrentlyExecuted();
	}
	
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
		if (this.path == null) return null;
		if (this.tick >= this.path.size()) return this.path.get(this.path.size()-1);
		return this.path.get(this.tick);
	}
	

	public int getCurrentTick() {
		return this.tick;
	}


	public boolean isRunning() {
        return this.path != null && this.tick <= this.path.size();
    }


    // Server-side tick disabled: requires ServerPlayerEntity.setPlayerInput() (MC 1.21.4+ only)
    // public void tick(ServerPlayerEntity player) { ... }
    
    public void tick(ClientPlayerEntity player, GameOptions options) {
    	player.getAbilities().allowFlying = false;
    	if(TungstenMod.pauseKeyBinding.isPressed() || stop) {
    		if (breakQueue != null) {
    			MinecraftClient.getInstance().interactionManager.cancelBlockBreaking();
    			breakQueue = null; breakingTicks = 0; settleTicks = 0;
    		}
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
    	if(this.tick == this.path.size()) {
    		// mine the planned wall before declaring the segment finished —
    		// the continuation search / goto retry then sees the opened world
    		if (tickBreaking(player, options)) {
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
            breakQueue = null; breakingTicks = 0; settleTicks = 0;
            return false;
        }
        settleTicks = 0;

        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        if (breakingTicks++ > 300 || eye.squaredDistanceTo(center) > 4.5 * 4.5) {
            Debug.logMessage("Mining aborted (timeout or out of reach)");
            options.attackKey.setPressed(false);
            mc.interactionManager.cancelBlockBreaking();
            breakQueue = null; breakingTicks = 0; settleTicks = 0;
            return false;
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
        // Aim at the block and HOLD the attack key — vanilla handleBlockBreaking
        // then drives the mining against crosshairTarget. Calling
        // updateBlockBreakingProgress directly does not work: with the attack
        // key up, vanilla cancels the breaking progress every tick.
        Vec3d d = center.subtract(eye);
        player.setYaw((float) Math.toDegrees(-Math.atan2(d.x, d.z)));
        player.setPitch((float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        options.attackKey.setPressed(true);
        return true;
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
