package adris.altoclef.util.time;

public class Stopwatch {

    boolean running = false;
    private double startTime = 0;

    private static double currentTime() {
        return (double) System.currentTimeMillis() / 1000.0;
    }

    public void begin() {
        startTime = currentTime();
        running = true;
    }

    public double time() {
        if (!running) return 0;
        return currentTime() - startTime;
    }
}
