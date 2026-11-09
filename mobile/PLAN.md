# Android app feature plan (v1.4 → v2.0)

**Status: complete (2026-11-06).** v2.0 (versionCode 12) shipped; see the Phase 7 entry at the bottom for what shipped,
what was left out, and what is still untested on a real phone (also listed at the end of `mobile/tools/TESTING.md`).

Executed phase by phase by subagents. **Do only the phase you were assigned**, then append to the Progress log at the
bottom and stop. Read `CLAUDE.md`, `PROTOCOL.md`, `mobile/tools/TESTING.md`, `mobile/www/index.html` and
`LumixNativePlugin.java` before you start. A pre-plan backup of the sources is at
`C:\Users\Cameron\AppData\Local\Temp\claude\968aecbcdad54ac3\1ee7dff4-7ced-4dc4-9619-00024ebce673\scratchpad\backup-v5`.
There is no git: before editing, copy the files you'll touch to `mobile/.bak/phase-N/` (gitignored location is fine).

Out of scope: deleting anything from the camera (and any "safe to delete" UI).

## How to work (Windows host, Claude Code tools)

- The project is bind-mounted into the dev container `dc-lumix` at `/workspaces/lumix`. Edit files on Windows with
  the normal file tools, run toolchain commands in the container:
  - PowerShell: `docker exec -w /workspaces/lumix dc-lumix sh mobile/build.sh`
  - Bash tool (Git Bash) mangles `/paths`: prefix with `export MSYS_NO_PATHCONV=1;`.
  - Multi-line shell: write a script into `mobile/tools/out/` (bind-mounted) and `docker exec ... sh mobile/tools/out/x.sh`.
- **Test on the emulator, not Cameron's phone.** It is already running on the Windows host (AVD `lumix_pixel5`,
  Android 12 / API 31, 1080x2340) and adb in the container is connected to it at `192.168.65.254:5555`.
  Use `docker exec -w /workspaces/lumix -e PHONE_TARGET=emulator dc-lumix sh mobile/tools/phone.sh <cmd>`
  (`install`, `launch`, `stop`, `shot`, `logs`, `eval`, `console`, `tap`, `back`). If adb lost it:
  `... phone.sh emulator`. Screenshots land in `mobile/tools/out/`; view them with the Read tool on the Windows path
  `C:\Users\Cameron\Projects\lumix\mobile\tools\out\...`. Do not install on the real phone.
- The emulator reaches the camera at `10.0.0.220` through the host's NAT (home-Wi-Fi mode). The camera's hotspot mode
  can't be tested here; keep that code path intact.
- Build after every phase, bump `versionCode` (+1) and `versionName` in `mobile/android/app/build.gradle`, install
  with `-r` (phone.sh does this), and run TESTING.md's checklist items that your phase touches plus your new checks.
- Camera rules: it serves **one client at a time**. If you get "Busy", the PC page (http://10.0.0.129:8420) or
  the app is holding it; `phone.sh stop` the app before probing with curl from the container, and wait ~20 s after.
  Pair before media requests: `curl "http://10.0.0.220/cam.cgi?mode=accctrl&type=req_acc&value=4D454930-0100-1000-8002-AABBCCDDEEFF&value2=probe"`,
  then poll `mode=getstate` every ≤5 s while you work. Its battery was at 1/3 when this plan was written; if the
  camera stops answering, finish what you can without it and say so in the log. Don't wait in loops for it.
- Saving to the **emulator** is allowed for testing, but keep it to ≤15 photos + 1–2 videos total across all
  phases (pick the newest files). Never save the whole card. Never delete from the camera.
- Cameron allowed, for Phase 6 only: up to **2 test shots** with the shutter and **setting the camera clock to the
  current local time**. Nothing else that writes camera settings.
- Keep the code style: plain JS in one `index.html` (no bundler, no frameworks), standard-library Java + AndroidX.
  Match the existing CSS tokens and the sentence-case, plain wording of existing UI text. Every interactive element
  gets an accessible name. Light and dark must both look right (screenshot both: `adb shell cmd uimode night yes|no`).
- `window.Capacitor.convertFileSrc(uri)` turns `content://…` and file paths into URLs the WebView can load
  (`/_capacitor_content_/`, `/_capacitor_file_/`). Use it for anything shown from the phone's storage.
- If a step turns out impossible (protocol not supported, etc.), don't fake it: leave it out, say why in the log.

## Phase 0: Emulator docs + protocol probes (no app changes)

1. **Emulator docs.** No docs exist yet. Find how it's set up (AVD in `C:\Users\Cameron\.android\avd\lumix_pixel5.*`,
   SDK in `%LOCALAPPDATA%\Android\Sdk`, `phone.sh emulator`, the `192.168.65.254:5555` address = host loopback via
   Docker Desktop; work out how adb on the host exposes 5555, e.g. `adb tcpip`/the emulator's own port, by looking at
   the host, not guessing). Write: how to start it on Windows (exact command), how the container connects,
   `PHONE_TARGET=emulator`, the camera-through-NAT note, what can't be tested there (hotspot, real notifications
   permission on API 33+, inbound UDP unless redirected). Put a short section in `CLAUDE.md` under
   "Testing on the phone" (rename it "Testing (emulator and phone)") and the details in `mobile/tools/TESTING.md`.
2. **Protocol probes** (curl from the container, paired as above). Record results in `PROTOCOL.md`:
   - The `dc:date` (and any other date) fields in a Browse response: exact format, timezone. Show one raw `<item>`.
   - HTTP `Range` on `DO<id>.JPG` and on an MP4: `curl -s -r 100-199 -D - -o /dev/null`. 206 or 200?
   - `cam.cgi?mode=getinfo&type=capability` full output, and `getinfo&type=allmenu` / `getinfo&type=curmenu` if they answer.
   - Clock read candidates: `mode=getsetting&type=clock`, `type=current_time`, `type=datetime`, `type=time`. Note which answer.
   - SSDP: multicast `M-SEARCH` to 239.255.255.250:1900 (`ST: urn:schemas-upnp-org:device:MediaServer:1` and
     `ssdp:all`) from the container. Docker NAT may block multicast; say so if it does and try unicast.
   - Record-mode probes (allowed, non-destructive): `mode=camcmd&value=recmode`, `getstate` (cammode should be rec),
     `mode=startstream&value=49199` while listening `nc -ul 49199` / a small Python UDP listener in the container
     for 3 s (packets may not reach the container through NAT; note it), `mode=stopstream`, then
     **`mode=camcmd&value=playmode`** to put it back. Always end in playmode.
3. Log findings, stop.

## Phase 1: Gallery data model, offline viewing, dates, filters

Native (`LumixNativePlugin.java`):
- `listSaved` returns per file: `name, size, uri (content://), mime, dateTaken (ms, DATE_TAKEN or DATE_ADDED*1000),
  width, height, duration (video)`. Query every folder in a `folders` array argument (default `["DCIM/Lumix/"]`),
  excluding pending rows (default MediaStore behaviour).
- `thumbs({uris:[...], size:256})`: for each uri, `ContentResolver.loadThumbnail(uri, Size(size,size), null)`,
  cache as JPEG in `cacheDir/thumbs/<md5(uri)>.jpg` (skip if present), return `{uri: filePath}` map. Run off the UI thread.

- **Dates (Phase 0: Browse has none; every media GET/HEAD returns `X-REC_DATE_TIME: 2026-10-31T21:38:14`, local
  camera time).** Load camera thumbnails through native instead of `<img src=camera>`:
  `camThumb({url, id})` GETs the `DT` thumbnail (through the existing pool, max 2 concurrent camera requests from
  this method), caches the bytes at `cacheDir/camthumbs/<id>.jpg` (skip the request if cached), and returns
  `{path, date}` where `date` is the header value (null if cached and unknown). This gives offline thumbnails for
  unsaved items too. `camDates({items:[{id,url}]})` does `HEAD` (or `Range: bytes=0-0` GET if HEAD fails) on `DT`
  URLs one at a time and emits `date` events `{id, date}`; JS runs it in the background after each listing for
  items without a known date, and stops it when a save starts (saves have priority).

JS (`index.html`):
- `it.date` (ms) comes from `X-REC_DATE_TIME` parsed as local time. Keep a permanent `dates` map (id → ms) in
  localStorage: an id's date never changes. Saved items without a camera date use `dateTaken`. Until an item's
  date is known, place it by id (ids increase with time) and group it under its neighbours' day; re-render
  (debounced) as dates arrive.
- One merged item list: camera catalog + saved files not in the catalog (name match) as `source:"phone"` items
  (they still show when the camera is off or the card was wiped). An item has: `id, name, size, kind, date,
  orig/thumb/preview` (camera, may be absent) and `uri, localThumb` (phone, may be absent).
- Tile image: prefer the local thumbnail when saved (works offline, no camera traffic), else the camera thumb
  (only when online, via the existing queue). Saved-only items open in the viewer from `convertFileSrc(uri)`.
- Grid grouped by day with sticky headers ("Today", "Yesterday", "Mon 3 Nov", "12 Aug 2025"); sort menu:
  newest first (default) / oldest first. Remember the choice in localStorage.
- Filters: keep All/New/Saved; add a second segmented control All / Photos / Videos; a search field (filename
  substring, case-insensitive) behind a search icon in the toolbar.
- Tiles: `aria-label` like "P1060871.JPG, photo, 3 Nov 2026, saved", `aria-pressed` while selecting.
- Viewer header shows the "taken on" date and time.
- Empty states must still make sense for each filter combo.
- Test offline: `phone.sh stop`, take the camera out of the picture by setting a bogus IP in settings, relaunch:
  saved items show with thumbnails and open in the viewer.

## Phase 2: Viewer

- Pinch-zoom (two-finger), pan when zoomed, double-tap toggles 1x/2.5x at the tap point, swipe navigation only at
  1x. Pointer events + CSS transforms, no library. Reset zoom when changing photo.
