# India Pulse

Hourly-refreshing news monitor for developments that can affect India and the Indian economy. True-black editorial UI.

## Get the APK
1. Push this repo to GitHub (branch `main`).
2. Actions tab → **Build APK** → Run workflow (it also runs on every push).
3. Open the finished run → Artifacts → `india-pulse-apk` → unzip → install `app-debug.apk`.

## Layout
- `app/src/main/assets/index.html` — the UI (single file). Inside the app it fetches Google News RSS through a native bridge (`MainActivity.kt`), so no CORS proxies are needed.
- `web/index.html` + `server.js` — web version. `node server.js` → http://localhost:3000, or double-click `web/index.html` (uses public CORS proxies; less reliable).

**Keep `app/src/main/assets/index.html` and `web/index.html` identical** when editing the UI.
