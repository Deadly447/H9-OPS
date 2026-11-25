package io.github.deadly447.h9ops.aprs;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BroadcastsTest {
    static final long MIN = 60_000;
    // 2026-11-23 21:05 UTC
    static final long T = 1795467900000L;

    static AprsPacket parse(String info) {
        return AprsParser.parse(Ax25.fromTnc2("N0CALL-7>APZH9M,WIDE1-1", info.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void objectTextAndParsedBack() {
        Broadcasts b = new Broadcasts();
        b.putObject("ICP", 40.033333, -105.25, '/', 'c', "Search base, 146.52", 10, T);
        List<String> due = b.due(T);
        assertEquals(1, due.size());
        assertEquals(";ICP      *232105z4002.00N/10515.00WcSearch base, 146.52", due.get(0));
        AprsPacket p = parse(due.get(0));
        assertEquals(AprsPacket.Type.OBJECT, p.type);
        assertEquals("ICP", p.name);
        assertFalse(p.killed);
        assertEquals(40.033333, p.lat, 1e-4);
        assertEquals(-105.25, p.lon, 1e-4);
        assertEquals('c', p.symCode);
        assertTrue(b.due(T + 9 * MIN).isEmpty());
        assertEquals(1, b.due(T + 10 * MIN).size());                 // resent every 10 minutes
    }

    @Test
    public void removedObjectsAreKilledThreeTimes() {
        Broadcasts b = new Broadcasts();
        b.putObject("Hazard 1", 40.0, -105.0, '/', '.', "", 10, T);
        b.due(T);
        assertTrue(b.removeObject("hazard 1", T + MIN));
        assertTrue(b.objects().isEmpty());                            // gone from the list straight away
        List<String> k = b.due(T + MIN);
        assertEquals(1, k.size());
        assertTrue(k.get(0), k.get(0).startsWith(";Hazard 1 _"));
        assertTrue(parse(k.get(0)).killed);
        assertEquals(1, b.due(T + 2 * MIN).size());
        assertEquals(1, b.due(T + 3 * MIN).size());
        assertTrue(b.due(T + 60 * MIN).isEmpty());                    // and then nothing more
        assertEquals(Long.MAX_VALUE, b.msUntilNext(T + 60 * MIN));
        assertFalse(b.removeObject("nothing", T));
    }

    @Test
    public void positionsRoundCleanly() {
        assertEquals("4000.00N", Broadcasts.lat(39.9999999));            // not 3959.100
        assertEquals("3730.50S", Broadcasts.lat(-37.508333));
        assertEquals("00000.00E", Broadcasts.lon(0));
        assertEquals("10959.99W", Broadcasts.lon(-109.999833));
    }

    @Test
    public void bulletinsRepeatForTheirTime() {
        Broadcasts b = new Broadcasts();
        b.sendBulletin("0", "Net tonight 1900 on 146.52", 1, T);
        List<String> d = b.due(T);
        assertEquals(":BLN0     :Net tonight 1900 on 146.52", d.get(0));
        AprsPacket p = parse(d.get(0));
        assertEquals(AprsPacket.Type.MESSAGE, p.type);
        assertEquals("BLN0", p.msgTo.trim());
        int sends = 1;
        for (long t = T + MIN; t <= T + 120 * MIN; t += MIN) sends += b.due(t).size();
        assertEquals(7, sends);                                       // 0, 10 … 60 minutes
        assertTrue(b.bulletins().isEmpty());
        b.sendBulletin("a", "once only", 0, T);
        assertEquals(":BLNA     :once only", b.due(T).get(0));
        assertTrue(b.bulletins().isEmpty());
        try {
            b.sendBulletin("10", "x", 0, T);
            fail("two-character line");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void namesAndTextAreCleaned() {
        assertEquals("Camp2", Broadcasts.cleanName(" Camp*2 "));
        assertEquals("ABCDEFGHI", Broadcasts.cleanName("ABCDEFGHIJKL"));
        assertEquals("a b", Broadcasts.cleanText("a~|{\nb", 43).replace("  ", " "));
        try {
            new Broadcasts().putObject("  ", 40, -105, '/', '.', "", 10, T);
            fail("empty name");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void savedAndLoaded() {
        Broadcasts b = new Broadcasts();
        b.putObject("ICP", 40.5, -105.5, '\\', 'P', "staging", 30, T);
        b.sendBulletin("1", "Road closed at the bridge", 4, T);
        b.sendBulletin("2", "finished already", 1, T);
        Broadcasts c = new Broadcasts();
        c.load(b.save(), T + 2 * 3_600_000L);                      // two hours later
        assertEquals(1, c.objects().size());
        Broadcasts.Obj o = c.object("icp");
        assertEquals('\\', o.table);
        assertEquals('P', o.code);
        assertEquals(30, o.minutes);
        assertEquals("staging", o.comment);
        assertEquals(1, c.bulletins().size());                       // the 1-hour one has ended
        assertEquals("1", c.bulletins().get(0).line);
        assertNull(c.object("nothing"));
    }

    @Test
    public void bulletinBoard() {
        BulletinBoard bb = new BulletinBoard();
        assertTrue(BulletinBoard.isBulletin("BLN0"));
        assertTrue(BulletinBoard.isBulletin("BLN1WX"));
        assertFalse(BulletinBoard.isBulletin("BLN"));
        assertFalse(BulletinBoard.isBulletin("N0CALL"));
        AprsPacket p = parse(":BLN3     :Water at the trailhead");
        assertTrue(bb.add(p, T));
        assertFalse(bb.add(p, T + MIN));                              // a repeat: not new
        assertEquals(1, bb.entries(T + MIN).size());
        assertEquals(2, bb.entries(T + MIN).get(0).count);
        assertEquals(1, bb.unseen());
        assertTrue(bb.add(parse(":BLN3     :Water gone"), T + 2 * MIN));
        assertEquals("Water gone", bb.entries(T + 2 * MIN).get(0).text);
        bb.addMine("N0CALL-5", "BLN0", "mine", T);
        assertEquals("BLN0", bb.entries(T + 3 * MIN).get(0).to);      // line order
        assertFalse(bb.add(parse(":N0CALL-5 :not a bulletin{1"), T));
        assertTrue(bb.entries(T + BulletinBoard.KEEP_MS + 10 * MIN).isEmpty());
    }
}
