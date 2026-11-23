package io.github.deadly447.h9ops.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PositionShareTest {
    static final long T = 1_763_500_500_000L;   // a fixed time for the tests

    @Test
    public void exactRoundTrip() {
        String s = PositionShare.encode(40.841034, -105.461203, PositionShare.Blur.EXACT, T);
        assertTrue(s, s.matches("POS 40\\.84103,-105\\.46120 @\\d{4}z"));
        PositionShare.Pos p = PositionShare.decode(s);
        assertEquals(40.84103, p.lat, 1e-9);
        assertEquals(-105.46120, p.lon, 1e-9);
        assertEquals(PositionShare.Blur.EXACT, p.blur);
    }

    @Test
    public void blurHidesTheSpotWithinTheCell() {
        for (PositionShare.Blur b : PositionShare.Blur.values()) {
            if (b == PositionShare.Blur.EXACT) continue;
            // two different points in the same cell give the same report
            double cell = b.cellDeg;
            double base = Math.floor(40.84 / cell) * cell;
            double baseLon = Math.floor(-105.46 / cell) * cell;
            String a = PositionShare.encode(base + cell * 0.1, baseLon + cell * 0.2, b, T);
            String c = PositionShare.encode(base + cell * 0.9, baseLon + cell * 0.8, b, T);
            assertEquals(a, c);
            PositionShare.Pos p = PositionShare.decode(a);
            assertEquals(b, p.blur);
            assertEquals(base + cell / 2, p.lat, cell * 0.01);
            assertTrue(a + " fits a plain message", a.length() <= Messenger.MAX_TEXT);
        }
    }

    @Test
    public void notAPosition() {
        assertNull(PositionShare.decode("hello"));
        assertNull(PositionShare.decode("POS 99.0,0.0"));
        assertNull(PositionShare.decode("POS 40.8,-105.4 ~7mi"));
        assertNull(PositionShare.decode("POS 40.8,-105.4 7mi"));
        assertNull(PositionShare.decode("POS 40.8,-105.4; rm -rf"));
    }

    @Test
    public void blurTagWithOrWithoutTilde() {
        assertEquals(PositionShare.Blur.ONE_MILE, PositionShare.decode("POS 40.8417,-105.4583 ~1mi @0515z").blur);
        assertEquals(PositionShare.Blur.ONE_MILE, PositionShare.decode("POS 40.8417,-105.4583 1mi @0515z").blur);
        String s = PositionShare.encode(40.84103, -105.4612, PositionShare.Blur.TEN_MILES, T);
        assertEquals(s, Messenger.cleanText(s));   // nothing APRS forbids
    }

    @Test
    public void sentAndReadBack() {
        MessengerTest.Phone a = new MessengerTest.Phone("N0CALL-7"), b = new MessengerTest.Phone("N0CALL-5");
        String text = PositionShare.encode(40.84103, -105.4612, PositionShare.Blur.ONE_MILE, T);
        a.m.send("N0CALL-5", text, T);
        AprsPacket onAir = a.drain().get(0);
        Messenger.Msg got = b.m.onPacket(onAir, T + 1000);
        PositionShare.Pos p = PositionShare.decode(got.text);
        assertEquals(PositionShare.Blur.ONE_MILE, p.blur);
        AprsPacket pk = PositionShare.toPacket(got.peer, p, onAir.raw);
        assertTrue(pk.hasPosition());
        assertTrue(pk.comment, pk.comment.contains("rounded to about 1 mile"));
    }
}
