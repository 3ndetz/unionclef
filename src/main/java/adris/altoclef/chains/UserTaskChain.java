package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;

// A task chain that runs a user defined task at the same priority.
// This basically replaces our old Task Runner.
@SuppressWarnings("ALL")
public class UserTaskChain extends SingleTaskChain {

    /** How many times an INTERRUPTED (not finished) goal is put back on its feet before we stop
     *  insisting. Three is enough for a death, an MLG and a mob-defence interruption in the same
     *  run, and few enough that a task stopping itself every tick still terminates. */
    private static final int MAX_REARM_ATTEMPTS = 3;
    private int rearmAttempts = 0;

    private final Stopwatch taskStopwatch = new Stopwatch();
    private Runnable currentOnFinish = null;

    private boolean runningIdleTask;
    private boolean nextTaskIdleFlag;

    public UserTaskChain(TaskRunner runner) {
        super(runner);
    }

    public double getTaskRunningTime() {
        return taskStopwatch.time();
    }

    public String getTaskRunningTimeString() {
        return prettyPrintTimeDuration(taskStopwatch.time());
    }

    public static String prettyPrintTimeDuration(double seconds) {
        int minutes = (int) (seconds / 60);
        int hours = minutes / 60;
        int days = hours / 24;

        String result = "";
        if (days != 0) {
            result += days + " days ";
        }
        if (hours != 0) {
            result += (hours % 24) + " hours ";
        }
        if (minutes != 0) {
            result += (minutes % 60) + " minutes ";
        }
        if (!result.isEmpty()) {
            result += "and ";
        }
        result += String.format("%.1f", (seconds % 60));
        return result;
    }

    @Override
    protected void onTick() {
        // Pause if we're not loaded into a world.
        if (!AltoClef.inGame()) return;

        // Check task timeout
        AltoClef mod = AltoClef.getInstance();
        if (mod != null && mainTask != null && mainTask.isActive() && mod.checkAndClearTimeout()) {
            Debug.logMessage("Задача завершена по таймауту.");
            cancel(mod);
            return;
        }

        super.onTick();
    }

    public void cancel(AltoClef mod) {
        if (mainTask != null && mainTask.isActive()) {
            stop();
            onTaskFinish(mod);
        }
        mod.getTaskRunner().disable();

        // FIXME kinda junk, the whole pausing should probably be moved to this class
        mod.setStoredTask(null);
        mod.setPaused(false);
    }

    @Override
    public float getPriority() {
        AltoClef mod = AltoClef.getInstance();
        if (mod != null) return mod.getBehaviour().getUserTaskChainPriority();
        return 50;
    }

    @Override
    public String getName() {
        return "UserTaskChain";
    }

    public void runTask(AltoClef mod, Task task, Runnable onFinish) {
        runningIdleTask = nextTaskIdleFlag;
        nextTaskIdleFlag = false;

        currentOnFinish = onFinish;

        if (!runningIdleTask) {
            Debug.logMessage("New task set to: " + task.toString());
        }
        mod.getTaskRunner().enable();
        taskStopwatch.begin();
        setTask(task);

        if (mod.getModSettings().failedToLoad()) {
            Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the" +
                    " file to re-load working settings.");
        }
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        // STOPPED IS NOT FINISHED, AND A GOAL DOES NOT EXPIRE BECAUSE WE DIED ON THE WAY.
        // SingleTaskChain calls this on `isFinished() || stopped()`, and the second half is
        // reached whenever something interrupts the task -- a death and respawn being the
        // ordinary case. Everything below then treats it as a completed job: the runner is
        // DISABLED, the chain switched off, and the bot stands where it respawned for the rest
        // of the run.
        //
        // Measured on nav_bridge, and reproduced on demand by killing the bot mid-goal: it walks
        // off a lip at x~18, falls to y=-193, respawns at 0.5,-60,0.5, and then does nothing for
        // forty seconds. The state afterwards reads
        //     active=false | (no chain running) | UserTaskChain(on=false)
        // which is a runner that was switched off, not a goal that was met.
        //
        // The goal is still unmet, so re-arm it instead. Bounded, because a task that stops
        // itself every tick must not spin here for ever: three attempts, then let it end the way
        // it used to, so a genuinely doomed task still terminates and says so.
        if (mainTask != null && !mainTask.isFinished() && rearmAttempts < MAX_REARM_ATTEMPTS) {
            rearmAttempts++;
            Debug.logMessage("Задача была ПРЕРВАНА, а не выполнена — возобновляю (попытка %d из %d)",
                    rearmAttempts, MAX_REARM_ATTEMPTS);
            mainTask.reset();
            return;
        }

