package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.PlayerArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commands.multiplayer.GestureCommand;
import adris.altoclef.tasks.entity.KillPlayerTask;

public class PunkCommand extends Command {
    public PunkCommand() {
        super("punk", "Punks someone (kill player task)", new PlayerArg("playerName"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String playerName = parser.get(String.class);
        GestureCommand.getValidPlayer(mod, playerName); // throws if player not visible
        mod.runUserTask(new KillPlayerTask(playerName), this::finish);
    }
}
