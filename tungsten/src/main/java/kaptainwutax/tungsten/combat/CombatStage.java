package kaptainwutax.tungsten.combat;

/**
 * Combat state machine stages.
 *
 * PURSUE          — chasing target, aim at predicted future position
 * NARROW_BATTLE   — fighting on bridge/narrow terrain, walk only (no jumps)
 * DANGER_BATTLE   — next enemy hit could knock us off; reposition while fighting
 * DANGER_IMMINENT — our velocity leads into a fall; emergency brake + escape jump
 * ESCAPE          — disengage: target just hit (damage immunity), or mutual edge danger
 * DELICATE_BATTLE — low HP, careful play (future)
 */
public enum CombatStage {
    PURSUE,
    NARROW_BATTLE,
    DANGER_BATTLE,
    DANGER_IMMINENT,
    ESCAPE,
    DELICATE_BATTLE;

    public String chatColor() {
        return switch (this) {
            case PURSUE -> "§a";           // green
            case NARROW_BATTLE -> "§9";    // blue
            case DANGER_BATTLE -> "§e";    // yellow
            case DANGER_IMMINENT -> "§c";  // red
            case ESCAPE -> "§b";           // cyan
            case DELICATE_BATTLE -> "§d";  // pink
        };
    }
}
