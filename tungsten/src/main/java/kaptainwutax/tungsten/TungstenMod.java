package kaptainwutax.tungsten;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kaptainwutax.tungsten.commandsystem.CommandExecutor;
import kaptainwutax.tungsten.path.PathExecutor;
import kaptainwutax.tungsten.path.PathFinder;
import kaptainwutax.tungsten.render.Renderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldView;

public class TungstenMod implements ClientModInitializer {

	public static final String MOD_ID = "tungsten";
//    public static final ModMetadata MOD_META;
    public static final String NAME;

    public static MinecraftClient mc = null;
    public static PlayerEntity player = null;
    public static WorldView world = null;
	public static Vec3d TARGET = new Vec3d(0.5D, 10.0D, 0.5D);

	/**
	 * Has anything actually ASKED for a goto, or is TARGET still the debug default?
	 *
	 * <p>TARGET is initialised to (0.5, 10.0, 0.5) and written only by ;goto, the create-goal
	 * keybinding, follow-entity and a few py4j primitives. The altoclef task drive never writes it --
	 * it calls FastNavigator.start(gp) directly -- so during any altoclef-driven run it holds that
	 * constant for the whole session. Anything that RESUMES a goto therefore has to ask whether there
	 * is a goto to resume, or it aims the bot at y=10.
	 */
	private static volatile boolean targetIsReal = false;

	/** Record that a real destination was requested. Called wherever TARGET is written. */
	public static void markGotoTarget() {
		targetIsReal = true;
	}

	/**
	 * A goto that has been COMPLETED is no longer a goto to resume.
	 *
	 * <p>stopNavigation() already clears this when a goto is STOPPED. Arriving was not covered, so
	 * TARGET kept its destination and the flag kept saying "real" -- and the next mining segment
	 * handed resumeGotoAfterMining a goto that had already finished. Harmless straight after arrival
	 * (that method returns inside 2 blocks) and not harmless once the bot has walked off to mine,
	 * when it walks BACK to a place it already reached.
	 */
	public static void clearGotoTarget() {
		targetIsReal = false;
	}

	public static boolean hasRealGotoTarget() {
		return targetIsReal;
	}
	public static clickModeEnum clickMode = clickModeEnum.OFF;
	public static final Logger LOG;
	public static KeyBinding pauseKeyBinding;
	public static KeyBinding runKeyBinding;
	public static KeyBinding runBlockSearchKeyBinding;
	public static KeyBinding createGoalKeyBinding;
    private static CommandExecutor _commandExecutor;
    public static boolean renderPositonBoxes = true;
	
	
	static {
		// MOD_META = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getMetadata();
        NAME = "Tungsten";
        // DEV_BUILD = MOD_META.getCustomValue(TungstenMod.MOD_ID + ":devbuild").getAsString();
        LOG = LoggerFactory.getLogger(NAME);
		
	}

	/**
	 * Stop everything that can be STEERING the body along a route. The one implementation.
	 *
	 * <h2>Why this is its own method</h2>
	 *
	 * There were three teardowns and no two agreed. {@code ;stop} stopped the movement queue, the
	 * pathfinder and the executor but not the NAVIGATOR -- which re-plans and hands the queue a
	 * fresh leg within a tick or two, so the stop did not hold. {@link #resetAllState()}, whose
	 * javadoc promises ALL state and which runs on DISCONNECT, stopped six tasks and neither the
	 * navigator nor its queue. And altoclef had no way to reach any of it: {@code AltoClef.stopTasks()}
	 * cancels the task chain and never speaks to a pathfinder at all.
	 *
	 * <p>What that cost, traced on mine_stone: an altoclef task finished with its eight cobblestone
	 * gathered, a search still in flight landed two seconds later, and its route was eight
	 * {@code MovementPillar} steps. The bot spent the whole haul building a tower and stood on it
	 * for the rest of the run.
	 *
	 * <h2>Order matters here</h2>
	 *
	 * {@code FastNavigator.stop()} cascades into {@code MovementQueue.stop()} -- and the queue is
	 * the driver the mixin lets outrank every other one, so stopping the walker or the pillar while
	 * the queue still runs stops nothing. Kill the planner first, then its executors.
	 *
	 * <p>Deliberately NOT combat: the punk task, the bow and the aim are not routes, and altoclef
	 * calls this when a task ends, where killing a shot the agent lined up would be wrong.
	 */
	public static void stopNavigation() {
		// A goto that has been stopped is not a goto to resume.
		targetIsReal = false;
		kaptainwutax.tungsten.task.FastNavigator.stop();
		kaptainwutax.tungsten.task.BlockPathWalker.stop();
		kaptainwutax.tungsten.task.BridgeTask.stop();
		kaptainwutax.tungsten.task.PillarTask.stop();
		var pf = TungstenModDataContainer.PATHFINDER;
		var ex = TungstenModDataContainer.EXECUTOR;
		kaptainwutax.tungsten.path.PathFinder.noteStop("TungstenMod@136");
		if (pf != null) pf.stop.set(true);
		if (ex != null) ex.stop = true;
	}

