#!/bin/sh
set -eu

target=${1:-}
adb_cmd="adb"

if [ -n "$target" ]; then
  adb_cmd="adb -s $target"
fi

echo "Applying Google Cast / Android 14 background service fixes..."

# Disable Android 14 cached apps freezer which freezes background CastReceiverService
$adb_cmd shell settings put global cached_apps_freezer disabled 2>/dev/null || true
$adb_cmd shell device_config put activity_manager_native_boot use_freezer false 2>/dev/null || true

# Whitelist Chromecast built-in and YouTube from device idle / Doze
$adb_cmd shell dumpsys deviceidle whitelist +com.google.android.apps.mediashell 2>/dev/null || true
$adb_cmd shell dumpsys deviceidle whitelist +com.google.android.youtube.tv 2>/dev/null || true

# Re-enable backdrop if it was disabled (required by Google Cast receiver)
$adb_cmd shell pm enable com.google.android.backdrop 2>/dev/null || true

# Restart CastReceiverService
$adb_cmd shell am startservice com.google.android.apps.mediashell/.CastReceiverService 2>/dev/null || true

echo "Done. Google Cast receiver is unfreezed and active."
