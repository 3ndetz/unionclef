package kaptainwutax.tungsten.path.specialMoves;

import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.world.WorldView;

public class ExitWaterMove {

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
	    if (!parent.agent.touchingWater) return parent;
		double cost = 0.00002;
		WorldView world = TungstenModDataContainer.world;
		Agent agent = parent.agent;
		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		float desiredPitch = (float) DirectionHelper.calcPitchFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		double distance = DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true));
		double closestDistance = Double.MAX_VALUE;
	    Node newNode = new Node(parent, world, new PathInput(false, false, false, false, false, false, false, desiredPitch, desiredYaw),
	    				new Color(0, 255, 150), parent.cost + 0.0001);
		int limit = 0;
        // Run forward to the node
        // ⛔ FIXED 2026-09-05: `closestDistance` was declared and initialized but never read or
        // updated -- a vestigial no-op left over from the sibling SwimmingMove.generateMove(),
        // which uses the same variable name for a real anti-stall break ("if distance stopped
        // improving, stop generating ticks"). Without it, and without SwimmingMove's
        // `!horizontalCollision` loop guard, this loop had no way to bail early: an agent that
        // got stuck on a bank lip while exiting water would still burn the full 40-tick budget
        // pressing forward into the obstruction instead of stopping like SwimmingMove does.
		while (distance > 0.2 && limit < 40 && !newNode.agent.horizontalCollision) {
        	limit++;
    		desiredYaw = (float) DirectionHelper.calcYawFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
    		desiredPitch = (float) DirectionHelper.calcPitchFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
            newNode = new Node(newNode, world, new PathInput(true, false, false, true, true, false, true, desiredPitch, desiredYaw + 45),
            		new Color(0, 255, 150), newNode.cost + cost);
            // distance recomputed AFTER the move, matching SwimmingMove's anti-stall check exactly
            distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));

            if (closestDistance > distance) {
                closestDistance = distance;
            } else {
                break;
            }
        }

        return newNode;
	}

}
