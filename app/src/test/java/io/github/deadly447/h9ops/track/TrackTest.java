package io.github.deadly447.h9ops.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;

public class TrackTest {
    private static final long T0 = 1_795_300_000_000L;   // 2026-11-21
    private static final double LAT = 40.96656, LON = -105.36183;

    /** About d metres north of the trailhead. */
    private static double north(double d) { return LAT + d / 111_195.0; }

    @Test
    public void keepsGoodFixesAndMeasuresDistance() {
        Track t = new Track("x");
        assertNotNull(t.addMe(T0, LAT, LON, 2100, 8));
        assertNull("inaccurate fix dropped", t.addMe(T0 + 5_000, north(20), LON, 2100, 120));
        assertNull("too small a move dropped", t.addMe(T0 + 10_000, north(2), LON, 2100, 8));
        assertNotNull(t.addMe(T0 + 15_000, north(30), LON, 2101, 8));
        assertNotNull(t.addMe(T0 + 25_000, north(60), LON, 2102, 8));
        assertEquals(3, t.me.size());
        assertEquals(60, t.distanceM(), 1.0);
    }

    @Test
    public void standingStillStillLeavesAPointEveryFiveMinutes() {
        Track t = new Track("x");
        t.addMe(T0, LAT, LON, Double.NaN, 5);
        for (int i = 1; i <= 120; i++) t.addMe(T0 + i * 5_000L, LAT, LON, Double.NaN, 5);   // 10 minutes still
        assertEquals(3, t.me.size());
        assertEquals(0, t.distanceM(), 0.01);
    }

    @Test
    public void gpsJumpIsIgnored() {
        Track t = new Track("x");
        t.addMe(T0, LAT, LON, Double.NaN, 5);
        assertNull("2 km in 5 s", t.addMe(T0 + 5_000, north(2000), LON, Double.NaN, 5));
        assertNotNull(t.addMe(T0 + 10_000, north(15), LON, Double.NaN, 5));
    }

    @Test
    public void gapDoesNotCountAsDistanceAndSplitsTheGpx() throws Exception {
        Track t = new Track("x");
        t.start = T0;
        t.addMe(T0, LAT, LON, Double.NaN, 5);
        t.addMe(T0 + 60_000, north(50), LON, Double.NaN, 5);
        t.addMe(T0 + 60 * 60_000, north(3000), LON, Double.NaN, 5);   // an hour later, 3 km on
        assertEquals(50, t.distanceM(), 1.0);
        Document d = gpx(t, "N0CALL-7");
        assertEquals(2, d.getElementsByTagName("trkseg").getLength());
    }

    @Test
    public void digipeatedCopiesOfAStationCountOnce() {
        Track t = new Track("x");
        assertNotNull(t.addSeen(T0, "RIDGE", 40.84, -105.46, false));
        assertNull("same place 1 s later (via a digi)", t.addSeen(T0 + 1_000, "RIDGE", 40.84, -105.46, false));
        assertNotNull("10 minutes later is a new report", t.addSeen(T0 + 600_000, "RIDGE", 40.84, -105.46, false));
        assertNotNull(t.addSeen(T0 + 1_000, "TEST3", 40.90, -105.40, false));
        assertEquals(2, t.stations());
    }

