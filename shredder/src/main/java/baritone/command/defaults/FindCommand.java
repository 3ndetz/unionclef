/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.BlockById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.BetterBlockPos;
import baritone.cache.CachedChunk;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

public class FindCommand extends Command {

    public FindCommand(IBaritone baritone) {
        super(baritone, "find");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        List<Block> toFind = new ArrayList<>();
        while (args.hasAny()) {
            toFind.add(args.getDatatypeFor(BlockById.INSTANCE));
        }
        BetterBlockPos origin = ctx.playerFeet();
        Text[] components = toFind.stream()
                .flatMap(block ->
                        ctx.worldData().getCachedWorld().getLocationsOf(
                                Registries.BLOCK.getId(block).getPath(),
                                Integer.MAX_VALUE,
                                origin.x,
                                origin.y,
                                4
                        ).stream()
                )
                .map(BetterBlockPos::new)
                .map(this::positionToComponent)
                .toArray(Text[]::new);
        if (components.length > 0) {
            Arrays.asList(components).forEach(this::logDirect);
        } else {
            logDirect("No positions known, are you sure the blocks are cached?");
        }
    }

    private Text positionToComponent(BetterBlockPos pos) {
        String positionText = String.format("%s %s %s", pos.x, pos.y, pos.z);
        String command = String.format("%sgoal %s", FORCE_COMMAND_PREFIX, positionText);
        MutableText baseComponent = Text.literal(pos.toString());
        MutableText hoverComponent = Text.literal("Click to set goal to this position");
        baseComponent.setStyle(baseComponent.getStyle()
                .withColor(Formatting.GRAY)
                .withInsertion(positionText)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverComponent)));
        return baseComponent;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return new TabCompleteHelper()
                .append(
                        CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.stream()
                                .map(Registries.BLOCK::getId)
                                .map(Object::toString)
                )
                .filterPrefixNamespaced(args.getString())
                .sortAlphabetically()
                .stream();
    }

    @Override
    public String getShortDesc() {
        return "Find positions of a certain block";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The find command searches through Baritone's cache and attempts to find the location of the block.",
                "Tab completion will suggest only cached blocks and uncached blocks can not be found.",
                "",
                "Usage:",
                "> find <block> [...] - Try finding the listed blocks"
        );
    }
}
