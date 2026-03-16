package kaptainwutax.tungsten.path.specialMoves;


import java.util.stream.Stream;

import com.google.common.collect.Streams;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.AgentChecker;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.world.WorldView;

public class DivingMove {

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		double cost = 0.00002;
		WorldView world = TungstenModDataContainer.world;
		Agent agent = parent.agent;
		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		float desiredPitch = (float) DirectionHelper.calcPitchFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		double distance = DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true));
	    Node newNode = new Node(parent, world, parent.input == null ? new PathInput(false, false, false, false, false, false, false, desiredPitch, desiredYaw) : parent.input,
	    				new Color(0, 255, 150), parent.cost + 0.0001);
		int limit = 0;
		double heightDiff = DistanceCalculator.getJumpHeight(newNode.agent.getPos().y, nextBlockNode.getPos(true).y);
		if (distance < 2.8 && distance > 0.8) {
            limit = 0;
			while (heightDiff > 0.8 && limit < 20 && newNode.agent.touchingWater) {
	        	RenderHelper.renderNode(newNode);
	        	try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				heightDiff = DistanceCalculator.getJumpHeight(newNode.agent.getPos().y, nextBlockNode.getPos(true).y);
	    		desiredYaw = (float) DirectionHelper.calcYawFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
				desiredPitch = (float) DirectionHelper.calcPitchFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
	            newNode = new Node(newNode, world, new PathInput(true, false, false, false, false, false, true, desiredPitch, desiredYaw),
	            		new Color(0, 25, 150), newNode.cost + cost);
	            limit++;
			}
			return newNode;
		}
		if (distance < 0.8) {
			if (heightDiff > 0) {
	            limit = 0;
				while (heightDiff > 0.8 && limit < 80 && newNode.agent.touchingWater && !newNode.agent.verticalCollision) {
		        	RenderHelper.renderNode(newNode);
		        	try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					heightDiff = DistanceCalculator.getJumpHeight(newNode.agent.getPos().y, nextBlockNode.getPos(true).y);
		            newNode = new Node(newNode, world, new PathInput(false, false, false, false, true, false, false, desiredPitch, desiredYaw),
		            		new Color(0, 85, 150), newNode.cost + cost);
		            limit++;
				}
			} else if (heightDiff < 0) {
	            limit = 0;
				while (heightDiff < 0 && limit < 80 && newNode.agent.touchingWater && !newNode.agent.verticalCollision) {
		        	RenderHelper.renderNode(newNode);
		        	try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					heightDiff = DistanceCalculator.getJumpHeight(newNode.agent.getPos().y, nextBlockNode.getPos(true).y);
		            newNode = new Node(newNode, world, new PathInput(false, false, false, false, false, true, false, desiredPitch, desiredYaw),
		            		new Color(0, 125, 150), newNode.cost + cost);
		            limit++;
				}
			}
			return newNode;
		}
        // Run forward to the node
		while (distance > 0.5 && limit < 150 && newNode.agent.touchingWater && !newNode.agent.horizontalCollision && !newNode.agent.verticalCollision) {
//        	RenderHelper.renderNode(newNode);
//        	try {
//				Thread.sleep(5);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
        	limit++;
    		distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));
    		desiredYaw = (float) DirectionHelper.calcYawFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
    		desiredPitch = (float) DirectionHelper.calcPitchFromVec3d(newNode.agent.getPos(), nextBlockNode.getPos(true));
            newNode = new Node(newNode, world, new PathInput(true, false, false, true, false, false, true, desiredPitch, desiredYaw + 45),
            		new Color(0, 105, 150), newNode.cost + cost);
            
        }
            
        return newNode;
	}

}
