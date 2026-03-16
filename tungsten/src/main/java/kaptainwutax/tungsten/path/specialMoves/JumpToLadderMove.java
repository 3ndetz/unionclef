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

public class JumpToLadderMove {

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		double cost = 0.0004;
		WorldView world = TungstenModDataContainer.world;
		Agent agent = parent.agent;
		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		double distance = DistanceCalculator.getEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true));
	    Node newNode = new Node(parent, world, new PathInput(false, false, false, false, false, false, false, parent.agent.pitch, desiredYaw),
	    				new Color(0, 255, 150), parent.cost + cost);
		int limit = 0;
        // Run forward to the node
//		RenderHelper.clearRenderers();
		desiredYaw = (float) DirectionHelper.calcYawFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
		if (distance < 0.2) return newNode;
		
		if (newNode.agent.getPos().y > nextBlockNode.getPos(true).y) {
			while (distance > 0.05 && limit < 10) {
//	        	if (newNode.agent.blockY < nextBlockNode.getBlockPos().getY()-1) break;
				
	        	limit++;
	            newNode = new Node(newNode, world, new PathInput(DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true)) > 1, false, false, false, false, false, true, parent.agent.pitch, desiredYaw),
	            		new Color(0, 255, 150), newNode.cost + cost);
	    		distance = DistanceCalculator.getEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));

//	        	RenderHelper.renderNode(newNode);
//	        	try {
//					Thread.sleep(50);
//				} catch (InterruptedException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
	            
	        }
	            
	        return newNode;
		}
		while (distance > 0.05 && limit < 10) {
//        	if (newNode.agent.blockY < nextBlockNode.getBlockPos().getY()-1) break;
			
        	limit++;
            newNode = new Node(newNode, world, new PathInput(true, false, false, false, newNode.agent.onGround || newNode.agent.isClimbing(world) && newNode.agent.getPos().y > nextBlockNode.getPos(true).y, false, true, parent.agent.pitch, desiredYaw),
            		new Color(0, 255, 150), newNode.cost + cost);
    		distance = DistanceCalculator.getEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));

//        	RenderHelper.renderNode(newNode);
//        	try {
//				Thread.sleep(50);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
            
        }
            
        return newNode;
	}

}
