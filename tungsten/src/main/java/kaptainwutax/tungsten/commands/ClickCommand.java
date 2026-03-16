package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenMod.clickModeEnum;
import kaptainwutax.tungsten.commands.arguments.EnumArgumentType;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandException;
import net.minecraft.command.CommandSource;

public class ClickCommand extends Command {
	public ClickCommand(TungstenMod mod) throws CommandException {
        super("click", "Activates click mode", mod);
    }

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {
		
		builder.then(argument("click mode", EnumArgumentType.of(clickModeEnum.class)).executes(context -> {
        	TungstenMod.clickMode = context.getArgument("click mode", clickModeEnum.class);
			
			return SINGLE_SUCCESS;
		}));
	}
}
