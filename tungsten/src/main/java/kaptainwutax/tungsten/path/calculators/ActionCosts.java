package kaptainwutax.tungsten.path.calculators;

public class ActionCosts {
	/**
	 * "Impossible" — POSITIVE, as upstream has it
	 * (baritone/src/main/java/baritone/api/pathing/movement/ActionCosts.java:34). It was
	 * NEGATIVE here, and this value is used as the initial cost of an unvisited node
	 * (BlockNode.java:132, :148, :168) the way A* uses +infinity. Negative means an unvisited
	 * node looks CHEAPER than any real path, so no relaxation can ever improve it.
	 */
	public static double COST_INF = 1000000;
	public static double WALK_ONE_BLOCK_COST = 20 / 4.317; // 4.633

	// Jump/fall costs for smart move generation (SmartMoves). Kept consistent with
	// the existing BlockNode penalty convention (a jump = WALK + ~6.5) so the smart
	// neighbours cost the same as the blind-scan filter would have charged — only
	// the branching factor changes, not the tuned cost landscape.
	/**
	 * PORTED VERBATIM, replacing an invented penalty
	 * (baritone/.../ActionCosts.java:20-97). These are TICKS, one unit system, derived from
	 * vanilla speeds — not multipliers of walking chosen by eye. The old
	 * JUMP_ONE_BLOCK_COST was WALK + 6.5 = 11.13; upstream's is 3.163, so this planner has
	 * been pricing every jump at three and a half times its real cost and routing around
	 * jumps it should have taken.
	 */
	public static double WALK_ONE_IN_WATER_COST = 20 / 2.2;          // 9.091
	public static double WALK_ONE_OVER_SOUL_SAND_COST = WALK_ONE_BLOCK_COST * 2;
	public static double SNEAK_ONE_BLOCK_COST = 20 / 1.3;            // 15.385 — the bridge price
	public static double SPRINT_ONE_BLOCK_COST = 20 / 5.612;         // 3.564
	public static double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST;
	public static double LADDER_UP_ONE_COST = 20 / 2.35;             // 8.511
	public static double LADDER_DOWN_ONE_COST = 20 / 3.0;            // 6.667
	public static double WALK_OFF_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8;
	public static double CENTER_AFTER_FALL_COST = WALK_ONE_BLOCK_COST - WALK_OFF_BLOCK_COST;

	/** Vanilla fall velocity after n ticks. */
	public static double velocity(int ticks) {
		return (Math.pow(0.98, ticks) - 1) * -3.92;
	}

	/** Ticks to fall this far, integrating the real velocity curve. */
	public static double distanceToTicks(double distance) {
		if (distance == 0) return 0;
		double remaining = distance;
		int ticks = 0;
		while (true) {
			double step = velocity(ticks);
			if (remaining <= step) return ticks + remaining / step;
			remaining -= step;
			ticks++;
		}
	}

	public static double FALL_1_25_BLOCKS_COST = distanceToTicks(1.25);
	public static double FALL_0_25_BLOCKS_COST = distanceToTicks(0.25);
	public static double JUMP_PENALTY = 6.5;
	public static double JUMP_ONE_BLOCK_COST = FALL_1_25_BLOCKS_COST - FALL_0_25_BLOCKS_COST;  // 3.163
	// Per-block horizontal cost of a running parkour jump (a bit cheaper than
	// walking the same distance would be if it were solid, but with the jump penalty).
	public static double PARKOUR_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8;
	// Falling is cheap horizontally but each block of drop adds a little (landing
	// recovery); large drops are rejected by the fall-damage guard, not priced here.
	public static double FALL_ONE_BLOCK_COST = 1.0;

	/** Swimming is roughly half walking speed. */
	public static double SWIM_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 2.0;
	/** Climbing a ladder, per block of height. */
	public static double LADDER_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 1.6;

	/** Placing one block to bridge with: aim, click, wait out the placement, then step on.
	 *  Priced above a jump so the search still PREFERS to jump a gap it can jump, and only
	 *  reaches for blocks where jumping is impossible — which is exactly how baritone gets
	 *  anywhere, and what tungsten could not plan at all before. */
	public static double PLACE_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 2.5;
}
