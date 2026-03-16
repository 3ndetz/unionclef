package adris.altoclef.commandsystem;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.util.ItemTarget;

import java.util.HashMap;

public class ItemList {
    public ItemTarget[] items;

    public ItemList(ItemTarget[] items) {
        this.items = items;
    }

}
