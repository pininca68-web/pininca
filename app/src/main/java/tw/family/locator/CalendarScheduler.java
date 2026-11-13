package tw.family.locator;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Per-event local alarms, with account scoping and explicit user notification control. */
public final class CalendarScheduler {
    static final String ACTION_ALARM = "tw.family.locator.CALENDAR_ALARM";
    public static final String ACTION_OPEN = "tw.family.locator.CALENDAR_OPEN";
    private static final String CHANNEL = "family_calendar_reminders_v1";
    private static final int PERIODIC_JOB = 9401;
    private static final int SOON_JOB = 9402;
    private static final int ALARM_ID = 9403;
    private static final int NOTIFICATION_ID = 9404;
    private CalendarScheduler() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("family_calendar_alarms", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) { return prefs(context).getBoolean("enabled", true); }

    public static void setEnabled(Context context, boolean enabled) {
        synchronized (CalendarRepository.LOCK) {
            prefs(context).edit().putBoolean("enabled", enabled).commit();
            reschedule(context);
        }
    }

    public static void bindFamily(Context context, String familyId) {
        CalendarRepository repo = new CalendarRepository(context);
        boolean changed = repo.bindFamily(familyId);
        if (familyId == null || familyId.isEmpty()) {
            cancelJobs(context);
            return;
        }
        start(context);
        if (changed) refreshSoon(context);
    }

    /** Safe to call after each foreground family status refresh; does not reset an existing job. */
    public static void start(Context context) {
        if (new CalendarRepository(context).familyId().isEmpty()) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null || jobs.getPendingJob(PERIODIC_JOB) != null) return;
        try {
            jobs.schedule(new JobInfo.Builder(PERIODIC_JOB, new ComponentName(context, CalendarSyncJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true).build());
        } catch (IllegalArgumentException | SecurityException ignored) {}
    }

