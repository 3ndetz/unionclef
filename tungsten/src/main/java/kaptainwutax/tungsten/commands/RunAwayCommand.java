package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandException;
import kaptainwutax.tungsten.task.RunAwayTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;

/** {@code ;runAwayPlayer <name> [distance]} — flee a player, keeping distance. */
public class RunAwayCommand extends Command {

    public RunAwayCommand(TungstenMod mod) throws CommandException {
        super("runAwayPlayer", "Flee a player, keeping distance (tungsten, void-safe)", mod);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        SuggestionProvider<CommandSource> playerSuggestions = (ctx, sb) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getNetworkHandler() != null) {
                String input = sb.getRemaining().toLowerCase();
                for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
                    //#if MC < 12111
                    //$$ String name = entry.getProfile().getName();
                    //#else
                    String name = entry.getProfile().name();
                    //#endif
                    if (name.toLowerCase().startsWith(input)) {
                        sb.suggest(name);
                    }
                }
            }
            return sb.buildFuture();
        };

        builder.then(argument("name", StringArgumentType.word())
                .suggests(playerSuggestions)
                .executes(context -> {
                    RunAwayTask.start(StringArgumentType.getString(context, "name"), 8.0);
                    return SINGLE_SUCCESS;
                })
                .then(argument("distance", DoubleArgumentType.doubleArg(3.0, 64.0))
                        .executes(context -> {
                            RunAwayTask.start(StringArgumentType.getString(context, "name"),
                                    DoubleArgumentType.getDouble(context, "distance"));
                            return SINGLE_SUCCESS;
                        })));
    }
}
