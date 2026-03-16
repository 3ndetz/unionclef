package kaptainwutax.tungsten.path.specialMoves;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.BlockStateChecker;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.world.WorldView;

public class SprintJumpMove {

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		double cost = 0.004;
		WorldView world = TungstenModDataContainer.world;
		Agent agent = parent.agent;
		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		double distance = DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true));
		double closestDistance = Double.MAX_VALUE;
	    Node newNode = new Node(parent, world, new PathInput(false, false, false, false, false, false, false, parent.agent.pitch, desiredYaw),
	    				new Color(0, 255, 150), parent.cost + cost);
		int limit = 0;
		Node lastHigheastNodeSinceGround = null;
        // Run forward to the node
//		TungstenMod.RENDERERS.clear();
		desiredYaw = (float) DirectionHelper.calcYawFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
		if (distance < 0.8) return newNode;
		while (distance > 0.95 && limit < 500 && !newNode.agent.horizontalCollision && !newNode.agent.isInLava() || (distance <= 0.3 && !newNode.agent.onGround)) {
//        	RenderHelper.renderNode(newNode);
//        	try {
//				Thread.sleep(50);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
//        	if (newNode.agent.blockY < nextBlockNode.getBlockPos().getY()-1) break;

			if (newNode.agent.onGround || lastHigheastNodeSinceGround != null && lastHigheastNodeSinceGround.agent.getPos().y < newNode.agent.getPos().y) {
				lastHigheastNodeSinceGround = newNode;
			} else if (lastHigheastNodeSinceGround != null
					&& (!TungstenModDataContainer.ignoreFallDamage
					&& !BlockStateChecker.isAnyWater(world.getBlockState(newNode.agent.getLandingPos(world))))
					&& DistanceCalculator.getJumpHeight(lastHigheastNodeSinceGround.agent.getPos().y, newNode.agent.getPos().y) < -3) {
				newNode = new Node(newNode, world, new PathInput(true, false, false, false, true, false, true, parent.agent.pitch, desiredYaw),
	            		new Color(255, 0, 0), newNode.cost + cost);
				break;
			}
			
        	limit++;
    		distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));
            newNode = new Node(newNode, world, new PathInput(true, false, false, false, newNode.agent.onGround, false, true, parent.agent.pitch, desiredYaw),
            		new Color(0, 255, 150), newNode.cost + cost);
            if (newNode.agent.isClimbing(world)) newNode.cost += 12.8;
            float forwardSpeedScore = 0.98f - Math.abs(newNode.agent.forwardSpeed);
//	    	float sidewaysSpeedScore = 0.98f - Math.abs(newNode.agent.sidewaysSpeed);
//	    	Debug.logMessage("" + forwardSpeedScore);
	    	newNode.cost += 
//	    			(sidewaysSpeedScore > 1e-8 || sidewaysSpeedScore < -1e-8 ? 5 : 0 ) 
	    			 (forwardSpeedScore > 1e-8 || forwardSpeedScore < -1e-8 ? 15 : 0 )
	    			 + (forwardSpeedScore );
        	if (closestDistance > distance) {
        		closestDistance = distance;
        	} else {
        		break;
        	}
            
        }
            
        return newNode;
	}

}
