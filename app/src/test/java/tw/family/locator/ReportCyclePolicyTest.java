package tw.family.locator;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReportCyclePolicyTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void firstAutomaticCheckWaitsOneWholeSelectedInterval() {
        for (int minutes : new int[]{30, 60, 120, 180, 240}) {
            long due = ReportCyclePolicy.start(NOW, minutes);
            assertEquals(NOW + minutes * 60_000L, due);
            assertNull(ReportCyclePolicy.claim(due, NOW, minutes));
            assertNull(ReportCyclePolicy.claim(due, due - 1, minutes));
            assertNotNull(ReportCyclePolicy.claim(due, due, minutes));
        }
    }

    @Test public void durableAdvancePreventsTickAndAlarmClaimingTheSameWindow() {
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

    @Test public void anyOneSocialAppIsEnoughAndNoAppsMeansManualPrompt() {
        for (int mask = 0; mask < 8; mask++) {
            boolean any = ReportCyclePolicy.usedAny((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
            assertEquals(mask != 0, any);
            assertEquals(mask == 0 ? ReportCyclePolicy.Action.REMIND : ReportCyclePolicy.Action.TRY_LOCATION,
                    ReportCyclePolicy.action(true, true, any));
        }
        assertEquals(ReportCyclePolicy.Action.REMIND, ReportCyclePolicy.action(false, true, true));
        assertEquals(ReportCyclePolicy.Action.REMIND, ReportCyclePolicy.action(true, false, true));
    }

    @Test public void successesNeverPromptButNoUsageAndAllFailuresDo() {
        assertFalse(ReportCyclePolicy.shouldNotify(ReportCyclePolicy.Outcome.SUCCESS));
        assertFalse(ReportCyclePolicy.shouldNotify(ReportCyclePolicy.Outcome.ALREADY_REPORTED));
        assertTrue(ReportCyclePolicy.shouldNotify(ReportCyclePolicy.Outcome.NO_USAGE));
        assertTrue(ReportCyclePolicy.shouldNotify(ReportCyclePolicy.Outcome.PERMISSION_MISSING));
        assertTrue(ReportCyclePolicy.shouldNotify(ReportCyclePolicy.Outcome.LOCATION_FAILED));
        assertTrue(ReportCyclePolicy.shouldNotify(ReportCyclePolicy.Outcome.UPLOAD_FAILED));
    }

    @Test public void successfulUploadRestartsFromSuccessTimeAndIntervalChangesStartFresh() {
        long success = NOW + 25_000L;
        assertEquals(success + 3_600_000L, ReportCyclePolicy.afterSuccess(success, success, 60));
        assertEquals(NOW + 1_800_000L, ReportCyclePolicy.start(NOW, 30));
        assertEquals(NOW + 14_400_000L, ReportCyclePolicy.start(NOW, 240));
        assertEquals(NOW + 3_600_000L, ReportCyclePolicy.afterSuccess(NOW - 7_200_000L, NOW, 60));
    }

    @Test public void fixedHalfOpenWindowsDoNotCountNextWindowOrEndedEarlierUse() {
        assertFalse(ReportCyclePolicy.overlaps(10, 100, 100, 200));
        assertFalse(ReportCyclePolicy.overlaps(200, 250, 100, 200));
        assertTrue(ReportCyclePolicy.overlaps(100, 101, 100, 200));
        assertTrue(ReportCyclePolicy.overlaps(199, 200, 100, 200));
        assertTrue(ReportCyclePolicy.overlaps(50, 250, 100, 200));
        assertFalse(ReportCyclePolicy.overlaps(150, 140, 100, 200));
    }
}
