package tw.family.locator;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** This phone's own reminder preferences; opt-in and never remotely enabled. */
public final class CheckInPreferences {
    static final Object LOCK = new Object();
    public static final String TOP = "TOP";
    public static final String BOTTOM = "BOTTOM";
    private static final String FILE = "check_in_preferences";
    private static final String ENABLED = "enabled";
    private static final String INTERVAL = "interval_minutes";
    private static final String PLACEMENT = "placement";
    private static final String LAST_REPORTED = "last_reported_at";
    private static final String NEXT_REMINDER = "next_reminder_at";
    private static final String LAST_NOTIFICATION = "last_notification_at";
    private static final String SNOOZE_DUE = "snooze_due_at";

    private CheckInPreferences() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int getIntervalMinutes(Context context) {
        synchronized (LOCK) {
            int value = prefs(context).getInt(INTERVAL, ReminderTiming.DEFAULT_INTERVAL_MINUTES);
            return ReminderTiming.isSupportedInterval(value)
                    ? value : ReminderTiming.DEFAULT_INTERVAL_MINUTES;
        }
    }

    public static void setIntervalMinutes(Context context, int minutes) {
        ReminderTiming.intervalMillis(minutes);
        synchronized (LOCK) {
            if (getIntervalMinutes(context) == minutes) return;
            long due = needsCycle(context)
                    ? ReportCyclePolicy.start(System.currentTimeMillis(), minutes) : 0;
            prefs(context).edit().putInt(INTERVAL, minutes).putLong(NEXT_REMINDER, due).apply();
            CheckInScheduler.reschedule(context);
        }
    }

    public static boolean isEnabled(Context context) {
        synchronized (LOCK) {
            return prefs(context).getBoolean(ENABLED, false);
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit().putBoolean(ENABLED, enabled);
            if (enabled && !isEnabled(context) && !LocationSharingService.isRunning()) {
                editor.putLong(NEXT_REMINDER, ReportCyclePolicy.start(System.currentTimeMillis(),
                        getIntervalMinutes(context)));
            } else if (!enabled) {
                if (!LocationSharingService.isRunning()) editor.putLong(NEXT_REMINDER, 0);
                editor.putLong(SNOOZE_DUE, 0);
            }
            editor.apply();
            CheckInScheduler.reschedule(context);
        }
    }

    public static String getPlacement(Context context) {
        synchronized (LOCK) {
            return BOTTOM.equals(prefs(context).getString(PLACEMENT, TOP)) ? BOTTOM : TOP;
        }
    }

    public static void setPlacement(Context context, String placement) {
        if (!TOP.equals(placement) && !BOTTOM.equals(placement)) {
            throw new IllegalArgumentException("請選擇上方或下方提醒。");
        }
        synchronized (LOCK) {
            prefs(context).edit().putString(PLACEMENT, placement).apply();
        }
    }

    public static long getLastReportedAt(Context context) {
        synchronized (LOCK) {
            return prefs(context).getLong(LAST_REPORTED, 0);
        }
    }

    public static long getNextReminderAt(Context context) {
        synchronized (LOCK) {
            return prefs(context).getLong(NEXT_REMINDER, 0);
        }
    }

    public static long getLastNotificationAt(Context context) {
        synchronized (LOCK) {
            return prefs(context).getLong(LAST_NOTIFICATION, 0);
        }
    }

    /** Keep this phone's interval/placement, but never reuse another account's report clock. */
    public static void clearForAccountChange(Context context) {
        synchronized (LOCK) {
            prefs(context).edit()
                    .putBoolean(ENABLED, false)
                    .remove(LAST_REPORTED)
                    .remove(NEXT_REMINDER)
                    .remove(LAST_NOTIFICATION)
                    .remove(SNOOZE_DUE)
                    .apply();
            CheckInScheduler.reschedule(context);
        }
    }

