package adris.altoclef.eventbus.events;

public class DeathEvent {
    public String name;
    public String attacker = null;

    public DeathEvent(String victim) {
        name = victim;
    }

    public DeathEvent(String victim, String killer) {
        name = victim;
        attacker = killer;
    }
}
