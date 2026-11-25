package io.github.deadly447.h9ops.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * An offline map in PMTiles v3 format (a Protomaps extract). This
 * checks the header and finds tiles by zoom, x and y, so the map page asks
 * for plain tile URLs (no byte ranges, which an app's WebView handles
 * unreliably). No Android code, so it is unit-tested on its own.
 */
public final class MapFile implements TileSource {
    public static final int HEADER_LEN = 127;
    /** Largest directory or tile read at once (real ones are kilobytes to a few megabytes). */
    public static final int MAX_READ = 32 * 1024 * 1024;

    /** The fixed 127-byte header at the start of every PMTiles v3 file. */
    public static final class Header {
        public final int version, internalCompression, tileCompression, tileType, minZoom, maxZoom, centerZoom;
        public final double minLon, minLat, maxLon, maxLat, centerLon, centerLat;
        final long rootOffset, rootLength, metaOffset, metaLength, leafOffset, tileDataOffset;

        private Header(ByteBuffer b) {
            version = b.get(7) & 0xFF;
            rootOffset = b.getLong(8);
            rootLength = b.getLong(16);
            metaOffset = b.getLong(24);
            metaLength = b.getLong(32);
            leafOffset = b.getLong(40);
            tileDataOffset = b.getLong(56);
            internalCompression = b.get(97) & 0xFF;
            tileCompression = b.get(98) & 0xFF;
            tileType = b.get(99) & 0xFF;
            minZoom = b.get(100) & 0xFF;
            maxZoom = b.get(101) & 0xFF;
            minLon = b.getInt(102) / 1e7;
            minLat = b.getInt(106) / 1e7;
            maxLon = b.getInt(110) / 1e7;
            maxLat = b.getInt(114) / 1e7;
            centerZoom = b.get(118) & 0xFF;
            centerLon = b.getInt(119) / 1e7;
            centerLat = b.getInt(123) / 1e7;
        }

        /** Reads and checks a street map's header (vector tiles). Throws with a reason a person can act on. */
        public static Header parse(byte[] bytes) throws IOException {
            return parse(bytes, false);
        }

        /**
         * Reads and checks a header. {@code raster}: a terrain or topo file, whose tiles are
         * pictures (PNG, JPEG or WebP); otherwise a street map, whose tiles are vector.
         */
        public static Header parse(byte[] bytes, boolean raster) throws IOException {
            if (bytes == null || bytes.length < HEADER_LEN) throw new IOException("Not a map file (too short)");
            for (int i = 0; i < 7; i++) {
                if (bytes[i] != "PMTiles".charAt(i)) {
                    throw new IOException("Not a PMTiles map file. MBTiles and other formats can't be read yet.");
                }
            }
            Header h = new Header(ByteBuffer.wrap(bytes, 0, HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN));
            if (h.version != 3) throw new IOException("PMTiles version " + h.version + " (only version 3 can be read)");
            if (!raster && !h.vector()) {
                throw new IOException("This file holds " + h.typeName() + " tiles (pictures), not a street map. "
                        + "If it's terrain or a topo map, add it under Terrain instead.");
            }
            if (raster && h.mime() == null) {
                throw new IOException(h.vector()
                        ? "This is a street map, not terrain. Add it under Map file instead."
                        : "This file holds " + h.typeName() + " tiles, which can't be shown (PNG, JPEG or WebP only)");
            }
            if (!supported(h.internalCompression) || !supported(h.tileCompression)) {
                throw new IOException("This map file is compressed in a way that can't be read (only gzip or none)");
            }
            if (h.rootOffset < HEADER_LEN || h.rootLength <= 0 || h.rootLength > MAX_READ) {
                throw new IOException("The map file's directory is damaged");
            }
            if (!(h.minLat < h.maxLat) || !(h.minLon < h.maxLon)
                    || h.minLat < -90 || h.maxLat > 90 || h.minLon < -180 || h.maxLon > 180) {
                throw new IOException("The map file's area is damaged");
            }
            return h;
        }

        public boolean vector() { return tileType == 1; }

        /** The tiles' picture type, or null for vector or unsupported tiles. */
        public String mime() {
            switch (tileType) {
                case 2: return "image/png";
                case 3: return "image/jpeg";
                case 4: return "image/webp";
                default: return null;
            }
        }

