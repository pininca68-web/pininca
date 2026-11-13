package tw.family.locator;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Inexact, permission-respecting reminders. No location is accessed by an alarm. */
public final class CheckInScheduler {
    public static final String ACTION_REPORT_NOW = "tw.family.locator.REPORT_NOW";
    public static final String ACTION_REMINDER_DUE = "tw.family.locator.REMINDER_DUE";
    public static final String ACTION_REPORT_COMPLETED = "tw.family.locator.REPORT_COMPLETED";
    public static final String EXTRA_REASON = "reason";
    static final String ACTION_ALARM = "tw.family.locator.CHECK_IN_ALARM";
    static final String ACTION_SNOOZE = "tw.family.locator.CHECK_IN_SNOOZE";
    static final String ACTION_PAUSE = "tw.family.locator.CHECK_IN_PAUSE";
    public static final String CHANNEL_ID = "family_check_in_reminders_v1";
    private static final int ALARM_REQUEST = 7501;
    private static final int NOTIFICATION_ID = 7502;
    private static final int SNOOZE_ALARM_REQUEST = 7506;
    private static volatile boolean appForeground;

    private CheckInScheduler() {}

    /** MainActivity registers its reminder receiver before setting this to true. */
    public static void setAppForeground(boolean foreground) {
        appForeground = foreground;
    }

    public static void reschedule(Context context) {
        Context app = context.getApplicationContext();
        synchronized (CheckInPreferences.LOCK) {
            AlarmManager alarms = app.getSystemService(AlarmManager.class);
            if (alarms == null) return;
            if (!CheckInPreferences.needsCycle(app)) {
                alarms.cancel(alarmIntent(app));
                CheckInPreferences.setNextReminderAt(app, 0);
            } else {
                long next = CheckInPreferences.ensureCycle(app, System.currentTimeMillis());
                // Both alarm delivery and the existing service claim this same deadline.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(app));
            }
            if (!CheckInPreferences.isEnabled(app)) {
                alarms.cancel(snoozeAlarmIntent(app));
                CheckInPreferences.setSnoozeDue(app, 0);
                dismissNotification(app);
                return;
            }
            long now = System.currentTimeMillis();
            long snooze = CheckInPreferences.getSnoozeDue(app);
            if (snooze > 0) {
                long bounded = Math.min(snooze, ReminderTiming.add(now, ReminderTiming.SNOOZE_MS));
                CheckInPreferences.setSnoozeDue(app, bounded);
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bounded, snoozeAlarmIntent(app));
            } else alarms.cancel(snoozeAlarmIntent(app));
        }
    }

    public static void cancel(Context context) {
        CheckInPreferences.setEnabled(context, false);
    }

    public static void dismissNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    public static void snooze(Context context) {
        synchronized (CheckInPreferences.LOCK) {
            if (!CheckInPreferences.isEnabled(context)) return;
            CheckInPreferences.setSnoozeDue(context,
                    ReminderTiming.add(System.currentTimeMillis(), ReminderTiming.SNOOZE_MS));
            dismissNotification(context);
            reschedule(context);
        }
    }

    static void onAlarm(Context context) {
        long now = System.currentTimeMillis();
        synchronized (CheckInPreferences.LOCK) {
            if (CheckInPreferences.claimSnooze(context, now)) {
                // A snoozed prompt never claims a cycle or authorizes a new GPS request.
                notifyReportNeeded(context, "稍後提醒時間到了，請手動回報目前位置。");
            }
            long due = CheckInPreferences.getNextReminderAt(context);
            if (due <= 0 || now < due) {
                reschedule(context);
                return;
            }
        }
        // This only posts to a live instance. It never starts/binds a service from a receiver.
        if (LocationSharingService.checkNow()) return;
        ReportCyclePolicy.Window window = CheckInPreferences.claimDueWindow(context, now);
        if (window != null) notifyReportNeeded(context, "到回報時間了，請手動回報位置，讓家人安心。");
        reschedule(context);
    }

    /** Preview never marks a position as reported or moves the normal reminder clock. */
    public static void showTestReminder(Context context) {
        dispatchReminder(context.getApplicationContext(), true, "這是回報提醒預覽。");
    }

    /** Does not move the cycle deadline or mark a location as reported. */
    public static void notifyReportNeeded(Context context, String reason) {
        synchronized (CheckInPreferences.LOCK) {
            if (!CheckInPreferences.isEnabled(context)) return;
            dispatchReminder(context.getApplicationContext(), false, reason);
        }
    }

    private static void dispatchReminder(Context context, boolean preview, String reason) {
        context.sendBroadcast(new Intent(ACTION_REMINDER_DUE).setPackage(context.getPackageName())
                .putExtra("preview", preview).putExtra(EXTRA_REASON, reason));
        if (!appForeground) postNotification(context, reason);
    }

    public static boolean canShowNotifications(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        ensureChannel(manager);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static void postNotification(Context context, String reason) {
        if (!canShowNotifications(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        Intent report = new Intent(context, MainActivity.class).setAction(ACTION_REPORT_NOW)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 7503, report,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent later = PendingIntent.getBroadcast(context, 7504,
                new Intent(context, CheckInReceiver.class).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getBroadcast(context, 7505,
                new Intent(context, CheckInReceiver.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("家人位置回報提醒")
                .setContentText(reason)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(reason + " 開啟 App 後由你確認回報。"))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .addAction(new Notification.Action.Builder(null, "立即回報", open).build())
                .addAction(new Notification.Action.Builder(null, "稍後 10 分鐘", later).build())
                .addAction(new Notification.Action.Builder(null, "暫停提醒", pause).build())
                .build();
        try {
            manager.notify(NOTIFICATION_ID, notification);
            CheckInPreferences.markNotificationShown(context, System.currentTimeMillis());
        } catch (SecurityException ignored) {
            // Permission may be revoked between the check and notify. This is not a report.
        }
    }

    private static void ensureChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "定時回報提醒",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("依你選擇的間隔提醒回報位置；可在手機設定關閉聲音或橫幅。");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    public static Intent getNotificationSettingsIntent(Context context) {
        return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
    }

    private static PendingIntent alarmIntent(Context context) {
        return PendingIntent.getBroadcast(context, ALARM_REQUEST,
                new Intent(context, CheckInReceiver.class).setAction(ACTION_ALARM),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent snoozeAlarmIntent(Context context) {
        return PendingIntent.getBroadcast(context, SNOOZE_ALARM_REQUEST,
                new Intent(context, CheckInReceiver.class).setAction(ACTION_ALARM),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
