package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.commands.arguments.GotoTargetArgumentType;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandException;
import kaptainwutax.tungsten.path.targets.BlockTarget;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.Vec3d;

public class GotoCommand extends Command {

	private static final int    MAX_RETRIES     = 10;
	private static final double ARRIVAL_DIST_SQ = 2.0 * 2.0;

	public GotoCommand(TungstenMod mod) throws CommandException {
        super("goto", "Tell bot to travel to a set of coordinates", mod);
    }

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {

		builder.then(argument("gotoTarget", GotoTargetArgumentType.create()).executes(context -> {
	        try {

	        	BlockTarget target = GotoTargetArgumentType.get(context);
	        	if(!TungstenModDataContainer.PATHFINDER.active.get() && !TungstenModDataContainer.EXECUTOR.isRunning()) {
	        		Vec3d targetVec = target.getVec3d().add(0.5, 0, 0.5);
	        		TungstenMod.TARGET = targetVec;
	        		startWithRetry(targetVec, 0);
	    		} else {
	    			Debug.logWarning("Already running!");
	    		}

			} catch (Exception e) {
				e.printStackTrace();
			}

			return SINGLE_SUCCESS;
		}));
	}

	private static void startWithRetry(Vec3d target, int attempt) {
		if (attempt >= MAX_RETRIES) {
			Debug.logWarning("Gave up after " + MAX_RETRIES + " attempts.");
			return;
		}
		if (TungstenModDataContainer.PATHFINDER.stop.get()) return;

		// Reset pathfinder params to defaults (may have been overridden by followPlayer)
		TungstenModDataContainer.PATHFINDER.searchTimeoutMs       = 15000L;
		TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 15;
		TungstenModDataContainer.PATHFINDER.minDistPath           = 1.8;
		TungstenModDataContainer.PATHFINDER.find(TungstenMod.mc.world, target, TungstenMod.mc.player);

		// Set callback: when pathfinder+executor finish, retry if not at target
		TungstenModDataContainer.EXECUTOR.cb = () -> {
			if (TungstenModDataContainer.PATHFINDER.stop.get()) return;
			if (TungstenMod.mc.player == null) return;
			double distSq = TungstenMod.mc.player.getEntityPos().squaredDistanceTo(target);
			if (distSq > ARRIVAL_DIST_SQ) {
				Debug.logMessage("Retrying (" + (attempt + 1) + "/" + MAX_RETRIES + ")...");
				// Small delay to let player land
				new Thread(() -> {
					try { Thread.sleep(500); } catch (InterruptedException ignored) {}
					if (!TungstenModDataContainer.PATHFINDER.stop.get()) {
						startWithRetry(target, attempt + 1);
					}
				}).start();
			}
		};
	}
}
