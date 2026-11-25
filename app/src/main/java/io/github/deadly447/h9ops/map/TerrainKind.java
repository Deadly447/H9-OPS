package io.github.deadly447.h9ops.map;

import java.util.Locale;

/**
 * What a picture-tile file holds: elevation data (terrain, drawn as shading and
 * contour lines) or a picture map (a topo map, drawn as it is). Elevation tiles
 * store heights in their colours, in one of two common encodings. No Android code.
 */
public enum TerrainKind {
    /** Terrarium elevation (Mapterhorn, AWS terrain tiles): metres = R×256 + G + B/256 − 32768. */
    TERRARIUM("terrarium"),
    /** Mapbox Terrain-RGB elevation: metres = −10000 + (R×65536 + G×256 + B) / 10. */
    MAPBOX("mapbox"),
    /** A picture map, such as a USGS topo map. */
    PICTURE("picture");

    public final String id;

    TerrainKind(String id) { this.id = id; }

    public static TerrainKind of(String id) {
        for (TerrainKind k : values()) if (k.id.equals(id)) return k;
        return PICTURE;
    }

    public boolean elevation() { return this != PICTURE; }

    /**
     * Decides from the file's metadata, if it says, or else from a sample tile's
     * pixels (ARGB). Elevation tiles have a nearly constant red channel over a
     * tile (it only changes every 256 m in Terrarium, every 6,500 m in Terrain-RGB);
     * a picture map has every colour.
     */
    public static TerrainKind classify(String metadata, int[] argb) {
        String m = metadata == null ? "" : metadata.toLowerCase(Locale.US);
        if (m.contains("terrarium")) return TERRARIUM;
        if (m.contains("terrain-rgb") || m.contains("\"encoding\":\"mapbox\"")) return MAPBOX;
        if (argb == null || argb.length == 0) return PICTURE;
        int n = 0, terr = 0, mb = 0, rMin = 255, rMax = 0;
        for (int p : argb) {
            if ((p >>> 24) < 250) continue;      // transparent: no data
            int r = (p >> 16) & 0xFF;
            n++;
            if (r >= 100 && r <= 170) terr++;
            if (r <= 4) mb++;
            rMin = Math.min(rMin, r);
            rMax = Math.max(rMax, r);
        }
        if (n < 16) return PICTURE;
        if (terr >= n * 0.95 && rMax - rMin <= 24) return TERRARIUM;
        if (mb >= n * 0.95) return MAPBOX;
        return PICTURE;
    }
}
