package kaptainwutax.tungsten;

import kaptainwutax.tungsten.commands.*;
import kaptainwutax.tungsten.commandsystem.CommandException;

public class TungstenCommands {

	public TungstenCommands(TungstenMod mod) throws CommandException {
		TungstenMod.getCommandExecutor().registerNewCommand(
				new ClickCommand(mod),
				new GotoCommand(mod),
				new StopCommand(mod),
				new SettingsCommand(mod),
				// new FollowCommand(mod), // disabled — not ready
				new FollowPlayerCommand(mod)
		);
	}
}
