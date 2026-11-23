package io.github.deadly447.h9ops;

import java.util.Locale;

/**
 * What the radio reports about itself (firmware P2 and later): battery voltage,
 * channels A and B (frequency, memory channel or VFO, power, Wide/Narrow), which
 * side is selected, and the APRS settings. The app asks with the SetHardware
 * command "H9I?" (older firmware ignores it); the radio answers "H9PI" and 206
 * hex digits: the RAM bytes listed in the firmware's info_table, in this order.
 *
 * <pre>
 *   0  16  0x11AE8: +1/+2 work mode A/B (0 = VFO), +4 side flag, +7/+8 channel A/B
 *  16   1  0x11B1D: current side (0 = A)
 *  17   1  0x11B50: bit 7 = side B
 *  18   2  0x11B80: battery, volts x 100
 *  20  16  0x11CA0: A RX, A TX, B RX, B TX (10 Hz units)
 *  36  64  0x120C1: channel records A memory, B memory, A VFO, B VFO (16 bytes:
 *          BCD RX and TX, tones, flags; +14 bits 5-4 power, bit 3 narrow)
 * 100   1  0x12BA7: side selection flag
 * 101   1  0xC4A0:  APRS switch (1 = on)
 * 102   1  0xC532:  APRS RX channel (2 = channel B)
 * </pre>
 * These locations come from reading the stock firmware, not from documentation;
 * the developer log keeps the raw bytes so they can be checked against the radio.
 */
public final class RadioInfo {
    public static final String QUERY = "H9I?";
    static final int BYTES = 103;
    public static final long APRS_FREQ = 14_439_000L;     // 144.390 MHz in 10 Hz units
    static final String[] POWER = {"Low", "Mid", "High", "?"};

    /** One side, A or B. */
    public static final class Side {
        public final boolean vfo;
        public final int channel;      // memory channel number (1-based), when not VFO
        public final long rx, tx;      // 10 Hz units; 0 if unknown
        public final int power;        // 0 Low, 1 Mid, 2 High
        public final boolean narrow;

        Side(boolean vfo, int channel, long rx, long tx, int power, boolean narrow) {
            this.vfo = vfo;
            this.channel = channel;
            this.rx = rx;
            this.tx = tx;
            this.power = power;
            this.narrow = narrow;
        }

        public String where() { return vfo ? "VFO" : "CH " + channel; }
        public String powerName() { return POWER[power & 3]; }
        public String width() { return narrow ? "Narrow" : "Wide"; }
    }

    public final int centivolts;
    public final Side a, b;
    public final boolean sideB;        // B is the selected side
    public final boolean aprsOn;
    public final int aprsRx;           // the radio's APRS RX CH setting (2 = channel B)
    public final String raw;           // the reply's hex digits, for the developer log

    private RadioInfo(int centivolts, Side a, Side b, boolean sideB, boolean aprsOn, int aprsRx, String raw) {
        this.centivolts = centivolts;
        this.a = a;
        this.b = b;
        this.sideB = sideB;
        this.aprsOn = aprsOn;
        this.aprsRx = aprsRx;
        this.raw = raw;
    }

