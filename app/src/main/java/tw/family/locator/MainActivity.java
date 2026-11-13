package tw.family.locator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.Icon;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small, native Android client. No sample people or locations are inserted. */
public final class MainActivity extends Activity {
    private static final int CREAM = Color.rgb(247, 245, 237);
    private static final int GREEN = Color.rgb(35, 84, 63);
    private static final int INK = Color.rgb(35, 47, 41);
    private static final int MUTED = Color.rgb(105, 114, 105);
    private static final int SOFT = Color.rgb(232, 237, 226);
    private static final int BORDER = Color.rgb(216, 222, 210);
    private static final int AMBER = Color.rgb(148, 91, 21);
    private static final int LOCATION_PERMISSION = 41;
    private static final int NOTIFICATION_PERMISSION = 42;
    private static final int REPORT_LOCATION_PERMISSION = 43;
    private static final int REMINDER_NOTIFICATION_PERMISSION = 44;
    private static final int LOCATION_SETTINGS_REQUEST = 45;
    private static final long REFRESH_MS = 15_000L;
    private static final long STALE_MS = 5 * 60_000L;
    private static final String MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<View> actionViews = new ArrayList<>();
    private ApiClient api;
    private FrameLayout root;
    private LinearLayout content;
    private ProgressBar progress;
    private TextView operationText;
    private TextView connectionText;
    private TextView mapHint;
    private LinearLayout memberList;
    private LinearLayout sharingCard;
    private Switch sharingSwitch;
    private MapView mapView;
    private MapLibreMap map;
    private Bundle mapSavedState;
    private JSONObject latestStatus;
    private boolean started;
    private boolean resumed;
    private volatile boolean destroyed;
    private boolean busy;
    private boolean refreshing;
    private boolean pendingSharePermission;
    private boolean stopNeedsSync;
    private boolean mapNeedsFit = true;
    private int sessionEpoch;
    private String renderedFamilyId;
    private String busyMessage = "";
    private String connectionMessage = "";
    private boolean connectionError;
    private boolean pendingManualReport;
    private boolean reportIntentPending;
    private boolean reminderReceiverRegistered;
    private FrameLayout reminderBanner;
    private OneShotLocation.Cancellation manualLocation;
    private volatile int manualReportEpoch;
    private boolean startShareAfterResume;
    private boolean manualReportAfterResume;
    private boolean settingsSyncing;
    private boolean resumeSingleReportAfterSettings;
    private boolean resumeAutoAfterSettings;

