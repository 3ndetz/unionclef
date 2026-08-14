package kaptainwutax.tungsten.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import kaptainwutax.tungsten.Debug;
import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenMod;
import kaptainwutax.tungsten.TungstenModDataContainer;
import kaptainwutax.tungsten.commands.arguments.GotoTargetArgumentType;
import kaptainwutax.tungsten.commandsystem.Command;
import kaptainwutax.tungsten.commandsystem.CommandException;
import kaptainwutax.tungsten.path.targets.BlockTarget;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.Vec3d;

public class GotoCommand extends Command {

	private static final int    MAX_RETRIES     = 10;
	private static final double ARRIVAL_DIST_SQ = 2.0 * 2.0;

	public GotoCommand(TungstenMod mod) throws CommandException {
        super("goto", "Tell bot to travel to a set of coordinates", mod);
    }

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {

		builder.then(argument("gotoTarget", GotoTargetArgumentType.create()).executes(context -> {
	        try {

	        	BlockTarget target = GotoTargetArgumentType.get(context);
	        	if(!TungstenModDataContainer.PATHFINDER.active.get() && !TungstenModDataContainer.EXECUTOR.isRunning()) {
	        		// A prior ;stop leaves stop=true forever (find() would reset it,
	        		// but startWithRetry bails on the stale flag before ever calling
	        		// find()) — a fresh user command overrides the old stop.
	        		TungstenModDataContainer.PATHFINDER.stop.set(false);
	        		TungstenModDataContainer.EXECUTOR.stop = false;
	        		// Snap a non-standable target (air / tall grass / flowers) to the
	        		// reachable ground — otherwise the search re-roots near it forever.
	        		Vec3d targetVec = kaptainwutax.tungsten.path.GoalSnap.snap(
	        				target.getVec3d().add(0.5, 0, 0.5), TungstenMod.mc.world);
	        		TungstenMod.TARGET = targetVec;
	        		TungstenMod.markGotoTarget();
	        		startWithRetry(targetVec, 0);
	    		} else {
	    			Debug.logWarning("Already running!");
	    		}

			} catch (Exception e) {
				e.printStackTrace();
			}

			return SINGLE_SUCCESS;
		}));
	}

	/**
	 * Plan the cheap block route and hand it to the walker so the bot moves NOW.
	 * Runs on its own thread: the plan is time-sliced (fastPlanBudgetMs) but the
	 * chat/command thread must never block on it.
	 */
	private static void startFastLeg(Vec3d target) {
		kaptainwutax.tungsten.task.FastNavigator.start(target);
	}

	private static void startWithRetry(Vec3d target, int attempt) {
		if (attempt >= MAX_RETRIES) {
			Debug.logWarning("Gave up after " + MAX_RETRIES + " attempts.");
			return;
		}
		if (TungstenModDataContainer.PATHFINDER.stop.get()) return;

		// FAST-FIRST: plan a cheap, physically-real block path and START WALKING it
		// right away, then let the physics search work while the bot is already
		// moving. Without this the first movement key waited for the physics leg
		// (>=0.5s prelude + block search + physics), i.e. the bot stood still while
		// the computer thought. The walker only leaves the ground for moves it can
		// actually do; a waypoint the planner marked needsPhysics stops the walk so
		// the physics engine takes that segment (parkour stays intact).
		// OWNERSHIP OF THE ROUTE DOES NOT EXPIRE ON THE FIRST RETRY. This used to be
		// `&& attempt == 0`, so every retry handed the route back to a rival physics search
		// for the FINAL goal. On any route physics cannot solve, that search then runs
		// forever — and BlockPathWalker.tick() stops the walker outright whenever the physics
		// executor is running, so the navigator's own execution is switched off as a side
		// effect. Measured on the bounce course: 78 "Partial path (goal unreachable)" in a
		// single run and a slime crossing that could never start because the walker was never
		// ticked. The navigator owns the route for as long as it is enabled; physics is asked
		// for the segments the navigator flags, and only those.
		boolean navigatorDrives = TungstenConfig.get().fastBlockFirst;
		if (navigatorDrives) {
			startFastLeg(target);
		}

		// Reset pathfinder params to defaults (may have been overridden by followPlayer)
		TungstenConfig.get().searchTimeoutMs       = 15000L;
		TungstenModDataContainer.PATHFINDER.minPathSizeForTimeout = 15;
		TungstenModDataContainer.PATHFINDER.minDistPath           = 1.8;

		// ONE OWNER OF THE ROUTE while the navigator is driving. There is a SINGLE physics
		// search engine. Searching for the final goal in parallel looks free, but on any
		// route whose goal is not directly reachable — behind a wall, on top of a ledge —
		// that search cannot succeed, burns its full 20 s budget, restarts, and monopolises
		// the engine, so the hand-offs the navigator needs for jumps and climbs are refused
		// on every single tick (measured on nav_wall2: `pending=set` and `pfActive=true`,
		// forever). The navigator owns the route and asks physics for the segments it cannot
		// walk itself.
		//
		// NOTE: only the SEARCH is skipped. An earlier attempt returned here outright and
		// also skipped the retry callback below — which is what actually broke nav_gaps,
		// because nothing continued the goto once an executor segment finished.
		if (!navigatorDrives) {
			TungstenModDataContainer.PATHFINDER.find(TungstenMod.mc.world, target, TungstenMod.mc.player);
		}

		// Set callback: when pathfinder+executor finish, retry if not at target
		TungstenModDataContainer.EXECUTOR.cb = () -> {
			if (TungstenModDataContainer.PATHFINDER.stop.get()) return;
			if (TungstenMod.mc.player == null) return;
			double distSq = TungstenMod.mc.player.getEntityPos().squaredDistanceTo(target);
			if (distSq <= ARRIVAL_DIST_SQ) {
				// Arrived. Stop any lingering search thread so it doesn't keep
				// re-rooting near the goal and report "busy" long after we got there.
				TungstenModDataContainer.PATHFINDER.stop.set(true);
				return;
			}
			if (distSq > ARRIVAL_DIST_SQ) {
				Debug.logMessage("Retrying (" + (attempt + 1) + "/" + MAX_RETRIES + ")...");
				// Small delay to let player land
				new Thread(() -> {
					try { Thread.sleep(500); } catch (InterruptedException ignored) {}
					// find() silently no-ops while the previous search thread is
					// alive — wait for it to die (matters after mined-wall
					// segments, where the retry drives the next leg)
					for (int i = 0; i < 40 && TungstenModDataContainer.PATHFINDER.thread != null; i++) {
						try { Thread.sleep(500); } catch (InterruptedException ignored) {}
					}
					if (!TungstenModDataContainer.PATHFINDER.stop.get()) {
						startWithRetry(target, attempt + 1);
					}
				}).start();
			}
		};
	}
}
