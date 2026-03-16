package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class MarvionCommand extends Command {

    public MarvionCommand() {
        super("marvion", "Unsupported leaving it here in case anyone uses it");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.logWarning("This command does not exist, if you want to beat the game use '@gamer'");
    }

}
