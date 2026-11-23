package io.github.deadly447.h9ops;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class RadioInfoTest {
    /** A reply as the P2 firmware builds it, from the RAM bytes. */
    static byte[] reply(int[] m) {
        StringBuilder sb = new StringBuilder("H9PI");
        for (int v : m) sb.append(String.format("%02X", v));
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    static void u32(int[] m, int i, long v) { for (int k = 0; k < 4; k++) m[i + k] = (int) (v >> (8 * k)) & 0xff; }

    static void bcd(int[] m, int i, long v) {   // little-endian BCD, 8 digits
        for (int k = 0; k < 4; k++) { int two = (int) (v % 100); v /= 100; m[i + k] = (two / 10) << 4 | (two % 10); }
    }

    static int[] sample() {
        int[] m = new int[RadioInfo.BYTES];
        m[1] = 1; m[7] = 3;            // A: memory channel 3
        m[2] = 0; m[8] = 9;            // B: VFO
        m[17] = 0x80;                  // side B selected
        m[18] = 792 & 0xff; m[19] = 792 >> 8;   // 7.92 V
        u32(m, 20, 14652000); u32(m, 24, 14652000);   // A 146.520
        u32(m, 28, 14439000); u32(m, 32, 14439000);   // B 144.390
        m[36 + 14] = 0x20;             // A memory record: High, Wide
        m[84 + 14] = 0x08;             // B VFO record: Low, Narrow
        m[101] = 1; m[102] = 2;        // APRS on, RX CH-B
        return m;
    }

    @Test public void parses() {
        RadioInfo r = RadioInfo.parse(reply(sample()));
        assertNotNull(r);
        assertEquals(792, r.centivolts);
        assertEquals("7.92 V 75%", r.battery());
        assertFalse(r.a.vfo);
        assertEquals("CH 3", r.a.where());
        assertEquals("146.520", RadioInfo.mhz(r.a.rx));
        assertEquals("High", r.a.powerName());
        assertEquals("Wide", r.a.width());
        assertTrue(r.b.vfo);
        assertEquals("Low", r.b.powerName());
        assertTrue(r.b.narrow);
        assertTrue(r.sideB);
        assertEquals(" A 146.520  CH 3  High  Wide", r.line(false));
        assertEquals("▶B 144.390  VFO  Low  Narrow  APRS", r.line(true));
        assertTrue(r.details().contains("Channel B (selected)"));
    }

    @Test public void tableRows() {
        RadioInfo r = RadioInfo.parse(reply(sample()));
        assertArrayEquals(new String[]{" A", "146.520", "03", "HI", "W", ""}, r.row(false));
        assertArrayEquals(new String[]{"▶B", "144.390", "VFO", "LO", "N", "TX·APRS"}, r.row(true));
        int[] m = sample();
        m[17] = 0;                                     // A selected
        RadioInfo r2 = RadioInfo.parse(reply(m));
        assertEquals("TX", r2.row(false)[5]);
        assertEquals("APRS", r2.row(true)[5]);
    }

    @Test public void channelBCheck() {
        int[] m = sample();
        String p = RadioInfo.parse(reply(m)).channelBProblem();
        assertNotNull(p);
        assertTrue(p, p.contains("Narrow") && !p.contains("not 144.390"));
        m[84 + 14] = 0x00;                         // Wide
        assertNull(RadioInfo.parse(reply(m)).channelBProblem());
        u32(m, 28, 14505000);
        assertTrue(RadioInfo.parse(reply(m)).channelBProblem().contains("145.050 MHz, not 144.390"));
        u32(m, 28, 14439000);
        m[102] = 1;
        assertTrue(RadioInfo.parse(reply(m)).channelBProblem().contains("APRS RX CH"));
        m[101] = 0;
        assertTrue(RadioInfo.parse(reply(m)).channelBProblem().contains("APRS Switch is off"));
    }

    @Test public void fallsBackToTheRecordsBcd() {
        int[] m = sample();
        u32(m, 20, 0);                 // binary word not filled in
        bcd(m, 36, 14652250);          // A memory record: 146.5225
        assertEquals("146.5225", RadioInfo.mhz(RadioInfo.parse(reply(m)).a.rx));
    }

    @Test public void sideFromTheSelectionFlag() {
        int[] m = sample();
        m[100] = 1; m[4] = 0;          // flag set: side from 0x11AEC
        assertFalse(RadioInfo.parse(reply(m)).sideB);
    }

    @Test public void batteryCurve() {
        int[] m = sample();
        m[18] = 600 & 0xff; m[19] = 600 >> 8;
        assertEquals(0, RadioInfo.parse(reply(m)).percent());
        m[18] = 830 & 0xff; m[19] = 830 >> 8;
        assertEquals(100, RadioInfo.parse(reply(m)).percent());
        m[18] = 760 & 0xff; m[19] = 760 >> 8;
        assertEquals(50, RadioInfo.parse(reply(m)).percent());
    }

    @Test public void rejectsOtherFrames() {
        assertNull(RadioInfo.parse("H9PW0205000045230100".getBytes(StandardCharsets.US_ASCII)));
        byte[] r = reply(sample());
        r[10] = 'G';
        assertNull(RadioInfo.parse(r));
        assertNull(RadioInfo.parse(null));
    }

    @Test public void settingsChangeIgnoresBattery() {
        int[] m = sample();
        RadioInfo one = RadioInfo.parse(reply(m));
        m[18] = 700 & 0xff; m[19] = 700 >> 8;
        assertFalse(RadioInfo.parse(reply(m)).settingsDiffer(one));
        m[7] = 4;
        assertTrue(RadioInfo.parse(reply(m)).settingsDiffer(one));
    }
}
