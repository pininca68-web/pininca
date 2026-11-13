package tw.family.locator;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** One bounded location request; no permission dialogs and no background service startup. */
public final class OneShotLocation {
    public interface Callback {
        void onLocation(Location location);
        void onError(String message);
    }
    public interface Cancellation { void cancel(); }
    private OneShotLocation() {}

    public static Cancellation request(Context context, Callback callback) {
        Request request = new Request(context.getApplicationContext(), callback);
        request.handler.post(request::start);
        return request;
    }

    private static final class Request implements Cancellation, LocationListener {
        private final Context context;
        private final Callback callback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final LocationManager manager;
        private volatile boolean finished;
        private final Runnable timeout = () -> fail("暫時取得不到新位置，請到訊號較好的地方再試。");

        Request(Context context, Callback callback) {
            this.context = context;
            this.callback = callback;
            this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }

        @android.annotation.SuppressLint("MissingPermission") // Both location permissions are checked below.
        void start() {
            if (finished) return;
            if (manager == null) { fail("這台手機沒有可用的定位服務。"); return; }
            boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) { fail("請先允許位置權限，再回報位置。"); return; }
            boolean registered = false;
            Location cached = null;
            try {
                String[] providers = fine
                        ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}
                        : new String[]{LocationManager.NETWORK_PROVIDER};
                for (String provider : providers) {
                    if (!manager.getAllProviders().contains(provider) || !manager.isProviderEnabled(provider)) continue;
                    Location previous = manager.getLastKnownLocation(provider);
                    if (valid(previous) && System.currentTimeMillis() - previous.getTime() <= 30_000
                            && (cached == null || previous.getTime() > cached.getTime())) cached = previous;
                }
                if (cached != null) { succeed(cached); return; }
                for (String provider : providers) {
                    if (!manager.getAllProviders().contains(provider) || !manager.isProviderEnabled(provider)) continue;
                    manager.requestSingleUpdate(provider, this, Looper.getMainLooper());
                    registered = true;
                }
            } catch (SecurityException exception) { fail("位置權限已關閉，請重新允許後再試。"); return; }
            catch (IllegalArgumentException exception) { fail("暫時無法啟用定位服務，請稍後重試。"); return; }
            if (!registered) { fail("請開啟手機的定位服務，再回報位置。"); return; }
            handler.postDelayed(timeout, 30_000L);
        }

        private boolean valid(Location location) {
            if (location == null || !location.hasAccuracy()) return false;
            long elapsed = location.getElapsedRealtimeNanos();
            long ageNanos = SystemClock.elapsedRealtimeNanos() - elapsed;
            return ageNanos >= -10_000_000_000L && ageNanos <= LocationPolicy.MAX_AGE_MS * 1_000_000L
                    && LocationPolicy.isValid(location.getLatitude(), location.getLongitude(),
                    location.getAccuracy(), location.getTime(), System.currentTimeMillis());
        }

        @Override public void onLocationChanged(Location location) { if (valid(location)) succeed(location); }
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

        private void succeed(Location location) {
            if (finished) return;
            finished = true;
            cleanup();
            callback.onLocation(new Location(location));
        }

        private void fail(String message) {
            if (finished) return;
            finished = true;
            cleanup();
            callback.onError(message);
        }

        @Override public void cancel() {
            finished = true;
            handler.post(this::cleanup);
        }

        private void cleanup() {
            handler.removeCallbacks(timeout);
            if (manager != null) {
                try { manager.removeUpdates(this); }
                catch (SecurityException ignored) {}
            }
        }
    }
}