        /** 0 unknown (treated as none), 1 none, 2 gzip; brotli (3) and zstd (4) aren't readable here. */
        static boolean supported(int compression) { return compression <= 2; }

        String typeName() {
            switch (tileType) {
                case 1: return "vector";
                case 2: return "PNG";
                case 3: return "JPEG";
                case 4: return "WebP";
                case 5: return "AVIF";
                default: return "unknown";
            }
        }

        /** "Vector map, zoom 0–15, 36.50–39.00 N, 111.00–107.50 W". */
        public String describe() {
            return area(vector() ? "Vector map" : typeName() + " tiles", minZoom, maxZoom, minLat, maxLat, minLon, maxLon);
        }

        static String area(String what, int minZoom, int maxZoom, double minLat, double maxLat, double minLon, double maxLon) {
            return String.format(Locale.US, "%s, zoom %d–%d, %s–%s, %s–%s",
                    what, minZoom, maxZoom, lat(minLat), lat(maxLat), lon(minLon), lon(maxLon));
        }

        static String lat(double v) {
            return String.format(Locale.US, "%.2f %s", Math.abs(v), v >= 0 ? "N" : "S");
        }

        static String lon(double v) {
            return String.format(Locale.US, "%.2f %s", Math.abs(v), v >= 0 ? "E" : "W");
        }
    }

    public final File path;
    public final long size;
    public final Header header;
    private final RandomAccessFile raf;
    private final FileChannel channel;

    private MapFile(File path, RandomAccessFile raf, Header header) throws IOException {
        this.path = path;
        this.raf = raf;
        this.channel = raf.getChannel();
        this.size = channel.size();
        this.header = header;
    }

    /** Opens and checks a street map file (vector tiles). */
    public static MapFile open(File f) throws IOException {
        return open(f, false);
    }

