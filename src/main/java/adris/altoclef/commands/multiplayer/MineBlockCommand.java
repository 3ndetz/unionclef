package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/*
 * @mineblock
 *
 * Break the block the bot is currently LOOKING AT (crosshair). Aim first with @goto / lookAt
 * (look_at) so the wanted block is under the crosshair, then @mineblock. If the server refuses
 * (claim / privat / protected region) the task simply won't make progress — the agent should
 * check @coords / the block afterwards and HONESTLY say "тут не сломать, приват" instead of
 * pretending. The agent decides which block and why.
 */
public class MineBlockCommand extends Command {

    public MineBlockCommand() {
        super("mineblock", "Break the block currently under your crosshair (look at it first)");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        HitResult hit = MinecraftClient.getInstance().crosshairTarget;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            mod.log("No block under crosshair. Look at a block first (lookAt / @goto), then @mineblock.");
            finish();
            return;
        }
        BlockPos pos = bhr.getBlockPos();
        Debug.logMessage("@mineblock: destroying block at " + pos.toShortString());
        mod.runUserTask(new DestroyBlockTask(pos), this::finish);
    }
}
