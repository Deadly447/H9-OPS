package io.github.deadly447.h9ops.map;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Random;

public class TerrainKindTest {
    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** A hillside from 1,900 to 2,300 m. */
    private static int[] terrarium() {
        int[] px = new int[1024];
        for (int i = 0; i < px.length; i++) {
            double m = 1900 + 400.0 * i / px.length;
            double v = m + 32768;
            int r = (int) (v / 256), g = (int) v % 256, b = (int) ((v - Math.floor(v)) * 256);
            px[i] = argb(r, g, b);
        }
        return px;
    }

    @Test
    public void elevationTilesAreRecognised() {
        assertEquals(TerrainKind.TERRARIUM, TerrainKind.classify("{}", terrarium()));
        int[] mb = new int[1024];
        for (int i = 0; i < mb.length; i++) {
            int v = (int) ((2100 + i * 0.3 + 10000) * 10);
            mb[i] = argb(v >> 16, (v >> 8) & 0xFF, v & 0xFF);
        }
        assertEquals(TerrainKind.MAPBOX, TerrainKind.classify(null, mb));
    }

    @Test
    public void metadataDecidesFirst() {
        assertEquals(TerrainKind.TERRARIUM, TerrainKind.classify("{\"encoding\":\"terrarium\"}", null));
        assertEquals(TerrainKind.MAPBOX, TerrainKind.classify("{\"name\":\"Terrain-RGB\"}", null));
        assertEquals(TerrainKind.PICTURE, TerrainKind.classify("{\"name\":\"USGS Topo\"}", null));
    }

    @Test
    public void picturesStayPictures() {
        int[] topo = new int[1024];
        Random r = new Random(4);
        for (int i = 0; i < topo.length; i++) {
            // mostly paper white and woodland green, with brown contours and black text
            int k = r.nextInt(10);
            topo[i] = k < 5 ? argb(255, 255, 250) : k < 8 ? argb(205, 230, 190) : k < 9 ? argb(170, 120, 70) : argb(20, 20, 20);
        }
        assertEquals(TerrainKind.PICTURE, TerrainKind.classify("{}", topo));
        int[] grey = new int[1024];
        for (int i = 0; i < grey.length; i++) grey[i] = argb(120 + i % 60, 120 + i % 60, 120 + i % 60);
        assertEquals(TerrainKind.PICTURE, TerrainKind.classify("{}", grey));   // red spread too wide
        assertEquals(TerrainKind.PICTURE, TerrainKind.classify("{}", new int[1024]));   // all transparent
    }

    @Test
    public void idsRoundTrip() {
        for (TerrainKind k : TerrainKind.values()) assertEquals(k, TerrainKind.of(k.id));
        assertEquals(TerrainKind.PICTURE, TerrainKind.of("nonsense"));
    }
}
