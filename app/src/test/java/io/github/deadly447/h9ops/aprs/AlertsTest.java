package io.github.deadly447.h9ops.aprs;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AlertsTest {
    static AprsPacket tnc2(String line) {
        int c = line.indexOf(':');
        return AprsParser.parse(Ax25.fromTnc2(line.substring(0, c), line.substring(c + 1).getBytes(StandardCharsets.ISO_8859_1)));
    }

    static final long MIN = 60_000;

    @Test
    public void patterns() {
        assertTrue(Alerts.matches("N0CALL-7", "n0call-7"));
        assertFalse(Alerts.matches("N0CALL-7", "N0CALL-9"));
        assertTrue(Alerts.matches("N0CALL-*", "N0CALL"));
        assertTrue(Alerts.matches("N0CALL-*", "N0CALL-12"));
        assertFalse(Alerts.matches("N0CALL-*", "N0CALLX-1"));
        assertTrue(Alerts.matches("N0*", "N0XYZ-3"));
        assertFalse(Alerts.matches("", "N0CALL"));
        assertEquals(Arrays.asList("N0CALL-7", "N0CALL-*", "RIDGE"), Alerts.parseCalls(" n0call-7, N0CALL-*;ridge\nRIDGE !!bad!! "));
    }

    @Test
    public void heardAgainOnlyAfterAQuietSpell() {
        Alerts a = new Alerts();
        a.setWatch(Collections.singletonList("N0CALL-7"));
        AprsPacket p = tnc2("N0CALL-7>APRS,RIDGE*:>on the air");
        List<Alerts.Alert> l = a.check(p, 0, 100 * MIN);                       // never heard before
        assertEquals(1, l.size());
        assertEquals(Alerts.Kind.HEARD, l.get(0).kind);
        assertEquals("via RIDGE", l.get(0).detail);
        assertEquals("N0CALL-7 heard", l.get(0).title());
        assertTrue(a.check(p, 100 * MIN, 101 * MIN).isEmpty());              // a minute later: no
        assertEquals(1, a.check(p, 101 * MIN, 140 * MIN).size());           // after 39 quiet minutes: yes
        assertTrue(a.check(tnc2("N0CALL-8>APRS:>other"), 0, 200 * MIN).isEmpty());
    }

    @Test
    public void areaEnterAndLeaveWithAMargin() {
        Alerts a = new Alerts();
        // 1 km circle around 40.0 N 105.0 W, for any station
        a.setAreas(Collections.singletonList(new Alerts.Area("Camp", 40.0, -105.0, 1000, null)));
        AprsPacket far = tnc2("N0CALL-9>APRS:!4002.00N/10500.00W>");                  // 3.7 km north
        assertTrue(a.check(far, 0, MIN).isEmpty());
        AprsPacket in = tnc2("N0CALL-9>APRS:!4000.20N/10500.00W>");                   // 370 m
        List<Alerts.Alert> l = a.check(in, 0, 2 * MIN);
        assertEquals(1, l.size());
        assertEquals(Alerts.Kind.ENTERED, l.get(0).kind);
        assertEquals("N0CALL-9 entered Camp", l.get(0).title());
        assertTrue(a.check(in, 0, 3 * MIN).isEmpty());                                 // still inside
        AprsPacket edge = tnc2("N0CALL-9>APRS:!4000.57N/10500.00W>");                 // 1056 m: inside the margin
        assertTrue(a.check(edge, 0, 4 * MIN).isEmpty());
        l = a.check(far, 0, 5 * MIN);
        assertEquals(1, l.size());
        assertEquals(Alerts.Kind.LEFT, l.get(0).kind);
    }

    @Test
    public void areaForListedStationsOnlyAndPrimed() {
        Alerts a = new Alerts();
        a.setAreas(Collections.singletonList(new Alerts.Area("Base", 40.0, -105.0, 1000, Alerts.parseCalls("N0CALL-*"))));
        AprsPacket other = tnc2("W1ABCD>APRS:!4000.10N/10500.00W-");
        assertTrue(a.check(other, 0, MIN).isEmpty());                                  // not on the list
        // already there at start-up: no alert for it, only for leaving
        StationStore store = new StationStore();
        store.add(0, tnc2("N0CALL-5>APRS:!4000.10N/10500.00W-"));
        a.prime(store.stations());
        assertTrue(a.check(tnc2("N0CALL-5>APRS:!4000.10N/10500.00W-"), 0, 2 * MIN).isEmpty());
        assertEquals(Alerts.Kind.LEFT, a.check(tnc2("N0CALL-5>APRS:!4003.00N/10500.00W-"), 0, 3 * MIN).get(0).kind);
    }

    @Test
    public void emergencies() {
        Alerts a = new Alerts();
        AprsPacket c = tnc2("N0CALL-7>APRS:!4000.00N/10500.00W[EMERGENCY fell, ankle");
        List<Alerts.Alert> l = a.check(c, 0, MIN);
        assertEquals(1, l.size());
        assertEquals(Alerts.Kind.EMERGENCY, l.get(0).kind);
        assertEquals("EMERGENCY · N0CALL-7", l.get(0).title());
        assertTrue(a.check(c, MIN, 2 * MIN).isEmpty());                       // repeats every beacon: once per 15 min
        assertEquals(1, a.check(c, 2 * MIN, 20 * MIN).size());
        assertNull(Alerts.emergencyText(tnc2("N0CALL-7>APRS:>no emergencyish words here")));
        assertNotNull(Alerts.emergencyText(tnc2("N0CALL>APRS:;EMERG-1  *111111z4000.00N/10500.00W!help")));
        a.setEmergency(false);
        assertTrue(a.check(c, 0, 100 * MIN).isEmpty());
    }

    @Test
    public void micEEmergency() {
        AprsPacket p = new AprsPacket();
        p.miceStatus = "Emergency";
        p.comment = " need help ";
        assertEquals("Mic-E emergency: need help", Alerts.emergencyText(p));
        p.miceStatus = "En Route";
        p.comment = null;
        assertNull(Alerts.emergencyText(p));
    }

    @Test
    public void areasSaveAndLoad() {
        Alerts.Area a = new Alerts.Area("Search\tarea 2", 40.123456, -105.5, 804.672, Alerts.parseCalls("N0CALL-*, W1ABCD"));
        Alerts.Area b = Alerts.Area.load(a.save());
        assertEquals("Search area 2", b.name);
        assertEquals(40.123456, b.lat, 1e-6);
        assertEquals(805, b.radiusM, 0.5);
        assertEquals(Arrays.asList("N0CALL-*", "W1ABCD"), b.calls);
        assertNull(Alerts.Area.load("broken"));
        assertNull(Alerts.Area.load("x\tNaN\t1\t5"));
    }
}
