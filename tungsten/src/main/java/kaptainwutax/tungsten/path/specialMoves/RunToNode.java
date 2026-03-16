package kaptainwutax.tungsten.path.specialMoves;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.BlockStateChecker;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.MathHelper;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.world.WorldView;

public class RunToNode {


	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		WorldView world = TungstenModDataContainer.world;
		Agent agent = parent.agent;

		float desiredYaw = (float) (DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true)));
		double distance = DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true));
		double closestDistance = Double.MAX_VALUE;
	    Node newNode = new Node(parent, world, new PathInput(false, false, false, false, false, false, true, agent.pitch, desiredYaw),
	    				new Color(0, 255, 150), parent.cost + 0.5D);
	    Node lastHigheastNodeSinceGround = null;
	    boolean jump = false;
        int limit = 0;
        while (limit < 200) {
        	limit++;
//        	RenderHelper.renderNode(newNode);
//        	try {
//				Thread.sleep(2);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
        	
        	if (lastHigheastNodeSinceGround != null && lastHigheastNodeSinceGround.agent.blockY - newNode.agent.blockY > 30) {
        		break;
        	}

        	if (closestDistance > distance) {
        		closestDistance = distance;
        	} else {
        		
//        		while (distance < 0.1) {
//        			distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));
//            		newNode = newNode.parent;
//				}
        		break;
        	}

			if (newNode.agent.onGround || lastHigheastNodeSinceGround != null && lastHigheastNodeSinceGround.agent.getPos().y < newNode.agent.getPos().y) {
				lastHigheastNodeSinceGround = newNode;
			} else if (lastHigheastNodeSinceGround != null
					&& (!TungstenModDataContainer.ignoreFallDamage
					&& !BlockStateChecker.isAnyWater(world.getBlockState(newNode.agent.getLandingPos(world))))
					&& DistanceCalculator.getJumpHeight(lastHigheastNodeSinceGround.agent.getPos().y, newNode.agent.getPos().y) < -3) {
				newNode = new Node(newNode, world, new PathInput(true, false, false, false, false, false, true, parent.agent.pitch, desiredYaw),
	            		new Color(255, 0, 0), newNode.cost + 3);
				break;
			}
//			desiredYaw = (float) DirectionHelper.calcYawFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
			
			if (newNode.agent.horizontalCollision && nextBlockNode.getBlockPos().getY() - newNode.agent.blockY >= 1) {
				jump = true;
				if (newNode.parent.agent.onGround && !newNode.agent.horizontalCollision) {
					newNode = newNode.parent;
				}
			} else if (newNode.agent.horizontalCollision) {
				while (newNode.agent.horizontalCollision) {
					newNode = newNode.parent;
				}
				break;
			} else {
				jump = false;
			}
			
			newNode = new Node(newNode, world, new PathInput(true, false, false, false, jump, false, true, parent.agent.pitch, desiredYaw ),
            		new Color(0, 255, 150), newNode.cost + 0.22D);
            if (newNode.agent.isClimbing(world)) newNode.cost += 1.8;
			distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));

        }
        
        return newNode;
	}
}
