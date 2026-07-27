package kaptainwutax.tungsten.path.calculators;

/**
 * Cost constants. EVERYTHING here is in TICKS, and so is the block-space
 * heuristic ({@link kaptainwutax.tungsten.path.blockSpaceSearchAssist.Goal#heuristic}) —
 * they must stay in the same unit or A* stops working as A*.
 */
public class ActionCosts {
	/**
	 * "Unreachable" sentinel for a node whose real cost is not known yet.
	 *
	 * <p>This used to be NEGATIVE (-1000000). Nothing compared against it, so it was
	 * harmless while the search had no real g accumulation — but it makes every
	 * "is this path cheaper?" test read backwards, which is exactly the comparison
	 * proper A* relaxation is built on. Positive infinity is the only value that
	 * lets `tentative < node.cost` mean what it says. (Audit 2026-07-27, C2.2.)
	 */
	public static double COST_INF = 1_000_000;
	public static double WALK_ONE_BLOCK_COST = 20 / 4.317; // 4.633

	// Jump/fall costs for smart move generation (SmartMoves). Kept consistent with
	// the existing BlockNode penalty convention (a jump = WALK + ~6.5) so the smart
	// neighbours cost the same as the blind-scan filter would have charged — only
	// the branching factor changes, not the tuned cost landscape.
	public static double JUMP_PENALTY = 6.5;
	public static double JUMP_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST + JUMP_PENALTY;
	// Per-block horizontal cost of a running parkour jump (a bit cheaper than
	// walking the same distance would be if it were solid, but with the jump penalty).
	public static double PARKOUR_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8;
	// Falling is cheap horizontally but each block of drop adds a little (landing
	// recovery); large drops are rejected by the fall-damage guard, not priced here.
	public static double FALL_ONE_BLOCK_COST = 1.0;

	/**
	 * Per-block weight the goal heuristic uses to convert a distance in BLOCKS into
	 * the TICK unit the costs are expressed in.
	 *
	 * <p>Without this the heuristic returned raw block distance (~1.0/block) while g
	 * accumulated ticks (~4.6/block), so h was ~4.6x under-weighted and the search
	 * collapsed towards Dijkstra — it explored outward almost uniformly instead of
	 * heading for the goal, which is a large part of why it exhausted its budget on
	 * anything non-trivial.
	 *
	 * <p>Deliberately a little BELOW {@link #WALK_ONE_BLOCK_COST}: staying under the
	 * true per-block cost keeps the heuristic admissible (it never over-estimates), so
	 * the search still finds good routes rather than merely fast ones.
	 */
	public static double HEURISTIC_PER_BLOCK = WALK_ONE_BLOCK_COST * 0.9; // 4.17

	/** Swimming is slower than walking. */
	public static double SWIM_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 2.0;
	/** Climbing a ladder, per block of height. */
	public static double LADDER_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 1.6;
	/** Flat multiplier on a diagonal step (sqrt(2) of horizontal travel). */
	public static double DIAGONAL_MULTIPLIER = 1.414;
}
