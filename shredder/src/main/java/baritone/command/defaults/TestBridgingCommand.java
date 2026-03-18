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
import baritone.api.pathing.goals.GoalXZ;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class TestBridgingCommand extends Command {

    protected TestBridgingCommand(IBaritone baritone) {
        super(baritone, "testbridging", "testbridge", "tb");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        double distance = 30;
        if (args.hasAny()) {
            distance = args.getAs(Double.class);
        }
        args.requireMax(0);

        // Enable jump bridging
        Baritone.settings().jumpBridging.value = true;
        logDirect("Jump bridging enabled");

        // Go forward in the direction the player is looking
        GoalXZ goal = GoalXZ.fromDirection(
                ctx.playerFeetAsVec(),
                ctx.player().getHeadYaw(),
                distance
        );
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
        logDirect(String.format("Bridging toward %s (%.0f blocks)", goal, distance));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Test jump bridging forward";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Enables jump bridging and paths forward in the direction you're looking.",
                "Stand at the edge of a gap, look in the bridging direction, and run this.",
                "",
                "Usage:",
                "> testbridging - bridge 30 blocks forward",
                "> testbridging <distance> - bridge N blocks forward"
        );
    }
}
