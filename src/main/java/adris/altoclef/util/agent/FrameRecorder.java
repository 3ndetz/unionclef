package adris.altoclef.util.agent;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Video of the game window only, made by the game itself: every few client ticks the frame is read
 * back from the GPU (asynchronously in 1.21.11) and its raw pixels go down a pipe to ffmpeg, which
 * encodes in its own process. Always 1920x1080: the window is scaled to cover it and the overflow cut
 * (a window on a 1080 screen loses a few rows to the frame and the taskbar). Test videos used to be PNG screenshots pulled over py4j: each one
 * stalled the game while it was encoded, ~3 frames a second and a stuttering game.
 *
 * <p>ffmpeg is the program named on the first line of {@code <game dir>/altoclef/recorder_ffmpeg.txt};
 * videos go to {@code <game dir>/recordings/<name>.mp4}. Neither comes from a command: the MCP server
 * listens on the LAN, and a program path in a command would let anyone with the token start any
 * program here. Not a setting either: {@code @set} reaches every setting, and saves all of them from
 * the client's memory, so a path written into the settings file was also wiped by the next {@code @set}
 * of a client started before it.
 */
public final class FrameRecorder {

    private static final byte[] END = new byte[0];

    private static volatile boolean on;
    private static boolean registered;
    private static int every = 1, tick, w, h, frames, dropped;
    private static String name = "", ffmpeg = "";
    private static File outFile;
    private static Process proc;
    private static Thread writer;
    private static BlockingQueue<byte[]> queue;

    private FrameRecorder() {
    }

    /** Start recording; fps up to 20 (one frame a client tick). Call on the client thread. */
    public static synchronized Map<String, Object> start(String videoName, int fps) {
        if (on) stop();
        String exe = ffmpegPath();
        if (exe.isBlank() || !new File(exe).isFile()) {
            return result(false, "put the path of ffmpeg.exe on the first line of altoclef/recorder_ffmpeg.txt");
        }
        String safe = (videoName == null ? "" : videoName).replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.isEmpty() || safe.startsWith(".")) safe = "rec-" + System.currentTimeMillis();
        if (!safe.endsWith(".mp4")) safe += ".mp4";
        File dir = new File(MinecraftClient.getInstance().runDirectory, "recordings");
        dir.mkdirs();
        if (!registered) {
            ClientTickEvents.END_CLIENT_TICK.register(FrameRecorder::tick);
            registered = true;
        }
        ffmpeg = exe;
        name = safe;
        outFile = new File(dir, safe);
        every = Math.max(1, 20 / Math.max(1, Math.min(fps, 20)));
        tick = w = h = frames = dropped = 0;
        queue = new ArrayBlockingQueue<>(30); // ~1.5 s of frames; a slow encoder drops, never stalls the game
        proc = null;
        on = true;
        return result(true, "");
    }

    private static String ffmpegPath() {
        File f = new File(new File(MinecraftClient.getInstance().runDirectory, "altoclef"), "recorder_ffmpeg.txt");
        try {
            var lines = java.nio.file.Files.readAllLines(f.toPath());
            return lines.isEmpty() ? "" : lines.get(0).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Stop and finish the file; waits for ffmpeg up to a minute. The wait holds no lock: frames
     *  arrive on the render thread under this class's lock, and a stop that waited while holding it
     *  froze the game until ffmpeg was done (01.11, every MCP call after it timed out). */
    public static Map<String, Object> stop() {
        Thread w;
        Process pr;
        BlockingQueue<byte[]> q;
        synchronized (FrameRecorder.class) {
            if (!on) return result(false, "not recording");
            on = false;
            w = writer;
            pr = proc;
            q = queue;
            writer = null;
            proc = null;
        }
        try {
            if (w != null) {
                q.put(END);
                w.join(60_000);
            }
            if (pr != null) pr.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return result(true, "");
    }

    public static Map<String, Object> status() {
        return result(on, "");
    }

    private static Map<String, Object> result(boolean ok, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", ok);
        if (!reason.isEmpty()) m.put("reason", reason);
        m.put("recording", on);
        m.put("frames", frames);
        m.put("dropped", dropped);
        if (outFile != null) m.put("file", outFile.getAbsolutePath());
        if (w > 0) m.put("size", w + "x" + h);
        return m;
    }

    private static void tick(MinecraftClient c) {
        if (!on || c.world == null || ++tick % every != 0) return;
        //#if MC >= 12111
        //$$ net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(c.getFramebuffer(), img -> {
        //$$     try {
        //$$         offer(img.getWidth(), img.getHeight(), img.copyPixelsArgb());
        //$$     } finally {
        //$$         img.close();
        //$$     }
        //$$ });
        //#else
        Debug.logWarning("FrameRecorder: recording needs MC 1.21.11");
        on = false;
        //#endif
    }

    /** A frame as ARGB ints; out to ffmpeg as BGRA bytes (the ints, little-endian). */
    private static synchronized void offer(int width, int height, int[] argb) {
        if (!on) return;
        if (proc == null && !launch(width, height)) return;
        if (width != w || height != h) { // the window was resized: ffmpeg was told one size
            dropped++;
            return;
        }
        ByteBuffer b = ByteBuffer.allocate(argb.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asIntBuffer().put(argb);
        if (queue.offer(b.array())) frames++;
        else dropped++;
    }

    private static boolean launch(int width, int height) {
        w = width;
        h = height;
        try {
            proc = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "rawvideo", "-pix_fmt", "bgra", "-s", w + "x" + h, "-use_wallclock_as_timestamps", "1",
                    "-i", "-", "-vf", "scale=1920:1080:force_original_aspect_ratio=increase:flags=lanczos,crop=1920:1080",
                    "-fps_mode", "cfr",
                    "-r", String.valueOf(20 / every), "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                    "-pix_fmt", "yuv420p", outFile.getAbsolutePath())
                    .redirectErrorStream(true)
                    .redirectOutput(new File(outFile.getParentFile(), name + ".log"))
                    .start();
        } catch (Exception e) {
            Debug.logWarning("FrameRecorder: ffmpeg did not start: " + e.getMessage());
            on = false;
            return false;
        }
        OutputStream pipe = proc.getOutputStream();
        BlockingQueue<byte[]> q = queue;
        writer = new Thread(() -> {
            try (pipe) {
                while (true) {
                    byte[] f = q.take();
                    if (f == END) break;
                    pipe.write(f);
                }
            } catch (Exception e) {
                Debug.logWarning("FrameRecorder: " + e.getMessage());
            }
        }, "frame-recorder");
        writer.setDaemon(true);
        writer.start();
        return true;
    }
}
