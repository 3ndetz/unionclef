package kaptainwutax.tungsten.path.specialMoves;

import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.world.WorldView;

/**
 * Batched slime-bounce trajectory: one macro-move covering the whole arc,
 * so the physics A* tree stays small.
 *
 * Two entry states:
 *  - standing still on slime (velY ~ 0): press jump for the first ground ticks
 *    to initiate a bounce, then ride the arc;
 *  - just landed with an inverted (upward) velocity from a fall: never press
 *    jump — on the landing tick jump() would REPLACE the big bounce velocity
 *    with the ordinary 0.42 jump. Just ride.
 *
 * While airborne: forward + sprint toward the target for air control.
 * Ends on the first landing after being airborne (or the tick cap).
 */
public class SlimeBounceMove {

	private static final int TICK_CAP = 90;
	private static final double COST_PER_TICK = 0.1;

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		WorldView world = TungstenModDataContainer.world;
		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(
				parent.agent.getPos(), nextBlockNode.getPos(true));

		// Arriving with upward velocity means the bounce already happened this tick.
		boolean initiate = parent.agent.velY <= 0.1;

		Node newNode = parent;
		boolean wasInAir = false;

		for (int tick = 0; tick < TICK_CAP; tick++) {
			boolean jump = initiate && !wasInAir && newNode.agent.onGround && tick < 3;

			newNode = new Node(newNode, world,
					new PathInput(true, false, false, false, jump, false, true,
							parent.agent.pitch, desiredYaw),
					new Color(255, 100, 255), newNode.cost + COST_PER_TICK);

			if (!newNode.agent.onGround) wasInAir = true;
			else if (wasInAir) break; // landed after the arc
		}

		return newNode;
	}
}
