package tw.family.locator;

/** Pure Java validation shared by GPS collection and the upload boundary. */
public final class LocationPolicy {
    public static final long MAX_AGE_MS = 120_000L;
    public static final long MAX_FUTURE_SKEW_MS = 10_000L;

    private LocationPolicy() {}

    public static boolean isValid(double latitude, double longitude, double accuracy,
                                  long recordedAt, long now) {
        return Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
                && Double.isFinite(accuracy) && accuracy >= 0 && accuracy <= 100_000
                && recordedAt > 0 && now > 0
                && recordedAt >= now - MAX_AGE_MS
                && recordedAt <= now + MAX_FUTURE_SKEW_MS;
    }

    public static boolean isDue(long lastSuccess, long now, int intervalMinutes) {
        long interval = Math.max(30, intervalMinutes) * 60_000L;
        return lastSuccess <= 0 || (now >= lastSuccess && now - lastSuccess >= interval);
    }
}
