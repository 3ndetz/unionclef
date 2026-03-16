package kaptainwutax.tungsten.path.specialMoves;

import java.util.stream.Stream;

import com.google.common.collect.Streams;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.AgentChecker;
import kaptainwutax.tungsten.helpers.BlockStateChecker;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.WorldView;
public class SwimmingMove {

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		double cost = 0.0002;
		WorldView world = TungstenModDataContainer.world;
		Agent agent = parent.agent;
		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		float desiredPitch = (float) DirectionHelper.calcPitchFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		double distance = DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true));
	    Node newNode = new Node(parent, world, new PathInput(false, false, false, false, false, false, false, desiredPitch, desiredYaw),
	    				new Color(0, 255, 150), parent.cost + 0.0001);
		int limit = 0;
		double closestDistance = Double.MAX_VALUE;
        // Run forward to the node
		while (distance > 0.2 && limit < 30 && newNode.agent.touchingWater) {
//        	RenderHelper.renderNode(newNode);
//        	try {
//				Thread.sleep(5);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
        	limit++;
    		distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));
    		
			
    		desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
    		desiredPitch = (float) DirectionHelper.calcPitchFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
    		boolean isWaterAbove = BlockStateChecker.isAnyWater(TungstenModDataContainer.world.getBlockState(newNode.agent.getBlockPos().up()));
    		if (isWaterAbove || (newNode.agent.getPos().y - (int) newNode.agent.getPos().y) < 0.44) {
                newNode = new Node(newNode, world, new PathInput(true, false, false, true, true, false, true, desiredPitch, desiredYaw + 45),
                		new Color(0, 255, 150), newNode.cost + cost);
    		}

    		if (newNode.agent.getPos().y < nextBlockNode.getPos(true).y && distance < 4) {
    			while (newNode.agent.getPos().y < nextBlockNode.getPos(true).y) {
                    newNode = new Node(newNode, world, new PathInput(false, false, false, true, true, false, false, desiredPitch, desiredYaw + 45),
                    		new Color(0, 255, 150), newNode.cost + cost);
    			}
    		} 
			if (closestDistance > distance) {
        		closestDistance = distance;
        	} else {
        		break;
        	}
            int i = 0;
            while (i < 28 && distance > 0.2 && !newNode.agent.horizontalCollision) {
        		distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));
    			
    			if (closestDistance > distance) {
            		closestDistance = distance;
            	} else {
            		break;
            	}
    			
        		desiredPitch = (float) DirectionHelper.calcPitchFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
//            	RenderHelper.renderNode(newNode);
//            	try {
//    				Thread.sleep(2);
//    			} catch (InterruptedException e) {
//    				// TODO Auto-generated catch block
//    				e.printStackTrace();
//    			}
                newNode = new Node(newNode, world, new PathInput(true, false, false, true, false, false, true, 0.25f, desiredYaw + 45),
                		new Color(0, 255, 150), newNode.cost + cost);
                i++;
			}
            
        }
            
        return newNode;
	}

}
