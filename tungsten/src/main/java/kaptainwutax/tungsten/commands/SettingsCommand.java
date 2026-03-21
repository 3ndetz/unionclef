package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
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

		// ;settings — show all current values
		builder.executes(context -> {
			TungstenConfig c = TungstenConfig.get();
			Debug.logMessage("§e--- Tungsten Settings ---");
			Debug.logMessage("ignoreFallDamage = " + TungstenModDataContainer.ignoreFallDamage);
			Debug.logMessage("driftThreshold = " + c.driftThreshold);
			Debug.logMessage("closedLoopStrength = " + c.closedLoopStrength);
			Debug.logMessage("airStrafeMultiplier = " + c.airStrafeMultiplier);
			Debug.logMessage("mismatchLogThreshold = " + c.mismatchLogThreshold);
			Debug.logMessage("enableLeap = " + c.enableLeap);
			Debug.logMessage("enableTrailing = " + c.enableTrailing);
			Debug.logMessage("verboseDebug = " + c.verboseDebugLogging);
			Debug.logMessage("debugTime = " + c.debugTime);
			return SINGLE_SUCCESS;
		});

		// ;settings reload
		builder.then(literal("reload").executes(context -> {
			TungstenConfig.load();
			Debug.logMessage("§aConfig reloaded from tungsten.json");
			return SINGLE_SUCCESS;
		}));

		// ;settings ignoreFallDamage [true/false]
		builder.then(literal("ignoreFallDamage")
			.executes(context -> {
				Debug.logMessage("ignoreFallDamage = " + TungstenModDataContainer.ignoreFallDamage);
				return SINGLE_SUCCESS;
			})
			.then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
				TungstenModDataContainer.ignoreFallDamage = BoolArgumentType.getBool(context, "enabled");
				TungstenConfig.save();
				Debug.logMessage("ignoreFallDamage = " + TungstenModDataContainer.ignoreFallDamage);
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

		// ;settings airStrafe [1.0]
		builder.then(literal("airStrafe")
			.executes(context -> {
				Debug.logMessage("airStrafeMultiplier = " + TungstenConfig.get().airStrafeMultiplier);
				return SINGLE_SUCCESS;
			})
			.then(argument("multiplier", FloatArgumentType.floatArg(0.1F, 10.0F)).executes(context -> {
				TungstenConfig.get().airStrafeMultiplier = FloatArgumentType.getFloat(context, "multiplier");
				TungstenConfig.save();
				Debug.logMessage("airStrafeMultiplier = " + TungstenConfig.get().airStrafeMultiplier);
				return SINGLE_SUCCESS;
			})));

		// ;settings closedLoop [0.4]
		builder.then(literal("closedLoop")
			.executes(context -> {
				Debug.logMessage("closedLoopStrength = " + TungstenConfig.get().closedLoopStrength);
				return SINGLE_SUCCESS;
			})
			.then(argument("strength", FloatArgumentType.floatArg(0.0F, 1.0F)).executes(context -> {
				TungstenConfig.get().closedLoopStrength = FloatArgumentType.getFloat(context, "strength");
				TungstenConfig.save();
				Debug.logMessage("closedLoopStrength = " + TungstenConfig.get().closedLoopStrength);
				return SINGLE_SUCCESS;
			})));

		// ;settings mismatchThreshold [0.000001]
		builder.then(literal("mismatchThreshold")
			.executes(context -> {
				Debug.logMessage("mismatchLogThreshold = " + TungstenConfig.get().mismatchLogThreshold);
				return SINGLE_SUCCESS;
			})
			.then(argument("value", DoubleArgumentType.doubleArg(0.0, 1.0)).executes(context -> {
				TungstenConfig.get().mismatchLogThreshold = DoubleArgumentType.getDouble(context, "value");
				TungstenConfig.save();
				Debug.logMessage("mismatchLogThreshold = " + TungstenConfig.get().mismatchLogThreshold);
				return SINGLE_SUCCESS;
			})));

		// ;settings enableLeap [true/false]
		builder.then(literal("enableLeap")
			.executes(context -> {
				Debug.logMessage("enableLeap = " + TungstenConfig.get().enableLeap);
				return SINGLE_SUCCESS;
			})
			.then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
				TungstenConfig.get().enableLeap = BoolArgumentType.getBool(context, "enabled");
				TungstenConfig.save();
				Debug.logMessage("enableLeap = " + TungstenConfig.get().enableLeap);
				return SINGLE_SUCCESS;
			})));

		// ;settings enableTrailing [true/false]
		builder.then(literal("enableTrailing")
			.executes(context -> {
				Debug.logMessage("enableTrailing = " + TungstenConfig.get().enableTrailing);
				return SINGLE_SUCCESS;
			})
			.then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
				TungstenConfig.get().enableTrailing = BoolArgumentType.getBool(context, "enabled");
				TungstenConfig.save();
				Debug.logMessage("enableTrailing = " + TungstenConfig.get().enableTrailing);
				return SINGLE_SUCCESS;
			})));

		// ;settings debugTime [true/false]
		builder.then(literal("debugTime")
			.executes(context -> {
				Debug.logMessage("debugTime = " + TungstenConfig.get().debugTime);
				return SINGLE_SUCCESS;
			})
			.then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
				TungstenConfig.get().debugTime = BoolArgumentType.getBool(context, "enabled");
				TungstenConfig.save();
				Debug.logMessage("debugTime = " + TungstenConfig.get().debugTime);
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
