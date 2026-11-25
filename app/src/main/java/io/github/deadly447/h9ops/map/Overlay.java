package io.github.deadly447.h9ops.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A trail plan or other drawing to show on the map: lines, areas and named points from a
 * GPX, KML or KMZ file (CalTopo, Gaia, onX, Google Earth, ATAK exports). Read once when
 * the file is added; the app keeps the result, not the file.
 */
public final class Overlay {
    /** Files bigger than this are refused (a trail plan is kilobytes). */
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    /** More points than this are thinned evenly, so the map stays quick. */
    public static final int MAX_POINTS = 50_000;

    public static final class Point {
        public final String name;
        public final double lat, lon;

        Point(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public String name;
    public final List<List<double[]>> lines = new ArrayList<>();
    public final List<List<double[]>> areas = new ArrayList<>();
    public final List<Point> points = new ArrayList<>();

    /** "2 lines, 1 area, 14 points". */
    public String summary() {
        List<String> parts = new ArrayList<>();
        if (!lines.isEmpty()) parts.add(lines.size() + (lines.size() == 1 ? " line" : " lines"));
        if (!areas.isEmpty()) parts.add(areas.size() + (areas.size() == 1 ? " area" : " areas"));
        if (!points.isEmpty()) parts.add(points.size() + (points.size() == 1 ? " point" : " points"));
        return parts.isEmpty() ? "nothing to draw" : String.join(", ", parts);
    }

    public boolean isEmpty() { return lines.isEmpty() && areas.isEmpty() && points.isEmpty(); }

    /** {"n":name,"l":[[[lat,lon],…]],"a":[…],"p":[{"n","la","lo"}]} for the map page. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"n\":").append(MapJson.q(name));
        sb.append(",\"l\":");
        shapes(sb, lines);
        sb.append(",\"a\":");
        shapes(sb, areas);
        sb.append(",\"p\":[");
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"n\":").append(MapJson.q(p.name)).append(",\"la\":").append(MapJson.num(p.lat))
                    .append(",\"lo\":").append(MapJson.num(p.lon)).append('}');
        }
        return sb.append("]}").toString();
    }

    private static void shapes(StringBuilder sb, List<List<double[]>> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            List<double[]> s = list.get(i);
            for (int j = 0; j < s.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('[').append(String.format(Locale.US, "%.6f,%.6f", s.get(j)[0], s.get(j)[1])).append(']');
            }
            sb.append(']');
        }
        sb.append(']');
    }

    // ---------------------------------------------------------------- reading

    /** Reads a GPX, KML or KMZ file. The message of the exception says what's wrong, for the user. */
    public static Overlay parse(byte[] data, String fileName) throws IOException {
        if (data.length > MAX_BYTES) throw new IOException("The file is too big for a map overlay (over 20 MB)");
        String fn = fileName == null ? "" : fileName;
        if (data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4) data = kmlFromKmz(data);
        String head = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.UTF_8);
        // No DTDs at all: they're never needed here and are how XML files attack readers.
        if (new String(data, StandardCharsets.ISO_8859_1).contains("<!DOCTYPE")) {
            throw new IOException("The file has a DOCTYPE, which overlays don't allow");
        }
        Overlay o = new Overlay();
        Handler h = new Handler(o);
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { }
            try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
            SAXParser p = f.newSAXParser();
            p.parse(new InputSource(new ByteArrayInputStream(data)), h);
        } catch (SAXException | javax.xml.parsers.ParserConfigurationException e) {
            throw new IOException(head.contains("<gpx") || head.contains("<kml")
                    ? "The file is damaged (" + e.getMessage() + ")" : "This isn't a GPX or KML file");
        }
        if (!h.sawRoot) throw new IOException("This isn't a GPX or KML file");
        if (o.isEmpty()) throw new IOException("The file has no lines, areas or points");
        if (o.name == null || o.name.trim().isEmpty()) {
            o.name = fn.replaceAll("(?i)\\.(gpx|kml|kmz)$", "");
            if (o.name.isEmpty()) o.name = "Overlay";
        }
        o.name = o.name.trim();
        if (o.name.length() > 60) o.name = o.name.substring(0, 60);
        o.thin();
        return o;
    }

