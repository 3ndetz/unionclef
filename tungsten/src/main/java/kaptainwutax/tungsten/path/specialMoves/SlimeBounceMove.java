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

public class SlimeBounceMove {

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		double cost = 0.1;
		WorldView world = TungstenModDataContainer.world;
		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(
				parent.agent.getPos(), nextBlockNode.getPos(true));

		Node newNode = parent;
		boolean hasBounced = false;
		boolean wasInAir = false;
		int limit = 0;

		// Phase 1: jump on slime to initiate bounce
		// Phase 2: ride the bounce arc toward target
		// Phase 3: land
		while (limit < 80) {
			limit++;
			boolean onGround = newNode.agent.onGround;
			boolean jump = onGround && limit < 5; // jump on first few ground ticks

			newNode = new Node(newNode, world,
					new PathInput(true, false, false, false, jump, false, true,
							parent.agent.pitch, desiredYaw),
					new Color(255, 100, 255), newNode.cost + cost);

			if (!newNode.agent.onGround) wasInAir = true;
			if (wasInAir && newNode.agent.onGround) {
				hasBounced = true;
				break; // landed after bounce
			}
		}

		return newNode;
	}
}
