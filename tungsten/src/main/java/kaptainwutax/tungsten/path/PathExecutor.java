package kaptainwutax.tungsten.path;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
// import net.minecraft.server.network.ServerPlayerEntity; // server-side disabled
import kaptainwutax.tungsten.agent.TungstenPlayerInput;

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
		    options.sprintKey.setPressed(false);
		    player.getAbilities().allowFlying = allowedFlying;

		    // Safe stop: hold sneak on dangerous surfaces to prevent falling
		    net.minecraft.util.math.BlockPos below = player.getBlockPos().down();
		    net.minecraft.block.BlockState belowState = player.getWorld().getBlockState(below);
		    boolean onDangerousSurface = belowState.getBlock() instanceof net.minecraft.block.LadderBlock
		        || belowState.isIn(net.minecraft.registry.tag.BlockTags.FENCES)
		        || belowState.isIn(net.minecraft.registry.tag.BlockTags.WALLS)
		        || player.getWorld().getBlockState(player.getBlockPos()).getBlock() instanceof net.minecraft.block.LadderBlock;
		    options.sneakKey.setPressed(onDangerousSurface);

		    this.path = null;
		    stop = false;
		    TungstenModRenderContainer.RUNNING_PATH_RENDERER.clear();
		    TungstenModRenderContainer.BLOCK_PATH_RENDERER.clear();
			player.setVelocity(0, 0, 0);
		    if (cb != null) {
		    	cb.run();
		    	cb = null;
		    }
	    } else {
		    Node node = this.path.get(this.tick);

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
    
    
    public static TungstenPlayerInput optionsToPlayerInput(GameOptions options) {
    	return new TungstenPlayerInput(options.forwardKey.isPressed(), options.backKey.isPressed(), options.leftKey.isPressed(), options.rightKey.isPressed(), options.jumpKey.isPressed(), options.sneakKey.isPressed(), options.sprintKey.isPressed());
    }

}
