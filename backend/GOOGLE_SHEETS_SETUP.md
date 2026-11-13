# Google 試算表紀錄設定

此功能把 App 已成功儲存的業務變更寫入家庭專用 Google 試算表。App 使用者登入、家庭權限、行事曆同步由 Supabase 管理；Google 試算表保存可查閱的歷史紀錄。

**目前交付的是程式與空白範本，尚未連接真實 Google 帳號或部署同步函式。** Google Drive 連線用於建立試算表；持續自動寫入則需要以下伺服器設定，即使關閉這個 Codex 聊天也能執行。

## 1. 準備專用試算表

將交付的家庭試算表範本匯入為原生 Google 試算表。每個家庭使用不同的一份試算表，並由擁有者決定哪些家人可以查看；不要設為公開連結。

五個紀錄分頁使用以下名稱，第一列是表頭，第二列起保留空白。同步程式會檢查表頭相符後才寫入。

| 分頁 | 欄位（由 A 欄起） |
|---|---|
| 定位回報 | 紀錄ID、家庭ID、操作、回報者ID、名稱、定位時間、緯度、經度、精度（公尺）、電量（%）、紀錄時間、原始資料（JSON） |
| 家庭成員 | 紀錄ID、家庭ID、操作、成員ID、名稱、分享狀態、紀錄時間、原始資料（JSON） |
| 行事曆 | 紀錄ID、家庭ID、操作、活動ID、標題、開始時間、結束時間、時區、提前提醒（分鐘）、地點、備註、登錄者ID、紀錄時間、原始資料（JSON） |
| 回報設定 | 紀錄ID、家庭ID、操作、成員ID、回報間隔（分鐘）、提醒開關、提醒位置、行事曆提醒開關、紀錄時間、原始資料（JSON） |
| 操作紀錄 | 紀錄ID、家庭ID、資料類型、操作、使用者ID、實體ID、紀錄時間、原始資料（JSON） |

日期欄位以台北時間的試算表日期數值保存，格式為 `yyyy-mm-dd hh:mm:ss`；原始 ISO 時間與事件 IANA 時區完整保留在 JSON。電量是 0 到 100 的數字，欄位格式使用一般數值，不再乘 100。

同步時會為各分頁的日期資料欄套用整欄日期格式（保留第一列表頭），因此超過範本預設列數後，新增紀錄仍會顯示日期時間。若資料已寫入但格式更新失敗，該筆仍保留待同步；下次查重後會先補上格式，再標示完成。

每筆變更有固定「紀錄ID」。修改與刪除會留下各自的紀錄，因此可追溯行事曆及設定變動。停止位置分享會撤回 App 地圖上的最新位置；**已同步到家庭試算表的歷史位置仍保留**。請讓加入家庭的成員了解此保存方式。

## 2. 建立 Google 伺服器帳號

1. 在自己的 Google Cloud 專案啟用 Google Sheets API。
2. 建立一個專供此功能使用的服務帳戶，取得 JSON 金鑰，僅存入 Supabase Edge Function 的伺服器秘密設定。
3. 把該服務帳戶 JSON 的 `client_email` 加到家庭試算表的共用清單，授予「編輯者」。服務帳戶只需這份文件的權限。
4. 從試算表網址 `https://docs.google.com/spreadsheets/d/文件ID/edit` 取得文件 ID。
5. 從 App 登入後的家庭資料或資料庫管理頁取得自己的家庭 UUID。

Google 私鑰、Supabase 服務金鑰及排程密碼均存於伺服器，不放進 APK，也不填在手機的公開用戶端金鑰欄。

## 3. 更新資料庫與部署函式

先依序執行原有 `schema.sql`（新專案才需要）及 `migrations/002_calendar_and_sheets.sql`。升級現有專案時只執行 migration。

在專案根目錄使用已登入自己的 Supabase CLI 執行 `supabase init`（若尚未初始化），再執行 `.\tools\prepare-sheets-function.ps1`，把入口檔與同步模組複製到標準的 `supabase/functions/sheets-sync/`。將以下設定加入 `supabase/config.toml`：

```toml
[functions.sheets-sync]
enabled = true
verify_jwt = false
```

此函式使用獨立的 `x-sync-secret` 驗證排程請求。只有經過驗證才讀取伺服器憑證或 outbox；手機無法呼叫此端點取得資料。

部署前，透過 Supabase 專案的 Edge Function Secrets 設定：

| 環境變數 | 內容 |
|---|---|
| `GOOGLE_SERVICE_ACCOUNT_JSON` | 完整服務帳戶 JSON，包含私鑰 |
| `GOOGLE_SHEETS_FAMILY_MAP` | `{"自己的家庭UUID":"該家庭的試算表文件ID"}` |
| `SHEETS_SYNC_SECRET` | 至少 32 字元的隨機排程密碼，建議使用 32 隨機位元組產生的 64 字元十六進位字串 |
| `SUPABASE_URL` | 部署平台通常自動提供本專案 URL |
| `SUPABASE_SERVICE_ROLE_KEY` | 部署平台通常自動提供的伺服器服務金鑰；確認它能以 `service_role` 執行 outbox RPC |

