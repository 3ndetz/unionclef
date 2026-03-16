package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.construction.compound.ConstructGraveTask;

public class GraveCommand extends Command {

    private String _capturedText = null;

    public GraveCommand() {
        super("grave", "Place grave with text. Usage: @grave any text");
    }

    @Override
    public void run(AltoClef mod, String line, Runnable onFinish) throws CommandException {
        int spaceIdx = line.indexOf(' ');
        _capturedText = (spaceIdx >= 0) ? line.substring(spaceIdx + 1).trim() : "";
        super.run(mod, line, onFinish);
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String text = _capturedText != null ? _capturedText : "";
        if (!text.isBlank()) {
            Debug.logMessage("Constructing grave with text: " + text);
            mod.runUserTask(new ConstructGraveTask(text), this::finish);
        } else {
            Debug.logWarning("Text is blank!");
            finish();
        }
    }
}
