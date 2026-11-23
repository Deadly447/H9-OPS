package io.github.deadly447.h9ops.aprs;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Builds packets for tests the way they would arrive from the radio. */
final class TestPackets {
    private TestPackets() {}

    static AprsPacket msg(String from, String to, String text) {
        String info = ":" + Messenger.pad9(to) + ":" + text;
        return AprsParser.parse(Ax25.parse(Ax25.encode(from, "APRS", Collections.emptyList(),
                info.getBytes(StandardCharsets.ISO_8859_1))));
    }

    static AprsPacket tnc2(String line) {
        int c = line.indexOf(':');
        int gt = line.indexOf('>');
        String info = line.substring(c + 1);
        return AprsParser.parse(Ax25.parse(Ax25.encode(line.substring(0, gt), "APRS", Collections.emptyList(),
                info.getBytes(StandardCharsets.ISO_8859_1))));
    }
}
