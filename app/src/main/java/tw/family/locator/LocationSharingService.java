package tw.family.locator;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit, visible opt-in. No boot receiver, sticky restart, or continuous GPS requests. */
public final class LocationSharingService extends Service {
    private static final String CHANNEL = "location_sharing";
    private static final String ACTION_STOP = "tw.family.locator.STOP_SHARING";
    private static final int NOTIFICATION_ID = 2201;
    private static final int REVOKE_JOB_ID = 2202;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final ExecutorService REVOKE_WORKER = Executors.newSingleThreadExecutor();
    private static volatile boolean running;
    private static volatile LocationSharingService instance;
    private static volatile String status = "尚未開始定時回報";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ApiClient api;
    private OneShotLocation.Cancellation locationRequest;
    private boolean busy;
    private volatile boolean destroyed;
    private long generation;

    public static boolean isRunning() { return running; }
    public static String getStatus() { return status; }

    /** Posts only to an already-running instance. Never starts a service from an alarm. */
    public static boolean checkNow() {
        LocationSharingService service = instance;
        if (!running || service == null || service.destroyed) return false;
        service.handler.post(service::checkCycleNow);
        return true;
    }

    /** Must be invoked while an Activity is visible, after explicit user consent. */
    public static void start(Context context) {
        if (!hasLocationPermission(context)) throw new IllegalStateException("請先允許位置權限，再開啟定時回報。");
        if (!isLocationEnabled(context)) throw new IllegalStateException("請先開啟手機定位，再開啟定時回報。");
        if (!ApiClient.isLocallyEnabled(context)) throw new IllegalStateException("請先確認位置分享同意。");
        context.startForegroundService(new Intent(context, LocationSharingService.class));
    }

