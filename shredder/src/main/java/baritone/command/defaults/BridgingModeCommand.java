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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidTypeException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class BridgingModeCommand extends Command {

    private static final Set<String> MODES = Set.of("slow", "standard", "back_jump", "jump");

    protected BridgingModeCommand(IBaritone baritone) {
        super(baritone, "bridgingmode");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            // No args — show current mode
            logDirect("Bridging mode: " + Baritone.settings().bridgingMode.value);
            return;
        }
        String mode = args.getString().toLowerCase();
        args.requireMax(0);

        if (!MODES.contains(mode)) {
            throw new CommandInvalidTypeException(args.consumed(), "one of: slow, standard, back_jump, jump");
        }

        Baritone.settings().bridgingMode.value = mode;
        logDirect("Bridging mode set to: " + mode);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return MODES.stream().sorted();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Set bridging mode";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Sets how the bot bridges across gaps.",
                "",
                "Modes:",
                "  slow      — (default) sneak to edge, place calmly",
                "  standard  — original baritone bridging",
                "  back_jump — face backward, walk backward + jump, place airborne",
                "  jump      — sprint-jump forward, snap rotation backward, place at speed",
                "",
                "Usage:",
                "> bridgingMode        — show current mode",
                "> bridgingMode <mode> — set mode"
        );
    }
}
