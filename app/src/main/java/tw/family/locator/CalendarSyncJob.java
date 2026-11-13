package tw.family.locator;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;

/** Deferred calendar sync only; it never starts location collection. */
public final class CalendarSyncJob extends JobService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, Running> tasks = new HashMap<>();
    private static final class Running {
        final AtomicBoolean cancelled = new AtomicBoolean();
        Future<?> future;
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        if (!new ApiClient(this).isSignedIn()) return false;
        Running running = new Running();
        synchronized (tasks) { tasks.put(parameters.getJobId(), running); }
        running.future = worker.submit(() -> {
            boolean retry = false;
            try { new CalendarRepository(this).sync(); }
            catch (ApiClient.ApiException ignored) { retry = true; }
            synchronized (tasks) { tasks.remove(parameters.getJobId(), running); }
            if (!running.cancelled.get()) jobFinished(parameters, retry);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Running running;
        synchronized (tasks) { running = tasks.remove(parameters.getJobId()); }
        if (running != null) {
            running.cancelled.set(true);
            if (running.future != null) running.future.cancel(true);
        }
        return new ApiClient(this).isSignedIn();
    }

    @Override public void onDestroy() {
        synchronized (tasks) {
            for (Running running : tasks.values()) running.cancelled.set(true);
            tasks.clear();
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
