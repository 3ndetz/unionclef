package adris.altoclef.util.progresscheck;

/**
 * A progress checker that can fail a few times before it "actually" fails.
 */
public class ProgressCheckerRetry<T> implements IProgressChecker<T> {

    private final IProgressChecker<T> subChecker;
    private final int allowedAttempts;

    private int failCount;

    public ProgressCheckerRetry(IProgressChecker<T> subChecker, int allowedAttempts) {
        this.subChecker = subChecker;
        this.allowedAttempts = allowedAttempts;
    }

    @Override
    public void setProgress(T progress) {
        subChecker.setProgress(progress);

        // If our subchecker fails, retry with an updated fail counter.
        if (subChecker.failed()) {
            failCount++;
            subChecker.reset();
        }
    }

    @Override
    public boolean failed() {
        return failCount >= allowedAttempts;
    }

    @Override
    public void reset() {
        subChecker.reset();
        failCount = 0;
    }
}
