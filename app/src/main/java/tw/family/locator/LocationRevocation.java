package tw.family.locator;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Revokes a saved share over the network. It cannot acquire or publish a location. */
public final class LocationRevocation {
    private static final int REVOKE_JOB_ID = 2202;
    private static final String LEGACY_SERVICE = "tw.family.locator.LocationSharingService";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private LocationRevocation() {}

    /** Local consent is withdrawn immediately, including when the phone is offline. */
    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        ApiClient.requestLocalStop(app);
        // Upgrade cleanup only: the removed service is never started or bound here.
        try { app.stopService(new Intent().setClassName(app, LEGACY_SERVICE)); }
        catch (SecurityException ignored) { /* The new manifest no longer declares it. */ }
        CheckInScheduler.reschedule(app);
        schedule(app);
        WORKER.execute(() -> retry(app));
    }

    /** A network-constrained retry is restored on boot, package update and App startup. */
    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        try {
            jobs.schedule(new JobInfo.Builder(REVOKE_JOB_ID, new ComponentName(app, RevokeJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
        } catch (IllegalArgumentException | SecurityException ignored) {
            // The persisted stop marker is also retried by subsequent API requests.
        }
    }

    private static void retry(Context context) {
        ApiClient client = new ApiClient(context);
        try {
            client.retryPendingStop();
            if (!client.hasPendingStop()) {
                JobScheduler jobs = context.getSystemService(JobScheduler.class);
                if (jobs != null) jobs.cancel(REVOKE_JOB_ID);
            }
        } catch (ApiClient.ApiException ignored) { /* JobScheduler retries with network access. */ }
    }

    /** Sends only saved revocations; never calls a location API or a sharing endpoint. */
    public static final class RevokeJob extends JobService {
        @Override public boolean onStartJob(JobParameters parameters) {
            ApiClient client = new ApiClient(this);
            if (!client.hasPendingStop()) return false;
            WORKER.execute(() -> {
                try { client.retryPendingStop(); }
                catch (ApiClient.ApiException ignored) { /* Retry with exponential backoff. */ }
                jobFinished(parameters, client.hasPendingStop());
            });
            return true;
        }

        @Override public boolean onStopJob(JobParameters parameters) {
            return new ApiClient(this).hasPendingStop();
        }
    }
}
