package adris.altoclef.eventbus.events;

public class ChangeHealthEvent {
    public float OldHealth;
    public float NewHealth;

    public ChangeHealthEvent(float oldHealth, float newHealth) {
        OldHealth = oldHealth;
        NewHealth = newHealth;
    }
}
