package kaptainwutax.tungsten.path.specialMoves;

import java.util.stream.Stream;

import com.google.common.collect.Streams;

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
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.WorldView;

public class LongJump {

	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		double cost = 0.2;
		WorldView world = TungstenModDataContainer.world;
		Agent agent = parent.agent;
		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
		double distance = DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true));
	    Node newNode = new Node(parent, world, new PathInput(false, false, false, false, false, false, false, agent.pitch, desiredYaw),
	    				new Color(0, 255, 150), parent.cost + 0.1);
	    // Go back if we are too close to the edge to jump
	    if (distance > 4 && DistanceCalculator.getDistanceToEdge(newNode.agent) < 0.8 && AgentChecker.isAgentStationary(newNode.agent, 0.07)) {
	        for (int j = 0; j < 6; j++) {
	        	if (newNode.agent.horizontalCollision) break;
	            Box adjustedBox = newNode.agent.box.offset(0, -0.5, 0).expand(-0.45, 0, -0.45);
	        	Stream<VoxelShape> blockCollisions = Streams.stream(agent.getBlockCollisions(TungstenModDataContainer.world, adjustedBox));
	        	if (j > 1 && blockCollisions.findAny().isEmpty()) break;
	        	desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
	            newNode = new Node(newNode, world, new PathInput(false, true, false, false, false, false, false, agent.pitch, desiredYaw),
	            		new Color(0, 255, 150), newNode.cost + cost + 5);
	        }
	    }
        
        boolean jump = false;
        int limit = 0;
        double boxExpension = -0.001;
		if (distance > 1.0) {
			distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));
	        // Go forward to edge and jump
	        while (limit < 10) {
	        	if (newNode.agent.horizontalCollision) break;
	            Box adjustedBox = newNode.agent.box.offset(0, -0.5, 0).expand(boxExpension, 0, boxExpension);
	        	limit++;
	        	if (distance > 3) {
		        	Stream<VoxelShape> blockCollisions = Streams.stream(newNode.agent.getBlockCollisions(TungstenModDataContainer.world, adjustedBox));
		            if (blockCollisions.findAny().isEmpty()) jump = true;
	        	} else {
	        		if (DistanceCalculator.getDistanceToEdge(newNode.agent) < 0.6) jump = true;
	        	}
	            if (!newNode.agent.onGround) break;
	
	    		desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
	            newNode = new Node(newNode, world, new PathInput(true, false, false, false, jump, false, true, agent.pitch, desiredYaw),
	            		new Color(0, 255, 150), newNode.cost + cost);
	        }
	        limit = 0;
    		desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
	        while (limit < 22 && !newNode.agent.onGround && newNode.agent.getPos().y > nextBlockNode.getBlockPos().getY()-1) {
	        	if (newNode.agent.horizontalCollision) break;
	            newNode = new Node(newNode, world, new PathInput(true, false, false, false, false, false, false, agent.pitch, desiredYaw),
	            		new Color(distance < 0.4 ? 180 : 0, 255, 150), newNode.cost + cost);
	        	limit++;
	        }
            newNode = new Node(newNode, world, new PathInput(true, false, false, false, false, false, false, agent.pitch, desiredYaw),
            		new Color(distance < 0.4 ? 180 : 0, 255, 150), newNode.cost + cost);
		} else {
			limit = 0;
	        // Run forward to the node
			while (distance > 0.2 && limit < 22) {
	        	if (newNode.agent.horizontalCollision) break;
	        	limit++;
	    		distance = DistanceCalculator.getHorizontalEuclideanDistance(newNode.agent.getPos(), nextBlockNode.getPos(true));
	            newNode = new Node(newNode, world, new PathInput(true, false, false, false, jump, false, false, agent.pitch, desiredYaw),
	            		new Color(0, 255, 150), newNode.cost + cost);
	        }
		}
            
        return newNode;
	}

}
