package io.github.deadly447.h9ops;

/**
 * Reminders to check the radio's channel B (the APRS channel). With firmware P2 and
 * later the radio reports its settings ({@link RadioInfo#channelBProblem}) and the app
 * warns only when something is wrong. With older firmware it reminds instead: a
 * checklist when you connect, and a hint in the status line when a connection has
 * heard nothing for a while.
 */
public final class ChannelCheck {
    private ChannelCheck() { }

    /** What channel B needs, as one line. */
    public static final String WANT = "144.390, Wide, APRS on";

    /** The checklist shown when the radio connects. */
    public static final String CHECKLIST =
            "On the radio, channel B should be:\n\n"
            + "•  144.390 MHz\n"
            + "•  Wide (not Narrow)\n"
            + "•  APRS Switch on\n\n"
            + "On Narrow the radio still connects but decodes almost nothing, and what you send "
            + "comes out at half deviation, too quiet for most digipeaters.";

    /** Don't show the checklist again within this long, unless you press Connect yourself. */
    public static final long REMIND_GAP_MS = 8L * 60 * 60 * 1000;
    /** Connected this long with nothing heard: show the hint. */
    public static final long QUIET_MS = 15L * 60 * 1000;

    /**
     * Whether to show the checklist now that the radio has connected.
     * @param lastShown when it was last shown (0 = never)
     * @param manual    the user pressed Connect since it was last shown
     */
    public static boolean remind(boolean enabled, long now, long lastShown, boolean manual) {
        if (!enabled) return false;
        return manual || lastShown <= 0 || now - lastShown >= REMIND_GAP_MS || now < lastShown;
    }

    /**
     * The status-line hint, or null. Counts from the later of connecting and the last frame
     * heard, so it clears as soon as something comes in.
     */
    public static String quietHint(long now, long connectedAt, long lastFrameAt) {
        return quietHint(now, connectedAt, lastFrameAt, false);
    }

    /** @param channelBOk the radio itself reported channel B set right (firmware P2) */
    public static String quietHint(long now, long connectedAt, long lastFrameAt, boolean channelBOk) {
        if (connectedAt <= 0) return null;
        long since = Math.max(connectedAt, lastFrameAt);
        long quiet = now - since;
        if (quiet < QUIET_MS) return null;
        return "Nothing heard for " + (quiet / 60000) + " min · "
                + (channelBOk ? "channel B is set right (" + WANT + ")" : "check channel B: " + WANT);
    }
}
