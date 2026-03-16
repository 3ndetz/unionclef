package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.commandsystem.Command;
import net.minecraft.command.CommandSource;

public class SettingsCommand extends Command {

	public SettingsCommand(TungstenMod mod) {
		super("settings", "Handles bot settings", mod);
	}

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {

		builder.then(argument("ignoreFallDamage", BoolArgumentType.bool()).executes(context -> {
			TungstenModDataContainer.ignoreFallDamage = BoolArgumentType.getBool(context, "ignoreFallDamage");
			TungstenConfig.save();
			return SINGLE_SUCCESS;
		}));

		// ;settings driftCorrection [true/false]
		builder.then(literal("driftCorrection")
			.executes(context -> {
				Debug.logMessage("driftCorrectionEnabled = " + TungstenConfig.get().driftCorrectionEnabled);
				return SINGLE_SUCCESS;
			})
			.then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
				TungstenConfig.get().driftCorrectionEnabled = BoolArgumentType.getBool(context, "enabled");
				TungstenConfig.save();
				Debug.logMessage("driftCorrectionEnabled = " + TungstenConfig.get().driftCorrectionEnabled);
				return SINGLE_SUCCESS;
			})));

		// ;settings driftThreshold [0.5]
		builder.then(literal("driftThreshold")
			.executes(context -> {
				Debug.logMessage("driftThreshold = " + TungstenConfig.get().driftThreshold);
				return SINGLE_SUCCESS;
			})
			.then(argument("blocks", DoubleArgumentType.doubleArg(0.0, 10.0)).executes(context -> {
				TungstenConfig.get().driftThreshold = DoubleArgumentType.getDouble(context, "blocks");
				TungstenConfig.save();
				Debug.logMessage("driftThreshold = " + TungstenConfig.get().driftThreshold);
				return SINGLE_SUCCESS;
			})));

		// ;settings verboseDebug [true/false]
		builder.then(literal("verboseDebug")
			.executes(context -> {
				Debug.logMessage("verboseDebugLogging = " + TungstenConfig.get().verboseDebugLogging);
				return SINGLE_SUCCESS;
			})
			.then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
				TungstenConfig.get().verboseDebugLogging = BoolArgumentType.getBool(context, "enabled");
				TungstenConfig.save();
				Debug.logMessage("verboseDebugLogging = " + TungstenConfig.get().verboseDebugLogging);
				return SINGLE_SUCCESS;
			})));
	}
}
