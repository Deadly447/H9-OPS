package io.github.deadly447.h9ops.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class MessengerTest {

    /** Two phones on one simulated channel: whatever one transmits, the other hears. */
    static final class Phone implements Messenger.Config, Messenger.Radio {
        final String call;
        final List<byte[]> sent = new ArrayList<>();
        boolean connected = true;
        boolean quiet;
        Messenger m;

        Phone(String call) { this.call = call; m = new Messenger(this, this); }
        @Override public String myCall() { return call; }
        @Override public List<String> path() { return Arrays.asList("WIDE1-1", "NOGATE"); }
        @Override public boolean quiet() { return quiet; }
        @Override public boolean transmit(byte[] f) { if (!connected) return false; sent.add(f); return true; }

        /** Decode what we sent, through the same KISS and AX.25 code the radio link uses. */
        List<AprsPacket> drain() {
            List<AprsPacket> out = new ArrayList<>();
            Kiss k = new Kiss();
            for (byte[] f : sent) for (byte[] ax : k.feed(Kiss.encode(f))) out.add(AprsParser.parse(Ax25.parse(ax)));
            sent.clear();
            return out;
        }
    }

    static void deliver(Phone from, Phone to, long now) {
        for (AprsPacket p : from.drain()) to.m.onPacket(p, now);
    }

    @Test
    public void frameOnTheAir() {
        Phone a = new Phone("N0CALL-7");
        a.m.send("n0call-5", "Hello from the trail", 0);
        AprsPacket p = a.drain().get(0);
        assertEquals("N0CALL-7>APZH9M,WIDE1-1,NOGATE::N0CALL-5 :Hello from the trail{1", p.raw);
        assertEquals(AprsPacket.Type.MESSAGE, p.type);
        assertEquals("N0CALL-5", p.msgTo);
        assertEquals("1", p.msgId);
    }

    @Test
    public void sendAckDelivered() {
        Phone a = new Phone("N0CALL-7"), b = new Phone("N0CALL-5");
        Messenger.Msg out = a.m.send("N0CALL-5", "Meet at the trailhead", 0);
        deliver(a, b, 1000);
        List<Messenger.Msg> got = b.m.messages("N0CALL-7");
        assertEquals(1, got.size());
        assertEquals("Meet at the trailhead", got.get(0).text);
        assertTrue(b.sent.isEmpty());              // ack waits for the sender's radio to listen again
        b.m.tick(1000 + Messenger.ACK_DELAY_MS);
        deliver(b, a, 5000);                       // b's ack
        assertEquals(Messenger.State.DELIVERED, out.state);
        a.m.tick(100_000);                         // no more retries once acked
        assertTrue(a.sent.isEmpty());
    }

    @Test
    public void retriesThenFails() {
        Phone a = new Phone("N0CALL-7");
        Messenger.Msg out = a.m.send("N0CALL-5", "anyone?", 0);
        long t = 0;
        for (int i = 0; i < 10; i++) { t += 300_000; a.m.tick(t); }
        assertEquals(5, a.drain().size());
        assertEquals(Messenger.State.FAILED, out.state);
    }

    @Test
    public void duplicateShownOnceButAckedEachTime() {
        Phone a = new Phone("N0CALL-7"), b = new Phone("N0CALL-5");
        a.m.send("N0CALL-5", "copy?", 0);
        List<AprsPacket> first = a.drain();
        assertNotNull(b.m.onPacket(first.get(0), 1000));
        b.m.tick(10_000);
        assertNull(b.m.onPacket(first.get(0), 31_000));   // the retry: not shown again
        b.m.tick(40_000);
        assertEquals(1, b.m.messages("N0CALL-7").size());
        assertEquals(2, b.drain().size());                 // but acked both times
    }

    @Test
    public void notSentWhileDisconnected() {
        Phone a = new Phone("N0CALL-7");
        a.connected = false;
        Messenger.Msg out = a.m.send("N0CALL-5", "later", 0);
        assertEquals(0, out.sends);
        a.connected = true;
        a.m.tick(10_000);
        assertEquals(1, out.sends);
    }

    @Test
    public void ignoresOthersAndOwnEcho() {
        Phone a = new Phone("N0CALL-7"), b = new Phone("N0CALL-5"), c = new Phone("W1AW");
        a.m.send("W1AW", "not for b", 0);
        for (AprsPacket p : a.drain()) {
            assertNull(b.m.onPacket(p, 1));       // addressed to someone else
            assertNull(a.m.onPacket(p, 1));       // our own, heard via a digipeater
            assertNotNull(c.m.onPacket(p, 1));
        }
    }

    @Test
    public void textIsCleanedAndLimited() {
        assertEquals("abcd e", Messenger.cleanText("a|b~c{d\ne"));
        assertEquals(67, Messenger.cleanText(new String(new char[100]).replace('\0', 'x')).length());
    }

    @Test
    public void saveAndLoad() {
        Phone a = new Phone("N0CALL-7");
        a.m.send("N0CALL-5", "saved", 0);
        List<String> lines = a.m.save();
        Phone a2 = new Phone("N0CALL-7");
        a2.m.load(lines, 5000);
        assertEquals("saved", a2.m.messages("N0CALL-5").get(0).text);
        Messenger.Msg next = a2.m.send("N0CALL-5", "next", 6000);
        assertEquals("2", next.id);
    }

    @Test
    public void ackFloodIsLimited() {
        Phone me = new Phone("N0CALL-7");
        for (int i = 0; i < 200; i++) me.m.onPacket(TestPackets.msg("N" + (i % 10) + "CALL", "N0CALL-7", "x{" + i), i);
        me.m.tick(100_000);
        assertTrue("acks " + me.sent.size(), me.sent.size() <= Messenger.ACKS_TOTAL);
        Phone me2 = new Phone("N0CALL-7");
        for (int i = 0; i < 20; i++) me2.m.onPacket(TestPackets.msg("W1AW", "N0CALL-7", "x{" + i), i);
        me2.m.tick(100_000);
        assertEquals(Messenger.ACKS_PER_SENDER, me2.sent.size());
    }

    @Test
    public void thirdPartyValidatedAndNotAcked() {
        Phone me = new Phone("N0CALL-7");
        AprsPacket bad = TestPackets.tnc2("IGATE>APRS:}EVIL\tX>APRS,TCPIP,IGATE*::N0CALL-7 :hello{7");
        assertNull(me.m.onPacket(bad, 1));
        AprsPacket ok = TestPackets.tnc2("IGATE>APRS:}W1AW>APRS,TCPIP,IGATE*::N0CALL-7 :hello{7");
        Messenger.Msg m = me.m.onPacket(ok, 1);
        assertNotNull(m);
        assertTrue(m.viaInternet);
        me.m.tick(100_000);
        assertTrue(me.sent.isEmpty());
    }

    @Test
    public void storageIsCapped() {
        Phone me = new Phone("N0CALL-7");
        for (int i = 0; i < Messenger.MAX_THREADS + 50; i++) {
            me.m.onPacket(TestPackets.msg("A" + i, "N0CALL-7", "hi{1"), i);
        }
        assertEquals(Messenger.MAX_THREADS, me.m.peers().size());
        StationStore st = new StationStore();
        for (int i = 0; i < StationStore.MAX_STATIONS + 100; i++) st.add(i, TestPackets.msg("B" + i, "X", "y"));
        assertEquals(StationStore.MAX_STATIONS, st.size());
    }

    @Test
    public void lineBreaksDoNotCorruptTheFile() {
        Phone me = new Phone("N0CALL-7");
        me.m.onPacket(TestPackets.msg("W1AW", "N0CALL-7", "two\nlines{3"), 0);
        List<String> lines = Arrays.asList(String.join("\n", me.m.save()).split("\n"));
        Phone me2 = new Phone("N0CALL-7");
        me2.m.load(lines, 0);
        assertEquals("two lines", me2.m.messages("W1AW").get(0).text);
        assertEquals(1, me2.m.peers().size());
    }

    @Test
    public void quietModeDoesNotAckStrangers() {
        Phone a = new Phone("N0CALL-7"), b = new Phone("N0CALL-5");
        b.quiet = true;
        a.m.send("N0CALL-5", "Are you out there?", 0);
        deliver(a, b, 1000);
        b.m.tick(10_000);
        assertTrue("no ack to a stranger in quiet mode", b.sent.isEmpty());
        assertTrue(b.m.messages("N0CALL-7").get(0).notAcked);
        // once we reply, the station is known: its retries are acked
        b.m.send("N0CALL-7", "Yes", 20_000);
        b.drain();
        a.m.tick(31_000);                 // a retries its message
        deliver(a, b, 32_000);
        b.m.tick(40_000);
        boolean acked = false;
        for (AprsPacket p : b.drain()) if (p.msgText != null && p.msgText.startsWith("ack")) acked = true;
        assertTrue("known station is acked", acked);
    }

    @Test
    public void developerEventsCoverTheExchangeAndNeverHoldText() {
        Phone a = new Phone("N0CALL-7"), b = new Phone("N0CALL-5");
        List<String> ea = new ArrayList<>(), eb = new ArrayList<>();
        a.m.setEvents(ea::add);
        b.m.setEvents(eb::add);
        a.m.send("N0CALL-5", "secret meeting point alpha", 0);
        deliver(a, b, 1000);
        b.m.tick(10_000);                 // b's ack goes out
        deliver(b, a, 11_000);
        String all = String.join("\n", ea) + "\n" + String.join("\n", eb);
        assertTrue(all, all.contains("queued to N0CALL-5 #"));
        assertTrue(all, all.contains("sent to N0CALL-5 #"));
        assertTrue(all, all.contains("received from N0CALL-7 #"));
        assertTrue(all, all.contains("ack queued"));
        assertTrue(all, all.contains("ack sent to N0CALL-7"));
        assertTrue(all, all.contains("DELIVERED to N0CALL-5 #"));
        assertTrue(all, !all.contains("secret") && !all.contains("alpha"));
    }
}
