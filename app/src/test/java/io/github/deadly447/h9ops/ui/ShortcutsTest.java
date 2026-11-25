package io.github.deadly447.h9ops.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ShortcutsTest {
    @Test
    public void defaultRows() {
        Shortcuts s = new Shortcuts();
        assertEquals(Arrays.asList(Shortcuts.Item.STATIONS, Shortcuts.Item.MESSAGES, Shortcuts.Item.LOG, Shortcuts.Item.MAP), s.row(0));
        assertEquals(Arrays.asList(Shortcuts.Item.WEATHER, Shortcuts.Item.TRACKS, Shortcuts.Item.OBJECTS, Shortcuts.Item.ALERTS), s.row(1));
        assertEquals(Arrays.asList(Shortcuts.Item.BULLETINS, Shortcuts.Item.RECORD, Shortcuts.Item.MAP_FILE, Shortcuts.Item.OVERLAYS), s.menu());
        assertEquals("Row 1", Shortcuts.place(3));
        assertEquals("Row 2", Shortcuts.place(4));
        assertEquals("⋮ menu", Shortcuts.place(8));
    }

    @Test
    public void moveAcrossRowsAndBack() {
        Shortcuts s = new Shortcuts();
        assertTrue(s.move(8, -1));           // Bulletins up into row 2, Alerts down into the menu
        assertEquals(Shortcuts.Item.BULLETINS, s.row(1).get(3));
        assertEquals(Shortcuts.Item.ALERTS, s.menu().get(0));
        assertFalse(s.move(0, -1));          // nothing above the first
        assertFalse(s.move(11, 1));          // nothing below the last
        s.reset();
        assertEquals(new Shortcuts().save(), s.save());
    }

    @Test
    public void savedTextSurvivesUnknownAndMissingItems() {
        Shortcuts s = new Shortcuts();
        s.move(3, 1);                        // Map into row 2, Weather into row 1
        Shortcuts t = Shortcuts.parse(s.save());
        assertEquals(s.save(), t.save());
        // an old list without newer items, with a name this version doesn't know and a repeat
        Shortcuts u = Shortcuts.parse("MAP, stations, SOMETHING_NEW, MAP, LOG");
        assertEquals(Shortcuts.Item.MAP, u.order().get(0));
        assertEquals(Shortcuts.Item.STATIONS, u.order().get(1));
        assertEquals(Shortcuts.Item.LOG, u.order().get(2));
        assertEquals(Shortcuts.Item.values().length, u.order().size());
        assertEquals(new Shortcuts().save(), Shortcuts.parse("").save());
        assertEquals(new Shortcuts().save(), Shortcuts.parse(null).save());
    }
}
