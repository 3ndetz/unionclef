package kaptainwutax.tungsten.helpers.render;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.TungstenModRenderContainer;
import kaptainwutax.tungsten.path.Node;
import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;
import kaptainwutax.tungsten.render.Color;
import kaptainwutax.tungsten.render.Cuboid;
import kaptainwutax.tungsten.render.Line;
import kaptainwutax.tungsten.render.Renderer;
import net.minecraft.util.math.Vec3d;

/**
 * Helper class to easily render out paths and nodes.
 */
public class RenderHelper {

	/**
	 * PRODUCER-SIDE GATE. The debug-renderer mixin only decides whether to DRAW;
	 * every render* method below still allocated Line/Cuboid objects into
	 * synchronised collections shared with the render thread even with
	 * visualisation off. Measured on the stand: navigating built up to 119
	 * renderer objects with renderVisualization=false, and fps fell from 23.5
	 * (idle) to ~12-16 while pathing. Nothing is built when nothing is drawn.
	 */
	private static boolean enabled() {
		kaptainwutax.tungsten.TungstenConfig cfg = kaptainwutax.tungsten.TungstenConfig.get();
		return cfg.renderVisualization && cfg.renderPathMoves;
	}

	/**
	 * SEARCH-PROGRESS THROTTLE. renderNodeConnection/renderPathSoFar are called
	 * once per EXPANDED NODE (PathFinder search loop) — thousands of allocations
	 * and lock acquisitions per second on the render thread's collections. The
	 * visualisation only needs to be human-visible, so cap it to ~20 Hz.
	 */
	private static final long SEARCH_RENDER_INTERVAL_MS = 50;
	private static long lastSearchRenderMs = 0;

	private static boolean searchRenderDue() {
		if (!enabled()) return false;
		long now = System.currentTimeMillis();
		if (now - lastSearchRenderMs < SEARCH_RENDER_INTERVAL_MS) return false;
		lastSearchRenderMs = now;
		return true;
	}

	public static void renderBlockPath(List<BlockNode> nodes, int nextNodeIDX) {
		if (!enabled()) { TungstenModRenderContainer.BLOCK_PATH_RENDERER.clear(); return; }
		TungstenModRenderContainer.BLOCK_PATH_RENDERER.clear();
//		TungstenMod.RENDERERS.clear();
//		TungstenMod.TEST.clear();
		BlockNode previous = null;
		for (Iterator<BlockNode> iterator = nodes.iterator(); iterator.hasNext();) {
			BlockNode node = iterator.next();
			Vec3d currentPos = node.getPos(true);
			
			if (previous != null) {
				Vec3d previousPos = previous.getPos(true);
				TungstenModRenderContainer.BLOCK_PATH_RENDERER.add(new Line(new Vec3d(previousPos.x, previousPos.y + 0.1, previousPos.z), new Vec3d(currentPos.x, currentPos.y + 0.1, currentPos.z), Color.RED));
			}
			if (nodes.size() <= nextNodeIDX) nextNodeIDX = nodes.size()-1;
			TungstenModRenderContainer.BLOCK_PATH_RENDERER.add(new Cuboid(currentPos.subtract(0.1, 0, 0.1), new Vec3d(0.2D, 0.2D, 0.2D), 
            		(nodes.get(nextNodeIDX).equals(node)) ? Color.WHITE : Color.BLUE
            		));
            previous = node;
//            TungstenMod.BLOCK_PATH_RENDERER.add(new Cuboid(new Vec3d(node.getBlockPos().getX(), node.getBlockPos().getY(), node.getBlockPos().getZ()), new Vec3d(1.0D, 1.0D, 1.0D), 
//            		(nodes.get(NEXT_CLOSEST_BLOCKNODE_IDX).equals(node)) ? Color.WHITE : Color.BLUE
//            		));
		}
	}
	
