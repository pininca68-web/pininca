// Browser client for the same Supabase RPC boundary used by the Android app.
// Public configuration stays on this device; passwords are never persisted.
const CONFIG_KEY = 'family-web-cloud-config';
const SESSION_KEY = 'family-web-session';
const MAX_RESPONSE_LENGTH = 1_048_576;
const REMINDERS = new Set([0, 5, 10, 15, 30, 60, 1440]);

export class CloudError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.name = 'CloudError';
    this.status = status;
  }
}

function readStorage(kind, key) {
  try { return JSON.parse(globalThis[kind]?.getItem(key) || 'null'); }
  catch { return null; }
}

function writeStorage(kind, key, value) {
  try {
    const storage = globalThis[kind];
    if (value == null) storage?.removeItem(key);
    else storage?.setItem(key, JSON.stringify(value));
  } catch { /* Private browsing can disable storage; in-memory use still works. */ }
}

function object(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value;
  throw new CloudError('雲端回應格式不正確，請確認家庭後端已完成設定。');
}

function length(value) { return [...String(value)].length; }

function text(value, minimum, maximum, label) {
  const result = String(value ?? '').trim();
  if (length(result) < minimum || length(result) > maximum) {
    throw new CloudError(`${label}請輸入 ${minimum} 到 ${maximum} 個字。`);
  }
  return result;
}

export function isPublicKey(key) {
  if (typeof key !== 'string' || key.length > 4096) return false;
  if (/^sb_publishable_[A-Za-z0-9_-]{8,}$/.test(key)) return true;
  if (key.startsWith('sb_secret_')) return false;
  try {
    const parts = key.split('.');
    if (parts.length !== 3) return false;
    const encoded = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(atob(encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=')));
    return payload.role === 'anon';
  } catch { return false; }
}

export function validatePublicConfig(url, key) {
  const normalizedUrl = String(url ?? '').trim().replace(/\/+$/, '');
  const normalizedKey = String(key ?? '').trim();
  try {
    const parsed = new URL(normalizedUrl);
    if (parsed.protocol !== 'https:' || !parsed.hostname || parsed.username || parsed.password
      || parsed.search || parsed.hash || (parsed.pathname && parsed.pathname !== '/')) throw new Error();
    if (!isPublicKey(normalizedKey)) {
      throw new CloudError('請使用 publishable 或 anon 公開金鑰，不可填入 secret 或 service_role 金鑰。');
    }
    return Object.freeze({ url: parsed.origin, key: normalizedKey });
  } catch (error) {
    if (error instanceof CloudError) throw error;
    throw new CloudError('請輸入 Supabase 專案的 HTTPS 網址，不要附加路徑。');
  }
}

export function validateLocation(location, now = Date.now()) {
  const latitude = location?.latitude;
  const longitude = location?.longitude;
  const accuracy = location?.accuracy;
  const recorded = typeof location?.recordedAt === 'number'
    ? location.recordedAt : Date.parse(location?.recordedAt);
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90
    || !Number.isFinite(longitude) || longitude < -180 || longitude > 180
    || !Number.isFinite(accuracy) || accuracy < 0 || accuracy > 100_000
    || !Number.isFinite(recorded) || recorded <= 0
    || recorded < now - 120_000 || recorded > now + 10_000) {
    throw new CloudError('定位資料無效或已過期，請重新取得位置。');
  }
  const battery = location.batteryLevel;
  if (battery != null && (!Number.isInteger(battery) || battery < 0 || battery > 100)) {
    throw new CloudError('電量資料無效，請重新取得位置。');
  }
  return {
    p_lat: latitude,
    p_lng: longitude,
    p_accuracy: accuracy,
    p_battery: battery ?? null,
    p_recorded_at: new Date(recorded).toISOString(),
  };
}

function normalizeSession(result) {
  const raw = object(result);
  const user = raw.user || { id: raw.user_id };
  if (typeof raw.access_token !== 'string' || !raw.access_token
    || typeof raw.refresh_token !== 'string' || !raw.refresh_token
    || typeof user.id !== 'string' || !user.id) {
    throw new CloudError('登入回應不完整，請重試。');
  }
  const now = Math.floor(Date.now() / 1000);
  const expiry = Number(raw.expires_at ?? now + Number(raw.expires_in ?? 3600));
  if (!Number.isFinite(expiry)) throw new CloudError('登入回應格式不正確，請重試。');
  return {
    access_token: raw.access_token,
    refresh_token: raw.refresh_token,
    expires_at: expiry,
    user_id: user.id,
    user: {
      id: user.id,
      email: typeof user.email === 'string' ? user.email : '',
      user_metadata: { display_name: String(user.user_metadata?.display_name || '') },
    },
  };
}

function serverError(status, body) {
  let message = body?.msg || body?.message || body?.error_description || '';
  message = typeof message === 'string' ? message : '';
  const lower = message.toLowerCase();
  if (lower.includes('invalid login credentials')) message = '電子郵件或密碼不正確。';
  else if (lower.includes('email not confirmed')) message = '請先到信箱驗證電子郵件，再登入。';
  else if (lower.includes('already registered')) message = '這個電子郵件已註冊，請改用登入。';
  else if (lower.includes('password')) message = '密碼未符合要求，請使用至少 8 碼並重試。';
  else if (lower.includes('refresh token') || lower.includes('jwt expired')) message = '登入已過期，請重新登入。';
  else if (status === 429) message = '操作太頻繁，請稍後再試。';
  else if (status === 404) message = '找不到雲端功能，請確認家庭後端已完成本版的設定與升級。';
  else if (!/[\u3400-\u9fff]/u.test(message)) {
    if (status === 401) message = '登入或公開金鑰無效，請確認設定並重新登入。';
    else if (status === 403) message = '目前沒有權限執行這個操作。';
    else message = `雲端操作未完成，請確認設定後重試（${status}）。`;
  }
  return new CloudError(message.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 220), status);
}