        boolean shouldIdle = mod.getModSettings().shouldRunIdleCommandWhenNotActive();
        if (!shouldIdle) {
            // Stop.
            mod.getTaskRunner().disable();
            // AND STOP WALKING. Disabling the runner ends the DECIDING, not the MOVING: a search
            // that was in flight when the task finished still lands, and its route is executed
            // against a bot with no goal. Measured on mine_stone -- the task reported done at
            // 29.5 s with its eight cobblestone, a route arrived two seconds later, and the bot
            // spent all eight building a tower out of its own pit and stood on it for the rest of
            // the run. See Nav.cancelAll for the trace and for why this is not cancel().
            adris.altoclef.control.Nav.cancelAll();
            // AND LET GO OF THE TEMPORARY BANS, which outlive the run that created them.
            //
            // The break/place avoidance installed on a suspected land claim is TEMPORARY by design:
            // WorldSurvivalChain clears it when a 60-second timer elapses. But that timer is only
            // consulted inside getPriority(), and chains do not tick while the task runner is off
            // (checklist RULE TWO). So a ban installed near the end of a job is never cleared, and
            // the NEXT job inherits it -- BotBehaviour keeps it explicitly "outside push/pop stack"
            // and applyState() re-adds it every time, so nothing else drops it either.
            //
            // Measured on mine_coal, on a run that PASSED: avoidSrc=0/0/1/0, i.e. one break-avoider
            // predicate present with ZERO registrations this run. It refused nothing that time
            // because the leaked ban was centred elsewhere; when it lands near the arena the same
            // course reads cb=0/818/0/0 with breakFail=0/0/0/0/0 -- 818 blocks refused as
            // unbreakable with no break having failed at all. That combination is unexplainable
            // until you know the ban is inherited.
            //
            // Same shape as RULE SEVEN's spectator leak, which survived a rebuild and two later
            // runs and looked exactly like a broken bot. A temporary ban must not outlive the job
            // that learnt it: re-learning costs one failed break, which is what it cost the first
            // time.
            mod.getBehaviour().resetAvoidBlockBreakingExtra();
            mod.getBehaviour().resetAvoidBlockPlacingExtra();
            // Extra reset. Sometimes baritone is laggy and doesn't properly reset our press
            if (mod.getClientBaritone() != null)
                mod.getInputControls().releaseAll();
        }
        double seconds = taskStopwatch.time();
        Task oldTask = mainTask;
        mainTask = null;
        rearmAttempts = 0;
        if (currentOnFinish != null) {
            currentOnFinish.run();
        }
        // our `onFinish` might have triggered more tasks.
        boolean actuallyDone = mainTask == null;
        if (actuallyDone) {
            if (!runningIdleTask) {
                Debug.logMessage("Поставленная задача ЗАВЕРШЕНА за %s сек.", prettyPrintTimeDuration(seconds));
                EventBus.publish(new TaskFinishedEvent(seconds, oldTask));
            }
            if (shouldIdle) {
                AltoClef.getCommandExecutor().executeWithPrefix(mod.getModSettings().getIdleCommand());
                signalNextTaskToBeIdleTask();
                runningIdleTask = true;
            }
        }
    }

    public boolean isRunningIdleTask() {
        return isActive() && runningIdleTask;
    }

    // The next task will be an idle task.
    public void signalNextTaskToBeIdleTask() {
        nextTaskIdleFlag = true;
    }
}
