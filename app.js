import {CloudClient} from './web-api.js';
import {INTERVALS, REMINDERS, TAIPEI, familyScope, recordsForScope, escapeHtml as esc, taipeiDay, taipeiDateTime, taipeiToIso, monthGrid, eventsOnDay, validateEvent, validPosition, relativeTime, sanitizeLocalData} from './web-model.js';

const DATA_KEY = 'family-web-data-v1';
const client = new CloudClient();
let data;
let storageWarning = '';
try { data = sanitizeLocalData(JSON.parse(localStorage.getItem(DATA_KEY) || '{}')); }
catch { data = sanitizeLocalData({}); storageWarning = '無法讀取此裝置的舊資料。新資料會在儲存成功後保留。'; }
const todayParts = taipeiDay().split('-').map(Number);
const state = {
  view:['overview','calendar','family','settings'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'overview',
  year:todayParts[0], month:todayParts[1]-1, selectedDay:taipeiDay(),
  family:null, members:[], cloudEvents:[], syncing:false, syncError:'', lastSync:null, familyUncertain:client.signedIn,
  reporting:false, generation:0, busy:false, foregroundReminder:'', sheets:null
};
const root = document.querySelector('#view-content');
const dialog = document.createElement('dialog');
dialog.id = 'app-dialog'; dialog.className = 'dialog'; dialog.setAttribute('aria-labelledby','dialog-title');
document.body.append(dialog);
const toastRegion = document.querySelector('#toast-container') || document.createElement('div');
toastRegion.id = 'toast-container'; toastRegion.classList.add('toast-container'); toastRegion.setAttribute('aria-live','polite'); toastRegion.setAttribute('aria-atomic','false');
if(!toastRegion.isConnected)document.body.append(toastRegion);
let dialogSubmit = null;
let reminderBaseline = Date.now();
let lastCheckinReminder = 0;
const remindedEvents = new Set();

const dateFormat = (value, options={month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}) => {
  if (!value || !Number.isFinite(new Date(value).getTime())) return '尚無紀錄';
  return new Intl.DateTimeFormat('zh-TW',{timeZone:TAIPEI,...options}).format(new Date(value));
};
const timeFormat = value => dateFormat(value,{hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
const shared = () => Boolean(client.signedIn && state.family);
const currentScope = () => shared() ? familyScope(client.userId,state.family.id) : 'local';
const currentEvents = () => shared() ? state.cloudEvents : data.events;
const reports = () => recordsForScope(data.reports,currentScope());
const currentName = () => state.members.find(member => member.user_id === client.userId)?.display_name || data.name;
const intervalLabel = value => Number(value) === 30 ? '30 分鐘' : `${Number(value)/60} 小時`;
const reminderLabel = value => Number(value) === 0 ? '活動開始時' : Number(value) === 1440 ? '提前 1 天' : `提前 ${Number(value)} 分鐘`;
const mapLink = position => `https://www.openstreetmap.org/?mlat=${Number(position.latitude)}&mlon=${Number(position.longitude)}#map=16/${Number(position.latitude)}/${Number(position.longitude)}`;
const button = (text,action,kind='secondary',extra='') => `<button class="btn ${kind}" type="button" data-action="${action}" ${extra}>${text}</button>`;
const empty = (title,description,action='') => `<div class="empty-state"><div class="empty-state-symbol" aria-hidden="true">○</div><strong>${title}</strong><p>${description}</p>${action}</div>`;

function updateData(change) {
  const next = structuredClone(data);
  change(next);
  try { localStorage.setItem(DATA_KEY, JSON.stringify(next)); }
  catch { throw new Error('此瀏覽器目前無法儲存資料。請確認沒有封鎖網站儲存空間，或先匯出資料。'); }
  data = next;
}

function addActivity(text,scope=currentScope()) {
  try { updateData(next => { next.activity.unshift({text,time:new Date().toISOString(),scope}); next.activity = next.activity.slice(0,50); }); }
  catch { storageWarning = '本次操作已完成，但裝置紀錄無法儲存。'; }
}

function toast(message,type='success') {
  const item = document.createElement('div'); item.className = `toast ${type}`; item.textContent = message;
  toastRegion.append(item); setTimeout(()=>item.remove(),6500);
}

function openDialog(title,body,submitLabel,onSubmit,{danger=false,wide=false}={}) {
  if (dialog.open) dialog.close();
  dialog.classList.toggle('wide',wide);
  dialog.innerHTML = `<form id="dialog-form"><div class="dialog-header"><h2 id="dialog-title">${esc(title)}</h2><button class="icon-button" type="button" data-dialog-close aria-label="關閉視窗">×</button></div><div class="dialog-body">${body}<p id="dialog-error" class="form-error" role="alert" hidden></p></div><div class="dialog-footer"><button class="btn secondary" type="button" data-dialog-close>取消</button><button class="btn ${danger?'danger':'primary'}" type="submit">${esc(submitLabel)}</button></div></form>`;
  dialogSubmit = onSubmit;
  dialog.showModal();
}

function closeDialog(){ dialog.close(); dialogSubmit = null; }
dialog.addEventListener('click',event=>{if(event.target.closest('[data-dialog-close]')) closeDialog();});
dialog.addEventListener('cancel',()=>{dialogSubmit=null;});
dialog.addEventListener('submit',async event=>{
  event.preventDefault();
  const form = event.target;
  const submit = form.querySelector('[type=submit]');
  const error = form.querySelector('#dialog-error');
  error.hidden=true; submit.disabled=true; submit.setAttribute('aria-busy','true');
  const handler=dialogSubmit;
  try { await handler?.(new FormData(form)); if(dialog.open&&dialogSubmit===handler) closeDialog(); }
  catch(exception) { error.textContent=exception.message || '操作失敗，請再試一次。'; error.hidden=false; }
  finally { submit.disabled=false; submit.removeAttribute('aria-busy'); }
});

function banner() {
  const parts=[];
  if(!navigator.onLine) parts.push('<div class="status-banner warning" role="status">目前離線。此裝置的行事曆仍可使用；家庭資料需連線後重新整理。</div>');
  if(storageWarning) parts.push(`<div class="status-banner warning" role="status">${esc(storageWarning)}</div>`);
  if(client.pendingStop) parts.push(`<div class="status-banner error" role="alert">雲端位置尚未成功撤回。請保持連線並重試；若登入已失效，請重新登入原帳號完成撤回。 ${button('重試撤回','stop-sharing','danger')} ${button('重新登入原帳號','sign-in','secondary')}</div>`);
  if(state.syncError) parts.push(`<div class="status-banner error" role="alert">${esc(state.syncError)} ${button('重新整理','refresh','ghost')}</div>`);
  if(state.foregroundReminder) parts.push(`<div class="reminder-banner" role="status"><span>${esc(state.foregroundReminder)}</span>${button('知道了','dismiss-reminder','ghost')}</div>`);
  return parts.join('');
}

function navigate(view) {
  if(!['overview','calendar','family','settings'].includes(view)) return;
  const changed=state.view!==view;
  state.view=view; history.replaceState(null,'','#'+view); render();
  if(changed)window.scrollTo({top:0,left:0,behavior:'instant'});
  if(shared() && view === 'calendar') refreshCloud();
}

function render() {
  const titles={overview:['家庭總覽','每一次回報，都是一份安心。'],calendar:['家庭行事曆',shared()?'和家人一起，記得每個重要時刻。':'把重要的日子，留在這台裝置。'],family:['我的家人','讓關心保持連結，也保留彼此的選擇。'],settings:['設定','用適合自己的方式，讓家人安心。']};
  document.querySelector('#page-title').textContent=titles[state.view][0];
  document.querySelector('#page-subtitle').textContent=titles[state.view][1];
  document.querySelector('#connection-label').textContent=!navigator.onLine?'目前離線':state.syncing?'正在同步':state.syncError?'同步待重試':shared()?'家庭雲端已連線':client.signedIn?'帳號已登入':'此裝置模式';
  document.querySelector('#today-label').textContent=dateFormat(Date.now(),{month:'long',day:'numeric',weekday:'long'});
  document.querySelector('#profile-name').textContent=currentName();
  document.querySelector('#profile-initial').textContent=Array.from(currentName())[0] || '家';
  document.querySelectorAll('[data-view]').forEach(nav=>{nav.classList.toggle('active',nav.dataset.view===state.view);if(nav.dataset.view===state.view) nav.setAttribute('aria-current','page');else nav.removeAttribute('aria-current');});
  const globalCheckin=document.querySelector('#global-checkin'); if(globalCheckin){globalCheckin.disabled=state.reporting;globalCheckin.setAttribute('aria-busy',String(state.reporting));}
  const views={overview:renderOverview,calendar:renderCalendar,family:renderFamily,settings:renderSettings};
  root.innerHTML=banner()+views[state.view]();
}

function renderOverview() {
  const last=reports()[0];
  const nextEvents=currentEvents().filter(event=>new Date(event.ends_at)>Date.now()).sort((a,b)=>new Date(a.starts_at)-new Date(b.starts_at)).slice(0,3);
  const visibleActivity=recordsForScope(data.activity,currentScope()).slice(0,4);
  const own=state.members.find(member=>member.user_id===client.userId);
  const nextDue=last?new Date(new Date(last.recordedAt).getTime()+data.settings.interval*60000):null;
  return `<section class="overview-hero"><div class="hero-copy"><p class="eyebrow">A LITTLE CHECK-IN, A LOT OF PEACE</p><h2>${esc(currentName())}，今天也好好在一起。</h2><p>${shared()?`你的家庭「${esc(state.family.name)}」已連線。位置由你決定何時回報。`:'先從自己的生活開始。記下行程、回報位置，準備好時再連結家人。'}</p><div class="hero-actions">${button('立即回報我的位置','checkin','primary',state.reporting?'disabled aria-busy="true"':'')}${button('新增行程','add-event','secondary')}</div></div><div class="hero-emblem" aria-hidden="true"><span>家</span><i></i></div></section>
  <div class="stat-grid"><section class="stat-card"><p class="eyebrow">上次位置回報</p><strong>${last?esc(relativeTime(last.recordedAt)):'尚未回報'}</strong><span class="muted">${last?esc(dateFormat(last.recordedAt)):shared()?'回報後，家人才能看到位置':'你的位置由你決定'}</span></section><section class="stat-card"><p class="eyebrow">接下來的行程</p><strong>${nextEvents.length?esc(dateFormat(nextEvents[0].starts_at,{month:'numeric',day:'numeric'})):'留點空白'}</strong><span class="muted">${nextEvents.length?esc(nextEvents[0].title):'目前沒有即將到來的活動'}</span></section><section class="stat-card"><p class="eyebrow">${shared()?'家庭成員':'使用方式'}</p><strong>${shared()?`${state.members.length} 位家人`:'此裝置模式'}</strong><span class="muted">${shared()?`${state.members.filter(member=>member.sharing&&member.recorded_at).length} 位有分享位置`:'資料保存在目前瀏覽器'}</span></section></div>
  <div class="overview-grid"><section class="card location-card"><div class="card-header"><div><p class="eyebrow">MY LAST CHECK-IN</p><h3 class="card-title">我的位置</h3></div><span class="badge ${client.sharing?'success':'neutral'}">${client.sharing?'已同意本次分享':shared()?'手動回報':'僅此裝置'}</span></div><div class="map-panel"><img class="map-preview" src="./public-map.svg" alt="裝飾用地圖示意，非即時位置"/><div class="location-marker" aria-hidden="true">⌖</div><div class="map-caption">${last?'已記下你這次的位置':'下一次安心，從一次回報開始。'}</div></div><div class="location-details">${last?`<div><strong>${Number(last.latitude).toFixed(5)}, ${Number(last.longitude).toFixed(5)}</strong><p class="muted">精度約 ${Math.round(last.accuracy)} 公尺 · ${esc(dateFormat(last.recordedAt))}</p><a class="section-link" href="${mapLink(last)}" target="_blank" rel="noopener noreferrer">在 OpenStreetMap 查看位置 ↗</a><p class="form-hint">點開地圖時，座標會交給 OpenStreetMap 顯示。</p></div>`:`<div><strong>尚未取得你的位置</strong><p class="muted">按下回報並確認後，才會請求瀏覽器定位。</p></div>`}${state.reporting?'<p class="badge warning" role="status">正在取得位置…</p>':button('再回報一次','checkin','secondary')}</div>${shared()&&(client.sharing||own?.sharing||client.pendingStop)?`<div class="local-note"><span>你可以隨時撤回家庭地圖上的最新位置。</span>${button('停止分享並撤回','stop-sharing','ghost')}</div>`:''}</section>
  <section class="card"><div class="card-header"><div><p class="eyebrow">COMING UP</p><h3 class="card-title">接下來，一起記得</h3></div>${button('查看全部','go-calendar','ghost')}</div><div class="upcoming-list">${nextEvents.length?nextEvents.map(event=>eventRow(event,true)).join(''):empty('還沒有安排活動','一起吃飯、重要約定，或給自己的小提醒。',button('建立第一個行程','add-event','secondary'))}</div></section>
  <section class="card"><div class="card-header"><div><p class="eyebrow">RECENT ACTIVITY</p><h3 class="card-title">最近的安心時刻</h3></div></div><div class="activity-list">${visibleActivity.length?visibleActivity.map(item=>`<div class="activity-item"><span class="activity-dot" aria-hidden="true"></span><div><strong>${esc(item.text)}</strong><p class="muted">${esc(relativeTime(item.time))}</p></div></div>`).join(''):empty('從今天開始記錄','完成位置回報或新增行程後，這裡會留下操作紀錄。')}</div></section>
  <section class="card reminder-card"><div class="card-header"><div><p class="eyebrow">AT YOUR OWN PACE</p><h3 class="card-title">剛剛好的關心</h3></div></div><div class="reminder-content"><span class="reminder-visual" aria-hidden="true">◷</span><strong>每 ${intervalLabel(data.settings.interval)}，記得回報</strong><p class="muted">${data.settings.reminders?(nextDue?`下一次約 ${esc(timeFormat(nextDue))} 提醒你。`:'分頁開啟期間，會在所選間隔提醒你。'):'目前提醒已關閉，你仍可隨時手動回報。'}</p>${button('調整提醒設定','go-settings','secondary')}</div><p class="form-hint">網頁提醒只在分頁開啟時運作，鎖定螢幕或分頁關閉後不保證通知。</p></section></div>
  ${!shared()?`<div class="local-note"><span><strong>目前資料只保存在這台裝置。</strong> 連結家庭雲端後，才會和家人共用位置與行事曆。</span>${button('連結我的家人','go-family','ghost')}</div>`:''}`;
}

function eventRow(event,compact=false) {
  return `<article class="event-row"><div class="event-date"><span>${esc(dateFormat(event.starts_at,{month:'short'}))}</span><strong>${esc(dateFormat(event.starts_at,{day:'numeric'}).replace('日',''))}</strong></div><div class="event-info"><strong>${esc(event.title)}</strong><p class="muted">${esc(timeFormat(event.starts_at))}–${esc(timeFormat(event.ends_at))}${event.location?` · ${esc(event.location)}`:''}</p>${!compact&&event.description?`<p class="event-description">${esc(event.description)}</p>`:''}${!compact?`<span class="form-hint">${esc(reminderLabel(event.reminder_minutes))} · 台北時間</span>`:''}</div><div class="event-actions"><button class="icon-button" data-action="edit-event" data-id="${esc(event.id)}" type="button" aria-label="編輯${esc(event.title)}">↗</button>${!compact?`<button class="icon-button" data-action="delete-event" data-id="${esc(event.id)}" type="button" aria-label="刪除${esc(event.title)}">×</button>`:''}</div></article>`;
}

function renderCalendar() {
  const events=currentEvents();
  const selected=eventsOnDay(events,state.selectedDay);
  const grid=monthGrid(state.year,state.month);
  return `<div class="page-intro"><div><span class="badge ${shared()?'success':'neutral'}">${shared()?'家庭共用行事曆':'此裝置行事曆'}</span><p class="muted">${shared()?'家庭成員可新增、編輯活動；只會顯示最近一次成功同步的內容。':'活動只保存在目前瀏覽器。連結家庭後不會自動上傳這些私人行程。'}</p></div>${button('＋ 新增行程','add-event','primary')}</div><div class="calendar-layout"><section class="card calendar-card"><div class="card-header"><h2 class="card-title">${state.year} 年 ${state.month+1} 月</h2><div class="toolbar"><button class="icon-button" data-action="previous-month" aria-label="上一個月" type="button">‹</button>${button('今天','calendar-today','ghost')}<button class="icon-button" data-action="next-month" aria-label="下一個月" type="button">›</button></div></div><div class="calendar-grid" role="group" aria-label="${state.year} 年 ${state.month+1} 月月曆">${['一','二','三','四','五','六','日'].map(day=>`<div class="calendar-weekday">${day}</div>`).join('')}${grid.map(day=>{const count=eventsOnDay(events,day.key).length;return `<button type="button" class="calendar-day ${day.inMonth?'':'outside'} ${day.today?'today':''} ${day.key===state.selectedDay?'selected':''}" data-action="select-day" data-day="${day.key}" aria-pressed="${day.key===state.selectedDay}" aria-label="${day.key}${count?`，${count} 個活動`:''}"><span class="calendar-day-number">${day.day}</span>${count?`<span class="calendar-dot" aria-hidden="true"></span><span class="calendar-event-count">${count} 個活動</span>`:''}</button>`;}).join('')}</div><p class="form-hint calendar-footnote">所有活動與提醒均使用台北時間（UTC+8）。</p></section><section class="card calendar-events"><div class="card-header"><div><p class="eyebrow">YOUR DAY</p><h3 class="card-title">${esc(dateFormat(state.selectedDay+'T12:00:00+08:00',{month:'long',day:'numeric',weekday:'short'}))}</h3></div><span class="badge neutral">${selected.length} 個活動</span></div>${selected.length?selected.map(event=>eventRow(event)).join(''):empty('這一天，還有空白','替重要的事情留一個位置。',button('新增這天的行程','add-selected-event','secondary'))}</section></div>`;
}

function renderFamily() {
  if(!client.configured) return `<section class="card family-welcome"><p class="eyebrow">TOGETHER, WITH YOUR PERMISSION</p><h2>讓每份關心，都有一個家。</h2><p>連結家庭雲端後，家人可以共用最新位置與行事曆。每個人使用自己的帳號，並自行決定何時分享。</p>${button('設定家庭雲端','go-settings','primary')}<div class="local-note">目前是此裝置模式，尚未連接任何家庭雲端。你仍可使用自己的行事曆和位置紀錄。</div></section>`;
  if(!client.signedIn) return `<section class="card family-welcome"><span class="badge neutral">雲端設定已儲存</span><h2>和家人，保持剛剛好的連結。</h2><p>登入自己的帳號後，可以建立家庭或輸入邀請碼加入。</p><div class="hero-actions">${button('登入我的帳號','sign-in','primary')}${button('註冊新帳號','sign-up','secondary')}</div><p class="form-hint">每位家人應使用自己的帳號。登入狀態僅保留在目前分頁的工作階段。</p></section>`;
  if(!state.family) return `<div class="page-intro"><div><span class="badge success">已登入</span><p class="muted">${esc(client.session?.user?.email||'帳號已就緒')} · 尚未加入家庭</p></div>${button('重新整理','refresh','secondary',state.syncing?'disabled':'')}</div><section class="card family-welcome"><h2>一起建立，你們的安心日常。</h2><p>由一位家人建立家庭，再把一次性邀請碼交給其他家人。</p><div class="hero-actions">${button('建立我的家庭','create-family','primary')}${button('輸入邀請碼加入','join-family','secondary')}</div></section>`;
  return `<div class="page-intro"><div><span class="badge success">家庭雲端</span><h2>${esc(state.family.name)}</h2><p class="muted">${state.members.length} 位成員 · ${state.lastSync?`最近同步 ${esc(relativeTime(state.lastSync))}`:'尚未完成同步'}</p></div><div class="toolbar">${button('邀請家人','invite-family','primary')}${button('重新整理','refresh','secondary',state.syncing?'disabled':'')}</div></div><div class="member-grid">${state.members.map(member=>{const own=member.user_id===client.userId;const fix=member.sharing&&Number.isFinite(member.latitude)&&Number.isFinite(member.longitude);return `<article class="card member-card"><div class="row"><span class="avatar">${esc(Array.from(member.display_name||'家')[0])}</span><div><h3>${esc(member.display_name)}${own?' <small class="muted">（我）</small>':''}</h3><span class="badge ${fix?'success':'neutral'}">${fix?'已有分享位置':member.sharing?'等待位置回報':'未分享位置'}</span></div></div><p class="muted">${fix?`最後回報：${esc(dateFormat(member.recorded_at))}`:'位置由本人確認後才會分享。'}</p>${fix?`<a class="btn secondary" href="${mapLink(member)}" target="_blank" rel="noopener noreferrer">在地圖查看 ↗</a><p class="form-hint">點開地圖會將這筆座標提供給 OpenStreetMap。</p>`:''}${own?`<div class="form-actions">${button('回報我的位置','checkin','primary',state.reporting?'disabled':'')}${member.sharing||client.sharing||client.pendingStop?button('停止分享並撤回','stop-sharing','ghost'):''}</div>`:''}</article>`;}).join('')}</div><section class="card"><div class="card-header"><div><h3 class="card-title">Google 試算表紀錄</h3><p class="muted">同步由你們的家庭後端負責，需事先完成設定。</p></div>${button('檢查同步狀態','sheet-status','secondary')}</div>${state.sheets?`<p>待同步 ${Number(state.sheets.queued)||0} 筆 · 失敗 ${Number(state.sheets.failed)||0} 筆</p><p class="muted">最近成功：${esc(dateFormat(state.sheets.last_delivered_at))}</p>`:''}</section>`;
}

function renderSettings() {
  return `<div class="settings-grid"><section class="card settings-section"><div class="card-header"><div><p class="eyebrow">YOUR PROFILE</p><h2 class="card-title">你想被怎麼稱呼？</h2></div></div><form id="profile-form" class="stack"><label class="field">顯示名稱<input name="name" value="${esc(currentName())}" maxlength="30" required autocomplete="nickname"/></label><p class="form-hint">${shared()?'儲存後也會更新家庭中的顯示名稱。':'這個名字會顯示在目前裝置的畫面。'}</p><div>${button('儲存名稱','submit-profile','primary')}</div></form></section><section class="card settings-section"><div class="card-header"><div><p class="eyebrow">GENTLE REMINDERS</p><h2 class="card-title">回報與活動提醒</h2></div></div><form id="reminder-form" class="stack"><label class="field">回報提醒間隔<select name="interval">${INTERVALS.map(value=>`<option value="${value}" ${data.settings.interval===value?'selected':''}>${intervalLabel(value)}</option>`).join('')}</select></label><label class="toggle-row"><span><strong>提醒我回報位置</strong><small>成功回報後，重新計算下一次提醒。</small></span><input type="checkbox" name="reminders" ${data.settings.reminders?'checked':''}/></label><label class="toggle-row"><span><strong>提醒即將開始的活動</strong><small>依每個活動設定的提前時間提醒。</small></span><input type="checkbox" name="calendarReminders" ${data.settings.calendarReminders?'checked':''}/></label><div class="form-actions">${button('儲存提醒設定','submit-reminders','primary')}${button('允許瀏覽器通知','enable-notifications','secondary')}</div></form><p class="form-hint">網頁無法在背景偵測 LINE 等 App 的使用。提醒需保持此分頁開啟；手機休眠、鎖定螢幕與瀏覽器限制可能延後或阻止通知。</p></section><section class="card settings-section"><div class="card-header"><div><p class="eyebrow">FAMILY CONNECTION</p><h2 class="card-title">家庭雲端連線</h2></div><span class="badge ${shared()?'success':'neutral'}">${shared()?'已連結家庭':client.configured?'設定已儲存':'尚未設定'}</span></div><p class="muted">使用與 Android App 相同的家庭雲端設定。未連線時，個人活動與位置紀錄仍只保存在這台裝置。</p><details class="cloud-details"><summary>設定 Supabase 雲端連線</summary><form id="cloud-form" class="stack"><label class="field">專案 HTTPS 網址<input name="url" type="url" placeholder="https://your-project.supabase.co" value="${esc(client.config?.url||'')}" required ${client.signedIn?'disabled':''}/></label><label class="field">公開用戶端金鑰<textarea name="key" rows="3" placeholder="sb_publishable_… 或 anon 公開金鑰" required spellcheck="false" autocomplete="off" ${client.signedIn?'disabled':''}>${esc(client.config?.key||'')}</textarea></label><p class="form-hint">只接受 publishable 或 anon 公開金鑰，請勿填入 secret 或 service_role 金鑰。家庭後端需先完成資料表與權限設定。</p>${button('儲存雲端設定','submit-cloud','primary',client.signedIn?'disabled':'')}<a class="section-link" href="https://github.com/pininca68-web/pininca/blob/main/README.md#建立家庭同步後端" target="_blank" rel="noopener noreferrer">查看雲端建立說明 ↗</a></form></details><div class="form-actions">${client.signedIn?`${button('前往我的家庭','go-family','secondary')}${button('登出帳號','sign-out','ghost')}`:client.configured?`${button('登入帳號','sign-in','primary')}${button('註冊新帳號','sign-up','secondary')}`:''}</div><p class="form-hint">${client.signedIn?'登出前會先撤回雲端最新位置。若離線撤回失敗，請連線後重試。':'登入後才會讀取家庭資料；這台裝置的私人行程不會自動上傳。'}</p></section><section class="card settings-section"><div class="card-header"><div><p class="eyebrow">YOUR DEVICE, YOUR DATA</p><h2 class="card-title">此裝置資料</h2></div></div><p class="muted">本機有 ${data.events.length} 個私人行程與 ${data.reports.length} 筆位置紀錄。清除瀏覽器資料會一併移除本機紀錄。</p><div class="form-actions">${button('匯出本機資料','export-data','secondary')}${button('清除本機位置紀錄','clear-reports','ghost')}</div><p class="form-hint">匯出檔可能含你自己的位置與行程，請妥善保管。清除本機紀錄不會刪除家庭後端或 Google 試算表的歷史資料。</p></section></div>`;
}

function eventDialog(existing=null,day=null) {
  if(client.signedIn&&state.familyUncertain){toast('請先重新整理，確認目前家庭後再編輯行程。','warning');return;}
  const scope=shared()?state.family.id:'local';
  const selected=day || taipeiDay();
  const start=existing?taipeiDateTime(existing.starts_at):selected+'T09:00';
  const end=existing?taipeiDateTime(existing.ends_at):selected+'T10:00';
  openDialog(existing?'編輯行程':'新增行程',`<p class="form-hint">${scope==='local'?'此行程僅保存在這台裝置。':'此行程會與目前家庭共用。'}時間均為台北時間。</p><div class="stack"><label class="field">活動名稱<input name="title" required maxlength="120" value="${esc(existing?.title||'')}" placeholder="例如：一起吃晚餐" autofocus/></label><div class="form-grid"><label class="field">開始時間<input type="datetime-local" name="start" required value="${start}"/></label><label class="field">結束時間<input type="datetime-local" name="end" required value="${end}"/></label></div><label class="field">地點<input name="location" maxlength="300" value="${esc(existing?.location||'')}" placeholder="在哪裡見面？"/></label><label class="field">備註<textarea name="description" maxlength="4000" rows="3" placeholder="想讓家人知道的事…">${esc(existing?.description||'')}</textarea></label><label class="field">提醒時間<select name="reminder">${REMINDERS.map(value=>`<option value="${value}" ${Number(existing?.reminder_minutes??15)===value?'selected':''}>${reminderLabel(value)}</option>`).join('')}</select></label></div>`,'儲存行程',async form=>{
    const fields=validateEvent(Object.fromEntries(form));
    if(scope!==(shared()?state.family.id:'local')) throw new Error('目前家庭已變更，請重新開啟行程。');
    if(scope==='local') {
      updateData(next=>{const saved={...fields,id:existing?.id||crypto.randomUUID(),version:(existing?.version||0)+1};next.events=next.events.filter(item=>item.id!==saved.id);next.events.push(saved);});
    } else {
      const saved=await client.calendarSave({p_id:existing?.id||null,p_title:fields.title,p_description:fields.description,p_location:fields.location,p_starts_at:fields.starts_at,p_ends_at:fields.ends_at,p_timezone:TAIPEI,p_reminder_minutes:fields.reminder_minutes,p_expected_version:existing?.version??null,p_expected_family_id:scope});
      if(!shared()||state.family.id!==scope||saved.family_id!==scope)throw new Error('家庭已變更，請重新整理以確認剛才的行程。');
      state.cloudEvents=state.cloudEvents.filter(item=>item.id!==saved.id).concat(saved);
    }
    state.selectedDay=taipeiDay(fields.starts_at); const parts=state.selectedDay.split('-').map(Number);state.year=parts[0];state.month=parts[1]-1;
    addActivity(`${existing?'更新':'新增'}行程「${fields.title}」`); toast(existing?'行程已更新':'行程已新增'); render();
  },{wide:true});
}

function deleteEvent(id) {
  const event=currentEvents().find(item=>item.id===id); if(!event) return;
  const scope=shared()?state.family.id:'local';
  openDialog('刪除這個行程？',`<p>你確定要刪除「<strong>${esc(event.title)}</strong>」嗎？${scope==='local'?'這個行程將從目前裝置移除。':'這個行程也會從家人的共用行事曆移除。'}</p>`,'刪除行程',async()=>{
    if(scope!==(shared()?state.family.id:'local')) throw new Error('目前家庭已變更，請重新整理。');
    if(scope==='local') updateData(next=>{next.events=next.events.filter(item=>item.id!==id);});
    else {await client.calendarDelete(id,event.version);state.cloudEvents=state.cloudEvents.filter(item=>item.id!==id);}
    addActivity(`刪除行程「${event.title}」`);toast('行程已刪除');render();
  },{danger:true});
}

function requestCheckin() {
  if(state.reporting) return;
  if(client.signedIn&&state.familyUncertain){toast('請先重新整理並確認目前家庭，再回報位置。','warning');return;}
  if(client.signedIn&&state.syncing&&!state.family){toast('家庭資料還在同步，請稍候再回報。','warning');return;}
  const scope=shared()?state.family.id:'local';
  const text=scope==='local'?'取得這一次的位置與精度，保存在目前瀏覽器，供你自己查看。不會自動分享給家人。':`取得這一次的位置與精度，傳送給「${esc(state.family.name)}」的家人。家庭後端與已設定的 Google 試算表可能保留歷史紀錄；停止分享會撤回家庭地圖上的最新位置。`;
  openDialog(scope==='local'?'記下我現在的位置':'向家人回報這次的位置',`<p>${text}</p><p class="form-hint">按下確認後，瀏覽器才會請求定位權限。這是單次回報，不會持續追蹤。</p>`,'確認並取得位置',async()=>{
    if(scope!==(shared()?state.family.id:'local')) throw new Error('目前家庭已變更，請重新確認。');
    closeDialog(); await performCheckin(scope);
  });
}

async function performCheckin(familyId) {
  const generation=++state.generation;const userId=client.userId;
  state.reporting=true;render();
  try {
    if(!isSecureContext||!navigator.geolocation) throw new Error('這個瀏覽器無法使用定位，請以 HTTPS 網址在手機瀏覽器開啟。');
    const fix=await new Promise((resolve,reject)=>navigator.geolocation.getCurrentPosition(resolve,error=>reject(new Error(({1:'定位權限未開啟。你可以在瀏覽器的網站設定中允許定位後再試一次。',2:'目前無法取得位置。請確認手機定位已開啟，並移動到訊號較好的地方。',3:'取得位置逾時，請稍後再試一次。'})[error.code]||'無法取得位置，請再試一次。')),{enableHighAccuracy:true,maximumAge:0,timeout:20000}));
    if(generation!==state.generation) return;
    const report={latitude:fix.coords.latitude,longitude:fix.coords.longitude,accuracy:fix.coords.accuracy,recordedAt:new Date(fix.timestamp).toISOString(),batteryLevel:null,scope:familyId==='local'?'local':familyScope(userId,familyId)};
    if(!validPosition(report)) throw new Error('這次取得的位置已過期或精度資料無效，請重新回報。');
    if(familyId!=='local') {
      if(state.familyUncertain||!shared()||state.family.id!==familyId||client.userId!==userId) throw new Error('帳號或家庭已變更，本次位置沒有傳送。');
      await client.setSharing(true);
      if(generation!==state.generation) return;
      await client.publishLocation(report);
      if(generation!==state.generation) return;
    }
    try { updateData(next=>{next.reports.unshift(report);next.reports=next.reports.slice(0,100);}); }
    catch(error){if(familyId==='local')throw error;storageWarning='位置已傳送，但目前無法保存這台裝置的回報紀錄。';}
    reminderBaseline=Date.now();lastCheckinReminder=0;
    addActivity(familyId==='local'?'記下目前位置（僅此裝置）':'已向家人回報位置',report.scope);
    toast(familyId==='local'?'位置已保存在這台裝置':'位置已成功傳送給家人');
    if(familyId!=='local') await refreshCloud();
  } catch(error){if(generation===state.generation)toast(error.message||'位置回報失敗，請再試一次。','error');}
  finally {if(generation===state.generation){state.reporting=false;render();}}
}

async function stopSharing() {
  ++state.generation;state.reporting=false;render();
  try {await client.setSharing(false);addActivity('已停止分享並撤回雲端位置');toast('已停止分享，雲端最新位置已撤回。');await refreshCloud();}
  catch(error){state.syncError='位置尚未成功撤回：'+error.message;toast(state.syncError,'error');}
  render();
}

async function refreshCloud() {
  if(!client.signedIn) return;
  if(state.syncing){state.refreshAgain=true;return;}
  state.syncing=true;state.syncError='';state.familyUncertain=true;render();
  const userId=client.userId;
  const requestedMonth=`${state.year}-${state.month}`;
  try {
    const result=await client.familyStatus();
    if(client.userId!==userId) return;
    const previousFamilyId=state.family?.id||null;
    const nextFamily=result.family||null;
    if(previousFamilyId!==(nextFamily?.id||null)){
      state.cloudEvents=[];state.sheets=null;state.lastSync=null;
      ++state.generation;state.reporting=false;
      client.invalidateConsent?.();
    }
    state.family=nextFamily;state.members=Array.isArray(result.members)?result.members:[];
    if(state.family) {
      const requestedFamilyId=state.family.id;
      const from=new Date(Date.UTC(state.year,state.month-1,1)-8*3600000).toISOString();
      const to=new Date(Date.UTC(state.year,state.month+3,1)-8*3600000).toISOString();
      const calendar=await client.calendarList(from,to);
      if(client.userId!==userId) return;
      if(calendar.family_id!==requestedFamilyId||state.family?.id!==requestedFamilyId){
        state.cloudEvents=[];state.sheets=null;state.members=[];
        ++state.generation;state.reporting=false;client.invalidateConsent?.();
        throw new Error('家庭在同步期間已變更，請重新整理後再使用家庭功能。');
      }
      state.cloudEvents=Array.isArray(calendar.events)?calendar.events:[];
    } else state.cloudEvents=[];
    state.familyUncertain=false;
    state.lastSync=new Date().toISOString();
  } catch(error) {if(client.userId===userId)state.syncError='家庭資料更新失敗：'+(error.message||'請稍後重試。');}
  finally {state.syncing=false;render();if(client.signedIn&&(state.refreshAgain||requestedMonth!==`${state.year}-${state.month}`)){state.refreshAgain=false;refreshCloud();}}
}

function authDialog(signup=false) {
  if(!client.configured){navigate('settings');return;}
  openDialog(signup?'建立自己的帳號':'登入我的帳號',`<div class="stack">${signup?`<label class="field">顯示名稱<input name="name" maxlength="30" value="${esc(data.name)}" required autocomplete="nickname"/></label>`:''}<label class="field">電子郵件<input name="email" type="email" required autocomplete="email" placeholder="you@example.com"/></label><label class="field">密碼<input name="password" type="password" minlength="6" required autocomplete="${signup?'new-password':'current-password'}"/></label><p class="form-hint">${signup?'註冊後，請依你們的雲端設定完成電子郵件驗證。':'登入狀態保留在目前分頁工作階段。'}</p></div>`,signup?'註冊帳號':'登入',async form=>{
    const result=signup?await client.signUp(form.get('email'),form.get('password'),form.get('name')):await client.signIn(form.get('email'),form.get('password'));
    if(signup&&result.confirmationRequired){toast('註冊已送出，請到電子郵件完成驗證，再回來登入。');render();return;}
    state.family=null;state.members=[];state.cloudEvents=[];state.sheets=null;state.lastSync=null;state.familyUncertain=true;state.syncError='';toast('登入成功');navigate('family');await refreshCloud();
  });
}

function createFamilyDialog(join=false) {
  openDialog(join?'加入家人的家庭':'建立我的家庭',`<label class="field">${join?'一次性邀請碼':'家庭名稱'}<input name="value" required maxlength="${join?200:50}" placeholder="${join?'輸入家人傳給你的邀請碼':'例如：我們這一家'}" autocomplete="off"/></label><p class="form-hint">${join?'邀請碼由家庭成員產生，24 小時內可使用一次。':'建立後，可以產生邀請碼讓其他家人加入。'}</p>`,join?'加入家庭':'建立家庭',async form=>{
    if(join)await client.joinFamily(String(form.get('value')).trim());else await client.createFamily(String(form.get('value')).trim());
    toast(join?'已加入家庭':'家庭已建立');await refreshCloud();
  });
}

async function inviteFamily() {
  try {const code=await client.createInvite();openDialog('邀請家人加入',`<p>請把這組一次性邀請碼交給想加入的家人。有效 24 小時，限使用一次；再次產生會取代你前一組尚未使用的邀請碼。</p><label class="field">邀請碼<input class="invite-code" value="${esc(code)}" readonly data-invite-code/></label><p class="form-hint">家人登入自己的帳號後，選擇「輸入邀請碼加入」。</p>`,'複製邀請碼',async()=>{if(!navigator.clipboard)throw new Error('此瀏覽器無法自動複製，請手動選取並複製邀請碼。');await navigator.clipboard.writeText(String(code));toast('邀請碼已複製');});}
  catch(error){toast(error.message,'error');}
}

async function signOut() {
  ++state.generation;state.reporting=false;
  try {await client.signOut();state.family=null;state.members=[];state.cloudEvents=[];state.sheets=null;state.lastSync=null;state.familyUncertain=false;state.syncError='';toast('已登出；雲端最新位置已撤回。');render();}
  catch(error){state.syncError='尚未完成登出，請先撤回位置後重試：'+error.message;render();}
}

async function loadSheetStatus(){
  const scope=currentScope();
  const result=await client.sheetSyncStatus();
  if(!shared()||currentScope()!==scope)return;
  state.sheets=result;render();
}

async function handleAction(action,target) {
  if(client.signedIn&&state.familyUncertain&&['create-family','join-family','invite-family','sheet-status','delete-event'].includes(action)){toast('請先重新整理，確認目前家庭後再操作。','warning');return;}
  const forms={'submit-profile':'profile-form','submit-reminders':'reminder-form','submit-cloud':'cloud-form'};
  if(forms[action]){document.getElementById(forms[action])?.requestSubmit();return;}
  const actions={checkin:requestCheckin,'go-calendar':()=>navigate('calendar'),'go-family':()=>navigate('family'),'go-settings':()=>navigate('settings'),'add-event':()=>eventDialog(),'add-selected-event':()=>eventDialog(null,state.selectedDay),'edit-event':()=>eventDialog(currentEvents().find(event=>event.id===target.dataset.id)),'delete-event':()=>deleteEvent(target.dataset.id),'calendar-today':()=>{const p=taipeiDay().split('-').map(Number);state.year=p[0];state.month=p[1]-1;state.selectedDay=taipeiDay();render();if(shared())refreshCloud();},'previous-month':()=>changeMonth(-1),'next-month':()=>changeMonth(1),'select-day':()=>{state.selectedDay=target.dataset.day;render();},'sign-in':()=>authDialog(),'sign-up':()=>authDialog(true),'sign-out':signOut,'create-family':()=>createFamilyDialog(),'join-family':()=>createFamilyDialog(true),'invite-family':inviteFamily,'stop-sharing':stopSharing,refresh:refreshCloud,'dismiss-reminder':()=>{state.foregroundReminder='';render();},'enable-notifications':async()=>{if(!('Notification'in window)){toast('此瀏覽器不支援網頁通知，分頁內仍會顯示提醒。','warning');return;}const result=await Notification.requestPermission();toast(result==='granted'?'通知已允許。請保持此分頁開啟，才能接收網頁提醒。':'未允許通知；分頁內提醒仍可使用。',result==='granted'?'success':'warning');},'sheet-status':loadSheetStatus,'export-data':()=>{const blob=new Blob([JSON.stringify({exported_at:new Date().toISOString(),...data},null,2)],{type:'application/json'});const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download=`family-records-${taipeiDay()}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000);},'clear-reports':()=>openDialog('清除這台裝置的位置紀錄？','<p>這會清除目前瀏覽器儲存的位置紀錄。家庭雲端與 Google 試算表的歷史資料不會一併刪除。</p>','清除本機紀錄',async()=>{updateData(next=>{next.reports=[];});toast('本機位置紀錄已清除');render();},{danger:true})};
  if(!actions[action])return;
  try{await actions[action]();}catch(error){toast(error.message||'操作失敗，請再試一次。','error');}
}

function changeMonth(offset){const next=new Date(Date.UTC(state.year,state.month+offset,1));state.year=next.getUTCFullYear();state.month=next.getUTCMonth();state.selectedDay=`${state.year}-${String(state.month+1).padStart(2,'0')}-01`;render();if(shared())refreshCloud();}

document.addEventListener('click',event=>{
  const nav=event.target.closest('[data-view]');if(nav){navigate(nav.dataset.view);return;}
  if(event.target.closest('#global-checkin')){requestCheckin();return;}
  if(event.target.closest('#global-settings')){navigate('settings');return;}
  const action=event.target.closest('[data-action]');if(action&&!action.disabled)handleAction(action.dataset.action,action);
});

root.addEventListener('submit',async event=>{
  event.preventDefault();const form=event.target;const values=new FormData(form);const buttons=[...form.querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);
  try{
    if(form.id==='profile-form'){const name=String(values.get('name')).trim();if(!name||name.length>30)throw new Error('顯示名稱請輸入 1 到 30 個字。');if(shared())await client.setDisplayName(name);updateData(next=>{next.name=name;});if(shared())await refreshCloud();toast('顯示名稱已儲存');}
    else if(form.id==='reminder-form'){const interval=Number(values.get('interval'));if(!INTERVALS.includes(interval))throw new Error('請選擇有效的回報間隔。');updateData(next=>{next.settings={interval,reminders:values.has('reminders'),calendarReminders:values.has('calendarReminders')};});reminderBaseline=Date.now();lastCheckinReminder=0;toast('提醒設定已儲存');}
    else if(form.id==='cloud-form'){await client.configure(String(values.get('url')),String(values.get('key')));state.family=null;state.members=[];state.cloudEvents=[];state.syncError='';toast('雲端設定已儲存，可以登入自己的帳號。');}
    render();
  }catch(error){toast(error.message||'儲存失敗，請再試一次。','error');}
  finally{buttons.forEach(b=>b.disabled=false);}
});

function notify(message){state.foregroundReminder=message;if('Notification'in window&&Notification.permission==='granted'){try{new Notification('家人在這裡',{body:message,tag:'family-reminder'});}catch{ /* In-page reminder remains available. */ }}toast(message,'warning');render();}
function checkReminders(){
  if(document.visibilityState!=='visible')return;
  const now=Date.now();
  const last=reports()[0];const baseline=last?Math.max(new Date(last.recordedAt).getTime(),reminderBaseline):reminderBaseline;
  if(data.settings.reminders&&now-baseline>=data.settings.interval*60000&&now-lastCheckinReminder>=data.settings.interval*60000){lastCheckinReminder=now;notify('到回報時間了。方便的時候，記得回報一下自己的位置。');}
  if(data.settings.calendarReminders)for(const event of currentEvents()){const deadline=new Date(event.starts_at).getTime()-Number(event.reminder_minutes)*60000;const key=`${currentScope()}:${event.id}:${event.version||1}`;if(now>=deadline&&now<new Date(event.starts_at).getTime()+60000&&now-deadline<120000&&!remindedEvents.has(key)){remindedEvents.add(key);notify(`活動提醒：${event.title}，${timeFormat(event.starts_at)} 開始。`);}}
}
window.addEventListener('online',()=>{render();if(client.signedIn)refreshCloud();});
window.addEventListener('offline',render);
window.addEventListener('storage',event=>{if(event.key===DATA_KEY){try{data=sanitizeLocalData(JSON.parse(event.newValue||'{}'));if(!dialog.open)render();}catch{toast('另一個分頁的裝置資料無法讀取，請重新整理。','warning');}}});
window.addEventListener('hashchange',()=>navigate(location.hash.slice(1)));
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'){checkReminders();if(client.signedIn&&state.view!=='settings')refreshCloud();else if(state.view==='overview')render();}});
setInterval(()=>{checkReminders();if(client.signedIn&&navigator.onLine&&document.visibilityState==='visible'&&!dialog.open&&state.view!=='settings')refreshCloud();else if(!dialog.open&&state.view==='overview'&&!state.reporting)render();},60000);
render();
if(client.signedIn)refreshCloud();
