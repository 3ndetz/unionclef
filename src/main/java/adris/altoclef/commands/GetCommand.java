package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.commandsystem.*;
import adris.altoclef.commandsystem.args.ItemTargetArg;
import adris.altoclef.commandsystem.args.ListArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import net.minecraft.item.ItemStack;

import java.util.List;

public class GetCommand extends Command {

    public GetCommand() throws CommandException {
        super("get", "Get an item/resource",
                new ListArg<>(new ItemTargetArg("stack"), "items")
        );
    }


    private void getItems(AltoClef mod, List<ItemTarget> items) {
        Task targetTask;
        if (items == null || items.isEmpty()) {
            mod.log("You must specify at least one item!");
            finish();
            return;
        }
        if (items.size() == 1) {
            targetTask = TaskCatalogue.getItemTask(items.getFirst());
        } else {
            targetTask = TaskCatalogue.getSquashedItemTask(items.toArray(new ItemTarget[0]));
        }
        if (targetTask != null) {
            mod.runUserTask(targetTask, this::finish);
        } else {
            finish();
        }
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        List<ItemTarget> items = parser.get(List.class);

        getItems(mod, items);
    }
}