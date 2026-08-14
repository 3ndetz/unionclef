package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.path.PathFinder;
import kaptainwutax.tungsten.task.FollowEntityTask;
import kaptainwutax.tungsten.task.FollowPlayerTask;
import kaptainwutax.tungsten.task.PunkPlayerTask;
import net.minecraft.command.CommandSource;

public class StopCommand extends Command {
	public StopCommand(TungstenMod mod) {
        super("stop", "Tell bot to stop", mod);
    }

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {

		builder.executes(context -> {
	        try {
				boolean hadSomething = FollowPlayerTask.isActive()
						|| FollowEntityTask.isActive()
						|| PunkPlayerTask.isActive()
						|| TungstenModDataContainer.PATHFINDER.active.get()
						|| TungstenModDataContainer.EXECUTOR.isRunning();

				// THE QUEUE IS A DRIVER TOO, AND IT SUPPRESSES EVERY OTHER ONE WHILE IT RUNS.
				// MovementQueue.stop() had a single caller in the whole repo (FastNavigator), so
				// a stop left it owning the tick: walker, bridge, pillar, slime task and the
				// physics executor all stay suppressed by the mixin while the old chain keeps
				// writing keys and camera until it completes or times out — including straight
				// through the next test scenario's setup.
				//
				// AND STOPPING THE QUEUE ALONE DID NOT HOLD. FastNavigator was left running by
				// this command, so it re-planned and handed the queue a fresh leg a tick or two
				// later — the stop looked like it worked and the bot kept walking. Same for the
				// walker, the bridge and the pillar, none of which this ever touched. All of it
				// now lives in one place, because three teardowns that disagreed is what produced
				// the defect this was written for.
				TungstenMod.stopNavigation();

				// Stop punk task first (it manages its own follow internally)
				if (PunkPlayerTask.isActive()) {
					PunkPlayerTask.stop();
				}

				// Stop follow tasks (cascades to pathfinder + executor)
				if (FollowPlayerTask.isActive()) {
					FollowPlayerTask.stop();
				} else if (FollowEntityTask.isActive()) {
					FollowEntityTask.stop();
				}

				// The standalone pathfinder/executor (e.g. ;goto) are stopped by
				// stopNavigation() above -- they used to be repeated here.

				Debug.logMessage(hadSomething ? "Stopped!" : "Nothing to stop.");
			} catch (Exception e) {
				// TODO: handle exception
			}

			return SINGLE_SUCCESS;
		});
	}
}