	public static void renderPathCurrentlyExecuted() {
		TungstenModRenderContainer.RUNNING_PATH_RENDERER.clear();
		TungstenModRenderContainer.RENDERERS.clear();
		TungstenModRenderContainer.TEST.clear();
		if (!enabled()) return;
		if (TungstenModDataContainer.EXECUTOR == null || TungstenModDataContainer.EXECUTOR.getPath() == null || !TungstenModDataContainer.EXECUTOR.isRunning()) return;
//		Node n = TungstenMod.EXECUTOR.getPath().getLast();
//		while (n.parent != null) {
//			TungstenMod.RUNNING_PATH_RENDERER.add(new Line(n.agent.getPos(), n.parent.agent.getPos(), n.color));
//			if (TungstenMod.renderPositonBoxes) {				
//				TungstenMod.RUNNING_PATH_RENDERER.add(new Cuboid(n.agent.getPos().subtract(0.05D, 0.05D, 0.05D), new Vec3d(0.1D, 0.1D, 0.1D), n.color));
//			}
//			n = n.parent;
//		}
		List<Node> path = TungstenModDataContainer.EXECUTOR.getPath();
		
		for (int i = (path.size()-2); i > 0; i--) {
			if (TungstenModDataContainer.EXECUTOR.getCurrentTick() > i) continue;
			Node n = path.get(i);
			
			Node parent = path.get(i+1);
			if (parent == null) continue;
			TungstenModRenderContainer.RUNNING_PATH_RENDERER.add(new Line(n.agent.getPos(), parent.agent.getPos(), n.color));
//			if (TungstenMod.renderPositonBoxes) {
				TungstenModRenderContainer.RUNNING_PATH_RENDERER.add(new Cuboid(n.agent.getPos().subtract(0.05D, 0.05D, 0.05D), new Vec3d(0.1D, 0.1D, 0.1D), n.color));
//			}
		}
	}
	
	public static void renderPathSoFar(BlockNode n) {
		if (!searchRenderDue()) return;   // called per expanded node during the search
		TungstenModRenderContainer.RENDERERS.clear();
		Vec3d currentPos = n.getPos(true);
		TungstenModRenderContainer.RENDERERS.add(new Cuboid(currentPos.subtract(0.1, 0, 0.1), new Vec3d(0.2D, 0.2D, 0.2D), Color.RED));
		while(n.previous != null) {
			currentPos = n.getPos(true);
			Vec3d previousPos = n.previous.getPos(true);
			TungstenModRenderContainer.RENDERERS.add(new Line(new Vec3d(previousPos.x, previousPos.y + 0.1, previousPos.z), new Vec3d(currentPos.x, currentPos.y + 0.1, currentPos.z), Color.WHITE));
			n = n.previous;
		}
	}
	
	public static void renderPathSoFar(Node n) {
		if (!searchRenderDue()) return;   // called per expanded node during the search
		TungstenModRenderContainer.RENDERERS.clear();
		TungstenModRenderContainer.RENDERERS.add(new Cuboid(n.agent.getPos().subtract(0.05D, 0.05D, 0.05D), new Vec3d(0.1D, 0.1D, 0.1D), Color.RED));
		while(n.parent != null) {
			TungstenModRenderContainer.RENDERERS.add(new Line(n.agent.getPos(), n.parent.agent.getPos(), n.color));
			n = n.parent;
		}
	}
	
	public static void renderNode(Node n) {
		if (!enabled()) return;
		TungstenModRenderContainer.RENDERERS.add(new Cuboid(n.agent.getPos().subtract(0.05D, 0.05D, 0.05D), new Vec3d(0.1D, 0.1D, 0.1D), n.color));
	}

	public static void renderNode(Node n, Collection<Renderer> renderer) {
		if (!enabled()) return;
		renderer.add(new Cuboid(n.agent.getPos().subtract(0.05D, 0.05D, 0.05D), new Vec3d(0.1D, 0.1D, 0.1D), n.color));
	}

	public static void renderNodeConnection(Node child, Node parent) {
		if (!searchRenderDue()) return;   // per expanded node in the physics search
		TungstenModRenderContainer.RUNNING_PATH_RENDERER.add(new Line(child.agent.getPos(), parent.agent.getPos(), child.color));
//	    if (TungstenMod.renderPositonBoxes) {
//	    	TungstenModRenderContainer.RUNNING_PATH_RENDERER.add(new Cuboid(child.agent.getPos().subtract(0.05D, 0.05D, 0.05D), new Vec3d(0.1D, 0.1D, 0.1D), child.color));
//	    }
	}
	
	public static void renderNodeConnection(BlockNode child, BlockNode parent) {
		if (!searchRenderDue()) return;   // per expanded node in the block-space search

//		Vec3d childPos = child.getPos(true);
//		Vec3d parentPos = parent.getPos(true);
		TungstenModRenderContainer.RENDERERS.add(new Line(new Vec3d(parent.x + 0.5, parent.y + 0.1, parent.z + 0.5), new Vec3d(child.x + 0.5, child.y + 0.1, child.z + 0.5), Color.RED));
		TungstenModRenderContainer.RENDERERS.add(new Cuboid(child.getPos(), new Vec3d(1.0D, 1.0D, 1.0D), Color.BLUE));
	}
	
	public static void clearRenderers() {
		TungstenModRenderContainer.RENDERERS.clear();
//	    TungstenModRenderContainer.RUNNING_PATH_RENDERER.clear();
		TungstenModRenderContainer.BLOCK_PATH_RENDERER.clear();
		TungstenModRenderContainer.TEST.clear();
	}
}
