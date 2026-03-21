package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandException;
import kaptainwutax.tungsten.task.PunkPlayerTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;

public class PunkPlayerCommand extends Command {

    public PunkPlayerCommand(TungstenMod mod) throws CommandException {
        super("punkPlayer", "Hunt and fight a player (A* approach + MPC combat)", mod);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        SuggestionProvider<CommandSource> playerSuggestions = (ctx, sb) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getNetworkHandler() != null) {
                String input = sb.getRemaining().toLowerCase();
                for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
                    String name = entry.getProfile().getName();
                    if (name.toLowerCase().startsWith(input)) {
                        sb.suggest(name);
                    }
                }
            }
            return sb.buildFuture();
        };

        // ;punkPlayer <name>
        builder.then(argument("name", StringArgumentType.word())
                .suggests(playerSuggestions)
                .executes(context -> {
                    String name = StringArgumentType.getString(context, "name");
                    PunkPlayerTask.start(name);
                    return SINGLE_SUCCESS;
                }));
    }
}
