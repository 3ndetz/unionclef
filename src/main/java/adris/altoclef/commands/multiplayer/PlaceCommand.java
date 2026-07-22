package adris.altoclef.commands.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.args.StringArg;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/*
 * @place <block>
 *
 * Place ONE block of <block> somewhere reachable nearby ("bear strats" — the task picks the
 * spot itself). The bot MUST already have the block in its inventory. The agent decides what
 * to place and why; combine several @place calls (+ @goto / lookAt to position) to build.
 *
 * Examples:
 *   @place stone
 *   @place oak_planks
 *   @place cobblestone
 */
public class PlaceCommand extends Command {

    public PlaceCommand() {
        super("place", "Place a block of <block> somewhere nearby (must have it in inventory)",
                new StringArg("block", null));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String name = parser.get(String.class);
        if (name == null || name.isBlank()) {
            mod.log("Usage: @place <block>  (e.g. @place stone). You must HAVE the block in inventory.");
            finish();
            return;
        }
        name = name.trim().toLowerCase();
        if (name.contains(":")) name = name.substring(name.indexOf(':') + 1);
        Identifier id;
        try {
            id = Identifier.of("minecraft", name);
        } catch (Exception e) {
            mod.log("Bad block name '" + name + "': " + e.getMessage());
            finish();
            return;
        }
        // DefaultedRegistry.get(Identifier) → Block (AIR for unknown). The //$$ line guards the
        // 1.21.11 build from the preprocessor remapping .get( → .getEntry( (which returns Optional).
        //#if MC >= 12111
        //$$ Block block = Registries.BLOCK.get(id);
        //#else
        Block block = Registries.BLOCK.get(id);
        //#endif
        if (block == Blocks.AIR) {
            mod.log("Unknown block '" + name + "'. Use a vanilla id like stone / dirt / oak_planks / cobblestone.");
            finish();
            return;
        }
        Debug.logMessage("@place: placing " + name + " nearby (needs it in inventory)");
        mod.runUserTask(new PlaceBlockNearbyTask(block), this::finish);
    }
}
