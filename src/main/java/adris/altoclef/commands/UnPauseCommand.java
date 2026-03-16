package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

import java.util.List;

public class UnPauseCommand extends Command {
    public UnPauseCommand() {
        super(List.of("unpause", "resume"), "Unpauses the bot");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        if (!mod.isPaused()) {
            mod.log("Bot isn't paused");
        } else {
            if (mod.getStoredTask() == null) {
                Debug.logError("Stored task is null!");
            } else {
                mod.runUserTask(mod.getStoredTask());
                mod.getTaskRunner().enable();
                mod.log("Unpausing Bot and time");
            }
            mod.setPaused(false);
        }
        finish();
    }
}