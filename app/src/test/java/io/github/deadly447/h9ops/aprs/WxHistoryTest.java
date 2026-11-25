package io.github.deadly447.h9ops.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WxHistoryTest {
    private static final long H = 3_600_000L;

    private static List<WxHistory.Sample> day() {
        List<WxHistory.Sample> l = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            if (h >= 10 && h < 13) continue;   // station off the air for three hours
            for (int q = 0; q < 4; q++) {      // a report every 15 minutes
                long t = h * H + q * 15 * 60_000L;
                l.add(new WxHistory.Sample(t)
                        .set(WxHistory.Field.TEMP, 40 + h)
                        .set(WxHistory.Field.GUST, q == 2 ? 20 + h : 5)
                        .set(WxHistory.Field.RAIN_1H, h >= 18 ? 0.10 : 0));
            }
        }
        return l;
    }

    @Test
    public void bucketsAverageOrPeakAndLeaveGaps() {
        List<WxHistory.Sample> l = day();
        double[] t = WxHistory.buckets(l, WxHistory.Field.TEMP, 0, 24 * H, 24);
        assertEquals(40, t[0], 1e-9);
        assertEquals(63, t[23], 1e-9);
        assertTrue(Double.isNaN(t[11]));               // the gap stays a gap
        double[] two = WxHistory.buckets(l, WxHistory.Field.TEMP, 0, 24 * H, 12);
        assertEquals(40.5, two[0], 1e-9);              // average of hours 0 and 1
        double[] g = WxHistory.buckets(l, WxHistory.Field.GUST, 0, 24 * H, 24);
        assertEquals(20, g[0], 1e-9);                  // the highest gust in the hour, not the average
        double[] none = WxHistory.buckets(l, WxHistory.Field.PRESSURE, 0, 24 * H, 24);
        assertTrue(Double.isNaN(none[5]));
        assertFalse(WxHistory.has(l, WxHistory.Field.PRESSURE));
        assertTrue(WxHistory.has(l, WxHistory.Field.TEMP));
    }

    @Test
    public void latestRangeAndRain() {
        List<WxHistory.Sample> l = day();
        assertEquals(63, WxHistory.latest(l, WxHistory.Field.TEMP), 1e-9);
        double[] r = WxHistory.range(l, WxHistory.Field.TEMP, 12 * H);
        assertEquals(53, r[0], 1e-9);
        assertEquals(63, r[1], 1e-9);
        // six hours of 0.10"/hour, reported every 15 minutes: about 0.6", not 2.4"
        assertEquals(0.6, WxHistory.rainTotal(l, 0, 24 * H), 1e-9);
        assertTrue(Double.isNaN(WxHistory.rainTotal(l, 25 * H, 30 * H)));
        // temperature rose 3 degrees in the last 3 hours (from 60 at 20:45 to 63 at 23:45)
        assertEquals(3, WxHistory.change(l, WxHistory.Field.TEMP, 3 * H), 1e-9);
        // nothing 30 hours back
        assertTrue(Double.isNaN(WxHistory.change(l, WxHistory.Field.TEMP, 30 * H)));
    }

    @Test
    public void fromAParsedPacket() {
        AprsPacket p = AprsParser.parse(Ax25.parse(Ax25.encode("N0CALL-13", "APRS",
                java.util.Arrays.asList("WIDE2-1"), "_11231805c220s004g012t045r001p010P005h55b10123".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        WxHistory.Sample s = WxHistory.Sample.of(0, p.wx);
        assertEquals(45, s.get(WxHistory.Field.TEMP), 1e-9);
        assertEquals(12, s.get(WxHistory.Field.GUST), 1e-9);
        assertEquals(1012.3, s.get(WxHistory.Field.PRESSURE), 1e-9);
        assertEquals(0.01, s.get(WxHistory.Field.RAIN_1H), 1e-9);
        assertEquals("1012.3 mb", WxHistory.Field.PRESSURE.format(1012.3));
        assertEquals("45°F", WxHistory.Field.TEMP.format(45.2));
        assertEquals("0.10\"", WxHistory.Field.RAIN_24H.format(0.1));
    }

    @Test
    public void missingSensorsAreNotZero() {
        AprsPacket p = AprsParser.parse(Ax25.parse(Ax25.encode("N0CALL-13", "APMI06", java.util.Arrays.asList("WIDE2-1"),
                "@241517z3645.22N/10806.74W_082/000g000t033r000p000P000h00b00000WX3in1".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        assertEquals(33, p.wx.tempF, 1e-9);
        assertTrue(Double.isNaN(p.wx.pressureMb));
        assertTrue(Double.isNaN(p.wx.humidity));
        AprsPacket q = AprsParser.parse(Ax25.parse(Ax25.encode("N0CALL-13", "APRS", java.util.Arrays.asList("WIDE2-1"),
                "_11231805c220s004g012t045h00b10123".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        assertEquals(100, q.wx.humidity, 1e-9);      // a real 100% beside a real pressure stays
    }
}
