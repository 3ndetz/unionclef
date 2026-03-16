package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.util.helpers.ConfigHelper;

import java.util.Arrays;

public class CustomCommand extends Command {
    private static CustomTaskConfig _ctc;

    static {
        ConfigHelper.loadConfig("configs/CustomTasks.json", CustomTaskConfig::new, CustomTaskConfig.class, newConfig -> _ctc = newConfig);
    }

    public CustomCommand() throws CommandException {
        super(_ctc.prefix, "Run a custom task sequence defined in configs/CustomTasks.json",
                new StringArg("task name"));
    }

    public static CustomTaskConfig getConfig() {
        return _ctc;
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String customCommand = parser.get(String.class);

        int commandIndex = -1;
        for (int i = 0; i < _ctc.customTasks.length; i++) {
            if (_ctc.customTasks[i].name.equalsIgnoreCase(customCommand)) {
                commandIndex = i;
                break;
            }
        }

        if (commandIndex < 0) {
            mod.log("Custom task '" + customCommand + "' not found.");
            finish();
            return;
        }

        StringBuilder commandToExecute = new StringBuilder();
        CustomTaskConfig.CustomTaskEntry entry = _ctc.customTasks[commandIndex];

        for (int i = 0; i < entry.tasks.length; i++) {
            if (i > 0) commandToExecute.append(";");
            CustomTaskConfig.CustomTaskEntry.CustomSubTaskEntry sub = entry.tasks[i];
            commandToExecute.append(sub.command).append(" ");
            if (sub.command.equals("get") || sub.command.equals("equip")) {
                commandToExecute.append("[");
                for (int j = 0; j < sub.parameters.length; j++) {
                    commandToExecute.append(Arrays.toString(sub.parameters[j])
                            .replaceAll("\\[", "").replaceAll("]", "").replaceAll(",", ""));
                    if (j < sub.parameters.length - 1) commandToExecute.append("?");
                }
                commandToExecute.append("]");
            } else {
                commandToExecute.append(Arrays.toString(sub.parameters[0])
                        .replaceAll("\\[", "").replaceAll("]", ""));
            }
        }

        AltoClef.getCommandExecutor().execute(
                mod.getModSettings().getCommandPrefix()
                        + commandToExecute.toString().replaceAll(",", "").replaceAll("\\?", ","));
        finish();
    }
}