	/**
	 * Reset ALL tungsten client-side state. Called on disconnect / world change so
	 * nothing survives a re-join: a frozen mine/combat aim, a running task, a stuck
	 * break, a live pathfinder/executor. Static singletons don't reset on world unload
	 * on their own — this is the durable fix for #29 (camera frozen on a block that
	 * persisted across reconnect). Also safe to call anytime as a hard reset.
	 */
	public static void resetAllState() {
		try {
			kaptainwutax.tungsten.util.WindMouseRotation.INSTANCE.clearTarget();
			stopNavigation();
			kaptainwutax.tungsten.task.PunkPlayerTask.stop();
			kaptainwutax.tungsten.task.RunAwayTask.stop();
			kaptainwutax.tungsten.task.BowShooter.stop();
			var ex = TungstenModDataContainer.EXECUTOR;
			if (ex != null) ex.breakQueue = null;
			MinecraftClient m = MinecraftClient.getInstance();
			if (m.options != null) {
				m.options.attackKey.setPressed(false);
				m.options.useKey.setPressed(false);
				m.options.forwardKey.setPressed(false);
				m.options.backKey.setPressed(false);
				m.options.leftKey.setPressed(false);
				m.options.rightKey.setPressed(false);
				m.options.sprintKey.setPressed(false);
				m.options.jumpKey.setPressed(false);
				m.options.sneakKey.setPressed(false);
			}
			if (m.interactionManager != null) m.interactionManager.cancelBlockBreaking();
		} catch (Exception e) {
			Debug.logMessage("resetAllState error: " + e.getMessage());
		}
	}

