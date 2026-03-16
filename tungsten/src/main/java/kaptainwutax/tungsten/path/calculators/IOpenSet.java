package kaptainwutax.tungsten.path.calculators;

import kaptainwutax.tungsten.path.Node;

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
    void insert(Node node);

    /**
     * @return {@code true} if the heap has no elements; {@code false} otherwise.
     */
    boolean isEmpty();

    /**
     * Removes and returns the minimum element in the heap.
     *
     * @return The minimum element in the heap
     */
    Node removeLowest();

    /**
     * A faster path has been found to this node, decreasing its cost. Perform a decrease-key operation.
     *
     * @param node The node
     */
    void update(Node node);
}