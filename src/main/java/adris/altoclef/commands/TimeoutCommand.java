package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;

public class TimeoutCommand extends Command {

    private String rawLine;

    public TimeoutCommand() throws CommandException {
        super("t", "Run command with default 60s timeout. Usage: @t <command> [args]");
    }

    @Override
    public void run(AltoClef mod, String line, Runnable onFinish) throws CommandException {
        rawLine = line;
        super.run(mod, line, onFinish);
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        int spaceIdx = rawLine.indexOf(' ');
        String inner = (spaceIdx >= 0) ? rawLine.substring(spaceIdx + 1).trim() : "";
        if (!inner.isBlank()) {
            mod.setTimeoutTaskFlag(true);
            AltoClef.getCommandExecutor().executeWithPrefix(inner);
        }
        finish();
    }
}