    private final BroadcastReceiver reminderReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (CheckInScheduler.ACTION_REMINDER_DUE.equals(intent.getAction()))
                showReminderBanner(intent.getStringExtra("reason"));
            else if (CheckInScheduler.ACTION_REPORT_COMPLETED.equals(intent.getAction())) {
                dismissReminderBanner();
                refreshFamily(false);
            }
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) return;
            refreshFamily(false);
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new ApiClient(getApplicationContext());
        MapLibre.getInstance(this);
        mapSavedState = savedInstanceState == null ? null : savedInstanceState.getBundle("map_state");
        getWindow().setStatusBarColor(CREAM);
        getWindow().setNavigationBarColor(CREAM);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = new FrameLayout(this);
        root.setBackgroundColor(CREAM);
        // Android 15 enforces edge-to-edge. Keep native controls outside system bars.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        renderScreen();
        reportIntentPending = CheckInScheduler.ACTION_REPORT_NOW.equals(getIntent().getAction());
        if (reportIntentPending) getIntent().setAction(null);
    }

    private void renderScreen() {
        if (destroyed) return;
        if (mapView != null && mapView.getParent() instanceof ViewGroup) {
            ((ViewGroup) mapView.getParent()).removeView(mapView);
        }
        root.removeAllViews();
        reminderBanner = null;
        actionViews.clear();
        memberList = null;
        sharingCard = null;
        sharingSwitch = null;
        mapHint = null;
        connectionText = null;
        LinearLayout shell = column();
        shell.setPadding(dp(20), dp(14), dp(20), 0);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout brand = row();
        TextView wordmark = text("家人在這裡", 25, GREEN, true);
        brand.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("設定", false, this::showSettings);
        brand.addView(settings, new LinearLayout.LayoutParams(-2, dp(44)));
        shell.addView(brand);
        TextView subtitle = text("彼此同意，安心相伴。", 13, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        shell.addView(subtitle);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        shell.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        operationText = text(busyMessage, 12, GREEN, false);
        operationText.setPadding(0, dp(4), 0, dp(5));
        shell.addView(operationText);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = column();
        content.setPadding(0, dp(5), 0, dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        if (!api.isConfigured()) renderSetup();
        else if (!api.isSignedIn()) renderAuthentication();
        else if (latestStatus == null) renderLoading();
        else if (latestStatus.optJSONObject("family") == null) renderNoFamily();
        else renderFamily();
        applyBusyState();
    }

    private void renderSetup() {
        LinearLayout card = card();
        card.addView(text("讓牽掛，有個安心的地方", 24, GREEN, true));
        addParagraph(card, "邀請家人加入同一個家庭。每個人都能選擇何時分享自己的位置，也能隨時停止。", 16);
        addParagraph(card, "先完成雲端連線，再註冊帳號並建立家庭。其他家人使用同一組雲端連線設定，即可透過邀請碼加入。", 14);
        addButton(card, "設定雲端連線", true, this::showCloudSettings);
        content.addView(card);
        addParagraph(content, "只會顯示家人同意分享的位置。尚未加入任何家人，也不會產生範例位置。", 13);
    }

    private void renderAuthentication() {
        LinearLayout card = card();
        card.addView(text("歡迎回家", 24, GREEN, true));
        addParagraph(card, "使用自己的帳號登入，讓每位家人保有分享與停止分享的選擇。", 14);
        EditText email = input("電子郵件", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = input("密碼（至少 8 個字元）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(email);
        card.addView(password);
        addButton(card, "登入", true, () -> {
            String address = email.getText().toString().trim();
            String secret = password.getText().toString();
            if (address.isEmpty() || secret.isEmpty()) {
                toast("請輸入電子郵件與密碼。");
                return;
            }
            runTask("正在登入…", () -> { api.signIn(address, secret); return true; }, ignored -> {
                CheckInPreferences.clearForAccountChange(this);
                CalendarScheduler.clearForAccountChange(this);
                password.setText("");
                latestStatus = null;
                connectionMessage = "";
                sessionEpoch++;
                renderScreen();
                refreshFamily(true);
            });
        });
        addButton(card, "建立新帳號", false, () -> {
            String address = email.getText().toString().trim();
            String secret = password.getText().toString();
            if (address.isEmpty() || secret.length() < 8) {
                toast("請輸入電子郵件，並設定至少 8 個字元的密碼。");
                return;
            }
            runTask("正在建立帳號…", () -> api.signUp(address, secret), signedIn -> {
                password.setText("");
                if (signedIn) {
                    CheckInPreferences.clearForAccountChange(this);
                    CalendarScheduler.clearForAccountChange(this);
                    latestStatus = null;
                    sessionEpoch++;
                    renderScreen();
                    refreshFamily(true);
                } else {
                    new AlertDialog.Builder(this).setTitle("請確認驗證信")
                            .setMessage("請到信箱開啟驗證信並完成驗證，再回到這裡登入。若沒看到信件，也請查看垃圾郵件。")
                            .setPositiveButton("知道了", null).show();
                }
            });
        });
        content.addView(card);
        addParagraph(content, "位置只會提供給同一家庭的成員。加入家庭後，位置分享預設由你自行開啟。", 13);
    }

    private void renderLoading() {
        LinearLayout card = card();
        card.addView(text("正在找回你的家庭", 23, GREEN, true));
        addParagraph(card, "連線成功後，這裡會顯示已加入的家庭與成員。", 14);
        connectionText = text(connectionMessage.isEmpty() ? "正在連線…" : connectionMessage,
                14, connectionError ? AMBER : MUTED, false);
        card.addView(connectionText);
        addButton(card, "重新載入", true, () -> refreshFamily(true));
        content.addView(card);
    }

    private void renderNoFamily() {
        renderedFamilyId = null;
        mapNeedsFit = true;
        clearMap();
        LinearLayout card = card();
        card.addView(text("把家人邀請到這裡", 24, GREEN, true));
        addParagraph(card, "目前尚未加入家庭。你可以建立一個家庭，再把一次性邀請碼分享給家人；也可以輸入家人傳來的邀請碼。", 15);
        addButton(card, "建立我的家庭", true, this::showCreateFamily);
        addButton(card, "輸入邀請碼加入", false, this::showJoinFamily);
        content.addView(card);
        addParagraph(content, "每位家人都需要在自己的手機登入，主動加入並開啟分享。尚未分享時，地圖不會顯示其座標。", 13);
        connectionText = text(connectionMessage, 12, connectionError ? AMBER : MUTED, false);
        content.addView(connectionText);
    }

    private void renderFamily() {
        JSONObject family = latestStatus.optJSONObject("family");
        String familyId = family.optString("id", "");
        if (!familyId.equals(renderedFamilyId)) {
            renderedFamilyId = familyId;
            mapNeedsFit = true;
        }
        LinearLayout heading = row();
        heading.addView(text(family.optString("name", "我的家庭"), 23, GREEN, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("邀請家人", true, this::generateInvite),
                new LinearLayout.LayoutParams(-2, dp(45)));
        content.addView(heading);
        addButton(content, "家庭共用行事曆", true, this::openFamilyCalendar);
        connectionText = text(connectionMessage.isEmpty() ? "開啟畫面時，每 15 秒更新家人狀態。" : connectionMessage,
                12, connectionError ? AMBER : MUTED, false);
        connectionText.setPadding(0, dp(7), 0, dp(10));
        content.addView(connectionText);

        FrameLayout mapFrame = new FrameLayout(this);
        mapFrame.setBackground(rounded(SOFT, 18, BORDER));
        mapFrame.setClipToOutline(true);
        content.addView(mapFrame, new LinearLayout.LayoutParams(-1, dp(310)));
        ensureMap();
        mapFrame.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        mapHint = text("尚無可顯示的位置", 13, GREEN, true);
        mapHint.setBackground(rounded(CREAM, 12, BORDER));
        mapHint.setPadding(dp(12), dp(8), dp(12), dp(8));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.setMargins(dp(12), dp(12), dp(12), 0);
        mapFrame.addView(mapHint, hintParams);
        // Attribution and logo remain enabled and unobstructed at the map's bottom.
        addButton(content, "查看所有分享位置", false, this::fitMapToMembers);

        sharingCard = card();
        content.addView(sharingCard);
        renderSharingCard();
        addButton(content, "立即回報我的位置", true, this::confirmManualReport);
        addButton(content, "回報時間與提醒設定", false, this::showReminderSettings);
        TextView membersTitle = text("我的家人", 21, GREEN, true);
        membersTitle.setPadding(0, dp(20), 0, dp(10));
        content.addView(membersTitle);
        memberList = column();
        content.addView(memberList);
        renderMembers();
        addParagraph(content, "綠色代表最近 5 分鐘內的位置；灰色代表較早或時間不明的回報。請以最後回報時間為準，較早的位置不代表家人現在的位置。", 12);
        addButton(content, "立即重新整理", false, () -> refreshFamily(true));
        addButton(content, "離開這個家庭", false, this::confirmLeaveFamily);
        updateMap();
    }

    private void renderSharingCard() {
        if (sharingCard == null) return;
        discardActions(sharingCard);
        sharingCard.removeAllViews();
        LinearLayout line = row();
        line.addView(text("分享我的位置", 18, GREEN, true), new LinearLayout.LayoutParams(0, -2, 1));
        sharingSwitch = new Switch(this);
        sharingSwitch.setContentDescription("分享我的位置");
        sharingSwitch.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{GREEN, MUTED}));
        boolean running = LocationSharingService.isRunning();
        sharingSwitch.setChecked(running && !stopNeedsSync);
        sharingSwitch.setEnabled(!busy);
        actionViews.add(sharingSwitch);
        sharingSwitch.setOnCheckedChangeListener((view, checked) -> {
            if (busy) return;
            if (checked) {
                // A tap expresses intent; the switch becomes on only after permission and start succeed.
                sharingSwitch.setOnCheckedChangeListener(null);
                sharingSwitch.setChecked(false);
                renderSharingCard();
                confirmStartSharing();
            } else stopSharing();
        });
        line.addView(sharingSwitch, new LinearLayout.LayoutParams(-2, dp(48)));
        sharingCard.addView(line);
        boolean serverSharing = selfMember() != null && selfMember().optBoolean("sharing", false);
        String revokeNotice = api.getPendingRevocationNotice();
        if (!revokeNotice.isEmpty()) addParagraph(sharingCard, revokeNotice, 12);
        if (stopNeedsSync) {
            addParagraph(sharingCard, "本機已停止定位。雲端撤回尚未完成，其他家人仍可能看到上次的位置；恢復連線後會重試。", 13);
            addButton(sharingCard, "重試停止雲端分享", false, this::stopSharing);
        } else if (running) {
            addParagraph(sharingCard, "已開啟自動回報：每 " + intervalLabel(CheckInPreferences.getIntervalMinutes(this))
                    + " 檢查近期是否使用 LINE、IG 或 FB，符合條件才嘗試更新位置。手機會顯示常駐通知，可隨時停止。", 13);
        } else if (serverSharing) {
            addParagraph(sharingCard, "目前保留最後一次回報的位置，沒有自動更新。你可以立即回報、開啟自動回報，或停止雲端分享。", 13);
            addButton(sharingCard, "停止雲端分享", false, this::stopSharing);
        } else {
            addParagraph(sharingCard, "目前未分享位置。可選擇單次回報，或開啟符合使用條件才更新的自動回報。", 13);
        }
        if (running) addParagraph(sharingCard, LocationSharingService.getStatus(), 12);
        if (manualLocation != null) {
            Button cancel = new Button(this);
            cancel.setText("取消這次回報");
            cancel.setOnClickListener(view -> {
                cancelManualLocation();
                renderSharingCard();
                toast("已取消這次定位，沒有傳送新位置。");
            });
            sharingCard.addView(cancel, new LinearLayout.LayoutParams(-1, dp(48)));
        }
    }

    private void renderMembers() {
        if (memberList == null) return;
        discardActions(memberList);
        memberList.removeAllViews();
        JSONArray members = members();
        if (members.length() == 0) {
            addParagraph(memberList, "目前沒有可顯示的成員。邀請家人加入後，就會出現在這裡。", 14);
            return;
        }
        for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.optJSONObject(i);
            if (member == null) continue;
            LinearLayout card = card();
            LinearLayout top = row();
            String name = memberName(member);
            TextView avatar = text(initial(name), 18, GREEN, true);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(rounded(SOFT, 24, SOFT));
            top.addView(avatar, new LinearLayout.LayoutParams(dp(43), dp(43)));
            LinearLayout labels = column();
            labels.setPadding(dp(12), 0, 0, 0);
            labels.addView(text(name + (isSelf(member) ? "（我）" : ""), 16, INK, true));
            boolean sharing = visibleSharing(member);
            boolean hasPosition = position(member) != null;
            boolean stale = isStale(member);
            String state;
            if (!sharing) state = "未分享位置";
            else if (!hasPosition) state = "已開啟分享 · 等待定位";
            else if (stale) state = "較早的回報 · 上次已知位置";
            else state = "分享中 · 最近的位置";
            labels.addView(text(state, 12, !sharing || stale ? MUTED : GREEN, false));
            top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(top);
            if (sharing) {
                addParagraph(card, "位置更新：" + ageLabel(recordedAt(member)), 12);
                if (hasPosition) {
                    double accuracy = member.optDouble("accuracy_m", Double.NaN);
                    String detail = Double.isFinite(accuracy) && accuracy >= 0
                            ? "定位精度約 " + Math.round(accuracy) + " 公尺" : "定位精度未知";
                    int battery = member.optInt("battery_level", -1);
                    if (battery >= 0 && battery <= 100) detail += " · 電量 " + battery + "%";
                    TextView metadata = text(detail, 12, MUTED, false);
                    card.addView(metadata);
                    addButton(card, "在地圖查看", false, () -> {
                        LatLng point = position(member);
                        if (map != null && point != null) {
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 14));
                            content.getParent().requestChildFocus(content, content.getChildAt(2));
                            if (content.getParent() instanceof ScrollView) ((ScrollView) content.getParent()).smoothScrollTo(0, 0);
                        }
                    });
                }
            } else {
                addParagraph(card, "只有本人開啟分享後，才會顯示位置。", 12);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(10);
            memberList.addView(card, params);
        }
    }

    private void ensureMap() {
        if (mapView != null) return;
        mapView = new MapView(this);
        mapView.onCreate(mapSavedState);
        if (started) mapView.onStart();
        if (resumed) mapView.onResume();
        mapView.getMapAsync(readyMap -> {
            if (destroyed) return;
            map = readyMap;
            map.getUiSettings().setAttributionEnabled(true);
            map.getUiSettings().setLogoEnabled(true);
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(23.7, 121.0), 6.1));
            map.setStyle(MAP_STYLE, style -> {
                if (!destroyed) updateMap();
            });
        });
        mapView.addOnDidFailLoadingMapListener(error -> {
            if (!destroyed && mapHint != null) {
                mapHint.setText("地圖載入失敗，仍可查看下方家人狀態");
                mapHint.setVisibility(View.VISIBLE);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void updateMap() {
        if (map == null || destroyed) return;
        map.clear();
        int positions = 0;
        JSONArray members = members();
        for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.optJSONObject(i);
            if (member == null) continue;
            LatLng point = position(member);
            if (point == null) continue;
            boolean stale = isStale(member);
            map.addMarker(new MarkerOptions().position(point)
                    .title(memberName(member) + (stale ? " · 較早的回報" : ""))
                    .snippet("更新：" + ageLabel(recordedAt(member)))
                    .icon(markerIcon(stale ? MUTED : GREEN)));
            positions++;
        }
        if (mapHint != null) {
            mapHint.setVisibility(positions == 0 ? View.VISIBLE : View.GONE);
            mapHint.setText("尚無分享位置 · 目前顯示台灣全島");
        }
        if (positions == 0) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(23.7, 121.0), 6.1));
            mapNeedsFit = true;
        } else if (mapNeedsFit) {
            mapView.post(this::fitMapToMembers);
        }
    }

    private Icon markerIcon(int color) {
        int size = dp(34);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float center = size / 2f;
        paint.setColor(Color.WHITE);
        canvas.drawCircle(center, center, size * .46f, paint);
        paint.setColor(color);
        canvas.drawCircle(center, center, size * .36f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(center, center, size * .12f, paint);
        return IconFactory.getInstance(this).fromBitmap(bitmap);
    }

    private void fitMapToMembers() {
        if (map == null || destroyed) return;
        List<LatLng> positions = new ArrayList<>();
        JSONArray members = members();
        for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.optJSONObject(i);
            if (member == null) continue;
            LatLng point = position(member);
            if (point != null) positions.add(point);
        }
        if (positions.isEmpty()) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(23.7, 121.0), 6.1));
            return;
        }
        if (positions.size() == 1) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(positions.get(0), 13));
        } else {
            LatLngBounds bounds = new LatLngBounds.Builder().includes(positions).build();
            // Co-located family members need a useful zoom rather than a zero-size bounds fit.
            if (bounds.getLatitudeSpan() < .0001 && bounds.getLongitudeSpan() < .0001) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(positions.get(0), 14));
            } else map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(48)));
        }
        mapNeedsFit = false;
    }

    private void clearMap() {
        if (map != null) map.clear();
    }

    private void confirmStartSharing() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("開始分享我的位置")
                .setMessage("開啟後，每 " + intervalLabel(CheckInPreferences.getIntervalMinutes(this))
                        + " 檢查最近是否使用 LINE、IG 或 FB，任一符合就嘗試回報一次，沒有使用則提醒手動回報。需開啟手機定位及「使用情況存取權」，並顯示常駐通知。同一家庭可查看最後回報的位置。回報會同步到家庭試算表並保留歷史；你隨時可以停止新回報。")
                .setNegativeButton("先不要", (dialog, which) -> renderSharingCard())
                .setPositiveButton("同意並開始", (dialog, which) -> {
                    if (hasLocationPermission()) startSharing();
                    else {
                        pendingSharePermission = true;
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION);
                    }
                }).show();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startSharing() {
        if (!resumed || busy || !hasLocationPermission()) {
            toast("請保持 App 開啟，並允許定位後再開始分享。");
            return;
        }
        if (!LocationSharingService.isLocationEnabled(this)) {
            showLocationSettings(true);
            return;
        }
        if (!AppUsageGate.hasPermission(this)) {
            new AlertDialog.Builder(this).setTitle("允許判斷 App 使用情況")
                    .setMessage("自動回報需要你在系統設定允許「使用情況存取權」，以判斷近期是否用過指定 App。使用情況只在這支手機判斷。設定完成後，回到這裡重新開啟自動回報。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("前往設定", (dialog, which) -> startActivity(
                            new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))).show();
            return;
        }
        runTask("正在開啟位置分享…", () -> { api.setSharing(true); return true; }, ignored -> {
            if (!resumed || !hasLocationPermission()) {
                rollbackStart("分享尚未啟動。請回到 App 並確認定位權限後重試。");
                return;
            }
            try {
                LocationSharingService.start(this);
                CheckInPreferences.setEnabled(this, true);
                syncSettingsIfNeeded();
                stopNeedsSync = false;
                renderSharingCard();
                renderMembers();
                refreshFamily(true);
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
                }
            } catch (RuntimeException error) {
                rollbackStart("手機無法啟動自動回報，已停止本機定位並嘗試撤回雲端分享。請確認定位權限後重試。");
            }
        });
    }

    private void rollbackStart(String message) {
        LocationSharingService.stop(this);
        stopNeedsSync = true;
        renderSharingCard();
        runTask("正在撤回分享…", () -> { api.setSharing(false); return true; }, ignored -> {
            stopNeedsSync = false;
            toast(message);
            refreshFamily(true);
            renderSharingCard();
        });
    }

    private void stopSharing() {
        if (busy) return;
        cancelManualLocation();
        LocationSharingService.stop(this);
        stopNeedsSync = true;
        renderSharingCard();
        renderMembers();
        updateMap();
        runTask("本機已停止定位，正在撤回雲端分享…", () -> { api.setSharing(false); return true; }, ignored -> {
            stopNeedsSync = false;
            renderSharingCard();
            toast("已停止位置分享。");
            refreshFamily(true);
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION) {
            boolean requested = pendingSharePermission;
            pendingSharePermission = false;
            if (requested && hasLocationPermission()) {
                if (resumed) startSharing(); else startShareAfterResume = true;
            }
            else {
                renderSharingCard();
                toast("未取得定位權限，尚未開始分享。你仍可查看其他家人的分享位置。");
            }
        } else if (requestCode == NOTIFICATION_PERMISSION) {
            // Notification permission affects the notification drawer, never location consent.
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                toast("未允許通知；自動回報仍可用，但手機無法彈出回報提醒。可在 App 設定開啟通知。");
            }
        } else if (requestCode == REPORT_LOCATION_PERMISSION) {
            boolean requested = pendingManualReport;
            pendingManualReport = false;
            if (requested && hasLocationPermission()) {
                if (resumed) requestManualPosition(); else manualReportAfterResume = true;
            }
            else toast("尚未取得定位權限，沒有回報位置。");
        } else if (requestCode == REMINDER_NOTIFICATION_PERMISSION) {
            boolean allowed = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            CheckInPreferences.setEnabled(this, allowed);
            CheckInScheduler.reschedule(this);
            toast(allowed ? "已開啟定時回報提醒。" : "未允許通知，定時提醒尚未開啟。");
        }
    }

    private void showCreateFamily() {
        EditText name = input("例如：我們一家", InputType.TYPE_CLASS_TEXT);
        showInputDialog("建立家庭", "設定一個家人容易認出的名稱。", name, "建立", () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.length() > 40) { toast("家庭名稱請輸入 1 到 40 個字。"); return; }
            runTask("正在建立家庭…", () -> { api.createFamily(value); return true; }, ignored -> {
                CheckInPreferences.clearForAccountChange(this);
                CalendarScheduler.clearForAccountChange(this);
                refreshFamily(true);
            });
        });
    }

    private void showJoinFamily() {
        EditText code = input("貼上家人提供的一次性邀請碼", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        showInputDialog("加入家人的家庭", "接受邀請後會加入同一個家庭；位置仍由你自行選擇是否分享。", code, "加入", () -> {
            String value = code.getText().toString().trim();
            if (value.isEmpty()) { toast("請輸入邀請碼。"); return; }
            runTask("正在加入家庭…", () -> { api.joinFamily(value); return true; }, ignored -> {
                CheckInPreferences.clearForAccountChange(this);
                CalendarScheduler.clearForAccountChange(this);
                refreshFamily(true);
            });
        });
    }

    private void generateInvite() {
        runTask("正在產生一次性邀請碼…", () -> api.createInvite(), code -> {
            new AlertDialog.Builder(this).setTitle("邀請一位家人")
                    .setMessage("一次性邀請碼：\n\n" + code + "\n\n每組邀請碼只能成功加入一次。請只交給你要邀請的家人；加入後仍需本人開啟位置分享。")
                    .setNegativeButton("完成", null)
                    .setPositiveButton("分享邀請碼", (dialog, which) -> {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(Intent.EXTRA_TEXT, "邀請你加入「家人在這裡」的家庭。\n一次性邀請碼：" + code
                                + "\n請在 App 登入後選擇「輸入邀請碼加入」。加入家庭後，你可以自行決定是否分享位置。"
                                + "\n雲端連線網址：" + api.getServerUrl());
                        startActivity(Intent.createChooser(send, "分享家庭邀請碼"));
                    }).show();
        });
    }

    private void confirmLeaveFamily() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("離開這個家庭？")
                .setMessage("會先停止你的位置分享。離開後，你與這個家庭將無法互相查看位置；之後可使用新的邀請碼重新加入。")
                .setNegativeButton("取消", null)
                .setPositiveButton("停止分享並離開", (dialog, which) -> {
                    cancelManualLocation();
                    CalendarScheduler.clearForAccountChange(this);
                    LocationSharingService.stop(this);
                    stopNeedsSync = true;
                    renderSharingCard();
                    runTask("正在停止分享並離開家庭…", () -> {
                        api.setSharing(false);
                        api.leaveFamily();
                        return true;
                    }, ignored -> {
                        stopNeedsSync = false;
                        CheckInPreferences.clearForAccountChange(this);
                        latestStatus = null;
                        clearMap();
                        renderScreen();
                        refreshFamily(true);
                    });
                }).show();
    }

    private void showSettings() {
        if (busy) return;
        if (!api.isSignedIn()) {
            new AlertDialog.Builder(this).setTitle("設定")
                    .setItems(new String[]{"回報時間與提醒", "雲端連線"}, (dialog, which) -> {
                        if (which == 0) showReminderSettings(); else showCloudSettings();
                    }).setNegativeButton("關閉", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("設定")
                .setItems(new String[]{"回報時間與提醒", "家庭共用行事曆", "Google 試算表記錄", "修改我的顯示名稱", "查看雲端連線", "登出帳號"}, (dialog, which) -> {
                    if (which == 0) showReminderSettings();
                    else if (which == 1) openFamilyCalendar();
                    else if (which == 2) showSheetsStatus();
                    else if (which == 3) showProfileName();
                    else if (which == 4) showCloudSettings();
                    else confirmSignOut();
                }).setNegativeButton("關閉", null).show();
    }

    private static String intervalLabel(int minutes) {
        return minutes == 30 ? "30 分鐘" : (minutes / 60) + " 小時";
    }

    private void openFamilyCalendar() {
        if (latestStatus == null || latestStatus.optJSONObject("family") == null) {
            toast("加入家庭後，就能使用共用行事曆。");
            return;
        }
        startActivity(new Intent(this, FamilyCalendarActivity.class));
    }

    private void showSheetsStatus() {
        if (latestStatus == null || latestStatus.optJSONObject("family") == null) {
            toast("請先加入家庭，再查看試算表同步狀態。");
            return;
        }
        runTask("正在檢查 Google 試算表同步…", api::sheetSyncStatus, status -> {
            String last = status.optString("last_delivered_at", "");
            String connection = last.isEmpty() || last.equals("null")
                    ? "目前尚無已完成的 Google 同步，連線仍待驗證。"
                    : "最後成功寫入 Google：" + last;
            new AlertDialog.Builder(this).setTitle("家庭 Google 試算表")
                    .setMessage(connection + "\n\n等待同步（含重試）：" + status.optInt("queued", 0)
                            + " 筆\n待重試：" + status.optInt("failed", 0)
                            + " 筆\n\n會記錄定位回報、家庭成員、行事曆與回報設定。需完成家庭後端及 Google 寫入連線。"
                            + "\n\n停止分享會撤回地圖位置，已匯入試算表的歷史紀錄仍保留。")
                    .setPositiveButton("知道了", null).show();
        });
    }

    private void syncSettingsIfNeeded() {
        if (settingsSyncing || latestStatus == null || !api.isSignedIn()) return;
        String familyId = familyId(latestStatus);
        if (familyId.isEmpty()) return;
        settingsSyncing = true;
        executor.execute(() -> {
            try { api.syncAppSettingsIfChanged(familyId); }
            catch (ApiClient.ApiException ignored) { /* Local settings stay active; next sync retries. */ }
            finally { main.post(() -> settingsSyncing = false); }
        });
    }

    private void showReminderSettings() {
        LinearLayout form = column();
        form.setPadding(dp(22), dp(6), dp(22), dp(12));
        Switch enabled = new Switch(this);
        enabled.setText("開啟定時回報提醒");
        enabled.setTextColor(INK);
        enabled.setChecked(CheckInPreferences.isEnabled(this));
        form.addView(enabled, new LinearLayout.LayoutParams(-1, dp(52)));
        addParagraph(form, "回報與提醒間隔", 16);
        final int[] options = {30, 60, 120, 180, 240};
        String[] labels = {"每 30 分鐘", "每 1 小時", "每 2 小時", "每 3 小時", "每 4 小時"};
        Spinner interval = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        interval.setAdapter(adapter);
        for (int i = 0; i < options.length; i++) {
            if (options[i] == CheckInPreferences.getIntervalMinutes(this)) interval.setSelection(i);
        }
        form.addView(interval, new LinearLayout.LayoutParams(-1, dp(52)));
        addParagraph(form, "App 開啟時的提醒位置", 16);
        RadioGroup placement = new RadioGroup(this);
        RadioButton top = new RadioButton(this);
        top.setId(View.generateViewId());
        top.setText("螢幕上方");
        RadioButton bottom = new RadioButton(this);
        bottom.setId(View.generateViewId());
        bottom.setText("螢幕下方");
        placement.addView(top);
        placement.addView(bottom);
        placement.check("BOTTOM".equals(CheckInPreferences.getPlacement(this)) ? bottom.getId() : top.getId());
        form.addView(placement);
        addParagraph(form, "切到 LINE、IG、FB 或鎖定螢幕後，改用系統通知提醒；是否彈出上方橫幅、發聲或震動，由手機通知設定決定。", 13);
        addParagraph(form, "自動回報開啟時，每個間隔有使用 LINE、IG、FB 任一項，就嘗試回報一次；都沒有使用，才提醒手動回報。尚未開啟自動回報時，以手動提醒為主。成功回報後重新計時，省電或休眠可能延後執行。", 13);
        addParagraph(form, "設定只套用在這支手機。家人可各自選擇頻率；回報位置前需登入並加入家庭。", 13);
        if (!CheckInScheduler.canShowNotifications(this)) {
            addParagraph(form, "手機目前未允許此類通知。請允許通知後，才能在 App 關閉時收到提醒。", 13);
        }
        long last = CheckInPreferences.getLastReportedAt(this);
        if (last > 0) addParagraph(form, "上次成功回報：" + ageLabel(Instant.ofEpochMilli(last)), 12);
        TextView preview = text("預覽：到回報時間了\n點一下回報目前位置，讓家人安心。\n［稍後］  ［立即回報］", 15, GREEN, false);
        preview.setPadding(dp(16), dp(14), dp(16), dp(14));
        preview.setBackground(rounded(SOFT, 14, BORDER));
        form.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        addButton(form, "開啟手機通知設定", false, () -> startActivity(
                CheckInScheduler.getNotificationSettingsIntent(this)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this).setTitle("回報時間與提醒").setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("儲存", (dialog, which) -> {
                    CheckInPreferences.setIntervalMinutes(this, options[interval.getSelectedItemPosition()]);
                    CheckInPreferences.setPlacement(this, placement.getCheckedRadioButtonId() == bottom.getId()
                            ? "BOTTOM" : "TOP");
                    CheckInPreferences.setEnabled(this, enabled.isChecked());
                    CheckInScheduler.reschedule(this);
                    syncSettingsIfNeeded();
                    if (enabled.isChecked() && Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REMINDER_NOTIFICATION_PERMISSION);
                    } else toast(enabled.isChecked() ? "已儲存回報間隔與提醒。" : "已儲存間隔，定時提醒目前關閉。");
                    renderSharingCard();
                }).show();
    }

    private void showReminderBanner() {
        showReminderBanner(null);
    }

    private void showReminderBanner(String reason) {
        if (!resumed || destroyed) return;
        dismissReminderBanner();
        CheckInScheduler.dismissNotification(this);
        reminderBanner = new FrameLayout(this);
        LinearLayout panel = column();
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setBackground(rounded(CREAM, 18, GREEN));
        panel.setElevation(dp(8));
        panel.addView(text("到回報時間了", 20, GREEN, true));
        addParagraph(panel, reason == null || reason.trim().isEmpty()
                ? "點一下回報目前位置，讓家人安心。" : reason, 14);
        LinearLayout actions = row();
        actions.addView(button("稍後", false, this::dismissReminderBanner), new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("立即回報", true, () -> {
            dismissReminderBanner();
            confirmManualReport();
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(actions);
        reminderBanner.addView(panel, new FrameLayout.LayoutParams(-1, -2));
        int gravity = "BOTTOM".equals(CheckInPreferences.getPlacement(this)) ? Gravity.BOTTOM : Gravity.TOP;
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-1, -2, gravity);
        layout.setMargins(dp(14), dp(14), dp(14), dp(14));
        root.addView(reminderBanner, layout);
    }

    private void dismissReminderBanner() {
        if (reminderBanner == null) return;
        discardActions(reminderBanner);
        root.removeView(reminderBanner);
        reminderBanner = null;
    }

    private void handleReportIntent() {
        if (!reportIntentPending || !resumed || destroyed) return;
        if (busy || (api.isSignedIn() && latestStatus == null)) return;
        reportIntentPending = false;
        confirmManualReport();
    }

    private void confirmManualReport() {
        if (!resumed || busy || manualLocation != null) return;
        if (!api.isConfigured() || !api.isSignedIn() || latestStatus == null
                || latestStatus.optJSONObject("family") == null) {
            toast("請先完成雲端連線、登入並加入家庭，再回報位置。");
            return;
        }
        new AlertDialog.Builder(this).setTitle("回報目前位置？")
                .setMessage("這次回報會把你的目前位置提供給同一家庭的成員，並同步到家庭試算表保留歷史。你可以隨時停止新回報。這個按鈕只回報一次。")
                .setNegativeButton("取消", null)
                .setPositiveButton("同意並回報", (dialog, which) -> {
                    if (hasLocationPermission()) requestManualPosition();
                    else {
                        pendingManualReport = true;
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION}, REPORT_LOCATION_PERMISSION);
                    }
                }).show();
    }

    private void requestManualPosition() {
        if (!resumed || busy || manualLocation != null) return;
        if (!LocationSharingService.isLocationEnabled(this)) {
            showLocationSettings(false);
            return;
        }
        final int operation = ++manualReportEpoch;
        runTask("正在準備這次位置回報…", () -> { api.setSharing(true); return true; }, ignored -> {
            if (!resumed || operation != manualReportEpoch) return;
            stopNeedsSync = false;
            busy = true;
            busyMessage = "正在取得目前位置，最多等候 30 秒…";
            applyBusyState();
            if (connectionText != null) connectionText.setText("正在取得目前位置，最多等候 30 秒…");
            manualLocation = OneShotLocation.request(this, new OneShotLocation.Callback() {
                @Override public void onLocation(android.location.Location location) {
                    manualLocation = null;
                    busy = false;
                    busyMessage = "";
                    if (!resumed || operation != manualReportEpoch) return;
                    renderSharingCard();
                    android.os.BatteryManager battery = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
                    int level = battery == null ? -1 : battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    final String reportingUser = api.currentUserId();
                    runTask("正在回報位置…", () -> {
                        if (destroyed || operation != manualReportEpoch || !reportingUser.equals(api.currentUserId()))
                            return false;
                        api.publishLocation(location.getLatitude(), location.getLongitude(), location.getAccuracy(), level,
                                Instant.ofEpochMilli(location.getTime()).toString());
                        if (!destroyed && operation == manualReportEpoch && reportingUser.equals(api.currentUserId())
                                && ApiClient.isLocallyEnabled(getApplicationContext()))
                            CheckInPreferences.markReported(getApplicationContext(), System.currentTimeMillis());
                        return true;
                    }, sent -> {
                        if (!sent || operation != manualReportEpoch) return;
                        dismissReminderBanner();
                        toast("位置已回報，本次定位取用已結束。手機定位開關由你自行管理。");
                        refreshFamily(true);
                    });
                }
                @Override public void onError(String message) {
                    manualLocation = null;
                    busy = false;
                    busyMessage = "";
                    applyBusyState();
                    if (operation == manualReportEpoch && !destroyed) {
                        toast(message + " 尚未回報新位置。");
                        refreshFamily(false);
                    }
                }
            });
            renderSharingCard();
        });
    }

    private void cancelManualLocation() {
        manualReportEpoch++;
        if (manualLocation != null) {
            manualLocation.cancel();
            busy = false;
            busyMessage = "";
            applyBusyState();
        }
        manualLocation = null;
    }

    private void showLocationSettings(boolean automatic) {
        new AlertDialog.Builder(this).setTitle("手機定位目前關閉")
                .setMessage("回報位置需要你在手機設定開啟定位。App 只在回報時取得一次位置，完成或取消就停止取用；不會替你切換手機原有的定位開關。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往定位設定", (dialog, which) -> {
                    resumeAutoAfterSettings = automatic;
                    resumeSingleReportAfterSettings = !automatic;
                    try {
                        startActivityForResult(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                LOCATION_SETTINGS_REQUEST);
                    } catch (android.content.ActivityNotFoundException error) {
                        resumeAutoAfterSettings = false;
                        resumeSingleReportAfterSettings = false;
                        toast("請從手機設定開啟定位，再回到 App 按回報。");
                    }
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != LOCATION_SETTINGS_REQUEST) return;
        boolean requestAuto = resumeAutoAfterSettings;
        boolean requestSingle = resumeSingleReportAfterSettings;
        resumeAutoAfterSettings = false;
        resumeSingleReportAfterSettings = false;
        if (!LocationSharingService.isLocationEnabled(this)) {
            toast("手機定位仍關閉，尚未取得或回報位置。");
            return;
        }
        // Activity results can precede onResume. Finish only the already-confirmed request there.
        if (requestAuto) {
            if (resumed) startSharing(); else startShareAfterResume = true;
        } else if (requestSingle) {
            if (resumed) requestManualPosition(); else manualReportAfterResume = true;
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (CheckInScheduler.ACTION_REPORT_NOW.equals(intent.getAction())) {
            intent.setAction(null);
            reportIntentPending = true;
            handleReportIntent();
        }
    }

    private void showProfileName() {
        EditText name = input("讓家人容易認出的名字", InputType.TYPE_CLASS_TEXT);
        JSONObject me = selfMember();
        if (me != null) name.setText(me.optString("display_name", ""));
        showInputDialog("我的顯示名稱", "這個名字會顯示給同一家庭的家人。", name, "儲存", () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.length() > 30) { toast("名稱請輸入 1 到 30 個字。"); return; }
            runTask("正在儲存名稱…", () -> { api.updateProfile(value); return true; }, ignored -> refreshFamily(true));
        });
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this).setTitle("登出帳號？")
                .setMessage("登出前會先停止本機定位並撤回雲端位置分享。")
                .setNegativeButton("取消", null)
                .setPositiveButton("停止分享並登出", (dialog, which) -> {
                    cancelManualLocation();
                    CalendarScheduler.clearForAccountChange(this);
                    LocationSharingService.stop(this);
                    stopNeedsSync = true;
                    renderSharingCard();
                    runTask("正在停止分享並登出…", () -> {
                        api.signOut();
                        return true;
                    }, ignored -> {
                        stopNeedsSync = false;
                        latestStatus = null;
                        connectionMessage = "";
                        renderedFamilyId = null;
                        sessionEpoch++;
                        clearMap();
                        CheckInPreferences.clearForAccountChange(this);
                        renderScreen();
                    });
                }).show();
    }

    private void showCloudSettings() {
        if (busy) return;
        LinearLayout form = column();
        form.setPadding(dp(22), dp(8), dp(22), dp(8));
        addParagraph(form, "使用同一個 Supabase 專案，讓家人的手機連到同一個家庭資料庫。", 14);
        EditText url = input("Supabase 專案網址（https://…）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText key = input("Publishable key 或 anon public key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        url.setText(api.getServerUrl());
        key.setText(api.getPublicKey());
        form.addView(url);
        form.addView(key);
        addParagraph(form, "請使用公開用戶端金鑰，請勿填入 service_role 或 secret key。雲端資料庫需先完成專案內的設定步驟。", 12);
        boolean signedIn = api.isSignedIn();
        if (signedIn) {
            url.setEnabled(false);
            key.setEnabled(false);
            addParagraph(form, "請先登出，再修改雲端連線設定。", 13);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("雲端連線設定").setView(scroll)
                .setNegativeButton(signedIn ? "完成" : "取消", null)
                .setPositiveButton(signedIn ? null : "儲存", null).create();
        dialog.setOnShowListener(ignored -> {
            if (!signedIn) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String server = url.getText().toString().trim();
                String publicKey = key.getText().toString().trim();
                if (!server.startsWith("https://") || publicKey.isEmpty()) {
                    toast("請輸入 https 開頭的專案網址與公開用戶端金鑰。");
                    return;
                }
                dialog.dismiss();
                runTask("正在儲存雲端設定…", () -> { api.configure(server, publicKey); return true; }, saved -> {
                    latestStatus = null;
                    sessionEpoch++;
                    renderScreen();
                    toast("雲端連線設定已儲存，請登入或建立帳號。");
                });
            });
        });
        dialog.show();
    }

    private void showInputDialog(String title, String message, EditText field, String positive, Runnable action) {
        LinearLayout form = column();
        form.setPadding(dp(22), dp(8), dp(22), dp(8));
        addParagraph(form, message, 14);
        form.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(form)
                .setNegativeButton("取消", null).setPositiveButton(positive, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (field.getText().toString().trim().isEmpty()) { field.setError("請輸入內容"); return; }
            dialog.dismiss();
            action.run();
        }));
        dialog.show();
    }

    private void refreshFamily(boolean explicit) {
        if (!resumed || destroyed || busy || refreshing || !api.isConfigured() || !api.isSignedIn()) return;
        refreshing = true;
        final int epoch = sessionEpoch;
        if (explicit && connectionText != null) connectionText.setText("正在更新家人狀態…");
        executor.execute(() -> {
            try {
                JSONObject status = api.familyStatus();
                main.post(() -> {
                    refreshing = false;
                    if (destroyed || epoch != sessionEpoch) return;
                    String oldId = familyId(latestStatus);
                    boolean first = latestStatus == null;
                    latestStatus = status;
                    stopNeedsSync = api.hasPendingStopForCurrentAccount();
                    JSONObject currentFamily = status.optJSONObject("family");
                    CalendarScheduler.bindFamily(getApplicationContext(), currentFamily == null ? "" : currentFamily.optString("id", ""));
                    if (currentFamily != null) {
                        CalendarScheduler.start(getApplicationContext());
                        CalendarScheduler.refreshSoon(getApplicationContext());
                        syncSettingsIfNeeded();
                    }
                    connectionError = false;
                    connectionMessage = "狀態已更新 · " + DateTimeFormatter.ofPattern("HH:mm:ss", Locale.TAIWAN)
                            .format(java.time.ZonedDateTime.now()) + " · 畫面開啟時每 15 秒更新";
                    if (!resumed) return;
                    if (first || !oldId.equals(familyId(status))) renderScreen();
                    else {
                        if (connectionText != null) {
                            connectionText.setText(connectionMessage);
                            connectionText.setTextColor(MUTED);
                        }
                        renderSharingCard();
                        renderMembers();
                        updateMap();
                        applyBusyState();
                    }
                    handleReportIntent();
                });
            } catch (Exception error) {
                main.post(() -> {
                    refreshing = false;
                    if (destroyed || epoch != sessionEpoch || !resumed) return;
                    connectionError = true;
                    connectionMessage = "連線未更新：" + userMessage(error)
                            + (latestStatus == null ? "" : "\n目前顯示最後取得的資料，請留意各位置的更新時間。");
                    if (connectionText != null) {
                        connectionText.setText(connectionMessage);
                        connectionText.setTextColor(AMBER);
                    }
                    renderMembers();
                    updateMap();
                    applyBusyState();
                });
            }
        });
    }

    private <T> void runTask(String message, Task<T> task, Result<T> success) {
        if (busy || destroyed) return;
        busy = true;
        busyMessage = message;
        applyBusyState();
        executor.execute(() -> {
            try {
                T result = task.run();
                main.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    busyMessage = "";
                    applyBusyState();
                    success.accept(result);
                    handleReportIntent();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    busyMessage = "";
                    applyBusyState();
                    renderSharingCard();
                    renderMembers();
                    updateMap();
                    new AlertDialog.Builder(this).setTitle(stopNeedsSync ? "本機已停止定位，雲端尚待同步" : "尚未完成")
                            .setMessage(userMessage(error) + (stopNeedsSync
                                    ? "\n\n其他家人仍可能看到上次的位置。請恢復連線後重試停止雲端分享。" : ""))
                            .setPositiveButton("知道了", null).show();
                });
            }
        });
    }

    private void applyBusyState() {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        if (operationText != null) {
            operationText.setText(busyMessage);
            operationText.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        for (View view : actionViews) view.setEnabled(!busy);
    }

    private void discardActions(View subtree) {
        actionViews.remove(subtree);
        if (subtree instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) subtree;
            for (int i = 0; i < group.getChildCount(); i++) discardActions(group.getChildAt(i));
        }
    }

    private JSONArray members() {
        JSONArray value = latestStatus == null ? null : latestStatus.optJSONArray("members");
        return value == null ? new JSONArray() : value;
    }

    private JSONObject selfMember() {
        JSONArray members = members();
        for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.optJSONObject(i);
            if (member != null && isSelf(member)) return member;
        }
        return null;
    }

    private boolean isSelf(JSONObject member) {
        String userId = api.currentUserId();
        return userId != null && !userId.isEmpty() && userId.equals(member.optString("user_id", ""));
    }

    private boolean visibleSharing(JSONObject member) {
        if (!member.optBoolean("sharing", false)) return false;
        return !isSelf(member) || !stopNeedsSync;
    }

    private LatLng position(JSONObject member) {
        if (!visibleSharing(member) || member.isNull("latitude") || member.isNull("longitude")) return null;
        double lat = member.optDouble("latitude", Double.NaN);
        double lon = member.optDouble("longitude", Double.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
        return new LatLng(lat, lon);
    }

    private String memberName(JSONObject member) {
        String name = member.optString("display_name", "").trim();
        return name.isEmpty() || name.equals("null") ? "家人" : name;
    }

    private static String initial(String name) {
        return name.isEmpty() ? "家" : name.substring(0, name.offsetByCodePoints(0, 1));
    }

    private static String familyId(JSONObject status) {
        JSONObject family = status == null ? null : status.optJSONObject("family");
        return family == null ? "" : family.optString("id", "");
    }

    private static Instant recordedAt(JSONObject member) {
        String value = member.optString("recorded_at", "");
        if (value.isEmpty() || value.equals("null")) return null;
        try { return Instant.parse(value); }
        catch (Exception first) {
            try { return OffsetDateTime.parse(value).toInstant(); }
            catch (Exception ignored) { return null; }
        }
    }

    private static boolean isStale(JSONObject member) {
        Instant instant = recordedAt(member);
        if (instant == null) return true;
        long age = System.currentTimeMillis() - instant.toEpochMilli();
        return age > STALE_MS || age < -60_000;
    }

    private static String ageLabel(Instant instant) {
        if (instant == null) return "尚無可確認的更新時間";
        long age = System.currentTimeMillis() - instant.toEpochMilli();
        if (age < -60_000) return "時間異常，請確認手機時鐘";
        if (age < 60_000) return "不到 1 分鐘前";
        if (age < 60 * 60_000L) return (age / 60_000L) + " 分鐘前";
        if (age < 24 * 60 * 60_000L) return (age / (60 * 60_000L)) + " 小時前";
        return (age / (24 * 60 * 60_000L)) + " 天前";
    }

    private String userMessage(Exception error) {
        if (error instanceof ApiClient.ApiException && error.getMessage() != null) return error.getMessage();
        return "暫時無法完成連線，請確認網路與雲端設定後再試一次。";
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(rounded(Color.WHITE, 18, BORDER));
        return layout;
    }

    private TextView text(String label, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void addParagraph(LinearLayout parent, String label, int size) {
        TextView paragraph = text(label, size, MUTED, false);
        paragraph.setPadding(0, dp(10), 0, dp(10));
        parent.addView(paragraph, new LinearLayout.LayoutParams(-1, -2));
    }

    private EditText input(String hint, int type) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextColor(INK);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(15);
        edit.setInputType(type);
        edit.setSingleLine(true);
        edit.setPadding(dp(12), dp(12), dp(12), dp(12));
        edit.setBackground(rounded(CREAM, 10, BORDER));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(53));
        params.topMargin = dp(10);
        params.bottomMargin = dp(5);
        edit.setLayoutParams(params);
        actionViews.add(edit);
        return edit;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(primary ? Color.WHITE : GREEN);
        view.setBackgroundTintList(null);
        view.setBackground(rounded(primary ? GREEN : SOFT, 12, primary ? GREEN : BORDER));
        view.setPadding(dp(15), dp(6), dp(15), dp(6));
        view.setMinHeight(dp(46));
        view.setMinimumHeight(dp(46));
        view.setOnClickListener(ignored -> { if (!busy) action.run(); });
        actionViews.add(view);
        return view;
    }

    private void addButton(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button view = button(label, primary, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(10);
        parent.addView(view, params);
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (mapView != null) mapView.onStart();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        stopNeedsSync = api.hasPendingStopForCurrentAccount();
        IntentFilter reminderFilter = new IntentFilter(CheckInScheduler.ACTION_REMINDER_DUE);
        reminderFilter.addAction(CheckInScheduler.ACTION_REPORT_COMPLETED);
        ContextCompat.registerReceiver(this, reminderReceiver, reminderFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        reminderReceiverRegistered = true;
        CheckInScheduler.setAppForeground(true);
        CheckInScheduler.reschedule(this);
        if (mapView != null) mapView.onResume();
        if (sharingCard != null) renderSharingCard();
        refreshFamily(false);
        main.removeCallbacks(poll);
        main.postDelayed(poll, REFRESH_MS);
        main.post(() -> {
            if (startShareAfterResume) { startShareAfterResume = false; startSharing(); }
            if (manualReportAfterResume) { manualReportAfterResume = false; requestManualPosition(); }
            handleReportIntent();
        });
    }

    @Override protected void onPause() {
        CheckInScheduler.setAppForeground(false);
        if (reminderReceiverRegistered) {
            unregisterReceiver(reminderReceiver);
            reminderReceiverRegistered = false;
        }
        cancelManualLocation();
        dismissReminderBanner();
        resumed = false;
        main.removeCallbacks(poll);
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        started = false;
        if (mapView != null) mapView.onStop();
        super.onStop();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (mapView != null) {
            Bundle mapState = new Bundle();
            mapView.onSaveInstanceState(mapState);
            state.putBundle("map_state", mapState);
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelManualLocation();
        main.removeCallbacksAndMessages(null);
        if (mapView != null) mapView.onDestroy();
        executor.shutdown();
        super.onDestroy();
    }

    private interface Task<T> { T run() throws Exception; }
    private interface Result<T> { void accept(T value); }
}
