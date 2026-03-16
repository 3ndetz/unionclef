package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;

public class AgentCommand extends Command {

    private String rawLine;

    public AgentCommand() {
        super("agent", "Execute agentic command (if python entrypoint started). Usage: @agent <command string>");
    }

    @Override
    public void run(AltoClef mod, String line, Runnable onFinish) throws CommandException {
        rawLine = line;
        super.run(mod, line, onFinish);
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        // Strip command name to get the argument text
        int spaceIdx = rawLine.indexOf(' ');
        String text = (spaceIdx >= 0) ? rawLine.substring(spaceIdx + 1).trim() : "";
        if (!text.isBlank()) {
            mod.getInfoSender().executeAgentCommand(text);
        }
        finish();
    }
}