    /** Cancels queued fixes immediately. Any in-flight upload is followed by cloud deletion. */
    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        ApiClient.requestLocalStop(app);
        GENERATION.incrementAndGet();
        running = false;
        status = "已停止採集位置；雲端撤回若離線會稍後補送";
        app.stopService(new Intent(app, LocationSharingService.class));
        CheckInScheduler.reschedule(app);
        scheduleRevocation(app);
        REVOKE_WORKER.execute(() -> {
            ApiClient client = new ApiClient(app);
            try {
                client.retryPendingStop();
                if (!client.hasPendingStop()) {
                    JobScheduler jobs = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                    if (jobs != null) jobs.cancel(REVOKE_JOB_ID);
                }
            } catch (ApiClient.ApiException ignored) { /* JobScheduler retries after reconnection. */ }
        });
    }

    public static void scheduleRevocation(Context context) {
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs == null) return;
        try {
            jobs.schedule(new JobInfo.Builder(REVOKE_JOB_ID,
                    new android.content.ComponentName(context, RevokeJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
        } catch (IllegalArgumentException | SecurityException ignored) {
            // The persisted local stop marker is also retried on every subsequent API call.
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        api = new ApiClient(this);
        generation = GENERATION.incrementAndGet();
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "定時位置回報", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("開啟定時回報時持續顯示，可隨時停止位置分享。");
            notifications.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop(this);
            return START_NOT_STICKY;
        }
        if (!ApiClient.isLocallyEnabled(this) || !api.isSignedIn()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification notification = notification("等待下一次回報檢查");
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFICATION_ID, notification);
        } catch (RuntimeException exception) {
            status = "無法啟動定位服務，請回到 App 檢查權限與手機定位。";
            ApiClient.requestLocalStop(this);
            scheduleRevocation(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean firstStart = !running || instance != this;
        running = true;
        instance = this;
        if (firstStart) CheckInPreferences.startAutomaticCycle(this);
        else CheckInScheduler.reschedule(this);
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed || generation != GENERATION.get()) return;
            handler.postDelayed(this, 60_000L);
            checkCycleNow();
        }
    };

    private void checkCycleNow() {
        if (destroyed || generation != GENERATION.get()) return;
        if (!ApiClient.isLocallyEnabled(this)) { stop(this); return; }
        if (busy) return;
        ReportCyclePolicy.Window window = CheckInPreferences.claimDueWindow(this, System.currentTimeMillis());
        if (window == null) {
            updateStatus("等待下次回報檢查（約 " + CheckInPreferences.getIntervalMinutes(this) + " 分鐘間隔）");
            return;
        }
        // The shared deadline has already advanced durably. No failure retries within this window.
        busy = true;
        long requestGeneration = generation;
        if (!AppUsageGate.hasPermission(this)) {
            completeWindow(window, requestGeneration, ReportCyclePolicy.Outcome.PERMISSION_MISSING,
                    "本次缺少使用情況存取權，請手動回報位置。");
            return;
        }
        if (!hasLocationPermission(this) || !isLocationEnabled(this)) {
            completeWindow(window, requestGeneration, ReportCyclePolicy.Outcome.PERMISSION_MISSING,
                    "本次無法使用手機定位，請檢查定位與權限後手動回報。");
            return;
        }
        worker.execute(() -> {
            boolean used = AppUsageGate.wasUsedInWindow(this, window.startAt, window.endAt);
            handler.post(() -> {
                if (!active(requestGeneration) || !CheckInPreferences.isClaimCurrent(this, window)) {
                    busy = false;
                    return;
                }
                boolean usagePermission = AppUsageGate.hasPermission(this);
                if (!usagePermission) {
                    completeWindow(window, requestGeneration, ReportCyclePolicy.Outcome.PERMISSION_MISSING,
                            "本次缺少使用情況存取權，請手動回報位置。");
                    return;
                }
                if (ReportCyclePolicy.action(true, usagePermission, used)
                        != ReportCyclePolicy.Action.TRY_LOCATION) {
                    completeWindow(window, requestGeneration, ReportCyclePolicy.Outcome.NO_USAGE,
                            "這段時間沒有符合條件的使用活動，請手動回報目前位置。");
                    return;
                }
                updateStatus("正在取得本次回報位置…");
                locationRequest = OneShotLocation.request(this, new OneShotLocation.Callback() {
                    @Override public void onLocation(Location location) {
                        locationRequest = null;
                        if (!active(requestGeneration) || !CheckInPreferences.isClaimCurrent(LocationSharingService.this, window)) {
                            busy = false;
                            return;
                        }
                        worker.execute(() -> upload(location, requestGeneration, window));
                    }
                    @Override public void onError(String message) {
                        locationRequest = null;
                        completeWindow(window, requestGeneration, ReportCyclePolicy.Outcome.LOCATION_FAILED,
                                message + " 請手動回報；本次定位取用已停止。");
                    }
                });
            });
        });
    }

    private void upload(Location location, long requestGeneration, ReportCyclePolicy.Window window) {
        String result;
        ReportCyclePolicy.Outcome outcome;
        try {
            if (!active(requestGeneration) || !CheckInPreferences.isClaimCurrent(this, window)) {
                handler.post(() -> busy = false);
                return;
            }
            BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean sent = api.publishLocationIfDue(location.getLatitude(), location.getLongitude(), location.getAccuracy(), level,
                    Instant.ofEpochMilli(location.getTime()).toString(), window.intervalMinutes);
            // A delayed old-account upload must not reset a newly signed-in account's clock.
            if (sent && active(requestGeneration)) CheckInPreferences.markReported(this, System.currentTimeMillis());
            outcome = sent ? ReportCyclePolicy.Outcome.SUCCESS : ReportCyclePolicy.Outcome.ALREADY_REPORTED;
            result = sent ? "本次位置已回報；本次定位取用已停止，等待下一次檢查"
                    : "近期已成功回報，本次不重複傳送；本次定位取用已停止";
        } catch (ApiClient.ApiException exception) {
            outcome = ReportCyclePolicy.Outcome.UPLOAD_FAILED;
            result = exception.getMessage() + " 請手動回報；本次定位取用已停止。";
        }
        String message = result;
        ReportCyclePolicy.Outcome completed = outcome;
        handler.post(() -> completeWindow(window, requestGeneration, completed, message));
    }

    private void completeWindow(ReportCyclePolicy.Window window, long requestGeneration,
                                ReportCyclePolicy.Outcome outcome, String message) {
        busy = false;
        if (!active(requestGeneration)) return;
        if (ReportCyclePolicy.shouldNotify(outcome) && !CheckInPreferences.isClaimCurrent(this, window)) return;
        updateStatus(message);
        if (ReportCyclePolicy.shouldNotify(outcome)) CheckInScheduler.notifyReportNeeded(this, message);
    }

    private boolean active(long requestGeneration) {
        return !destroyed && running && requestGeneration == GENERATION.get() && ApiClient.isLocallyEnabled(this);
    }

    private void updateStatus(String message) {
        status = message;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notifications != null && running) notifications.notify(NOTIFICATION_ID, notification(message));
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 2201, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(this, 2202,
                new Intent(this, LocationSharingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("家人在這裡・定時回報已開啟")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止分享", stopIntent).build())
                .build();
    }

    public static boolean hasLocationPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isLocationEnabled(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        try { return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); }
        catch (RuntimeException exception) { return false; }
    }

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (locationRequest != null) locationRequest.cancel();
        if (generation == GENERATION.get()) {
            GENERATION.incrementAndGet();
            running = false;
            ApiClient.requestLocalStop(this);
            scheduleRevocation(this);
        }
        if (instance == this) instance = null;
        CheckInScheduler.reschedule(this);
        worker.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Only sends a saved stop request. It cannot collect location or start sharing. */
    public static final class RevokeJob extends JobService {
        @Override public boolean onStartJob(JobParameters parameters) {
            ApiClient client = new ApiClient(this);
            if (!client.hasPendingStop()) return false;
            REVOKE_WORKER.execute(() -> {
                try { client.retryPendingStop(); }
                catch (ApiClient.ApiException ignored) {}
                jobFinished(parameters, client.hasPendingStop());
            });
            return true;
        }
        @Override public boolean onStopJob(JobParameters parameters) { return new ApiClient(this).hasPendingStop(); }
    }
}
