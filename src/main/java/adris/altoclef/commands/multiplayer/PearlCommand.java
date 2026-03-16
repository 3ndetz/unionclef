package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.PlayerArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.RuntimeCommandException;
import adris.altoclef.tasks.movement.ThrowEnderPearlSimpleProjectileTask;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public class PearlCommand extends Command {
    public PearlCommand() {
        super("pearl", "Tp to player using enderpearl", new PlayerArg("playerName"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String playerName = parser.get(String.class);
        if (!mod.getItemStorage().hasItem(Items.ENDER_PEARL)) {
            throw new RuntimeCommandException("Cannot perform enderpearl teleport: no enderpearls in inventory.");
        }
        Optional<Entity> player = Optional.empty();
        if (mod.getEntityTracker().isPlayerLoaded(playerName)) {
            player = mod.getEntityTracker().getPlayerEntity(playerName).map(Entity.class::cast);
        }
        if (player.isPresent()) {
            BlockPos pos = player.get().getBlockPos();
            if (pos != null) {
                mod.runUserTask(new ThrowEnderPearlSimpleProjectileTask(pos), this::finish);
                return;
            }
        }
        throw new RuntimeCommandException("Cannot perform enderpearl teleport: Player " + playerName + " not found.");
    }
}
