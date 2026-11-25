package io.github.deadly447.h9ops.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class OverlayTest {
    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static final String GPX = "<?xml version=\"1.0\"?><gpx version=\"1.1\" creator=\"CalTopo\" xmlns=\"http://www.topografix.com/GPX/1/1\">"
            + "<metadata><name>Ridge loop</name></metadata>"
            + "<wpt lat=\"40.85\" lon=\"-105.45\"><name>Trailhead</name></wpt>"
            + "<trk><name>Loop</name><trkseg><trkpt lat=\"40.85\" lon=\"-105.45\"/><trkpt lat=\"40.86\" lon=\"-105.46\"/>"
            + "<trkpt lat=\"99\" lon=\"0\"/><trkpt lat=\"40.87\" lon=\"-105.44\"/></trkseg></trk>"
            + "<rte><rtept lat=\"40.8\" lon=\"-105.4\"/><rtept lat=\"40.81\" lon=\"-105.41\"/></rte></gpx>";

    static final String KML = "<?xml version=\"1.0\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">"
            + "<Document><name>Search area 4</name>"
            + "<Placemark><name>ICP</name><Point><coordinates>-105.34,40.87,0</coordinates></Point></Placemark>"
            + "<Placemark><name>Road</name><LineString><coordinates>-105.3,40.8 -105.31,40.81\n -105.32,40.82</coordinates></LineString></Placemark>"
            + "<Placemark><name>Seg A</name><Polygon><outerBoundaryIs><LinearRing><coordinates>"
            + "-105.4,40.9 -105.39,40.9 -105.39,40.91 -105.4,40.9</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>"
            + "<Placemark><gx:Track><when>2026-11-23T10:00:00Z</when><gx:coord>-105.5 40.7 2100</gx:coord>"
            + "<gx:coord>-105.51 40.71 2110</gx:coord></gx:Track></Placemark>"
            + "</Document></kml>";

    @Test
    public void gpx() throws IOException {
        Overlay o = Overlay.parse(b(GPX), "loop.gpx");
        assertEquals("Ridge loop", o.name);
        assertEquals(2, o.lines.size());
        assertEquals(3, o.lines.get(0).size());          // the point at latitude 99 is dropped
        assertEquals(1, o.points.size());
        assertEquals("Trailhead", o.points.get(0).name);
        assertEquals("2 lines, 1 point", o.summary());
        assertTrue(o.toJson().startsWith("{\"n\":\"Ridge loop\",\"l\":[[[40.850000,-105.450000],"));
    }

    @Test
    public void kmlAndKmz() throws IOException {
        Overlay o = Overlay.parse(b(KML), "area4.kml");
        assertEquals("Search area 4", o.name);
        assertEquals(2, o.lines.size());                  // LineString and gx:Track
        assertEquals(1, o.areas.size());
        assertEquals("ICP", o.points.get(0).name);
        assertEquals(40.87, o.points.get(0).lat, 1e-9);
        assertEquals(-105.34, o.points.get(0).lon, 1e-9);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("files/icon.png"));
            z.write(new byte[]{1, 2, 3});
            z.putNextEntry(new ZipEntry("doc.kml"));
            z.write(b(KML));
        }
        assertEquals(o.toJson(), Overlay.parse(bo.toByteArray(), "area4.kmz").toJson());
    }

    @Test
    public void refusals() {
        String[][] bad = {
            {"<!DOCTYPE gpx [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><gpx><wpt lat=\"1\" lon=\"1\"><name>&x;</name></wpt></gpx>", "DOCTYPE"},
            {"hello", "isn't a GPX"},
            {"<html><body/></html>", "isn't a GPX"},
            {"<gpx></gpx>", "no lines"},
        };
        for (String[] c : bad) {
            try {
                Overlay.parse(b(c[0]), "x.gpx");
                fail(c[0]);
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(c[1]));
            }
        }
    }

    @Test
    public void nameFromFileAndEscaping() throws IOException {
        Overlay o = Overlay.parse(b("<gpx><wpt lat=\"1\" lon=\"2\"><name>a\"&lt;/script&gt;</name></wpt></gpx>"), "My plan.GPX");
        assertEquals("My plan", o.name);
        assertTrue(o.toJson().contains("a\\\"\\u003c/script>"));
    }

    @Test
    public void thinsHugeTracks() throws IOException {
        StringBuilder sb = new StringBuilder("<gpx><trk><trkseg>");
        for (int i = 0; i < 120_000; i++) sb.append("<trkpt lat=\"40.").append(i).append("\" lon=\"-105\"/>");
        sb.append("</trkseg></trk></gpx>");
        Overlay o = Overlay.parse(b(sb.toString()), "big.gpx");
        assertTrue(o.lines.get(0).size() <= Overlay.MAX_POINTS + 1);
        assertTrue(o.lines.get(0).size() > Overlay.MAX_POINTS / 2);
    }

    @Test
    public void storeAddHideRemove() throws IOException {
        java.io.File d = java.nio.file.Files.createTempDirectory("ov").toFile();
        Overlays s = new Overlays(d);
        Overlays.Entry a = s.add(Overlay.parse(b(GPX), "loop.gpx"));
        Overlays.Entry k = s.add(Overlay.parse(b(KML), "area4.kml"));
        assertEquals(2, new Overlays(d).list().size());           // survives a restart
        assertTrue(s.json().startsWith("[{\"id\":\"" + a.id + "\",\"n\":\"Ridge loop\""));
        s.setVisible(a.id, false);
        assertTrue(new Overlays(d).json().startsWith("[{\"id\":\"" + k.id + "\""));
        assertEquals(false, new Overlays(d).list().get(0).visible);
        s.remove(k.id);
        assertEquals("[]", s.json());
        assertEquals(1, new Overlays(d).list().size());
    }
}
