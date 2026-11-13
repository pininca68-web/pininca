package tw.family.locator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar rules independent of Android, so interval boundaries can be tested. */
public final class CalendarPolicy {
    public static final ZoneId FAMILY_ZONE = ZoneId.of("Asia/Taipei");
    public static final long MAX_SYNC_RANGE_MS = 400L * 24 * 60 * 60_000;
    private CalendarPolicy() {}

    public static boolean isReminderSupported(int minutes) {
        return minutes == 0 || minutes == 5 || minutes == 10 || minutes == 15
                || minutes == 30 || minutes == 60 || minutes == 1440;
    }

    public static String validationError(String title, String description, String location,
                                         long start, long end, int reminderMinutes) {
        if (title == null || title.trim().isEmpty() || title.trim().codePointCount(0, title.trim().length()) > 120)
            return "活動標題請輸入 1 到 120 個字。";
        if (description != null && description.codePointCount(0, description.length()) > 4000)
            return "備註最多 4000 個字。";
        if (location != null && location.codePointCount(0, location.length()) > 300)
            return "地點最多 300 個字。";
        if (start <= 0 || end <= start) return "結束時間必須晚於開始時間。";
        if (!isReminderSupported(reminderMinutes)) return "請選擇有效的活動提醒時間。";
        return null;
    }

    public static long reminderAt(long startsAt, int minutes) {
        if (startsAt <= 0 || !isReminderSupported(minutes)) throw new IllegalArgumentException("活動時間無效。");
        return Math.max(0, startsAt - minutes * 60_000L);
    }

    public static boolean overlaps(long start, long end, long from, long to) {
        return start < to && end > from && end > start && to > from;
    }

    public static boolean occursOnDay(long start, long end, LocalDate day) {
        long from = day.atStartOfDay(FAMILY_ZONE).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(FAMILY_ZONE).toInstant().toEpochMilli();
        return overlaps(start, end, from, to);
    }

    /** A cached reminder is still useful if Android delivers it before the activity ends. */
    public static boolean canDeliver(long due, long end, long now, boolean alreadyDelivered) {
        return !alreadyDelivered && due > 0 && now >= due && now < end;
    }

    public static boolean isSyncRangeValid(long from, long to) {
        return from > 0 && to > from && to - from <= MAX_SYNC_RANGE_MS;
    }

    public static long parseTime(String value) {
        try { return Instant.parse(value).toEpochMilli(); }
        catch (Exception ignored) {
            try { return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
            catch (Exception invalid) { return 0; }
        }
    }
}
