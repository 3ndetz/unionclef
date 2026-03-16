package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class PauseCommand extends Command {
    public PauseCommand() {
        super("pause", "Pauses the currently running task");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        if (mod.isPaused()) {
            log("Bot is already paused!");
        } else if (!mod.getUserTaskChain().isActive()) {
            log("Bot has no current task!");
        } else {
            mod.setStoredTask(mod.getUserTaskChain().getCurrentTask());
            mod.setPaused(true);
            mod.getUserTaskChain().stop();
            mod.getTaskRunner().disable();
            log("Pausing Bot and time");
        }
        finish();
    }
}