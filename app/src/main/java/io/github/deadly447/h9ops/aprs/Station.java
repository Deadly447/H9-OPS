package io.github.deadly447.h9ops.aprs;

import java.util.ArrayDeque;
import java.util.Deque;

/** Everything heard from one station (or one object/item). */
public final class Station {
    public static final int KEEP_PACKETS = 30;

    public final String name;
    public long firstHeard, lastHeard;
    public int count;
    public AprsPacket last;          // most recent packet of any kind
    public AprsPacket lastPosition;  // most recent packet with a position
    public long lastPositionTime;
    public String status;            // latest '>' status text
    public AprsPacket.Weather wx;    // latest weather
    public Telemetry.Values telemetry;   // latest T# report
    public long telemetryTime;
    public Telemetry.Defs telemetryDefs; // what its channels mean (from its PARM/UNIT/EQNS/BITS)
    public String lastVia;           // null = last heard direct
    public boolean object;
    public final Deque<Heard> history = new ArrayDeque<>();

    public static final class Heard {
        public final long time;
        public final AprsPacket packet;
        /** The same packet was already in the log from under a minute ago (another digipeater or iGate). */
        public boolean again;
        Heard(long time, AprsPacket packet) { this.time = time; this.packet = packet; }

        /** Packet-log tag: "VIA RIDGE", "NET · PEAK", "AGAIN · VIA RIDGE", or null when heard direct. */
        public String tag() {
            AprsPacket q = packet;
            String t = q.thirdParty
                    ? (q.fromInternet ? "NET · " : "GATED · ") + q.gatedBy.toUpperCase(java.util.Locale.US)
                    : q.heardVia == null ? null : "VIA " + q.heardVia.toUpperCase(java.util.Locale.US);
            return again ? (t == null ? "AGAIN" : "AGAIN · " + t) : t;
        }

        /** For the packet's details: "heard direct", "via RIDGE", "internet, gated by RIDGE", plus a note if heard again. */
        public String describe() {
            AprsPacket q = packet;
            return (q.thirdParty ? q.route() : q.heardVia == null ? "heard direct" : "via " + q.heardVia)
                    + (again ? "\n\nHeard again: the same packet arrived under a minute earlier. Either the"
                    + " sender sent it again (a retry), or another digipeater or iGate repeated it." : "");
        }
    }

    public Station(String name) {
        this.name = name;
    }

    void add(long now, AprsPacket p) {
        if (count == 0) firstHeard = now;
        count++;
        lastHeard = now;
        last = p;
        lastVia = p.heardVia;
        object = p.type == AprsPacket.Type.OBJECT || p.type == AprsPacket.Type.ITEM;
        if (p.hasPosition()) { lastPosition = p; lastPositionTime = now; }
        if (p.status != null) status = p.status;
        if (p.wx != null) wx = p.wx;
        if (p.type == AprsPacket.Type.TELEMETRY && p.telemetryFor == null) {
            Telemetry.Values v = Telemetry.Values.parse(p.telemetry);
            if (v != null) { telemetry = v; telemetryTime = now; }
        }
        history.addFirst(new Heard(now, p));
        while (history.size() > KEEP_PACKETS) history.removeLast();
    }

    /** A position this station sent us by message (PositionShare). Kept if it is the newest. */
    void sharedPosition(long t, AprsPacket p) {
        if (count == 0) firstHeard = t;
        if (t >= lastPositionTime) { lastPosition = p; lastPositionTime = t; }
        if (t > lastHeard) lastHeard = t;
        if (last == null) last = p;
    }

    public char symTable() { return lastPosition != null ? lastPosition.symTable : '/'; }
    public char symCode() { return lastPosition != null ? lastPosition.symCode : ' '; }

    /** One line for the list: what this station said most recently. */
    public String summary() {
        AprsPacket p = last;
        StringBuilder sb = new StringBuilder();
        switch (p.type) {
            case MESSAGE:
                sb.append("Message to ").append(p.msgTo).append(": ").append(p.msgText);
                break;
            case STATUS:
                sb.append(p.status);
                break;
            case TELEMETRY: {
                // with the station's PARM/EQNS: its first two channels; without, the numbers mean little
                String t = telemetryDefs != null && telemetryDefs.any() && telemetry != null
                        ? Telemetry.brief(telemetry, telemetryDefs) : "";
                if (!t.isEmpty()) sb.append(t);
                else if (lastPosition != null && lastPosition.comment != null) sb.append(lastPosition.comment);
                else sb.append("Telemetry");
                break;
            }
            case OTHER:
                sb.append(p.raw.substring(p.raw.indexOf(':') + 1));
                break;
            default:
                if (p.killed) sb.append("Deleted. ");
                if (p.wx != null && !p.wx.summary().isEmpty()) sb.append(p.wx.summary()).append(". ");
                String sc = Format.speedCourse(p.speedKnots, p.courseDeg);
                if (!sc.isEmpty()) sb.append(sc).append(". ");
                if (p.miceStatus != null && !"Off Duty".equals(p.miceStatus)) sb.append(p.miceStatus).append(". ");
                if (p.comment != null) sb.append(p.comment);
        }
        return sb.toString().trim();
    }
}