    /** Call only after a real location was successfully accepted by the family backend. */
    public static void markReported(Context context, long reportedAt) {
        if (reportedAt <= 0) throw new IllegalArgumentException("回報時間無效。");
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long acceptedAt = Math.min(reportedAt, now);
            // An out-of-order network completion must not move our success clock backwards.
            long latest = Math.max(getLastReportedAt(app), acceptedAt);
            long due = needsCycle(app)
                    ? ReportCyclePolicy.afterSuccess(latest, now, getIntervalMinutes(app)) : 0;
            prefs(app).edit().putLong(LAST_REPORTED, latest).putLong(NEXT_REMINDER, due)
                    .putLong(SNOOZE_DUE, 0).apply();
            CheckInScheduler.dismissNotification(app);
            CheckInScheduler.reschedule(app);
        }
        app.sendBroadcast(new Intent(CheckInScheduler.ACTION_REPORT_COMPLETED)
                .setPackage(app.getPackageName()));
    }

    static void setNextReminderAt(Context context, long timestamp) {
        synchronized (LOCK) {
            prefs(context).edit().putLong(NEXT_REMINDER, timestamp).apply();
        }
    }

    static void markNotificationShown(Context context, long timestamp) {
        synchronized (LOCK) {
            prefs(context).edit().putLong(LAST_NOTIFICATION, timestamp).apply();
        }
    }

    static boolean needsCycle(Context context) {
        return isEnabled(context) || LocationSharingService.isRunning();
    }

    /** Explicit automatic opt-in starts a whole new interval, never an immediate fix. */
    static void startAutomaticCycle(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().putLong(NEXT_REMINDER,
                    ReportCyclePolicy.start(System.currentTimeMillis(), getIntervalMinutes(context))).commit();
            CheckInScheduler.reschedule(context);
        }
    }

    static long ensureCycle(Context context, long now) {
        synchronized (LOCK) {
            if (!needsCycle(context)) {
                prefs(context).edit().putLong(NEXT_REMINDER, 0).apply();
                return 0;
            }
            long due = ReportCyclePolicy.ensureDeadline(getNextReminderAt(context), now, getIntervalMinutes(context));
            if (due != getNextReminderAt(context)) prefs(context).edit().putLong(NEXT_REMINDER, due).apply();
            return due;
        }
    }

    /** The alarm and live service use the same durable compare-and-advance under LOCK. */
    static ReportCyclePolicy.Window claimDueWindow(Context context, long now) {
        synchronized (LOCK) {
            if (!needsCycle(context)) return null;
            ReportCyclePolicy.Window window = ReportCyclePolicy.claim(getNextReminderAt(context), now,
                    getIntervalMinutes(context));
            if (window == null) return null;
            // A failed disk write never authorizes an automatic location attempt.
            if (!prefs(context).edit().putLong(NEXT_REMINDER, window.nextDue).commit()) return null;
            CheckInScheduler.reschedule(context);
            return window;
        }
    }

    /** Interval changes or a simultaneous successful manual report invalidate old pending work. */
    static boolean isClaimCurrent(Context context, ReportCyclePolicy.Window window) {
        synchronized (LOCK) {
            return needsCycle(context) && getIntervalMinutes(context) == window.intervalMinutes
                    && getNextReminderAt(context) == window.nextDue;
        }
    }

    static long getSnoozeDue(Context context) {
        synchronized (LOCK) { return prefs(context).getLong(SNOOZE_DUE, 0); }
    }

    static void setSnoozeDue(Context context, long due) {
        synchronized (LOCK) { prefs(context).edit().putLong(SNOOZE_DUE, due).apply(); }
    }

    static boolean claimSnooze(Context context, long now) {
        synchronized (LOCK) {
            long due = getSnoozeDue(context);
            if (!isEnabled(context) || due <= 0 || now < due) return false;
            return prefs(context).edit().putLong(SNOOZE_DUE, 0).commit();
        }
    }
}
