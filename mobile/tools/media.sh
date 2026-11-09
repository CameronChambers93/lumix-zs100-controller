#!/bin/bash
# Make the README screenshots and demo reel (docs/media/) on the emulator with the real camera. Run in the lumix container:
#   bash mobile/tools/media.sh [step...]      steps: setup shots notify reel encode cleanup (default: all, in that order)
# Before: emulator running (`phone.sh emulator` done once), camera on its Wi-Fi waiting screen with sleep mode off, and for
# live view the redirect + udprelay.py on the host (TESTING.md, "Live view on the emulator"). Takes ~5 min.
# Saves the 2 newest photos on the emulator a few times for the notification shots and deletes them again in `cleanup`.
# Raw captures: mobile/tools/out/raw/ (scratch). Steps and timings explained in TESTING.md, "README screenshots and demo reel".
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
RAW="$HERE/out/raw"
MEDIA=${MEDIA:-$ROOT/docs/media}
DEV=192.168.65.254:5555
CAM=${LUMIX_CAMERA:-10.0.0.220}
export PATH="$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools:$HOME/.local/bin" PHONE_TARGET=emulator
MODES=${MODES:-light dark}

A() { adb -s "$DEV" shell "$@"; }
P() { sh "$HERE/phone.sh" "$@"; }
js() { P eval "$1" >/dev/null; }
demo() { A am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
theme() {   # light|dark: system dark mode, then make the app re-read it
  if [ "$1" = dark ]; then A cmd uimode night yes >/dev/null; else A cmd uimode night no >/dev/null; fi
  sleep 5; js 'refreshNight(); scrollTo(0,0)'; sleep 2
}
shot() { P shot "$RAW/$1.png"; }
saved_ids() {
  A "content query --uri content://media/external/images/media --projection _id --where \"relative_path LIKE 'DCIM/Lumix%'\"" \
    | grep -oE "_id=[0-9]+" | cut -d= -f2 || true
}
unsave() {   # delete everything in DCIM/Lumix on the emulator (test saves only: the emulator holds nothing else there)
  for i in $(saved_ids); do A content delete --uri "content://media/external/images/media/$i"; done
  sleep 1; P eval 'refreshSaved().then(()=>"ok")' >/dev/null || true
}
clear_shade() {   # tap "Clear all" so an old "Saved" notification doesn't group with the new progress one
  A cmd statusbar expand-notifications; sleep 2
  A uiautomator dump /sdcard/u.xml >/dev/null
  b=$(A cat /sdcard/u.xml | grep -oE 'text="Clear all"[^>]*bounds="[^"]*"' | grep -oE 'bounds="[^"]*"' | tr -c '0-9\n' ' ' || true)
  if [ -n "$b" ]; then set -- $b; A input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); fi
  sleep 1; A cmd statusbar collapse
}
ffmpeg_bin() {
  command -v ffmpeg >/dev/null && return
  python3 -c "import imageio_ffmpeg" 2>/dev/null || pip install --user -q imageio-ffmpeg
  mkdir -p "$HOME/.local/bin"
  ln -sf "$(python3 -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())')" "$HOME/.local/bin/ffmpeg"
}

step_setup() {
  mkdir -p "$RAW"
  [ "$(adb -s "$DEV" get-state 2>/dev/null)" = device ] || P emulator
  curl -sf -m 10 "http://$CAM/cam.cgi?mode=getstate" | grep -q '<result>ok' \
    || { echo "camera not answering at $CAM (Wi-Fi waiting screen?)" >&2; exit 1; }
  P install
  A pm grant com.cameron.lumix android.permission.POST_NOTIFICATIONS
  A settings put global sysui_demo_allowed 1
  demo enter; demo clock -e hhmm 1000; demo notifications -e visible false
  demo battery -e level 100 -e plugged false; demo network -e wifi show -e level 4 -e mobile hide
  P launch >/dev/null
  echo "waiting for the catalog, dates and thumbnails..."; sleep 45
  # retry thumbnails that failed (the camera's server drops some under load)
  js 'document.querySelectorAll(".tile img").forEach(i=>{if(!i.dataset.loaded){delete i.dataset.tries;delete i.dataset.miss;delete i.dataset.queued;observer.unobserve(i);observer.observe(i)}})'
  sleep 15
  # warm the EXIF cache for the photos the shots and reel open Details on
  for i in 1 2; do js "openLb($i)"; sleep 3; js 'showInfo(true)'; sleep 12; js 'showInfo(false); closeLb()'; done
}

