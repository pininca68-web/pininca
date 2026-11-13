# 家人在這裡

給 Android 家庭使用的位置回報與共用行事曆 App。每位家人使用自己的帳號、接受家庭邀請，並自行選擇何時分享位置。

## 這一版的功能

- 回報與提醒間隔：**30 分鐘、1 小時、2 小時、3 小時、4 小時**，預設 1 小時。
- 定時提醒預設關閉，由每位使用者在自己的手機啟用。
- App 畫面開啟時，提醒可以顯示在**上方或下方**，提供「立即回報」按鈕。
- 使用其他 App 或鎖定畫面時，使用 Android 系統通知，可「立即回報」、「稍後 10 分鐘」或「暫停提醒」。通知橫幅、聲音及震動依手機設定。
- **成功傳出位置**後，下一次提醒重新計時。取得不到位置、離線或上傳失敗，會顯示失敗狀態。
- 手動回報：本人確認後取得一次位置，家庭地圖保留最後回報的位置與時間。
- 智慧回報：本人開啟後，以常駐通知顯示服務狀態。每個所選間隔只判斷一次：有使用 LINE、Instagram、Facebook 任一項，就嘗試回報一次當下位置；都沒使用，就提醒本人手動回報。定位或傳送失敗也會提醒，成功回報才重新計時。使用情況在手機本機判斷。
- 家庭共用行事曆：同家庭成員可登錄、修改、刪除活動，設定開始時間、結束時間、地點、備註及提前提醒。每位家人可自己決定是否接收活動提醒。
- 行事曆採台北時間，提醒可選活動開始時或提前 5、10、15、30、60 分鐘、1 天。畫面開啟時約每分鐘同步，背景約每 15 分鐘同步；新加入或遠端修改的活動須同步到手機後才可排提醒。
- 家庭邀請、顯示名稱、最後回報時間、定位精度與電量。App 地圖顯示最新一筆位置，Google 試算表保存歷史變更。
- Google 試算表紀錄：包含定位回報、家庭成員、共用行事曆、回報設定及家庭操作。伺服器持久佇列保留待同步資料，連線恢復後重試；App 的「Google 試算表記錄」顯示待同步與成功時間。
- 停止分享會立即停止本機採集，並撤回雲端位置；離線時保留撤回工作，連線恢復後重試。

「上方／下方」選項適用於本 App 畫面。在 LINE 等其他 App 上，Android 以系統通知控制呈現位置。提醒與定位可能因手機休眠、通知設定、省電、網路或定位訊號延後。

## 在手機上使用

1. 將 `output/family-locator-preview.apk` 傳到 Android 手機安裝。這是自行安裝的測試版，安裝時依 Android 畫面允許該來源安裝 App。
2. 按「設定」→「雲端連線」，填入下節建立的 Supabase 專案網址與公開用戶端金鑰。家人手機使用同一組連線設定。
3. 每位家人以自己的電子郵件註冊。若專案啟用信箱確認，先完成驗證，再登入。
4. 一位家人建立家庭，按「邀請家人」，把一次性邀請碼傳給其他家人；其他人登入後選「輸入邀請碼加入」。邀請碼有效 24 小時、限使用一次；重新產生會取代同一人先前未使用的邀請碼。
5. 進入「回報時間與提醒」，選擇間隔、提示位置並開啟提醒。Android 13 以上需允許通知；聲音與橫幅在手機通知設定調整。
6. 按「立即回報我的位置」，確認分享並允許定位。只有成功傳到家庭資料庫才會顯示「位置已成功回報」。
7. 若要自動回報，開啟「分享我的位置」，依畫面授權使用情況存取權與定位。完成系統設定後，回到 App 再開啟一次分享。

手機的定位開關仍須開啟。LINE、Instagram、Facebook 的使用只作為回報條件；位置由本 App 取得。若 10:05 使用 LINE、10:30 才成功定位，記錄的是 10:30 的位置。

每次回報只取得一次位置，取得成功、失敗、取消或逾時都停止這次定位取用。App 不自行切換手機系統的定位開關；若原本關閉，會引導本人到定位設定，由本人決定是否開啟。回報完成也不會關閉其他 App 使用中的定位。

## 共用行事曆與 Google 試算表

登入並加入家庭後，按「家庭共用行事曆」即可使用。所有同家庭成員都能編輯，若別人剛改過同一活動，會要求重新整理再修改。取消或修改活動後，手機會在下次成功同步後取消或重排提醒。

