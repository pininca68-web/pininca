package tw.family.locator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Local reminder days, with ISO Monday bit 0 through Sunday bit 6. No Android APIs. */
public final class ReportWeekdays {
    public static final int WEEKDAYS = 31;
    public static final int WEEKENDS = 96;
    public static final int ALL = 127;
    public static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final String[] LABELS = {"週一", "週二", "週三", "週四", "週五", "週六", "週日"};

    private ReportWeekdays() {}

    public static boolean isValidMask(int mask) {
        return mask > 0 && (mask & ~ALL) == 0;
    }

    public static int requireMask(int mask) {
        if (!isValidMask(mask)) throw new IllegalArgumentException("請至少選擇一個提醒日。");
        return mask;
    }

    /** Old installs have no setting; discard unknown bits without disabling every day. */
    public static int sanitizeMask(int storedMask) {
        int knownDays = storedMask & ALL;
        return knownDays == 0 ? ALL : knownDays;
    }

    public static boolean isSelectedDay(long timestamp, int mask) {
        requireMask(mask);
        if (timestamp < 0) throw new IllegalArgumentException("時間不可為負數。");
        int isoDay = Instant.ofEpochMilli(timestamp).atZone(ZONE).getDayOfWeek().getValue();
        return (mask & (1 << (isoDay - 1))) != 0;
    }

    /** Keep a valid deadline; otherwise use midnight at the next selected Taipei date. */
    public static long nextAllowedAt(long timestamp, int mask) {
        requireMask(mask);
        if (isSelectedDay(timestamp, mask)) return timestamp;
        ZonedDateTime date = Instant.ofEpochMilli(timestamp).atZone(ZONE).toLocalDate()
                .atStartOfDay(ZONE);
        for (int ahead = 1; ahead <= 7; ahead++) {
            ZonedDateTime next = date.plusDays(ahead);
            if ((mask & (1 << (next.getDayOfWeek().getValue() - 1))) != 0) {
                return next.toInstant().toEpochMilli();
            }
        }
        throw new IllegalStateException("缺少提醒日。");
    }

    /** Late delivery on an excluded date must wait for a future selected date. */
    public static long reminderDue(long storedDue, long now, int intervalMinutes, int mask) {
        long due = ReportCyclePolicy.ensureDeadline(storedDue, now, intervalMinutes);
        if (!isSelectedDay(now, mask)) due = Math.max(now, due);
        return nextAllowedAt(due, mask);
    }

    /** A snooze is also a reminder: crossing Friday midnight skips the whole weekend. */
    public static long snoozeDue(long now, int mask) {
        return nextAllowedAt(ReminderTiming.add(now, ReminderTiming.SNOOZE_MS), mask);
    }

    /** Reminder UI expires at a Taipei date boundary rather than lingering into an excluded day. */
    public static long nextMidnightAt(long timestamp) {
        if (timestamp < 0) throw new IllegalArgumentException("時間不可為負數。");
        return Instant.ofEpochMilli(timestamp).atZone(ZONE).toLocalDate().plusDays(1)
                .atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static String label(int mask) {
        requireMask(mask);
        if (mask == ALL) return "每天（週一至週日）";
        if (mask == WEEKDAYS) return "平日（週一至週五）";
        if (mask == WEEKENDS) return "假日（週六、週日）";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < LABELS.length; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (text.length() > 0) text.append("、");
            text.append(LABELS[i]);
        }
        return text.toString();
    }
}