    /** The report in a KISS SetHardware payload, or null if it isn't one. */
    public static RadioInfo parse(byte[] p) {
        if (p == null || p.length != 4 + 2 * BYTES || p[0] != 'H' || p[1] != '9' || p[2] != 'P' || p[3] != 'I') return null;
        int[] m = new int[BYTES];
        for (int i = 0; i < BYTES; i++) {
            int hi = hex(p[4 + 2 * i]), lo = hex(p[5 + 2 * i]);
            if (hi < 0 || lo < 0) return null;
            m[i] = hi << 4 | lo;
        }
        Side a = side(m, m[1] == 0, m[7], u32(m, 20), u32(m, 24), m[1] == 0 ? 68 : 36);
        Side b = side(m, m[2] == 0, m[8], u32(m, 28), u32(m, 32), m[2] == 0 ? 84 : 52);
        // as the stock routine at app+0x058D4C picks the side to set up
        boolean sideB = m[100] != 0 ? m[4] != 0 : (m[17] & 0x80) != 0;
        return new RadioInfo(m[18] | m[19] << 8, a, b, sideB, m[101] == 1, m[102],
                new String(p, 4, 2 * BYTES, java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static Side side(int[] m, boolean vfo, int ch, long rx, long tx, int rec) {
        if (!plausible(rx)) rx = bcd(m, rec);
        if (!plausible(tx)) tx = bcd(m, rec + 4);
        int flags = m[rec + 14];
        return new Side(vfo, ch, plausible(rx) ? rx : 0, plausible(tx) ? tx : 0, (flags >> 4) & 3, (flags & 0x08) != 0);
    }

    static boolean plausible(long f) { return f >= 1_800_000L && f <= 130_000_000L; }

    private static long u32(int[] m, int i) {
        return m[i] | (long) m[i + 1] << 8 | (long) m[i + 2] << 16 | (long) m[i + 3] << 24;
    }

    /** Little-endian BCD, 4 bytes, as the radio stores channel frequencies; -1 if not BCD. */
    static long bcd(int[] m, int i) {
        long v = 0;
        for (int k = 3; k >= 0; k--) {
            int hi = m[i + k] >> 4, lo = m[i + k] & 15;
            if (hi > 9 || lo > 9) return -1;
            v = v * 100 + hi * 10 + lo;
        }
        return v;
    }

    private static int hex(byte c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }

    /** "144.390", "146.5225": MHz with at least 3 decimals. */
    public static String mhz(long f10) {
        if (f10 <= 0) return "?";
        String s = String.format(Locale.US, "%d.%05d", f10 / 100_000, f10 % 100_000);
        while (s.length() > s.indexOf('.') + 4 && s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The firmware's 2-cell curve : volts x 100 to percent. */
    static final int[][] CURVE = {{670, 0}, {720, 10}, {736, 20}, {744, 30}, {752, 40}, {760, 50},
            {770, 60}, {784, 70}, {798, 80}, {812, 90}, {824, 100}};

    public int percent() {
        int v = centivolts;
        if (v <= CURVE[0][0]) return 0;
        for (int i = 1; i < CURVE.length; i++) {
            if (v < CURVE[i][0]) {
                int[] lo = CURVE[i - 1], hi = CURVE[i];
                return lo[1] + (v - lo[0]) * (hi[1] - lo[1]) / (hi[0] - lo[0]);
            }
        }
        return 100;
    }

    public String battery() {
        return String.format(Locale.US, "%.2f V %d%%", centivolts / 100.0, percent());
    }

    /** One line per side for the status card; the selected side is marked. */
    public String line(boolean sideB) {
        Side s = sideB ? b : a;
        boolean sel = sideB == this.sideB;
        return (sel ? "▶" : " ") + (sideB ? "B " : "A ") + mhz(s.rx) + "  " + s.where() + "  " + s.powerName()
                + "  " + s.width() + (sideB && aprsOn && aprsRx == 2 ? "  APRS" : "");
    }

    static final String[] POWER_SHORT = {"LO", "MID", "HI", "?"};

    /**
     * One row of the radio card's table for side A or B: side ("▶A" when selected),
     * MHz, channel ("04" or "VFO"), power, width (W/N) and use (TX, APRS or both).
     */
    public String[] row(boolean sideB) {
        Side s = sideB ? b : a;
        boolean sel = sideB == this.sideB;
        boolean aprs = aprsOn && aprsRx == (sideB ? 2 : 1);
        String use = sel && aprs ? "TX·APRS" : sel ? "TX" : aprs ? "APRS" : "";
        return new String[]{(sel ? "▶" : " ") + (sideB ? "B" : "A"), mhz(s.rx),
                s.vfo ? "VFO" : String.format(Locale.US, "%02d", s.channel),
                POWER_SHORT[s.power & 3], s.narrow ? "N" : "W", use};
    }

    /** What's wrong with channel B for APRS, or null when it's 144.390, Wide, APRS on and receiving there. */
    public String channelBProblem() {
        StringBuilder sb = new StringBuilder();
        if (b.rx != APRS_FREQ) sb.append("•  Channel B is on ").append(mhz(b.rx)).append(" MHz, not 144.390\n");
        if (b.narrow) sb.append("•  Channel B is set to Narrow: set it to Wide\n");
        if (!aprsOn) sb.append("•  APRS Switch is off\n");
        else if (aprsRx != 2) sb.append("•  APRS RX CH isn't CH-B\n");
        if (sb.length() == 0) return null;
        return "The radio reports:\n\n" + sb + "\nOn Narrow the radio decodes almost nothing, and what you send "
                + "comes out at half deviation, too quiet for most digipeaters.";
    }

    /** The details, for the radio info dialog. */
    public String details() {
        StringBuilder sb = new StringBuilder("Battery ").append(battery()).append("\n\n");
        for (int k = 0; k < 2; k++) {
            Side s = k == 0 ? a : b;
            sb.append(k == 0 ? "Channel A" : "Channel B").append((k == 1) == sideB ? " (selected)" : "").append('\n')
                    .append("  ").append(mhz(s.rx)).append(" MHz, ").append(s.where()).append('\n');
            if (s.tx > 0 && s.tx != s.rx) sb.append("  transmits on ").append(mhz(s.tx)).append(" MHz\n");
            sb.append("  ").append(s.powerName()).append(" power, ").append(s.width()).append("\n\n");
        }
        sb.append("APRS ").append(aprsOn ? "on" : "off").append(", RX ")
                .append(aprsRx == 2 ? "CH-B" : aprsRx == 1 ? "CH-A" : "setting " + aprsRx);
        return sb.toString();
    }

    /** For the developer log: the decoded summary and the raw bytes behind it (no message text, keys or PIN). */
    public String devLine() {
        return battery() + "; " + line(false).trim() + "; " + line(true).trim() + "; APRS "
                + (aprsOn ? "on" : "off") + " RX " + aprsRx + "; raw " + raw;
    }

    /** True when the decoded settings (not the battery) differ from {@code o}. */
    public boolean settingsDiffer(RadioInfo o) {
        return o == null || !key().equals(o.key());
    }

    private String key() { return line(false) + line(true) + aprsOn + aprsRx; }
}
