# Phone test checklist

Run from the lumix container: `P="sh mobile/tools/phone.sh"`. Setup is in `CLAUDE.md` ("Testing (emulator and phone)").
Commands below assume the emulator (`PHONE_TARGET=emulator`); for the real phone drop it.
The camera (10.0.0.220) only answers while it's on its Wi-Fi "waiting for connection" screen on home Wi-Fi.
**Saving:** a few test saves on the emulator are fine (newest files, never the whole card). Ask Cameron before saving
to his phone or taking test shots. Never uninstall the app. Never delete from the camera.

## Emulator setup (Windows host)

Facts found on the host (2026-11-06; `lumix_s23plus` added 2026-11-08):
- AVD `lumix_s23plus` (default now): Galaxy S23+-like, 1080x2340, **density 400** (the emulator only accepts fixed densities; 400 is the nearest to the S23+'s ~393),
  4 GB RAM, system image `system-images\android-33\google_apis_playstore\x86_64` (Android 13, API 33, Play Store). Stock Google Android, not One UI.
  Created with `avdmanager create avd -n lumix_s23plus -k <image> -d pixel_5`, then `hw.lcd.width/height/density` and `hw.ramSize` set in
  `C:\Users\Cameron\.android\avd\lumix_s23plus.avd\config.ini`. Needs the SDK's `cmdline-tools` (they were not installed by default).
  Screen px = css px x 2.5 here (x 2.75 on the Pixel 5); the status-bar offset below was measured on the Pixel 5 and needs re-measuring.
- AVD `lumix_pixel5`: `C:\Users\Cameron\.android\avd\lumix_pixel5.avd` (+ `lumix_pixel5.ini`). Pixel 5, 1080x2340, 440 dpi,
  3 GB RAM, system image `system-images\android-31\google_apis_playstore\x86_64` (Android 12, API 31, Play Store).
- SDK: `%LOCALAPPDATA%\Android\Sdk` (`emulator\emulator.exe`, `platform-tools\adb.exe`).
- Start it (PowerShell, leave it running; `-no-snapshot-save` = always a clean boot state):
  `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd lumix_s23plus -no-snapshot-save -gpu auto` (or `lumix_pixel5`; one at a time)
- The emulator listens on host loopback: `127.0.0.1`/`::1` port **5554** (console) and **5555** (its adb endpoint; no
  `adb tcpip` needed). The host adb server (port 5037) also lists it as `emulator-5554`.
- The container can't see host loopback directly. Docker Desktop publishes the host as `host.docker.internal`
  (`192.168.65.254`), and connections to it arrive on the host's loopback, so `adb connect 192.168.65.254:5555`
  from the container reaches the emulator's adb port. `phone.sh emulator` does exactly that and stores the target in
  `mobile/tools/.device-emulator`. Accept the "Allow USB debugging?" prompt in the emulator the first time (if adb says
  "unauthorized": `adb keygen /root/.android/adbkey` in the container so the key is persistent, then "Always allow").
- Use it: `docker exec -w /workspaces/lumix -e PHONE_TARGET=emulator dc-lumix sh mobile/tools/phone.sh <cmd>`
  (PowerShell), same commands as for the phone (`install`, `launch`, `shot`, `eval`, ...). Without
  `PHONE_TARGET=emulator` the script targets Cameron's phone. If adb lost the emulator, run `phone.sh emulator` again;
  `adb devices` should show `192.168.65.254:5555 device`. Bash tool on Windows: prefix `export MSYS_NO_PATHCONV=1;`.
- Dark/light: `adb -s 192.168.65.254:5555 shell cmd uimode night yes|no`. Notification shade: `shell cmd statusbar expand-notifications|collapse`
  (collapse it before sending taps/swipes to the app).

What works: the camera in home-Wi-Fi mode. Emulator traffic is NATed through the host's network, so `10.0.0.220` is
reachable like for any PC app. Saving to MediaStore works (the emulator's own storage, not the phone's).

What can't be tested there:
- The camera's own hotspot (`192.168.54.1`): the emulator can't join the camera's Wi-Fi.
- Inbound UDP (live view on port 49199) only works with a redirect and, for the real camera, a host relay: see "Live view on the emulator" below.
- Multicast SSDP (blocked by the container NAT; unicast to the camera works).
- Android 14+ foreground-service rules (the emulator is API 33; the notification-permission prompt can be tested on `lumix_s23plus`, not on the API 31 Pixel 5); OEM
  battery managers. Those need the real phone.
- `bindProcessToNetwork` on a no-internet Wi-Fi: the emulator's simulated Wi-Fi has internet.

## Checklist (v2.0)

`$P eval` runs JS in the app page (globals `cam`, `sync`, `catalog`, `items`, `Native`, ...). Camera rules: one client at a time
(stop the app and the PC page before curl probes, then wait ~20 s); the camera drops back to `rec` mode by itself some seconds after
its last client leaves (the app and the PC server send `playmode` when they see that). End every session with
`curl "http://10.0.0.220/cam.cgi?mode=camcmd&value=playmode"`. Delete test files saved on the emulator afterwards
(`adb shell content delete --uri content://media/external/images/media/<id>`).

### Basics
1. **Installs and loads.** `$P install && $P launch`; `$P status` shows the `versionCode` from `build.gradle`; `$P shot`;
   `$P eval 'typeof window.Capacitor?.registerPlugin'` -> `"function"`.
2. **Camera found and paired.** `$P eval 'document.getElementById("camText").textContent'` -> `ZS100 · battery ...`;
   `$P eval 'JSON.stringify(cam)'` -> `online: true`, `ip: "10.0.0.220"`, empty `error`.
3. **Camera back in playback mode.** With the app stopped for ~30 s, `curl "http://10.0.0.220/cam.cgi?mode=getstate"` shows `<cammode>rec`.
   Launch the app: within ~15 s it shows `<cammode>play`. While the app runs, `curl ...mode=camcmd&value=recmode` is undone by the 5 s loop; during
   a save the log says `LumixSave: camera was in rec mode: sending playmode`; the PC server logs the same in `/tmp/lumix.log`. Nothing is
   switched while the Remote screen is open.
4. **No errors.** `$P logs 400 | grep "FATAL EXCEPTION"` shows nothing for `com.cameron.lumix` (Android's own Settings once crashed on a withdrawn
   Wi-Fi request: not ours); `$P console 5` shows only Capacitor's "Error injecting safe area CSS" lines (harmless). PC page: `node --check` on its script.
5. **Offline banner (needs Cameron).** He takes the camera off its Wi-Fi screen: within ~10 s `cam.online` is false, the pill reads
   "Camera offline" (or "Searching..."), `#offlineHelp` is visible; put it back and it reconnects on its own.

### Gallery
6. **Grid and dates.** `document.querySelectorAll(".tile img.loaded").length` rises toward the visible tile count; day headers read Today /
   Yesterday / "Sat 31 Oct"; `Object.keys(dates).length` reaches `catalog.length` (dates come from `X-REC_DATE_TIME`); `catalog.length` matches the camera's count.
7. **Filters, search, sort.** All/New/Saved and All/Photos/Videos combine; search "1070" narrows by name; "zzz" shows `No files match "zzz".`;
   Oldest first reverses and is remembered.
8. **Viewer.** `openLb(0)`: the preview shows; a swipe (`adb shell input swipe 800 1200 200 1200 150`) moves `lbIndex`; double-tap / pinch zoom;
   Details (i) shows EXIF (camera photos through `exifRemote`, saved ones locally); Full size loads the original; `$P back` closes details, then the
   viewer, then (at top level) sends the app to the background. Share and Open in show the Android chooser for saved photos.
9. **Selection and browsing.** Long-press a tile (`adb shell input swipe x y x y 700`; Pixel 5: screen y = 145 + css y * 2.75; `lumix_s23plus`: css y * 2.5 plus a status-bar offset to measure) -> `selecting` true, that tile in
   `sel`. Drag-select (`input motionevent DOWN/MOVE/UP` from a script pushed to /data/local/tmp) selects a contiguous range and scrolls near the edges.
   The day header circle selects a day; "Select all N" selects `visIds.length`; Save counts unsaved, Share counts saved; Back leaves select mode.
   Pull-to-refresh at scroll top (`input swipe 500 500 500 1150 600`) runs the refresh once, not while selecting or in the viewer. Fast scroll: drag
   `#fastThumb`, the bubble shows the month. With the viewer open `thumbQueue` fills but `thumbActive` stays 0.
10. **Offline gallery.** Camera off (or a wrong address): saved items show local thumbnails and open from `content://`; unsaved ones show cached camera thumbnails.

### Saving
11. **Save one photo (ask Cameron first).** `saveItems([byId.get("<id>")])`; `$P eval 'JSON.stringify(sync)'` shows progress and the log has `LumixSave` lines;
    then `Native.listSaved({folders: allFolders()})` lists it and the tile's badge turns to a check.
12. **Background save and notification.** During a save press Home (`adb shell input keyevent 3`), `adb shell cmd statusbar expand-notifications`, `$P shot`:
    "Saving 1 of 1 · 9 MB of 9 MB" with progress, then "Saved 1 file" ("In your gallery, <folder>"). Collapse the shade, reopen the app: `sync` shows the
    finished state. (The API 33+ notification permission prompt can't be tested on the API 31 Pixel 5 emulator; use `lumix_s23plus`.)
13. **Resume.** During a ~10 MB photo: `adb shell svc wifi disable`, 5 s, `svc wifi enable`. `sync.waiting` goes true; the log has
    `resume P... from N of M: HTTP 206` and the file ends at the camera's size. (Items 11-13 can be one save.)
14. **Cancel.** Start a 2-photo save, tap Cancel: `sync.running` false, `sync.cancelled` true, toast "Stopped", no `.pending` files
    (`adb shell ls -a /sdcard/DCIM/Lumix/`) and no `is_pending=1` rows.
15. **Failures and space.** `Native.enqueue` with 404 URLs: the card shows "N files couldn't be saved · Retry · Details" and Details lists every file. Free-space dialog:
    `window.getFree=async()=>230e6` then `saveItems(items.filter(i=>!isSaved(i)).slice(0,4))` -> "Needs ..., 230 MB free. Save the newest 3 files ..."; Cancel starts nothing.
16. **Location tagging.** Settings > Location on (permission prompt, "While using the app"), `adb -s emulator-5554 emu geo fix <lon> <lat>` (host adb),
    `Native.locationStatus()` count rises, notification "Recording location for photos" (with Stop). Save a JPEG whose DateTimeOriginal is within 10 min of a fix
    (the fake camera below if you don't want a real save): `LumixSave: P....JPG: tagged <lat>,<lon>`, `Native.exif({uri})` has `lat`/`lon`, `isSaved` stays true
    (`listSaved`: `size` = the camera's size, `fileSize` = the real one). `Native.clearLocationLog()` empties the log; turn recording off afterwards.
17. **Auto-save (needs Cameron).** Settings toggle on, relaunch with unsaved files: one save of the not-yet-saved files starts after the first good listing
    (code path `tryIp` -> `autoSave`).

### Settings and connection
18. **Settings, theme, folder, About.** `settingsBtn.click()`: full-screen sheet (Camera, Saving, Location, Appearance, About). Theme System/Light/Dark changes
    `document.documentElement.dataset.theme` and the screenshot; System follows `adb shell cmd uimode night yes|no` within ~5 s (the emulator's old WebView never reports
    `prefers-color-scheme`, so the page asks native `appInfo().night`). Folder: a new name gives `folders` = [old, new] and earlier saves still count as saved; "bad/name"
    is refused. About shows "2.0 (12)", phone, saving folder, camera model/firmware when online.
19. **Finding the camera.** Home Wi-Fi, camera on. `store.set("ip","10.0.0.99"); store.set("homeIp","10.0.0.99"); cam.online=false; lastDiscover=0`: it is back on
    10.0.0.220 within ~15 s and `homeIp` is rewritten. `Native.discover({timeout:1500, subnets:["10.0.0."]})` -> `{cameras, method, ms}`. A full sweep takes ~27 s on the
    emulator (NAT; its Wi-Fi is 10.0.2.x, so the page passes the last known /24), ~12 s on a real LAN. Searching repeats at most every 30 s.
20. **Camera hotspot join (the real join is phone only).** Emulator: `Native.setCameraWifi({ssid:"ZS100-",password:"12345678"})`, `Native.joinCameraWifi({timeout:8000})` shows
    Android's "Connect to device" prompt and rejects after the timeout; `Native.joinState()` -> `{joining:false,joined:false}`; with the toggle on, `joinFails` backs off 1, 2, 4... min.
    Clear the password afterwards (`setCameraWifi({ssid:"ZS100-",password:""})`). Phone: camera Wi-Fi > New connection > Remote Shooting & View > Direct, toggle on, name `ZS100-`
    + the password from the camera screen, Join now, approve, then 192.168.54.1 answers.
21. **PC server finds the camera.** `docker exec -u vscode -w /workspaces/lumix dc-lumix sh app/start.sh` (as root the /tmp/lumix.log write is refused). With the app stopped, POST
    `{"camera_ip":"10.0.0.99"}` to `http://10.0.0.129:8420/api/settings`: `/api/status` shows `searching`, `/tmp/lumix.log` has `search: found the camera at 10.0.0.220 via sweep`, and
    `data/config.json` is back to 10.0.0.220. When the camera was in rec: `camera was in rec mode: sending playmode`. With no page polling `/api/status` it logs
    `idle: releasing the camera` ~30 s later.

### Remote shooting
22. **Remote screen.** Camera online, not saving: `remoteBtn` is visible. `remoteBtn.click()` (not while a listing runs): the camera goes to rec, `remote.frames` rises (on the emulator
    set up the live view as below), `$P shot`. Hold W/T (`input swipe x y x y 1500`) zooms and sends `zoomstop`; tapping the picture sends `camctrl&type=touch&value=x/y&value2=on`;
    the shutter sends `camcmd&value=capture` (**uses a real shot: ask first**); Video + shutter = `video_recstart`/`video_recstop` with a timer. Close (X or Back) = `stopstream` + `playmode`:
    `camCmd("mode=getstate")` shows `<cammode>play`. The 5 s connection loop must not switch the camera to play while Remote is open (frames keep coming).
23. **Fake camera (to avoid real shots or a real camera).** On the Windows host: `FAKE_PHOTO=photo.jpg FAKE_FRAMES=<dir of *.jpg> FAKE_CLOCK=compact python mobile/tools/fakecam.py 0.0.0.0 ok 80 x`
    (`photo.jpg` = any JPEG with EXIF, e.g. pulled from the emulator; it answers recmode/playmode/capture/zoom/touch/video/startstream, serves `/DO<n>.JPG?t=<local time>` with
    X-REC_DATE_TIME and rewritten EXIF dates, supports Range, `/_stats` lists the commands it saw; `busy` instead of `ok` answers 503). Point the app at it
    (`store.set("ip","10.0.0.129"); store.set("homeIp","10.0.0.129"); cam.online=false`) and save through the real queue:
    `Native.enqueue({items:[{url:"http://10.0.0.129/DO1.JPG?t=<now>",name:"P9990001.JPG",size:<bytes>,mime:"image/jpeg",date:Date.now()}],camIp:"10.0.0.129",appId,folder:curFolder(),parallel:1})`.
    Delete the test file afterwards, stop the fake before testing discovery, put `ip`/`homeIp` back to 10.0.0.220.

## Live view on the emulator
The camera (or the fake) sends UDP to the host's LAN address, but the emulator only sees what its redirect forwards from host loopback. What worked:
1. `adb -s emulator-5554 emu redir add udp:49199:49199` (host adb; `redir list` to check). Gone when the emulator restarts.
2. Real camera: `python mobile/tools/udprelay.py 10.0.0.129` on the host copies datagrams from `<LAN ip>:49199` to `127.0.0.1:49199`
   (the LAN IP is where the emulator's NAT shows up as the requester; the camera replies to it). Stop the relay afterwards.
   Fake camera: `FAKE_UDP` defaults to 127.0.0.1, so no relay is needed.
3. The app's socket is bound to the Wi-Fi network and receives the redirected packets. Measured ~11 fps displayed in the emulator (software rendering).
A live view that stalls after startup: close and reopen Remote (re-sending `startstream`, with or without `stopstream`, did not revive it once).

## README screenshots and demo reel
How `docs/media/` was made (2026-11-09, `lumix_s23plus`, real camera on home Wi-Fi). Takes ~20 min; the camera must stay on its
Wi-Fi waiting screen the whole time (turn off its sleep mode). Raw captures go to `mobile/tools/out/raw/` (scratch).
`A` below is `adb -s 192.168.65.254:5555 shell`. In zsh, wrap commands in functions or a `bash` script: `A="adb ... shell"; $A ...` fails because zsh doesn't split words.

1. **Setup.** Emulator running, `phone.sh emulator`, `install`, `launch`; live view redirect and `udprelay.py` on the host (section above).
   ffmpeg isn't in the image: `pip install --user imageio-ffmpeg`, then `ln -s $(python3 -c "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())") ~/.local/bin/ffmpeg`.
   Wait ~1 min after launch for dates and thumbnails. Thumbnails that failed stay blank; retry them with
   `$P eval 'document.querySelectorAll(".tile img").forEach(i=>{if(!i.dataset.loaded){delete i.dataset.tries;delete i.dataset.miss;delete i.dataset.queued;observer.unobserve(i);observer.observe(i)}})'`.
2. **Clean status bar** (Android demo mode, clock 10:00, no icons):
   `A settings put global sysui_demo_allowed 1`, then `A am broadcast -a com.android.systemui.demo -e command <cmd>` with `enter`,
   `clock -e hhmm 1000`, `notifications -e visible false`, `battery -e level 100 -e plugged false`, `network -e wifi show -e level 4 -e mobile hide`.
   Afterwards `-e command exit`.
3. **Theme.** `A cmd uimode night yes|no`, then `$P eval 'refreshNight()'` (theme "System" follows it; the shade and status bar too). Shoot everything once per mode.
4. **Screens** (drive with `$P eval`, wait, `$P shot <file>`):
   - Gallery: `scrollTo(0,0)`.
   - Viewer: `openLb(1)` (wait ~6 s for the image).
   - Details: `showInfo(true)`; wait ~10 s or it shows "Reading details…" (EXIF is fetched from the camera; cached afterwards).
   - Selection: `showInfo(false); closeLb(); setSelecting(true); visible().slice(0,4).forEach(i=>sel.add(i.id)); refreshTiles(); updateSel()`, then `setSelecting(false)`.
   - Remote: `openRemote()`, wait ~12 s; `remote.frames` should be climbing. `closeRemote()` afterwards. Remote is always dark; only the status bar changes.
5. **Save notification.** Uses the 2 newest photos only, deleted again afterwards.
   - `A pm grant com.cameron.lumix android.permission.POST_NOTIFICATIONS` (no prompt in the shot).
   - Hide other apps' notifications for an hour: list keys with `A dumpsys notification --noredact | grep -oE "key=0\|[^ ]+\|[0-9]+$"`, then
     `A "cmd notification snooze --for 3600000 '<key>'"` for each non-Lumix key.
   - Before each save, clear Lumix's previous "Saved" notification (otherwise the two group and the progress bar is collapsed): expand the shade,
     `A uiautomator dump /sdcard/u.xml`, tap the centre of the `text="Clear all"` bounds, collapse.
   - If the files are already saved, delete them first (below) and `$P eval 'refreshSaved()'`.
   - `$P eval 'saveItems(visible().slice(0,2))'`, wait 3 s, `A cmd statusbar expand-notifications`, wait 2 s, shoot (catches "Saving 1 of 2" with
     its bar), wait ~18 s for the save to finish, `A cmd statusbar collapse`.
6. **Demo reel** (light mode, ~35 s). Warm the EXIF cache for the photo the reel opens Details on (`openLb(2); showInfo(true)`, wait 12 s, close).
   Start `adb -s 192.168.65.254:5555 shell screenrecord --time-limit 38 --bit-rate 8000000 /sdcard/demo.mp4 &`, then with `A input` (screen px):
   swipe up twice through the gallery, swipe down 3x fast (pull-to-refresh), tap a tile (540 1065), swipe left to the next photo,
   tap Details (888 148), back twice, long-press a tile (`swipe 203 1065 203 1065 900`) and tap 3 more, back, tap the camera icon (657 138),
   wait 7 s for live view, back. `wait` for screenrecord, then `adb pull /sdcard/demo.mp4`.
7. **Encode into `docs/media/`:**
   - Screenshots: `ffmpeg -i <raw>.png -vf scale=540:-1:flags=lanczos -q:v 3 docs/media/<screen>-<mode>.jpg` (35-120 KB each).
   - MP4: `ffmpeg -ss 1.5 -t 33 -i demo.mp4 -vf "fps=30,scale=540:-2:flags=lanczos" -c:v libx264 -crf 26 -preset slow -pix_fmt yuv420p -movflags +faststart -an docs/media/demo.mp4` (~2 MB).
   - GIF for the README (GitHub doesn't play repo-path videos inline): `ffmpeg -ss 1.5 -t 33 -i demo.mp4 -vf "fps=10,scale=300:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" docs/media/demo.gif` (~3.7 MB).
   - Check them with a contact sheet before committing: `ffmpeg -i demo.mp4 -vf "fps=1/2,scale=180:-1,tile=10x2" sheet.png`.
8. **Cleanup.** Delete the test saves:
   `A "content query --uri content://media/external/images/media --projection _id --where \"relative_path LIKE 'DCIM/Lumix%'\""`, then
   `A content delete --uri content://media/external/images/media/<id>` for each. Then: demo mode `exit`, `A cmd uimode night no`, `$P stop`,
   `curl "http://10.0.0.220/cam.cgi?mode=camcmd&value=playmode"`, stop the relay. Snoozed notifications come back by themselves.

## Untested on a real phone
Everything above ran on the API 31 Pixel 5 emulator (the API 33 `lumix_s23plus` was added later and not yet run through the checklist) (plus the real camera over home Wi-Fi). Not verified on Cameron's phone:
- The camera's own hotspot: live view over UDP to 192.168.54.x without a relay, saving and the Remote screen on `192.168.54.1`, and the real `joinCameraWifi` (SSID prefix, password, approval, release when the camera goes away).
- API 33+ notification permission prompt; Android 14+ foreground-service rules (`dataSync`, `location`) and the Android 15 `onTimeout`; OEM battery managers; the notification Cancel / Stop buttons while the app is in the background.
- Location with the screen off and real GPS fixes (the camera's clock is a few minutes ahead of the phone; the +-10 min window absorbs it, and the app cannot set the clock: no command found).
- The WebView's `prefers-color-scheme` (emulator WebView 91 never reports it), status/navigation bar colours on gesture navigation / API 35 edge to edge, 4K video playback in the viewer, real two-finger pinch, haptics.
- Video recording and tap-to-focus (`value2=on`) on the real camera; auto-save on a live connection; whether 2 downloads at once is faster (it was slower on the emulator).
- A discovery sweep (254 probes every 30 s while the camera is off and the app is open) on a real LAN.
