package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.PlayerArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.RuntimeCommandException;
import adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask;
import net.minecraft.entity.Entity;

import java.util.Optional;

import static adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask.canUseRanged;
import static adris.altoclef.tasks.entity.ShootArrowSimpleProjectileTask.readyForRanged;

public class ShootCommand extends Command {
    public ShootCommand() {
        super("shoot", "Shoot a player with bow", new PlayerArg("playerName"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String playerName = parser.get(String.class);
        if (!readyForRanged(mod)) {
            throw new RuntimeCommandException("Cannot shoot: no arrows or bow in inventory");
        }
        Optional<Entity> player = GestureCommand.getPlayerTarget(mod, playerName);
        if (player.isPresent()) {
            if (!canUseRanged(mod, player.get())) {
                throw new RuntimeCommandException("Cannot shoot: trajectory is blocked");
            }
            mod.runUserTask(new ShootArrowSimpleProjectileTask(player.get()), this::finish);
            finish();
            return;
        }
        throw new RuntimeCommandException("Player " + playerName + " not found.");
    }
}