/** No request is made by construction or configuration. */
export class CloudClient {
  constructor(config = null, session = undefined) {
    this.config = Object.freeze({ url: '', key: '' });
    this.session = null;
    this.sharing = false;
    this.pendingStop = false;
    this._consentVersion = 0;
    this._queue = Promise.resolve();
    const supplied = config || readStorage('localStorage', CONFIG_KEY);
    if (supplied?.url && supplied?.key) {
      try { this.config = validatePublicConfig(supplied.url, supplied.key); }
      catch { /* An invalid saved connection is treated as unconfigured. */ }
    }
    const saved = session === undefined ? readStorage('sessionStorage', SESSION_KEY) : session;
    if (this.configured && saved && (session !== undefined || saved.url === this.config.url)) {
      try {
        this.session = normalizeSession(saved.session || saved);
        this.pendingStop = saved.pendingStop === true;
        this.sharing = saved.sharing === true && !this.pendingStop;
      } catch { writeStorage('sessionStorage', SESSION_KEY, null); }
    }
  }

  get configured() { return Boolean(this.config.url && this.config.key); }
  get signedIn() { return Boolean(this.session); }
  get userId() { return this.session?.user_id || ''; }

  invalidateConsent() {
    // Passive identity/family changes cancel this browser's queued consent.
    // They do not authorize a withdrawal against a newly observed family.
    this._consentVersion++;
    this.sharing = false;
    this._persistSession();
  }

  configure(url, key) {
    const next = validatePublicConfig(url, key);
    if ((this.signedIn || this.pendingStop)
      && (next.url !== this.config.url || next.key !== this.config.key)) {
      throw new CloudError('請先連線停止分享並登出，再更換雲端設定。');
    }
    this.config = next;
    writeStorage('localStorage', CONFIG_KEY, next);
    return next;
  }

  _requireConfigured() {
    if (!this.configured) throw new CloudError('請先設定家庭雲端連線。');
  }

  _persistSession() {
    writeStorage('sessionStorage', SESSION_KEY, this.session ? {
      url: this.config.url,
      session: this.session,
      sharing: this.sharing,
      pendingStop: this.pendingStop,
    } : null);
  }

  _enqueue(operation) {
    const result = this._queue.then(operation);
    // Every authenticated operation shares one queue, including token rotation
    // and revocation, so an upload cannot race a successful pause.
    this._queue = result.catch(() => {});
    return result;
  }

