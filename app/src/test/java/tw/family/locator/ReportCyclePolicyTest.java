package tw.family.locator;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReportCyclePolicyTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void firstReminderWaitsOneWholeSelectedInterval() {
        for (int minutes : new int[]{30, 60, 120, 180, 240}) {
            long due = ReportCyclePolicy.start(NOW, minutes);
            assertEquals(NOW + minutes * 60_000L, due);
            assertNull(ReportCyclePolicy.claim(due, NOW, minutes));
            assertNull(ReportCyclePolicy.claim(due, due - 1, minutes));
            assertNotNull(ReportCyclePolicy.claim(due, due, minutes));
        }
    }

    @Test public void durableAdvancePreventsDuplicateAlarmsClaimingTheSameWindow() {
        ReportCyclePolicy.Window first = ReportCyclePolicy.claim(NOW, NOW, 60);
        assertNotNull(first);
        assertEquals(NOW - 3_600_000L, first.startAt);
        assertEquals(NOW, first.endAt);
        assertEquals(NOW + 3_600_000L, first.nextDue);
        assertNull(ReportCyclePolicy.claim(first.nextDue, NOW, 60));
        assertNull(ReportCyclePolicy.claim(first.nextDue, NOW + 60_000L, 60));
        ReportCyclePolicy.Window second = ReportCyclePolicy.claim(first.nextDue, first.nextDue, 60);
        assertNotNull(second);
        assertEquals(first.endAt, second.startAt);
    }

    @Test public void delayedAlarmUsesThePastSelectedDurationWithoutCatchUpBursts() {
        long delivered = NOW + 5 * 3_600_000L;
        ReportCyclePolicy.Window late = ReportCyclePolicy.claim(NOW, delivered, 60);
        assertNotNull(late);
        assertEquals(delivered - 3_600_000L, late.startAt);
        assertEquals(delivered, late.endAt);
        assertEquals(delivered + 3_600_000L, late.nextDue);
        assertNull(ReportCyclePolicy.claim(late.nextDue, delivered, 60));
    }

    @Test public void reschedulingDoesNotPushAnOverdueCycleIntoTheFuture() {
        assertEquals(NOW - 1000, ReportCyclePolicy.ensureDeadline(NOW - 1000, NOW, 60));
        assertEquals(NOW + 3_600_000L, ReportCyclePolicy.ensureDeadline(0, NOW, 60));
        assertEquals(NOW + 3_600_000L,
                ReportCyclePolicy.ensureDeadline(NOW + 7 * 24 * 3_600_000L, NOW, 60));
    }

    @Test public void successfulUploadRestartsFromSuccessTimeAndIntervalChangesStartFresh() {
        long success = NOW + 25_000L;
        assertEquals(success + 3_600_000L, ReportCyclePolicy.afterSuccess(success, success, 60));
        assertEquals(NOW + 1_800_000L, ReportCyclePolicy.start(NOW, 30));
        assertEquals(NOW + 14_400_000L, ReportCyclePolicy.start(NOW, 240));
        assertEquals(NOW + 3_600_000L, ReportCyclePolicy.afterSuccess(NOW - 7_200_000L, NOW, 60));
    }

}
