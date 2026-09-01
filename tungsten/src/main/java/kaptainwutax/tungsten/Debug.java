package kaptainwutax.tungsten;

import net.minecraft.text.Text;

public class Debug {

    public static void logInternal(String message) {
        System.out.println("Tungsten: " + message);
    }

    public static void logInternal(String format, Object... args) {
        logInternal(String.format(format, args));
    }

    private static String getLogPrefix() {
        return "[Tungsten] ";
    }

    /**
     * Repeats of the SAME line are swallowed for a few seconds and go to the console instead.
     *
     * <p>The pathfinder narrates every attempt, so a player standing at their destination gets
     * "Already at target location!" printed nine times in a row. That chatter lands in three places
     * that matter: the recorded gameplay (it is burned into every frame of the b-roll our videos are
     * cut from), the chat the agent reads to understand what is happening around it, and the client
     * log. Each distinct message still shows up the first time, so nothing an agent needs to see is
     * lost — only the flood is.
     */
    private static final java.util.Map<String, Long> LAST_SHOWN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REPEAT_MUTE_MS = 5000;

    private static boolean isRepeat(String message) {
        long now = System.currentTimeMillis();
        Long prev = LAST_SHOWN.put(message, now);
        if (LAST_SHOWN.size() > 256) {
            LAST_SHOWN.entrySet().removeIf(e -> now - e.getValue() > REPEAT_MUTE_MS);
        }
        return prev != null && now - prev < REPEAT_MUTE_MS;
    }

    public static void logMessage(String message, boolean prefix) {
        if (isRepeat(message)) {
            logInternal("(repeat muted) " + message);
            return;
        }
        if (TungstenModDataContainer.player != null) {
            if (prefix) {
                message = "\u00A72\u00A7l\u00A7o" + getLogPrefix() + "\u00A7r" + message;
            }
            try {
                TungstenModDataContainer.player.sendMessage(Text.of(message), false);
            } catch (Exception e) {
                // MC ChatHud can crash with IndexOutOfBoundsException on message overflow
                logInternal("Chat overflow, message dropped: " + message);
            }
        } else {
            logInternal(message);
        }
    }

    public static void logMessage(String message) {
        logMessage(message, true);
    }

    public static void logMessage(String format, Object... args) {
        logMessage(String.format(format, args));
    }

    public static void logWarning(String message) {
        if (TungstenModDataContainer.player != null) {
            message = "\u00A72\u00A7l\u00A7oWARNING:\u00A7r" + message;
            try {
                TungstenModDataContainer.player.sendMessage(Text.of(message), false);
            } catch (Exception e) {
                logInternal("Chat overflow, warning dropped: " + message);
            }
        } else {
            logInternal("WARNING: " + message);
        }
    }

    public static void logWarning(String format, Object... args) {
        logWarning(String.format(format, args));
    }

    public static void logError(String message) {
        String stacktrace = getStack(2);
        System.err.println(message);
        System.err.println("at:");
        System.err.println(stacktrace);
    }

    public static void logError(String format, Object... args) {
        logError(String.format(format, args));
    }

    public static void logStack() {
        logInternal("STACKTRACE: \n" + getStack(2));
    }

    private static String getStack(int toSkip) {
        StringBuilder stacktrace = new StringBuilder();
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            if (toSkip-- <= 0) {
                stacktrace.append(ste.toString()).append("\n");
            }
        }
        return stacktrace.toString();
    }
}