  async _request(path, body, token = null) {
    this._requireConfigured();
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), 18_000);
    try {
      const headers = { apikey: this.config.key, 'Content-Type': 'application/json', Accept: 'application/json' };
      if (token) headers.Authorization = `Bearer ${token}`;
      const response = await fetch(this.config.url + path, {
        method: 'POST', headers, body: JSON.stringify(body), signal: abort.signal,
        redirect: 'error', credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
      });
      const raw = await response.text();
      if (raw.length > MAX_RESPONSE_LENGTH) throw new CloudError('雲端回應過大，請縮小查詢範圍後重試。');
      let data = null;
      if (raw.trim()) {
        try { data = JSON.parse(raw); }
        catch {
          if (!response.ok) throw serverError(response.status, null);
          throw new CloudError('雲端回應格式不正確，請確認連線設定。');
        }
      }
      if (!response.ok) throw serverError(response.status, data);
      return data;
    } catch (error) {
      if (error instanceof CloudError) throw error;
      if (abort.signal.aborted) throw new CloudError('連線逾時，請稍後重試；操作尚未確認完成。');
      throw new CloudError('目前無法連線，請確認網路後重試；操作尚未確認完成。');
    } finally { clearTimeout(timeout); }
  }

  async _refresh() {
    if (!this.session?.refresh_token) throw new CloudError('登入已過期，請重新登入。', 401);
    const previousId = this.userId;
    const result = await this._request('/auth/v1/token?grant_type=refresh_token', {
      refresh_token: this.session.refresh_token,
    });
    const refreshed = normalizeSession(result);
    if (refreshed.user_id !== previousId) throw new CloudError('登入身分不一致，請重新登入。', 401);
    this.session = refreshed;
    this._persistSession(); // Save rotated refresh tokens before the next RPC.
  }

  async _authenticated(path, body, maySend = null) {
    this._requireConfigured();
    if (!this.session) throw new CloudError('請先登入家庭帳號。', 401);
    if (this.session.expires_at <= Date.now() / 1000 + 45) await this._refresh();
    if (maySend && !maySend()) throw new CloudError('位置分享已停止，沒有傳送位置。');
    try { return await this._request(path, body, this.session.access_token); }
    catch (error) {
      if (error.status !== 401) throw error;
      await this._refresh();
      if (maySend && !maySend()) throw new CloudError('位置分享已停止，沒有傳送位置。');
      return this._request(path, body, this.session.access_token);
    }
  }

  async _flushPendingStop() {
    if (!this.pendingStop) return;
    if (!this.session) throw new CloudError('尚有待撤回的位置分享，請登入原帳號重試。');
    const consentVersion = this._consentVersion;
    await this._authenticated('/rest/v1/rpc/set_sharing', { p_enabled: false });
    // A new stop requested during this request keeps its own marker. In
    // particular, an old response cannot release a newer queued enable.
    if (consentVersion === this._consentVersion) this.pendingStop = false;
    this.sharing = false;
    this._persistSession();
  }

  _rpc(name, body = {}) {
    return this._enqueue(async () => {
      await this._flushPendingStop();
      return this._authenticated(`/rest/v1/rpc/${name}`, body);
    });
  }

  _credentials(email, password, signup = false) {
    const normalized = String(email ?? '').trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)) throw new CloudError('請輸入有效的電子郵件。');
    if (typeof password !== 'string' || !password || (signup && password.length < 8)) {
      throw new CloudError(signup ? '密碼請使用至少 8 碼。' : '請輸入密碼。');
    }
    return { email: normalized, password };
  }

  signIn(email, password) {
    const credentials = this._credentials(email, password);
    return this._enqueue(async () => {
      const previousUserId = this.userId;
      const repairingStop = Boolean(this.session && this.pendingStop);
      if (this.session && !repairingStop) throw new CloudError('請先登出目前帳號。');
      const result = await this._request('/auth/v1/token?grant_type=password', credentials);
      const freshSession = normalizeSession(result);
      if (repairingStop && freshSession.user_id !== previousUserId) {
        // Do not discard the original account's unfinished withdrawal or keep
        // an accidental second account session alive in this tab.
        try { await this._request('/auth/v1/logout?scope=local', {}, freshSession.access_token); }
        catch { /* Original-account recovery still takes precedence. */ }
        throw new CloudError('尚有待撤回的位置分享，請使用原帳號重新登入完成處理。');
      }
      this.session = freshSession;
      this._consentVersion++;
      this.sharing = false;
      this.pendingStop = repairingStop;
      this._persistSession();
      if (repairingStop) await this._flushPendingStop();
      return this.session;
    });
  }

  signUp(email, password, name = '') {
    const credentials = this._credentials(email, password, true);
    const displayName = name ? text(name, 1, 30, '顯示名稱') : '';
    return this._enqueue(async () => {
      if (this.session) throw new CloudError('請先登出目前帳號。');
      const result = object(await this._request('/auth/v1/signup', {
        ...credentials, ...(displayName ? { data: { display_name: displayName } } : {}),
      }));
      if (!result.access_token) return { signedIn: false, confirmationRequired: true, session: null };
      this.session = normalizeSession(result);
      this.sharing = false;
      this.pendingStop = false;
      this._persistSession();
      return { signedIn: true, confirmationRequired: false, session: this.session };
    });
  }

  signOut() {
    this._consentVersion++;
    this.sharing = false;
    this.pendingStop = Boolean(this.session);
    this._persistSession();
    return this._enqueue(async () => {
      // Failed revocation keeps this session available for an explicit retry.
      await this._flushPendingStop();
      if (this.session) {
        try { await this._authenticated('/auth/v1/logout?scope=local', {}); }
        catch { /* Location is already revoked; local sign-out can finish. */ }
      }
      this.session = null;
      this.pendingStop = false;
      this._persistSession();
    });
  }

  async familyStatus() { return object(await this._rpc('family_status')); }
  createFamily(name) { return this._rpc('create_family', { p_name: text(name, 1, 50, '家庭名稱') }); }
  joinFamily(code) { return this._rpc('join_family', { p_invite_code: text(code, 1, 500, '邀請碼') }); }
  setDisplayName(name) { return this._rpc('set_display_name', { p_name: text(name, 1, 30, '顯示名稱') }); }

  async createInvite() {
    const value = await this._rpc('create_invite');
    if (typeof value !== 'string' || !value || value.length > 500) throw new CloudError('雲端邀請碼格式不正確，請重試。');
    return value;
  }

  setSharing(enabled) {
    if (typeof enabled !== 'boolean') throw new CloudError('請指定是否分享位置。');
    const consentVersion = ++this._consentVersion;
    if (!enabled) {
      // Cancel queued work immediately, before waiting for network or refresh.
      this.sharing = false;
      this.pendingStop = Boolean(this.session);
      this._persistSession();
    }
    return this._enqueue(async () => {
      if (!enabled) { await this._flushPendingStop(); return; }
      if (consentVersion !== this._consentVersion) throw new CloudError('位置分享已停止。');
      await this._flushPendingStop();
      if (consentVersion !== this._consentVersion) throw new CloudError('位置分享已停止。');
      await this._authenticated('/rest/v1/rpc/set_sharing', { p_enabled: true },
        () => consentVersion === this._consentVersion);
      if (consentVersion !== this._consentVersion) {
        this.sharing = false;
        this._persistSession();
        throw new CloudError(this.pendingStop
          ? '位置分享已停止，正在撤回雲端設定。'
          : '家庭狀態已變更，請重新確認位置分享。');
      }
      this.sharing = true;
      this._persistSession();
    });
  }

  publishLocation(location) {
    const consentVersion = this._consentVersion;
    return this._enqueue(async () => {
      const fields = validateLocation(location);
      if (!this.sharing || this.pendingStop || consentVersion !== this._consentVersion) {
        throw new CloudError('請先開啟位置分享；目前沒有傳送位置。');
      }
      return this._authenticated('/rest/v1/rpc/publish_location', fields,
        () => this.sharing && !this.pendingStop && consentVersion === this._consentVersion);
    });
  }

  async calendarList(fromIso, toIso) {
    const from = Date.parse(fromIso);
    const to = Date.parse(toIso);
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from || to - from > 400 * 86_400_000) {
      throw new CloudError('請選擇有效的日期範圍，最多可查詢 400 天。');
    }
    return object(await this._rpc('calendar_list', { p_from: new Date(from).toISOString(), p_to: new Date(to).toISOString() }));
  }

  calendarSave(fields) {
    const values = object(fields);
    const title = text(values.p_title, 1, 120, '行程標題');
    const description = String(values.p_description ?? '');
    const location = String(values.p_location ?? '').trim();
    const timezone = String(values.p_timezone || 'Asia/Taipei');
    const start = Date.parse(values.p_starts_at);
    const end = Date.parse(values.p_ends_at);
    if (length(description) > 4000 || length(location) > 300 || length(timezone) > 100
      || !Number.isFinite(start) || !Number.isFinite(end) || end <= start
      || !REMINDERS.has(values.p_reminder_minutes)
      || (values.p_id && (!Number.isInteger(values.p_expected_version) || values.p_expected_version < 1))
      || (!values.p_id && values.p_expected_version != null)) {
      throw new CloudError('行程資料無效，請檢查起訖時間、提醒時間與版本後重試。');
    }
    try { new Intl.DateTimeFormat('zh-TW', { timeZone: timezone }); }
    catch { throw new CloudError('行程時區無效，請重新選擇。'); }
    return this._rpc('calendar_save', {
      p_id: values.p_id || null,
      p_title: title,
      p_description: description,
      p_location: location,
      p_starts_at: new Date(start).toISOString(),
      p_ends_at: new Date(end).toISOString(),
      p_timezone: timezone,
      p_reminder_minutes: values.p_reminder_minutes,
      p_expected_version: values.p_expected_version ?? null,
      p_expected_family_id: values.p_expected_family_id ?? null,
    });
  }

  calendarDelete(id, version) {
    if (typeof id !== 'string' || !id || !Number.isInteger(version) || version < 1) {
      throw new CloudError('行程版本無效，請重新整理後再刪除。');
    }
    return this._rpc('calendar_delete', { p_id: id, p_expected_version: version });
  }

  async sheetSyncStatus() { return object(await this._rpc('sheet_sync_status')); }
}
