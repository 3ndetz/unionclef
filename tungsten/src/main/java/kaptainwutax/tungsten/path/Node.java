package kaptainwutax.tungsten.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.Streams;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.agent.Agent;
import kaptainwutax.tungsten.helpers.BlockShapeChecker;
import kaptainwutax.tungsten.helpers.BlockStateChecker;
import kaptainwutax.tungsten.helpers.DirectionHelper;
import kaptainwutax.tungsten.helpers.DistanceCalculator;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.path.specialMoves.ClimbALadderMove;
import kaptainwutax.tungsten.path.specialMoves.CornerJump;
import kaptainwutax.tungsten.path.specialMoves.DivingMove;
import kaptainwutax.tungsten.path.specialMoves.EnterWaterAndSwimMove;
import kaptainwutax.tungsten.path.specialMoves.ExitWaterMove;
import kaptainwutax.tungsten.path.specialMoves.JumpToLadderMove;
import kaptainwutax.tungsten.path.specialMoves.LongJump;
import kaptainwutax.tungsten.path.specialMoves.RunToNode;
import kaptainwutax.tungsten.path.specialMoves.SlimeBounceMove;
import kaptainwutax.tungsten.path.specialMoves.SprintJumpMove;
import kaptainwutax.tungsten.path.specialMoves.SwimmingMove;
import kaptainwutax.tungsten.path.specialMoves.TurnACornerMove;
import kaptainwutax.tungsten.path.specialMoves.WalkToNode;
import kaptainwutax.tungsten.path.specialMoves.neo.NeoJump;
import kaptainwutax.tungsten.render.Color;
import net.minecraft.block.BlockState;
import net.minecraft.block.IceBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.SlimeBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.WorldView;

public class Node {

	public Node parent;
	public Agent agent;
	public PathInput input;
	public double cost;
	public double estimatedCostToGoal = 0;
	public int heapPosition;
	public double combinedCost;
	public Color color;

	public Node(Node parent, Agent agent, Color color, double pathCost) {
		this.parent = parent;
		this.agent = agent;
		this.color = color;
		this.cost = pathCost;
		this.combinedCost = 0;
		this.heapPosition = -1;
	}

	public Node(Node parent, WorldView world, PathInput input, Color color, double pathCost) {
		this.parent = parent;
		this.agent = Agent.of(parent.agent, input).tick(world);
		this.input = input;
		this.color = color;
		this.cost = pathCost;
		this.combinedCost = 0;
		this.heapPosition = -1;
	}
	
	 public boolean isOpen() {
	        return heapPosition != -1;
    }
	 
	 public int hashCode() {
		 return (int) hashCode(1, true);
	 }
	 
	 public int hashCode(int round, boolean shouldAddYaw) {
		 long result = 3241;
		 if (this.input != null) {
			 if (this.input.forward) result += "forward".hashCode();
			 if (this.input.back) result += "back".hashCode();
			 if (this.input.right) result += "right".hashCode();
			 if (this.input.left) result += "left".hashCode();
			 if (this.input.jump) result += "jump".hashCode();
			 if (this.input.sneak) result += "sneak".hashCode();
			 if (this.input.sprint) result += "sprint".hashCode();
//		    result = result + (Math.round(this.input.pitch));
		    if (shouldAddYaw) result = result + (Math.round(this.input.yaw / 45f));
		    // velocity removed from hash — too many unique values caused
		    // excessive hash collisions in the closed set
		 }
//	    if (round > 1) {
//		    result = 34L * result + Double.hashCode(roundToPrecision(this.agent.getPos().x, round));
//		    result = 87L * result + Double.hashCode(roundToPrecision(this.agent.getPos().y, round));
//		    result = 28L * result + Double.hashCode(roundToPrecision(this.agent.getPos().z, round));
//	    } else {
//		    result = 34L * result + Double.hashCode(this.agent.getPos().x);
//		    result = 87L * result + Double.hashCode(this.agent.getPos().y);
//		    result = 28L * result + Double.hashCode(this.agent.getPos().z);
//	    }
	    return (int) result;
    }


