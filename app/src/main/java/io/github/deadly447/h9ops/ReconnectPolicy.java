package io.github.deadly447.h9ops;

/**
 * When to try the radio again, and how. Plain Java so it can be unit tested.
 *
 * Normal: scan for a few seconds, and if the scan misses the radio try a
 * direct connection anyway (some phones filter scans oddly). Waits double
 * from 3 s up to 30 s.
 *
 * Silent radio: after {@link #SILENT_AFTER} scans in a row that saw nothing,
 * the radio isn't advertising (its Bluetooth is off or stuck), so a direct
 * connection can only time out (status 147). Then scan only, every 2 minutes,
 * until a scan sees it again. If a direct connection has ever worked on this
 * phone after a missed scan, direct connections are kept, since there the
 * scan is what is unreliable.
 */
final class ReconnectPolicy {
    static final long FIRST_MS = 3_000, MAX_MS = 30_000, SILENT_MS = 120_000;
    static final int SILENT_AFTER = 3;

    private long retryMs = FIRST_MS;
    private int missed;
    private boolean silent, directWorks, directTry;

    /** A connection is up. */
    void connected() {
        if (directTry && missed > 0) directWorks = true;
        retryMs = FIRST_MS;
        missed = 0;
        silent = false;
        directTry = false;
    }

    /** A scan saw the radio. Returns true if it had been silent until now. */
    boolean seen() {
        boolean was = silent;
        missed = 0;
        silent = false;
        directTry = false;
        return was;
    }

    /**
     * A scan ended without seeing the radio. Returns true to try a direct
     * connection, false to skip it and just wait for the next scan.
     */
    boolean scanMissed() {
        missed++;
        if (missed >= SILENT_AFTER && !directWorks) {
            silent = true;
            directTry = false;
            return false;
        }
        directTry = true;
        return true;
    }

    /** The wait before the next attempt. */
    long nextDelay() {
        if (silent) return SILENT_MS;
        long d = retryMs;
        retryMs = Math.min(MAX_MS, retryMs * 2);
        return d;
    }

    /** The user started the connection again: start from the beginning. */
    void restart() {
        retryMs = FIRST_MS;
        missed = 0;
        silent = false;
        directTry = false;
    }

    boolean silent() { return silent; }
    int missed() { return missed; }
    boolean directWorks() { return directWorks; }
}
