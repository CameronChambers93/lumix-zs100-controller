# Lumix ZS100 Wi-Fi protocol (verified 2026-11-06)

Camera: DMC-ZS100 (reports model TZ100, firmware D2.90, comm_proto_ver 1.6), joined to the home Wi-Fi
"Via Network" at `10.0.0.220`. Its ports only open while the camera is on the Wi-Fi "waiting for connection"
screen; otherwise it drops packets silently.

## Control (port 80)
- Pair: `GET /cam.cgi?mode=accctrl&type=req_acc&value=<uuid>&value2=<app name>` → `ok,ZS100-248B00,remote,encrypted`
- State / keepalive (poll every ~5 s): `GET /cam.cgi?mode=getstate` → XML (batt, cammode, sd status)
- Capabilities: `GET /cam.cgi?mode=getinfo&type=capability`

## Media (UPnP / DLNA)
- SSDP M-SEARCH (unicast to camera:1900 works) → `LOCATION: http://<cam>:60606/A0CC2B248B00/Server0/ddd`
- ContentDirectory control: `POST http://<cam>:60606/Server0/CDS_control`, SOAPACTION
  `urn:schemas-upnp-org:service:ContentDirectory:1#Browse`, ObjectID `0`, BrowseDirectChildren,
  paged with StartingIndex/RequestedCount; `TotalMatches` gives the count (313 at time of test).
- Each item has three `res` URLs on port 50001:
  - `DO<id>.JPG` original (~5–6 MB), `DS<id>.JPG` screen-size (~100 KB), `DT<id>.JPG` thumbnail (~5 KB)
- Download speed measured ~1.2 MB/s (4.9 MB in 4.2 s).

## Gotchas
- **One app at a time.** While one client is connected, others get HTTP 503 ("Service Temporarily Overloaded")
  or `<result>err_already_connected</result>`. A client that stops polling is dropped after roughly 10–20 s.
- Thumbnails (`DT`) are 160x120 with 3:2 photos letterboxed (black bars); previews (`DS`) are 640x424, no bars.
- A browser page can't call the camera directly (no CORS headers) → needs a small server to proxy/sync.
- zsh: don't name a shell variable `path` (it's tied to `PATH`).

## Phase 0 probes (2026-11-06, all from the dev container, paired, camera firmware D2.90)

- **Browse has no date.** No `dc:date` (or any date) in `Browse` results, even with `Filter=dc:date,...` or
  `BrowseMetadata`. An item is just:
  `<item id="1060871" parentID="0" restricted="0"><dc:title>106-0871</dc:title><upnp:writeStatus>WRITABLE</upnp:writeStatus><upnp:class name="imageItem">object.item.imageItem</upnp:class><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00900000000000000000000000000000;PANASONIC.COM_PN=CAM_ORG" size="4894720">http://10.0.0.220:50001/DO1060871.JPG</res>` plus the `DT`/`DS` `res` entries.
  Videos are `<upnp:class name="videoItem">object.item.videoItem.movie</upnp:class>`, `video/mp4`, with
  `duration="0:00:01"` (h:mm:ss) on the original `res`; the MP4 file is `DO<id>.MP4`, thumbnail/preview are JPEGs.
  The item id is dir+number (`1070274` = file `P1070274.JPG`). Browse returns at most 50 items per call
  (`RequestedCount=100` -> `NumberReturned=50`), page with `StartingIndex`.
- **Where the date is: the `X-REC_DATE_TIME` response header** on every GET/HEAD of `DO`, `DS`, `DT` (and MP4):
  `X-REC_DATE_TIME: 2026-10-31T21:38:14` = ISO 8601 local time **without a timezone** (camera clock, treat as local
  wall time). Other headers: `X-FILE_SIZE`, `X-ROTATE_INFO`. A tiny request is enough, e.g.
  `curl -r 0-0 -D - -o /dev/null http://10.0.0.220:50001/DT<id>.JPG`. EXIF `DateTimeOriginal` in the file
  matches (`2026:10:31 21:38:14`, found in the first 64 KB). One request per item, so fetching dates for 300+ items
  costs 300 small requests (do it lazily / cache).
- **Range works.** `Accept-Ranges: bytes`; `-r 100-199` on `DO1070274.JPG` and on `DO1070001.MP4` both return
  `206 Partial Content` with correct `Content-Range: bytes 100-199/<total>`; open-ended `-r N-` works too (tail of
  the file returned). A `-r 0-0` on `DT` returns 206 as well. So resume with `Range: bytes=N-` is possible.
- **Capability** (`getinfo&type=capability`, 8 KB): `comm_proto_ver 1.6`, model `TZ100`; camcmds
  `tele-normal, tele-fast, wide-normal, wide-fast, 4kphoto_marking`; camspecs `touch_command_auto,
  oneshot_af_enable, setting_play_sort_mode_enable, af_size_change_pinch, zoombar_with_partition_focaldist,
  lens_detach_disable, normal_disp_with_zoom_operation_enable, long_expo_disp_T, geotag_add_enable,
  peaking_flag_use_enable, menu_notify_enable, copy_assist_enable`; `contents_action_info` lists
  `copy_to_sp`/`upload`/`playback`/`delete` per content type (delete is listed as enabled; we never use it).
  `geotag_add_enable` + `<add_location_data>off</add_location_data>` in `getstate` suggest the camera can accept
  location from the phone app via a command that is not documented here (not probed; phone-side tagging is the plan).
  `getinfo&type=allmenu` (161 KB) and `type=curmenu` (19 KB) both answer (menus: liveview quality, exposure, AF,
  filters, burst, ...) but contain no clock/date/time-zone item.
