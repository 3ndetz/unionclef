package kaptainwutax.tungsten.path.specialMoves.neo;

import java.util.stream.Stream;

import com.google.common.collect.Streams;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.helpers.render.RenderHelper;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.PathInput;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.WorldView;

public class NeoJump {
	
	public static Node generateMove(Node parent, BlockNode nextBlockNode) {
		WorldView world = TungstenModDataContainer.world;
		Agent agent = parent.agent;
		
		Direction jumpTowardsDirection = DirectionHelper.getHorizontalDirectionFromPos(nextBlockNode.previous.getPos(true), nextBlockNode.getPos(true));
		float jumpTowardsRotation = getDirectionDegrees(jumpTowardsDirection);
		Direction neoDirection = nextBlockNode.getNeoSide();
		float neoRotation = getDirectionDegrees(neoDirection);

		float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
        double distance = DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true));
	    Node newNode = new Node(parent, world, new PathInput(false, false, false, false, false, false, false, agent.pitch, desiredYaw),
	    				new Color(0, 255, 150), parent.cost + 0.25);
        
        // Go forward to edge and jump
        boolean jump = false;
        int limit = 0;
//        desiredYaw = nudgeRotation(jumpTowardsRotation, 30);
	      if (neoRotation == 180 || neoRotation == 0)
	    	  desiredYaw = nudgeRotation(neoRotation, -35);
	      if (neoRotation == 270 || neoRotation == 90)
	    	  desiredYaw = nudgeRotation(neoRotation, 35);

        while (limit < 40 && jump == false && newNode.agent.getPos().y > nextBlockNode.getBlockPos().getY()-1) {
            Box adjustedBox = newNode.agent.box.offset(0, -0.5, 0).expand(-0.04, 0, -0.04);
        	limit++;
        	Stream<VoxelShape> blockCollisions = Streams.stream(agent.getBlockCollisions(TungstenModDataContainer.world, adjustedBox));
//        	RenderHelper.renderNode(newNode);
            if (blockCollisions.findAny().isEmpty()) {
        		desiredYaw = nudgeRotation(jumpTowardsRotation, 5);
        		jump = true;
        	}
            newNode = new Node(newNode, world, new PathInput(true, false, false, false, jump, false, true, agent.pitch, desiredYaw),
            		new Color(0, 255, 150), newNode.cost + 0.25);
            if (jump) break;
        }
        

        limit = 0;
        while (limit < 40 && !newNode.agent.onGround && newNode.agent.getPos().y > nextBlockNode.getBlockPos().getY()-1) {
        	limit++;
//        	RenderHelper.renderNode(newNode);
//            try {
//				Thread.sleep(50);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
            newNode = new Node(newNode, world, new PathInput(true, false, false, false, false, false, true, agent.pitch, 
            		(neoRotation == 270 || neoRotation == 90) ?
            			nudgeRotation(jumpTowardsRotation, distance < 2 ? 65 : 35)
        			:
        				nudgeRotation(jumpTowardsRotation, distance < 2 ? -65 : -35)
            		
            		),
            		new Color(0, 255, 150), newNode.cost + 0.25);
        }
            
        return newNode;
	}
	
	private static float nudgeRotation(float rotation, float nudgeAmount) {
		return DirectionHelper.calcYawFromRotation(rotation + nudgeAmount);
	}

	// Direction.getPositiveHorizontalDegrees() removed in MC 1.21
	private static float getDirectionDegrees(Direction dir) {
		switch (dir) {
			case SOUTH: return 0f;
			case WEST:  return 90f;
			case NORTH: return 180f;
			case EAST:  return 270f;
			default:    return 0f;
		}
	}
	
}