多個家庭的映射可放在同一 JSON，但每個家庭必須使用不同的試算表文件 ID。新增映射後重新部署函式，讓執行個體載入新設定。

```powershell
supabase functions deploy sheets-sync --project-ref YOUR_PROJECT_REF --no-verify-jwt
```

原始碼入口為 `backend/functions/sheets-sync/index.ts`，匯入同目錄的 `sync-core.mjs`。準備指令複製到 CLI 的標準部署位置，不需要自訂入口或安裝 npm 套件。日後更新原始碼後，先重跑準備指令，再部署。

## 4. 啟用雲端排程

在 Supabase 啟用 Cron、`pg_net` 與 Vault，使用 SQL Editor 把專案網址與**同一份**排程密碼存入 Vault。下列是需要自行替換值的設定範例，請不要把真實密碼提交到原始碼：

```sql
select vault.create_secret('https://YOUR_PROJECT_REF.supabase.co', 'family_sheets_project_url');
select vault.create_secret('REPLACE_WITH_THE_SAME_RANDOM_SECRET', 'family_sheets_sync_secret');

select cron.schedule(
  'family-sheets-sync',
  '*/5 * * * *',
  $$
    select net.http_post(
      url := (select decrypted_secret from vault.decrypted_secrets
              where name = 'family_sheets_project_url') || '/functions/v1/sheets-sync',
      headers := jsonb_build_object(
        'Content-Type', 'application/json',
        'x-sync-secret', (select decrypted_secret from vault.decrypted_secrets
                          where name = 'family_sheets_sync_secret')
      ),
      body := '{}'::jsonb,
      timeout_milliseconds := 120000
    );
  $$
);
```

範例每五分鐘執行一次。同步是非同步佇列，App 儲存成功與 Google 試算表寫入成功是兩個狀態；Google 斷線、配額、權限或格式問題會保留待同步資料，在後續排程重試。

## 5. 驗證與日常維護

- 建立一筆可辨認的家庭活動，確認 App 可讀取，再查看 Google 試算表「行事曆」分頁是否出現對應紀錄。
- 在 Edge Function 日誌查看執行計數，並在資料庫管理頁查看 outbox 的 `delivered_at`、`last_error`。錯誤只記錄代碼，不輸出私鑰、權杖或 Google 原始錯誤內容。
- `SHEET_HEADER_MISMATCH`：恢復分頁名稱、A1 表頭與欄位順序。程式不會自動改寫自訂表頭。
- `UPSTREAM_HTTP_403`：檢查 Sheets API 啟用、服務帳戶編輯權及配額。
- `UPSTREAM_HTTP_404`：檢查文件 ID、分頁是否存在及服務帳戶是否能看見文件。
- `FAMILY_SPREADSHEET_NOT_CONFIGURED`：補齊該家庭映射並重新部署。
- 修改服務帳戶金鑰或排程密碼後，更新 Secrets、Vault 對應值並重新部署。

同步批次最多處理 50 筆、最多三個家庭，單次執行預算 110 秒。資料庫以每個家庭的租約序列化同步；重試前會讀取 A 欄既有紀錄 ID 查重。模糊的寫入失敗會等原租約過後再重試。Google Sheets 與 PostgreSQL 之間沒有跨服務交易；保留固定紀錄 ID 方便查核，也請避免人工刪除或修改此欄。

本機離線測試（無帳號、無網路）：

```powershell
node --test backend/functions/sheets-sync/sync-core.test.mjs
```

已通過 17 項測試，包含跨家庭路由、日期數值、公式文字的 RAW 寫入、失敗後查重、格式失敗後重試、超過範本列數的日期格式、租約、錯誤表頭、密碼拒絕、網路超時與 RS256 OAuth 簽章。尚未執行真實 Google API、Supabase Edge Runtime 或跨服務整合測試。

## 官方參考

- [Google 服務帳戶 OAuth](https://developers.google.com/identity/protocols/oauth2/service-account)
- [Google Sheets 寫入與 RAW 選項](https://developers.google.com/workspace/sheets/api/guides/values)
- [Google Sheets append](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets.values/append)
- [Google Sheets API 限制](https://developers.google.com/workspace/sheets/api/limits)
- [Supabase 函式秘密設定](https://supabase.com/docs/guides/functions/secrets)
- [Supabase 自訂函式入口](https://supabase.com/docs/guides/local-development/cli/config)
- [Supabase 排程函式](https://supabase.com/docs/guides/functions/schedule-functions)
