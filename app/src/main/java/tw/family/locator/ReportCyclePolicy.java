package tw.family.locator;

/** A durable manual reminder clock; independent of Android and location acquisition. */
public final class ReportCyclePolicy {
    public static final class Window {
        public final long startAt;
        public final long endAt;
        public final long nextDue;
        public final int intervalMinutes;

        private Window(long startAt, long endAt, long nextDue, int intervalMinutes) {
            this.startAt = startAt;
            this.endAt = endAt;
            this.nextDue = nextDue;
            this.intervalMinutes = intervalMinutes;
        }
    }

    private ReportCyclePolicy() {}

    public static long start(long now, int intervalMinutes) {
        return ReminderTiming.add(now, ReminderTiming.intervalMillis(intervalMinutes));
    }

    /** Overdue cycles remain due, so rescheduling cannot keep postponing their processing. */
    public static long ensureDeadline(long storedDue, long now, int intervalMinutes) {
        long latest = start(now, intervalMinutes);
        return storedDue <= 0 ? latest : Math.min(storedDue, latest);
    }

    /** Persist nextDue before displaying a reminder. The same deadline cannot claim twice. */
    public static Window claim(long storedDue, long now, int intervalMinutes) {
        long length = ReminderTiming.intervalMillis(intervalMinutes);
        if (now < 0) throw new IllegalArgumentException("時間不可為負數。");
        if (storedDue <= 0 || now < storedDue) return null;
        return new Window(Math.max(0, now - length), now, start(now, intervalMinutes), intervalMinutes);
    }

    public static long afterSuccess(long successfulAt, long now, int intervalMinutes) {
        if (successfulAt < 0 || now < 0) throw new IllegalArgumentException("時間不可為負數。");
        long due = start(Math.min(successfulAt, now), intervalMinutes);
        return due > now ? due : start(now, intervalMinutes);
    }

}
