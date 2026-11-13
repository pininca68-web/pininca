package tw.family.locator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReminderTimingTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void allFiveUserIntervalsAreSupported() {
        for (int minutes : new int[]{30, 60, 120, 180, 240}) {
            assertTrue(ReminderTiming.isSupportedInterval(minutes));
            assertEquals(minutes * 60_000L, ReminderTiming.intervalMillis(minutes));
        }
        assertFalse(ReminderTiming.isSupportedInterval(0));
        assertFalse(ReminderTiming.isSupportedInterval(45));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedIntervalCannotSilentlyBeSaved() {
        ReminderTiming.intervalMillis(45);
    }

    @Test public void changingIntervalStartsAFullNewInterval() {
        assertEquals(NOW + 30 * 60_000L, ReminderTiming.changedIntervalDue(NOW, 30));
        assertEquals(NOW + 240 * 60_000L, ReminderTiming.changedIntervalDue(NOW, 240));
    }

    @Test public void successfulReportPostponesReminderFromItsRealTime() {
        assertEquals(NOW + 60 * 60_000L, ReminderTiming.afterReport(NOW, NOW, 60));
        assertEquals(NOW + 55 * 60_000L,
                ReminderTiming.afterReport(NOW - 5 * 60_000L, NOW, 60));
    }

    @Test public void futureReportTimeIsBoundedToCurrentTime() {
        assertEquals(NOW + 60 * 60_000L,
                ReminderTiming.afterReport(NOW + 86_400_000L, NOW, 60));
    }

    @Test public void overdueRestartSchedulesOneGracePeriodNotABurst() {
        long missedYesterday = NOW - 86_400_000L;
        assertEquals(NOW + ReminderTiming.RESTORE_GRACE_MS,
                ReminderTiming.restoredDue(missedYesterday, NOW, 30));
        assertEquals(NOW + ReminderTiming.RESTORE_GRACE_MS,
                ReminderTiming.restoredDue(NOW, NOW, 30));
    }

    @Test public void rebootKeepsAnAlreadyFutureDeadline() {
        assertEquals(NOW + 15 * 60_000L,
                ReminderTiming.restoredDue(NOW + 15 * 60_000L, NOW, 60));
    }

    @Test public void clockChangeCannotPostponeForDays() {
        assertEquals(NOW + 60 * 60_000L,
                ReminderTiming.restoredDue(NOW + 7 * 86_400_000L, NOW, 60));
    }

    @Test public void delayedDeliveryStartsNextIntervalFromDelivery() {
        long deliveredLate = NOW + 45 * 60_000L;
        assertEquals(deliveredLate + 30 * 60_000L,
                ReminderTiming.afterReminder(deliveredLate, 30));
    }

    @Test public void overflowDoesNotWrapToThePast() {
        assertEquals(Long.MAX_VALUE, ReminderTiming.initialDue(Long.MAX_VALUE - 10, 30));
        assertEquals(Long.MAX_VALUE, ReminderTiming.add(Long.MAX_VALUE, 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeClockIsRejected() {
        ReminderTiming.initialDue(-1, 30);
    }
}
