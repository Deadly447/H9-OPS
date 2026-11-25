package io.github.deadly447.h9ops.map;

import java.io.Closeable;
import java.io.IOException;

/** A file of map tiles found by zoom, column and row: a PMTiles or MBTiles file. */
public interface TileSource extends Closeable {
    /** The tile's bytes (uncompressed), or null where the file has none. Safe from several threads. */
    byte[] tile(int z, int x, int y) throws IOException;
    int minZoom();
    int maxZoom();
    /** {south, west, north, east}, degrees. */
    double[] bounds();
    /** "image/png", "image/jpeg" or "image/webp" for pictures; null for vector tiles. */
    String mime();
    /** One line for people: kind, zooms and area. */
    String describe();
    /** The file's own metadata as JSON (attribution, encoding and so on), or "{}". */
    String metadata();
    /** Changes whenever the file is replaced. */
    String version();
    long size();
}
