package tw.family.locator;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Synchronous API boundary. Call network methods from an executor, never the UI thread. */
public final class ApiClient {
    // One process-wide lock serializes refresh-token rotation, uploads and cloud revocation.
    private static final Object LOCK = new Object();
    private static final AtomicLong CONSENT_VERSION = new AtomicLong();
    private static volatile boolean initialized;
    private static volatile JSONObject session;
    private static volatile boolean archivedRevoke;
    private static volatile Set<String> archivedIdentities = Collections.emptySet();
    private static SharedPreferences prefs;
    private static SessionStore secrets;
    private final Context context;

    public static final class ApiException extends Exception {
        public final int status;
        public ApiException(String message) { this(message, 0); }
        public ApiException(String message, int status) { super(message); this.status = status; }
    }

    public ApiClient(Context context) {
        this.context = context.getApplicationContext();
        initialize(this.context);
    }

    private static void initialize(Context context) {
        if (initialized) return;
        synchronized (LOCK) {
            if (initialized) return;
            prefs = context.getSharedPreferences("family_locator", Context.MODE_PRIVATE);
            secrets = new SessionStore(context);
            try {
                String stored = secrets.read("session");
                session = stored == null ? null : new JSONObject(stored);
                String pending = secrets.read("pending_revoke");
                if (pending != null) {
                    JSONObject storedPending = new JSONObject(pending);
                    JSONArray tasks = storedPending.optJSONArray("tasks");
                    updateArchiveIdentities(tasks != null ? tasks : new JSONArray().put(storedPending));
                }
            } catch (Exception exception) {
                // A restored or invalidated Keystore must never expose an old session.
                session = null;
                archivedRevoke = false;
                archivedIdentities = Collections.emptySet();
                try { secrets.write("session", null); } catch (Exception ignored) {}
            }
            initialized = true;
        }
    }

    public boolean isConfigured() { return !getServerUrl().isEmpty() && !getPublicKey().isEmpty(); }
    public boolean isSignedIn() { return session != null; }
    public String getServerUrl() { return prefs.getString("server_url", ""); }
    public String getPublicKey() { return prefs.getString("public_key", ""); }
    public String currentUserId() {
        JSONObject current = session;
        return current == null ? "" : current.optString("user_id", "");
    }

