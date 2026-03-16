package adris.altoclef.util.time;

public abstract class BaseTimer {
    private double prevTime = 0;
    private double interval;

    public BaseTimer(double intervalSeconds) {
        interval = intervalSeconds;
    }

    public double getDuration() {
        return currentTime() - prevTime;
    }

    public void setInterval(double interval) {
        this.interval = interval;
    }

    public boolean elapsed() {
        return getDuration() > interval;
    }

    public void reset() {
        prevTime = currentTime();
    }

    public void forceElapse() {
        prevTime = 0;
    }

    protected abstract double currentTime();

    protected void setPrevTimeForce(double toSet) {
        prevTime = toSet;
    }

    protected double getPrevTime() {
        return prevTime;
    }

}
