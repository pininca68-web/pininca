package tw.family.locator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CalendarView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A real shared calendar. Cached data stays explicitly dated while a phone is offline. */
public final class FamilyCalendarActivity extends Activity {
    private static final int CREAM = Color.rgb(247, 245, 237), GREEN = Color.rgb(35, 84, 63);
    private static final int INK = Color.rgb(35, 47, 41), MUTED = Color.rgb(105, 114, 105);
    private static final int SOFT = Color.rgb(232, 237, 226), AMBER = Color.rgb(148, 91, 21);
    private static final int REQUEST_NOTIFICATIONS = 72;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> controls = new ArrayList<>();
    private CalendarRepository repository;
    private ApiClient api;
    private LinearLayout eventsList;
    private TextView status, dayHeading;
    private ProgressBar progress;
    private Switch reminderSwitch;
    private CalendarView calendarWidget;
    private LocalDate selected = LocalDate.now(CalendarPolicy.FAMILY_ZONE);
    private boolean resumed, busy, destroyed, syncing;
    private String syncError = "";
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            refresh(false);
            main.postDelayed(this, 60_000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        api = new ApiClient(getApplicationContext());
        repository = new CalendarRepository(getApplicationContext());
        if (state != null) {
            try { selected = LocalDate.parse(state.getString("day", selected.toString())); }
            catch (Exception ignored) {}
        }
        selectNotificationEvent(getIntent());
        LinearLayout root = column();
        root.setBackgroundColor(CREAM);
        root.setPadding(dp(18), dp(14), dp(18), dp(10));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left + dp(18), bars.top + dp(12), bars.right + dp(18), bars.bottom + dp(10));
            } else view.setPadding(insets.getSystemWindowInsetLeft() + dp(18), insets.getSystemWindowInsetTop() + dp(12),
                    insets.getSystemWindowInsetRight() + dp(18), insets.getSystemWindowInsetBottom() + dp(10));
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();

        LinearLayout top = row();
        top.addView(text("家庭共用行事曆", 23, GREEN, true), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button("返回", false, this::finish), new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(top);
        root.addView(text("同一家庭的家人皆可登錄、編輯及查看。時間以台北（UTC+8）顯示。", 12, MUTED, false));
        ScrollView outerScroll = new ScrollView(this);
        LinearLayout body = column();
        outerScroll.addView(body);
        root.addView(outerScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        CalendarView calendar = new CalendarView(this);
        calendarWidget = calendar;
        calendar.setDate(widgetDate(selected), false, true);
        calendar.setOnDateChangeListener((view, year, month, day) -> {
            LocalDate previous = selected;
            selected = LocalDate.of(year, month + 1, day);
            renderEvents();
            if (previous.getMonthValue() != selected.getMonthValue() || previous.getYear() != selected.getYear()) refresh(true);
        });
        body.addView(calendar, new LinearLayout.LayoutParams(-1, dp(285)));

        reminderSwitch = new Switch(this);
        reminderSwitch.setText("提醒這支手機上的家庭行程");
        reminderSwitch.setTextColor(INK);
        reminderSwitch.setChecked(CalendarScheduler.isEnabled(this));
        reminderSwitch.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{GREEN, MUTED}));
        reminderSwitch.setOnCheckedChangeListener((view, checked) -> {
            CalendarScheduler.setEnabled(this, checked);
            if (checked) requestNotificationsIfNeeded();
            CalendarScheduler.refreshSoon(this);
            renderStatus();
        });
        body.addView(reminderSwitch, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout actions = row();
        actions.addView(button("新增行程", true, () -> showEditor(null)), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("重新整理", false, () -> refresh(true)), new LinearLayout.LayoutParams(0, dp(48), 1));
        body.addView(actions);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        status = text("", 12, MUTED, false);
        status.setPadding(0, dp(7), 0, dp(8));
        body.addView(status);
        dayHeading = text("", 18, GREEN, true);
        body.addView(dayHeading);
        eventsList = column();
        eventsList.setPadding(0, dp(8), 0, dp(20));
        body.addView(eventsList);
        renderEvents();
        renderStatus();
    }

    private void renderEvents() {
        if (eventsList == null) return;
        eventsList.removeAllViews();
        dayHeading.setText(selected.format(DateTimeFormatter.ofPattern("M 月 d 日 EEEE", Locale.TAIWAN)));
        List<JSONObject> today = new ArrayList<>();
        JSONArray events = repository.events();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null && CalendarPolicy.occursOnDay(CalendarRepository.startsAt(event), CalendarRepository.endsAt(event), selected)) today.add(event);
        }
        today.sort(Comparator.comparingLong(CalendarRepository::startsAt));
        if (today.isEmpty()) {
            eventsList.addView(text(repository.familyId().isEmpty() ? "請先在首頁登入並加入家庭，再登錄共用行程。"
                    : "這一天尚無已同步的家庭行程。", 15, MUTED, false));
        }
        for (JSONObject event : today) {
            LinearLayout card = column();
            card.setPadding(dp(15), dp(12), dp(15), dp(12));
            card.setBackground(rounded(Color.WHITE, 14));
            card.addView(text(event.optString("title", "家庭活動"), 18, GREEN, true));
            String range = formatTime(CalendarRepository.startsAt(event)) + " — " + formatTime(CalendarRepository.endsAt(event));
            card.addView(text(range, 14, INK, false));
            String place = event.optString("location", "").trim();
            if (!place.isEmpty()) card.addView(text(place, 14, MUTED, false));
            String description = event.optString("description", "").trim();
            if (!description.isEmpty()) card.addView(text(description, 14, MUTED, false));
            int lead = event.optInt("reminder_minutes", 15);
            card.addView(text(lead == 0 ? "活動開始時提醒" : "提前 " + lead + " 分鐘提醒", 12, MUTED, false));
            Button edit = new Button(this);
            edit.setText("查看與編輯");
            edit.setEnabled(!busy);
            edit.setOnClickListener(view -> showEditor(event));
            card.addView(edit, new LinearLayout.LayoutParams(-1, dp(44)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(10);
            eventsList.addView(card, params);
        }
    }

    private void renderStatus() {
        if (status == null) return;
        long sync = repository.lastSyncedAt();
        String label = syncing ? "正在同步家庭行程…" : sync > 0 ? "本機最近同步：" + formatTime(sync) : "尚未取得家庭行程。";
        if (!syncError.isEmpty()) label += "\n" + syncError + "；目前保留上次資料。";
        label += "\n背景約每 15 分鐘同步；新行程與提醒可能因同步、休眠或省電延後。";
        if (CalendarScheduler.isEnabled(this) && !CalendarScheduler.notificationsAllowed(this))
            label += "\n通知尚未允許，可在上方開關重新開啟，或到手機通知設定調整。";
        status.setText(label);
        status.setTextColor(syncError.isEmpty() ? MUTED : AMBER);
        progress.setVisibility(syncing || busy ? View.VISIBLE : View.INVISIBLE);
        for (View view : controls) view.setEnabled(!busy);
    }

    private void refresh(boolean explicit) {
        if (!resumed || syncing || busy || destroyed) return;
        if (!api.isSignedIn()) { syncError = "請先登入"; renderStatus(); return; }
        syncing = true;
        if (explicit) syncError = "";
        renderStatus();
        LocalDate requested = selected;
        worker.execute(() -> {
            String error = "";
            try { repository.syncMonth(requested); CalendarScheduler.start(this); }
            catch (Exception failure) { error = message(failure); }
            String result = error;
            main.post(() -> {
                syncing = false;
                if (destroyed) return;
                syncError = result;
                renderEvents();
                renderStatus();
                if (resumed && (requested.getMonthValue() != selected.getMonthValue() || requested.getYear() != selected.getYear())) refresh(true);
            });
        });
    }

    private void showEditor(JSONObject original) {
        if (busy || syncing) { toast("請等候同步完成後再編輯。"); return; }
        String family = repository.familyId();
        if (family.isEmpty()) { toast("請先登入、加入家庭並完成一次同步。"); return; }
        final String owner = CalendarRepository.identity(api);
        boolean creating = original == null;
        LinearLayout form = column();
        form.setPadding(dp(20), dp(10), dp(20), dp(15));
        EditText title = input("活動標題");
        EditText place = input("地點（可留空）");
        EditText note = input("備註（可留空）");
        note.setMinLines(2);
        if (!creating) {
            title.setText(original.optString("title")); place.setText(original.optString("location")); note.setText(original.optString("description"));
        }
        form.addView(title); form.addView(place); form.addView(note);
        ZonedDateTime initial = selected.atTime(9, 0).atZone(CalendarPolicy.FAMILY_ZONE);
        final ZonedDateTime[] times = creating ? new ZonedDateTime[]{initial, initial.plusHours(1)} : new ZonedDateTime[]{
                Instant.ofEpochMilli(CalendarRepository.startsAt(original)).atZone(CalendarPolicy.FAMILY_ZONE),
                Instant.ofEpochMilli(CalendarRepository.endsAt(original)).atZone(CalendarPolicy.FAMILY_ZONE)};
        Button starts = new Button(this), ends = new Button(this);
        Runnable labels = () -> { starts.setText("開始：" + editorTime(times[0])); ends.setText("結束：" + editorTime(times[1])); };
        labels.run();
        starts.setOnClickListener(view -> pickDateTime(times[0], value -> { times[0] = value; labels.run(); }));
        ends.setOnClickListener(view -> pickDateTime(times[1], value -> { times[1] = value; labels.run(); }));
        form.addView(starts, new LinearLayout.LayoutParams(-1, dp(48)));
        form.addView(ends, new LinearLayout.LayoutParams(-1, dp(48)));
        form.addView(text("台北時間（UTC+8）", 12, MUTED, false));
        Spinner lead = new Spinner(this);
        int[] minutes = {0, 5, 10, 15, 30, 60, 1440};
        String[] leadLabels = {"開始時提醒", "提前 5 分鐘", "提前 10 分鐘", "提前 15 分鐘", "提前 30 分鐘", "提前 1 小時", "提前 1 天"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, leadLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        lead.setAdapter(adapter);
        int currentLead = creating ? 15 : original.optInt("reminder_minutes", 15);
        for (int i = 0; i < minutes.length; i++) if (minutes[i] == currentLead) lead.setSelection(i);
        form.addView(text("家庭活動提醒", 14, GREEN, true));
        form.addView(lead, new LinearLayout.LayoutParams(-1, dp(48)));
        form.addView(text("這個行程會分享給同一家庭，並同步至家庭試算表。每位家人可自行關閉自己手機的活動提醒。", 12, MUTED, false));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(creating ? "新增家庭行程" : "編輯家庭行程")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("儲存", null)
                .setNeutralButton(creating ? null : "刪除行程", creating ? null : (value, which) -> confirmDelete(original, owner, family)).create();
        dialog.setOnShowListener(value -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String caption = title.getText().toString().trim(), description = note.getText().toString(), location = place.getText().toString().trim();
            long from = times[0].toInstant().toEpochMilli(), to = times[1].toInstant().toEpochMilli();
            int advance = minutes[lead.getSelectedItemPosition()];
            String error = CalendarPolicy.validationError(caption, description, location, from, to, advance);
            if (error != null) { toast(error); return; }
            JSONObject fields = new JSONObject();
            try {
                fields.put("p_id", creating ? JSONObject.NULL : original.optString("id"));
                fields.put("p_expected_version", creating ? JSONObject.NULL : original.optInt("version"));
                fields.put("p_expected_family_id", family);
                fields.put("p_title", caption); fields.put("p_description", description); fields.put("p_location", location);
                fields.put("p_starts_at", times[0].toInstant().toString()); fields.put("p_ends_at", times[1].toInstant().toString());
                fields.put("p_timezone", "Asia/Taipei"); fields.put("p_reminder_minutes", advance);
            } catch (Exception failure) { toast("活動資料無法儲存，請重新輸入。"); return; }
            dialog.dismiss();
            runMutation(owner, family, () -> { repository.saveLocalEvent(api.calendarSave(fields)); }, "家庭行程已儲存。");
            if (CalendarScheduler.isEnabled(this)) requestNotificationsIfNeeded();
        }));
        dialog.show();
    }

    private void confirmDelete(JSONObject event, String owner, String family) {
        new AlertDialog.Builder(this).setTitle("刪除這個家庭行程？")
                .setMessage("同一家庭的家人同步後，會移除此行程及尚未送出的提醒。試算表會保留變更紀錄。")
                .setNegativeButton("取消", null).setPositiveButton("刪除", (dialog, which) -> runMutation(owner, family, () -> {
                    api.calendarDelete(event.optString("id"), event.optInt("version"));
                    repository.removeLocalEvent(event.optString("id"));
                }, "家庭行程已刪除。")).show();
    }

    private interface Mutation { void run() throws Exception; }
    private void runMutation(String owner, String family, Mutation mutation, String success) {
        if (busy || destroyed) return;
        busy = true; renderStatus(); renderEvents();
        worker.execute(() -> {
            String failure = "";
            try {
                if (!owner.equals(CalendarRepository.identity(api)) || !family.equals(repository.familyId()))
                    throw new ApiClient.ApiException("家庭或帳號已變更，請重新整理後再操作。");
                mutation.run();
            } catch (Exception error) { failure = message(error); }
            String result = failure;
            main.post(() -> {
                busy = false;
                if (destroyed) return;
                renderEvents(); renderStatus();
                if (result.isEmpty()) { toast(success); refresh(false); }
                else if (resumed) new AlertDialog.Builder(this).setTitle("行程尚未更新")
                        .setMessage(result + "\n請重新整理確認家人是否已修改，再進行編輯。")
                        .setPositiveButton("重新整理", (dialog, which) -> refresh(true)).show();
            });
        });
    }

    private interface TimeResult { void accept(ZonedDateTime value); }
    private void pickDateTime(ZonedDateTime value, TimeResult result) {
        new DatePickerDialog(this, (picker, year, month, day) -> new TimePickerDialog(this,
                (clock, hour, minute) -> result.accept(LocalDate.of(year, month + 1, day).atTime(hour, minute)
                        .atZone(CalendarPolicy.FAMILY_ZONE)), value.getHour(), value.getMinute(), true).show(),
                value.getYear(), value.getMonthValue() - 1, value.getDayOfMonth()).show();
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        else if (!CalendarScheduler.notificationsAllowed(this)) new AlertDialog.Builder(this).setTitle("允許家庭行程通知")
                .setMessage("請在手機通知設定允許家庭共用行事曆提醒。")
                .setNegativeButton("稍後", null).setPositiveButton("開啟設定", (dialog, which) -> startActivity(CalendarScheduler.notificationSettingsIntent(this))).show();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_NOTIFICATIONS) {
            CalendarScheduler.reschedule(this);
            renderStatus();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                toast("尚未允許通知，行程仍會保留；請到手機設定開啟提醒。");
        }
    }

    private void selectNotificationEvent(Intent intent) {
        if (intent == null || repository == null) return;
        String id = intent.getStringExtra("event_id");
        JSONObject event = id == null ? null : repository.event(id);
        if (event != null) selected = Instant.ofEpochMilli(CalendarRepository.startsAt(event)).atZone(CalendarPolicy.FAMILY_ZONE).toLocalDate();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); selectNotificationEvent(intent);
        if (calendarWidget != null) calendarWidget.setDate(widgetDate(selected), false, true);
        renderEvents(); refresh(true);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; CalendarScheduler.start(this); CalendarScheduler.reschedule(this);
        if (reminderSwitch != null) reminderSwitch.setChecked(CalendarScheduler.isEnabled(this));
        refresh(false); main.postDelayed(poll, 60_000L);
    }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(poll); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("day", selected.toString()); super.onSaveInstanceState(out); }
    @Override protected void onDestroy() { destroyed = true; main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String label, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(label); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setPadding(0, dp(3), 0, dp(3)); return view;
    }
    private EditText input(String hint) {
        EditText field = new EditText(this); field.setHint(hint); field.setTextColor(INK); field.setTextSize(16);
        field.setPadding(dp(10), dp(8), dp(10), dp(8)); return field;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(14);
        view.setTextColor(primary ? Color.WHITE : GREEN); view.setBackgroundTintList(ColorStateList.valueOf(primary ? GREEN : SOFT));
        view.setOnClickListener(clicked -> action.run()); controls.add(view); return view;
    }
    private GradientDrawable rounded(int fill, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); return drawable; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private static String message(Exception error) { return error.getMessage() == null ? "目前無法同步，請稍後再試" : error.getMessage(); }
    private static String formatTime(long millis) { return DateTimeFormatter.ofPattern("M/d HH:mm", Locale.TAIWAN).withZone(CalendarPolicy.FAMILY_ZONE).format(Instant.ofEpochMilli(millis)); }
    private static String editorTime(ZonedDateTime value) { return value.format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.TAIWAN)); }
    private static long widgetDate(LocalDate date) { return date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(); }
}
