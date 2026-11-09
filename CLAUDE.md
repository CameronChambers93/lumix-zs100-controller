# lumix

Web app that copies photos and videos off Cameron's Panasonic Lumix ZS100 over Wi-Fi into `C:\Users\Cameron\Pictures\Lumix`
and lets any device on the home network browse and download them. Protocol details: `PROTOCOL.md`.

- **URL:** http://10.0.0.129:8420 (the PC's LAN address; port published by the dc container `dc-lumix`).
- **Camera:** joins the home Wi-Fi "Via Network" (Wi-Fi → Remote Shooting & View) and is at `10.0.0.220`
  (changeable in the app's settings, saved in `data/config.json`). It only answers while on that waiting screen.
- **Code:** `app/server.py` (standard-library Python, no deps) + `app/static/index.html` (single page, no build step).
  `app/start.sh` (re)starts it in the background; `postStartCommand` runs it on every container start. Log: `/tmp/lumix.log`.
  After editing the server: `sh app/start.sh`. Static files are read per request, so no restart for those.
- **Paths in the container:** library `/photos` (bind mount of `Pictures\Lumix`), app state in `data/` (gitignored:
  config, cached catalog, thumbnail/preview cache so the gallery works with the camera off).
- **Never deletes from the camera.** Files are named as on the card (`P1060871.JPG`); "on the PC" means a file with that
  name and the camera's byte size exists. Downloads go to `*.part` then rename.
- **The camera serves one app at a time** (others get 503 / `err_already_connected`). The server only polls it while
  a page polled `/api/status` in the last 30 s or a copy is running, then lets go so the phone app can connect.
- **The server finds the camera itself:** when the configured IP stops answering (and a page is polling), it sweeps the /24 with
  `getstate` (multicast SSDP first; blocked by Docker NAT) at most once a minute and saves the new IP. Restart as `-u vscode` (`/tmp/lumix.log`).
- **Web app UI mirrors the Android gallery** (all but Remote): day groups, search, Photos/Videos filter, sort, long-press/drag select,
  pull-to-refresh, fast scroll, zoomable viewer with Full size, Details panel, theme, Find camera. Server additions: capture dates in
  `data/dates.json` (from `X-REC_DATE_TIME` on any camera fetch, plus a background filler thread `fill_dates` that HEADs thumbnails
  newest-first when the camera is held and idle; library JPEGs use EXIF), `GET /api/exif/<id>` (pure-Python parser; local file or the first
  128 KB of the camera original; cached in `data/exif.json`), `POST /api/find`; `/api/items` rows have `date`, `dur`, `w`, `h`.
  `python3 app/test_exif.py` checks the EXIF parser.
- The camera's web server is tiny: camera fetches go through a 2-slot semaphore, and the full-file download is serial.

## Android app (`mobile/`)

Capacitor 8.6 app v2.0 (`com.cameron.lumix`, Android 10+) that talks to the camera directly, no PC needed; works on the
camera's own hotspot (`192.168.54.1`) or on home Wi-Fi. Saves into the gallery (default `DCIM/Lumix`).

- **UI/logic:** `mobile/www/index.html` (no bundler; `window.Capacitor`; CSS copied from the web app's). Gallery grouped by day
  (dates from `X-REC_DATE_TIME`), filters/search/sort, select (long-press, drag, day, all), pull-to-refresh, fast scroll,
  viewer (pinch/double-tap zoom, Full size, Details/EXIF, Share, Open in), Remote screen (live view, shutter, zoom, focus, video),
  full-screen Settings. Android Back arrives as a native `back` event (`handleBack`).
- **Native** (`android/app/src/main/java/com/cameron/lumix/`): `LumixNativePlugin` (all camera HTTP pinned to the Wi-Fi network),
  `SaveQueue` (static download engine) + `SaveService`, `LocationService` + `LocationLog`.
  - Plugin methods: `httpGet/httpPost, bindWifi, listSaved, thumbs, camThumb, camDates/stopDates, exif, exifRemote, share, openExternal,
    exitApp, enqueue/queueState/cancelSave, freeSpace, requestNotifications, appInfo, setSystemBars, discover, cameraInfo,
    getCameraWifi/setCameraWifi/joinCameraWifi/leaveCameraWifi/joinState, locationStatus/setLocationRecording/clearLocationLog,
    startLiveView/stopLiveView`. Events: `queue, saved, date, back, frame, liveview, location`.
  - `SaveService` (foreground, `dataSync`): background saving with progress/Cancel notification; resume with `Range` (SharedPreferences
    `resume`), 3 tries per file, waits up to 60 s for the camera, wake + Wi-Fi lock, keepalive `getstate` every 5 s, GPS-tags JPEGs.
  - `LocationService` (foreground, `location`): fix every 2 min / 100 m into `filesDir/locations.csv`; photos within 10 min get GPS EXIF.
  - Permissions: INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, CHANGE_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE (SSDP),
    FOREGROUND_SERVICE(+_DATA_SYNC, +_LOCATION), POST_NOTIFICATIONS, WAKE_LOCK, VIBRATE, ACCESS_FINE/COARSE_LOCATION.
  - Settings (localStorage unless noted): camera address override + Find camera, auto-join camera hotspot (SSID prefix + password in
    SharedPreferences `camwifi`), gallery folder (every folder ever used is passed to `listSaved`), downloads at once 1/2, auto-save on
    connect, record location, theme System/Light/Dark. The app finds the camera by itself (`discover()`: multicast SSDP, then a /24 sweep).
  - The camera drops back to `rec` mode by itself some seconds after its last client leaves. Everything that lists or downloads
    (app connect/loop/listing, `SaveQueue`, `app/server.py`) sends `camcmd&value=playmode` first when `getstate` says otherwise,
    except while the Remote screen is open. Remote always ends with `stopstream` + `playmode`.
  - No clock-set command exists on the ZS100 (not offered); no deleting from the camera.
- **Build:** `sh mobile/build.sh` in the container → `dist/lumix.apk`, also served at http://10.0.0.129:8420/lumix.apk.
  Bump `versionCode` in `android/app/build.gradle` per release (v2.0 = code 12).
- **Signing key:** `mobile/keystore/` (gitignored). Losing it means updates won't install over the old app (uninstall
  first). Toolchain (Node 22, JDK 21, SDK 36) is in the Dockerfile; Gradle cache in volume `dc-lumix-gradle`.

## Testing (emulator and phone)

Wireless ADB to Cameron's phone, driven by `mobile/tools/phone.sh` (run in the container: `sh mobile/tools/phone.sh`;
checklist in `mobile/tools/TESTING.md`). The release build has WebView debugging on. Always use explicit `ip:port`
(no mDNS through Docker NAT). Never uninstall the app (it would orphan the saved photos); `install` uses `-r`.

