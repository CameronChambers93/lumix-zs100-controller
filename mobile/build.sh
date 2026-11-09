#!/bin/sh
# Build the signed Android app and publish it at http://<pc>:8420/lumix.apk. Run inside the lumix container.
# Bump versionCode in android/app/build.gradle for each release you install over an older one.
set -e
cd "$(dirname "$0")"
# No bundler: the page loads Capacitor's runtime as a plain script.
cp node_modules/@capacitor/core/dist/capacitor.js www/
npx cap sync android
(cd android && ./gradlew --no-daemon -q assembleRelease)
mkdir -p ../dist
cp android/app/build/outputs/apk/release/app-release.apk ../dist/lumix.apk
echo "built ../dist/lumix.apk"
