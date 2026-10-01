package adris.altoclef.tasksystem;

import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.TimeoutWanderTask;

import java.util.function.Predicate;

public abstract class Task {

    /** Cumulative child-selection vetoes; descendants count separately to expose wrapper bypasses. */
    public static volatile int interruptionVetoes, descendantInterruptionVetoes;
    public static volatile String lastInterruptionVeto = "-";

    /** Optional diagnostic sink, installed only while a movement handoff trace is requested. */
    public static volatile java.util.function.Consumer<String> diagnosticEvents;

    public static void noteDiagnostic(String event) {
        java.util.function.Consumer<String> sink = diagnosticEvents;
        if (sink != null) sink.accept(event);
    }

    private String oldDebugState = "";
    private String debugState = "";

    private Task sub = null;

    private boolean first = true;

    private boolean stopped = false;

    private boolean active = false;

    public void tick(TaskChain parentChain) {
        parentChain.addTaskToChain(this);
        if (first) {
            Debug.logInternal("Task START: " + this);
            active = true;
            onStart();
            first = false;
            stopped = false;
        }
        if (stopped) return;

        Task newSub = onTick();
        // Debug state print
        if (!oldDebugState.equals(debugState)) {
            Debug.logInternal(toString());
            oldDebugState = debugState;
        }
        // We have a sub task
        if (newSub != null) {
            if (!newSub.isEqual(sub)) {
                if (canBeInterrupted(sub, newSub)) {
                    if (diagnosticEvents != null) noteDiagnostic("select " + getClass().getSimpleName()
                            + ": " + (sub == null ? "null" : sub.getClass().getSimpleName())
                            + " -> " + newSub.getClass().getSimpleName());
                    // Our sub task is new
                    if (sub != null) {
                        // Our previous sub must be interrupted.
                        sub.stop(newSub);
                    }

                    sub = newSub;
                }
            }

            // Run our child
            sub.tick(parentChain);
        } else {
            // We are null
            if (sub != null) {
                if (canBeInterrupted(sub, null)) {
                    if (diagnosticEvents != null) noteDiagnostic("select " + getClass().getSimpleName()
                            + ": " + sub.getClass().getSimpleName() + " -> null");
                    // Our previous sub must be interrupted.
                    sub.stop();
                    sub = null;
                } else {
                    // The retained child must keep running to reach a safe cancellation state,
                    // including finishing a movement or returning a protected cursor item.
                    sub.tick(parentChain);
                }
            }
        }
    }

    public void reset() {
        first = true;
        active = false;
        stopped = false;
    }

    public void stop() {
        stop(null);
    }

    /**
     * Stops the task. Next time it's run it will run `onStart`
     */
    public void stop(Task interruptTask) {
        if (!active) return;
        Debug.logInternal("Task STOP: " + this + ", interrupted by " + interruptTask);
        if (!first) {
            onStop(interruptTask);
        }

        if (sub != null && !sub.stopped()) {
            sub.stop(interruptTask);
        }

        first = true;
        active = false;
        stopped = true;
    }

    /**
     * Lets the task know it's execution has been "suspended"
     * <p>
     * STILL RUNS `onStop`
     * <p>
     * Doesn't stop it all-together (meaning `isActive` still returns true)
     */
    public void interrupt(Task interruptTask) {
        if (!active) return;
        if (!first) {
            onStop(interruptTask);
        }

        if (sub != null && !sub.stopped()) {
            sub.interrupt(interruptTask);
        }

        first = true;
    }

    protected void setDebugState(String state) {
        if (state == null) {
            state = "";
        }
        debugState = state;
    }

    // Virtual
    public boolean isFinished() {
        return false;
    }

    public boolean isActive() {
        return active;
    }

    public boolean stopped() {
        return stopped;
    }

    protected abstract void onStart();

    protected abstract Task onTick();

    // interruptTask = null if the task stopped cleanly
    protected abstract void onStop(Task interruptTask);

    protected abstract boolean isEqual(Task other);

    protected abstract String toDebugString();

    @Override
    public String toString() {
        return "<" + toDebugString() + "> " + debugState;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Task task) {
            return isEqual(task);
        }
        return false;
    }

    public boolean thisOrChildSatisfies(Predicate<Task> pred) {
        Task t = this;
        while (t != null) {
            if (pred.test(t)) return true;
            t = t.sub;
        }
        return false;
    }

    public boolean thisOrChildAreTimedOut() {
        return thisOrChildSatisfies(task -> task instanceof TimeoutWanderTask);
    }

    /**
     * Sometimes a task just can NOT be bothered to be interrupted right now.
     * For instance, if we're in mid air and MUST complete the parkour movement.
     */
    private boolean canBeInterrupted(Task subTask, Task toInterruptWith) {
        if (subTask == null) return true;
        // Upstream PathingControlManager.java:85-110 cancels only a safe segment;
        // MovementFall.java:173-177 refuses a mid-fall handoff. Preserve our existing
        // grounded/cursor guards through every wrapper: one descendant veto is enough.
        // Looking for ANY allowed node let a plain wrapper bypass its protected child.
        // Explicit stop()/interrupt() remains unconditional for user and chain preemption.
        for (Task current = subTask; current != null; current = current.sub) {
            if (current.isActive() && current instanceof ITaskCanForce canForce
                    && canForce.shouldForce(toInterruptWith)) {
                interruptionVetoes++;
                if (current != subTask) descendantInterruptionVetoes++;
                lastInterruptionVeto = current.getClass().getSimpleName() + " -> "
                        + (toInterruptWith == null ? "null" : toInterruptWith.getClass().getSimpleName());
                if (diagnosticEvents != null) noteDiagnostic("veto " + lastInterruptionVeto);
                return false;
            }
        }
        return true;
    }
}
