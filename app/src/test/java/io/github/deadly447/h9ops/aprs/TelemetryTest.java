package io.github.deadly447.h9ops.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/** Telemetry, with RIDGE's real packets (heard 2026-11-20). */
public class TelemetryTest {
    static AprsPacket pkt(String tnc2) {
        int c = tnc2.indexOf(':');
        return AprsParser.parse(Ax25.fromTnc2(tnc2.substring(0, c), tnc2.substring(c + 1).getBytes(StandardCharsets.ISO_8859_1)));
    }

    static final String[] RIDGE_DEFS = {
        "RIDGE>APMI04,WIDE2-1::RIDGE    :PARM.Vin,Rx1h,Dg1h,Eff1h,A5,O1,O2,O3,O4,I1,I2,I3,I4",
        "RIDGE>APMI04,WIDE2-1::RIDGE    :UNIT.Volt,Pkt,Pkt,Pcnt,None,On,On,On,On,Hi,Hi,Hi,Hi",
        "RIDGE>APMI04,WIDE2-1::RIDGE    :EQNS.0,0.079,0,0,10,0,0,10,0,0,1,0,0,0,0",
        "RIDGE>APMI04,WIDE2-1::RIDGE    :BITS.11111111,Ridge Telemetry",
    };

    @Test
    public void ridgeReportWithItsDefinitions() {
        StationStore st = new StationStore();
        long t = 1000;
        for (String d : RIDGE_DEFS) st.add(t++, pkt(d));
        st.add(t, pkt("RIDGE>APMI04,WIDE2-1:T#102,174,033,001,058,000,00000000"));
        Station s = st.get("RIDGE");
        assertNotNull(s.telemetry);
        assertEquals("102", s.telemetry.seq);
        assertEquals("Ridge Telemetry", s.telemetryDefs.title);
        List<String> l = Telemetry.describe(s.telemetry, s.telemetryDefs);
        assertEquals("Vin  13.7 Volt", l.get(0));      // 174 * 0.079 = 13.75
        assertEquals("Rx1h  330 Pkt", l.get(1));
        assertEquals("Dg1h  10 Pkt", l.get(2));
        assertEquals("Eff1h  58 Pcnt", l.get(3));
        assertEquals("A5  0 None", l.get(4));
        assertEquals("O1  off", l.get(5));              // bit 0 with sense 1: off
        assertEquals(13, l.size());
        assertEquals("Vin 13.7 Volt · Rx1h 330 Pkt", s.summary());
    }

    @Test
    public void reportBeforeDefinitionsShowsRawThenNamed() {
        StationStore st = new StationStore();
        st.add(1, pkt("PEAK>APMI04,RIDGE*,WIDE2*:T#235,148,043,006,094,000,00000000"));
        Station s = st.get("PEAK");
        List<String> raw = Telemetry.describe(s.telemetry, s.telemetryDefs);
        assertEquals("A1  148", raw.get(0));
        assertEquals("Bits  00000000", raw.get(5));
        assertEquals("Telemetry", s.summary());
        st.add(2, pkt("PEAK>APMI04,RIDGE*,WIDE2*::PEAK     :PARM.Vin"));
        st.add(3, pkt("PEAK>APMI04,RIDGE*,WIDE2*::PEAK     :EQNS.0,0.079,0"));
        assertEquals("Vin  11.7", Telemetry.describe(s.telemetry, s.telemetryDefs).get(0));
    }

    @Test
    public void definitionsAreNotMessages() {
        AprsPacket p = pkt(RIDGE_DEFS[0]);
        assertEquals(AprsPacket.Type.TELEMETRY, p.type);
        assertEquals("RIDGE", p.telemetryFor);
        assertNull(p.msgTo);
    }

    @Test
    public void oddReports() {
        assertNull(Telemetry.Values.parse(""));
        assertNull(Telemetry.Values.parse("MIC"));
        Telemetry.Values v = Telemetry.Values.parse("MIC,1.5,2,3");
        assertEquals("MIC", v.seq);
        assertEquals(1.5, v.analog[0], 0);
        assertTrue(Double.isNaN(v.analog[3]));
        v = Telemetry.Values.parse("005,199,000,255,073,123,01101001 solar shed");
        assertEquals("01101001", v.bits);
        assertEquals("solar shed", v.comment);
        v = Telemetry.Values.parse("1,Infinity,x,3,4,5,11111111");
        assertEquals("?", Telemetry.number(v.analog[0]));
        assertTrue(Double.isNaN(v.analog[1]));
        // a hostile EQNS can't break the display
        Telemetry.Defs d = new Telemetry.Defs();
        d.apply("EQNS.1e308,1e308,1e308");
        d.apply("PARM.x");
        assertEquals("x  ?", Telemetry.describe(Telemetry.Values.parse("1,1e200,0,0,0,0,00000000"), d).get(0));
        assertEquals("0.25", Telemetry.number(0.25));
        assertEquals("1234", Telemetry.number(1234.4));
        assertEquals("-3.5", Telemetry.number(-3.5));
    }
}
