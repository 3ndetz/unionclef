package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.PlayerArg;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.RuntimeCommandException;
import adris.altoclef.tasks.entity.CombatTask;
import net.minecraft.entity.player.PlayerEntity;

public class CombatCommand extends Command {
    public CombatCommand() {
        super("combat", "Combat mode with configurable options",
            new StringArg("mode", "all"),
            new PlayerArg("target", null),
            new StringArg("graves", "on"),
            new StringArg("gestures", "on")
        );
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String mode = parser.get(String.class);
        String target = parser.get(String.class);
        boolean buildGraves = parser.get(String.class).equalsIgnoreCase("on");
        boolean useGestures = parser.get(String.class).equalsIgnoreCase("on");

        if (mode.equalsIgnoreCase("target")) {
            if (target == null || target.isEmpty()) {
                throw new RuntimeCommandException("Target name required in target mode");
            }
            mod.runUserTask(new CombatTask(target, buildGraves, useGestures), this::finish);
        } else if (mode.equalsIgnoreCase("all")) {
            mod.runUserTask(new CombatTask(
                entity -> entity instanceof PlayerEntity && !entity.equals(mod.getPlayer()),
                buildGraves,
                useGestures
            ), this::finish);
        } else {
            throw new RuntimeCommandException("Invalid mode. Use 'all' or 'target'");
        }
    }
}