原生 Google 試算表需要連接擁有者的 Google 帳號才能建立；持續寫入由家庭後端執行，與此聊天是否開啟無關。請依 [`backend/GOOGLE_SHEETS_SETUP.md`](backend/GOOGLE_SHEETS_SETUP.md) 設定專用試算表、伺服器寫入權與雲端排程。目前尚未連接任何真實 Google 帳號或部署。

停止分享會撤回地圖上最新的位置；Google 試算表及後端佇列保留已記錄的歷史資料。App 的分享確認畫面會說明這項保存方式。

## 建立家庭同步後端

此專案尚未綁定或部署到任何使用者的雲端帳號。安裝 APK 後，需要完成以下一次性設定，才能在不同手機間同步。

1. 在 [Supabase](https://supabase.com/dashboard) 登入並建立一個**新的專用專案**，自行選擇服務方案與地區。
2. 在該專案的 SQL Editor 依序執行 [`backend/schema.sql`](backend/schema.sql) 與 [`backend/migrations/002_calendar_and_sheets.sql`](backend/migrations/002_calendar_and_sheets.sql)。已用過舊版的專案只執行 migration。這些腳本建立私有資料表、權限與 App 使用的 RPC；不會清除既有家庭資料。
3. 保留 Email／Password 登入，依家庭使用方式設定信箱驗證。若啟用驗證，請確保驗證信能正常寄送。
4. 從專案連線/API 設定複製 **Project URL** 與 **publishable key**（或舊版 `anon` public key），填到各手機的 App 裡。手機只使用公開用戶端金鑰。
5. 舊安全測試需在 migration 前執行。升級後使用 [`backend/calendar-security-tests.sql`](backend/calendar-security-tests.sql) 驗證共用行事曆、家庭隔離與試算表佇列；測試最後會回滾測試資料。

後端使用 Supabase Auth 確認使用者身分、私有 schema 與 RLS 隔離資料，App 透過驗證身分的 RPC 存取自己的家庭。一次性邀請只存雜湊值。登入憑證使用 Android Keystore 加密儲存。

## 編譯與驗證

環境：JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2、Android SDK 35。最低 Android 8.0（API 26）。地圖使用 MapLibre Native 13.6.1 OpenGL 與 OpenFreeMap，畫面保留地圖來源標示。

使用 Android Studio 開啟此資料夾，完成 SDK 安裝與授權，即可建置。這台電腦也可使用專案內下載的工具：

```powershell
.\tools\bootstrap-android.ps1 -SkipSetup
```

第一次設定可執行 `.\tools\bootstrap-android.ps1 -SetupOnly`，從官方來源下載並驗證 JDK、Gradle 與 Android SDK 工具。編譯工具快取保留在 `.tooling`，不放進原始碼交付壓縮檔。

建置指令包含 `assembleDebug`、`lintDebug` 與 `testDebugUnitTest`。時間規則與位置校驗有 JVM 單元測試；資料庫測試使用真實 PostgreSQL 相容引擎驗證角色及 RPC。

0.2.0 驗證結果：Android 編譯成功、Lint 0 錯誤（17 項版本、在地化與同步寫入偏好警告）、32 項 Android 單元測試通過，APK 簽章與 16 KB 對齊檢查通過。Google 同步模組 17 項離線測試通過。後端 SQL 測試腳本已提供；因測試引擎下載授權未完成，**本次尚未執行 SQL 測試**。Supabase 實際登入、Google 寫入、跨手機同步與真機通知／定位也尚待驗證。

本機後端測試可用 `node tools/test-backend.mjs --download` 執行。第一次從官方 npm registry 下載固定版本 PGlite，核對 SHA512 後執行；後續可省略 `--download`。這個測試使用本機 Auth stub，與實際 Supabase HTTP 登入及多手機連線測試分開。

實際手機的通知橫幅、鎖屏、休眠、省電與 GPS 表現，仍需在家人的手機上測試。此次沒有連接家人的手機或使用真實家庭位置資料。

## 官方參考

- [Android 通知](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications)
- [Android 使用情況存取](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [定位前景服務](https://developer.android.com/develop/background-work/services/fgs/service-types#location)
- [Supabase 資料庫函式](https://supabase.com/docs/guides/database/functions)
- [Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [OpenFreeMap 地圖設定](https://openfreemap.org/quick_start/)
