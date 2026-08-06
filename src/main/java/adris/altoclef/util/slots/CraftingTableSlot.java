package adris.altoclef.util.slots;

import java.util.stream.IntStream;

public class CraftingTableSlot extends Slot {
    public static final CraftingTableSlot OUTPUT_SLOT = new CraftingTableSlot(0);

    public static final CraftingTableSlot[] INPUT_SLOTS = IntStream.range(0, 9).mapToObj(ind -> getInputSlot(ind, true)).toArray(CraftingTableSlot[]::new);

    public CraftingTableSlot(int windowSlot) {
        this(windowSlot, false);
    }

    protected CraftingTableSlot(int slot, boolean inventory) {
        super(slot, inventory);
    }

    public static CraftingTableSlot getInputSlot(int x, int y) {
        return getInputSlot(y * 3 + x, true);
    }

    /**
     * Where a recipe's cell {@code index} lives in the open table window.
     *
     * <h2>A 2x2 recipe was being scattered across the 3x3 grid</h2>
     *
     * The window numbers the nine input cells 1..9 in reading order, so a 0-based recipe index
     * becomes a window slot by adding one. That +1 belongs to the BIG case only, and it used to be
     * applied to {@code index} BEFORE the small case decomposed it into x and y — which took the
     * decomposition apart:
     *
     * <pre>
     *   cell 0 -> window 2 (row 0, col 1)      wanted: window 1 (row 0, col 0)
     *   cell 1 -> window 4 (row 1, col 0)      wanted: window 2 (row 0, col 1)
     *   cell 2 -> window 5 (row 1, col 1)      wanted: window 4 (row 1, col 0)
     *   cell 3 -> window 7 (row 2, col 0)      wanted: window 5 (row 1, col 1)
     * </pre>
     *
     * Those four cells are not a square. A 2x2 SHAPED recipe laid into them does not match anything
     * — a crafting table, four planks in a square, is exactly that recipe and could never be made
     * in a table. The shapes that happened to survive were the ones whose cells landed in a column
     * by luck, which is why some crafts worked and the rung above them did not.
     *
     * <p>The small recipe now goes in the top-left 2x2 of the grid, which is what {@code
     * getInputSlot(x, y)} means and where a player would put it.
     */
    public static CraftingTableSlot getInputSlot(int index, boolean big) {
        if (big) {
            // The window's nine input cells are 1..9; the recipe's are 0..8.
            return new CraftingTableSlot(index + 1);
        }
        // Small recipe in the big window: the top-left 2x2. getInputSlot(x, y) adds the +1 itself.
        return getInputSlot(index % 2, index / 2);
    }

    @Override
    public int inventorySlotToWindowSlot(int inventorySlot) {
        if (inventorySlot < 9) {
            return inventorySlot + 37;
        }
        return inventorySlot + 1;
    }

    @Override
    protected int windowSlotToInventorySlot(int windowSlot) {
        if (windowSlot >= 37) {
            return windowSlot - 37;
        }
        return windowSlot - 1;
    }

    @Override
    protected String getName() {
        return "CraftingTable";
    }
}
