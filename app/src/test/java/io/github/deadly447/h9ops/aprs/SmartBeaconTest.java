package io.github.deadly447.h9ops.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SmartBeaconTest {
    private static final long MIN = 60_000L;
    private static final double M_PER_DEG = 111_320;
    private static final double NAN = Double.NaN;

    @Test
    public void rateFollowsSpeed() {
        SmartBeacon.Profile d = SmartBeacon.Profile.DRIVING;
        assertEquals(30 * MIN, d.rateMs(0));
        assertEquals(30 * MIN, d.rateMs(NAN));
        assertEquals(30 * MIN, d.rateMs(5));
        assertEquals(6 * MIN, d.rateMs(30));
        assertEquals(3 * MIN, d.rateMs(60));
        assertEquals(3 * MIN, d.rateMs(80));
        assertEquals(36.5, d.turnDeg(30), 0.01);
        SmartBeacon.Profile f = SmartBeacon.Profile.ON_FOOT;
        assertEquals(10 * MIN, f.rateMs(2));
        assertEquals(5 * MIN, f.rateMs(4));
    }

    /** A car: north at 30 mph, a right turn, east, then parked. */
    @Test
    public void drivingSendsOnRateTurnAndStop() {
        SmartBeacon s = new SmartBeacon(SmartBeacon.Profile.DRIVING);
        double perMin = 30 / SmartBeacon.MPS_TO_MPH * 60;   // metres a minute at 30 mph
        double lat = 37.87, lon = -109.34;
        long t = 1_000_000_000L, lastSent = 0;
        List<String> why = new ArrayList<>();
        // minutes 0-3 north, 4-12 east, 13-50 parked
        for (int m = 0; m <= 50; m++) {
            if (m > 0 && m <= 3) lat += perMin / M_PER_DEG;
            else if (m > 3 && m <= 12) lon += perMin / (M_PER_DEG * Math.cos(Math.toRadians(lat)));
            String r = s.check(lat, lon, t, NAN, NAN, lastSent, t);
            if (r != null) { why.add(m + ":" + r); s.sent(); lastSent = t; }
            t += MIN;
        }
        // first; heading east from minute 4 (turn); 6 min later still moving; stopped at 14
        // (two slow checks); then once per half hour while parked
        assertEquals("[0:first, 4:turn, 10:moving, 14:stopped, 44:parked]", why.toString());
        assertEquals(0, s.mph(), 0.01);
    }

    @Test
    public void standingStillIsNotMovingEvenIfTheGpsSaysSo() {
        SmartBeacon s = new SmartBeacon(SmartBeacon.Profile.ON_FOOT);
        long t = 0;
        assertEquals("first", s.check(37.87, -109.34, t, 0.5, 120, 0, t));
        s.sent();
        // GPS reports 1.1 mph of wander; the fixes are 3 m apart
        t += 2 * MIN;
        assertNull(s.check(37.87 + 3 / M_PER_DEG, -109.34, t, 0.5, 300, 0 + 1, t));
        assertTrue(s.mph() < 0.1);
    }

    @Test
    public void firstCheckUsesTheGpsSpeed() {
        SmartBeacon s = new SmartBeacon(SmartBeacon.Profile.DRIVING);
        s.check(37.87, -109.34, 0, 26.8, 90, 0, 0);    // 26.8 m/s = 60 mph
        assertEquals(60, s.mph(), 0.1);
    }

    @Test
    public void theSameFixTwiceKeepsTheSpeed() {
        SmartBeacon s = new SmartBeacon(SmartBeacon.Profile.DRIVING);
        double perMin = 60 / SmartBeacon.MPS_TO_MPH * 60;
        s.check(37.87, -109.34, 0, NAN, NAN, 0, 0);
        s.check(37.87 + perMin / M_PER_DEG, -109.34, MIN, NAN, NAN, 1, MIN);
        assertEquals(60, s.mph(), 0.5);
        // a cached fix handed out again 5 s later: not a stop
        s.check(37.87 + perMin / M_PER_DEG, -109.34, MIN, NAN, NAN, 1, MIN + 5000);
        assertEquals(60, s.mph(), 0.5);
    }

    @Test
    public void angles() {
        assertEquals(20, SmartBeacon.angle(350, 10), 1e-9);
        assertEquals(180, SmartBeacon.angle(0, 180), 1e-9);
        assertEquals(90, SmartBeacon.bearing(0, 0, 0, 1), 1e-6);
        assertEquals(0, SmartBeacon.bearing(0, 0, 1, 0), 1e-6);
    }

    @Test
    public void smartPlanChecksOftenAndSavesItsProfile() {
        AutoPosition a = new AutoPosition();
        AutoPosition.Plan p = a.start("N0CALL-9", 0, SmartBeacon.Profile.DRIVING, PositionShare.Blur.EXACT, 4, 0);
        assertEquals("smart: driving", p.label());
        assertEquals(1, a.due(0, null).size());
        a.checked(p, 0);
        assertTrue(a.due(59_000, null).isEmpty());
        assertEquals(1, a.due(MIN, null).size());
        a.failed(p, MIN, "No GPS fix");
        assertTrue(a.due(3 * MIN, null).isEmpty());
        assertEquals(1, a.due(4 * MIN, null).size());
        AutoPosition b = new AutoPosition();
        b.load(a.save(), 5 * MIN);
        assertEquals(SmartBeacon.Profile.DRIVING, b.get("N0CALL-9").smart.profile);
        // a 1.25 line (6 fields) still loads as a fixed plan
        b.load(java.util.Arrays.asList("N0CALL-9|30|EXACT|0|0|0"), 0);
        assertEquals(30, b.get("N0CALL-9").minutes);
        assertNull(b.get("N0CALL-9").smart);
    }
}
