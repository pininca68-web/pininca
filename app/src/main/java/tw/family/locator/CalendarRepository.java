package tw.family.locator;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Private, account/family-scoped cache. A failed sync never replaces valid cached events. */
public final class CalendarRepository {
    static final Object LOCK = new Object();
    // Serialize the complete read/merge transaction, not just each HTTP request.
    // The cache lock remains free while network I/O is in progress.
    private static final Object SYNC_LOCK = new Object();
    static long generation;
    private final Context context;
    private final SharedPreferences prefs;

    public CalendarRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("family_calendar_cache", Context.MODE_PRIVATE);
    }

    static String identity(ApiClient api) {
        return api.isSignedIn() ? api.getServerUrl() + "\n" + api.currentUserId() : "";
    }

    public boolean matchesCurrentAccount() {
        synchronized (LOCK) {
            String current = identity(new ApiClient(context));
            return !current.isEmpty() && current.equals(prefs.getString("identity", ""));
        }
    }

    public String familyId() {
        synchronized (LOCK) { return matchesCurrentAccount() ? prefs.getString("family_id", "") : ""; }
    }

    public long lastSyncedAt() {
        synchronized (LOCK) { return matchesCurrentAccount() ? prefs.getLong("synced_at", 0) : 0; }
    }

    public JSONArray events() {
        synchronized (LOCK) {
            if (!matchesCurrentAccount() || familyId().isEmpty()) return new JSONArray();
            try { return new JSONArray(prefs.getString("events", "[]")); }
            catch (JSONException ignored) { return new JSONArray(); }
        }
    }

    public JSONObject event(String id) {
        JSONArray events = events();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null && id.equals(event.optString("id"))) return event;
        }
        return null;
    }

    /** The family id comes from an authenticated family_status response, never a deep link. */
    public boolean bindFamily(String familyId) {
        synchronized (LOCK) {
            String owner = identity(new ApiClient(context));
            String family = familyId == null ? "" : familyId;
            if (owner.equals(prefs.getString("identity", "")) && family.equals(prefs.getString("family_id", "")))
                return false;
            generation++;
            CalendarScheduler.cancelCachedAlarms(context);
            prefs.edit().clear().putString("identity", owner).putString("family_id", family)
                    .putString("events", "[]").commit();
            return true;
        }
    }

    /** Blocking network operation, used only by a worker. */
    public void sync() throws ApiClient.ApiException {
        long now = System.currentTimeMillis();
        syncRange(now - 7L * 24 * 60 * 60_000, now + 90L * 24 * 60 * 60_000);
    }

    public void syncMonth(LocalDate day) throws ApiClient.ApiException {
        LocalDate from = day.withDayOfMonth(1);
        syncRange(from.atStartOfDay(CalendarPolicy.FAMILY_ZONE).toInstant().toEpochMilli(),
                from.plusMonths(1).atStartOfDay(CalendarPolicy.FAMILY_ZONE).toInstant().toEpochMilli());
    }

    private void syncRange(long from, long to) throws ApiClient.ApiException {
        synchronized (SYNC_LOCK) {
            syncRangeSerialized(from, to);
        }
    }

    private void syncRangeSerialized(long from, long to) throws ApiClient.ApiException {
        if (!CalendarPolicy.isSyncRangeValid(from, to)) throw new ApiClient.ApiException("行事曆日期範圍無效。");
        ApiClient api = new ApiClient(context);
        final String owner = identity(api);
        if (owner.isEmpty()) throw new ApiClient.ApiException("請先登入後查看家庭行事曆。");
        final long beforeStatus;
        synchronized (LOCK) { beforeStatus = generation; }
        JSONObject status = api.familyStatus();
        JSONObject family = status.optJSONObject("family");
        String familyId = family == null ? "" : family.optString("id", "");
        final long requestGeneration;
        synchronized (LOCK) {
            if (beforeStatus != generation || !owner.equals(identity(api))) return;
            bindFamily(familyId);
            if (familyId.isEmpty()) return;
            requestGeneration = generation;
        }
        JSONObject response = api.calendarList(Instant.ofEpochMilli(from).toString(), Instant.ofEpochMilli(to).toString());
        JSONArray incoming = response.optJSONArray("events");
        if (!familyId.equals(response.optString("family_id"))) {
            synchronized (LOCK) {
                if (generation == requestGeneration && owner.equals(identity(api))) bindFamily("");
            }
            throw new ApiClient.ApiException("家庭已變更，已移除舊家庭的本機行程，請重新整理。");
        }
        if (incoming == null)
            throw new ApiClient.ApiException("行事曆家庭資料不一致，請重新整理。");
        synchronized (LOCK) {
            if (generation != requestGeneration || !owner.equals(identity(api)) || !familyId.equals(familyId())) return;
            Map<String, JSONObject> merged = new HashMap<>();
            JSONArray existing = events();
            long earliest = System.currentTimeMillis() - 370L * 24 * 60 * 60_000;
            for (int i = 0; i < existing.length(); i++) {
                JSONObject event = existing.optJSONObject(i);
                if (event == null) continue;
                long start = startsAt(event), end = endsAt(event);
                if (end >= earliest && !CalendarPolicy.overlaps(start, end, from, to))
                    merged.put(event.optString("id"), event);
            }
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject event = incoming.optJSONObject(i);
                if (event == null || !familyId.equals(event.optString("family_id")) || event.optString("id").isEmpty())
                    throw new ApiClient.ApiException("收到無效的家庭行程，保留上次同步資料。");
                merged.put(event.optString("id"), event);
            }
            if (merged.size() > 3000) throw new ApiClient.ApiException("本機行程過多，請縮小需要查閱的日期範圍。");
            List<JSONObject> sorted = new ArrayList<>(merged.values());
            sorted.sort(Comparator.comparingLong(CalendarRepository::startsAt));
            JSONArray saved = new JSONArray();
            for (JSONObject event : sorted) saved.put(event);
            if (!prefs.edit().putString("events", saved.toString()).putLong("synced_at", System.currentTimeMillis()).commit())
                throw new ApiClient.ApiException("行事曆尚未儲存，請確認手機儲存空間。");
            CalendarScheduler.reschedule(context);
        }
        try { api.syncAppSettingsIfChanged(familyId); }
        catch (ApiClient.ApiException ignored) { /* The next calendar/foreground sync retries settings separately. */ }
    }

    public void saveLocalEvent(JSONObject event) throws ApiClient.ApiException {
        synchronized (LOCK) {
            if (!matchesCurrentAccount() || !familyId().equals(event.optString("family_id")))
                throw new ApiClient.ApiException("家庭已變更，請重新整理行事曆。");
            generation++;
            JSONArray updated = new JSONArray();
            JSONArray current = events();
            for (int i = 0; i < current.length(); i++) {
                JSONObject row = current.optJSONObject(i);
                if (row != null && !row.optString("id").equals(event.optString("id"))) updated.put(row);
            }
            updated.put(event);
            prefs.edit().putString("events", updated.toString()).commit();
            CalendarScheduler.reschedule(context);
        }
    }

    public void removeLocalEvent(String id) {
        synchronized (LOCK) {
            generation++;
            JSONArray updated = new JSONArray();
            JSONArray current = events();
            for (int i = 0; i < current.length(); i++) {
                JSONObject row = current.optJSONObject(i);
                if (row != null && !id.equals(row.optString("id"))) updated.put(row);
            }
            prefs.edit().putString("events", updated.toString()).commit();
            CalendarScheduler.reschedule(context);
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) {
            generation++;
            CalendarScheduler.cancelCachedAlarms(context);
            context.getSharedPreferences("family_calendar_cache", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    public static long startsAt(JSONObject event) { return CalendarPolicy.parseTime(event.optString("starts_at")); }
    public static long endsAt(JSONObject event) { return CalendarPolicy.parseTime(event.optString("ends_at")); }
}
