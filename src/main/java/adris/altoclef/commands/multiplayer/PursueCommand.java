package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.PlayerArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.movement.IdleTask;

public class PursueCommand extends Command {
    public PursueCommand() {
        super("pursue", "Pursues someone", new PlayerArg("username", null));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String username = parser.get(String.class);
        if (username == null) {
            finish();
            return;
        }
        if (mod.getInfoSender() != null) {
            mod.getInfoSender().attackPlayer(username);
        }
        adris.altoclef.tasksystem.TaskChain current = mod.getTaskRunner().getCurrentTaskChain();
        if (current == null || !current.isActive()) {
            mod.runUserTask(new IdleTask());
        }
    }
}
