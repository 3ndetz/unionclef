package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.commandsystem.Command;
import net.minecraft.command.CommandSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-generated settings commands from TungstenConfig public fields.
 *
 * ;settings           — list all values
 * ;settings reload    — reload from tungsten.json
 * ;settings <name>    — show current value
 * ;settings <name> <value> — set value + save
 *
 * Also includes legacy: ;settings ignoreFallDamage (not in TungstenConfig).
 */
public class SettingsCommand extends Command {

    public SettingsCommand(TungstenMod mod) {
        super("settings", "Handles bot settings", mod);
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        List<Field> fields = getConfigFields();

        // ;settings — show all
        builder.executes(context -> {
            TungstenConfig c = TungstenConfig.get();
            Debug.logMessage("§e--- Tungsten Settings ---");
            Debug.logMessage("ignoreFallDamage = " + TungstenModDataContainer.ignoreFallDamage);
            for (Field f : fields) {
                try {
                    Debug.logMessage(f.getName() + " = " + f.get(c));
                } catch (Exception ignored) {}
            }
            return SINGLE_SUCCESS;
        });

        // ;settings reload
        builder.then(literal("reload").executes(context -> {
            TungstenConfig.load();
            Debug.logMessage("§aConfig reloaded from tungsten.json");
            return SINGLE_SUCCESS;
        }));

        // ;settings ignoreFallDamage [true/false] — legacy, not in TungstenConfig
        builder.then(literal("ignoreFallDamage")
            .executes(context -> {
                Debug.logMessage("ignoreFallDamage = " + TungstenModDataContainer.ignoreFallDamage);
                return SINGLE_SUCCESS;
            })
            .then(argument("enabled", BoolArgumentType.bool()).executes(context -> {
                TungstenModDataContainer.ignoreFallDamage = BoolArgumentType.getBool(context, "enabled");
                Debug.logMessage("ignoreFallDamage = " + TungstenModDataContainer.ignoreFallDamage);
                return SINGLE_SUCCESS;
            })));

        // auto-generate commands for each TungstenConfig field
        for (Field f : fields) {
            registerField(builder, f);
        }
    }

    private void registerField(LiteralArgumentBuilder<CommandSource> builder, Field field) {
        String name = field.getName();
        Class<?> type = field.getType();

        // ;settings <name> — show value
        var sub = literal(name).executes(context -> {
            try {
                Debug.logMessage(name + " = " + field.get(TungstenConfig.get()));
            } catch (Exception e) {
                Debug.logMessage("§cError reading " + name);
            }
            return SINGLE_SUCCESS;
        });

        // ;settings <name> <value> — set value
        RequiredArgumentBuilder<CommandSource, ?> arg = createArgument(type);
        if (arg != null) {
            sub.then(arg.executes(context -> {
                try {
                    Object value = getArgumentValue(context, "value", type);
                    field.set(TungstenConfig.get(), value);
                    TungstenConfig.save();
                    Debug.logMessage(name + " = " + value);
                } catch (Exception e) {
                    Debug.logMessage("§cError setting " + name + ": " + e.getMessage());
                }
                return SINGLE_SUCCESS;
            }));
        }

        builder.then(sub);
    }

    private static RequiredArgumentBuilder<CommandSource, ?> createArgument(Class<?> type) {
        if (type == boolean.class) {
            return argument("value", BoolArgumentType.bool());
        } else if (type == double.class) {
            return argument("value", DoubleArgumentType.doubleArg());
        } else if (type == float.class) {
            return argument("value", FloatArgumentType.floatArg());
        } else if (type == int.class) {
            return argument("value", IntegerArgumentType.integer());
        }
        return null;
    }

    private static Object getArgumentValue(
            com.mojang.brigadier.context.CommandContext<CommandSource> context,
            String name, Class<?> type) {
        if (type == boolean.class) return BoolArgumentType.getBool(context, name);
        if (type == double.class) return DoubleArgumentType.getDouble(context, name);
        if (type == float.class) return FloatArgumentType.getFloat(context, name);
        if (type == int.class) return IntegerArgumentType.getInteger(context, name);
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    private static List<Field> getConfigFields() {
        List<Field> result = new ArrayList<>();
        for (Field f : TungstenConfig.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers())
                    && !Modifier.isStatic(f.getModifiers())
                    && !Modifier.isFinal(f.getModifiers())) {
                result.add(f);
            }
        }
        return result;
    }
}
