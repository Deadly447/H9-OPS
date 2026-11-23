package io.github.deadly447.h9ops.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Frames captured from a TD-H9 over BLE (nRF Connect log, 2026-11-18), split the way they arrived. */
public class DecoderTest {

    static byte[] hex(String s) {
        String[] parts = s.trim().split("[- ]+");
        byte[] b = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) b[i] = (byte) Integer.parseInt(parts[i], 16);
        return b;
    }

    static final String[][] CAPTURED = {
        {   // RIDGE telemetry, path WIDE2-1
            "C0-C0-C0-C0-C0-00-82-A0-9A-92-60-68-60-A4-92-88-8E-8A-40-60",
            "AE-92-88-8A-64-40-63-03-F0-54-23-31-38-35-2C-31-37-34-2C-30",
            "32-39-2C-30-30-31-2C-30-35-37-2C-30-30-30-2C-30-30-30-30-30",
            "30-30-30-C0-C0-C0"},
        {   // PEAK telemetry, digipeated by RIDGE
            "C0-C0-C0-C0-C0-00-82-A0-9A-92-60-68-60-A0-8A-82-96-40-40-60",
            "A4-92-88-8E-8A-40-E0-AE-92-88-8A-64-40-E1-03-F0-54-23-31-30",
            "38-2C-31-34-38-2C-30-33-30-2C-30-30-34-2C-30-39-33-2C-30-30",
            "30-2C-30-30-30-30-30-30-30-30-C0-C0-C0"},
        {   // RIDGE weather-station position
            "C0-C0-C0-C0-C0-00-82-A0-9A-92-60-68-60-A4-92-88-8E-8A-40-60",
            "AE-92-88-8A-64-40-63-03-F0-40-31-38-30-38-32-37-7A-34-30-35",
            "30-2E-34-30-4E-54-31-30-35-32-37-2E-36-37-57-26-57-58-33-69",
            "6E-31-4D-69-6E-69-20-55-3D-31-33-2E-36-56-20-6E-30-63-61-6C",
            "C0-C0-C0"},
    };

    static List<byte[]> frames(String[] chunks) {
        Kiss k = new Kiss();
        List<byte[]> out = new ArrayList<>();
        for (String c : chunks) out.addAll(k.feed(hex(c)));
        return out;
    }

    @Test
    public void kissJoinsChunksAndIgnoresExtraFends() {
        for (String[] cap : CAPTURED) assertEquals(1, frames(cap).size());
        // one frame split mid-escape
        Kiss k = new Kiss();
        assertEquals(0, k.feed(hex("C0 00 41 DB")).size());
        List<byte[]> f = k.feed(hex("DC 42 C0"));
        assertEquals(1, f.size());
        assertEquals(3, f.get(0).length);
        assertEquals((byte) 0xC0, f.get(0)[1]);
    }

    @Test
    public void telemetryDirect() {
        Ax25 f = Ax25.parse(frames(CAPTURED[0]).get(0));
        assertNotNull(f);
        assertEquals("RIDGE", f.source);
        assertEquals("APMI04", f.dest);
        assertEquals("[WIDE2-1]", f.path.toString());
        assertTrue(f.heardDirect());
        assertNull(f.heardVia());
        AprsPacket p = AprsParser.parse(f);
        assertEquals(AprsPacket.Type.TELEMETRY, p.type);
        assertEquals("185,174,029,001,057,000,00000000", p.telemetry);
        assertEquals("RIDGE>APMI04,WIDE2-1:T#185,174,029,001,057,000,00000000", p.raw);
    }

    @Test
    public void telemetryViaDigi() {
        Ax25 f = Ax25.parse(frames(CAPTURED[1]).get(0));
        assertEquals("PEAK", f.source);
        assertEquals("[RIDGE*, WIDE2*]", f.path.toString());
        assertFalse(f.heardDirect());
        assertEquals("RIDGE", f.heardVia());
    }

    @Test
    public void weatherStationPosition() {
        AprsPacket p = AprsParser.parse(Ax25.parse(frames(CAPTURED[2]).get(0)));
        assertEquals(AprsPacket.Type.POSITION, p.type);
        assertEquals(40 + 50.40 / 60, p.lat, 1e-9);
        assertEquals(-(105 + 27.67 / 60), p.lon, 1e-9);
        assertEquals('T', p.symTable);
        assertEquals('&', p.symCode);
        assertEquals("WX3in1Mini U=13.6V n0cal", p.comment);
    }

    static AprsPacket tnc2(String line) {
        int c = line.indexOf(':');
        Ax25 f = Ax25.fromTnc2(line.substring(0, c), line.substring(c + 1).getBytes(StandardCharsets.ISO_8859_1));
        return AprsParser.parse(f);
    }

    @Test
    public void compressedPosition() {
        // APRS 1.0.1 spec example: /5L!!<*e7>7P[  = 49 30' N, 72 45' W, course 88, 36.2 knots
        AprsPacket p = tnc2("N0CALL>APRS:=/5L!!<*e7>7P[");
        assertEquals(49.5, p.lat, 1e-4);
        assertEquals(-72.75, p.lon, 1e-4);
        assertEquals(88, p.courseDeg, 1e-9);
        assertEquals(36.2, p.speedKnots, 0.1);
        assertEquals('>', p.symCode);
    }

    @Test
    public void plainWithCourseSpeedAltitude() {
        AprsPacket p = tnc2("N0CALL-9>APRS,WIDE1-1:!4052.50N/10525.00W>090/045/A=007200 hello");
        assertEquals(90, p.courseDeg, 1e-9);
        assertEquals(45, p.speedKnots, 1e-9);
        assertEquals(7200 * 0.3048, p.altitudeM, 1e-6);
        assertEquals("hello", p.comment);
    }

    @Test
    public void weatherReport() {
        AprsPacket p = tnc2("WX1>APRS:@092345z4903.50N/07201.75W_220/004g005t077r000p000P000h50b09900wRSW");
        assertEquals(AprsPacket.Type.WEATHER, p.type);
        assertEquals(220, p.wx.windDir, 1e-9);
        assertEquals(4, p.wx.windMph, 1e-9);
        assertEquals(77, p.wx.tempF, 1e-9);
        assertEquals(50, p.wx.humidity, 1e-9);
        assertEquals(990.0, p.wx.pressureMb, 1e-9);
    }

    @Test
    public void objectItemStatusMessage() {
        AprsPacket o = tnc2("N0CALL>APRS:;LEADER   *092345z4903.50N/07201.75W>088/036");
        assertEquals(AprsPacket.Type.OBJECT, o.type);
        assertEquals("LEADER", o.name);
        assertEquals("N0CALL", o.sender);
        AprsPacket i = tnc2("N0CALL>APRS:)AID #2!4903.50N/07201.75WA");
        assertEquals(AprsPacket.Type.ITEM, i.type);
        assertEquals("AID #2", i.name);
        AprsPacket s = tnc2("N0CALL>APRS:>092345zNet control tonight");
        assertEquals("Net control tonight", s.status);
        AprsPacket m = tnc2("N0CALL>APRS::N0CALL-5 :Hello there{12");
        assertEquals(AprsPacket.Type.MESSAGE, m.type);
        assertEquals("N0CALL-5", m.msgTo);
        assertEquals("Hello there", m.msgText);
        assertEquals("12", m.msgId);
    }

    @Test
    public void thirdParty() {
        AprsPacket p = tnc2("IGATE>APRS:}N0CALL>APRS,TCPIP,IGATE*:!4052.50N/10525.00W-home");
        assertTrue(p.thirdParty);
        assertEquals("N0CALL", p.name);
        assertEquals("home", p.comment);
    }

    @Test
    public void storeKeepsLatest() {
        StationStore st = new StationStore();
        for (String[] cap : CAPTURED) st.add(1000, frames(cap).get(0));
        assertEquals(2, st.size());
        Station a = st.get("RIDGE");
        assertEquals(2, a.count);
        assertNotNull(a.lastPosition);
        assertEquals(3, st.log().size());
    }

    @Test
    public void gatedFromInternet() {
        // 144.390, 2026-11-21 (phone number replaced)
        AprsPacket p = tnc2("RIDGE>APMI04,WIDE2-1:}SMS>APOSMS,TCPIP,RIDGE*::W1ABCD   :@5550100 Yes it did!{4789");
        assertTrue(p.thirdParty);
        assertTrue(p.fromInternet);
        assertEquals("SMS", p.sender);
        assertEquals("RIDGE", p.gatedBy);
        assertEquals(AprsPacket.Type.MESSAGE, p.type);
        assertEquals("W1ABCD", p.msgTo);
        assertEquals("internet, gated by RIDGE", p.route());
        AprsPacket b = tnc2("PEAK>APMI04,RIDGE,WIDE2*:}WA2XYZ-1>APDR16,TCPIP,PEAK*::W1ABCD   :howdy{8");
        assertEquals("WA2XYZ-1", b.sender);
        assertEquals("internet, gated by PEAK, via RIDGE", b.route());
        AprsPacket rf = tnc2("RIDGE>APMI04:}N0CALL>APRS,WIDE1*:>on the trail");
        assertFalse(rf.fromInternet);
        assertEquals("gated by RIDGE", rf.route());
    }

    @Test
    public void viaWhenOnlyLastHopIsMarked() {
        assertEquals("via RIDGE", tnc2("PEAK>APMI04,RIDGE,WIDE2*:T#168,148,038,005,079,000,00000000").route());
        assertEquals("via RIDGE", tnc2("PEAK>APMI04,RIDGE*,WIDE2*:>x").route());
        assertEquals("direct", tnc2("TEST1>APZH9M,WIDE1-1,NOGATE::TEST3    :testing{42").route());
        assertEquals("via a digipeater", tnc2("X>APRS,WIDE1*,WIDE2-1:>x").route());
    }

    @Test
    public void sameMessageFromTwoIgatesIsMarkedAgain() {
        StationStore st = new StationStore();
        st.add(1000, tnc2("RIDGE>APMI04,WIDE2-1:}WA2XYZ-1>APDR16,TCPIP,RIDGE*::W1ABCD   :howdy{8"));
        st.add(1700, tnc2("PEAK>APMI04,RIDGE,WIDE2*:}WA2XYZ-1>APDR16,TCPIP,PEAK*::W1ABCD   :howdy{8"));
        st.add(61_000, tnc2("RIDGE>APMI04,WIDE2-1:}WA2XYZ-1>APDR16,TCPIP,RIDGE*::W1ABCD   :howdy{8"));
        List<Station.Heard> log = st.log();      // newest first
        assertFalse(log.get(2).again);
        assertTrue(log.get(1).again);
        assertTrue("within a minute of the PEAK copy", log.get(0).again);
        StationStore t = new StationStore();
        t.add(1000, tnc2("RIDGE>APMI04,WIDE2-1:T#016,173,031,001,053,000,00000000"));
        t.add(200_000, tnc2("RIDGE>APMI04,WIDE2-1:T#016,173,031,001,053,000,00000000"));
        assertFalse("a retry minutes later is a new packet", t.log().get(0).again);
    }

    @Test
    public void utf8TextFromTheInternet() {
        String line = "RIDGE>APMI04,WIDE2-1:}OTA>APZ100,TCPIP,RIDGE*::WB3DEF-7 :DE W4GHI-6 cheers from Rom\u00e2nia{6064";
        byte[] utf8 = line.getBytes(StandardCharsets.UTF_8);
        int c = line.indexOf(':');
        byte[] info = java.util.Arrays.copyOfRange(utf8, line.substring(0, c + 1).getBytes(StandardCharsets.UTF_8).length, utf8.length);
        AprsPacket p = AprsParser.parse(Ax25.fromTnc2(line.substring(0, c), info));
        assertEquals("DE W4GHI-6 cheers from Rom\u00e2nia", p.msgText);
        assertTrue(p.raw, p.raw.endsWith("cheers from Rom\u00e2nia{6064"));
    }
}