- **Clock read: nothing answers.** `getsetting&type=clock` -> `err_non_support`; `current_time`, `datetime`, `time`,
  `date`, `clockset`, `date_time`, `system_time`, `time_zone`, `timezone`, `world_time`, `clock_set`, `summer_time`
  -> `err_param`; `getinfo&type=clock|current_time|datetime|time|date` -> `err_param`. `getstate` has no time and
  `cam.cgi` responses have no `Date` header (SSDP `DATE:` says 2012-02-13, a fixed fake). The only camera-clock
  evidence is the date of new shots (`X-REC_DATE_TIME`). `getsetting&type=clock` being `err_non_support` (not
  `err_param`) means "clock" is a known type this model lacks, so a `setsetting&type=clock` is unlikely to work
  either (Phase 6 tried: `err_param` for every variant, see below; no clock command exists).
- **SSDP.** Multicast `M-SEARCH` to 239.255.255.250:1900 from the container: 0 replies (Docker NAT), for both
  `urn:schemas-upnp-org:device:MediaServer:1` and `ssdp:all`. Unicast to `10.0.0.220:1900`: replies
  (1 for MediaServer, 5 for ssdp:all: rootdevice, uuid, MediaServer, ContentDirectory, ConnectionManager), all with
  `LOCATION: http://10.0.0.220:60606/A0CC2B248B00/Server0/ddd`, `SERVER: Linux/4.0 UPnP/1.0 Panasonic-UPnP-MW/1.0`.
  Device description: friendlyName `ZS100-248B00`, manufacturer Panasonic, modelNumber `DMC-ZS100`,
  `X_AdditionalFunction CPRemoteView`, `X_FirmVersion 1.20`. So discovery has to match on LOCATION port 60606 /
  `Panasonic` / the `ddd` path.
- **Record mode.** `mode=camcmd&value=recmode` -> `ok`; `getstate` then shows `<cammode>rec</cammode>` (2 s later).
  `mode=startstream&value=49199` -> `ok`, but a UDP listener on 49199 in the container received 0 packets in 4 s
  (inbound UDP doesn't traverse Docker NAT; the camera may well be sending, untested). `mode=stopstream` -> `ok`.
  `mode=camcmd&value=playmode` -> `ok`, `cammode` back to `play`. Camera was left in playmode.

## Phase 6 probes (2026-11-06, camera firmware D2.90, from the Android app on the emulator)
- **Live view works.** `mode=camcmd&value=recmode` then `mode=startstream&value=49199`: the camera sends UDP datagrams (23-29 KB, one 640x432 JPEG each, a short
  header before `FF D8 FF`) from camera port ~65430 to the requester's address, ~15-25 per second. `stopstream` ends it, `playmode` returns. In one session the
  stream stopped after 12 frames (a catalog Browse was probably running) and `startstream` again (with or without `stopstream`) answered ok but sent nothing; closing and re-entering recmode fixed it.
- Commands that answer `<result>ok</result>`: `camcmd&value=capture`, `tele-normal`, `wide-normal`, `zoomstop` (zoom visibly changes the live view),
  `camctrl&type=touch&value=500/500&value2=on`. `video_recstart/video_recstop` were not tried on the real camera.
- `getstate` in rec mode also has `remaincapacity` (shots left), `video_remaincapacity`, `rec` (off/on), `temperature`, `lens`, `add_location_data` (off), `version`.
- **Clock: no command found.** `mode=setsetting&type=<clock|date_time|datetime|current_time|clockset|date>&value=<2026-11-06T15:38:27 or 20261106153827>` -> `err_param`
  for every combination; the clock can only be set on the camera. A shot taken at phone time ~15:38:56 had `X-REC_DATE_TIME: 2026-11-06T15:43:41` (camera clock ~4m45s ahead).
- `add_location_data` exists in `getstate` (always `off`), `geotag_add_enable` in capability; no command to push a location was probed. Location is tagged phone-side.

## Camera mode (rec vs play), Phase 7
- `getstate` `<cammode>` is `play` (playback; what media access wants) or `rec` (shooting / live view). `camcmd&value=recmode` / `playmode` switch it (`ok`, takes ~1-2 s).
- **The camera returns to `rec` by itself** some seconds after its last client leaves (seen every time: app stopped, ~20-30 s later `rec`; pairing alone does not do it, and it stays `rec` when
  left alone). It can also flip back during a session. Remote shooting is the only thing in the app that asks for `rec`.
- A `getstate` without pairing answers `ok` too. Browse (HTTP 200) also works in `rec`; a Browse sent right after `playmode` once answered HTTP 500 and succeeded on retry.
- So the app (`ensurePlay`: connect, 5 s loop, listing retries), `SaveQueue` (once per run + every keepalive) and `app/server.py` (`Camera.ensure_play` on every poll, before a listing and a sync)
  send `camcmd&value=playmode` whenever `getstate` shows anything but `play`, wait ~2 s, and never do it while the app's Remote screen is open. Remote's close sends `stopstream` + `playmode`.
- Other commands used by the app: `startstream&value=<udp port>` / `stopstream` (live view), `camcmd&value=capture|tele-normal|wide-normal|zoomstop|video_recstart|video_recstop`,
  `camctrl&type=touch&value=<x>/<y>&value2=on` (0-1000 coordinates). Never sent: anything that deletes or changes camera settings.
