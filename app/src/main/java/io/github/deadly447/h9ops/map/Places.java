package io.github.deadly447.h9ops.map;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Town names for a position, read from the offline map file itself (the "places" layer
 * of a Protomaps vector map), so a weather station can say "Springfield" with no internet and no
 * look-up service. Only the point features of that one layer are decoded.
 */
public final class Places {
    /** A place in a map tile. */
    static final class Place {
        final String name, kind;
        final double lat, lon;
        final long population;

        Place(String name, String kind, double lat, double lon, long population) {
            this.name = name;
            this.kind = kind;
            this.lat = lat;
            this.lon = lon;
            this.population = population;
        }
    }

    /** Further than this from every town: no name (a station on a peak far from anywhere). */
    static final double MAX_KM = 40;

    private final MapFile map;
    private final Map<String, String> cache = new HashMap<>();

    public Places(MapFile map) { this.map = map; }

    /** "Springfield", or null when the map has no town near enough. */
    public synchronized String near(double lat, double lon) {
        String key = String.format(Locale.US, "%.2f,%.2f", lat, lon);
        if (cache.containsKey(key)) return cache.get(key);
        String name = null;
        try {
            name = find(lat, lon);
        } catch (IOException | RuntimeException ignored) {
            // a damaged tile: no name
        }
        cache.put(key, name);
        return name;
    }

    private String find(double lat, double lon) throws IOException {
        int top = Math.min(map.header.maxZoom, 12);
        for (int z = top; z >= Math.max(map.header.minZoom, top - 4); z -= 2) {
            int n = 1 << z;
            int tx = (int) Math.floor((lon + 180) / 360 * n);
            double r = Math.toRadians(lat);
            int ty = (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n);
            Place best = null;
            double bestScore = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = Math.floorMod(tx + dx, n), y = ty + dy;
                    if (y < 0 || y >= n) continue;
                    byte[] t = map.tile(z, x, y);
                    if (t == null) continue;
                    for (Place p : decode(t, z, x, y)) {
                        if (!p.kind.equals("locality")) continue;
                        double km = km(lat, lon, p.lat, p.lon);
                        if (km > MAX_KM) continue;
                        // nearest wins; a real town beats a hamlet a little closer
                        double score = km / (1 + Math.log10(1 + p.population) / 3);
                        if (score < bestScore) { bestScore = score; best = p; }
                    }
                }
            }
            if (best != null) return best.name;
        }
        return null;
    }

    static double km(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    // ---------------------------------------------------------------- the vector tile (protobuf)

    /** The point features of the "places" layer in one tile. */
    static List<Place> decode(byte[] tile, int z, int tx, int ty) {
        List<Place> out = new ArrayList<>();
        Pb t = new Pb(tile, 0, tile.length);
        while (t.more()) {
            int key = (int) t.varint();
            if (key >>> 3 == 3 && (key & 7) == 2) {
                Pb layer = t.sub();
                layer(layer, z, tx, ty, out);
            } else t.skip(key & 7);
        }
        return out;
    }

    private static void layer(Pb l, int z, int tx, int ty, List<Place> out) {
        String name = null;
        long extent = 4096;
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<Pb> features = new ArrayList<>();
        while (l.more()) {
            int key = (int) l.varint();
            int f = key >>> 3, w = key & 7;
            if (f == 1 && w == 2) name = l.string();
            else if (f == 2 && w == 2) features.add(l.sub());
            else if (f == 3 && w == 2) keys.add(l.string());
            else if (f == 4 && w == 2) values.add(value(l.sub()));
            else if (f == 5 && w == 0) extent = l.varint();
            else l.skip(w);
        }
        if (!"places".equals(name) || extent <= 0) return;
        double n = 1 << z;
        for (Pb ft : features) {
            long type = 0;
            Pb tags = null, geom = null;
            while (ft.more()) {
                int key = (int) ft.varint();
                int f = key >>> 3, w = key & 7;
                if (f == 2 && w == 2) tags = ft.sub();
                else if (f == 3 && w == 0) type = ft.varint();
                else if (f == 4 && w == 2) geom = ft.sub();
                else ft.skip(w);
            }
            if (type != 1 || geom == null || tags == null) continue;
            String pName = null, kind = "";
            long pop = 0;
            while (tags.more()) {
                int k = (int) tags.varint();
                if (!tags.more()) break;
                int v = (int) tags.varint();
                if (k >= keys.size() || v >= values.size()) continue;
                String kn = keys.get(k);
                Object val = values.get(v);
                if (kn.equals("name") && val instanceof String) pName = (String) val;
                else if (kn.equals("pmap:kind") || kn.equals("kind")) kind = String.valueOf(val);
                else if (kn.equals("population") && val instanceof Number) pop = ((Number) val).longValue();
            }
            if (pName == null || pName.isEmpty()) continue;
            long cmd = geom.varint();
            if ((cmd & 7) != 1 || (cmd >>> 3) < 1) continue;          // MoveTo, at least one point
            long px = zigzag(geom.varint()), py = zigzag(geom.varint());
            double fx = (tx + px / (double) extent) / n, fy = (ty + py / (double) extent) / n;
            double lon = fx * 360 - 180;
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * fy))));
            out.add(new Place(pName, kind, lat, lon, pop));
        }
    }

    private static Object value(Pb v) {
        Object out = null;
        while (v.more()) {
            int key = (int) v.varint();
            int f = key >>> 3, w = key & 7;
            if (f == 1 && w == 2) out = v.string();
            else if (f == 2 && w == 5) out = (double) Float.intBitsToFloat((int) v.fixed(4));
            else if (f == 3 && w == 1) out = Double.longBitsToDouble(v.fixed(8));
            else if ((f == 4 || f == 5) && w == 0) out = v.varint();
            else if (f == 6 && w == 0) out = zigzag(v.varint());
            else if (f == 7 && w == 0) out = v.varint() != 0;
            else v.skip(w);
        }
        return out;
    }

    static long zigzag(long n) { return (n >>> 1) ^ -(n & 1); }

    /** A slice of protobuf bytes. */
    static final class Pb {
        private final byte[] b;
        private int p;
        private final int end;

        Pb(byte[] b, int start, int end) {
            this.b = b;
            this.p = start;
            this.end = end;
        }

        boolean more() { return p < end; }

        long varint() {
            long r = 0;
            for (int s = 0; s < 64; s += 7) {
                if (p >= end) throw new IllegalArgumentException("truncated");
                int c = b[p++] & 0xFF;
                r |= (long) (c & 0x7F) << s;
                if (c < 0x80) return r;
            }
            throw new IllegalArgumentException("bad varint");
        }

        long fixed(int n) {
            if (p + n > end) throw new IllegalArgumentException("truncated");
            long r = 0;
            for (int i = 0; i < n; i++) r |= (long) (b[p++] & 0xFF) << (8 * i);
            return r;
        }

        Pb sub() {
            long len = varint();
            if (len < 0 || p + len > end) throw new IllegalArgumentException("truncated");
            Pb s = new Pb(b, p, (int) (p + len));
            p += (int) len;
            return s;
        }

        String string() {
            Pb s = sub();
            return new String(b, s.p, s.end - s.p, java.nio.charset.StandardCharsets.UTF_8);
        }

        void skip(int wire) {
            switch (wire) {
                case 0: varint(); break;
                case 1: fixed(8); break;
                case 2: sub(); break;
                case 5: fixed(4); break;
                default: throw new IllegalArgumentException("wire type " + wire);
            }
        }
    }
}
