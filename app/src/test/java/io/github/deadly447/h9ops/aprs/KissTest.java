package io.github.deadly447.h9ops.aprs;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** KISS TXDELAY frames (command 0x01, 10 ms units). */
public class KissTest {
    private static byte[] b(int... v) {
        byte[] o = new byte[v.length];
        for (int i = 0; i < v.length; i++) o[i] = (byte) v[i];
        return o;
    }

    @Test public void txDelayFrames() {
        assertArrayEquals(b(0xC0, 0x01, 30, 0xC0), Kiss.txDelay(300));
        assertArrayEquals(b(0xC0, 0x01, 50, 0xC0), Kiss.txDelay(500));
        assertArrayEquals(b(0xC0, 0x01, 0, 0xC0), Kiss.txDelay(-20));     // clamped
        assertArrayEquals(b(0xC0, 0x01, 255, 0xC0), Kiss.txDelay(9000));  // clamped
        assertArrayEquals(b(0xC0, 0x01, 0xDB, 0xDC, 0xC0), Kiss.txDelay(1920));  // 192 = FEND, escaped
        assertArrayEquals(b(0xC0, 0x01, 0xDB, 0xDD, 0xC0), Kiss.txDelay(2190));  // 219 = FESC, escaped
    }
}
