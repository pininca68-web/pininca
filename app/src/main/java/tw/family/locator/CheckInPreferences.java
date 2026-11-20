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
    private static final String REPORT_DAYS = "report_days_mask";
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
            int value;
            try { value = prefs(context).getInt(INTERVAL, ReminderTiming.DEFAULT_INTERVAL_MINUTES); }
            catch (ClassCastException ignored) { value = ReminderTiming.DEFAULT_INTERVAL_MINUTES; }
            return ReminderTiming.isSupportedInterval(value)
                    ? value : ReminderTiming.DEFAULT_INTERVAL_MINUTES;
        }
    }

    public static void setIntervalMinutes(Context context, int minutes) {
        ReminderTiming.intervalMillis(minutes);
        synchronized (LOCK) {
            if (getIntervalMinutes(context) == minutes) return;
            long due = needsCycle(context) ? ReportWeekdays.nextAllowedAt(
                    ReportCyclePolicy.start(System.currentTimeMillis(), minutes), getReportDaysMask(context)) : 0;
            prefs(context).edit().putInt(INTERVAL, minutes).putLong(NEXT_REMINDER, due).apply();
            CheckInScheduler.reschedule(context);
        }
    }

    public static int getReportDaysMask(Context context) {
        synchronized (LOCK) {
            int stored;
            try { stored = prefs(context).getInt(REPORT_DAYS, ReportWeekdays.ALL); }
            catch (ClassCastException ignored) { stored = 0; }
            int mask = ReportWeekdays.sanitizeMask(stored);
            if (stored != mask) prefs(context).edit().putInt(REPORT_DAYS, mask).apply();
            return mask;
        }
    }

    public static void setReportDaysMask(Context context, int mask) {
        ReportWeekdays.requireMask(mask);
        synchronized (LOCK) {
            if (getReportDaysMask(context) == mask) return;
            long due = needsCycle(context) ? ReportWeekdays.nextAllowedAt(
                    ReportCyclePolicy.start(System.currentTimeMillis(), getIntervalMinutes(context)), mask) : 0;
            prefs(context).edit().putInt(REPORT_DAYS, mask).putLong(NEXT_REMINDER, due)
                    .putLong(SNOOZE_DUE, 0).putLong(LAST_NOTIFICATION, 0).apply();
            CheckInScheduler.dismissNotification(context);
            CheckInScheduler.reschedule(context);
            context.getApplicationContext().sendBroadcast(new Intent(CheckInScheduler.ACTION_REMINDER_SETTINGS_CHANGED)
                    .setPackage(context.getPackageName()));
        }
    }

    public static String getReportDaysLabel(Context context) {
        return ReportWeekdays.label(getReportDaysMask(context));
    }

    public static boolean isEnabled(Context context) {
        synchronized (LOCK) {
            return prefs(context).getBoolean(ENABLED, false);
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit().putBoolean(ENABLED, enabled);
            if (enabled && !isEnabled(context)) {
                editor.putLong(NEXT_REMINDER, ReportWeekdays.nextAllowedAt(
                        ReportCyclePolicy.start(System.currentTimeMillis(), getIntervalMinutes(context)),
                        getReportDaysMask(context)));
            } else if (!enabled) {
                editor.putLong(NEXT_REMINDER, 0);
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

    /** Keep this phone's interval/days/placement, but never reuse another account's report clock. */
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
            long due = needsCycle(app) ? ReportWeekdays.nextAllowedAt(
                    ReportCyclePolicy.afterSuccess(latest, now, getIntervalMinutes(app)),
                    getReportDaysMask(app)) : 0;
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
        return isEnabled(context);
    }

    static long ensureCycle(Context context, long now) {
        synchronized (LOCK) {
            if (!needsCycle(context)) {
                prefs(context).edit().putLong(NEXT_REMINDER, 0).apply();
                return 0;
            }
            long due = ReportWeekdays.reminderDue(getNextReminderAt(context), now,
                    getIntervalMinutes(context), getReportDaysMask(context));
            if (due != getNextReminderAt(context)) prefs(context).edit().putLong(NEXT_REMINDER, due).apply();
            return due;
        }
    }

    /** Claim once before delivering a manual reminder. An alarm never authorizes location. */
    static ReportCyclePolicy.Window claimDueWindow(Context context, long now) {
        synchronized (LOCK) {
            if (!needsCycle(context)) return null;
            if (!ReportWeekdays.isSelectedDay(now, getReportDaysMask(context))) {
                ensureCycle(context, now);
                return null;
            }
            ReportCyclePolicy.Window window = ReportCyclePolicy.claim(getNextReminderAt(context), now,
                    getIntervalMinutes(context));
            if (window == null) return null;
            long next = ReportWeekdays.nextAllowedAt(window.nextDue, getReportDaysMask(context));
            if (!prefs(context).edit().putLong(NEXT_REMINDER, next).commit()) return null;
            CheckInScheduler.reschedule(context);
            return window;
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
            if (!ReportWeekdays.isSelectedDay(now, getReportDaysMask(context))) {
                setSnoozeDue(context, ReportWeekdays.nextAllowedAt(now, getReportDaysMask(context)));
                return false;
            }
            return prefs(context).edit().putLong(SNOOZE_DUE, 0).commit();
        }
    }
}
