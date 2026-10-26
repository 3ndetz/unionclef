package adris.altoclef.trackers;

import adris.altoclef.AltoClef;

public abstract class Tracker {

    protected AltoClef mod;
    // Needs to update
    private boolean dirty = true;

    public Tracker(TrackerManager manager) {
        manager.addTracker(this);
    }

    public void setDirty() {
        dirty = true;
    }

    // Virtual
    protected boolean isDirty() {
        return dirty;
    }

    /**
     * ⛔ SYNCHRONIZED (2026-10-26): updateState() clears and refills plain HashMaps, and two threads
     * ran it at once -- the client crashed mid-playthrough with "Cannot invoke List.add because
     * HashMap.get(...) is null" in InventorySubTracker.registerItem (the map was cleared between its
     * put and get), reached from MobDefenseChain.hasShield on the render thread. Subclasses that
     * read their maps after this call synchronize those readers on the same monitor.
     */
    protected synchronized void ensureUpdated() {
        if (isDirty()) {
            updateState();
            dirty = false;
        }
    }

    protected abstract void updateState();

    protected abstract void reset();
}
