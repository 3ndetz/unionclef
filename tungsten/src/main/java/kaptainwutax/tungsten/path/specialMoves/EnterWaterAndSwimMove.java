package kaptainwutax.tungsten.path.specialMoves;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;

public class EnterWaterAndSwimMove {

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		if (!parent.agent.touchingWater) {
			if (parent.agent.canSprint()) {
		    	Node sprintJumpMove = SprintJumpMove.generateMove(parent, nextBlockNode);
		    	if (sprintJumpMove.agent.touchingWater) {
		    		Node swimmingMove = SwimmingMove.generateMove(sprintJumpMove, nextBlockNode);
		    		return swimmingMove;
		    	}
			} else {
		    	Node walkMove = WalkToNode.generateMove(parent, nextBlockNode);
		    	if (walkMove.agent.touchingWater) {
		    		Node swimmingMove = SwimmingMove.generateMove(walkMove, nextBlockNode);
		    		RenderHelper.renderPathSoFar(swimmingMove);
		    		return swimmingMove;
		    	}
			}
		}
		return parent;
	}
}
