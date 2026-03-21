package kaptainwutax.tungsten.path;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
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
    	this.path = resimulateFromRealPosition(path);
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
			    player.setYaw(node.input.yaw);
			    player.setPitch(node.input.pitch);
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
     * Re-simulate path inputs from the real player position instead of the
     * stale position the pathfinder used. Same inputs (yaw, forward, sprint,
     * jump), but starting from where the player actually is.
     * This fixes stale start drift without changing the path's logic.
     */
    private List<Node> resimulateFromRealPosition(List<Node> path) {
        if (!isClient || path == null || path.isEmpty()) return path;
        ClientPlayerEntity player = TungstenMod.mc.player;
        if (player == null) return path;

        Vec3d realPos = player.getPos();
        Vec3d pathStart = path.get(0).agent.getPos();
        double startDrift = realPos.distanceTo(pathStart);

        // If start is close enough, no correction needed
        if (startDrift < 0.2) return path;

        WorldView world = TungstenMod.mc.world;
        if (world == null) return path;

        // Re-simulate from real player state with same inputs
        Agent agent = Agent.of(player);
        List<Node> corrected = new ArrayList<>(path.size());

        for (int i = 0; i < path.size(); i++) {
            Node original = path.get(i);
            if (original.input == null) {
                corrected.add(original);
                continue;
            }

            agent = Agent.of(agent, original.input).tick(world);
            Node newNode = new Node(
                i > 0 ? corrected.get(i - 1) : null,
                agent, original.color, original.cost
            );
            newNode.input = original.input;
            newNode.estimatedCostToGoal = original.estimatedCostToGoal;
            corrected.add(newNode);

            // Once the corrected trajectory converges with original,
            // keep the rest of the original path as-is (saves computation)
            double convergeDist = agent.getPos().distanceTo(original.agent.getPos());
            if (convergeDist < 0.05 && i > 5) {
                for (int j = i + 1; j < path.size(); j++) {
                    corrected.add(path.get(j));
                }
                break;
            }
        }

        if (TungstenConfig.get().verboseDebugLogging) {
            Debug.logMessage(String.format("Resimulated %d/%d nodes from real pos (startDrift=%.3f)",
                Math.min(corrected.size(), path.size()), path.size(), startDrift));
        }

        return corrected;
    }

    /**
     * Try to reconnect to the path by scanning ahead for a node whose
     * parent state is close to the player's current position.
     * Called from Agent.compare() when drift exceeds threshold.
     *
     * @return true if reconnected (tick advanced), false if no suitable node found
     */
    public boolean tryReconnect(Vec3d playerPos) {
        if (this.path == null || this.tick >= this.path.size()) return false;

        double bestDist = Double.MAX_VALUE;
        int bestIdx = -1;
        // Scan ahead up to 20 nodes (don't search the whole path — too expensive
        // and distant nodes make no sense for reconnection)
        int scanLimit = Math.min(this.tick + 20, this.path.size());

        for (int i = this.tick; i < scanLimit; i++) {
            Node node = this.path.get(i);
            // node.agent is the position AFTER this tick's input.
            // The player needs to match the pre-input state, which is
            // the parent's post-state. For the path list, that's path[i-1].agent.
            // For i == tick (current), we use node.agent as rough approximation
            // since we're already past where we should be.
            Vec3d nodePos = node.agent.getPos();
            double dist = playerPos.distanceTo(nodePos);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }

        // Only reconnect if the closest node is within a reasonable range
        if (bestIdx >= 0 && bestDist < TungstenConfig.get().driftThreshold) {
            int skipped = bestIdx - this.tick + 1;
            // Advance past the matched node — next tick will execute from bestIdx+1
            this.tick = bestIdx + 1;
            Debug.logMessage(String.format(
                "Reconnected to path at node %d (skipped %d, dist %.3f)",
                bestIdx, skipped, bestDist));
            return true;
        }

        return false;
    }

    public static TungstenPlayerInput optionsToPlayerInput(GameOptions options) {
    	return new TungstenPlayerInput(options.forwardKey.isPressed(), options.backKey.isPressed(), options.leftKey.isPressed(), options.rightKey.isPressed(), options.jumpKey.isPressed(), options.sneakKey.isPressed(), options.sprintKey.isPressed());
    }

}
