package adris.altoclef.util.agent;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Voice for a local agent without py4j: one UDP datagram to 127.0.0.1 per Simple Voice Chat
 * packet. The py4j path needs a callback server on the Python side and a gateway that a settings
 * reload used to restart under a connected client; then voice stopped until someone reconnected
 * by hand. A datagram needs neither: nobody listening costs nothing, a listener that restarts gets
 * the next packet.
 *
 * Datagram: "PYV1" | u8 length + own nick | u8 length + speaker | PCM 16-bit LE, 48 kHz mono.
 * The own nick lets two clients on one machine share the port; each listener keeps its bot's.
 */
public final class VoiceUdp {
    private static final byte[] MAGIC = {'P', 'Y', 'V', '1'};
    private static DatagramSocket socket;
    private static boolean broken;

    private VoiceUdp() {
    }

    public static void send(int port, String self, String speaker, byte[] pcm) {
        if (port <= 0 || broken) return;
        byte[] a = utf8(self), b = utf8(speaker);
        byte[] out = new byte[MAGIC.length + 2 + a.length + b.length + pcm.length];
        int i = 0;
        System.arraycopy(MAGIC, 0, out, i, MAGIC.length);
        i += MAGIC.length;
        out[i++] = (byte) a.length;
        System.arraycopy(a, 0, out, i, a.length);
        i += a.length;
        out[i++] = (byte) b.length;
        System.arraycopy(b, 0, out, i, b.length);
        i += b.length;
        System.arraycopy(pcm, 0, out, i, pcm.length);
        try {
            socket().send(new DatagramPacket(out, out.length, InetAddress.getLoopbackAddress(), port));
        } catch (java.net.PortUnreachableException e) {
            // nobody listening yet
        } catch (java.net.SocketException e) {
            // no socket at all (not a missing listener: UDP does not report that): stop trying
            broken = true;
        } catch (Exception ignored) {
            // one lost packet; STT needs a steady stream, not every frame
        }
    }

    private static synchronized DatagramSocket socket() throws java.net.SocketException {
        if (socket == null) socket = new DatagramSocket();
        return socket;
    }

    /** UTF-8 cut to 255 bytes, the length field is one byte. */
    private static byte[] utf8(String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        return b.length <= 255 ? b : java.util.Arrays.copyOf(b, 255);
    }
}
