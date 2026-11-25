package io.github.deadly447.h9ops.map;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MapFileTest {
    /** A PMTiles v3 header like a Protomaps extract: vector, zoom 0–15. */
    static byte[] header(int version, int tileType) {
        ByteBuffer b = ByteBuffer.allocate(MapFile.HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN);
        b.put("PMTiles".getBytes());
        b.put((byte) version);
        b.putLong(8, MapFile.HEADER_LEN);   // root directory right after the header
        b.putLong(16, 1);
        b.position(97);
        b.put((byte) 2);              // internal compression gzip
        b.put((byte) 2);              // tile compression gzip
        b.put((byte) tileType);
        b.put((byte) 0);              // min zoom
        b.put((byte) 15);             // max zoom
        b.putInt((int) Math.round(-111.0 * 1e7));
        b.putInt((int) Math.round(36.5 * 1e7));
        b.putInt((int) Math.round(-107.5 * 1e7));
        b.putInt((int) Math.round(39.0 * 1e7));
        b.put((byte) 9);              // centre zoom
        b.putInt((int) Math.round(-109.25 * 1e7));
        b.putInt((int) Math.round(37.75 * 1e7));
        return b.array();
    }

    @Test
    public void readsTheHeader() throws IOException {
        MapFile.Header h = MapFile.Header.parse(header(3, 1));
        assertEquals(3, h.version);
        assertTrue(h.vector());
        assertEquals(0, h.minZoom);
        assertEquals(15, h.maxZoom);
        assertEquals(-111.0, h.minLon, 1e-7);
        assertEquals(36.5, h.minLat, 1e-7);
        assertEquals(-107.5, h.maxLon, 1e-7);
        assertEquals(39.0, h.maxLat, 1e-7);
        assertEquals(9, h.centerZoom);
        assertEquals(-109.25, h.centerLon, 1e-7);
        assertEquals(37.75, h.centerLat, 1e-7);
        assertEquals("Vector map, zoom 0–15, 36.50 N–39.00 N, 111.00 W–107.50 W", h.describe());
    }

    @Test
    public void refusesOtherFiles() {
        refused(new byte[10], "too short");
        byte[] mb = header(3, 1);
        mb[0] = 'S';                   // an SQLite (MBTiles) file starts "SQLite format 3"
        refused(mb, "MBTiles");
        refused(header(2, 1), "version 2");
        refused(header(3, 2), "PNG");
        byte[] br = header(3, 1);
        br[98] = 3;                                       // brotli tiles
        refused(br, "compressed");
        byte[] bad = header(3, 1);
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(102, (int) (-100.0 * 1e7));   // min lon > max lon
        refused(bad, "damaged");
    }

    private static void refused(byte[] b, String why) {
        try {
            MapFile.Header.parse(b);
            fail("accepted: " + why);
        } catch (IOException e) {
            assertTrue(e.getMessage() + " should mention " + why, e.getMessage().contains(why));
        }
    }

    @Test
    public void tileIdsFollowTheSpec() {
        // values from the PMTiles reference implementation
        assertEquals(0, MapFile.tileId(0, 0, 0));
        assertEquals(1, MapFile.tileId(1, 0, 0));
        assertEquals(2, MapFile.tileId(1, 0, 1));
        assertEquals(3, MapFile.tileId(1, 1, 1));
        assertEquals(4, MapFile.tileId(1, 1, 0));
        assertEquals(5, MapFile.tileId(2, 0, 0));
        assertEquals(9024165, MapFile.tileId(12, 800, 1500));
        assertEquals(571785733, MapFile.tileId(15, 6000, 12500));
    }

    @Test
    public void findsTilesInTheDirectory() throws IOException {
        // three tiles: z0, z1 (0,0), and z1 (1,1)+(1,0) sharing one blob (run length 2)
        byte[][] tiles = {"zero".getBytes(), "one".getBytes(), "three-four".getBytes()};
        File f = File.createTempFile("map", ".pmtiles");
        try {
            writePmtiles(f, new long[]{0, 1, 3}, new int[]{1, 1, 2}, tiles);
            try (MapFile m = MapFile.open(f)) {
                assertArrayEquals("zero".getBytes(), m.tile(0, 0, 0));
                assertArrayEquals("one".getBytes(), m.tile(1, 0, 0));
                assertNull(m.tile(1, 0, 1));                               // id 2: not in the map
                assertArrayEquals("three-four".getBytes(), m.tile(1, 1, 1));
                assertArrayEquals("three-four".getBytes(), m.tile(1, 1, 0)); // id 4, same run
                assertNull(m.tile(2, 0, 0));                               // above max zoom
                assertNull(m.tile(1, 2, 0));                               // off the world
                assertNull(m.tile(-1, 0, 0));
            }
        } finally {
            assertTrue(f.delete());
        }
    }

    /** A minimal PMTiles v3 file: gzip root directory and gzip tiles, zoom 0-1. */
    static void writePmtiles(File f, long[] ids, int[] runs, byte[][] tiles) throws IOException {
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        int[] lens = new int[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            byte[] g = gzip(tiles[i]);
            lens[i] = g.length;
            data.write(g);
        }
        java.io.ByteArrayOutputStream dir = new java.io.ByteArrayOutputStream();
        varint(dir, ids.length);
        long last = 0;
        for (long id : ids) { varint(dir, id - last); last = id; }
        for (int r : runs) varint(dir, r);
        for (int l : lens) varint(dir, l);
        for (int i = 0; i < ids.length; i++) varint(dir, i == 0 ? 1 : 0);   // offset 0, then "right after"
        byte[] root = gzip(dir.toByteArray());
        ByteBuffer h = ByteBuffer.wrap(header(3, 1)).order(ByteOrder.LITTLE_ENDIAN);
        h.putLong(8, MapFile.HEADER_LEN);                 // root directory
        h.putLong(16, root.length);
        h.putLong(40, MapFile.HEADER_LEN + root.length);  // leaf directories (none)
        h.putLong(56, MapFile.HEADER_LEN + root.length);  // tile data
        h.put(100, (byte) 0);
        h.put(101, (byte) 1);                             // zoom 0-1
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(h.array());
            out.write(root);
            out.write(data.toByteArray());
        }
    }

    static byte[] gzip(byte[] b) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(o)) { g.write(b); }
        return o.toByteArray();
    }

    static void varint(java.io.ByteArrayOutputStream o, long v) {
        while (v >= 0x80) { o.write((int) (v & 0x7F) | 0x80); v >>>= 7; }
        o.write((int) v);
    }

    @Test
    public void opensAndReads() throws IOException {
        File f = File.createTempFile("map", ".pmtiles");
        try {
            byte[] body = new byte[5000];
            for (int i = 0; i < body.length; i++) body[i] = (byte) i;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(header(3, 1));
                out.write(body);
            }
            try (MapFile m = MapFile.open(f)) {
                assertEquals(MapFile.HEADER_LEN + 5000, m.size);
                assertEquals(15, m.header.maxZoom);
                byte[] got = m.read(MapFile.HEADER_LEN + 10, 4);
                assertArrayEquals(new byte[]{10, 11, 12, 13}, got);
                try {
                    m.read(m.size - 2, 4);
                    fail("read past the end");
                } catch (IOException expected) { }
                assertTrue(m.version().contains("-"));
            }
        } finally {
            assertTrue(f.delete());
        }
    }

    @Test
    public void openRefusesARasterFile() throws IOException {
        File f = File.createTempFile("map", ".pmtiles");
        try {
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(header(3, 2)); }
            try {
                MapFile.open(f).close();
                fail("opened a raster map");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("PNG"));
            }
        } finally {
            assertTrue(f.delete());
        }
    }
}