	@Override
	public void onInitializeClient() {
		TungstenConfig.load();
		// #29 durable fix: nothing tungsten must survive a reconnect (a frozen aim, a
		// stuck mine, a running task). Static state doesn't reset on world unload itself.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
				.register((handler, client) -> resetAllState());
		TungstenModDataContainer.EXECUTOR = new PathExecutor(true);
		//#if MC < 12111
		//$$ LOG.info("[Tungsten] Preprocessor: MC < 12111 (1.21.1 fallback mode)");
		//#else
		LOG.info("[Tungsten] Preprocessor: MC >= 12111 ACTIVE (1.21.11 mode)");
		//#endif
		//#if MC < 12104
		//$$ LOG.info("[Tungsten] Diagonal normalization: DISABLED (MC < 1.21.4)");
		//#else
		LOG.info("[Tungsten] Diagonal normalization: ENABLED (MC >= 1.21.4)");
		//#endif
		//#if MC < 12111
		//$$ pauseKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
	            //$$ "key.tungsten.pause", // The translation key of the keybinding's name
	            //$$ InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
	            //$$ GLFW.GLFW_KEY_P, // The keycode of the key
	            //$$ "key.category.tungsten.test" // The translation key of the keybinding's category.
        //$$ ));
		//$$ runKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
	            //$$ "key.tungsten.run", // The translation key of the keybinding's name
	            //$$ InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
	            //$$ GLFW.GLFW_KEY_G, // The keycode of the key
	            //$$ "key.category.tungsten.test" // The translation key of the keybinding's category.
        //$$ ));
		//$$ runBlockSearchKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
	            //$$ "key.tungsten.run_block_search", // The translation key of the keybinding's name
	            //$$ InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
	            //$$ GLFW.GLFW_KEY_J, // The keycode of the key
	            //$$ "key.category.tungsten.test.development" // The translation key of the keybinding's category.
        //$$ ));
		//$$ createGoalKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
	            //$$ "key.tungsten.create_goal", // The translation key of the keybinding's name
	            //$$ InputUtil.Type.KEYSYM, // The type of the keybinding, KEYSYM for keyboard, MOUSE for mouse.
	            //$$ GLFW.GLFW_KEY_H, // The keycode of the key
	            //$$ "key.category.tungsten.test" // The translation key of the keybinding's category.
        //$$ ));
		//#else
		pauseKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		    "key.tungsten.pause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P,
		    net.minecraft.client.option.KeyBinding.Category.MISC));
		runKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		    "key.tungsten.run", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G,
		    net.minecraft.client.option.KeyBinding.Category.MISC));
		runBlockSearchKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		    "key.tungsten.run_block_search", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J,
		    net.minecraft.client.option.KeyBinding.Category.MISC));
		createGoalKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
		    "key.tungsten.create_goal", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H,
		    net.minecraft.client.option.KeyBinding.Category.MISC));
		//#endif
        _commandExecutor = new CommandExecutor(this);

        // Global minecraft client accessor
        mc = MinecraftClient.getInstance();
        TungstenModDataContainer.player = mc.player;
        TungstenModDataContainer.world = mc.world;
        TungstenModDataContainer.gameRenderer = mc.gameRenderer;

        initializeCommands();

    	ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        Runnable toRun = new Runnable() {
            public void run() {
	        	if (!TungstenModRenderContainer.ERROR.isEmpty()) {
	        		TungstenModRenderContainer.ERROR.clear();
	        	}
            }
        };
        ScheduledFuture<?> handle = scheduler.scheduleAtFixedRate(toRun, 1, 15, TimeUnit.SECONDS);
    
        ClientTickEvents.START_CLIENT_TICK.register((a) -> {
        	// The place-rate gate, ticked exactly once per client tick — upstream ticks it from
        	// InputOverrideHandler, which is the same "once per tick, whatever else is going on".
        	// This is also what drains the build queue, so it must run regardless of pathing.
        	kaptainwutax.tungsten.helpers.BlockPlaceHelper.tickCooldown();

        	boolean isRunning = TungstenModDataContainer.PATHFINDER.active.get() || TungstenModDataContainer.isExecutorRunning();
        	if (!isRunning) {
	        	if (!TungstenModRenderContainer.BLOCK_PATH_RENDERER.isEmpty()) {
	        		TungstenModRenderContainer.BLOCK_PATH_RENDERER.clear();
	        	}
	        	if (!TungstenModRenderContainer.RUNNING_PATH_RENDERER.isEmpty()) {
	        		TungstenModRenderContainer.RUNNING_PATH_RENDERER.clear();
	        	}
	        	if (!TungstenModRenderContainer.RENDERERS.isEmpty()) {
	        		TungstenModRenderContainer.RENDERERS.clear();
	        	}
	        	if (!TungstenModRenderContainer.TEST.isEmpty()) {
	        		TungstenModRenderContainer.TEST.clear();
	        	}
        	}
        	if (clickMode != clickModeEnum.OFF && mc.options.useKey.isPressed() && !isRunning) {
        		
        		 Camera camera = mc.gameRenderer.getCamera();
                 Vec3d cameraPos = camera.getCameraPos();

                 // Calculate the direction the camera is looking based on its pitch and yaw, and extend this direction 210 units away from the camera position
                 // 210 is used here as the maximum distance of 200 blocks
                 // This is done to be able to set target while in freecam
                 Vec3d direction = Vec3d.fromPolar(camera.getPitch(), camera.getYaw()).multiply(210);
                 Vec3d targetPos = cameraPos.add(direction);
                 
                 RaycastContext context = new RaycastContext(
                         cameraPos,   // start position of the ray
                         targetPos,   // end position of the ray
                         RaycastContext.ShapeType.OUTLINE,
                         RaycastContext.FluidHandling.NONE,
                         mc.player
                 );
                 
                 HitResult hitResult = mc.world.raycast(context);
                 
                 if (hitResult.getType() == HitResult.Type.BLOCK) {
                     BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
	                 if (mc.world.getBlockState(pos).onUse(mc.world, mc.player, (BlockHitResult) hitResult) != ActionResult.PASS) return;
	
		                 BlockState state = mc.world.getBlockState(pos);
		
		                 VoxelShape shape = state.getCollisionShape(mc.world, pos);
//		                 if (shape.isEmpty()) shape = state.getOutlineShape(mc.world, pos);
		
		                 double height = shape.isEmpty() ? 0 : shape.getMax(Direction.Axis.Y);
		
		                 Vec3d newPos = new Vec3d(pos.getX() + 0.5, pos.getY() + height, pos.getZ() + 0.5);
		                 // Snap a non-standable click (air / tall grass / flowers) to the
		                 // reachable ground so the search doesn't spin forever near it.
		                 newPos = kaptainwutax.tungsten.path.GoalSnap.snap(newPos, mc.world);
		         		TungstenMod.TARGET = newPos;


		        		if (clickMode == clickModeEnum.GOTO && !TungstenModDataContainer.PATHFINDER.active.get()) {
		        			TungstenModDataContainer.PATHFINDER.find(TungstenMod.mc.world, TARGET, TungstenMod.mc.player);
		        		}
	        		}
        		}
        		
        		
        });
	}
	
	 public static String getCommandPrefix() {
		 return ";";
	 }
	 
	// List all command sources here.
    private void initializeCommands() {
        try {
            // This creates the commands. If you want any more commands feel free to initialize new command lists.
            new TungstenCommands(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
	 
	 /**
     * Executes commands
     */
    public static CommandExecutor getCommandExecutor() {
        return _commandExecutor;
    }
    
    public enum clickModeEnum {
    	OFF,
    	PLACE_GOAL,
    	GOTO
    }

}