    public void configure(String url, String key) throws ApiException {
        String normalizedUrl = url == null ? "" : url.trim().replaceAll("/+$", "");
        String normalizedKey = key == null ? "" : key.trim();
        try {
            URI uri = new URI(normalizedUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty()))
                throw new Exception();
        } catch (Exception exception) {
            throw new ApiException("請輸入 Supabase 專案的 HTTPS 網址，不要附加路徑。");
        }
        if (!isPublicKey(normalizedKey))
            throw new ApiException("請使用 publishable 或 anon 公開金鑰；不要填入 secret 或 service_role 金鑰。");
        synchronized (LOCK) {
            if ((isSignedIn() || prefs.getBoolean("stop_requested", false))
                    && (!normalizedUrl.equals(getServerUrl()) || !normalizedKey.equals(getPublicKey())))
                throw new ApiException("請先連線停止分享並登出，再更換雲端設定。");
            prefs.edit().putString("server_url", normalizedUrl).putString("public_key", normalizedKey).apply();
        }
    }

    private static boolean isPublicKey(String key) {
        if (key.startsWith("sb_publishable_") && key.length() > 20 && key.length() < 4096) return true;
        if (key.startsWith("sb_secret_") || key.length() > 4096) return false;
        try {
            String[] parts = key.split("\\.");
            if (parts.length != 3) return false;
            JSONObject claims = new JSONObject(new String(Base64.decode(parts[1],
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
            return "anon".equals(claims.optString("role"));
        } catch (Exception exception) { return false; }
    }

    public void signIn(String email, String password) throws ApiException {
        synchronized (LOCK) {
            requireConfigured();
            if (session != null) throw new ApiException("請先登出目前帳號。");
            JSONObject result = object(request(getServerUrl(), getPublicKey(),
                    "/auth/v1/token?grant_type=password", json("email", email.trim(), "password", password), null));
            saveSession(normalizeSession(result));
            prefs.edit().putBoolean("local_sharing", false).putBoolean("stop_requested", false).apply();
            reconcileAfterLoginLocked();
        }
    }

    public boolean signUp(String email, String password) throws ApiException {
        synchronized (LOCK) {
            requireConfigured();
            if (session != null) throw new ApiException("請先登出目前帳號。");
            JSONObject result = object(request(getServerUrl(), getPublicKey(), "/auth/v1/signup",
                    json("email", email.trim(), "password", password), null));
            if (result.optString("access_token", "").isEmpty()) return false;
            saveSession(normalizeSession(result));
            prefs.edit().putBoolean("local_sharing", false).putBoolean("stop_requested", false).apply();
            reconcileAfterLoginLocked();
            return true;
        }
    }

    public void signOut() throws ApiException {
        requestLocalStop(context);
        synchronized (LOCK) {
            ApiException failure = null;
            try {
                flushPendingStopLocked();
                if (session != null) {
                    try { authenticated("/auth/v1/logout?scope=local", new JSONObject()); }
                    catch (ApiException ignored) { /* Local credentials are still cleared. */ }
                }
            } catch (ApiException exception) {
                failure = exception;
                // Keep only a revocation task, not an interactive signed-in session.
                if (session != null) {
                    archiveCurrentSessionLocked();
                }
            } finally {
                saveSession(null);
                prefs.edit().putBoolean("local_sharing", false).putBoolean("stop_requested", false).apply();
            }
            if (failure != null || archivedRevoke) LocationSharingService.scheduleRevocation(context);
        }
    }

    public JSONObject familyStatus() throws ApiException { return object(rpc("family_status", new JSONObject())); }
    public void createFamily(String name) throws ApiException { rpc("create_family", json("p_name", name)); }
    public void joinFamily(String code) throws ApiException { rpc("join_family", json("p_invite_code", code.trim())); }
    public void updateProfile(String name) throws ApiException { rpc("set_display_name", json("p_name", name)); }
    public JSONObject calendarList(String fromIso, String toIso) throws ApiException {
        return object(rpc("calendar_list", json("p_from", fromIso, "p_to", toIso)));
    }
    public JSONObject calendarSave(JSONObject fields) throws ApiException {
        return object(rpc("calendar_save", fields));
    }
    public JSONObject sheetSyncStatus() throws ApiException {
        return object(rpc("sheet_sync_status", new JSONObject()));
    }
    public void calendarDelete(String eventId, int expectedVersion) throws ApiException {
        rpc("calendar_delete", json("p_id", eventId, "p_expected_version", expectedVersion));
    }
    /** Called off the UI thread. Failed snapshots are retried at the next family/calendar sync. */
    public void syncAppSettingsIfChanged(String familyId) throws ApiException {
        if (familyId == null || familyId.isEmpty()) return;
        synchronized (LOCK) {
            if (!isSignedIn()) return;
            JSONObject snapshot = json("p_interval_minutes", CheckInPreferences.getIntervalMinutes(context),
                    "p_reminders_enabled", CheckInPreferences.isEnabled(context),
                    "p_placement", CheckInPreferences.getPlacement(context),
                    "p_calendar_reminders_enabled", CalendarScheduler.isEnabled(context),
                    "p_expected_family_id", familyId);
            String fingerprint = getServerUrl() + "\n" + currentUserId() + "\n" + snapshot;
            if (fingerprint.equals(prefs.getString("last_settings_snapshot", ""))) return;
            rpc("app_settings_save", snapshot);
            prefs.edit().putString("last_settings_snapshot", fingerprint).apply();
        }
    }
    public String createInvite() throws ApiException {
        Object result = rpc("create_invite", new JSONObject());
        if (result instanceof String) return (String) result;
        throw new ApiException("邀請碼格式不正確，請確認雲端資料庫版本。");
    }

    public void leaveFamily() throws ApiException {
        requestLocalStop(context);
        synchronized (LOCK) {
            flushPendingStopLocked();
            authenticated("/rest/v1/rpc/leave_family", new JSONObject());
        }
    }

    public void setSharing(boolean enabled) throws ApiException {
        if (!enabled) {
            requestLocalStop(context);
            synchronized (LOCK) { flushPendingStopLocked(); }
            return;
        }
        long intentVersion = CONSENT_VERSION.incrementAndGet();
        synchronized (LOCK) {
            flushPendingStopLocked();
            authenticated("/rest/v1/rpc/set_sharing", json("p_enabled", true));
            if (CONSENT_VERSION.get() != intentVersion) {
                prefs.edit().putBoolean("stop_requested", true).putBoolean("local_sharing", false).apply();
                flushPendingStopLocked();
                throw new ApiException("位置分享已停止。");
            }
            prefs.edit().putBoolean("local_sharing", true).putBoolean("stop_requested", false).apply();
        }
    }

    public void publishLocation(double latitude, double longitude, float accuracy,
                                int batteryLevel, String recordedAt) throws ApiException {
        long timestamp;
        try { timestamp = Instant.parse(recordedAt).toEpochMilli(); }
        catch (Exception exception) { throw new ApiException("定位時間無效，請重新取得位置。"); }
        if (!LocationPolicy.isValid(latitude, longitude, accuracy, timestamp, System.currentTimeMillis()))
            throw new ApiException("定位已過期或精度無效，請重新取得位置。");
        synchronized (LOCK) {
            if (!isLocallyEnabled(context)) throw new ApiException("位置分享已停止，沒有傳送位置。");
            flushArchivedRevokeLocked(false);
            // A stop marker is written without LOCK so it also cancels queued uploads.
            if (prefs.getBoolean("stop_requested", false)) throw new ApiException("位置分享已停止。");
            authenticated("/rest/v1/rpc/publish_location", json("p_lat", latitude, "p_lng", longitude,
                    "p_accuracy", accuracy, "p_battery", batteryLevel < 0 ? JSONObject.NULL : Math.min(100, batteryLevel),
                    "p_recorded_at", recordedAt));
            // Recorded inside the upload lock, before another automatic/manual upload can start.
            prefs.edit().putString("last_upload_identity", getServerUrl() + "\n" + currentUserId())
                    .putLong("last_upload_at", System.currentTimeMillis()).apply();
        }
    }

    /** Automatic mode also respects a manual report that finished a moment ago. */
    public boolean publishLocationIfDue(double latitude, double longitude, float accuracy,
                                       int batteryLevel, String recordedAt, int intervalMinutes) throws ApiException {
        synchronized (LOCK) {
            long last = CheckInPreferences.getLastReportedAt(context);
            if ((getServerUrl() + "\n" + currentUserId()).equals(prefs.getString("last_upload_identity", "")))
                last = Math.max(last, prefs.getLong("last_upload_at", 0));
            if (!LocationPolicy.isDue(last, System.currentTimeMillis(), intervalMinutes)) return false;
            publishLocation(latitude, longitude, accuracy, batteryLevel, recordedAt);
            return true;
        }
    }

    public static boolean isLocallyEnabled(Context context) {
        initialize(context.getApplicationContext());
        return prefs.getBoolean("local_sharing", false) && !prefs.getBoolean("stop_requested", false);
    }

    /** No network or waiting for an in-flight upload: revoke local consent immediately. */
    public static void requestLocalStop(Context context) {
        initialize(context.getApplicationContext());
        CONSENT_VERSION.incrementAndGet();
        prefs.edit().putBoolean("local_sharing", false).putBoolean("stop_requested", true).apply();
    }

    public boolean hasPendingStop() { return archivedRevoke || prefs.getBoolean("stop_requested", false); }
    /** UI-safe snapshot: never waits behind a network request or decrypts tokens. */
    public boolean hasPendingStopForCurrentAccount() {
        return prefs.getBoolean("stop_requested", false)
                || (!currentUserId().isEmpty() && archivedIdentities.contains(getServerUrl() + "\n" + currentUserId()));
    }
    public String getPendingRevocationNotice() {
        return archivedRevoke ? "先前帳號仍有待撤回的位置分享。若持續無法撤回，請用原帳號重新登入完成處理。" : "";
    }

    public void retryPendingStop() throws ApiException {
        synchronized (LOCK) {
            ApiException failure = null;
            try { flushArchivedRevokeLocked(true); }
            catch (ApiException exception) { failure = exception; }
            // An unrelated old account must never prevent the current account from stopping.
            try { flushPendingStopLocked(); }
            catch (ApiException exception) { failure = exception; }
            if (failure != null) throw failure;
        }
    }

    private Object rpc(String name, JSONObject body) throws ApiException {
        synchronized (LOCK) {
            flushPendingStopLocked();
            return authenticated("/rest/v1/rpc/" + name, body);
        }
    }

    private void flushPendingStopLocked() throws ApiException {
        flushArchivedRevokeLocked(false);
        if (!prefs.getBoolean("stop_requested", false)) return;
        long version = CONSENT_VERSION.get();
        if (session != null) authenticated("/rest/v1/rpc/set_sharing", json("p_enabled", false));
        if (CONSENT_VERSION.get() == version)
            prefs.edit().putBoolean("stop_requested", false).apply();
    }

    private void reconcileAfterLoginLocked() {
        try { flushArchivedRevokeLocked(false); }
        catch (ApiException ignored) { /* Keep the task; login remains recoverable and can retry. */ }
        if (archivedRevoke) LocationSharingService.scheduleRevocation(context);
    }

    private static JSONArray readRevocationsLocked() throws ApiException {
        try {
            String encoded = secrets.read("pending_revoke");
            if (encoded == null) return new JSONArray();
            JSONObject stored = new JSONObject(encoded);
            JSONArray tasks = stored.optJSONArray("tasks");
            // Read the initial single-task format too, without losing an offline stop.
            return tasks != null ? tasks : new JSONArray().put(stored);
        } catch (GeneralSecurityException | JSONException exception) {
            throw new ApiException("無法讀取待撤回的位置分享，請檢查手機安全設定。");
        }
    }

    private static void saveRevocationsLocked(JSONArray tasks) throws ApiException {
        writeSecret("pending_revoke", tasks.length() == 0 ? null : json("tasks", tasks).toString());
        updateArchiveIdentities(tasks);
    }

    private static void updateArchiveIdentities(JSONArray tasks) {
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            JSONObject old = task == null ? null : task.optJSONObject("session");
            if (old != null) identities.add(task.optString("url") + "\n" + old.optString("user_id"));
        }
        archivedIdentities = Collections.unmodifiableSet(identities);
        archivedRevoke = tasks.length() > 0;
    }

    private void archiveCurrentSessionLocked() throws ApiException {
        JSONArray tasks = readRevocationsLocked();
        String userId = currentUserId();
        JSONObject record = json("url", getServerUrl(), "key", getPublicKey(), "session", session);
        boolean replaced = false;
        for (int index = 0; index < tasks.length(); index++) {
            JSONObject existing = tasks.optJSONObject(index);
            JSONObject old = existing == null ? null : existing.optJSONObject("session");
            if (old != null && userId.equals(old.optString("user_id"))
                    && getServerUrl().equals(existing.optString("url"))) {
                try { tasks.put(index, record); }
                catch (JSONException exception) { throw new ApiException("無法儲存撤回工作。"); }
                replaced = true;
                break;
            }
        }
        if (!replaced) tasks.put(record);
        saveRevocationsLocked(tasks);
    }

    /** Normal requests only reconcile this account; the dedicated job can retry other accounts. */
    private void flushArchivedRevokeLocked(boolean includeOtherAccounts) throws ApiException {
        if (!archivedRevoke) return;
        JSONArray tasks = readRevocationsLocked();
        ApiException failure = null;
        for (int index = 0; index < tasks.length();) {
            JSONObject record = tasks.optJSONObject(index);
            JSONObject oldSession = record == null ? null : record.optJSONObject("session");
            if (oldSession == null) throw new ApiException("待撤回工作格式無效，請檢查手機安全設定。");
            String oldUrl = record.optString("url");
            boolean currentAccount = session != null && getServerUrl().equals(oldUrl)
                    && currentUserId().equals(oldSession.optString("user_id"));
            if (!currentAccount && !includeOtherAccounts) { index++; continue; }
            try {
                if (currentAccount) {
                    // The new login repairs an expired/revoked old refresh token.
                    // Never log this fresh interactive session out after revocation.
                    authenticated("/rest/v1/rpc/set_sharing", json("p_enabled", false));
                } else {
                    JSONObject[] holder = { oldSession };
                    SessionSink sink = updated -> {
                        holder[0] = updated;
                        try { record.put("session", updated); }
                        catch (JSONException exception) { throw new ApiException("無法儲存撤回工作。"); }
                        saveRevocationsLocked(tasks);
                    };
                    authenticatedRaw(oldUrl, record.optString("key"), holder[0],
                            "/rest/v1/rpc/set_sharing", json("p_enabled", false), sink);
                    try { request(oldUrl, record.optString("key"), "/auth/v1/logout?scope=local",
                            new JSONObject(), holder[0].optString("access_token")); }
                    catch (ApiException ignored) {}
                }
                tasks.remove(index);
                saveRevocationsLocked(tasks);
            } catch (ApiException exception) {
                // Retain every failed task, including invalid old credentials, for original-account login.
                failure = exception;
                index++;
            }
        }
        saveRevocationsLocked(tasks);
        if (failure != null) throw failure;
    }

    private interface SessionSink { void save(JSONObject value) throws ApiException; }

    private Object authenticated(String path, JSONObject body) throws ApiException {
        requireConfigured();
        JSONObject current = session;
        if (current == null) throw new ApiException("請先登入。");
        return authenticatedRaw(getServerUrl(), getPublicKey(), current, path, body, ApiClient::saveSession);
    }

    private static Object authenticatedRaw(String url, String key, JSONObject initial,
                                            String path, JSONObject body, SessionSink sink) throws ApiException {
        JSONObject active = initial;
        if (active.optLong("expires_at", 0) <= System.currentTimeMillis() / 1000 + 45) {
            active = refresh(url, key, active);
            sink.save(active); // Save every rotated refresh token before doing anything else.
        }
        try { return request(url, key, path, body, active.optString("access_token")); }
        catch (ApiException exception) {
            if (exception.status != 401) throw exception;
            active = refresh(url, key, active);
            sink.save(active);
            return request(url, key, path, body, active.optString("access_token"));
        }
    }

    private static JSONObject refresh(String url, String key, JSONObject current) throws ApiException {
        String token = current.optString("refresh_token", "");
        if (token.isEmpty()) throw new ApiException("登入已過期，請重新登入。", 401);
        JSONObject result = object(request(url, key, "/auth/v1/token?grant_type=refresh_token",
                json("refresh_token", token), null));
        return normalizeSession(result);
    }

    private static JSONObject normalizeSession(JSONObject result) throws ApiException {
        JSONObject user = result.optJSONObject("user");
        if (result.optString("access_token", "").isEmpty()
                || result.optString("refresh_token", "").isEmpty() || user == null
                || user.optString("id", "").isEmpty()) throw new ApiException("登入回應不完整，請重試。");
        return json("access_token", result.optString("access_token"),
                "refresh_token", result.optString("refresh_token"), "user_id", user.optString("id"),
                "expires_at", result.optLong("expires_at", System.currentTimeMillis() / 1000
                        + result.optLong("expires_in", 3600)));
    }

    private static void saveSession(JSONObject value) throws ApiException {
        writeSecret("session", value == null ? null : value.toString());
        session = value;
    }

    private static void writeSecret(String key, String value) throws ApiException {
        try { secrets.write(key, value); }
        catch (GeneralSecurityException exception) { throw new ApiException("無法安全儲存登入資料，請檢查手機安全設定。"); }
    }

    private void requireConfigured() throws ApiException {
        if (!isConfigured()) throw new ApiException("請先設定 Supabase 雲端網址與公開金鑰。");
    }

    private static JSONObject json(Object... pairs) throws ApiException {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]); }
        catch (JSONException exception) { throw new ApiException("資料格式不正確。"); }
        return object;
    }

    private static JSONObject object(Object value) throws ApiException {
        if (value instanceof JSONObject) return (JSONObject) value;
        throw new ApiException("雲端回應格式不正確，請確認資料庫已完成設定。");
    }

    private static Object request(String baseUrl, String key, String path, JSONObject body,
                                  String bearer) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(18_000);
            connection.setRequestProperty("apikey", key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null && !bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = readResponse(stream);
            if (status < 200 || status >= 300) throw serverError(status, response);
            if (response.trim().isEmpty()) return JSONObject.NULL;
            try { return new JSONTokener(response).nextValue(); }
            catch (JSONException exception) { throw new ApiException("雲端回應格式不正確。"); }
        } catch (IOException exception) {
            throw new ApiException("目前無法連線，請確認網路後重試；停止分享會在連線恢復後補送。");
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static String readResponse(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 1_048_576) throw new IOException("Response too large");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static ApiException serverError(int status, String response) {
        String message = "";
        try {
            JSONObject error = new JSONObject(response);
            message = error.optString("msg", error.optString("message", error.optString("error_description", "")));
        } catch (JSONException ignored) {}
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("invalid login credentials")) message = "電子郵件或密碼不正確。";
        else if (lower.contains("email not confirmed")) message = "請先到信箱驗證電子郵件，再登入。";
        else if (lower.contains("password")) message = "密碼未符合要求，請使用至少 8 碼並重試。";
        else if (lower.contains("already registered")) message = "這個電子郵件已註冊，請改用登入。";
        else if (lower.contains("refresh token") || lower.contains("jwt expired")) message = "登入已過期，請重新登入。";
        else if (status == 429) message = "操作太頻繁，請稍後再試。";
        else if (status == 404) message = "找不到雲端功能，請確認家庭後端已完成本版的設定與升級。";
        else if (!message.matches(".*[\\u4e00-\\u9fff].*")) {
            if (status == 401) message = "登入或公開金鑰無效，請確認設定並重新登入。";
            else if (status == 403) message = "目前沒有權限執行這個操作。";
            else message = "雲端操作未完成，請確認設定後重試（" + status + "）。";
        }
        return new ApiException(message.length() > 220 ? message.substring(0, 220) : message, status);
    }
}
