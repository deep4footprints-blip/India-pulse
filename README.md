# India Pulse

Hourly-refreshing news monitor for developments that can affect India and the Indian economy. True-black editorial UI.

## Get the APK
1. Push to `main` (the **Build APK** workflow runs on every push) or run it from the Actions tab.
2. Open the finished run → Artifacts → `india-pulse-apk` → unzip → install `app-debug.apk`.

## Features
- **Story clustering** – the same event across many outlets collapses into one card ("+N more").
- **Impact tags** – rule-based High / Medium / Low, direction for India, affected sectors, one-line "why it matters".
- **Native story page** – tapping a headline opens an in-app page: impact brief, reader-mode article text (Android app), insight, and every source covering the story.
- **Reader mode** – `ArticleFetcher.kt` resolves the Google News link, downloads the publisher page like a normal browser, and Mozilla Readability extracts the text on-device. If a source is paywalled or blocked it automatically tries up to two other sources, then falls back to the brief. No paywall bypassing. Per-source success rates are shown under Alerts.
- **Insight** (per story) – key figures, transmission chain, sector exposure, coverage timeline.
- **Topics** – Follow, ★ Watch (boost + alerts), Mute (hide by keyword), Remove. Add your own.
- **Trends** – 14-day coverage lines per theme and sector (history builds from first use).
- **Ask** – questions answered on-device from the last 7 days of collected headlines (no AI model).
- **Alerts** – Android: hourly background check + notifications; browser: only while the tab is open.

## Layout
- `app/src/main/assets/index.html` — the UI and all logic (single file). In the app, feeds are fetched through a native bridge (`MainActivity.kt`), no CORS proxies.
- `AlertWorker.kt` / `Notifier.kt` — background alerts (WorkManager, ~hourly).
- `web/index.html` + `server.js` — web version. `node server.js` → http://localhost:3000 (the server relays Google News RSS). Double-clicking `web/index.html` also works via public CORS proxies, less reliably.

**Keep `app/src/main/assets/index.html` and `web/index.html` identical** when editing the UI.

## Limits
Impact, tone and sector tilt come from keyword rules on headlines — indicative, not analysis or investment advice. Clustering is headline-based, so differently-worded reports of one event may stay separate.