    /** Bounded refresh request, never a network call on the caller's UI thread. */
    public static void refreshSoon(Context context) {
        synchronized (CalendarRepository.LOCK) {
            if (new CalendarRepository(context).familyId().isEmpty()) return;
            long now = System.currentTimeMillis();
            long requested = prefs(context).getLong("refresh_requested_at", 0);
            if (now >= requested && now - requested < 5 * 60_000L) return;
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            try {
                int result = jobs.schedule(new JobInfo.Builder(SOON_JOB, new ComponentName(context, CalendarSyncJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
                if (result == JobScheduler.RESULT_SUCCESS)
                    prefs(context).edit().putLong("refresh_requested_at", now).apply();
            } catch (IllegalArgumentException | SecurityException ignored) {}
        }
    }

    public static void clearForAccountChange(Context context) {
        synchronized (CalendarRepository.LOCK) {
            cancelJobs(context);
            CalendarRepository.clear(context);
            boolean enabled = isEnabled(context);
            prefs(context).edit().clear().putBoolean("enabled", enabled).commit();
        }
    }

    private static void cancelJobs(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs != null) { jobs.cancel(PERIODIC_JOB); jobs.cancel(SOON_JOB); }
    }

    public static void reschedule(Context context) {
        synchronized (CalendarRepository.LOCK) {
            CalendarRepository repo = new CalendarRepository(context);
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            if (alarms == null) return;
            Set<String> previous = new HashSet<>(prefs(context).getStringSet("scheduled", new HashSet<>()));
            Set<String> current = new HashSet<>();
            Set<String> knownNotifications = new HashSet<>();
            for (String key : prefs(context).getAll().keySet()) {
                if (key.startsWith("delivered:")) knownNotifications.add(key.substring("delivered:".length()));
            }
            Set<String> keepNotifications = new HashSet<>();
            JSONArray events = repo.events();
            List<JSONObject> ordered = new ArrayList<>();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event != null) ordered.add(event);
            }
            ordered.sort(Comparator.comparingLong(CalendarRepository::startsAt));
            long now = System.currentTimeMillis();
            if (isEnabled(context) && !repo.familyId().isEmpty()) {
                for (JSONObject event : ordered) {
                    if (event == null || !repo.familyId().equals(event.optString("family_id"))) continue;
                    String id = event.optString("id");
                    int version = event.optInt("version", -1);
                    long start = CalendarRepository.startsAt(event), end = CalendarRepository.endsAt(event);
                    int reminder = event.optInt("reminder_minutes", -1);
                    if (id.isEmpty() || version < 1 || !CalendarPolicy.isReminderSupported(reminder) || end <= now || start <= 0) continue;
                    long due = CalendarPolicy.reminderAt(start, reminder);
                    String signature = signature(version, due);
                    if (signature.equals(prefs(context).getString("delivered:" + id, ""))) {
                        // A delivered alarm is finished, but its unread notification stays visible.
                        // Visit every event even after the pending-alarm limit has been reached.
                        keepNotifications.add(id);
                        continue;
                    }
                    // Keep well below Android's per-app alarm limit; later syncs refill the nearest events.
                    if (current.size() >= 400) continue;
                    PendingIntent alarm = alarmIntent(context, id, version, due);
                    try { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due > now ? due : now + 1000L, alarm); }
                    catch (IllegalStateException | SecurityException ignored) { continue; }
                    current.add(id);
                    if (previous.contains(id)) {
                        String prior = prefs(context).getString("scheduled_version:" + id, "");
                        if (!signature.equals(prior)) cancelNotification(context, id);
                    }
                    prefs(context).edit().putString("scheduled_version:" + id, signature).apply();
                }
            }
            for (String id : previous) {
                if (!current.contains(id)) {
                    alarms.cancel(alarmIntent(context, id, 0, 0));
                    prefs(context).edit().remove("scheduled_version:" + id).apply();
                }
            }
            // Delivered events are no longer in `scheduled`, so inspect persisted delivery ids too.
            // Only deleted, changed, expired, disabled or former-family notifications are withdrawn.
            knownNotifications.addAll(previous);
            SharedPreferences.Editor notificationState = prefs(context).edit();
            for (String id : knownNotifications) {
                if (!keepNotifications.contains(id)) {
                    cancelNotification(context, id);
                    notificationState.remove("delivered:" + id);
                }
            }
            notificationState.apply();
            prefs(context).edit().putStringSet("scheduled", current).commit();
        }
    }

    static void cancelCachedAlarms(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        Set<String> ids = new HashSet<>(prefs(context).getStringSet("scheduled", new HashSet<>()));
        for (String id : ids) {
            if (alarms != null) alarms.cancel(alarmIntent(context, id, 0, 0));
            cancelNotification(context, id);
        }
        // Include already-delivered notifications, whose alarms no longer need to be scheduled.
        for (String key : prefs(context).getAll().keySet()) {
            if (key.startsWith("delivered:")) cancelNotification(context, key.substring("delivered:".length()));
        }
        prefs(context).edit().remove("scheduled").apply();
    }

    static void deliver(Context context, Intent intent) {
        synchronized (CalendarRepository.LOCK) {
            if (!isEnabled(context)) return;
            CalendarRepository repo = new CalendarRepository(context);
            String id = intent.getStringExtra("event_id");
            if (id == null || repo.familyId().isEmpty()) return;
            JSONObject event = repo.event(id);
            if (event == null || !repo.familyId().equals(event.optString("family_id"))) return;
            int version = event.optInt("version", -1);
            int minutes = event.optInt("reminder_minutes", -1);
            if (version < 1 || !CalendarPolicy.isReminderSupported(minutes)) return;
            long due = CalendarPolicy.reminderAt(CalendarRepository.startsAt(event), minutes);
            if (version != intent.getIntExtra("version", -1) || due != intent.getLongExtra("due", -1)) return;
            String signature = signature(version, due);
            boolean delivered = signature.equals(prefs(context).getString("delivered:" + id, ""));
            if (!CalendarPolicy.canDeliver(due, CalendarRepository.endsAt(event), System.currentTimeMillis(), delivered)) return;
            if (!notificationsAllowed(context)) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            Intent open = new Intent(context, FamilyCalendarActivity.class).setAction(ACTION_OPEN)
                    .setData(eventUri(id)).putExtra("event_id", id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String time = java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")
                    .withZone(CalendarPolicy.FAMILY_ZONE).format(Instant.ofEpochMilli(CalendarRepository.startsAt(event)));
            String place = event.optString("location", "").trim();
            String details = time + " · 台北時間" + (place.isEmpty() ? "" : "\n" + place);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("家庭共用行程：" + event.optString("title", "活動"))
                    .setContentText(details).setStyle(new Notification.BigTextStyle().bigText(details))
                    .setContentIntent(content).setCategory(Notification.CATEGORY_EVENT)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build();
            try {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return;
                manager.notify("calendar:" + id, NOTIFICATION_ID, notification);
                prefs(context).edit().putString("delivered:" + id, signature).commit();
            } catch (SecurityException ignored) {}
        }
    }

    public static boolean notificationsAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "家庭共用行事曆", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("依活動設定提醒同一家庭的成員；可自行關閉。");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel saved = manager.getNotificationChannel(CHANNEL);
        return saved != null && saved.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static Intent notificationSettingsIntent(Context context) {
        notificationsAllowed(context);
        return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL);
    }

    private static String signature(int version, long due) { return version + ":" + due; }
    private static Uri eventUri(String id) { return new Uri.Builder().scheme("family-calendar").authority("event").appendPath(id).build(); }

    private static PendingIntent alarmIntent(Context context, String id, int version, long due) {
        Intent alarm = new Intent(context, CalendarReceiver.class).setAction(ACTION_ALARM).setData(eventUri(id))
                .putExtra("event_id", id).putExtra("version", version).putExtra("due", due);
        return PendingIntent.getBroadcast(context, ALARM_ID, alarm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelNotification(Context context, String id) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel("calendar:" + id, NOTIFICATION_ID);
    }

}