- Full resolution: saved photos load the original from `convertFileSrc(uri)`. Camera-only photos show the preview,
  plus a "Full size" button that loads `orig` from the camera (spinner; it's ~5 MB at ~1.2 MB/s). Videos: saved
  ones play from the local uri.
- Info panel (an "i" button): name, date, size, dimensions, and EXIF. Native `exif({uri})` (saved) and
  `exifRemote({url})` (camera: stream the original into `androidx.exifinterface.media.ExifInterface(InputStream)`
  and disconnect once parsed, so only the header crosses the network). Fields: camera model, lens focal length (and
  35mm equiv), aperture, shutter, ISO, exposure comp, flash, GPS if present. Add
  `implementation "androidx.exifinterface:exifinterface:1.4.1"` (use the newest that resolves).
- Share: native `share({uris:[...]})` → `ACTION_SEND`/`ACTION_SEND_MULTIPLE` chooser with
  `FLAG_GRANT_READ_URI_PERMISSION`. Available for saved items in the viewer and for a multi-selection (only the
  saved ones; tell the user if some weren't saved yet).
- "Open in…": native `openExternal({uri, mime})` → `ACTION_VIEW` chooser (opens the Gallery/Photos app or editors).
- **Fixes found in Phase 1:**
  - Android Back leaves the app instead of closing the viewer (also true in v1.4). Handle it natively: register an
    `OnBackPressedCallback` in the plugin (or MainActivity) that emits a `back` event; JS closes the topmost thing
    (viewer info panel → viewer → search/sort menu → select mode, later dialogs) and calls native `exitApp()` (or
    `moveTaskToBack`) when nothing is open. Remove the `history.pushState` hack if it's no longer needed.
  - The WebView never reports `prefers-color-scheme: dark`: `AppTheme` in `res/values/styles.xml` has parent
    `Theme.AppCompat.Light.DarkActionBar`, and the WebView follows the app theme's `isLightTheme`. Change it to a
    DayNight parent (keep the other styles consistent) and confirm with `adb shell cmd uimode night yes` + relaunch
    that the page's `matchMedia('(prefers-color-scheme: dark)').matches` is true. Phase 5 adds the manual theme choice.
- **Pending camera checks from Phase 1** (camera was off): if `getstate` answers, run them first: online grid via
  `camThumb`, real `X-REC_DATE_TIME` dates and background fill, pause during a save, `DATE_TAKEN` on a newly saved
  photo, TESTING.md items 2–5. If the camera is still off, keep them in the pending list in your log entry.

## Phase 3: Background saving (foreground service), resume, retries, auto-save

Move the save queue into native code so it survives the app being backgrounded or the screen locking.
- `SaveService` (foreground service, `foregroundServiceType="dataSync"`): manifest permissions `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`. Start it with
  `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. Request `POST_NOTIFICATIONS` at runtime
  (API 33+) the first time a save starts; saving works even if denied.
- Notification channel "Saving": "Saving 3 of 12 · 41 MB of 70 MB", determinate progress, Cancel action,
  tap opens the app. When finished: "Saved 12 files" or "Saved 10, 2 failed" (tap opens app).
- Hold a partial `WakeLock` and a `WifiManager.WifiLock` (high-perf) while running.
- The service keeps the camera session alive: `getstate` every 5 s while running (the JS loop may be throttled in
  the background), re-pair if `result` isn't ok.
- Plugin API: `enqueue({items:[{url,name,size,mime,date}], camIp, folder, parallel})`, `cancelSave()`,
  `queueState()` → `{running, total, done, failed:[{name,error}], bytes, totalBytes, current:[names]}`; event
  `queue` with the same snapshot, throttled to ≤4/s; event `saved` `{name,size,uri}` per file. JS mirrors this into
  `sync` and calls `queueState()` on start and on `visibilitychange` so a reopened app shows the live state.
  Remove the JS-side loop and `keepAwake` use during saves (keep the method).
- Resume: keep the pending MediaStore row on a network failure, remember `name → {uri, bytes}` in
  SharedPreferences. On retry, if Phase 0 found Range works, request `Range: bytes=N-`, require 206, open the row
  with mode `"wa"` and append; otherwise reopen with `"wt"` and start over. Cancel deletes the pending row.
  Verify final size == camera size before clearing `IS_PENDING`.
- Retries: 3 attempts per file with backoff (2 s, 5 s, 10 s), and if the camera drops, wait up to 60 s for it
  before failing the rest. Failed files stay "new" in the grid; after the queue, the card shows
  "2 couldn't be saved · Retry · Details" (details dialog lists every failure, not just 3).
- Parallel: setting "Downloads at once: 1 / 2" (default 1). Implement with a fixed pool. Measure on the emulator
  with 4 photos each way and write the numbers in the log; if 2 isn't faster, leave the default at 1 and say so.
- Free space: before enqueueing, `StatFs` on the primary external volume; if needed bytes + 200 MB margin > free,
  show a dialog ("Needs 1.2 GB, 800 MB free") with "Save what fits" (newest first, up to the free space) / Cancel.
- Auto-save: settings toggle "Save new files automatically when the camera connects" (default off). After the
  first successful listing per connection, enqueue every not-yet-saved item (still subject to the free-space check;
  no dialog: save what fits and notify).
- Test: start a 3-photo save, press Home (`adb shell input keyevent 3`), wait, reopen: it finished; notification
  visible in a screenshot of the shade (`adb shell cmd statusbar expand-notifications`). Test resume by stopping
  the camera link mid-video (e.g. `adb shell svc wifi disable` for 5 s, then enable) and check the log shows a
  206 resume. Test cancel leaves no partial row.

## Phase 4: Selection and browsing

- Long-press a tile (450 ms) enters select mode with that tile selected (haptic: `navigator.vibrate(10)`).
- Drag-select: after a long-press, dragging across tiles selects the range from the start tile to the tile under the
  finger (like Google Photos); auto-scroll when near the top/bottom edge.
- Tap the day header's check circle to select/deselect that whole day.
- "All" in the selection bar selects everything matching the current filters (all days, not just loaded ones) and
  says so: "Select all 312".
- Selection bar actions: Save (camera items not yet saved), Share (saved items). Back button exits select mode.
- Pull-to-refresh at scroll top: indicator, triggers the same as the refresh button; doesn't fire while zoomed
  in the viewer or while selecting.
- Camera request priority (from Phase 2): while the viewer is open, pause grid `camThumb`/`camDates` so the
  viewer's preview, Full size and `exifRemote` aren't stuck behind them (exifRemote took ~25 s during grid loading).
  Order: listing > viewer > save > grid thumbs > dates (saves are native in Phase 3; just don't starve them).
- Fast scroll: a draggable thumb on the right edge appearing during scroll, with a bubble showing the month/year
  under it. Must work with 300+ items; check scrolling stays smooth (no layout thrash: compute header offsets once
  per render).

## Phase 5: Settings, discovery, theme, about

- Settings becomes a full-screen sheet with sections: Camera (address, "Find camera" button), Saving (folder,
  downloads at once, auto-save), Location (Phase 6), Appearance (theme: System / Light / Dark, stored; applied via
  `data-theme` on `<html>` with CSS mirroring the dark tokens; also set the status bar colour), About (app version
  from native `appInfo()` via PackageManager, camera model/firmware from getstate/capability, link-free text).
- Folder: choice of `DCIM/Lumix` (default), `Pictures/Lumix`, or a custom name under DCIM/ or Pictures/ (validate:
  letters, digits, space, `-_`). Keep a list of every folder ever used in localStorage and pass it to
  `listSaved`, so files saved earlier still count as saved.
- SSDP discovery: native `discover({timeout:3000})`: acquire a `WifiManager.MulticastLock`
  (`CHANGE_WIFI_MULTICAST_STATE` permission), send M-SEARCH from a socket bound to the Wi-Fi network
  (`network.bindSocket`), collect responses whose LOCATION port is 60606 or server mentions Panasonic, confirm
  each with `getstate`, return `[{ip, name}]`. Fallback when multicast gets no reply (some routers drop it):
  sweep the phone's Wi-Fi /24 (from `LinkProperties` of the Wi-Fi network) with `getstate` on port 80, 1.5 s timeout,
  ~32 at a time, stop at the first camera that answers; also send a unicast M-SEARCH to each host. Skip the sweep
  on the camera's hotspot (192.168.54.x).
  **Automatic on home Wi-Fi (Cameron asked for this):** the user should never need to type an IP. `connect()` tries
  the last-known IP first; if it fails, run discovery (multicast, then sweep) at most every 30 s while offline, save
  the IP it finds as `homeIp`/`ip`, and connect. The router may hand the camera a new address, and that must heal on
  its own. Settings keeps a manual address field as an override, plus "Find camera" (lists results to pick).
  Show "Searching for the camera…" in the pill while a discovery runs.
- **Auto-join the camera's hotspot (Cameron asked for this).** Settings > Camera: "Join the camera's Wi-Fi
  automatically" toggle, with the network name (prefilled pattern: the camera's SSID starts with the model, e.g.
  `ZS100-…`; the pair reply shows `ZS100-248B00`) and the password shown on the camera screen (stored in
  SharedPreferences, not localStorage). Native `joinCameraWifi({ssid|ssidPrefix, password, timeout})` uses
  `WifiNetworkSpecifier` (SSID or `PatternMatcher.PATTERN_PREFIX`, WPA2 passphrase) +
  `ConnectivityManager.requestNetwork`; on `onAvailable` keep that `Network` and make `wifi()`/`open()`/
  `bindProcessToNetwork` prefer it over other Wi-Fi networks; `onUnavailable`/timeout → reject. Release it
  (`unregisterNetworkCallback`) when the camera goes away for good or the user turns the toggle off.
  When to try: app in the foreground, toggle on, camera not found on the current network after the normal
  connect + discovery attempt, at most once a minute, never while a save is running on another network. The system
  shows its own one-tap approval the first time; tell the user in the UI what to expect. Then 192.168.54.1 as usual.
  The emulator has no camera hotspot: test the request flow as far as it goes (onUnavailable path, no crash, no
  prompt loop), and list the real join as untested on a phone.

## Phase 5b: PC web app finds the camera itself (Cameron asked for this)

- `app/server.py` (stdlib only): when the configured camera IP stops answering, search for it: unicast sweep of
  the configured IP's /24 with `GET /cam.cgi?mode=getstate` on port 80 (1.5 s timeout, ~32 threads, accept a
  `<camrply>` reply or a 503 from a host that also answers on :60606 as "camera, busy"). Multicast SSDP is blocked
  by Docker NAT (Phase 0), so don't rely on it; you may try it first. At most once a minute while a page is polling
  `/api/status` (the server must keep letting go of the camera when no page is open, per CLAUDE.md). Save the
  found IP to `data/config.json`. The settings field stays as an override; show "Searching for the camera…" in
  the status while it runs, in `app/static/index.html`.
- Restart with `docker exec -w /workspaces/lumix dc-lumix sh app/start.sh`, check `/tmp/lumix.log`. Test by setting
  a wrong IP in the page's settings and watching it find 10.0.0.220 again. Don't hold the camera while an emulator
  test needs it (one client at a time). On the emulator multicast probably won't
  get through; test as far as possible and note it.

## Phase 6: Camera features (remote shooting, clock, location)

- **Remote screen** (camera icon in the header, only when online and not saving): switches the camera to recmode,
  shows live view, shutter button, video record start/stop with a timer, zoom (W/T hold buttons:
  `wide-normal`/`tele-normal`, `zoomstop` on release), tap-to-focus (`mode=camctrl&type=touch&value=<x>/<y>&value2=on`,
  0–1000 coordinates; verify), battery. On close, `stopstream` + `playmode`, then refresh the catalog so new shots
  appear.
  - Live view: native `startLiveView({camIp, port:49199})` opens a `DatagramSocket` bound to the Wi-Fi network,
    sends `startstream`, re-sends it every ~10 s if frames stop, finds the JPEG (from `FF D8` to the end of the
    packet) in each datagram, and emits only the newest frame as base64 at ≤15 fps (`frame` event). `stopLiveView`.
    Keep `getstate` polling going.
  - Emulator: inbound UDP won't reach it through NAT. Try `adb emu redir add udp:49199:49199` (or the emulator
    console) plus whatever Windows-side forwarding is needed, and document what worked in TESTING.md. If it can't be
    made to work, test the control commands only and say live view is untested.
  - Test shots: at most 2 photos total (Cameron's OK). Prefer one photo; don't record video unless that's the
    only way to check record start/stop, and then ≤3 s and count it as your second shot.
- **Clock sync**: in Settings > Camera, "Set camera clock to phone time" button. Phase 0 found no command that
  reads the clock (`getsetting&type=clock` → `err_non_support`), so the only check is a test shot's
  `X-REC_DATE_TIME`. Use the command Phase 0 identified; probe the `setsetting` variant with the **current local
  time only** and verify it took (read it back, or the date of a test shot). If no working command is found, leave
  the feature out.
- **Location for photos** (the camera has no GPS; Panasonic's app logs the phone's location and tags photos):
  Settings > Location: "Record location while out shooting" toggle → a foreground service
  (`foregroundServiceType="location"`, `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`)
  using `LocationManager` (no Play Services) that stores a fix every 2 min (and on ≥100 m movement) to a file in app
  storage; persistent notification "Recording location for photos" with Stop. When saving a JPEG, before clearing
  `IS_PENDING`, read `DateTimeOriginal` with ExifInterface on the row's `"rw"` file descriptor, find the nearest fix
  within ±10 min (camera time = phone local time; the clock sync matters), write GPS tags, `saveAttributes()`.
  Then verify the size check still uses the downloaded byte count (the file grows after tagging; "on the phone"
  matching must still work: store the camera size in SharedPreferences per name, or compare before tagging, and
  make `listSaved`/`isSaved` use that). Settings shows how many fixes are stored, plus "Clear location log".
  Test on the emulator with `adb emu geo fix <lon> <lat>`.
- Also check what `add_location_data` in `getstate` means (capability/menu output); if the camera accepts location
  over the protocol, note it in PROTOCOL.md, but the phone-side tagging above is what ships.

## Phase 7: Wrap-up

- Run the whole TESTING.md checklist (updated for the new features) on the emulator. Fix what breaks.
- Update `CLAUDE.md` (Android section: the new plugin methods, services, permissions, settings) and
  `PROTOCOL.md`. Keep CLAUDE.md terse.
- Final `versionName "2.0"`, build, and make sure `dist/lumix.apk` is the final build (served at
  http://10.0.0.129:8420/lumix.apk). Don't install on the phone; Cameron will.
- Summarize in the log: what shipped, what's untested on a real phone (hotspot, API 34+ service rules,
  notifications permission prompt, live view if UDP didn't reach the emulator), and anything left out.

## Progress log

(Each phase appends: date, versionCode, what changed, test evidence (commands + results, screenshot paths),
problems, anything left for later.)

### Phase 0 (2026-11-06) - done, no app changes, versionCode unchanged
- Docs: emulator setup written to `mobile/tools/TESTING.md` ("Emulator setup (Windows host)") and a short section in
  `CLAUDE.md` (heading renamed "Testing (emulator and phone)"). Facts read from the host: AVD `lumix_pixel5`
  (API 31 google_apis_playstore x86_64), started with `emulator.exe -avd lumix_pixel5 -no-snapshot-save -gpu auto`;
  emulator.exe listens on host loopback 5554 (console) / 5555 (adb); Docker Desktop maps `host.docker.internal`
  (192.168.65.254) to the host loopback, hence `adb connect 192.168.65.254:5555`.
- Probes: all recorded in `PROTOCOL.md` ("Phase 0 probes"). Key findings:
  - **Browse items have NO date** (no `dc:date`). Date comes from the `X-REC_DATE_TIME` response header on any
    GET/HEAD of DO/DS/DT (e.g. a `Range: bytes=0-0` on the `DT` thumbnail): `2026-10-31T21:38:14`, local, no timezone.
    Phase 1 must fetch dates per item (lazy, cached) instead of parsing Browse. Browse pages max 50 items.
  - **Range works**: 206 + Content-Range on JPG and MP4 (also `N-` open-ended). Resume in Phase 3 is viable.
  - Capability: zoom camcmds `tele/wide-normal/fast`, `4kphoto_marking`, camspec `geotag_add_enable`; allmenu/curmenu answer.
  - **No clock read command answers** (`getsetting&type=clock` = err_non_support, all others err_param). Phase 6 can
    only show/set the clock if a `setsetting` variant works; likely leave out.
  - SSDP: multicast gets 0 replies from the container (NAT); unicast to camera:1900 works (5 replies for ssdp:all).
  - recmode OK (cammode=rec), startstream OK but 0 UDP packets reached the container (NAT), stopstream OK, playmode
    OK: camera left in `play`. Battery still 1/3.
- Problems: none. Probe scripts remain in `mobile/tools/out/` (p0-p6.sh, raw XML dumps cap.xml, allmenu.xml, curmenu.xml, all.xml).

### Phase 1 (2026-11-06) - code done, built and installed on the emulator; camera checks PENDING (camera was off)
- versionCode 6, versionName "1.5". Backups of the touched files: `mobile/.bak/phase-1/` (index.html original, `index.new.html` = copy of the Phase 1 page, LumixNativePlugin.java, build.gradle).
- Native (`LumixNativePlugin.java`): `listSaved({folders})` returns name, size, uri (content://), mime, dateTaken (DATE_TAKEN or DATE_ADDED*1000), width, height, duration; `thumbs({uris,size})` caches JPEGs in `cacheDir/thumbs/<md5(uri)>-<size>.jpg`
  and returns `{thumbs:{uri:path}}`; `camThumb({url,id,cacheOnly})` caches DT thumbs in `cacheDir/camthumbs/<id>.jpg` and returns `{path,date}`
  (date = X-REC_DATE_TIME, null when cached); `camDates({items})` HEAD (Range GET fallback) one at a time, emits `date` {id,date}, stops after 3
  failures in a row; `stopDates()`; `saveMedia` takes optional `date` (ms, written as DATE_TAKEN). camThumb and camDates share one 2-slot camera semaphore.
- Finding: `loadThumbnail` on the saved JPEGs returns the camera's embedded 160x120 letterboxed EXIF thumbnail (black bars), so for images
  `thumbs` decodes and scales the file itself (shorter side = size, EXIF rotation applied); videos still use `loadThumbnail`.
- JS (`www/index.html`): merged item list (camera catalog + saved files not in it as `source:"phone"`); permanent `dates` map in localStorage; items without a
  date sort and group by id neighbours' date; day groups with sticky headers (Today / Yesterday / "Mon 3 Nov" / "12 Aug 2025", "Date unknown" if nothing known);
  sort menu (newest/oldest, remembered); All/New/Saved plus All/Photos/Videos segmented controls; search field behind a header icon (filename substring);
  tile images from local thumbs when saved (works offline) else cached camera thumbs via `camThumb` (cache-only when offline); saved items open in the
  viewer from `convertFileSrc(uri)`; viewer header shows date and time; tile `aria-label` ("P1070137.JPG, photo, 3 Oct 2026, saved"), `aria-pressed` while selecting;
  per-filter empty texts; video tiles show duration; background `camDates` after each listing, stopped while a save runs and restarted after.
- Tested on the emulator (PHONE_TARGET=emulator, camera off the whole time, 2 photos already saved from earlier, nothing saved this phase):
  - Install/launch OK (versionCode 6), no AndroidRuntime crash, console clean.
  - Offline (camera unreachable, cached 313-item catalog): saved tiles show local thumbs (`naturalWidth 384x256` after the fix), badge ok; Saved filter shows 2 items;
    tapping a saved tile opens the viewer from `http://localhost/_capacitor_content_/media/external/images/media/49` (naturalWidth 5472), header shows "6 Nov 2026, 12:37 / 10 MB · on your phone · 1 of 2".
  - Date grouping/sort: with synthetic dates set via eval, headers read Today, Yesterday, Wed 4 Nov ... and "31 Aug 2025"; sorted oldest first reverses them and persists in localStorage.
  - Filters: Photos/Videos counts (4 videos), Saved+Videos -> "No videos saved on this phone yet.", search "1070137" -> 1 item with the aria-label above, "zzz" -> `No files match "zzz".`
  - Screenshots in `mobile/tools/out/`: offline light shot-175008.png (Saved filter, local thumbs), viewer shot-175016.png, grouped light shot-175603.png, search shot-175614.png,
    dark (forced, see below) shot-175652.png and sort menu in dark shot-175654.png.
- Problems / notes:
  - The webview never reports `prefers-color-scheme: dark` on the emulator even with `cmd uimode night yes` and a relaunch (app theme is light-based), so dark was checked by
    applying the dark token values to `:root` by eval. Phase 5's theme setting (`data-theme`) should fix this properly.
  - Android BACK does not close the viewer on the emulator (it sends the app to the background; `history.back()` does close it). Same with the original v1.4 page
    (checked by rebuilding with the backup), so it is not a regression; TESTING.md item 7 should be re-checked on the real phone.
  - The unsaved tiles have no thumbnails offline because no camera thumb had been cached yet (the old version loaded them straight into `<img>`); they fill on the first online run.
- Pending (camera was off; needs it on its Wi-Fi waiting screen): online grid with camera thumbs through `camThumb` (cache files appear, tiles load); real `X-REC_DATE_TIME` dates
  via `camThumb`/`camDates` (check `Object.keys(dates).length` rises, headers regroup, HEAD works or the Range GET fallback kicks in); `camDates` stopping while a save runs;
  a one-photo save then `listSaved` showing dateTaken from the camera date (`saveMedia` `date`); TESTING.md items 2-5 (connect, grid fills, save, cancel).
- Left for later: nothing in the Phase 1 scope was dropped. TESTING.md/CLAUDE.md not updated for the new plugin methods (Phase 7).

### Phase 2 (2026-11-06) - done, built and tested on the emulator with the camera on; versionCode 7, versionName "1.6"
- Backups: `mobile/.bak/phase-2/` (index.html, LumixNativePlugin.java, MainActivity.java, build.gradle, styles.xml, AndroidManifest.xml as before Phase 2).
- Native: `exif({uri})`, `exifRemote({url})` (streams only the header, then disconnects; uses the 2-slot camera semaphore),
  `share({uris})` (ACTION_SEND/SEND_MULTIPLE chooser, ClipData + read grant), `openExternal({uri,mime})` (ACTION_VIEW chooser),
  `exitApp()` (moveTaskToBack), and a plugin `OnBackPressedCallback` that emits a `back` event (backgrounds the app if the page has no listener).
  `androidx.exifinterface:exifinterface:1.4.1` added. `AppTheme` parent changed to `Theme.AppCompat.DayNight.DarkActionBar`.
- Camera-flakiness fixes found while doing the pending Phase 1 camera checks: every native camera request now sends `Connection: close`;
  `camThumb` and `exifRemote` retry up to 3 times with 4-5 s timeouts (the camera sometimes stalls one request; before this the grid loaded
  ~1 thumbnail per 20 s on the emulator, after it ~75 in 30 s); `camDates` tolerates 5 failures in a row and sleeps 1 s after each, and JS restarts it every 20 s while dates are missing.
  Listing now has priority: `refreshCatalog` stops `camDates`, pauses the thumbnail queue (`browsing`) and waits for requests in flight, retries the whole
  Browse 3 times, and `loop()` retries a failed listing on later cycles; the "Couldn't list the photos" text clears as soon as a listing succeeds.
  The "unexpected end of stream" error seen in shot-182217 was most likely thumbnail/date traffic competing with Browse plus emulator NAT (after the fix 3 relaunches and 3 refreshes gave no error; a full Browse takes ~30 s on the emulator).
- Saved files' dates: MediaProvider ignores/reset `DATE_TAKEN` set by apps (logcat: "Ignoring mutation of datetaken"; `content query` showed NULL even when set at insert
  and with the IS_PENDING update). Now `saveMedia` stores the camera date in SharedPreferences `taken` (name -> ms) and `listSaved` uses MediaStore's value, else that record,
  else EXIF DateTimeOriginal (cached in the same prefs), else DATE_ADDED. Verified: P1070274 -> camera 21:38 on 31 Oct.
- JS (`www/index.html`): viewer rewritten with pointer events (pinch with focal point, two-finger pan, one-finger pan when zoomed, double-tap 1x/2.5x at the tap point
  with a 0.2 s snap, max 6x, swipe only at 1x, zoom reset on photo change, video controls area ignored); saved photos load the original from `convertFileSrc(uri)`, camera-only show
  the preview plus a "Full size · 7 MB" button with spinner (loads `orig`, remembered for the session); header buttons Details (i), Share, Open in another app (last two only for saved items);
  Details sheet (name, taken, size, dimensions, camera, lens, focal length with 35mm equiv., aperture, shutter, ISO, exposure comp., flash, GPS if present; EXIF "taken" preferred);
  selection bar got a Share button (shares the saved ones, toast "Sharing 2 saved; 1 not saved yet"); Android Back via the native `back` event closes details > viewer > settings > sort menu > search > select mode, else backgrounds the app;
  `history.pushState` hack removed. Viewer background is now solid; icon buttons use `color: var(--text)`.
- Test evidence (emulator, PHONE_TARGET=emulator):
  - Back: viewer + details open -> back closes details (lbIndex 0), back closes viewer (-1), back in select mode exits select, back at top level goes to the launcher.
  - Zoom: synthetic PointerEvents on `#lbStage` (pinch -> s=5, pan, swipe while zoomed keeps the photo, double-tap toggles 1 <-> 2.5; screenshot shot-180456.png at 2.5x);
    real `adb shell input`: two taps at x=700 -> zoom {s:2.5,x:-87}, again -> 1; swipe 900->150 goes 1 of 2 -> 2, back swipe returns.
  - Details/EXIF: saved photo (shot-180326.png: DMC-ZS100, 9.1 mm (25 mm equiv.), f/5.9, 1/2500 s, ISO 125, -2 EV, 5472 x 3648); camera photo through `exifRemote` (shot-181920.png).
  - Share chooser: single (shot-180516.png) and multi-select with an unsaved item (shot-183326.png); Open in -> Photos opened the picture (shot-180520.png), Back returned to the app.
  - Full size: preview 640 px -> full 5472 px in 7 s, spinner shown (shot-181928.png), button disappears.
  - Phase 1 camera checks: online grid fills from `camThumb` (cache files, tiles load), all 313 dates filled from `X-REC_DATE_TIME` (grouped "Sat 31 Oct", "Mon 12 Oct", ...; HEAD works), `camDates` stopped
    during a save (event count frozen at 44 during a save, resumed after), save of one photo and cancel of a 2-photo save leave no pending rows (`content query ... is_pending` all 0), versions: TESTING.md items 2-5 pass.
  - Saved on the emulator in this phase: P1070274, P1070273, P1070272 (JPG) and video P1070001 (1 s). With the earlier 2 that is 5 photos + 1 video total.
- Problems / notes:
  - Dark mode: the Phase 1 diagnosis was wrong. The activity theme was already DayNight (`AppTheme.NoActionBar`); a debug log showed `isLightTheme=false`, night config and `forceDark=AUTO`, and the page still gets `prefers-color-scheme: light`.
    The emulator's WebView is 91 (old); forcing `setForceDark(ON)` made the WebView darken the page itself (washed colours, near-invisible header icons) so it was reverted. Dark layout was checked by applying the dark tokens to `:root`
    (shot-182948.png, icons visible). Real phone WebView is current, so check there; Phase 5's `data-theme` setting makes this independent of the WebView.
  - Video playback of the saved 4K clip fails on the emulator (`MEDIA_ERR_SRC_NOT_SUPPORTED`, duration read as 1.0 s, so the file and uri load fine); this is probably the emulator's codec limit. Re-check on the phone.
  - A saved photo's viewer shows nothing different from the camera one when offline apart from the missing Full size button (by design).
  - exifRemote can take ~25 s while the grid is still pulling thumbnails; consider pausing the grid while the viewer is open (not done).
  - The Open in chooser appeared to open Photos directly on the emulator (only one handler).
- Still pending on the camera / phone: nothing from Phase 1/2 on the camera; real phone only: WebView dark media query, 4K video playback, pinch with real two-finger touch, API 34+ nothing yet (no services in this phase).


### Phase 3 (2026-11-06) - done and tested on the emulator; the camera died (battery 0/3) near the end; versionCode 8, versionName "1.7"
- Backups: `mobile/.bak/phase-3/` (index.html, LumixNativePlugin.java, AndroidManifest.xml, build.gradle as before Phase 3).
- Native (new files `SaveQueue.java` = static queue engine, `SaveService.java` = foreground service):
  - `SaveService`: `foregroundServiceType="dataSync"`, started with `ContextCompat.startForegroundService` and `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)`;
    `onTimeout` (Android 15 six-hour limit) cancels the queue. Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.
    Channel "Saving" (low importance). Notification "Saving 3 of 12 · 41 MB of 70 MB" + current file name, determinate progress (refreshed at most every 0.7 s), Cancel action (`PendingIntent.getForegroundService`), tap opens the app;
    on finish "Saved N files" / "Saved 10, 2 failed" (tap opens the app); nothing after a cancel.
  - `SaveQueue`: fixed pool of `parallel` workers (1-3), `PARTIAL_WAKE_LOCK` + `WifiLock(HIGH_PERF)` for the run, keepalive thread (`getstate` every 5 s, every 1.5 s while waiting; re-pairs when `result` isn't ok; the page's `appId` is passed in `enqueue`),
    camera header phase and keepalive serialised with one lock (the camera garbles overlapping requests), re-binds the process to the Wi-Fi network when it comes back as a new `Network` (needed after a Wi-Fi drop, see below).
    Resume: every file keeps its pending MediaStore row + SharedPreferences `resume` (name -> uri, bytes) after a network failure; the next attempt reads the row's real size and sends `Range: bytes=N-`, requires 206 and a matching `Content-Range`, opens with `"wa"` (a 200 restarts with `"wt"`);
    the size must equal the camera's before `IS_PENDING` is cleared; wrong size / HTTP errors drop the row; the camera date is still written to SharedPreferences `taken`. Cancel deletes every pending row of the run (in `finish()`, before `running` goes false).
    Retries: 3 attempts per file, 2 s then 5 s backoff (I read "2 s, 5 s, 10 s" as the allowed waits and used the first two for three attempts); after a link error (not an HTTP error) it waits up to 60 s for the keepalive to see the camera again (`waiting: true`), and if it doesn't return, that file and all later ones fail ("Lost the camera").
    Connect timeout 10 s, read 30 s.
  - Plugin: `enqueue({items,camIp,appId,folder,parallel})`, `queueState()`, `cancelSave()` (also stops the queue), `freeSpace()` (StatFs on the primary external volume), `requestNotifications()` (Capacitor `@Permission` alias; resolves at once below API 33; saving works if denied); events `queue` (<= 4/s, same shape as `queueState`) and `saved {name,size,uri}`. `saveMedia` and `keepAwake` are still there but unused.
- JS: `sync` mirrors the native snapshot; the loop doesn't poll the camera while a save runs; grid thumbnails and dates pause while saving (they competed with the downloads); `queueState()` on launch and on `visibilitychange`;
  the `saved` event updates the tile (and the open viewer); the viewer's Save button follows the queue; the card shows "N couldn't be saved · Retry · Details" (Details lists all failures, Retry re-enqueues them);
  free-space dialog "Needs 46 MB, 230 MB free. Save the newest 3 files (25 MB) that fit?" with Save what fits / Cancel (200 MB margin, newest first); settings: "Downloads at once" 1/2 and "Save new files automatically when the camera connects" (default off; once per connection after the first good listing, no dialog, saves what fits and toasts);
  the notifications permission is requested the first time a save starts.
- Test evidence (emulator, PHONE_TARGET=emulator, screenshots in `mobile/tools/out/`):
  - Background: 3-photo save, Home, shade in shot-184136.png ("Saving 2 of 3 · 9 MB of 25 MB" with progress), later shot-184202.png ("Saved 3 files · In your gallery, DCIM/Lumix"); reopened: `sync` done 3/3, all 3 saved. shot-185558.png shows both notifications during a later run.
  - Resume after a Wi-Fi drop: `svc wifi disable`, 5 s, `enable` during P1070270 (10 MB): `sync.waiting` true, log `LumixSave: P1070270.JPG attempt 1 failed: Software caused connection abort`, `bound to Wi-Fi network 103`,
    `resume P1070270.JPG from 6112979 of 10009088: HTTP 206 bytes 6112979-10009087/10009088`; final size 10009088 = camera size. My first try of this test failed after the 60 s wait because the process stayed bound to the dead Wi-Fi `Network` (keepalive `ENONET`); the re-bind fixed it.
    Also resumed a kept row across an app reinstall: P1070268 from 7092529 of 10470912, 206.
  - Cancel: a 2-photo save cancelled after ~7 s: `sync.cancelled` true, `ls -a /sdcard/DCIM/Lumix/` has no `.pending` files, notification gone.
  - Retries and failure list: `Native.enqueue` with 4 URLs that 404 (the PC page, parallel 2): each tried 3 times, `failed` lists all 4 ("camera returned HTTP 404"); card "4 files couldn't be saved · Retry · Details" (shot-191420.png), Details dialog lists all four (shot-191426.png).
    Real camera-side retries also happened often (see problems).
  - Free space: `window.getFree=async()=>230e6` -> dialog in shot-191729.png; "Save what fits" returns the newest 3 (P1070267, 269, 271). Settings with the new fields: shot-191738.png.
  - Parallel (4 photos P1070268-271, 36.1 MB, copies deleted between runs, newest files): parallel 1: 60.6 s and 63.6 s; parallel 2: 81.0 s and 76.0 s (every run also had 1-3 failed first attempts).
    Parallel 2 is not faster (slower: ~0.45-0.5 MB/s vs ~0.6 MB/s through the emulator NAT), so the default stays 1; the setting stays for the real phone to try.
  - Saved on the emulator this phase (distinct files): P1070268, 269, 270, 271 (JPG). Total with earlier phases: 9 photos + 1 video, inside the 15 + 2 budget. In the gallery now: P1070268, 270 plus the earlier P1070272-274, P1070137, P1070144, P1070001.MP4.
- Problems / notes:
  - The camera often answers the first request of a file with a truncated reply (`\n not found: size=1 content=0d...`: a lone CR then EOF), taking ~10 s to fail; the retry usually works. Seen with parallel 1 and 2, with thumbnails paused and with the keepalive lock, so it is the camera / emulator NAT, not our contention. One file failed all 3 attempts in the very first run, while grid thumbnails were still loading (thumbnails now pause while saving).
  - After a Wi-Fi drop the process stays bound (`bindProcessToNetwork`) to the old `Network`; `SaveQueue` now re-binds when the Wi-Fi `Network` changes. The page's `bindWifi()` still only runs at `connect()`.
  - The emulator hung (system_server gone, `screencap`/`am` timing out) after one build and had to be killed and cold-booted; its adb then said "unauthorized" because the container's `~/.android/adbkey` had gone, so each connect made a new key and dialog. Fixed with `adb keygen /root/.android/adbkey` in the container and tapping "Always allow" through the host adb. The container key is now persistent.
  - Camera battery went 1/3 -> 0/3 and it stopped answering at about 14:00 (after the resume tests, before the last build). Everything needing real camera traffic was done before that; the rest used the PC page as a fake camera and JS overrides. The final build was only smoke-tested (launch, no crash).
  - `cancelSave` can take up to the connect timeout (10 s) when the camera is unreachable.
- Pending / untested: auto-save on a live connection (code path `connect()` -> `autoSave()`; only `checkSpace(auto)` and the settings toggle were checked; needs the camera on); Cancel via the notification button; the API 33+ notification permission prompt, Android 14+ foreground-service rules and the Android 15 `onTimeout` (emulator is API 31); `getForegroundService` from the Cancel action while backgrounded; the `saved` event updating an open viewer for a camera-only photo mid-download; TESTING.md items 2-3 against the final build once the camera is charged.

### Phase 4 (2026-11-06) - done and tested on the emulator with the camera OFF (battery flat); versionCode 9, versionName "1.8"
- Backups: `mobile/.bak/phase-4/` (index.html, LumixNativePlugin.java (unchanged), AndroidManifest.xml, build.gradle as before Phase 4). Changed: `www/index.html`, the manifest (added `VIBRATE`), `build.gradle`, TESTING.md (item 12). No Java changes.
- Selection (`index.html`):
  - Long-press 450 ms on a tile (pointer events + timer, cancelled if the finger moves >10 px = scroll) enters select mode with that tile selected and `navigator.vibrate(10)`. Drag-select: after the press, `touchmove` is `preventDefault`ed (so the page does not scroll) and the range from the start tile to the tile under the finger (`elementFromPoint`) is selected; a long-press on an already selected tile drags in "remove" mode. Auto-scroll with a rAF loop within 56 px of the header or 130 px of the bottom (up to ~22 px/frame), hit-testing just inside those zones because the selection bar covers the bottom edge. `render()` is deferred while a drag is active (replacing tiles would cancel the touch); `setSelecting`/Select all no longer call `render()` (they update tiles in place). The click that ends a press is swallowed.
  - Day header got a check circle (only while selecting; 24 px with a bigger hit area; `aria-label` "Select all from Sat 31 Oct"; `aria-pressed` follows the selection) that toggles the whole day group. Header min-height stops the layout jumping.
  - Selection bar: "N selected", "Select all 313" (everything matching the filters, not only what is on screen; becomes "Clear" when all are selected; toast "Selected all 313"), "Share N" (saved ones) and "Save N" (unsaved, disabled offline). Two-row layout so it fits a 360 dp phone. Back leaves select mode (already in `handleBack`).
  - Pull-to-refresh: touch events at `scrollY == 0`, indicator circle under the header (arrow flips past 64 px, spinner while refreshing), runs the refresh button handler; not while selecting, viewer open, a dialog open, or on the search input / sort menu / fast thumb.
  - Fast scroll: `#fast` thumb on the right edge appears while scrolling (hides after 2 s), draggable with pointer capture, bubble shows "Aug 2026" for the group under it. Group offsets, document height and track size are measured once per `render()` in a single rAF (not per scroll event); the scroll handler is passive and rAF-throttled and only sets two transforms; the month is found by binary search. Hidden for short lists (<1.5 screens).
- Camera request priority: while the viewer is open (`lbIndex >= 0`) `pumpThumbs`, `startDates` and the 20 s date timer do nothing and `openLb` calls `Native.stopDates()`; `closeLb` resumes both. Requests already in flight finish (<= 3 thumbs). Listing still pauses everything and grid thumbs/dates still wait for saves, so the order is listing > viewer > save > thumbs > dates. No Java change (exifRemote/Full size/preview now only compete with the viewer's own traffic). NOT tested on the camera (off). Offline check: with the viewer open and the grid scrolled, `thumbQueue` = 15 and `thumbActive` = 0 for 6 s; after closing, the queue drained to 0.
- bindWifi: native requests already pick the Wi-Fi `Network` per call (`open()`/`openCam()`) and SaveQueue re-binds the process during saves; the page only needs the process binding for WebView image loads (viewer preview/Full size from the camera). After a Wi-Fi loss the loop goes offline and `connect()` calls `bindWifi()` again, so reconnects already healed; I also call `bindWifi()` each online loop iteration (5 s, cheap) and on `visibilitychange`. Test: `svc wifi disable`, 4 s, `enable`, then `Native.bindWifi()` -> `{wifi:true,bound:true}`.
- Found and fixed: the emulator's WebView 91 has no `color-mix`, and `color-mix(... var(--bg) ...)` computes to transparent there, so the sticky app header and day headers showed photos through. Now a solid `var(--bg)` with the `color-mix` version under `@supports`.
- Test evidence (emulator, PHONE_TARGET=emulator, 313-item cached catalog, camera off; screenshots in `mobile/tools/out/`):
  - Long-press: `input swipe 200 1800 200 1800 700` -> `selecting` true, `sel` = [1070274], bar "1 selected / Select all 313 / Share 1 / Save" (shot-192530.png).
  - Drag-select with auto-scroll: `motionevent DOWN` on the first tile, 0.8 s, MOVE to the bottom zone and hold 2.5 s (`mobile/tools/out/drag1.sh`): 20 tiles selected, indexes 0..19 contiguous, `scrollY` rose to 660 (shot-192721.png). Before the hit-test clamp the range did not extend while the finger sat over the selection bar.
  - Day select: tapping the circle selected 36 (aria-pressed true, "36 selected"), tapping again 0. Select all: 313 selected, label "Clear", every day circle on. Saved filter: "Select all 8", Share 8 opened the system chooser "P1070274.JPG + 7 files" (shot-192836.png); Save stayed disabled (nothing unsaved / camera offline). Back closed the chooser, Back again left select mode.
  - Pull-to-refresh: `input swipe 500 500 500 1150 600` at the top -> indicator with the arrow (shot-192847.png), refresh handler called once, spinner then gone, `refreshing` false again.
  - Fast scroll: DOWN on the thumb then drag down: `scrollY` reached the max 13105, bubble "Aug 2026", class `fast show drag` (shot-193140.png). Two earlier failed attempts were my test (a tap had opened the viewer; the thumb had already hidden, hence the 1.4 -> 2 s delay).
  - Smoothness (rAF frame deltas during 6 flings through 313 items): idle 17.1 ms, flinging avg 22.5 ms, p95 50 ms, max 83 ms, 4 of 317 frames over 50 ms. The emulator renders in software, so that is a pessimistic bound; no per-scroll layout reads.
  - Light and dark (dark by injecting the dark tokens, as the old emulator WebView never reports the media query): select mode with a day selected, bar and thumb: shot-193526.png (light), shot-193528.png (dark); dark at the top shot-193227.png. Both look right.
  - No crashes (`logs` has only the normal "VM exiting" lines from `am`).
- Problems / notes: the header's backdrop blur looks smeared on the emulator (software render). Unsaved tiles are blank offline when no camera thumbnail was cached (as before). The fast-scroll thumb is a touch-only helper (aria-hidden); on gesture navigation the right screen edge can trigger Android's back gesture, the thumb hit area is 44 dp wide so it can still be grabbed.
- Pending (camera off; `curl -m 3 http://10.0.0.220/cam.cgi?mode=getstate` timed out at the end): the request-priority check on the real camera (open a camera-only photo while the grid still loads; Full size and Details should not wait behind grid traffic, compare exifRemote with the earlier ~25 s); Save from a real selection of unsaved photos (drag-select 3 -> "Save 3" -> queue); haptics and real-finger feel on the phone (emulator has no vibrator); TESTING.md items 2-5 against this build once the camera is charged.

### Phase 5 and 5b (2026-11-06) - done and tested on the emulator with the camera OFF (battery still flat); versionCode 10, versionName "1.9"
- Backups: `mobile/.bak/phase-5/` (index.html, LumixNativePlugin.java, SaveQueue.java, MainActivity.java, AndroidManifest.xml, build.gradle, styles.xml, and `app/server.py`, `app/static/index.html`).
  Changed: `www/index.html`, `LumixNativePlugin.java`, `SaveQueue.java` (uses `LumixNativePlugin.wifiNetwork`), manifest (ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, CHANGE_NETWORK_STATE), `build.gradle`, `app/server.py`,
  `app/static/index.html`, TESTING.md (items 13-16), CLAUDE.md (two short bullets). Test helpers in `mobile/tools/out/`: `fakecam.py` (fake camera: port 80 answers `<camrply>`, `busy` gives 503 + :60606), `t5b*.sh`, `joinwatch.sh`, `patch5*.py`.
- Phase 5 native: `appInfo()` (version, sdk, device, `night`), `setSystemBars({color,dark})` (status/nav colour, light icons, and the window background because Capacitor 8 draws edge to edge), `discover({timeout,subnets})`
  (multicast SSDP behind a MulticastLock on a socket bound to the Wi-Fi network, replies kept when LOCATION port is 60606 or SERVER says Panasonic and `getstate` confirms; else a sweep of the /24 of the last known address then the Wi-Fi's own,
  `getstate` on :80, 1.5 s, 32 threads, stops at the first camera, 45 s cap, unicast M-SEARCH to every host as well; accepts `<camrply>` or a 503 from a host that also takes :60606 as "busy"; on 192.168.54.x it returns `hotspot:true` and does nothing),
  `cameraInfo({ip})` (unicast SSDP -> device description -> name, model, firmware), `getCameraWifi/setCameraWifi` (name + password in SharedPreferences `camwifi`), `joinCameraWifi({timeout})` (`WifiNetworkSpecifier`, SSID prefix pattern, WPA2, `requestNetwork` with timeout; onAvailable keeps the `Network`
  as `LumixNativePlugin.camNet` and binds the process to it, `wifi()` and SaveQueue's `openCam` prefer it; onUnavailable rejects; SecurityException is caught), `leaveCameraWifi()`, `joinState()`.
- Phase 5 JS: Settings is a full-screen `<dialog class="sheet">` (Back arrow, Android Back closes it) with Camera (status, address override, Find camera listing results as "Use 10.0.0.x" buttons, auto-join toggle with name prefix + password + Join now), Saving
  (folder: DCIM|Pictures + a validated name `[A-Za-z0-9][A-Za-z0-9 _-]{0,39}`, downloads at once, auto-save), Appearance (System/Light/Dark), About (app version/code, phone, saving folder, camera model/name/firmware via `cameraInfo` when online, address, battery). Every control applies at once.
  Theme: `data-theme` on `<html>` with the dark tokens mirrored in CSS (the media query only covers the instant before the script runs); System follows native `appInfo().night` (polled every 3 s while on System) because the emulator's WebView 91 never reports `prefers-color-scheme`; status/nav bar colours follow.
  Folder: `folder` + `folders` (every folder ever used, always including DCIM/Lumix/) in localStorage; `listSaved({folders})` gets all of them; `enqueue` gets the current one; the card text shows it.
  `connect()` = saved ip, 192.168.54.1, homeIp (as before), then `discoverCamera()` (pill/card "Searching...", at most every 30 s counted from the end of a search, a Refresh press forces it; a found address is saved as `homeIp`/`ip` and used, toast "Found the camera at ...";
  a camera that answers 503 is remembered as busy) then `maybeJoinHotspot()` (toggle on, app visible, no save running, back-off 1, 2, 4... up to 10 min after failures; an approved join then tries 192.168.54.1; the joined network is released after 2 min of the camera being offline or when the toggle goes off).
  The pill says "Searching..." / "Joining Wi-Fi..." (short because the longer text pushed the settings gear off a 393 dp screen); the card carries "Searching for the camera..." and "Joining the camera's Wi-Fi...".
- Phase 5b (`app/server.py`, stdlib): `watch()` calls `search_camera()` when `poll()` fails with "Camera not reachable" (not for busy), a page or copy is active, and the last search was over 60 s ago. Search = multicast SSDP (1.5 s; returns nothing under Docker NAT) then `sweep_subnet` of the configured IP's /24
  (`/cam.cgi?mode=getstate` on :80, 1.5 s, 32 threads, first `<camrply>` = ok, 503 + :60606 open = busy); a hit is written to `data/config.json` and polled at once. The Refresh button also searches. `/api/status` has `camera.searching`; the PC page pill and card show "Searching for the camera..." while it runs.
- Evidence (emulator, PHONE_TARGET=emulator; a fake camera on the Windows host 10.0.0.129:80 stands in for 10.0.0.220):
  - App discovery: wrong ip/homeIp 10.0.0.99 -> pill "Searching..." (shot-195511.png), ~25 s later connected at 10.0.0.129, `homeIp` rewritten, the fake saw getstate + accctrl. With the fake off: discover returned `{method:none, cameras:[]}` after 27 s (508 hosts through the emulator's NAT; a real /24 should take ~12 s); repeats start 38 s apart (sweep + 30 s).
    The `Find camera` button listed "Use 10.0.0.129" (needed the last-known /24 swept first and a 45 s cap: the emulator's Wi-Fi is 10.0.2.x behind NAT, so the page passes the /24 of the last known address).
  - Hotspot join request (the emulator has no camera AP): `joinCameraWifi({timeout:12000})` shows Android's "Connect to device" prompt (shot-195830.png; no SecurityException, so no location permission is needed to ask), rejects with onUnavailable after the timeout, `joinState` back to {joining:false}. Auto path with the toggle on and no camera (5 min, `joinwatch.sh`):
    one attempt, then back-off (`joinFails` 1 -> 2), exactly 2 attempts in 300 s, no loop, our app never crashed. The system's own Settings app crashed once (NPE in `NetworkRequestDialogActivity`; the leftover dialog "Something came up. The application has cancelled the request to choose a device" needs one tap on OK, shot-200453.png) when a request was withdrawn while its dialog was up.
  - Theme: Light (shot-200821.png), Dark (shot-200706.png; bars follow: my first try left light bars with unreadable icons until `setSystemBars` also painted the window background), System with `cmd uimode night yes` -> dark (shot-201007.png) and `night no` -> light (shot-201013.png) within 5 s; `matchMedia` stayed false throughout, confirming why System asks native.
    Settings sheet light (shot-195002.png, shot-201402.png), dark with the join fields (shot-200530.png). The first layout overflowed the viewport by 26 px (`.pair` grid min-content in the old WebView): fixed with `minmax(0, ...)`.
  - Folder: change to Pictures/"Lumix Test" -> `folders` = [DCIM/Lumix/, Pictures/Lumix Test/]; "bad/name" is refused and reverted; the 8 earlier saves still count (`saved.size` 8 -> 9 after a save into the new folder, all 9 badges on). That save (P1070135.JPG, 10 MB, served by the PC page as the source, fake camera for the keepalive) landed in `/sdcard/Pictures/Lumix Test/`, including a 206 resume
    ("from 10251183 of 10251264"; the PC route cut the first try 81 bytes short). Saved this phase: 1 photo (P1070135), within the 2.
  - About: shot-201402.png (1.9 (10), phone, Camera "Not connected"); it showed the stale folder until I made a folder change refresh it (fixed).
  - PC server (restarted with `docker exec -u vscode -w /workspaces/lumix dc-lumix sh app/start.sh`; as root it can't write /tmp/lumix.log, which vscode owns): nothing to find -> searches at 19:59:25, 20:00:32 (every ~60 s, 13.5 s each against the real LAN); on a test instance (:8422) a fake busy camera at 127.0.0.2 -> "found ... via sweep (busy)" and a fake ok camera at 127.0.0.1 -> "found the camera at 127.0.0.1 via sweep (ok)" with config.json updated;
    real server :8420 with camera_ip 10.0.0.99 -> `searching:true` in /api/status, then "found the camera at 10.0.0.129 via sweep (ok) (7.5s)" and config.json saved. After pages stopped polling it logged "idle: releasing the camera" ~30 s later and sent no more requests to the fake. config.json restored to 10.0.0.220 at the end. `node --check` on the PC page script passes.
- Problems / notes:
  - The camera was off the whole phase (`curl -m 3 http://10.0.0.220/cam.cgi?mode=getstate` timed out at start, mid and end), so discovery against the real camera, `cameraInfo` (model/firmware in About), real SSDP multicast, the real hotspot join and the Phase 1-4 pending camera checks are untested.
  - The console shows 3 "Error injecting safe area CSS ... 'style' of null" lines from Capacitor's SystemBars at load (replayed on every attach); layout and insets are right. I did not check whether they predate this phase (applyTheme only calls `setSystemBars` when the colour really changes).
  - The pill shows "Searching..." not the full sentence (space). Real-phone behaviour of the join prompt may differ (Android shows a one-tap approval; with a prefix pattern it lists matching networks).
  - On a real phone a sweep is 254 probes every 30 s while the camera is off and the app is open; raise `DISCOVER_EVERY` if that is too chatty.
- Pending (needs the camera on its Via Network screen, and the phone): discovery with a really changed address (wrong IP in settings, expect 10.0.0.220 in about 15 s; same on the PC page); About camera model/firmware; TESTING.md items 2-5 against this build; the real hotspot join on the phone (SSID prefix, password, system approval, 192.168.54.1 answering, release after the camera is gone, API 34+ behaviour); status/nav bar colours on the phone (gesture nav, API 35 edge to edge); WebView dark media query on the phone.

### Phase 6 (2026-11-06) - done; tested on the emulator with the fake camera and then the real camera (it came back mid-phase); versionCode 11, versionName "1.10"
- Backups: `mobile/.bak/phase-6/` (index.html, LumixNativePlugin.java, SaveQueue.java, SaveService.java, AndroidManifest.xml, build.gradle, TESTING.md, fakecam.py, PROTOCOL.md, PLAN.md as before Phase 6).
- New native: `LocationLog.java` (`filesDir/locations.csv`, "ms,lat,lon,alt,acc", 60-day prune, a fix <60 s and <100 m from the last is skipped), `LocationService.java` (foreground service type `location`, LocationManager GPS+network,
  one request per 2 min and one per 100 m, notification "Recording location for photos" with Stop, seeds with the last known fix; manifest: ACCESS_FINE/COARSE_LOCATION, FOREGROUND_SERVICE_LOCATION).
  Plugin: `locationStatus()`, `setLocationRecording({on})` (asks for the permission via a Capacitor alias, rejects "denied"), `clearLocationLog()`, event `location`; `startLiveView({camIp,port})`/`stopLiveView()`
  (UDP socket bound to the Wi-Fi network, sends `startstream`, re-sends after 5 s without frames, finds `FF D8 FF` within the first 512 bytes, emits only the newest frame as base64 at <=15/s in `frame` events, `liveview` events {state:"frames"|"stalled"}).
  `SaveQueue.tagLocation`: after the size check and before `IS_PENDING` is cleared, ExifInterface on an `"rw"` descriptor, DateTimeOriginal (local) -> nearest fix within +-10 min -> `setLatLong` (+altitude) -> `saveAttributes`; never fails the save.
  Size bookkeeping: SharedPreferences `camsize` (name -> camera size); `listSaved` reports `size` = camera size when the file is the same or up to 64 KB bigger, and `fileSize` = the real size, so `isSaved` keeps working.
- JS: a camera icon in the header (online and not saving) opens a full-screen Remote screen: recmode (waits for `cammode rec`), live view `<img>`, tap-to-focus (maps to 0-1000 over the contained image, focus ring), W/T hold buttons (`wide-normal`/`tele-normal`, `zoomstop` on release, retried), shutter (`capture`, flash + toast),
  Photo/Video switch with `video_recstart/stop` and a timer, battery and shot count; all camera commands are serialised (`camCmd`). Close/Back/app hidden/camera lost = `stopLiveView`, `stopstream`, `playmode` (checked with getstate, up to 3 tries), then the listing refreshes if something was shot. Grid thumbnails/dates/listing pause while it is open, and it refuses to open while a listing runs.
  Settings > Location: toggle (permission prompt), count and time of the latest fix, "Clear location log"; recording is restarted at launch if it was on.
- **Clock sync was left out.** On the real camera `setsetting&type=clock|date_time|datetime|current_time|clockset|date` with ISO or compact local time all answered `err_param` (details in PROTOCOL.md). The Location text tells the user to check the camera's clock on the camera. (I built the button first, with a 3-format fallback tested against the fake, then removed it.)
- Evidence:
  - Location: permission prompt (shot-203151.png); `adb emu geo fix` -> `locationStatus` count 1, log `fix gps 39.9526,-75.1652`; Settings > Location light shot-204223.png, dark shot-204228.png.
  - Tagging with the fake camera (`fakecam.py` now serves a real JPEG with X-REC_DATE_TIME and rewritten EXIF dates, with Range): `P9990001.JPG: tagged 39.95,-75.165 (fix 9 s from the photo)`, `Native.exif` has lat/lon, isSaved true (the emulator NAT cut the first attempts 136 bytes short; the 206 resume finished it).
    With the REAL camera: my one test shot P1070275.JPG saved from the camera: `tagged 51.5074,-0.1278 (fix 299 s from the photo)`, EXIF taken 15:43:41, lat/lon in `exif`, isSaved true. In both cases the file size did not change (the camera's EXIF has room), so the `camsize` growth path is code-reviewed, not exercised.
  - Live view, fake: `adb emu redir add udp:49199:49199`, the fake sends to 127.0.0.1: 68 frames in ~3 s, ~10.8 fps displayed (sender 25 fps), 640 px frames (shot-203544.png). Controls through real `adb input`: tap at y=1100 -> `touch value=499/482`, shutter -> `capture`, hold W 900 ms -> `wide-normal`+`zoomstop`, hold T -> `tele-normal`+`zoomstop`, tap outside the picture -> nothing, Video + shutter twice -> `video_recstart`,`video_recstop` (timer, shot-203614.png), close -> `stopstream`,`playmode`.
  - Live view, REAL camera: needed a host relay because the camera sends to the host's LAN address (`mobile/tools/out/udprelay.py 10.0.0.129`, plus the redir). Frames arrive, 640x432 (shot-203736.png; the diagonal stripes are the camera's own highlight warning). Holding T visibly zooms (shot-203747.png), W returns, `touch 500/500` -> ok, `capture` -> "Photo taken" (**1 test shot used, no video**), close -> getstate `play`.
    Remote screen (dark by design in both themes) shot-204408.png; header with the camera icon in light shot-204221.png.
  - Phase 5 pending checks, now done on the real camera: with ip/homeIp 10.0.0.99 the app was back on 10.0.0.220 within ~12 s via discovery and rewrote homeIp; About shows "DMC-ZS100 (ZS100-248B00)", firmware 1.20, address, battery 2/3 (`cameraInfo` works).
    PC server (app stopped first): POST /api/settings camera_ip 10.0.0.99 -> status `searching` -> `found the camera at 10.0.0.220 via sweep (ok) (12.0s)`, config.json is back to 10.0.0.220, ~60 s later `idle: releasing the camera`. The fake camera was stopped before these.
  - Camera clock vs phone: the shot's `X-REC_DATE_TIME` was 15:43:41 for a shot at ~15:38:56 phone time, so the camera runs ~4m45s ahead. That is why the +-10 min window matters; the app cannot fix it.
- Problems / notes:
  - After the PC-server test the camera was found in `rec` mode (the PC status showed `mode: rec` once and a direct getstate said rec) although my last Remote close had verified `play`. I put it back with `playmode`; final getstate says `play`. I could not tell which client left it in rec; worth watching when Remote is used on the phone. It happened a second time at the very end (all my clients stopped, camera back in `rec` a few minutes later, put back with `playmode`; final getstate `play`), so the camera seems to switch back to rec by itself some time after its client leaves, or when a session times out; nothing in the app sends recmode apart from Remote.
  - A live view stalled once after 12 frames right after an app relaunch (a catalog Browse was probably running); re-sending `startstream` did not help, re-entering recmode did. Guard added: Remote refuses to open while a listing runs and `refreshCatalog` does nothing while Remote is open. Not reproduced afterwards; there is no automatic recovery from a mid-session stall beyond the 5 s `startstream` resend ("Waiting for the picture..." shows).
  - One `zoomstop` timed out (6 s) on the real camera; the stop is now retried (2 more tries, 4 s each). Not re-tested on the camera after that edit.
  - `video_recstart/stop` were only tested against the fake. Tap-to-focus returns ok, but I did not see the focus move (my screenshots came after the ring faded); the `value2=on` semantics (and whether an `off` is needed) are unverified.
  - The emulator NAT truncates big downloads (136 bytes short) from the fake as it sometimes does from the real camera; the Phase 3 retry/resume covers it.
  - Saved on the emulator this phase: P9990001 (fake, deleted again) and P1070275 (the real test shot, left in Pictures/Lumix Test). The emulator app's folder setting is back to DCIM/Lumix/ (the list of used folders still includes Pictures/Lumix Test/). The "Something came up" dialog was not showing. Fake camera and UDP relay stopped; the emulator's `redir` and `geo fix` remain (emulator-side only). Location recording turned off, app stopped, PC server idle.
- Pending (needs the real phone or more camera time): live view over the camera's own hotspot (UDP to 192.168.54.x, no relay), API 34+ location foreground-service rules, location with the screen off, the location notification's Stop button, video recording on the real camera, tap-to-focus visually, the zoomstop retry, TESTING.md items 2-5 against this build.

### Phase 7 (2026-11-06) - done; versionCode 12, versionName "2.0"; tested on the emulator with the real camera (battery 1/3 by the end)
- Backups: `mobile/.bak/phase-7/` (index.html, all Java files, AndroidManifest.xml, build.gradle, app/server.py, CLAUDE.md, PROTOCOL.md, TESTING.md, PLAN.md as before Phase 7).
- **Camera left in rec mode: the camera does it itself.** Observed 4 times: after the app stopped (20-30 s later `rec`), after the PC server went idle, at the start of this phase (no client for hours), and once mid-session (a few seconds after
  the app's own `playmode`, during a save). Pairing (`accctrl`) does not change the mode (paired while `rec`/`play`: unchanged), polling does not, and nothing in the app sends `recmode` except Remote (whose close ends in `playmode`, verified: `play` after closing).
  It returns to `rec` by itself some seconds after its last client leaves. Fix (defensive, `playmode` only): app `ensurePlay` after `tryIp`, in the 5 s loop and at the start/retries of a listing; `SaveQueue.ensurePlay` once per run
  before the first download and on every keepalive; `app/server.py` `Camera.ensure_play` on every poll, before `browse_all`, before a sync, and a retry of a failed listing. Never while Remote is open (`remote.open`/`closing`). Evidence: app launch
  turned `rec` into `play` within ~15 s; `recmode` by curl while the app ran was `play` again 12 s later; save with the camera forced to `rec` logged `LumixSave: camera was in rec mode: sending playmode`; server log
  `camera was in rec mode: sending playmode` after connect and after a search; Remote open for 9 s kept `rec` and 92 live view frames (no interference), close -> `play`.
  Side finding: Browse works in `rec` (HTTP 200); a Browse right after `playmode` once answered HTTP 500 (retried fine), so the post-switch wait is 2 s and both listing paths retry. PROTOCOL.md has a "Camera mode" section.
- **Code review** (fixed): removed dead `saveMedia` (+ its `cancel` flag, `progress` event, unused imports) and `keepAwake` from the plugin (no callers since Phase 3; the old JS save loop and `history.pushState` hack were already gone);
  removed unused CSS (`.errors`, `.lb-nav`, `.okline`, `.warnline`); the hotspot `joinCb`/`joining` are now static so a re-created activity can still release the request (before, a new plugin instance could not unregister the old callback);
  `handleOnDestroy` now also stops date lookups; the "Saved N files" notification said "DCIM/Lumix" whatever the folder (now the real one); manifest: dropped `ACCESS_WIFI_STATE` (nothing reads Wi-Fi info; join prompt still works).
  Kept `CHANGE_WIFI_STATE` (not needed by anything I could find, but WifiNetworkSpecifier flows are documented with it and it is a normal permission) - could be dropped after a test on the phone. Checked and fine: every `addListener` is registered once, timers are cleared
  (`remoteTimer`, `dateTimer`, toast, fast-scroll hide), the two page-lifetime `setInterval`s are intentional, wake/Wi-Fi/multicast locks and the network callbacks are released, LocationService unregisters its listeners.
- Checklist on the emulator (PHONE_TARGET=emulator, camera on; items numbered as in the rewritten TESTING.md): 1-4 pass (2.0 (12), 314 items, 16 thumbnails loaded, no FATAL from our package, console only the known safe-area lines);
  6 dates/grid as before; 8 viewer swipe + Back (viewer closes, next Back backgrounds); 9 long-press -> `selecting`, Select all 314 ("Save 303"), Back leaves; 11-13 one save of P1070269 (9.9 MB, **the 1 photo saved this phase**), Home, shade shows
  "Saving 1 of 1 · 9 MB of 9 MB" (shot-205547.png), Wi-Fi off 5 s -> `resume P1070269.JPG from 9165000 of 9917952: HTTP 206`, file complete; 14 cancel of a 2-photo save: `cancelled`, toast "Stopped", no pending rows;
  15 free-space dialog "Needs 36 MB, 230 MB free. Save the newest 3 files (26 MB) that fit?"; 16 with the fake camera (a pulled JPEG; P9990001 deleted afterwards): `tagged 39.95,-75.165 (fix 3 s from the photo)`, `exif` lat/lon, size = camera size;
  18 Settings/About "2.0 (12)" (shot-210101.png), theme dark/system follows `uimode night` (dark/light within 5 s); 19 wrong ip/homeIp -> back on 10.0.0.220 and `homeIp` rewritten within ~6 s; 20 join prompt "Connect to device"
  (shot-210216.png) then rejection "Couldn't join the camera's Wi-Fi", `joinState` clear (with `ACCESS_WIFI_STATE` removed); 21 PC server: `camera_ip` 10.0.0.99 -> `searching` -> `search: found the camera at 10.0.0.220 via sweep (ok) (12.0s)`, config restored, `idle: releasing the camera` ~35 s after the last poll;
  22 Remote (live view via `adb emu redir` + `udprelay.py`): 92 frames in 9 s, close -> `play` (no shutter used, **no test shots**). Not run: 5 (needs Cameron to switch the camera off its screen), 10 (offline gallery was covered in Phase 1), 17 (auto-save, needs OK to save everything), shutter/video/zoom in 22.
- Docs: `CLAUDE.md` Android section rewritten (methods, events, services, permissions, settings, rec-mode rule, emulator helpers); `PROTOCOL.md` "Camera mode" section; `mobile/tools/TESTING.md` rewritten as one numbered checklist (Basics, Gallery, Saving, Settings and connection, Remote shooting) plus the emulator setup, live view notes and an "Untested on a real phone" list.
- Build: `docker exec -w /workspaces/lumix dc-lumix sh mobile/build.sh` -> `dist/lumix.apk` (7,943,157 bytes, sha256 `98505b8b885d33bc514fc8c7ce5f52d1996f8bcc2155870e74637737c5de3420`); `curl http://10.0.0.129:8420/lumix.apk | sha256sum` is identical; installed on the emulator (versionCode 12). Not installed on Cameron's phone. PC server restarted (`-u vscode`).
- Final state: camera `play` (it will drift back to `rec` on its own), app stopped, fake camera and UDP relay stopped (the emulator's `redir` remains), emulator location recording off and log cleared, emulator app settings back to 10.0.0.220 / DCIM/Lumix.

#### Final summary (v2.0)
- **What shipped (v1.4 -> v2.0):** gallery rebuilt around a merged camera + phone list with real dates (from `X-REC_DATE_TIME`), day headers, filters/search/sort, offline viewing with local thumbnails; viewer with pinch/double-tap zoom, Full size, EXIF details, Share and Open in; Android Back handled natively;
  long-press/drag/day/all selection, pull-to-refresh, fast scroll; background saving in a foreground service with notification, resume by Range, retries, cancel, free-space check, optional auto-save and 1-2 parallel downloads;
  full-screen Settings (camera address override + Find camera, hotspot auto-join, gallery folder, theme System/Light/Dark, About); automatic camera discovery on home Wi-Fi (multicast SSDP + /24 sweep) in the app and on the PC server;
  Remote shooting (live view, shutter, zoom, tap-to-focus, video, battery); optional location log that GPS-tags saved JPEGs; and the rec-mode guard. Tests and notes: TESTING.md, PROTOCOL.md, CLAUDE.md.
- **Left out and why:** setting the camera clock (no command works: every `setsetting` variant answers `err_param`; the Location text tells the user to check the clock on the camera); pushing location to the camera over the protocol (`add_location_data` exists but no command was found, phone-side tagging ships);
  anything that deletes from the camera (out of scope); no dependence on multicast on the PC (blocked by Docker NAT; the sweep covers it).
- **Untested on a real phone:** the camera's own hotspot (join, 192.168.54.1, live view over UDP without a relay), API 33+ notification prompt, Android 14/15 foreground-service rules and `onTimeout`, notification Cancel/Stop while backgrounded, location with the screen off and real GPS, WebView dark media query and system-bar colours on gesture nav / API 35,
  4K video playback, real pinch and haptics, video recording and tap-to-focus on the camera, auto-save on a live connection, whether 2 parallel downloads help, and sweep traffic on a real LAN. Also not run this phase: the offline-banner item (needs the camera switched off by hand).
