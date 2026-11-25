package io.github.deadly447.h9ops.aprs;

/**
 * SmartBeaconing for timed positions: send more often when moving fast, rarely when
 * parked, and soon after a corner. The phone checks the GPS every minute or two
 * (the profile's check time) and this class decides whether that fix is worth sending.
 *
 * Rate: at or below the slow speed, one send per slow rate; at or above the fast
 * speed, one per fast rate; in between the rate scales with speed (fast rate x fast
 * speed / speed). A corner sends early once the heading has changed by more than
 * min angle + slope / speed. Stopping (two checks in a row below the slow speed)
 * sends once, so the last position isn't left a few minutes behind.
 */
public final class SmartBeacon {
    public enum Profile {
        //       label            slow mph, min   fast mph, min   turn min deg, slope   min turn s   check s
        DRIVING("Smart: driving", 5, 30,          60, 3,          28, 255,              30,          60),
        ON_FOOT("Smart: on foot", 1, 30,          4, 5,           30, 15,               300,         120);

        public final String label;
        public final double slowMph, fastMph, turnMinDeg, turnSlope;
        public final long slowMs, fastMs, minTurnMs, checkMs;

        Profile(String label, double slowMph, int slowMin, double fastMph, int fastMin,
                double turnMinDeg, double turnSlope, int minTurnSec, int checkSec) {
            this.label = label;
            this.slowMph = slowMph;
            this.slowMs = slowMin * 60_000L;
            this.fastMph = fastMph;
            this.fastMs = fastMin * 60_000L;
            this.turnMinDeg = turnMinDeg;
            this.turnSlope = turnSlope;
            this.minTurnMs = minTurnSec * 1000L;
            this.checkMs = checkSec * 1000L;
        }

        /** Time between sends at this speed. */
        public long rateMs(double mph) {
            if (Double.isNaN(mph) || mph <= slowMph) return slowMs;
            if (mph >= fastMph) return fastMs;
            return Math.max(fastMs, Math.min(slowMs, Math.round(fastMs * fastMph / mph)));
        }

        /** Heading change, degrees, that counts as a corner at this speed. */
        public double turnDeg(double mph) {
            return turnMinDeg + turnSlope / Math.max(mph, 0.1);
        }
    }

    static final double MPS_TO_MPH = 2.2369363;
    /** Speed from two fixes needs them at least this far apart in time... */
    static final long MIN_DT_MS = 20_000;
    /** ...and no further apart than this (older fixes say nothing about now). */
    static final long MAX_DT_MS = 15 * 60_000L;
    /** A heading from two fixes needs this much movement, metres (GPS wanders a few metres). */
    static final double MIN_MOVE_M = 30;
    static final long SLACK_MS = 5_000;

    public final Profile profile;
    private double prevLat = Double.NaN, prevLon = Double.NaN;
    private long prevTime;
    private double mph = Double.NaN, course = Double.NaN;
    private double sentMph = Double.NaN, sentCourse = Double.NaN;
    /** Checks in a row below the slow speed (one slow check may just be a traffic light). */
    private int slowChecks;

    public SmartBeacon(Profile profile) { this.profile = profile; }

    /** Speed at the last check, mph (NaN before the first). */
    public double mph() { return mph; }

    /**
     * Looks at a new fix. Returns why it should be sent now ("first", "moving", "turn",
     * "stopped", "parked"), or null to wait for a later check.
     *
     * @param speedMps speed the GPS reported, m/s, or NaN
     * @param courseDeg heading the GPS reported, degrees, or NaN
     * @param lastSent when a position was last sent, ms; 0 = never
     */
    public String check(double lat, double lon, long fixTime, double speedMps, double courseDeg,
                        long lastSent, long now) {
        double reported = Double.isNaN(speedMps) ? Double.NaN : speedMps * MPS_TO_MPH;
        double derived = Double.NaN, derivedCourse = Double.NaN;
        long dt = fixTime - prevTime;
        if (!Double.isNaN(prevLat) && dt >= MIN_DT_MS && dt <= MAX_DT_MS) {
            double m = Alerts.metres(prevLat, prevLon, lat, lon);
            derived = m / (dt / 1000.0) * MPS_TO_MPH;
            if (m >= MIN_MOVE_M) derivedCourse = bearing(prevLat, prevLon, lat, lon);
        }
        if (!Double.isNaN(prevLat) && dt < MIN_DT_MS && dt >= 0) {
            // the same (or almost the same) fix again: keep the speed worked out last time
            derived = mph;
        } else {
            prevLat = lat;
            prevLon = lon;
            prevTime = fixTime;
        }
        // The average over the last check is steadier than the GPS's instant speed,
        // which wanders by a mile an hour or so while standing still.
        mph = !Double.isNaN(derived) ? derived : Double.isNaN(reported) ? 0 : reported;
        course = !Double.isNaN(courseDeg) && !Double.isNaN(reported) && reported >= profile.slowMph
                ? courseDeg : derivedCourse;

        boolean moving = mph >= profile.slowMph;
        slowChecks = moving ? 0 : slowChecks + 1;
        if (lastSent <= 0) return "first";
        long since = now - lastSent;
        // Sent while parked (no heading then): the first heading after it is what a turn is measured from.
        if (Double.isNaN(sentCourse) && moving && !Double.isNaN(course)) sentCourse = course;
        // (a few seconds' slack: checks never land exactly on the minute)
        if (since + SLACK_MS >= profile.rateMs(mph)) return moving ? "moving" : "parked";
        if (moving && since >= profile.minTurnMs && !Double.isNaN(course) && !Double.isNaN(sentCourse)
                && angle(course, sentCourse) > profile.turnDeg(mph)) return "turn";
        if (slowChecks >= 2 && !Double.isNaN(sentMph) && sentMph >= profile.slowMph) return "stopped";
        return null;
    }

    /** The fix from the last check was sent. */
    public void sent() {
        sentMph = mph;
        sentCourse = course;
    }

    /** Smallest difference between two headings, 0-180 degrees. */
    static double angle(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    /** Initial bearing from one point to another, degrees from north. */
    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }
}