    /** Opens and checks a map file; {@code raster}: terrain or topo pictures instead of vector tiles. */
    public static MapFile open(File f, boolean raster) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] h = new byte[HEADER_LEN];
            raf.readFully(h);
            return new MapFile(f, raf, Header.parse(h, raster));
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e instanceof IOException ? (IOException) e : new IOException("Not a map file", e);
        }
    }

    /** Reads {@code len} bytes at {@code start}. Safe from several threads at once. */
    public byte[] read(long start, int len) throws IOException {
        if (start < 0 || len < 0 || start + len > size) throw new IOException("Read past the end of the map file");
        ByteBuffer buf = ByteBuffer.allocate(len);
        long pos = start;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n < 0) throw new IOException("Map file ended early");
            pos += n;
        }
        return buf.array();
    }

    // ---------------------------------------------------------------- tiles

    /** One directory entry: a run of tiles, or (runLength 0) a pointer to a leaf directory. */
    static final class DirEntry {
        final long tileId, offset;
        final int length, runLength;

        DirEntry(long tileId, long offset, int length, int runLength) {
            this.tileId = tileId;
            this.offset = offset;
            this.length = length;
            this.runLength = runLength;
        }
    }

    /** Directories read recently (the root and the leaves around where the map is looking). */
    private final Map<Long, DirEntry[]> dirs = new LinkedHashMap<Long, DirEntry[]>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, DirEntry[]> e) { return size() > 64; }
    };

    /**
     * The tile at zoom z, column x, row y, uncompressed (a Mapbox vector tile),
     * or null when the map has none there (outside its area or zoom range).
     */
    @Override
    public byte[] tile(int z, int x, int y) throws IOException {
        if (z < 0 || z > 26 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) return null;
        if (z < header.minZoom || z > header.maxZoom) return null;
        long id = tileId(z, x, y);
        long dirOffset = header.rootOffset, dirLength = header.rootLength;
        for (int depth = 0; depth < 4; depth++) {
            DirEntry e = find(directory(dirOffset, dirLength), id);
            if (e == null) return null;
            if (e.runLength > 0) {
                byte[] raw = read(header.tileDataOffset + e.offset, e.length);
                return decompress(raw, header.tileCompression);
            }
            dirOffset = header.leafOffset + e.offset;
            dirLength = e.length;
        }
        throw new IOException("The map file's directories are too deep");
    }

    private DirEntry[] directory(long offset, long length) throws IOException {
        if (length <= 0 || length > MAX_READ) throw new IOException("Map directory has a bad size");
        synchronized (dirs) {
            DirEntry[] d = dirs.get(offset);
            if (d != null) return d;
        }
        DirEntry[] d = parseDirectory(decompress(read(offset, (int) length), header.internalCompression));
        synchronized (dirs) { dirs.put(offset, d); }
        return d;
    }

    /** The last entry at or before the tile, if it covers it (or points to a leaf that may). */
    static DirEntry find(DirEntry[] d, long id) {
        int lo = 0, hi = d.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long c = d[mid].tileId - id;
            if (c == 0) return d[mid];
            if (c < 0) lo = mid + 1; else hi = mid - 1;
        }
        if (hi >= 0) {
            DirEntry e = d[hi];
            if (e.runLength == 0) return e;                       // a leaf directory starting before it
            if (id - e.tileId < e.runLength) return e;            // inside a run of identical tiles
        }
        return null;
    }

    /** PMTiles v3 directory: count, then tile ids (deltas), run lengths, lengths, offsets, as varints. */
    static DirEntry[] parseDirectory(byte[] b) throws IOException {
        int[] pos = {0};
        long n = varint(b, pos);
        if (n < 0 || n > 10_000_000) throw new IOException("Map directory is damaged");
        int count = (int) n;
        long[] ids = new long[count], offs = new long[count];
        int[] runs = new int[count], lens = new int[count];
        long last = 0;
        for (int i = 0; i < count; i++) { last += varint(b, pos); ids[i] = last; }
        for (int i = 0; i < count; i++) runs[i] = (int) varint(b, pos);
        for (int i = 0; i < count; i++) lens[i] = (int) varint(b, pos);
        for (int i = 0; i < count; i++) {
            long v = varint(b, pos);
            // 0 after the first entry means "right after the previous one"
            offs[i] = v == 0 && i > 0 ? offs[i - 1] + lens[i - 1] : v - 1;
        }
        DirEntry[] d = new DirEntry[count];
        for (int i = 0; i < count; i++) d[i] = new DirEntry(ids[i], offs[i], lens[i], runs[i]);
        return d;
    }

    private static long varint(byte[] b, int[] pos) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= b.length) throw new IOException("Map directory ended early");
            int c = b[pos[0]++] & 0xFF;
            v |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) return v;
        }
        throw new IOException("Map directory is damaged");
    }

    static byte[] decompress(byte[] raw, int compression) throws IOException {
        if (compression != 2) return raw;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 3);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_READ) throw new IOException("Map tile too large");
            }
            return out.toByteArray();
        }
    }

    /** PMTiles tile id: all tiles of lower zooms first, then the Hilbert-curve position within zoom z. */
    static long tileId(int z, int x, int y) {
        long acc = ((1L << (2 * z)) - 1) / 3;
        long n = 1L << z, d = 0;
        long tx = x, ty = y;
        for (long s = n / 2; s > 0; s /= 2) {
            int rx = (tx & s) > 0 ? 1 : 0;
            int ry = (ty & s) > 0 ? 1 : 0;
            d += s * s * ((3L * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    tx = s - 1 - tx;
                    ty = s - 1 - ty;
                }
                long t = tx;
                tx = ty;
                ty = t;
            }
        }
        return acc + d;
    }

    /** The file's metadata (JSON, written by whatever made it), or "{}". At most 1 MB is read. */
    public String metadata() {
        try {
            if (header.metaLength <= 0 || header.metaLength > 1 << 20) return "{}";
            byte[] raw = read(header.metaOffset, (int) header.metaLength);
            return new String(decompress(raw, header.internalCompression), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "{}";
        }
    }

    // TileSource
    @Override public int minZoom() { return header.minZoom; }
    @Override public int maxZoom() { return header.maxZoom; }
    @Override public double[] bounds() { return new double[]{header.minLat, header.minLon, header.maxLat, header.maxLon}; }
    @Override public String mime() { return header.mime(); }
    @Override public String describe() { return header.describe(); }
    @Override public long size() { return size; }

    /** Changes whenever the file is replaced, so the page doesn't reuse an old copy. */
    @Override
    public String version() {
        return Long.toString(path.lastModified(), 36) + "-" + Long.toString(size, 36);
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
