package tw.family.locator;

/** Wall-clock reminder calculations, independent of Android and without catch-up bursts. */
public final class ReminderTiming {
    public static final int DEFAULT_INTERVAL_MINUTES = 60;
    public static final long RESTORE_GRACE_MS = 60_000L;
    public static final long SNOOZE_MS = 10 * 60_000L;

    private ReminderTiming() {}

    public static boolean isSupportedInterval(int minutes) {
        return minutes == 30 || minutes == 60 || minutes == 120
                || minutes == 180 || minutes == 240;
    }

    public static long intervalMillis(int minutes) {
        if (!isSupportedInterval(minutes)) {
            throw new IllegalArgumentException("請選擇 30 分鐘、1、2、3 或 4 小時。");
        }
        return minutes * 60_000L;
    }

    /** Saturates instead of wrapping into a past timestamp. */
    public static long add(long now, long delay) {
        if (now < 0 || delay < 0) throw new IllegalArgumentException("時間不可為負數。");
        return now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
    }

    public static long initialDue(long now, int intervalMinutes) {
        return add(now, intervalMillis(intervalMinutes));
    }

    /** Interval changes start a new interval from now; existing success is not fabricated. */
    public static long changedIntervalDue(long now, int intervalMinutes) {
        return initialDue(now, intervalMinutes);
    }

    public static long afterReport(long reportedAt, long now, int intervalMinutes) {
        if (reportedAt < 0 || now < 0) throw new IllegalArgumentException("時間不可為負數。");
        long boundedReport = Math.min(reportedAt, now);
        long due = add(boundedReport, intervalMillis(intervalMinutes));
        return due > now ? due : add(now, RESTORE_GRACE_MS);
    }

    /** Reboot or clock changes restore one future reminder, never all missed intervals. */
    public static long restoredDue(long storedDue, long now, int intervalMinutes) {
        long normalDue = initialDue(now, intervalMinutes);
        if (storedDue <= 0) return normalDue;
        if (storedDue <= now) return add(now, RESTORE_GRACE_MS);
        // A manually changed clock must not postpone the reminder for days.
        return Math.min(storedDue, normalDue);
    }

    /** A late alarm advances from its actual delivery, not the old planned timestamp. */
    public static long afterReminder(long deliveredAt, int intervalMinutes) {
        return initialDue(deliveredAt, intervalMinutes);
    }
}