- **Once:** phone Developer options > Wireless debugging > "Pair device with pairing code", then
  `phone.sh pair <ip:port> <code>`. Then `phone.sh connect <ip:port>` (the port on the main Wireless debugging
  screen; it changes whenever debugging is toggled). The target is remembered in `mobile/tools/.device`.
- **Common:** `status`, `install`, `launch`, `stop`, `shot` (prints a PNG path to view), `logs [n]`,
  `eval '<js>'` / `console [secs]` (Chrome DevTools Protocol via `cdp.mjs`, page globals like `cam`, `sync` work),
  `tap x y`, `text '...'`, `back`.
- Test on the emulator by default; a few test saves there are fine (newest files, never the whole card). Ask Cameron
  before saving to his phone or taking test shots.

**Emulator (default for development):** AVD `lumix_s23plus` (Galaxy S23+-like: Android 13 / API 33, 1080x2340, density 400, Google APIs + Play Store, x86_64;
stock Google Android, not One UI). The older `lumix_pixel5` (Android 12 / API 31, 440 dpi) still exists; run only one at a time (both use adb port 5555).
It runs on the Windows host; the container reaches it at `192.168.65.254:5555`. Start it from PowerShell:
`& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd lumix_s23plus -no-snapshot-save -gpu auto`. Then in the
container `sh mobile/tools/phone.sh emulator` (once per session) and prefix everything with `PHONE_TARGET=emulator`.
It reaches the camera at `10.0.0.220` through the host's NAT (home-Wi-Fi mode only; no hotspot; the API 33 notification prompt can be tested on `lumix_s23plus` but not on the Pixel 5;
live view needs `adb emu redir add udp:49199:49199` + `mobile/tools/udprelay.py`). Fake camera for tests: `mobile/tools/fakecam.py`. `mobile/tools/out/` is scratch
(screenshots, probe dumps). Details and limits: `mobile/tools/TESTING.md`.
