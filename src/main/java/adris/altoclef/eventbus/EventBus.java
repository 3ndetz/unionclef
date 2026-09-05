package adris.altoclef.eventbus;

import net.minecraft.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * A static class to solve dependency issues. Lets us send and receive events globally, decoupling our codebase.
 * <p>
 * Technically `ConfigHelper` does something like this, but here is a more general case.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class EventBus {

    private static final HashMap<Class, List<Subscription>> topics = new HashMap<>();
    private static final List<Pair<Class, Subscription>> toAdd = new ArrayList<>();
    private static boolean lock;

    public static <T> void publish(T event) {
        Class type = event.getClass();

        // Add all subscriptions we need to add
        for (Pair<Class, Subscription> toAdd : toAdd) {
            subscribeInternal(toAdd.getLeft(), toAdd.getRight());
        }
        toAdd.clear();

        if (topics.containsKey(type)) {
            List<Subscription> subscribers = topics.get(type);

            // Subscriptions can be deleted while they're called
            List<Subscription> toDelete = new ArrayList<>();

            // Go through our subscription list. We shouldn't modify the list while we're iterating it.
            lock = true;
            for (Subscription subRaw : subscribers) {
                Subscription<T> sub;
                try {
                    sub = (Subscription<T>) subRaw;
                    if (sub.shouldDelete()) {
                        toDelete.add(sub);
                    } else {
                        sub.accept(event);
                    }
                } catch (ClassCastException e) {
                    System.err.println("TRIED PUBLISHING MISMAPPED EVENT: " + event);
                    e.printStackTrace();
                }
            }
            lock = false;
            // ⛔ FIXED 2026-09-05: "Delete all subscriptions" was the comment, but toDelete was
            // built and then never used -- unsubscribe() only sets a flag (Subscription.delete()),
            // and nothing here ever removed a flagged entry from `subscribers`. Confirmed live: 9
            // real call sites across tasks/ (PlaceBlockNearbyTask, ContainerStoredTracker,
            // CombatTask, PlaceBedAndSetSpawnTask x2, ChunkSearchTask, SearchChunksExploreTask,
            // BedWarsTask, ReplaceBlocksTask) each subscribe when a task starts and unsubscribe
            // when it stops -- and every one of those, over a long playthrough, left a permanently
            // dead entry in this list. It was never freed and every future publish() to the same
            // event type had to skip over it forever, an unbounded per-publish cost that only grows
            // across a session, which is exactly the failure mode a "complete the whole game in one
            // sitting" run would feel. Safe to remove after the loop (not during it, per the
            // comment above): `subscribers` was only appended to via toDelete, never mutated mid-loop.
            subscribers.removeAll(toDelete);
        }
    }

    private static <T> void subscribeInternal(Class<T> type, Subscription<T> sub) {
        if (!topics.containsKey(type)) {
            topics.put(type, new ArrayList<>());
        }
        topics.get(type).add(sub);
    }

    public static <T> Subscription<T> subscribe(Class<T> type, Consumer<T> consumeEvent) {
        Subscription<T> sub = new Subscription<>(consumeEvent);
        if (lock) {
            toAdd.add(new Pair<>(type, sub));
        } else {
            subscribeInternal(type, sub);
        }
        return sub;
    }

    public static <T> void unsubscribe(Subscription<T> subscription) {
        if (subscription != null)
            subscription.delete();
    }
}
