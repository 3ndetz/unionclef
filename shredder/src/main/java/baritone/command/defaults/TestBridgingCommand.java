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
import baritone.api.pathing.goals.GoalBlock;
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

        logDirect("Bridging mode: " + Baritone.settings().bridgingMode.value);

        // Go forward in the direction the player is looking, at the SAME Y level.
        // GoalXZ allows any Y, so the pathfinder would descend instead of bridge.
        // GoalBlock forces horizontal bridging at the current height.
        GoalXZ xzGoal = GoalXZ.fromDirection(
                ctx.playerFeetAsVec(),
                ctx.player().getHeadYaw(),
                distance
        );
        int playerY = ctx.playerFeet().getY();
        GoalBlock goal = new GoalBlock(xzGoal.getX(), playerY, xzGoal.getZ());
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
        logDirect(String.format("Bridging toward %s (%.0f blocks, Y=%d)", goal, distance, playerY));
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
