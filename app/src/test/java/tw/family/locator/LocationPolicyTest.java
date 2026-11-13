package tw.family.locator;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocationPolicyTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void acceptsValidFixAndCoordinateBoundaries() {
        assertTrue(LocationPolicy.isValid(25.03, 121.56, 20, NOW, NOW));
        assertTrue(LocationPolicy.isValid(-90, -180, 0, NOW, NOW));
        assertTrue(LocationPolicy.isValid(90, 180, 100_000, NOW, NOW));
        assertTrue(LocationPolicy.isValid(0, 0, 1, NOW, NOW));
    }

    @Test public void rejectsOldFutureAndMissingTimestamps() {
        assertFalse(LocationPolicy.isValid(25, 121, 5, NOW - 120_001, NOW));
        assertFalse(LocationPolicy.isValid(25, 121, 5, NOW + 10_001, NOW));
        assertFalse(LocationPolicy.isValid(25, 121, 5, 0, NOW));
        assertTrue(LocationPolicy.isValid(25, 121, 5, NOW - 120_000, NOW));
        assertTrue(LocationPolicy.isValid(25, 121, 5, NOW + 10_000, NOW));
    }

    @Test public void rejectsNonFiniteAndOutOfBoundsCoordinates() {
        assertFalse(LocationPolicy.isValid(Double.NaN, 121, 5, NOW, NOW));
        assertFalse(LocationPolicy.isValid(25, Double.POSITIVE_INFINITY, 5, NOW, NOW));
        assertFalse(LocationPolicy.isValid(Double.NEGATIVE_INFINITY, 121, 5, NOW, NOW));
        assertFalse(LocationPolicy.isValid(90.0001, 121, 5, NOW, NOW));
        assertFalse(LocationPolicy.isValid(25, -180.0001, 5, NOW, NOW));
    }

    @Test public void rejectsMissingOrInvalidAccuracy() {
        assertFalse(LocationPolicy.isValid(25, 121, -1, NOW, NOW));
        assertFalse(LocationPolicy.isValid(25, 121, Double.NaN, NOW, NOW));
        assertFalse(LocationPolicy.isValid(25, 121, Double.POSITIVE_INFINITY, NOW, NOW));
        assertFalse(LocationPolicy.isValid(25, 121, 100_001, NOW, NOW));
    }

    @Test public void dueUsesLastSuccessfulReportAndHandlesClockRollback() {
        assertTrue(LocationPolicy.isDue(0, NOW, 30));
        assertFalse(LocationPolicy.isDue(NOW - 1_799_999, NOW, 30));
        assertTrue(LocationPolicy.isDue(NOW - 1_800_000, NOW, 30));
        assertFalse(LocationPolicy.isDue(NOW - 3_600_000, NOW, 120));
        assertTrue(LocationPolicy.isDue(NOW - 14_400_000, NOW, 240));
        assertFalse(LocationPolicy.isDue(NOW + 1000, NOW, 30));
    }
}
