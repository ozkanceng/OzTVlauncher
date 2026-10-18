# Google Cast and YouTube Casting Notes

OZLauncher is a privacy-first, offline launcher that requires no `INTERNET` permission and does not run any background network servers. On Android TV and Google TV, media casting (including YouTube, Spotify, and Chrome tab casting) is handled by the platform's native **Chromecast built-in** (`com.google.android.apps.mediashell`) service.

## Issue on Android TV 14 / Google TV

On Android TV 14 and newer, the operating system introduced an aggressive **Cached App Freezer** policy (`cached_apps_freezer`). When the stock OEM launcher is replaced by a third-party launcher or disabled via ADB:

1. Android TV considers `com.google.android.apps.mediashell` an idle background service and moves its process into the cached cgroup.
2. The OS freezes the process (`isFrozen=true`), causing it to stop responding to network sockets (ports 8008 / 8009) and mDNS discovery packets.
3. Phones and laptops searching for Cast targets either fail to see the TV or time out when trying to connect.
4. If `com.google.android.backdrop` (Ambient Mode / Screensaver) was also disabled during third-party launcher setup, the Cast receiver loses its idle visual state and fails to initialize.

## Resolution

Run the provided helper script with your TV's ADB connection:

```sh
./scripts/fix-android-tv-cast.sh [TV_IP_ADDRESS:5555]
```

Or execute the following commands manually via ADB:

```sh
# 1. Disable Android 14 cached apps freezer for background services
adb shell settings put global cached_apps_freezer disabled
adb shell device_config put activity_manager_native_boot use_freezer false

# 2. Exclude Chromecast built-in and YouTube from Doze/idle restrictions
adb shell dumpsys deviceidle whitelist +com.google.android.apps.mediashell
adb shell dumpsys deviceidle whitelist +com.google.android.youtube.tv

# 3. Ensure backdrop is enabled (required for Google Cast idle state)
adb shell pm enable com.google.android.backdrop

# 4. Wake up and restart the Cast receiver service
adb shell am startservice com.google.android.apps.mediashell/.CastReceiverService
```

## Non-Certified Android TV / AOSP Devices

Generic TV boxes and custom AOSP builds lack Google Cast certification keys. On those devices, casting will not discover the TV over Google Cast. Use **Link with TV Code** inside the YouTube TV app settings to pair phones directly via YouTube's servers.
