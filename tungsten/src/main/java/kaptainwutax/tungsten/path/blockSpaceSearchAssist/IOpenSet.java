package kaptainwutax.tungsten.path.blockSpaceSearchAssist;

import kaptainwutax.tungsten.path.blockSpaceSearchAssist.BlockNode;

/**
 * An open set for A* or similar graph search algorithm
 *
 * @author leijurv
 */
public interface IOpenSet {

    /**
     * Inserts the specified node into the heap
     *
     * @param node The node
     */
    void insert(BlockNode node);

    /**
     * @return {@code true} if the heap has no elements; {@code false} otherwise.
     */
    boolean isEmpty();

    /**
     * Removes and returns the minimum element in the heap.
     *
     * @return The minimum element in the heap
     */
    BlockNode removeLowest();

    /**
     * A faster path has been found to this node, decreasing its cost. Perform a decrease-key operation.
     *
     * @param node The node
     */
    void update(BlockNode node);
}