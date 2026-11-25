package io.github.deadly447.h9ops.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapRecipeTest {
    @Test
    public void commandForAnArea() {
        long t = 1795467900000L;   // 2026-11-23 21:05 UTC
        assertEquals("20261121", MapRecipe.buildDate(t));
        String c = MapRecipe.command(40.0, -105.25, 15, t);
        assertEquals("pmtiles extract https://build.protomaps.com/20261121.pmtiles h9-map.pmtiles "
                + "--bbox=-105.5338,39.7826,-104.9662,40.2174", c);
        double[] b = MapRecipe.bbox(40, -105.25, 40);
        assertTrue(b[0] < b[2] && b[1] < b[3]);
        assertEquals(40 / 69.0 * 2, b[3] - b[1], 0.001);
        double[] edge = MapRecipe.bbox(89, 179.9, 100);       // clamped at the poles and the date line
        assertTrue(edge[3] <= 85 && edge[2] <= 180);
    }
}
