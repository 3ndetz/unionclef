package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.PlayerArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.movement.IdleTask;

public class AvoidCommand extends Command {
    public AvoidCommand() {
        super("avoid", "Avoids someone", new PlayerArg("username", null));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String username = parser.get(String.class);
        if (username == null) {
            finish();
            return;
        }
        mod.getDamageTracker().getThreatTable().forget(username);
        mod.getDamageTracker().getThreatTable().avoid(username);
        adris.altoclef.tasksystem.TaskChain current = mod.getTaskRunner().getCurrentTaskChain();
        if (current == null || !current.isActive()) {
            mod.runUserTask(new IdleTask());
        }
    }
}
