export const INTERVALS = [30, 60, 120, 180, 240];
export const REMINDERS = [0, 5, 10, 15, 30, 60, 1440];
export const TAIPEI = 'Asia/Taipei';

export function familyScope(userId, familyId) {
  return `family:${String(userId)}:${String(familyId)}`;
}

export function recordsForScope(records, scope) {
  return records.filter(record => (record.scope || 'local') === scope);
}

export function escapeHtml(value = '') {
  return String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
}

export function taipeiDay(value = Date.now()) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  return new Intl.DateTimeFormat('en-CA', {timeZone: TAIPEI, year:'numeric', month:'2-digit', day:'2-digit'}).format(date);
}

export function taipeiDateTime(value = Date.now()) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {timeZone:TAIPEI, year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', hourCycle:'h23'}).formatToParts(date).map(p => [p.type, p.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

export function taipeiToIso(value) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) throw new Error('請輸入有效的日期與時間。');
  const date = new Date(value + ':00+08:00');
  if (!Number.isFinite(date.getTime()) || taipeiDateTime(date) !== value) throw new Error('請輸入有效的日期與時間。');
  return date.toISOString();
}

export function monthGrid(year, month, today = taipeiDay()) {
  const first = new Date(Date.UTC(year, month, 1));
  const offset = (first.getUTCDay() + 6) % 7;
  return Array.from({length:42}, (_, index) => {
    const date = new Date(Date.UTC(year, month, 1 - offset + index));
    const key = date.toISOString().slice(0,10);
    return {key, day:date.getUTCDate(), inMonth:date.getUTCMonth() === month, today:key === today};
  });
}

export function eventsOnDay(events, day) {
  const from = new Date(day + 'T00:00:00+08:00').getTime();
  return events.filter(event => new Date(event.starts_at).getTime() < from + 86400000 && new Date(event.ends_at).getTime() > from).sort((a,b) => new Date(a.starts_at) - new Date(b.starts_at));
}

export function validateEvent(fields) {
  const title = String(fields.title || '').trim();
  const description = String(fields.description || '').trim();
  const location = String(fields.location || '').trim();
  if (!title || title.length > 120) throw new Error('活動名稱請輸入 1 到 120 個字。');
  if (description.length > 4000 || location.length > 300) throw new Error('地點最多 300 字，備註最多 4000 字。');
  const starts_at = taipeiToIso(fields.start);
  const ends_at = taipeiToIso(fields.end);
  if (new Date(ends_at) <= new Date(starts_at)) throw new Error('結束時間必須晚於開始時間。');
  const reminder_minutes = Number(fields.reminder);
  if (!REMINDERS.includes(reminder_minutes)) throw new Error('請選擇有效的提醒時間。');
  return {title, description, location, starts_at, ends_at, timezone:TAIPEI, reminder_minutes};
}

export function validPosition(position, now = Date.now()) {
  const {latitude, longitude, accuracy, recordedAt} = position;
  const timestamp = new Date(recordedAt).getTime();
  return Number.isFinite(latitude) && Math.abs(latitude) <= 90 && Number.isFinite(longitude) && Math.abs(longitude) <= 180 && Number.isFinite(accuracy) && accuracy >= 0 && accuracy <= 100000 && timestamp > 0 && timestamp >= now - 120000 && timestamp <= now + 10000;
}

export function relativeTime(value, now = Date.now()) {
  const elapsed = Math.max(0, now - new Date(value).getTime());
  if (!Number.isFinite(elapsed)) return '尚無紀錄';
  if (elapsed < 60000) return '剛剛';
  if (elapsed < 3600000) return `${Math.floor(elapsed / 60000)} 分鐘前`;
  if (elapsed < 86400000) return `${Math.floor(elapsed / 3600000)} 小時前`;
  return `${Math.floor(elapsed / 86400000)} 天前`;
}

export function sanitizeLocalData(raw) {
  const input = raw && typeof raw === 'object' ? raw : {};
  const settings = input.settings && typeof input.settings === 'object' ? input.settings : {};
  return {
    name:typeof input.name === 'string' && input.name.trim() ? input.name.trim().slice(0,30) : '家人',
    settings:{interval:INTERVALS.includes(settings.interval) ? settings.interval : 60, reminders:settings.reminders === true, calendarReminders:settings.calendarReminders === true},
    events:Array.isArray(input.events) ? input.events.filter(e => e && typeof e.id === 'string' && typeof e.title === 'string' && Number.isFinite(new Date(e.starts_at).getTime()) && Number.isFinite(new Date(e.ends_at).getTime()) && new Date(e.ends_at) > new Date(e.starts_at)).slice(0,1000) : [],
    reports:Array.isArray(input.reports) ? input.reports.filter(e => e && validPosition(e, new Date(e.recordedAt).getTime())).slice(0,100) : [],
    activity:Array.isArray(input.activity) ? input.activity.filter(e => e && typeof e.text === 'string' && Number.isFinite(new Date(e.time).getTime())).slice(0,50) : []
  };
}
