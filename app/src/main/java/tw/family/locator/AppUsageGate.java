package tw.family.locator;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.UserManager;

/** Local-only usage check. It never reads messages or sends package names to the server. */
public final class AppUsageGate {
    private static final long LOOKBACK_MS = 24L * 60 * 60_000;

    private AppUsageGate() {}

    public static boolean hasPermission(Context context) {
        AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (manager == null) return false;
        try {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                    : manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException exception) { return false; }
    }

    public static boolean wasRecentlyUsed(Context context, long intervalMillis) {
        long end = System.currentTimeMillis();
        return wasUsedInWindow(context, Math.max(0, end - Math.max(30 * 60_000L, intervalMillis)), end);
    }

    /** Fixed claimed-window bounds prevent a delayed worker from counting next-cycle activity. */
    public static boolean wasUsedInWindow(Context context, long startAt, long endAt) {
        if (!hasPermission(context)) return false;
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (users != null && !users.isUserUnlocked()) return false;
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return false;
        long now = Math.min(System.currentTimeMillis(), endAt);
        long start = Math.max(0, startAt);
        if (now < start || endAt <= 0) return false;
        // Earlier events establish an app that was already in front at the window boundary.
        UsageEvents events;
        try { events = manager.queryEvents(Math.max(0, now - LOOKBACK_MS), now); }
        catch (RuntimeException exception) { return false; }
        if (events == null) return false;
        UsageEvents.Event event = new UsageEvents.Event();
        String foreground = null;
        long foregroundSince = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            long time = Math.min(now, event.getTimeStamp());
            int type = event.getEventType();
            String packageName = event.getPackageName();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (overlaps(foreground, foregroundSince, time, start, now)) return true;
                foreground = packageName;
                foregroundSince = time;
                if (isSocialPackage(packageName) && time >= start && time < now) return true;
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND
                    || type == 23 /* ACTIVITY_STOPPED */) {
                if (packageName != null && packageName.equals(foreground)) {
                    if (overlaps(foreground, foregroundSince, time, start, now)) return true;
                    foreground = null;
                }
            } else if (type == 16 /* SCREEN_NON_INTERACTIVE */
                    || type == 17 /* KEYGUARD_SHOWN */ || type == 26 /* DEVICE_SHUTDOWN */) {
                if (overlaps(foreground, foregroundSince, time, start, now)) return true;
                foreground = null;
            }
        }
        return overlaps(foreground, foregroundSince, now, start, now);
    }

    private static boolean overlaps(String name, long from, long to, long windowStart, long now) {
        return isSocialPackage(name) && ReportCyclePolicy.overlaps(from, to, windowStart, now);
    }

    private static boolean isSocialPackage(String name) {
        return ReportCyclePolicy.usedAny("jp.naver.line.android".equals(name),
                "com.facebook.katana".equals(name) || "com.facebook.lite".equals(name),
                "com.instagram.android".equals(name));
    }
}
