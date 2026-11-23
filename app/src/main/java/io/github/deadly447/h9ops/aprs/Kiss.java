package io.github.deadly447.h9ops.aprs;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Joins KISS bytes that arrive in pieces (BLE notifications are 20 bytes)
 * into whole frames. Extra FENDs between frames are ignored.
 */
public final class Kiss {
    public static final int FEND = 0xC0, FESC = 0xDB, TFEND = 0xDC, TFESC = 0xDD;
    private static final int MAX_FRAME = 1024;

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private boolean inFrame = false;
    private boolean escape = false;

    /** One KISS frame: the command byte's low nibble (0 = data, 6 = SetHardware) and the rest. */
    public static final class Frame {
        public final int command;
        public final byte[] payload;
        Frame(int command, byte[] payload) { this.command = command; this.payload = payload; }
    }

    /** Feed received bytes; returns the AX.25 payloads of any complete data frames. */
    public List<byte[]> feed(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        for (Frame f : feedFrames(data)) if (f.command == 0) out.add(f.payload);
        return out;
    }

    /** Feed received bytes; returns every complete frame (data and commands). */
    public List<Frame> feedFrames(byte[] data) {
        List<Frame> out = new ArrayList<>();
        if (data == null) return out;
        for (byte bb : data) {
            int b = bb & 0xFF;
            if (b == FEND) {
                if (inFrame && buf.size() > 1) {
                    byte[] f = buf.toByteArray();
                    byte[] rest = new byte[f.length - 1];
                    System.arraycopy(f, 1, rest, 0, rest.length);
                    out.add(new Frame(f[0] & 0x0F, rest));
                }
                buf.reset();
                inFrame = true;
                escape = false;
                continue;
            }
            if (!inFrame) continue;
            if (escape) {
                if (b == TFEND) b = FEND;
                else if (b == TFESC) b = FESC;
                escape = false;
            } else if (b == FESC) {
                escape = true;
                continue;
            }
            if (buf.size() >= MAX_FRAME) {   // runaway frame: drop it
                buf.reset();
                inFrame = false;
                continue;
            }
            buf.write(b);
        }
        return out;
    }

    /** A KISS SetHardware (0x06) frame carrying an ASCII command, as the P2 radio PIN uses. */
    public static byte[] command(String ascii) {
        byte[] a = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[a.length + 3];
        out[0] = (byte) FEND;
        out[1] = 0x06;
        System.arraycopy(a, 0, out, 2, a.length);
        out[out.length - 1] = (byte) FEND;
        return out;
    }

    /** KISS TXDELAY: the spec's own default is 50 (500 ms). */
    public static final int TXDELAY_SPEC_DEFAULT_MS = 500;
    public static final int TXDELAY_MAX_MS = 2550;

    /**
     * A KISS TXDELAY (command 0x01) frame for port 0: how long the radio keys
     * up and sends flags before the packet, in 10 ms units (0 to 255).
     */
    public static byte[] txDelay(int ms) {
        int v = Math.max(0, Math.min(255, Math.round(ms / 10f)));
        if (v == FEND) return new byte[] {(byte) FEND, 0x01, (byte) FESC, (byte) TFEND, (byte) FEND};
        if (v == FESC) return new byte[] {(byte) FEND, 0x01, (byte) FESC, (byte) TFESC, (byte) FEND};
        return new byte[] {(byte) FEND, 0x01, (byte) v, (byte) FEND};
    }

    /** Wraps an AX.25 frame as a KISS data frame for port 0. */
    public static byte[] encode(byte[] ax25) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(ax25.length + 8);
        o.write(FEND);
        o.write(0x00);
        for (byte bb : ax25) {
            int b = bb & 0xFF;
            if (b == FEND) { o.write(FESC); o.write(TFEND); }
            else if (b == FESC) { o.write(FESC); o.write(TFESC); }
            else o.write(b);
        }
        o.write(FEND);
        return o.toByteArray();
    }

    public void reset() {
        buf.reset();
        inFrame = false;
        escape = false;
    }
}