    private static byte[] kmlFromKmz(byte[] zip) throws IOException {
        byte[] best = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.US);
                if (e.isDirectory() || !n.endsWith(".kml")) continue;
                byte[] b = readLimited(z);
                if (best == null || n.equals("doc.kml")) best = b;
                if (n.equals("doc.kml")) break;
            }
        }
        if (best == null) throw new IOException("The KMZ file has no KML inside");
        return best;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 15];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("The file is too big for a map overlay (over 20 MB)");
        }
        return out.toByteArray();
    }

    /** Keeps at most MAX_POINTS in lines and areas, every k-th point, ends kept. */
    void thin() {
        int total = 0;
        for (List<double[]> l : lines) total += l.size();
        for (List<double[]> l : areas) total += l.size();
        if (total <= MAX_POINTS) return;
        int k = (total + MAX_POINTS - 1) / MAX_POINTS;
        for (List<List<double[]>> group : java.util.Arrays.asList(lines, areas)) {
            for (int i = 0; i < group.size(); i++) {
                List<double[]> l = group.get(i), t = new ArrayList<>();
                for (int j = 0; j < l.size(); j++) if (j % k == 0 || j == l.size() - 1) t.add(l.get(j));
                group.set(i, t);
            }
        }
    }

    static boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static double num(String s) {
        if (s == null) return Double.NaN;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /** One SAX pass for both formats; element names without their prefix ("gx:coord" → "coord"). */
    private static final class Handler extends DefaultHandler {
        private final Overlay o;
        private final Deque<String> path = new ArrayDeque<>();
        private final StringBuilder text = new StringBuilder();
        boolean sawRoot;
        private List<double[]> line;        // GPX segment/route, or a KML gx:Track
        private double wLat = Double.NaN, wLon = Double.NaN;
        private String wName, pmName;
        private final List<double[]> pmPoints = new ArrayList<>();
        private boolean kml;

        Handler(Overlay o) { this.o = o; }

        private static String local(String q) {
            int i = q.indexOf(':');
            return (i >= 0 ? q.substring(i + 1) : q).toLowerCase(Locale.US);
        }

        private String parent() {
            java.util.Iterator<String> it = path.iterator();
            if (!it.hasNext()) return "";
            it.next();
            return it.hasNext() ? it.next() : "";
        }

        @Override
        public void startElement(String uri, String ln, String qName, Attributes a) {
            String n = local(qName);
            path.push(n);
            text.setLength(0);
            if (path.size() == 1) {
                sawRoot = n.equals("gpx") || n.equals("kml");
                kml = n.equals("kml");
            }
            switch (n) {
                case "trkseg": case "rte": line = new ArrayList<>(); break;
                case "track": if (kml) line = new ArrayList<>(); break;
                case "trkpt": case "rtept":
                    if (line != null) {
                        double la = num(a.getValue("lat")), lo = num(a.getValue("lon"));
                        if (valid(la, lo)) line.add(new double[]{la, lo});
                    }
                    break;
                case "wpt":
                    wLat = num(a.getValue("lat"));
                    wLon = num(a.getValue("lon"));
                    wName = null;
                    break;
                case "placemark": pmName = null; break;
                default: break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (text.length() < 1_000_000) text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String ln, String qName) {
            String n = path.isEmpty() ? "" : path.peek();
            String p = parent();
            String t = text.toString().trim();
            switch (n) {
                case "name":
                    if (p.equals("wpt")) wName = t;
                    else if (p.equals("placemark")) pmName = t;
                    else if (o.name == null && (p.equals("metadata") || p.equals("document") || p.equals("trk")
                            || p.equals("rte") || p.equals("gpx") || p.equals("folder"))) o.name = t;
                    break;
                case "trkseg": case "rte": finishLine(); break;
                case "track": if (kml) finishLine(); break;
                case "wpt":
                    if (valid(wLat, wLon)) o.points.add(new Point(wName, wLat, wLon));
                    break;
                case "coord":   // gx:Track: "lon lat alt"
                    if (line != null) {
                        String[] f = t.split("\\s+");
                        if (f.length >= 2) {
                            double lo = num(f[0]), la = num(f[1]);
                            if (valid(la, lo)) line.add(new double[]{la, lo});
                        }
                    }
                    break;
                case "coordinates":
                    coordinates(t, p);
                    break;
                default: break;
            }
            path.pop();
            text.setLength(0);
        }

        private void finishLine() {
            if (line != null && line.size() >= 2) o.lines.add(line);
            else if (line != null && line.size() == 1) o.points.add(new Point(null, line.get(0)[0], line.get(0)[1]));
            line = null;
        }

        /** KML "lon,lat[,alt] lon,lat[,alt] …" inside a Point, LineString or LinearRing. */
        private void coordinates(String t, String parent) {
            pmPoints.clear();
            for (String tuple : t.split("\\s+")) {
                String[] f = tuple.split(",");
                if (f.length < 2) continue;
                double lo = num(f[0]), la = num(f[1]);
                if (valid(la, lo)) pmPoints.add(new double[]{la, lo});
            }
            if (pmPoints.isEmpty()) return;
            switch (parent) {
                case "point": o.points.add(new Point(pmName, pmPoints.get(0)[0], pmPoints.get(0)[1])); break;
                case "linestring": if (pmPoints.size() >= 2) o.lines.add(new ArrayList<>(pmPoints)); break;
                case "linearring": if (pmPoints.size() >= 3) o.areas.add(new ArrayList<>(pmPoints)); break;
                default: break;
            }
        }
    }
}