    @Test
    public void gpxHasPhoneTrackStationTracksAndWaypoints() throws Exception {
        Track t = new Track("x");
        t.start = T0;
        t.name = "Lakeside <loop> & back";
        t.addMe(T0, LAT, LON, 2100.5, 5);
        t.addMe(T0 + 30_000, north(40), LON, 2102, 5);
        t.addSeen(T0 + 10_000, "TEST3", 40.90, -105.40, false);
        t.addSeen(T0 + 70_000, "TEST3", 40.901, -105.401, false);   // moving: a track
        t.addSeen(T0 + 20_000, "RIDGE", 40.84, -105.46, false);      // once: a waypoint
        t.addSeen(T0 + 40_000, "N0TK", 40.97, -105.37, true);         // rounded: a waypoint
        Document d = gpx(t, "N0CALL-7");
        assertEquals(2, d.getElementsByTagName("trk").getLength());
        assertEquals(2, d.getElementsByTagName("wpt").getLength());
        assertEquals(4, d.getElementsByTagName("trkpt").getLength());
        String all = gpxText(t, "N0CALL-7");
        assertTrue(all.contains("<name>N0CALL-7 (this phone)</name>"));
        assertTrue(all.contains("&lt;loop&gt; &amp; back"));
        assertTrue(all.contains("<ele>2100.5</ele>"));
        assertTrue(all.contains("rounded"));
        assertTrue("GPX 1.1 wants waypoints before tracks", all.indexOf("<wpt") < all.indexOf("<trk>"));
    }

    @Test
    public void linesRoundTrip() {
        Track t = new Track("x");
        Track.Point p = t.addMe(T0, LAT, LON, 2100.25, 7);
        Track.Seen s = t.addSeen(T0 + 1, "TEST3", 40.9, -105.4, true);
        Track back = Track.parse("x", List.of(Track.begin(T0, "Hike"), Track.line(p), Track.line(s), Track.end(T0 + 99),
                "M\t123\tgarbage"));
        assertEquals("Hike", back.name);
        assertEquals(T0 + 99, back.end);
        assertEquals(1, back.me.size());
        assertEquals(LAT, back.me.get(0).lat, 1e-6);
        assertEquals(1, back.seen.size());
        assertTrue(back.seen.get(0).approx);
    }

    @Test
    public void recordingSurvivesTheAppBeingKilled() throws Exception {
        File dir = Files.createTempDirectory("tracks").toFile();
        Tracks a = new Tracks(dir, Runnable::run);
        assertFalse(a.recording());
        a.start(T0, "Hike");
        assertTrue(a.me(T0, LAT, LON, Double.NaN, 5));
        a.seen(T0 + 1_000, "TEST3", 40.9, -105.4, false);
        assertTrue(a.list().isEmpty());

        Tracks b = new Tracks(dir, Runnable::run);   // app restarted mid-hike
        assertTrue(b.recording());
        assertEquals(1, b.current().me.size());
        assertTrue(b.me(T0 + 60_000, north(100), LON, Double.NaN, 5));
        Track done = b.stop(T0 + 120_000);
        assertEquals(100, done.distanceM(), 1.0);

        List<Track> l = new Tracks(dir, Runnable::run).list();
        assertEquals(1, l.size());
        assertEquals("Hike", l.get(0).name);
        assertEquals(2, l.get(0).me.size());
        assertEquals(1, l.get(0).stations());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] err = {"not called"};
        b.exportGpx(l.get(0).id, out, "N0CALL-7", e -> err[0] = e);
        assertNull(err[0]);
        assertTrue(out.toString(StandardCharsets.UTF_8.name()).contains("<trkpt"));
        assertTrue(b.delete(l.get(0).id));
        assertTrue(b.list().isEmpty());
    }

    @Test
    public void nothingIsRecordedWhenNotRecording() throws Exception {
        File dir = Files.createTempDirectory("tracks").toFile();
        Tracks a = new Tracks(dir, Runnable::run);
        assertFalse(a.me(T0, LAT, LON, Double.NaN, 5));
        a.seen(T0, "TEST3", 40.9, -105.4, false);
        assertNull(a.stop(T0));
        String[] files = dir.list();
        assertEquals(0, files == null ? 0 : files.length);
    }

    private static String gpxText(Track t, String me) throws Exception {
        StringWriter w = new StringWriter();
        t.writeGpx(w, me);
        return w.toString();
    }

    private static Document gpx(Track t, String me) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(gpxText(t, me).getBytes(StandardCharsets.UTF_8)));
    }
}
