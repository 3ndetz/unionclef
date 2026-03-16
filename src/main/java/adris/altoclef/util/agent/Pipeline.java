package adris.altoclef.util.agent;

/**
 * Represents a current pipeline that is higher level than a task chain.
 *
 * Should have method description with short description of a mode and current behavior
 */
public enum Pipeline {
    SpeedRun("Minecraft speedrunning mode - focuses on beating the game by killing the Ender Dragon and firstly gathering need resources. "
    + getCombatDesc() ),
    SkyWars("SkyWars game mode - strategy for surviving and winning in SkyWars minigame. Players spawn on separate sky islands with chests. Goal is to be the last survivor. The center 'mid' has valuable loot. Features chest refills and mob spawns. "
    + getMinigameCombatDesc()),
    MurderMystery("Murder Mystery mode - playing the Murder Mystery minigame. Roles: killer (must kill all), innocent (collects resources), detective (must kill killer only). "
    + getMinigameCombatDesc() + " You cannot build/break blocks during this mode!"),
    BedWars("BedWars game mode - strategy for surviving and winning in Bed Wars minigame. Players spawn on separate sky islands with beds - the target is to protect your own bed and crush destroy others's beds. When your team has bed not destroyed, your teammates will be respawned after each death. The game has a resource system with iron, gold and emeralds. Collecting the resources, you can buy blocks, armor, weapons and other stuff in the shop. "
            + getMinigameCombatDesc()),
    BattleRoyale("Very tricky sweet Yandere girl mode pipeline focusing on trick with unexpected behaviour change: be sweet and offer some items or cute ideas. When target is behaving bad OR tricked=trusted you, then aggressively kill the target showing aggressive gestures, building graves, and show mocks and disrespect to this pathefic naive target. "
    + getCombatDesc()),
    None("");

    private final String description;

    Pipeline(String description) {
        this.description = description;
    }

    /**
     * Get the description of the current pipeline mode and its behavior
     * @return String describing the pipeline's purpose and behavior
     */
    public String getDescription() {
        return this.description;
    }

    private static String getMinigameCombatDesc() {
        return getCombatDesc() +
            "Since this is a minigame mode, player data is reset each round - forget previous player info when game restarts.";
    }

    private static String getCombatDesc() {
        return "You can prioritize your certain player targets with pursue, temporarily ignore with avoid, and stopping targeting with adding to friends.";
    }
}
