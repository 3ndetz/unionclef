package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.butler.ButlerConfig;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.SettingNameArg;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.helpers.SettingsReflectionHelper;

import java.util.List;

public class SetSettingsCommand extends Command {

    public SetSettingsCommand() throws CommandException {
        super("set", "set <setting_name> <new_value> | set list",
                new SettingNameArg("setting"),
                new StringArg("new value", null));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String settingName = parser.get(String.class).toLowerCase();

        if (settingName.equals("list")) {
            listAllSettings(mod);
            finish();
            return;
        }

        String newValue = parser.get(String.class);
        if (newValue == null) {
            // Show current value
            var val = SettingsReflectionHelper.getSetting(mod.getModSettings(), settingName);
            if (val.isEmpty()) val = SettingsReflectionHelper.getSetting(ButlerConfig.getInstance(), settingName);
            if (val.isPresent()) {
                mod.log(settingName + " = " + val.get());
            } else {
                mod.log("Настройка '" + settingName + "' не найдена.");
            }
            finish();
            return;
        }

        boolean success = false;

        if (SettingsReflectionHelper.setSetting(mod.getModSettings(), settingName, newValue)) {
            ConfigHelper.saveConfig("altoclef_settings.json", mod.getModSettings());
            ConfigHelper.reloadAllConfigs();
            mod.log("Настройка успешно обновлена!");
            success = true;
        } else if (SettingsReflectionHelper.setSetting(ButlerConfig.getInstance(), settingName, newValue)) {
            ConfigHelper.saveConfig("configs/butler.json", ButlerConfig.getInstance());
            ConfigHelper.reloadAllConfigs();
            mod.log("Butler настройка успешно обновлена!");
            success = true;
        }

        if (!success) {
            mod.log("Настройка '" + settingName + "' не найдена. Используй '@set list' для списка.");
        }

        finish();
    }

    private void listAllSettings(AltoClef mod) {
        mod.log("=== Доступные настройки ===");
        mod.log("Основные (altoclef_settings.json):");
        List<SettingsReflectionHelper.SettingInfo> main =
                SettingsReflectionHelper.getSettableFields(mod.getModSettings());
        for (SettingsReflectionHelper.SettingInfo s : main) {
            mod.log("  " + s);
        }
        mod.log("Butler (configs/butler.json):");
        List<SettingsReflectionHelper.SettingInfo> butler =
                SettingsReflectionHelper.getSettableFields(ButlerConfig.getInstance());
        for (SettingsReflectionHelper.SettingInfo s : butler) {
            mod.log("  " + s);
        }
    }
}
