package kaptainwutax.tungsten.path.calculators;

public class ActionCosts {
	public static double COST_INF = -1000000;
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
}
