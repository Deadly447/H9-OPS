package io.github.deadly447.h9ops;

/**
 * The radio's Bluetooth watchdog counters (firmware P2 and later). The app
 * asks with the SetHardware command "H9W?" (older firmware ignores it); the
 * radio answers "H9PW" and 16 hex digits: watchdog restarts since power-on,
 * the Bluetooth state at the last restart (+128 when it dropped a dead
 * connection), frames that couldn't be sent to the phone just now, the
 * watchdog stage (1 = disconnect asked), then the radio's 100 Hz tick counter
 * (little-endian), which gives its time since power-on.
 */
public final class WatchdogReport {
    public static final String QUERY = "H9W?";

    public final int restarts;
    public final int lastState;      // stock BLE state at the last restart, without the +128 flag
    public final boolean lastStale;  // the last restart dropped a connection that had stopped working
    public final int stalls;
    public final int stage;
    public final long uptimeS;

    private WatchdogReport(int restarts, int last, int stalls, int stage, long uptimeS) {
        this.restarts = restarts;
        this.lastStale = last >= 128;
        this.lastState = last & 0x7f;
        this.stalls = stalls;
        this.stage = stage;
        this.uptimeS = uptimeS;
    }

    /** The report in a KISS SetHardware payload, or null if it isn't one. */
    public static WatchdogReport parse(byte[] p) {
        if (p == null || p.length != 20 || p[0] != 'H' || p[1] != '9' || p[2] != 'P' || p[3] != 'W') return null;
        int[] b = new int[8];
        for (int i = 0; i < 8; i++) {
            int hi = hex(p[4 + 2 * i]), lo = hex(p[5 + 2 * i]);
            if (hi < 0 || lo < 0) return null;
            b[i] = hi << 4 | lo;
        }
        long ticks = b[4] | (long) b[5] << 8 | (long) b[6] << 16 | (long) b[7] << 24;
        return new WatchdogReport(b[0], b[1], b[2], b[3], ticks / 100);
    }

    private static int hex(byte c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    /** The radio's own Bluetooth state numbers (RAM 0x11AD0 + 7). */
    static String stateName(int s) {
        switch (s) {
            case 32: return "advertising";
            case 3: case 33: return "connected";
            case 4: return "disconnect requested";
            case 5: return "disconnected";
            default: return "state " + s;
        }
    }

    static String duration(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min";
        return (s / 3600) + " h " + (s % 3600 / 60) + " min";
    }

    /** One line for the developer log. */
    public String describe() {
        StringBuilder sb = new StringBuilder("radio on for ").append(duration(uptimeS))
                .append("; Bluetooth watchdog restarts since power-on: ").append(restarts);
        if (restarts > 0) {
            sb.append(lastStale
                    ? " (the last one dropped a connection that had stopped working, state "
                    : " (the last one found the radio not advertising, ")
                    .append(stateName(lastState)).append(')');
        }
        if (stalls > 0) sb.append("; ").append(stalls).append(stalls == 1 ? " frame" : " frames")
                .append(" to the phone couldn't be sent just now");
        if (stage == 1) sb.append("; a disconnect is under way");
        return sb.toString();
    }
}
