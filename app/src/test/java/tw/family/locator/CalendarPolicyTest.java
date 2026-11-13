package tw.family.locator;

import org.junit.Test;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.Assert.*;

public final class CalendarPolicyTest {
    private static long time(String value) { return Instant.parse(value).toEpochMilli(); }

    @Test public void allOfferedReminderLeadTimesAreAccepted() {
        for (int value : new int[]{0, 5, 10, 15, 30, 60, 1440}) assertTrue(CalendarPolicy.isReminderSupported(value));
        assertFalse(CalendarPolicy.isReminderSupported(-1));
        assertFalse(CalendarPolicy.isReminderSupported(7));
    }

    @Test public void invalidEventsCannotBeSaved() {
        long start = time("2026-11-13T01:00:00Z");
        assertNotNull(CalendarPolicy.validationError(" ", "", "", start, start + 1000, 15));
        assertNotNull(CalendarPolicy.validationError("活動", "", "", start, start, 15));
        assertNotNull(CalendarPolicy.validationError("活動", "", "", start, start - 1000, 15));
        assertNotNull(CalendarPolicy.validationError("活動", "", "", start, start + 1000, 99));
        assertNull(CalendarPolicy.validationError("活動", "", "", start, start + 1000, 15));
    }

    @Test public void characterLimitsCountWholeUnicodeCharacters() {
        String emoji = new String(Character.toChars(0x1f44b));
        long now = time("2026-11-13T01:00:00Z");
        assertNull(CalendarPolicy.validationError(emoji.repeat(120), "", "", now, now + 1000, 0));
        assertNotNull(CalendarPolicy.validationError(emoji.repeat(121), "", "", now, now + 1000, 0));
    }

    @Test public void midnightEndBelongsOnlyToThePreviousDay() {
        long start = time("2026-11-12T15:30:00Z"); // 11/12 23:30 Taipei
        long end = time("2026-11-12T16:00:00Z");
        assertTrue(CalendarPolicy.occursOnDay(start, end, LocalDate.of(2026, 11, 12)));
        assertFalse(CalendarPolicy.occursOnDay(start, end, LocalDate.of(2026, 11, 13)));
    }

    @Test public void multiDayEventsAppearOnEachOverlappedDay() {
        long start = time("2026-11-12T15:30:00Z"), end = time("2026-11-14T02:00:00Z");
        assertTrue(CalendarPolicy.occursOnDay(start, end, LocalDate.of(2026, 11, 12)));
        assertTrue(CalendarPolicy.occursOnDay(start, end, LocalDate.of(2026, 11, 13)));
        assertTrue(CalendarPolicy.occursOnDay(start, end, LocalDate.of(2026, 11, 14)));
    }

    @Test public void reminderDoesNotFireEarlyOrAfterAnEventEnds() {
        long start = time("2026-11-13T01:00:00Z"), end = start + 3_600_000L;
        long due = CalendarPolicy.reminderAt(start, 15);
        assertEquals(start - 900_000L, due);
        assertFalse(CalendarPolicy.canDeliver(due, end, due - 1, false));
        assertTrue(CalendarPolicy.canDeliver(due, end, due, false));
        assertFalse(CalendarPolicy.canDeliver(due, end, end, false));
        assertFalse(CalendarPolicy.canDeliver(due, end, due, true));
    }

    @Test public void syncRangesHaveBoundariesRatherThanOverflowing() {
        long start = time("2026-11-13T01:00:00Z");
        assertTrue(CalendarPolicy.isSyncRangeValid(start, start + CalendarPolicy.MAX_SYNC_RANGE_MS));
        assertFalse(CalendarPolicy.isSyncRangeValid(start, start + CalendarPolicy.MAX_SYNC_RANGE_MS + 1));
        assertFalse(CalendarPolicy.isSyncRangeValid(start, start));
        assertFalse(CalendarPolicy.isSyncRangeValid(-1, start));
    }

    @Test public void timestampsAcceptRealTimezoneOffsetsAndRejectInvalidData() {
        assertEquals(time("2026-11-13T01:00:00Z"), CalendarPolicy.parseTime("2026-11-13T09:00:00+08:00"));
        assertEquals(0, CalendarPolicy.parseTime("not-a-date"));
        assertEquals(0, CalendarPolicy.parseTime(""));
    }
}
