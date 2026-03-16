package adris.altoclef.eventbus;

import java.util.function.Consumer;

// A wrapper object for event subscription
public class Subscription<T> {
    private final Consumer<T> callback;
    private boolean shouldDelete;

    public Subscription(Consumer<T> callback) {
        this.callback = callback;
    }

    public void accept(T event) {
        callback.accept(event);
    }

    public void delete() {
        shouldDelete = true;
    }

    public boolean shouldDelete() {
        return shouldDelete;
    }
}
