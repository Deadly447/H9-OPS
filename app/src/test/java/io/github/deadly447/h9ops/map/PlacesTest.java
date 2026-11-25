package io.github.deadly447.h9ops.map;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class PlacesTest {
    // a tiny protobuf writer, just enough for a vector tile
    private static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }
    private static void bytes(ByteArrayOutputStream o, int field, byte[] b) {
        varint(o, (field << 3) | 2);
        varint(o, b.length);
        o.write(b, 0, b.length);
    }
    private static byte[] str(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] strValue(String s) { ByteArrayOutputStream o = new ByteArrayOutputStream(); bytes(o, 1, str(s)); return o.toByteArray(); }
    private static byte[] intValue(long v) { ByteArrayOutputStream o = new ByteArrayOutputStream(); varint(o, 4 << 3); varint(o, v); return o.toByteArray(); }
    private static long zz(long n) { return (n << 1) ^ (n >> 63); }

    private static byte[] feature(int nameIdx, int kindIdx, int popIdx, int x, int y) {
        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        varint(tags, 0); varint(tags, nameIdx); varint(tags, 1); varint(tags, kindIdx); varint(tags, 2); varint(tags, popIdx);
        ByteArrayOutputStream g = new ByteArrayOutputStream();
        varint(g, (1 << 3) | 1); varint(g, zz(x)); varint(g, zz(y));
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        bytes(f, 2, tags.toByteArray());
        varint(f, 3 << 3); varint(f, 1);
        bytes(f, 4, g.toByteArray());
        return f.toByteArray();
    }

    static byte[] tile(String layerName) {
        ByteArrayOutputStream l = new ByteArrayOutputStream();
        bytes(l, 1, str(layerName));
        bytes(l, 2, feature(3, 5, 7, 2048, 2048));      // a town in the middle of the tile
        bytes(l, 2, feature(4, 6, 8, 100, 100));        // a neighbourhood near a corner
        bytes(l, 3, str("name")); bytes(l, 3, str("kind")); bytes(l, 3, str("population"));
        for (byte[] v : new byte[][]{strValue("x"), strValue("x"), strValue("x"), strValue("Springfield"),
                strValue("Downtown"), strValue("locality"), strValue("neighbourhood"), intValue(2000), intValue(0)}) bytes(l, 4, v);
        varint(l, 5 << 3); varint(l, 4096);
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        bytes(t, 3, l.toByteArray());
        return t.toByteArray();
    }

    @Test
    public void decodesPlacesLayer() {
        int z = 10, tx = 200, ty = 400;
        List<Places.Place> p = Places.decode(tile("places"), z, tx, ty);
        assertEquals(2, p.size());
        assertEquals("Springfield", p.get(0).name);
        assertEquals("locality", p.get(0).kind);
        assertEquals(2000, p.get(0).population);
        double n = 1 << z;
        double lon = (tx + 0.5) / n * 360 - 180;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (ty + 0.5) / n))));
        assertEquals(lon, p.get(0).lon, 1e-9);
        assertEquals(lat, p.get(0).lat, 1e-9);
        assertEquals("neighbourhood", p.get(1).kind);
        assertEquals(0, Places.decode(tile("roads"), z, tx, ty).size());   // other layers are skipped
    }
}
