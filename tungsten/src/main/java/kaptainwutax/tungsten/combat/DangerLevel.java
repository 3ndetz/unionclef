package kaptainwutax.tungsten.combat;

/**
 * Height danger classification.
 *
 * NONE          — 0-1 blocks, safe
 * HEIGHT_RELIEF — 1-3 blocks, minor height loss, no damage. Ignorable during ESCAPE.
 * HEIGHT_HIGH   — 4-9 blocks, fall damage. Triggers DANGER_IMMINENT.
 * HEIGHT_DEATH  — 10+ blocks, lethal fall. Maximum priority brake.
 */
public enum DangerLevel {
    NONE,
    HEIGHT_RELIEF,
    HEIGHT_HIGH,
    HEIGHT_DEATH;

    public static DangerLevel fromFallHeight(int blocks) {
        if (blocks <= 1) return NONE;
        if (blocks <= 3) return HEIGHT_RELIEF;
        if (blocks <= 9) return HEIGHT_HIGH;
        return HEIGHT_DEATH;
    }

    public boolean isSerious() {
        return this == HEIGHT_HIGH || this == HEIGHT_DEATH;
    }

    public String chatColor() {
        return switch (this) {
            case NONE -> "§a";
            case HEIGHT_RELIEF -> "§e";
            case HEIGHT_HIGH -> "§6";
            case HEIGHT_DEATH -> "§4";
        };
    }
}
