import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;

/** Lifecycle contracts against the actual Task.java, without a Minecraft client dependency. */
public final class TaskForceContract {
    private static final TaskChain CHAIN = new TaskChain();
    private static class Node extends Task {
        Task next;
        int ticks, stops;
        protected void onStart() { }
        protected Task onTick() { ticks++; return next; }
        protected void onStop(Task candidate) { stops++; }
        protected boolean isEqual(Task other) { return other == this; }
        protected String toDebugString() { return "contract node"; }
    }
    private static final class Forced extends Node implements ITaskCanForce {
        boolean blocked = true;
        Task allowed;
        int releaseAfterTicks = Integer.MAX_VALUE;
        public boolean shouldForce(Task candidate) { return blocked && (allowed == null || candidate != allowed); }
        protected Task onTick() {
            Task result = super.onTick();
            if (ticks >= releaseAfterTicks) blocked = false;
            return result;
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void replace(boolean wrapped) {
        Node root = new Node(), wrapper = new Node(), replacement = new Node();
        Forced leaf = new Forced();
        wrapper.next = leaf;
        root.next = wrapped ? wrapper : leaf;
        root.tick(CHAIN);
        root.next = replacement;
        root.tick(CHAIN);
        require(leaf.stops == 0 && leaf.isActive() && replacement.ticks == 0,
                (wrapped ? "wrapped" : "direct") + " forcing task was interrupted before it released control");
        leaf.blocked = false;
        root.tick(CHAIN);
        require(leaf.stops == 1 && replacement.ticks == 1, "deferred replacement never resumed");
    }
    private static void cancelWrapped() {
        Node root = new Node(), wrapper = new Node();
        Forced leaf = new Forced();
        root.next = wrapper;
        wrapper.next = leaf;
        root.tick(CHAIN);
        root.next = null;
        root.tick(CHAIN);
        require(leaf.isActive() && leaf.stops == 0, "null child selection bypassed a forcing descendant");
        leaf.blocked = false;
        root.tick(CHAIN);
        require(leaf.stops == 1 && wrapper.stops == 1, "deferred child cancellation never completed");
    }
    private static void unblockedAncestor() {
        Node root = new Node(), replacement = new Node();
        Forced ancestor = new Forced(), leaf = new Forced();
        ancestor.blocked = false;
        ancestor.next = leaf;
        root.next = ancestor;
        root.tick(CHAIN);
        root.next = replacement;
        root.tick(CHAIN);
        require(leaf.stops == 0 && replacement.ticks == 0, "non-forcing ancestor bypassed a forcing child");
        leaf.blocked = false;
        root.tick(CHAIN);
        require(leaf.stops == 1 && replacement.ticks == 1, "fully released hierarchy could not be replaced");
    }
    private static void ordinaryAndExplicitStop() {
        Node root = new Node(), child = new Node(), replacement = new Node();
        root.next = child;
        root.tick(CHAIN);
        root.next = replacement;
        root.tick(CHAIN);
        require(child.stops == 1 && replacement.ticks == 1, "ordinary replacement was blocked");
        Forced force = new Forced();
        root.next = force;
        root.tick(CHAIN);
        root.stop();
        require(force.stops == 1 && !force.isActive(), "explicit user cancellation was blocked");
    }
    private static void nullSelectionKeepsTicking() {
        Node root = new Node(), wrapper = new Node();
        Forced leaf = new Forced();
        leaf.releaseAfterTicks = 3;
        root.next = wrapper;
        wrapper.next = leaf;
        root.tick(CHAIN);
        root.next = null;
        root.tick(CHAIN);
        root.tick(CHAIN);
        require(leaf.ticks == 3 && leaf.stops == 0, "retained null-selection child stopped progressing");
        root.tick(CHAIN);
        require(leaf.stops == 1 && wrapper.stops == 1, "retained child never reached cancellation");
    }
    private static void candidateOverride() {
        Node root = new Node(), wrapper = new Node(), allowed = new Node();
        Forced leaf = new Forced();
        leaf.allowed = allowed;
        root.next = wrapper;
        wrapper.next = leaf;
        root.tick(CHAIN);
        root.next = allowed;
        root.tick(CHAIN);
        require(leaf.stops == 1 && allowed.ticks == 1, "candidate-specific override was blocked");
    }
    private static void explicitChainInterrupt() {
        Node root = new Node(), wrapper = new Node();
        Forced leaf = new Forced();
        root.next = wrapper;
        wrapper.next = leaf;
        root.tick(CHAIN);
        root.interrupt(new Node());
        require(leaf.stops == 1 && leaf.isActive(), "chain preemption was blocked or lost suspended state");
        root.tick(CHAIN);
        require(leaf.ticks == 2, "interrupted chain did not resume");
    }
    private static void stoppedTreeDoesNotVetoRestart() {
        Node root = new Node(), wrapper = new Node(), replacement = new Node();
        Forced leaf = new Forced();
        root.next = wrapper;
        wrapper.next = leaf;
        root.tick(CHAIN);
        root.stop();
        root.next = replacement;
        root.tick(CHAIN);
        require(replacement.ticks == 1 && leaf.ticks == 1 && !leaf.isActive(),
                "a stopped subtree vetoed a fresh task and restarted itself");
    }
    public static void main(String[] args) {
        replace(false);
        replace(true);
        cancelWrapped();
        unblockedAncestor();
        ordinaryAndExplicitStop();
        nullSelectionKeepsTicking();
        candidateOverride();
        explicitChainInterrupt();
        stoppedTreeDoesNotVetoRestart();
        require(Task.interruptionVetoes > 0 && Task.descendantInterruptionVetoes > 0,
                "wrapper veto instrumentation never ran");
        System.out.println("Task force contracts: 9/9 PASS");
    }
}
