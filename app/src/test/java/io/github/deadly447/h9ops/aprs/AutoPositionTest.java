package io.github.deadly447.h9ops.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AutoPositionTest {
    private static final long MIN = 60_000L, HOUR = 3_600_000L;

    @Test
    public void firstSendIsImmediateThenEveryInterval() {
        AutoPosition a = new AutoPosition();
        AutoPosition.Plan p = a.start("n0call-7", 30, PositionShare.Blur.ONE_MILE, 12, 0);
        assertEquals("N0CALL-7", p.peer);
        assertEquals(1, a.due(0, null).size());
        a.sent(p, 0);
        assertTrue(a.due(29 * MIN, null).isEmpty());
        assertEquals(1, a.due(30 * MIN, null).size());
        assertEquals(30 * MIN, a.msUntilNext(0));
    }

    @Test
    public void noFixRetriesSoonerThanTheInterval() {
        AutoPosition a = new AutoPosition();
        AutoPosition.Plan p = a.start("N0CALL-7", 60, PositionShare.Blur.EXACT, 0, 0);
        a.failed(p, 10 * MIN, "No GPS fix");
        assertTrue(a.due(14 * MIN, null).isEmpty());
        assertEquals(1, a.due(15 * MIN, null).size());
        assertEquals("No GPS fix", p.problem);
    }

    @Test
    public void planEndsAtItsStopTime() {
        AutoPosition a = new AutoPosition();
        a.start("N0CALL-7", 30, PositionShare.Blur.ONE_MILE, 1, 0);
        List<AutoPosition.Plan> ended = new ArrayList<>();
        assertTrue(a.due(HOUR, ended).isEmpty());
        assertEquals(1, ended.size());
        assertTrue(!a.any());
    }

    @Test
    public void untilTurnedOffNeverEnds() {
        AutoPosition a = new AutoPosition();
        AutoPosition.Plan p = a.start("N0CALL-7", 120, PositionShare.Blur.TEN_MILES, 0, 0);
        a.sent(p, 0);
        assertEquals(1, a.due(1000 * HOUR, null).size());
    }

    @Test
    public void saveAndLoadKeepTiming() {
        AutoPosition a = new AutoPosition();
        AutoPosition.Plan p = a.start("N0CALL-7", 15, PositionShare.Blur.TENTH_MILE, 4, 0);
        a.sent(p, 5 * MIN);
        AutoPosition b = new AutoPosition();
        b.load(a.save(), 6 * MIN);
        AutoPosition.Plan q = b.get("N0CALL-7");
        assertEquals(15, q.minutes);
        assertEquals(PositionShare.Blur.TENTH_MILE, q.blur);
        assertEquals(20 * MIN, q.next());
        // an expired plan or a damaged line is dropped on load
        b.load(a.save(), 5 * HOUR);
        assertTrue(!b.any());
        b.load(java.util.Arrays.asList("garbage", "N0CALL-7|x|ONE_MILE|0|0|0", "<html>|15|ONE_MILE|0|0|0"), 0);
        assertTrue(!b.any());
    }
}
