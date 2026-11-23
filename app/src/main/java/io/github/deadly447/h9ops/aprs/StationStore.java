package io.github.deadly447.h9ops.aprs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Heard stations and the raw packet log. Not thread-safe: use from one thread. */
public final class StationStore {
    public static final int KEEP_LOG = 1000;
    /** Most stations kept; the one heard longest ago goes first. */
    public static final int MAX_STATIONS = 500;
    /** A packet heard again within this long, by another route, is marked "again". */
    public static final long SAME_PACKET_MS = 60_000;

    private final Map<String, Station> stations = new LinkedHashMap<>();
    private final Deque<Station.Heard> log = new ArrayDeque<>();
    /** Telemetry definitions by the station they define (they can arrive before its first report). */
    private final Map<String, Telemetry.Defs> defs = new LinkedHashMap<>();
    private long lastPacketTime;

    /** Decodes and records one AX.25 frame. Returns the packet, or null if it was not a UI frame. */
    public AprsPacket add(long now, byte[] ax25) {
        Ax25 f = Ax25.parse(ax25);
        if (f == null) return null;
        AprsPacket p = AprsParser.parse(f);
        add(now, p);
        return p;
    }

    public void add(long now, AprsPacket p) {
        lastPacketTime = now;
        String key = p.name == null || p.name.isEmpty() ? p.sender : p.name;
        Station s = stations.get(key);
        if (s == null) {
            if (stations.size() >= MAX_STATIONS) dropOldest();
            s = new Station(key);
            stations.put(key, s);
        }
        s.add(now, p);
        if (p.type == AprsPacket.Type.TELEMETRY && p.telemetryFor != null && !p.telemetryFor.isEmpty()) {
            String target = p.telemetryFor.toUpperCase(java.util.Locale.US);
            Telemetry.Defs d = defs.get(target);
            if (d == null) {
                if (defs.size() >= MAX_STATIONS) defs.remove(defs.keySet().iterator().next());
                d = new Telemetry.Defs();
                defs.put(target, d);
            }
            d.apply(p.telemetry);
            Station t = stations.get(target);
            if (t != null) t.telemetryDefs = d;
        }
        if (s.telemetryDefs == null) s.telemetryDefs = defs.get(key.toUpperCase(java.util.Locale.US));
        Station.Heard h = new Station.Heard(now, p);
        h.again = heardBefore(now, p);
        log.addFirst(h);
        while (log.size() > KEEP_LOG) log.removeLast();
    }

    private boolean heardBefore(long now, AprsPacket p) {
        if (p.content == null) return false;
        for (Station.Heard o : log) {
            if (now - o.time > SAME_PACKET_MS) break;          // newest first
            if (p.content.equals(o.packet.content) && p.sender != null && p.sender.equals(o.packet.sender)) return true;
        }
        return false;
    }

    private void dropOldest() {
        String oldest = null;
        long t = Long.MAX_VALUE;
        for (Map.Entry<String, Station> e : stations.entrySet()) {
            if (e.getValue().lastHeard < t) { t = e.getValue().lastHeard; oldest = e.getKey(); }
        }
        if (oldest != null) stations.remove(oldest);
    }

    /** Records a position a station sent us inside a message (not added to the packet log). */
    public void addShared(long time, AprsPacket p) {
        Station s = stations.get(p.name);
        if (s == null) {
            if (stations.size() >= MAX_STATIONS) dropOldest();
            s = new Station(p.name);
            stations.put(p.name, s);
        }
        s.sharedPosition(time, p);
    }

    /** Stations, most recently heard first. */
    public List<Station> stations() {
        List<Station> l = new ArrayList<>(stations.values());
        Collections.sort(l, (a, b) -> Long.compare(b.lastHeard, a.lastHeard));
        return l;
    }

    public Station get(String name) { return stations.get(name); }

    public List<Station.Heard> log() { return new ArrayList<>(log); }

    public int size() { return stations.size(); }

    public long lastPacketTime() { return lastPacketTime; }

    public void clear() {
        stations.clear();
        log.clear();
        defs.clear();
    }
}
