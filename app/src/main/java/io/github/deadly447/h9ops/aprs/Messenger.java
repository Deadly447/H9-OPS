package io.github.deadly447.h9ops.aprs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * APRS text messaging (APRS 1.0.1 chapter 14): sends messages with an ID,
 * retries until the other station acknowledges, and acknowledges messages
 * addressed to us. Pure Java, driven by {@link #tick(long)}; not thread-safe.
 */
public final class Messenger {
    public static final int MAX_TEXT = 67;
    public static final String TOCALL = "APZH9M";       // APZ = experimental software
    static final int MAX_SENDS = 5;
    /** Wait after send n for an ack before sending again (the last wait ends in FAILED). */
    static final long[] RETRY_AFTER_MS = {30_000, 60_000, 120_000, 240_000};
    static final long NOT_CONNECTED_RETRY_MS = 10_000;
    static final long DUP_WINDOW_MS = 30 * 60_000;
    /**
     * Wait before acking. The TD-H9 is deaf for a moment after it transmits,
     * so an ack sent straight back (while the sender's radio is still
     * switching from transmit to receive) is never heard there.
     */
    static final long ACK_DELAY_MS = 3_000;
    /** Ack limits, so spoofed messages can't key our transmitter over and over. */
    static final long ACK_WINDOW_MS = 60_000;
    static final int ACKS_PER_SENDER = 3, ACKS_TOTAL = 10;
    /** Storage limits, so a flood of messages can't fill memory or the phone. */
    static final int MAX_THREADS = 200, MAX_PER_THREAD = 300, MAX_SEEN = 2000;

    public enum State { SENDING, DELIVERED, FAILED, REJECTED, RECEIVED }

    public static final class Msg {
        public final long time;
        public final String peer;
        public final boolean outgoing;
        public final String text;
        /** Arrived through an iGate (third-party packet), not straight over the air. */
        public boolean viaInternet;
        /** Received in quiet mode from a station we don't know: shown, but not acknowledged. */
        public boolean notAcked;
        public final String id;     // null for messages sent without an ID
        public State state;
        public int sends;           // transmissions so far (outgoing)
        public long nextSend;
        public long stateTime;

        Msg(long time, String peer, boolean outgoing, String text, String id, State state) {
            this.time = time;
            this.peer = peer;
            this.outgoing = outgoing;
            this.text = text;
            this.id = id;
            this.state = state;
            this.stateTime = time;
        }

        /** Who wrote it: the other station for incoming messages. */
        public String from() {
            return peer;
        }
    }

    /** Hands a finished AX.25 frame to the radio. Returns false if it could not be sent now. */
    public interface Radio {
        boolean transmit(byte[] ax25);
    }

    /** Settings the messenger reads each time it sends. */
    public interface Config {
        String myCall();          // e.g. N0CALL-7, upper case; null = not set
        List<String> path();      // e.g. [WIDE1-1, NOGATE]
        /**
         * Quiet mode: acknowledge only stations we already know (we have sent
         * them a message). An ack is a transmission,
         * so without this anyone can make the radio transmit, which confirms
         * we're on the air and gives direction-finding something to locate.
         */
        default boolean quiet() { return false; }
    }

    private final Config cfg;
    private final Radio radio;
    private final Map<String, List<Msg>> threads = new LinkedHashMap<>();
    private final Map<String, Long> seen = new HashMap<>();   // "FROM|id" -> time first heard
    private final List<PendingAck> acks = new ArrayList<>();
    private final Deque<long[]> ackLog = new ArrayDeque<>();   // {time, peerHash} of acks queued
    private int nextId = 1;

    private static final class PendingAck {
        final String to, text;
        long due;
        PendingAck(String to, String text, long due) { this.to = to; this.text = text; this.due = due; }
    }

    public Messenger(Config cfg, Radio radio) {
        this.cfg = cfg;
        this.radio = radio;
    }

    // ------------------------------------------------------------------ sending

    /** Cleans text the way APRS requires: printable ASCII, no | ~ {, at most 67 characters. */
    public static String cleanText(String t) {
        StringBuilder sb = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c < 0x20 || c > 0x7E || c == '|' || c == '~' || c == '{') continue;
            sb.append(c);
        }
        String s = sb.toString().trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    // ------------------------------------------------------------------ developer log

    /** One line per messaging event, for the developer log. Never given message text. */
    public interface Events { void event(String what); }
    private Events events = w -> { };
    public void setEvents(Events e) { events = e == null ? w -> { } : e; }

    private static String desc(Msg m) {
        return (m.outgoing ? "to " : "from ") + m.peer
                + (m.id != null ? " #" + m.id : " (no id)")
                + (PositionShare.decode(m.text) != null ? " position" : "");
    }

    private static String secs(long ms) { return ms < 10_000 ? (ms / 100) / 10.0 + " s" : (ms / 1000) + " s"; }

    /** Queues a message and sends it right away if the radio is connected. */
    public Msg send(String to, String text, long now) {
        String peer = to.trim().toUpperCase(Locale.US);
        if (!Ax25.validCall(peer)) throw new IllegalArgumentException("Not a valid callsign: " + to);
        String me = cfg.myCall();
        if (me == null) throw new IllegalStateException("Set your callsign first");
        String body = cleanText(text);
        if (body.isEmpty()) throw new IllegalArgumentException("Message is empty");
        String id = Integer.toString(nextId);
        nextId = nextId >= 999 ? 1 : nextId + 1;
        Msg m = new Msg(now, peer, true, body, id, State.SENDING);
        add(m);
        events.event("queued " + desc(m) + ", " + body.length() + " chars");
        m.nextSend = now;
        tick(now);
        return m;
    }

    /** Sends any message or ack that is due; call every second or so. Returns true if anything changed. */
    public boolean tick(long now) {
        boolean changed = false;
        for (Iterator<PendingAck> it = acks.iterator(); it.hasNext(); ) {
            PendingAck a = it.next();
            if (a.due > now) continue;
            if (transmit(a.to, a.text)) { it.remove(); events.event("ack sent to " + a.to + " (" + ackId(a.text) + ")"); }
            else if (now - a.due > 5 * 60_000) { it.remove(); events.event("ack to " + a.to + " dropped: radio not connected for 5 min"); }     // couldn't send for 5 minutes: drop
            else a.due = now + NOT_CONNECTED_RETRY_MS;
        }
        for (List<Msg> l : threads.values()) {
            for (Msg m : l) {
                if (!m.outgoing || m.state != State.SENDING || m.nextSend > now) continue;
                if (m.sends >= MAX_SENDS) {
                    m.state = State.FAILED;
                    m.stateTime = now;
                    events.event("FAILED " + desc(m) + ": no ack after " + m.sends + " sends, " + secs(now - m.time));
                } else if (transmit(m.peer, m.text + "{" + m.id)) {
                    m.sends++;
                    m.nextSend = now + RETRY_AFTER_MS[Math.min(m.sends - 1, RETRY_AFTER_MS.length - 1)];
                    events.event("sent " + desc(m) + ", try " + m.sends + " of " + MAX_SENDS
                            + (m.sends < MAX_SENDS ? ", next in " + secs(m.nextSend - now) : ""));
                } else {
                    events.event("waiting " + desc(m) + ": radio not connected, next try in " + secs(NOT_CONNECTED_RETRY_MS));
                    m.nextSend = now + NOT_CONNECTED_RETRY_MS;
                }
                changed = true;
            }
        }
        return changed;
    }

    /** Sends a failed message again. */
    public void retry(Msg m, long now) {
        if (!m.outgoing) return;
        events.event("retry " + desc(m) + " (by hand)");
        m.state = State.SENDING;
        m.sends = 0;
        m.nextSend = now;
        m.stateTime = now;
        tick(now);
    }

    public void cancel(Msg m, long now) {
        if (m.outgoing && m.state == State.SENDING) {
            m.state = State.FAILED;
            m.stateTime = now;
            events.event("cancelled " + desc(m) + " after " + m.sends + " sends");
        }
    }

    private static String ackId(String ackText) {
        return ackText;
    }

    private boolean transmit(String to, String text) {
        String me = cfg.myCall();
        if (me == null || !Ax25.validCall(to)) return false;
        String info = ":" + pad9(to) + ":" + text;
        byte[] frame;
        try {
            frame = Ax25.encode(me, TOCALL, cfg.path(), info.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return radio.transmit(frame);
    }

    static String pad9(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 9) sb.append(' ');
        return sb.substring(0, 9);
    }

    /** Queues an ack unless this sender (or everyone together) has used up the ack allowance. */
    private void queueAck(String to, String text, long now) {
        for (PendingAck a : acks) if (a.to.equals(to) && a.text.equals(text)) return;
        while (!ackLog.isEmpty() && now - ackLog.peekFirst()[0] > ACK_WINDOW_MS) ackLog.removeFirst();
        int mine = 0;
        long h = to.hashCode();
        for (long[] e : ackLog) if (e[1] == h) mine++;
        if (mine >= ACKS_PER_SENDER || ackLog.size() >= ACKS_TOTAL) {
            events.event("ack to " + to + " not sent: ack limit reached (" + (mine >= ACKS_PER_SENDER ? "this sender" : "everyone") + ")");
            return;
        }
        ackLog.addLast(new long[]{now, h});
        acks.add(new PendingAck(to, text, now + ACK_DELAY_MS));
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Looks at every decoded packet. Returns the new incoming message if this
     * packet is one addressed to us that we have not seen before, else null.
     */
    public Msg onPacket(AprsPacket p, long now) {
        String me = cfg.myCall();
        if (me == null || p.type != AprsPacket.Type.MESSAGE || p.msgTo == null || p.sender == null) return null;
        if (!p.msgTo.trim().equalsIgnoreCase(me)) return null;
        String from = p.sender.toUpperCase(Locale.US);
        if (!Ax25.validCall(from)) return null;                  // nothing we can show or answer safely
        if (from.equalsIgnoreCase(me)) return null;              // our own packet, digipeated back
        String text = p.msgText == null ? "" : p.msgText;

        if (isAck(text, p.msgId)) {
            onAck(from, text, now);
            return null;
        }

        String id = p.msgId;
        if (id != null) {
            int brace = id.indexOf('}');   // reply-ack: "{MM}AA" carries an ack for our AA
            if (brace >= 0) {
                String acked = id.substring(brace + 1);
                id = id.substring(0, brace);
                for (Msg m : messages(from)) {
                    if (m.outgoing && acked.equals(m.id) && m.state == State.SENDING) {
                        m.state = State.DELIVERED;
                        m.stateTime = now;
                        events.event("DELIVERED " + desc(m) + " (reply-ack) after " + m.sends + " sends, " + secs(now - m.time));
                    }
                }
            }
            if (id.isEmpty() || id.length() > 5) return null;
        }

        Msg m = new Msg(now, from, false, text, id, State.RECEIVED);
        m.viaInternet = p.thirdParty;

        // Ack messages that came over the air, not ones an iGate relayed from the internet.
        boolean ackIt = id != null && !p.thirdParty;
        if (ackIt && cfg.quiet() && !known(from)) {
            ackIt = false;
            m.notAcked = true;
        }
        if (ackIt) {
            String ackText = "ack" + id;
            // Always ack, even duplicates: the sender retries because it missed our ack.
            queueAck(from, ackText, now);
        }

        String key = from + "|" + (id != null ? id : "=" + text);
        Long first = seen.get(key);
        String how = (p.heardVia != null ? " via " + p.heardVia : " direct") + (p.thirdParty ? ", from the internet" : "")
                + (ackIt ? ", ack queued" : m.notAcked ? ", not acked (quiet mode)" : id == null ? ", no id (no ack)" : ", not acked");
        if (first != null && now - first < DUP_WINDOW_MS) {
            events.event("duplicate " + desc(m) + how);
            return null;
        }
        seen.put(key, now);
        if (seen.size() > MAX_SEEN) seen.values().removeIf(t -> now - t > DUP_WINDOW_MS);
        add(m);
        events.event("received " + desc(m) + how);
        return m;
    }

    /** A station we have sent a message to. */
    public boolean known(String peer) {
        for (Msg m : messages(peer)) if (m.outgoing) return true;
        return false;
    }

    /** "ackNN" or "rejNN" (with an optional reply-ack part). */
    private static boolean isAck(String text, String msgId) {
        if (msgId != null) return false;
        String l = text.toLowerCase(Locale.US);
        return (l.startsWith("ack") || l.startsWith("rej")) && text.length() <= 18
                && text.substring(3).matches("[A-Za-z0-9]{1,5}(\\}[A-Za-z0-9]{0,5})?");
    }

    private void onAck(String from, String text, long now) {
        boolean ack = text.toLowerCase(Locale.US).startsWith("ack");
        String rest = text.substring(3).trim();
        int brace = rest.indexOf('}');
        if (brace >= 0) rest = rest.substring(0, brace);
        boolean matched = false;
        for (Msg m : messages(from)) {
            if (!m.outgoing || !rest.equals(m.id)) continue;
            if (!(m.state == State.SENDING || m.state == State.FAILED)) {
                matched = true;
                events.event((ack ? "extra ack" : "extra rej") + " for " + desc(m) + " (already " + m.state.name().toLowerCase(Locale.US) + ")");
                continue;
            }
            matched = true;
            m.state = ack ? State.DELIVERED : State.REJECTED;
            m.stateTime = now;
            events.event((ack ? "DELIVERED " : "REJECTED ") + desc(m) + " after " + m.sends + " sends, " + secs(now - m.time));
        }
        if (!matched) events.event((ack ? "ack" : "rej") + " from " + from + " for #" + rest + ": no such message");
    }

    // ------------------------------------------------------------------ data

    private void add(Msg m) {
        List<Msg> l = threads.get(m.peer);
        if (l == null) {
            if (threads.size() >= MAX_THREADS) {
                // drop the conversation that has been quiet the longest
                String oldest = null;
                long t = Long.MAX_VALUE;
                for (Map.Entry<String, List<Msg>> e : threads.entrySet()) {
                    long last = e.getValue().isEmpty() ? 0 : e.getValue().get(e.getValue().size() - 1).time;
                    if (last < t) { t = last; oldest = e.getKey(); }
                }
                threads.remove(oldest);
            }
            l = new ArrayList<>();
            threads.put(m.peer, l);
        }
        l.add(m);
        while (l.size() > MAX_PER_THREAD) l.remove(0);
    }

    public List<Msg> messages(String peer) {
        List<Msg> l = threads.get(peer);
        return l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
    }

    /** Conversation partners, most recent first. */
    public List<String> peers() {
        List<String> p = new ArrayList<>(threads.keySet());
        p.removeIf(k -> threads.get(k).isEmpty());
        p.sort((a, b) -> Long.compare(last(b), last(a)));
        return p;
    }

    private long last(String peer) {
        List<Msg> l = threads.get(peer);
        return l.isEmpty() ? 0 : l.get(l.size() - 1).time;
    }

    public void deleteThread(String peer) {
        threads.remove(peer);
    }

    public void clear() {
        acks.clear();
        ackLog.clear();
        threads.clear();
        seen.clear();
    }

    // ------------------------------------------------------------------ saving

    private static String field(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** One line per message: time, direction, state, sends, id, peer, text, flags (tab separated). */
    public List<String> save() {
        List<String> out = new ArrayList<>();
        out.add("#nextId\t" + nextId);
        for (List<Msg> l : threads.values()) {
            for (Msg m : l) {
                out.add(m.time + "\t" + (m.outgoing ? "out" : "in") + "\t" + m.state + "\t" + m.sends + "\t"
                        + (m.id == null ? "" : field(m.id)) + "\t" + field(m.peer) + "\t"
                        + field(m.text) + "\t"
                        + (m.viaInternet ? "net" : ""));
            }
        }
        return out;
    }

    public void load(List<String> lines, long now) {
        clear();
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            try {
                if (f[0].equals("#nextId")) { nextId = Integer.parseInt(f[1]); continue; }
                if (f.length < 7 || !Ax25.validCall(f[5])) continue;
                Msg m = new Msg(Long.parseLong(f[0]), f[5], f[1].equals("out"), f[6],
                        f[4].isEmpty() ? null : f[4], State.valueOf(f[2]));
                m.sends = Integer.parseInt(f[3]);
                m.viaInternet = f.length > 7 && f[7].contains("net");
                m.nextSend = now;               // resume any unfinished sends
                add(m);
                if (!m.outgoing && m.id != null)
                    seen.put(m.peer + "|" + m.id, m.time);
            } catch (RuntimeException ignored) {
                // skip damaged line
            }
        }
    }
}
