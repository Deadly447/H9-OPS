package io.github.deadly447.h9ops.map;

import io.github.deadly447.h9ops.aprs.AprsPacket;
import io.github.deadly447.h9ops.aprs.AprsParser;
import io.github.deadly447.h9ops.aprs.Ax25;
import io.github.deadly447.h9ops.aprs.PositionShare;
import io.github.deadly447.h9ops.aprs.Station;
import io.github.deadly447.h9ops.aprs.StationStore;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MapJsonTest {
    static AprsPacket tnc2(String line) {
        int c = line.indexOf(':');
        return AprsParser.parse(Ax25.fromTnc2(line.substring(0, c),
                line.substring(c + 1).getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void stringsAreEscaped() {
        assertEquals("\"plain\"", MapJson.q("plain"));
        assertEquals("\"a\\\"b\\\\c\"", MapJson.q("a\"b\\c"));
        assertEquals("\"\\u003c/script>\"", MapJson.q("</script>"));
        assertEquals("\"x\\ny\\u0001\\u2028\"", MapJson.q("x\ny\u0001\u2028"));
        assertEquals("null", MapJson.q(null));
    }

    @Test
    public void stationsWithPositionsOnly() {
        StationStore store = new StationStore();
        store.add(1000, tnc2("N0CALL-7>APRS,RIDGE*,WIDE2-1:!4002.00N/10515.00W[Hiking \"up\" </b>"));
        store.add(2000, tnc2("N0CALL-8>APRS:>status only, no position"));
        String json = MapJson.stations(store.stations());
        assertTrue(json, json.startsWith("[{\"n\":\"N0CALL-7\""));
        assertTrue(json, json.contains("\"la\":40.033333"));
        assertTrue(json, json.contains("\"lo\":-105.250000"));
        assertTrue(json, json.contains("\"k\":\"b\""));
        assertTrue(json, json.contains("\"t\":1000"));
        assertTrue(json, json.contains("\"v\":\"RIDGE\""));
        assertTrue(json, json.contains("Hiking \\\"up\\\" \\u003c/b>"));
        assertFalse(json, json.contains("N0CALL-8"));
        assertFalse(json, json.contains("\"tr\""));          // it hasn't moved
        assertFalse(json, json.contains("\"r\""));           // exact
    }

    @Test
    public void movingStationHasATrailOldestFirst() {
        StationStore store = new StationStore();
        store.add(1000, tnc2("N0CALL-9>APRS:!4000.00N/10500.00W>"));
        store.add(2000, tnc2("N0CALL-9>APRS:!4000.00N/10500.00W>"));   // same spot again: one point
        store.add(3000, tnc2("N0CALL-9>APRS:>status between"));
        store.add(4000, tnc2("N0CALL-9>APRS:!4001.00N/10500.00W>"));
        store.add(5000, tnc2("N0CALL-9>APRS:!4002.00N/10500.00W>"));
        Station s = store.get("N0CALL-9");
        List<double[]> tr = MapJson.trail(s);
        assertEquals(3, tr.size());
        assertEquals(40.0, tr.get(0)[0], 1e-6);
        assertEquals(40 + 2 / 60.0, tr.get(2)[0], 1e-6);       // ends at the marker
        assertTrue(MapJson.stations(store.stations()).contains("\"tr\":[[40.000000,-105.000000],"));
    }

    @Test
    public void objectsAndAmbiguity() {
        StationStore store = new StationStore();
        store.add(1000, tnc2("N0CALL>APRS:;147.060+ *111111z4000.00N/10500.00WrT88.5"));
        store.add(2000, tnc2("N0CALL-3>APRS:=4000.  N/10500.  W-"));   // 2 digits blanked: a 1' cell
        String json = MapJson.stations(store.stations());
        assertTrue(json, json.contains("\"n\":\"147.060+\""));
        assertTrue(json, json.contains("\"k\":\"o\""));
        AprsPacket amb = store.get("N0CALL-3").lastPosition;
        assertEquals(2, amb.ambiguity);
        double r = MapJson.radius(amb);
        assertTrue("radius " + r, r > 1000 && r < 1400);          // half the diagonal of 1' x 1' at 40 N
    }

    @Test
    public void sharedPositionsAndTheirArea() {
        StationStore store = new StationStore();
        PositionShare.Pos exact = PositionShare.decode("POS 40.04103,-105.26120 @0515z");
        store.addShared(1000, PositionShare.toPacket("N0CALL-5", exact, "raw"));
        PositionShare.Pos round = PositionShare.decode("POS 40.0417,-105.2583 ~1mi @0515z");
        store.addShared(2000, PositionShare.toPacket("N0CALL-6", round, "raw"));
        Station a = store.get("N0CALL-5"), b = store.get("N0CALL-6");
        assertEquals("s", MapJson.kind(a));
        assertEquals("s", MapJson.kind(b));
        assertEquals(0, MapJson.radius(a.lastPosition), 0);
        double r = MapJson.radius(b.lastPosition);
        assertTrue("radius " + r, r > 1000 && r < 1400);
        assertTrue(MapJson.stations(store.stations()).contains("\"r\":" + Math.round(r)));
    }
}
