package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandException;
import kaptainwutax.tungsten.task.BridgeTask;
import net.minecraft.command.CommandSource;

/**
 * Godbridge command:
 *   ;bridge            — bridge in the facing direction, 16 blocks
 *   ;bridge <n>        — bridge in the facing direction, n blocks
 *   ;bridge <x> <y> <z>— bridge toward a target position
 * A block must be in the held hotbar slot.
 */
public class BridgeCommand extends Command {

    public BridgeCommand(TungstenMod mod) throws CommandException {
        super("bridge", "Godbridge forward or toward a position", mod);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> { BridgeTask.start("", 16); return SINGLE_SUCCESS; });

        builder.then(argument("n", IntegerArgumentType.integer(1, 4096))
                .executes(ctx -> {
                    BridgeTask.start("", IntegerArgumentType.getInteger(ctx, "n"));
                    return SINGLE_SUCCESS;
                })
                .then(argument("y", IntegerArgumentType.integer(-256, 512))
                        .then(argument("z", IntegerArgumentType.integer(-30000000, 30000000))
                                .executes(ctx -> {
                                    BridgeTask.startTo(
                                            IntegerArgumentType.getInteger(ctx, "n"),
                                            IntegerArgumentType.getInteger(ctx, "y"),
                                            IntegerArgumentType.getInteger(ctx, "z"));
                                    return SINGLE_SUCCESS;
                                }))));
    }
}
