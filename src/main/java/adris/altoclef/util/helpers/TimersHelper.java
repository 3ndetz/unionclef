package adris.altoclef.util.helpers;

import adris.altoclef.util.time.TimerGame;

public abstract class TimersHelper {
    private static final TimerGame _chestInteractTimer = new TimerGame(0.7);

    public static void ChestInteractTimerReset() {
        _chestInteractTimer.reset();
    }

    public static boolean CanChestInteract() {
        return _chestInteractTimer.elapsed();
    }
}
