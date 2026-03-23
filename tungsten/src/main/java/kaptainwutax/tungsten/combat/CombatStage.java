package kaptainwutax.tungsten.combat;

/**
 * Combat state machine stages.
 *
 * PURSUE          — chasing target, aim at predicted future position
 * DANGER_BATTLE   — next enemy hit could knock us off; reposition while fighting
 * DANGER_IMMINENT — our velocity leads into a fall; emergency brake + escape jump
 * ESCAPE          — disengage: target just hit (damage immunity), or mutual edge danger
 * DELICATE_BATTLE — low HP, careful play (future)
 */
public enum CombatStage {
    PURSUE,
    DANGER_BATTLE,
    DANGER_IMMINENT,
    ESCAPE,
    DELICATE_BATTLE;

    public String chatColor() {
        return switch (this) {
            case PURSUE -> "§a";           // green
            case DANGER_BATTLE -> "§e";    // yellow
            case DANGER_IMMINENT -> "§c";  // red
            case ESCAPE -> "§b";           // cyan
            case DELICATE_BATTLE -> "§d";  // pink
        };
    }
}