step_shots() {
  for m in $MODES; do
    theme "$m"
    shot "gallery-$m"
    js 'openLb(1)'; sleep 6; shot "viewer-$m"
    js 'showInfo(true)'; sleep 6; shot "details-$m"
    js 'showInfo(false); closeLb(); setSelecting(true); visible().slice(0,4).forEach(i=>sel.add(i.id)); refreshTiles(); updateSel(); scrollTo(0,0)'
    sleep 3; shot "select-$m"; js 'setSelecting(false)'
    js 'openRemote()'; sleep 12
    echo "live view frames: $(P eval 'remote.frames')"
    shot "remote-$m"; js 'closeRemote()'; sleep 4
  done
}

step_notify() {
  # hide other apps' notifications for an hour (they come back by themselves)
  A dumpsys notification --noredact | grep -oE "key=0\|[^ ]+\|[0-9]+$" | grep -v com.cameron.lumix | sed 's/^key=//' | sort -u \
    | while read -r k; do A "cmd notification snooze --for 3600000 '$k'" </dev/null >/dev/null; done
  for m in $MODES; do
    theme "$m"; unsave; clear_shade; sleep 3
    js 'scrollTo(0,0); saveItems(visible().slice(0,2))'
    sleep 3; A cmd statusbar expand-notifications; sleep 2; shot "notify-$m"   # "Saving 1 of 2" with its progress bar
    sleep 18; A cmd statusbar collapse
  done
}

step_reel() {
  theme light
  A rm -f /sdcard/demo.mp4
  adb -s "$DEV" shell screenrecord --time-limit 38 --bit-rate 8000000 /sdcard/demo.mp4 &
  rec=$!
  sleep 2.5
  A input swipe 540 1900 540 700 600; sleep 1.5                   # scroll the gallery
  A input swipe 540 1900 540 600 600; sleep 1.5
  for _ in 1 2 3; do A input swipe 540 600 540 2100 300; sleep 0.4; done; sleep 1.1   # back to the top, pull to refresh
  A input tap 540 1065; sleep 3                                     # open a photo
  A input swipe 900 1200 150 1200 250; sleep 2.5                    # next photo
  A input tap 888 148; sleep 4                                      # Details
  A input keyevent 4; sleep 1; A input keyevent 4; sleep 1.5
  A input swipe 203 1065 203 1065 900; sleep 0.7                    # long-press to select, then 3 more
  A input tap 876 1065; sleep 0.5; A input tap 203 1400; sleep 0.5; A input tap 540 1400; sleep 2
  A input keyevent 4; sleep 1.5
  A input tap 657 138; sleep 7                                      # Remote with live view
  A input keyevent 4
  wait $rec
  adb -s "$DEV" pull /sdcard/demo.mp4 "$RAW/demo.mp4"
}

step_encode() {
  ffmpeg_bin; mkdir -p "$MEDIA"
  for f in "$RAW"/*-light.png "$RAW"/*-dark.png; do
    [ -e "$f" ] || continue
    ffmpeg -loglevel error -y -i "$f" -vf scale=540:-1:flags=lanczos -q:v 3 "$MEDIA/$(basename "${f%.png}").jpg"
  done
  if [ -e "$RAW/demo.mp4" ]; then
    ffmpeg -loglevel error -y -ss 1.5 -t 33 -i "$RAW/demo.mp4" -vf "fps=30,scale=540:-2:flags=lanczos" \
      -c:v libx264 -crf 26 -preset slow -pix_fmt yuv420p -movflags +faststart -an "$MEDIA/demo.mp4"
    ffmpeg -loglevel error -y -ss 1.5 -t 33 -i "$RAW/demo.mp4" -vf "fps=10,scale=300:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
      "$MEDIA/demo.gif"
    ffmpeg -loglevel error -y -i "$RAW/demo.mp4" -vf "fps=1/2,scale=180:-1,tile=10x2" "$HERE/out/reel-sheet.png"
    echo "reel contact sheet: $HERE/out/reel-sheet.png"
  fi
  ls -la "$MEDIA"
}

step_cleanup() {
  unsave
  demo exit
  A cmd uimode night no >/dev/null
  P stop
  curl -s -m 10 "http://$CAM/cam.cgi?mode=camcmd&value=playmode" >/dev/null || true
  echo "done; stop udprelay.py on the host"
}

steps=${*:-setup shots notify reel encode cleanup}
for s in $steps; do
  case "$s" in
    setup|shots|notify|reel|encode|cleanup) echo "== $s"; "step_$s" ;;
    *) sed -n '2,7p' "$0"; exit 1 ;;
  esac
done
