package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.movement.TimeoutWanderTask;

/**
 * @wander — idle-life movement so the agent never stands like a statue (operator: standing frozen in a
 * lobby is cringe). Runs a bounded baritone wander in the BACKGROUND while the agent keeps chatting/
 * listening. The AGENT decides WHEN to start it and when to stop it (@idle/@stop) — it's a tool, not a
 * daemon. increaseRange=true keeps it wandering locally instead of stopping after one leg.
 */
public class WanderCommand extends Command {
    public WanderCommand() {
        super("wander", "Wander around idly so you don't stand still (background). Stop with @idle/@stop.");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        // ~8 blocks, increaseRange=true -> keeps drifting around the area like a player messing about.
        mod.runUserTask(new TimeoutWanderTask(8f, true), this::finish);
    }
}
