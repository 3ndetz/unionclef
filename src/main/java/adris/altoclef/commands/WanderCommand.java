package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.movement.AliveConfig;
import adris.altoclef.tasks.movement.AliveTask;

/**
 * @wander — idle-life so the agent never stands like a statue (operator: standing frozen in a lobby
 * is cringe). Backed by AliveTask: PURELY input-driven fidgeting (look around, glance at people,
 * tiny in-place shuffles, jumps) — NO pathfinding, so it works EVERYWHERE, including protected
 * lobbies where baritone can't move. Runs ~10s in the BACKGROUND while the agent keeps chatting and
 * listening, then ends on its own; the AGENT decides when to start it and can stop it (@idle/@stop).
 *   @wander         ~10s of idle-life
 *   @wander <sec>   idle-life for <sec> seconds
 */
public class WanderCommand extends Command {
    public WanderCommand() {
        super("wander", "Idle-life so you don't stand still: look around, glance at people, fidget (works in lobbies). @wander [sec]",
                new StringArg("seconds", "10"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String secArg = parser.get(String.class);
        double secs = 10;
        if (secArg != null && !secArg.isEmpty()) {
            try { secs = Double.parseDouble(secArg); } catch (NumberFormatException ignored) { }
        }
        AliveConfig.set(AliveConfig.Mode.IDLE, null, AliveConfig.radius());   // @wander = @alive idle
        mod.runUserTask(new AliveTask(secs), this::finish);
    }
}
