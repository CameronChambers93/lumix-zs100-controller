#!/bin/sh
# Drive the Lumix app on Cameron's phone over wireless ADB. Run inside the lumix container:
#   sh mobile/tools/phone.sh <command>
# Always give explicit ip:port (mDNS doesn't work through Docker NAT).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
APK="$HERE/../../dist/lumix.apk"
DEVFILE="$HERE/.device"
# PHONE_TARGET=emulator drives the Windows-host emulator instead of the phone (separate remembered target).
[ "${PHONE_TARGET:-}" = emulator ] && DEVFILE="$HERE/.device-emulator"
EMU_ADDR=192.168.65.254:5555   # host.docker.internal; Docker Desktop forwards it to the host's loopback, where the emulator listens
PKG=com.cameron.lumix
PATH="$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools"   # not on PATH in non-login shells

usage() {
  cat <<U
usage: phone.sh <command>
  pair <ip:port> <code>   one-time pairing (port/code from Wireless debugging > Pair with code)
  connect <ip:port>       connect (port from the main Wireless debugging screen); remembered
  emulator                connect to the host emulator (lumix_s23plus or lumix_pixel5, start it on Windows first); then use
                          PHONE_TARGET=emulator sh mobile/tools/phone.sh <command> for everything else
  status                  device, installed versionCode, whether the app is running
  install | launch | stop install dist/lumix.apk (-r, never uninstall) / start / force-stop
  shot [file]             screenshot to mobile/tools/out/shot-<time>.png; prints the path
  logs [n]                last n (default 100) log lines for the app (+ Capacitor, chromium, AndroidRuntime)
  devtools                forward WebView DevTools to localhost:9222 and list targets
  eval '<js>'             run JS in the app page, print the result (exit 1 on exception)
  console [secs]          print console.* output and exceptions for N seconds
  tap <x> <y> | text '<s>' | back
U
}

dev() {
  [ -s "$DEVFILE" ] || { echo "no device; run pair/connect" >&2; exit 1; }
  adb -s "$(cat "$DEVFILE")" "$@"
}
app_pid() { dev shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
forward() {
  pid=$(app_pid)
  [ -n "$pid" ] || { echo "app is not running; run launch" >&2; exit 1; }
  dev forward tcp:9222 "localabstract:webview_devtools_remote_$pid" >/dev/null
}

cmd=${1:-}
[ $# -gt 0 ] && shift
case "$cmd" in
  pair) [ $# -eq 2 ] || { usage; exit 1; }; adb pair "$1" "$2" ;;
  connect)
    [ $# -eq 1 ] || { usage; exit 1; }
    adb connect "$1"
    adb -s "$1" get-state >/dev/null   # fails if the connect didn't take
    echo "$1" > "$DEVFILE" ;;
  emulator)
    DEVFILE="$HERE/.device-emulator"
    adb connect "$EMU_ADDR"
    adb -s "$EMU_ADDR" get-state >/dev/null   # "unauthorized" fails here: accept the prompt in the emulator
    echo "$EMU_ADDR" > "$DEVFILE" ;;
  status)
    [ -s "$DEVFILE" ] || { echo "no device; run pair/connect" >&2; exit 1; }
    adb devices -l
    ver=$(dev shell dumpsys package "$PKG" | grep -m1 versionCode || true)
    echo "installed: ${ver:-not installed}"
    pid=$(app_pid || true)
    echo "running: ${pid:-no}" ;;
  install) dev install -r "$APK" ;;
  launch) dev shell am start -n "$PKG/.MainActivity" ;;
  stop) dev shell am force-stop "$PKG" ;;
  shot)
    out=${1:-$HERE/out/shot-$(date +%H%M%S).png}
    mkdir -p "$(dirname "$out")"
    dev exec-out screencap -p > "$out"
    echo "$out" ;;
  logs)
    n=${1:-100}
    pid=$(app_pid || true)
    dev logcat -d -v threadtime \
      | grep -E "^[0-9-]+ +[0-9:.]+ +${pid:-NOPID} | (Capacitor|Capacitor/Console|chromium|AndroidRuntime) *: " \
      | tail -n "$n" || true ;;
  devtools)
    forward
    curl -s http://localhost:9222/json ;;
  eval) [ $# -eq 1 ] || { usage; exit 1; }; forward; node "$HERE/cdp.mjs" eval "$1" ;;
  console) forward; node "$HERE/cdp.mjs" console "${1:-10}" ;;
  tap) [ $# -eq 2 ] || { usage; exit 1; }; dev shell input tap "$1" "$2" ;;
  text) [ $# -eq 1 ] || { usage; exit 1; }; dev shell input text "$(printf %s "$1" | sed 's/ /%s/g')" ;;
  back) dev shell input keyevent 4 ;;
  *) usage; [ -z "$cmd" ] || exit 1 ;;
esac
