package tw.family.locator;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReportWeekdaysTest {
    private static long taipei(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli();
    }

    @Test public void isoBitsMatchMondayThroughSundayAndPresetMasks() {
        long monday = taipei("2026-11-16T12:00:00");
        for (int iso = 1; iso <= 7; iso++) {
            long date = monday + (iso - 1) * 86_400_000L;
            for (int selected = 1; selected <= 7; selected++) {
                assertEquals(iso == selected, ReportWeekdays.isSelectedDay(date, 1 << (selected - 1)));
            }
            assertEquals(iso <= 5, ReportWeekdays.isSelectedDay(date, ReportWeekdays.WEEKDAYS));
            assertEquals(iso >= 6, ReportWeekdays.isSelectedDay(date, ReportWeekdays.WEEKENDS));
        }
    }

    @Test public void fridayToMondaySkipsExcludedWeekendAtTaipeiMidnight() {
        long lastFridayInstant = taipei("2026-11-20T23:59:59.999");
        assertEquals(lastFridayInstant, ReportWeekdays.nextAllowedAt(lastFridayInstant, ReportWeekdays.WEEKDAYS));
        assertEquals(taipei("2026-11-23T00:00:00"),
                ReportWeekdays.nextAllowedAt(lastFridayInstant + 1, ReportWeekdays.WEEKDAYS));
    }

    @Test public void sundayToSaturdayWrapsToTheNextWeek() {
        int saturdayOnly = 1 << 5;
        assertEquals(taipei("2026-11-28T00:00:00"), ReportWeekdays.nextAllowedAt(
                taipei("2026-11-22T16:30:00"), saturdayOnly));
    }

    @Test public void allDaysKeepsTimeAndOldMissingOrEmptyMaskDefaultsToAll() {
        long now = taipei("2026-11-22T16:30:45.123");
        assertEquals(now, ReportWeekdays.nextAllowedAt(now, ReportWeekdays.ALL));
        assertEquals(ReportWeekdays.ALL, ReportWeekdays.sanitizeMask(0));
        assertEquals(ReportWeekdays.ALL, ReportWeekdays.sanitizeMask(128));
        assertEquals(ReportWeekdays.ALL, ReportWeekdays.sanitizeMask(-1));
        assertEquals(1, ReportWeekdays.sanitizeMask(129));
        assertFalse(ReportWeekdays.isValidMask(0));
        assertFalse(ReportWeekdays.isValidMask(128));
        assertFalse(ReportWeekdays.isValidMask(-1));
        assertTrue(ReportWeekdays.isValidMask(127));
    }

    @Test(expected = IllegalArgumentException.class)
    public void savingNoDaysIsRejectedInsteadOfSilentlyDisablingReminders() {
        ReportWeekdays.requireMask(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownBitsCannotBeSaved() {
        ReportWeekdays.requireMask(129);
    }

    @Test public void snoozeCrossingFridayMidnightWaitsUntilMonday() {
        long friday = taipei("2026-11-20T23:55:00");
        assertEquals(taipei("2026-11-23T00:00:00"),
                ReportWeekdays.snoozeDue(friday, ReportWeekdays.WEEKDAYS));
        assertEquals(taipei("2026-11-21T00:05:00"),
                ReportWeekdays.snoozeDue(friday, ReportWeekdays.WEEKENDS));
        assertEquals(taipei("2026-11-20T16:10:00"), ReportWeekdays.snoozeDue(
                taipei("2026-11-20T16:00:00"), ReportWeekdays.WEEKDAYS));
    }

    @Test public void lateAlarmOnExcludedDayIsMovedEvenWhenStoredDeadlineWasFriday() {
        long now = taipei("2026-11-21T08:00:00");
        long missed = taipei("2026-11-20T23:00:00");
        assertEquals(taipei("2026-11-23T00:00:00"),
                ReportWeekdays.reminderDue(missed, now, 60, ReportWeekdays.WEEKDAYS));
        assertEquals(taipei("2026-11-23T00:00:00"),
                ReportWeekdays.reminderDue(0, now, 60, ReportWeekdays.WEEKDAYS));
    }

    @Test public void selectedDayOverdueDeadlineRemainsDueWithoutCatchUpBursts() {
        long now = taipei("2026-11-23T08:00:00");
        long missed = taipei("2026-11-23T07:00:00");
        assertEquals(missed, ReportWeekdays.reminderDue(missed, now, 60, ReportWeekdays.WEEKDAYS));
        ReportCyclePolicy.Window claim = ReportCyclePolicy.claim(missed, now, 60);
        assertNotNull(claim);
        assertEquals(taipei("2026-11-23T09:00:00"),
                ReportWeekdays.nextAllowedAt(claim.nextDue, ReportWeekdays.WEEKDAYS));
        assertNull(ReportCyclePolicy.claim(claim.nextDue, now, 60));
    }

    @Test public void taipeiDateIgnoresPhoneOrJvmDefaultTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            long saturdayInTaipei = Instant.parse("2026-11-20T16:00:00Z").toEpochMilli();
            assertFalse(ReportWeekdays.isSelectedDay(saturdayInTaipei, ReportWeekdays.WEEKDAYS));
            assertTrue(ReportWeekdays.isSelectedDay(saturdayInTaipei, ReportWeekdays.WEEKENDS));
            assertTrue(ReportWeekdays.isSelectedDay(saturdayInTaipei - 1, ReportWeekdays.WEEKDAYS));
            assertEquals(taipei("2026-11-23T00:00:00"),
                    ReportWeekdays.nextAllowedAt(saturdayInTaipei, ReportWeekdays.WEEKDAYS));
        } finally { TimeZone.setDefault(original); }
    }

    @Test public void everyNonemptyMaskReturnsItsEarliestSelectedDate() {
        long monday = taipei("2026-11-16T13:15:00");
        for (int mask = 1; mask <= ReportWeekdays.ALL; mask++) {
            for (int day = 0; day < 14; day++) {
                long candidate = monday + day * 86_400_000L;
                long next = ReportWeekdays.nextAllowedAt(candidate, mask);
                assertTrue(next >= candidate);
                assertTrue(next - candidate < 7 * 86_400_000L);
                assertTrue(ReportWeekdays.isSelectedDay(next, mask));
                if (next != candidate) {
                    assertFalse(ReportWeekdays.isSelectedDay(candidate, mask));
                    assertFalse(ReportWeekdays.isSelectedDay(next - 1, mask));
                }
            }
        }
    }

    @Test public void reminderExpiresAtTheNextTaipeiMidnight() {
        long now = Instant.parse("2026-11-20T15:59:59.999Z").toEpochMilli();
        assertEquals(now + 1, ReportWeekdays.nextMidnightAt(now));
        assertEquals(taipei("2026-11-22T00:00:00"),
                ReportWeekdays.nextMidnightAt(taipei("2026-11-21T00:00:00")));
    }
}
