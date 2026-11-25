package io.github.deadly447.h9ops.map;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * The terrain file: elevation tiles (drawn as shading and contour lines) or a picture
 * map such as a USGS topo map, in PMTiles or MBTiles format. Kept beside the street map.
 */
public final class Terrain {
    private Terrain() {}

    /** Opens a PMTiles or MBTiles file of picture tiles. */
    public static TileSource open(File f) throws IOException {
        byte[] head = new byte[16];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (raf.length() < 16) throw new IOException("Not a map file (too short)");
            raf.readFully(head);
        }
        if (MbTiles.looksLike(head)) return MbTiles.open(f);
        return MapFile.open(f, true);
    }

    /** What the file holds and its tile size in pixels (256 or 512). */
    public static final class Info {
        public final TerrainKind kind;
        public final int px;

        Info(TerrainKind kind, int px) {
            this.kind = kind;
            this.px = px;
        }
    }

    /** Looks at one tile near the middle of the file's area. */
    public static Info detect(TileSource t) throws IOException {
        double[] b = t.bounds();
        double lat = (b[0] + b[2]) / 2, lon = (b[1] + b[3]) / 2;
        int start = Math.max(t.minZoom(), Math.min(t.maxZoom(), 11));
        java.util.List<Integer> zooms = new java.util.ArrayList<>();
        for (int z = start; z >= t.minZoom(); z--) zooms.add(z);
        for (int z = start + 1; z <= t.maxZoom(); z++) zooms.add(z);
        for (int z : zooms) {
            int n = 1 << z;
            int x = Math.min(n - 1, Math.max(0, (int) Math.floor((lon + 180) / 360 * n)));
            double r = Math.toRadians(lat);
            int y = Math.min(n - 1, Math.max(0, (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n)));
            byte[] bytes = t.tile(z, x, y);
            if (bytes == null || bytes.length == 0) continue;
            Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bm == null) throw new IOException("The file's tiles can't be read as pictures");
            try {
                int w = bm.getWidth(), h = bm.getHeight();
                int[] px = new int[32 * 32];
                for (int j = 0; j < 32; j++) {
                    for (int i = 0; i < 32; i++) px[j * 32 + i] = bm.getPixel(i * w / 32, j * h / 32);
                }
                return new Info(TerrainKind.classify(t.metadata(), px), w >= 384 ? 512 : 256);
            } finally {
                bm.recycle();
            }
        }
        throw new IOException("No tiles found in the middle of the file's area");
    }
}
