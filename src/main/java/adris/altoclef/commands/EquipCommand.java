package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.*;
import adris.altoclef.commandsystem.args.CataloguedItemArg;
import adris.altoclef.commandsystem.args.ListArg;
import adris.altoclef.commandsystem.exception.BadCommandSyntaxException;
import adris.altoclef.commandsystem.exception.CommandException;
import adris.altoclef.commandsystem.exception.RuntimeCommandException;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
//#if MC < 12111
import net.minecraft.item.Equipment;
//#endif
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;


public class EquipCommand extends Command {

    public EquipCommand() {
        super("equip", "Equips items",
                new ListArg<>(new EquipmentItemArg("equipment"), "[equippable_items]")
                        .addAlias("leather", catalogueNames(ItemHelper.LEATHER_ARMORS))
                        .addAlias("iron", catalogueNames(ItemHelper.IRON_ARMORS))
                        .addAlias("gold", catalogueNames(ItemHelper.GOLDEN_ARMORS))
                        .addAlias("golden", catalogueNames(ItemHelper.GOLDEN_ARMORS))
                        .addAlias("diamond", catalogueNames(ItemHelper.DIAMOND_ARMORS))
                        .addAlias("netherite", catalogueNames(ItemHelper.NETHERITE_ARMORS))
        );
    }

    /**
     * Alias values must be CATALOGUE names ("iron_helmet"): that is what the argument parser
     * yields and what {@link ItemTarget#ItemTarget(String)} resolves. The aliases used to be built
     * from {@code Item::toString}, which is the namespaced registry id ("minecraft:iron_helmet")
     * and unknown to the catalogue, so no alias could ever resolve. "iron" also pointed at the
     * golden set and "gold" at the iron one.
     */
    private static List<String> catalogueNames(Item[] items) {
        return Arrays.stream(items).map(ItemHelper::stripItemName).toList();
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        // The list argument is built from EquipmentItemArg, an Arg<String>: the parser hands back
        // catalogue NAMES, not ItemTargets. Reading it as List<ItemTarget> compiled (erasure) and
        // threw ClassCastException on the first element at runtime, so `@equip` had never run once
        // since the monorepo was created. Found on the stand 2026-09-10 while trying to exercise
        // the armor-equip fix through it.
        List<String> names = parser.get(List.class);
        ItemTarget[] items = names.stream().map(ItemTarget::new).toArray(ItemTarget[]::new);

        for (ItemTarget target : items) {
            for (Item item : target.getMatches()) {
                if (!canBeEquipped(item)) {
                    throw new RuntimeCommandException("'" + ItemHelper.stripItemName(item).toUpperCase() + "' cannot be equipped!");
                }
            }
        }

        mod.runUserTask(new EquipArmorTask(items), this::finish);
    }

    /**
     * Whether {@link EquipArmorTask} knows how to put this item on: armor (anything the game
     * itself would equip to a body slot) or a shield in the off hand.
     */
    private static boolean canBeEquipped(Item item) {
        //#if MC < 12111
        return item instanceof Equipment;
        //#else
        //$$ // The Equipment interface is gone in 1.21.11; the EQUIPPABLE component now says which
        //$$ // slot an item goes to, and ItemHelper.getArmorSlot reads it. Shields carry no such
        //$$ // component (they are held, not worn) and stay a special case, as in EquipArmorTask.
        //$$ return item == Items.SHIELD || ItemHelper.getArmorSlot(item) != null;
        //#endif
    }


    // this is kinda meh way to do it
    private static class EquipmentItemArg extends CataloguedItemArg {

        public EquipmentItemArg(String name) {
            super(name);
        }

        @Override
        protected StringParser<String> getParser() {
            return this::parseLocal;
        }

        private String parseLocal(StringReader reader) throws CommandException {
            StringParser<String> parentParser = super.getParser();

            ParseResult result = getSupplied(reader.copy(), parentParser);
            if (result == ParseResult.NOT_FINISHED) {
                String first = reader.peek();
                if (getSuggestions(null).noneMatch(s -> s.startsWith(first))) {
                    throw new BadCommandSyntaxException("Not equipment named '"+first+"' exists");
                }
            }

            String parsed = parentParser.parse(reader);

            if (!isEquipment(parsed)) {
                throw new BadCommandSyntaxException("Item '"+parsed+"' is not an equipment");
            }

            return parsed;
        }

        @Override
        public Stream<String> getSuggestions(StringReader reader) {
            return super.getSuggestions(reader).filter(EquipmentItemArg::isEquipment);
        }

        private static boolean isEquipment(String cataloguedItem) {
            return Arrays.stream(new ItemTarget(cataloguedItem).getMatches()).anyMatch(EquipCommand::canBeEquipped);
        }
    }


}