	public List<Node> getChildren(WorldView world, Vec3d target, BlockNode nextBlockNode) {
		if (shouldSkipNodeGeneration(nextBlockNode)) {
	        return Collections.emptyList();
	    }

	    List<Node> nodes = new ArrayList<>();

	    
//	    if (!agent.isClimbing(world) && nextBlockNode.getBlockState(world).getBlock() instanceof LadderBlock) {
//	    	Node sprintJumpMove = JumpToLadderMove.generateMove(this, nextBlockNode);
//	    	boolean isSprintJumpMoveClose = sprintJumpMove.agent.getPos().distanceTo(nextBlockNode.getPos(true)) < 0.55;
//	    	if (isSprintJumpMoveClose) {
//		    	nodes.add(sprintJumpMove);
//	    		return nodes;
//	    	}
//	    }
	    
	    if (DistanceCalculator.getHorizontalManhattanDistance(agent.getPos(), nextBlockNode.getPos(true)) <= 0.5 && nextBlockNode.getBlockState(world).getBlock() instanceof LadderBlock) {
	    	Node climbALadderMove = ClimbALadderMove.generateMove(this, nextBlockNode);
	    	boolean isClimbALadderMoveClose = Math.abs(climbALadderMove.agent.getPos().y - nextBlockNode.getPos(true).y) < 0.4;
	    	nodes.add(climbALadderMove);
	    	if (isClimbALadderMoveClose) {
	    		return nodes;
	    	}
	    }

	    
	    if (agent.onGround && this.agent.canSprint()) {
	    	if (nextBlockNode.isDoingNeo()) {
	    		nodes.add(NeoJump.generateMove(this, nextBlockNode));
	    	}
		    if (nextBlockNode.isDoingLongJump(world) || world.getBlockState(nextBlockNode.getBlockPos()).getBlock() instanceof LadderBlock || nextBlockNode.previous != null && world.getBlockState(nextBlockNode.previous.getBlockPos()).getBlock() instanceof IceBlock) {
		    	nodes.add(LongJump.generateMove(this, nextBlockNode));
		    }
	    }
	    // Slime bounce: when on slime, generate a bounce trajectory toward target
	    if (agent.onGround && world.getBlockState(agent.getBlockPos().down()).getBlock() instanceof SlimeBlock) {
	    	nodes.add(SlimeBounceMove.generateMove(this, nextBlockNode));
	    }
	    if (!agent.touchingWater && BlockStateChecker.isAnyWater(nextBlockNode.getBlockState(world))) {
	    	Node enterWaterAndSwimMove = EnterWaterAndSwimMove.generateMove(this, nextBlockNode);
//	    	boolean isEnterWaterAndSwimMoveClose = enterWaterAndSwimMove.agent.getPos().distanceTo(nextBlockNode.getPos(true)) > 1.5;
	    	nodes.add(enterWaterAndSwimMove);
//	    	if (isEnterWaterAndSwimMoveClose) return nodes;
	    }

        if (agent.touchingWater) {
            if (BlockShapeChecker.getShapeVolume(nextBlockNode.getBlockPos().up(), world) == 0) nodes.add(SwimmingMove.generateMove(this, nextBlockNode));
            else  nodes.add(DivingMove.generateMove(this, nextBlockNode));
            return nodes;
        }

            if (this.agent.canSprint()) {
                Node sprintJumpMove = SprintJumpMove.generateMove(this, nextBlockNode);
                boolean isSprintJumpMoveClose = sprintJumpMove.agent.getPos().distanceTo(nextBlockNode.getPos(true)) < 0.85;
                if (!sprintJumpMove.agent.onGround || !isSprintJumpMoveClose) {
                    if (agent.onGround || agent.touchingWater || agent.isClimbing(world)) {
                        generateGroundOrWaterNodes(world, target, nextBlockNode, nodes);
                    } else {
                        generateAirborneNodes(world, nextBlockNode, nodes);
                    }

                }
                nodes.add(sprintJumpMove);
//	    	if (isSprintJumpMoveClose) return nodes;
            } else {
                if (agent.onGround || agent.touchingWater || agent.isClimbing(world)) {
                    generateGroundOrWaterNodes(world, target, nextBlockNode, nodes);
                } else {
                    generateAirborneNodes(world, nextBlockNode, nodes);
                }
            }
	    
	    if (agent.onGround) {
	    	if (!world.getBlockState(agent.getBlockPos().up(2)).isAir() && nextBlockNode.getPos(true).distanceTo(agent.getPos()) < 3) {
	//    		nodes.add(TurnACornerMove.generateMove(this, nextBlockNode, false));
	//    		nodes.add(TurnACornerMove.generateMove(this, nextBlockNode, true)); 		
	    		Node cj1 = CornerJump.generateMove(this, nextBlockNode, false);
	    		Node cj2 = CornerJump.generateMove(this, nextBlockNode, true);
	    		if (cj1 != null) nodes.add(cj1);
	    		if (cj2 != null) nodes.add(cj2);
	    	}
	    }
	    if (agent.touchingWater && BlockShapeChecker.getShapeVolume(nextBlockNode.getBlockPos(), world) == 0 && !BlockStateChecker.isAnyWater(nextBlockNode.getBlockState(world))) {
	    	Node exitWaterMove = ExitWaterMove.generateMove(this, nextBlockNode);
//	    	boolean isExitWaterMoveClose = exitWaterMove.agent.getPos().distanceTo(nextBlockNode.getPos(true)) < 1.5;
	    	nodes.add(exitWaterMove);
//	    	if (isExitWaterMoveClose) return nodes;
	    }

	    if (!agent.touchingWater && !this.agent.canSprint()) {
	    	nodes.add(WalkToNode.generateMove(this, nextBlockNode));
	    }

	    if (!agent.touchingWater && this.agent.canSprint() && nextBlockNode.getPos(true).distanceTo(agent.getPos()) < 4) {
	    	nodes.add(RunToNode.generateMove(this, nextBlockNode));
	    }
    	if (!agent.isClimbing(world) && world.getBlockState(agent.getBlockPos().down()).getBlock() instanceof LadderBlock) {	
	    	nodes.add(LongJump.generateMove(this, nextBlockNode));    		
//    		nodes.add(CornerJump.generateMove(this, nextBlockNode));
    	}
    	
	    return nodes;
	}

	
	private boolean shouldSkipNodeGeneration(BlockNode nextBlockNode) {
	    Node n = this.parent;
	    if (n != null && (n.agent.isInLava() || agent.isInLava() || (agent.fallDistance > 
	    this.agent.getPos().y - nextBlockNode.getBlockPos().getY()+2
	    && !agent.slimeBounce 
	    && !agent.touchingWater
	    ))) {
	        return true;
	    }
	    return false;
	}

