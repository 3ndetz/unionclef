package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.RuntimeCommandException;

public class TimeoutSpecificCommand extends Command {

    private String rawLine;

    public TimeoutSpecificCommand() throws CommandException {
        super("timeout", "Run command with a specific timeout. Usage: @timeout <seconds> <command> [args]");
    }

    @Override
    public void run(AltoClef mod, String line, Runnable onFinish) throws CommandException {
        rawLine = line;
        super.run(mod, line, onFinish);
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        // rawLine = "@timeout 30 goto 0 0 0" — strip command name first
        int firstSpace = rawLine.indexOf(' ');
        String after = (firstSpace >= 0) ? rawLine.substring(firstSpace + 1).trim() : "";
        if (after.isBlank()) throw new RuntimeCommandException("Usage: @timeout <seconds> <command> [args]");

        int secondSpace = after.indexOf(' ');
        if (secondSpace < 0) throw new RuntimeCommandException("Usage: @timeout <seconds> <command> [args]");

        String timeStr = after.substring(0, secondSpace);
        String innerCmd = after.substring(secondSpace + 1).trim();

        float seconds;
        try {
            seconds = Float.parseFloat(timeStr);
        } catch (NumberFormatException e) {
            throw new RuntimeCommandException("Invalid time value: " + timeStr);
        }

        mod.setTimeoutTask(seconds);
        AltoClef.getCommandExecutor().executeWithPrefix(innerCmd);
        finish();
    }
}
