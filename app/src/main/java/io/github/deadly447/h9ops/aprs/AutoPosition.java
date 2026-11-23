package io.github.deadly447.h9ops.aprs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Timed position sharing: send my position to one station every N minutes,
 * as a normal position message.
 * This class only decides *when*; the app gets the GPS fix and sends.
 *
 * Every plan has an end time by default, so a forgotten plan can't keep
 * reporting your position indefinitely.
 */
public final class AutoPosition {
    /** Interval choices, minutes. */
    public static final int[] MINUTES = {5, 10, 15, 30, 60, 120};
    /** "Stop after" choices, hours; 0 = until turned off. */
    public static final int[] STOP_HOURS = {1, 4, 12, 24, 0};
    /** When no GPS fix was possible, try again this soon instead of waiting a full interval. */
    public static final long NO_FIX_RETRY_MS = 5 * 60_000L;
    static final long NEVER = Long.MIN_VALUE;

    public static final class Plan {
        public final String peer;
        public final int minutes;
        public final PositionShare.Blur blur;
        /** Stop at this time (ms), or 0 for "until turned off". */
        public final long until;
        /** Last attempt (fix taken and sent, or tried and failed), ms; NEVER = not yet. */
        long lastTry = NEVER;
        /** Last time a position was actually sent, ms; 0 = not yet. */
        public long lastSent;
        /** Why the last attempt didn't send, or null. */
        public String problem;

        Plan(String peer, int minutes, PositionShare.Blur blur, long until) {
            this.peer = peer;
            this.minutes = minutes;
            this.blur = blur;
            this.until = until;
        }

        public long intervalMs() { return minutes * 60_000L; }

        /** When the next send is due (ms). */
        public long next() { return lastTry == NEVER ? Long.MIN_VALUE : lastTry + intervalMs(); }
    }

    private final Map<String, Plan> plans = new LinkedHashMap<>();

    /** Starts (or replaces) a plan. The first send is due straight away. */
    public Plan start(String peer, int minutes, PositionShare.Blur blur, int stopHours, long now) {
        if (minutes < 1) throw new IllegalArgumentException("interval");
        String p = peer.toUpperCase(Locale.US);
        Plan plan = new Plan(p, minutes, blur, stopHours > 0 ? now + stopHours * 3_600_000L : 0);
        plans.put(p, plan);
        return plan;
    }

    public void stop(String peer) { plans.remove(peer.toUpperCase(Locale.US)); }

    public Plan get(String peer) { return plans.get(peer.toUpperCase(Locale.US)); }

    public boolean any() { return !plans.isEmpty(); }

    public List<Plan> plans() { return new ArrayList<>(plans.values()); }

    /** Plans due now. Plans past their end time are removed (and listed in {@code ended}). */
    public List<Plan> due(long now, List<Plan> ended) {
        List<Plan> out = new ArrayList<>();
        for (Plan p : new ArrayList<>(plans.values())) {
            if (p.until != 0 && now >= p.until) {
                plans.remove(p.peer);
                if (ended != null) ended.add(p);
                continue;
            }
            if (now >= p.next()) out.add(p);
        }
        return out;
    }

    /** The position was sent. */
    public void sent(Plan p, long now) {
        p.lastTry = now;
        p.lastSent = now;
        p.problem = null;
    }

    /** No fix (or no permission): try again in a few minutes, not a full interval. */
    public void failed(Plan p, long now, String why) {
        p.lastTry = now - p.intervalMs() + Math.min(NO_FIX_RETRY_MS, p.intervalMs());
        p.problem = why;
    }

    /** Milliseconds until the next plan is due (or ends), for scheduling a wake-up. -1 = no plans. */
    public long msUntilNext(long now) {
        long best = -1;
        for (Plan p : plans.values()) {
            long t = Math.max(p.next(), now);
            if (p.until != 0) t = Math.min(t, p.until);
            long d = Math.max(0, t - now);
            if (best < 0 || d < best) best = d;
        }
        return best;
    }

    // ---- saving: one line per plan: PEER|minutes|blur|until|lastTry|lastSent

    public List<String> save() {
        List<String> out = new ArrayList<>();
        for (Plan p : plans.values()) {
            out.add(p.peer + "|" + p.minutes + "|" + p.blur.name() + "|" + p.until + "|" + p.lastTry + "|" + p.lastSent);
        }
        return out;
    }

    public void load(List<String> lines, long now) {
        plans.clear();
        for (String l : lines) {
            String[] f = l.split("\\|");
            if (f.length != 6 || !Ax25.validCall(f[0])) continue;
            try {
                int minutes = Integer.parseInt(f[1]);
                PositionShare.Blur blur = PositionShare.Blur.valueOf(f[2]);
                long until = Long.parseLong(f[3]);
                if (minutes < 1 || (until != 0 && until <= now)) continue;
                Plan p = new Plan(f[0], minutes, blur, until);
                long lt = Long.parseLong(f[4]);
                p.lastTry = lt == NEVER ? NEVER : Math.min(lt, now);
                p.lastSent = Math.min(Long.parseLong(f[5]), now);
                plans.put(p.peer, p);
            } catch (IllegalArgumentException ignored) {
                // bad line: skip it
            }
        }
    }
}