	/** Parameter tuple for deferred parallel node creation. */
	private record ChildGenParams(boolean forward, boolean right, boolean left, boolean sneak,
	                               boolean sprint, boolean jump, float yaw) {}

	private void generateGroundOrWaterNodes(WorldView world, Vec3d target, BlockNode nextBlockNode, List<Node> nodes) {
	    boolean isDoingLongJump = nextBlockNode.isDoingLongJump(world) || nextBlockNode.isDoingNeo();
	    boolean isCloseToBlockNode = DistanceCalculator.getHorizontalEuclideanDistance(agent.getPos(), nextBlockNode.getPos(true)) < 1;
//	    boolean needToJump = agent.blockY < nextBlockNode.y;
    	BlockState state = world.getBlockState(nextBlockNode.getBlockPos());

	    if (agent.isClimbing(world)
	    		&& state.getBlock() instanceof LadderBlock
	    		&& nextBlockNode.getBlockPos().getX() == agent.blockX
	    		&& nextBlockNode.getBlockPos().getZ() == agent.blockZ) {
	    	Direction dir = state.get(Properties.HORIZONTAL_FACING);
	    	double desiredYaw = DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true).offset(dir.getOpposite(), 1)) /*+ MathHelper.roundToPrecision(Math.random(), 2) / 1000000*/;
	    	if (nextBlockNode.getBlockPos().getY() > agent.blockY) {
		    	{ Node n = createNode(world, nextBlockNode, true, false, false, false, false, true, (float) desiredYaw, isDoingLongJump, isCloseToBlockNode); if (n != null) nodes.add(n); }
		    	return;
	    	}
	    	if (nextBlockNode.getBlockPos().getY() < agent.blockY) {
	    		{ Node n = createNode(world, nextBlockNode, true, false, false, false, false, false, (float) desiredYaw, isDoingLongJump, isCloseToBlockNode); if (n != null) nodes.add(n); }
	    		return;
	    	}
	    }
	    // 1) Collect parameter combinations (cheap — no physics)
	    List<ChildGenParams> params = new ArrayList<>();
	    float desiredYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
	    float a = 134.4f;
	    float fromYaw = Math.max(desiredYaw - a, -180.0f);
	    float toYaw = Math.min(desiredYaw + a, 180f);
	    for (boolean forward : new boolean[]{true, false}) {
	        for (boolean right : new boolean[]{true, false}) {
	            for (boolean left : new boolean[]{true, false}) {
	                    for (float yaw = fromYaw; yaw < toYaw; yaw += 22.5f) {
	                        for (boolean sprint : new boolean[]{true, false}) {
	                        	if (!this.agent.canSprint() && sprint) continue;
								if (!right && !left && !forward) continue;
								if (right && left) continue;
	                            if (( ((right || left) && !forward)) && sprint) continue;

	                            for (boolean jump : new boolean[]{true, false}) {
	                                params.add(new ChildGenParams(forward, right, left, false, sprint, jump, yaw));
	                            }
	                        }
	                    }
	            }
	        }
	    }

	    // 2) Create nodes in parallel (expensive — Agent.tick per child)
	    List<Node> created = params.parallelStream()
	        .map(p -> createNode(world, nextBlockNode, p.forward, p.right, p.left, p.sneak, p.sprint, p.jump, p.yaw, isDoingLongJump, isCloseToBlockNode))
	        .filter(Objects::nonNull)
	        .collect(Collectors.toList());
	    nodes.addAll(created);
	}

	private Node createNode(WorldView world, BlockNode nextBlockNode,
	                        boolean forward, boolean right, boolean left, boolean sneak, boolean sprint, boolean jump,
	                        float yaw, boolean isDoingLongJump, boolean isCloseToBlockNode) {
	    try {

            if (jump && sneak) return null;
	        Node newNode = new Node(this, world, new PathInput(forward, false, right, left, jump, sneak, sprint, agent.pitch, yaw),
	                new Color(sneak ? 220 : 0, 255, sneak ? 50 : 0), this.cost);
	        double addNodeCost = calculateNodeCost(forward, sprint, jump, sneak, newNode.agent);
	        if (newNode.agent.getPos().isWithinRangeOf(nextBlockNode.getPos(true), 0.1, 0.4)) return null;

	        boolean isMoving = (forward || right || left);
	        if (newNode.agent.isClimbing(world)) jump = this.agent.getBlockPos().getY() < nextBlockNode.getBlockPos().getY();

	            if (!newNode.agent.touchingWater && !newNode.agent.onGround && sneak) return null;
	            if (!newNode.agent.touchingWater && sneak && jump) return null;
	            if (!newNode.agent.touchingWater && (sneak && sprint)) return null;
	            if (!newNode.agent.touchingWater && sneak && (right || left) && forward) return null;
	            if (!newNode.agent.touchingWater && sneak && Math.abs(newNode.parent.agent.yaw - newNode.agent.yaw) > 80) return null;
	            if (newNode.agent.touchingWater && (sneak || jump) && newNode.agent.getBlockPos().getY() == nextBlockNode.getBlockPos().getY()) return null;
	            if (newNode.agent.touchingWater && jump && newNode.agent.getBlockPos().getY() > nextBlockNode.getBlockPos().getY()) return null;
	            if (!sneak) {
	            	boolean isBelowClosedTrapDoor = BlockStateChecker.isClosedBottomTrapdoor(world.getBlockState(nextBlockNode.getBlockPos().down()));
	        	    boolean shouldAllowWalkingOnLowerBlock = !world.getBlockState(agent.getBlockPos().up(2)).isAir() && nextBlockNode.getPos(true).distanceTo(agent.getPos()) < 3;
		            for (int j = 0; j < ((!jump) && !newNode.agent.isClimbing(world) ? 1 : 10); j++) {
		                if (!isMoving) break;
		                Box adjustedBox = newNode.agent.box.offset(0, -0.5, 0).expand(-0.001, 0, -0.001);
		                Stream<VoxelShape> blockCollisions = Streams.stream(agent.getBlockCollisions(world, adjustedBox));
			            if (blockCollisions.findAny().isEmpty() && isDoingLongJump) jump = true;
		                newNode = new Node(newNode, world, new PathInput(forward, false, right, left, jump, sneak, sprint, agent.pitch, yaw),
		                        jump ? new Color(150, 55, 85) : new Color(sneak ? 220 : 0, 255, sneak ? 50 : 0), this.cost + addNodeCost + 2.4);
		                if (!isDoingLongJump && jump && j > 1) break;
                        if (!newNode.agent.onGround && !newNode.agent.isClimbing(world)) break;
		            }
	            }

	        return newNode;
	    } catch (ConcurrentModificationException e) {
	        return null;
	    }
	}

	private double calculateNodeCost(boolean forward, boolean sprint, boolean jump, boolean sneak, Agent agent) {
	    double addNodeCost = 4.358; // Magic number makes pathfinder go FAST. DO NOT TOUCH

//	    if (forward && sprint && jump && !sneak) {
//	        addNodeCost -= 0.2;
//	    }
		if (agent.touchingWater) {
			addNodeCost += 0.2;
		}

		if (Math.abs(agent.velX) < 0.01 && Math.abs(agent.velY) < 0.01 && Math.abs(agent.velZ) < 0.01) {
			addNodeCost += 15;
		}
		if (agent.horizontalCollision) {
			addNodeCost += 0.0004;
		}

		if (agent.isInLava()) addNodeCost += 2e6;

	    if (sneak) {
	        addNodeCost += 2000;
	    }

//        if (agent.touchingWater) {
//            addNodeCost += 200;
//        }


        return addNodeCost; //+ Math.abs(agent.yaw- this.agent.yaw) * 5;
	}

	private void generateAirborneNodes(WorldView world, BlockNode nextBlockNode, List<Node> nodes) {
	    try {
	        float targetYaw = (float) DirectionHelper.calcYawFromVec3d(agent.getPos(), nextBlockNode.getPos(true));
	        for (float yaw : new float[]{agent.yaw, targetYaw, targetYaw - 30f}) {
	            createAirborneNodes(world, nextBlockNode, nodes, true, false, yaw);
	        }
	    } catch (ConcurrentModificationException e) {
	    }
	}

	private void createAirborneNodes(WorldView world, BlockNode nextBlockNode, List<Node> nodes, boolean forward, boolean right, float yaw) {
	    Node newNode = new Node(this, world, new PathInput(forward, false, right, false, false, false, true, agent.pitch, yaw),
	            new Color(0, 255, 255), this.cost + 1);


        if (newNode.agent.getPos().isWithinRangeOf(nextBlockNode.getPos(true), 0.9, 0.4)) return;
        double newNodeDistanceToBlockNode = Math.ceil(newNode.agent.getPos().distanceTo(nextBlockNode.getPos(true)) * 1e4);
        double parentNodeDistanceToBlockNode = Math.ceil(newNode.parent.agent.getPos().distanceTo(nextBlockNode.getPos(true)) * 1e4);

        if (newNodeDistanceToBlockNode >= parentNodeDistanceToBlockNode) return;
	    int i = 0;
	    boolean isBelowClosedTrapDoor = BlockStateChecker.isClosedBottomTrapdoor(world.getBlockState(nextBlockNode.getBlockPos().down()));
	    boolean shouldAllowWalkingOnLowerBlock = !world.getBlockState(agent.getBlockPos().up(2)).isAir() && nextBlockNode.getPos(true).distanceTo(agent.getPos()) < 3;
	    double minY = isBelowClosedTrapDoor ? nextBlockNode.getPos(true).y - 1 : nextBlockNode.getBlockPos().getY() - (shouldAllowWalkingOnLowerBlock ? 1.4 : 0.4);
	    while (!newNode.agent.onGround && !newNode.agent.isClimbing(world) && newNode.agent.getPos().y >= minY
	    		&& (TungstenModDataContainer.ignoreFallDamage || DistanceCalculator.getJumpHeight(agent.posY, newNode.agent.posY) > -3)) {
	    	if (i > 60) break;
	    	i++;
			double addNodeCost = calculateNodeCost(forward, true, false, false, newNode.agent);
	        newNode = new Node(newNode, world, new PathInput(forward, false, right, false, false, false, true, agent.pitch, yaw),
	                new Color(0, 255, 255), this.cost + addNodeCost + (this.agent.canSprint() ? 1 : 8));
	    }
		double addNodeCost = calculateNodeCost(forward, true, false, false, newNode.agent);
        newNode = new Node(newNode, world, new PathInput(forward, false, right, false, false, false, true, agent.pitch, yaw),
                new Color(0, 255, 255), this.cost + addNodeCost + (this.agent.canSprint() ? 1 : 8));

        if (newNode.agent.getPos().distanceTo(this.agent.getPos()) < 1.05) return;
	    nodes.add(newNode);
	}
	
}
