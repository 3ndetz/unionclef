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

				// Stop standalone pathfinder/executor (e.g., ;goto)
				TungstenModDataContainer.PATHFINDER.stop.set(true);
				TungstenModDataContainer.EXECUTOR.stop = true;

				Debug.logMessage(hadSomething ? "Stopped!" : "Nothing to stop.");
			} catch (Exception e) {
				// TODO: handle exception
			}

			return SINGLE_SUCCESS;
		});
	}
}
