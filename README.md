# OZLauncher

OZLauncher is a free, independent and privacy-first home launcher for Android TV and Google TV. It shows your apps and TV inputs without ads, recommendations, accounts or network access.

## Highlights

- Tiny pure-Java APK with no runtime libraries.
- Favorites plus a D-pad-first, virtualized app drawer.
- Local app search, hiding, ordering, custom icons and remote shortcuts.
- HDMI/AV input tiles on compatible televisions.
- Six themes, 5–8 columns, four corner styles and an optional static wallpaper.
- Versioned `.ozbackup` backup/restore including private custom icons.
- English, Turkish, German, Spanish, French, Brazilian Portuguese, Russian, Arabic, Hindi and Simplified Chinese.
- No `INTERNET`, location, microphone, account or accessibility-service permission.

## Compatibility

- Android TV and Google TV: Android 8.0 / API 26 or newer.
- Fire TV: the APK can be sideloaded, but some Fire OS releases prevent third-party HOME selection. See [Fire TV notes](docs/FIRE_TV.md).
- Google Cast / YouTube Casting: on Android TV 14+, see [Casting and background service notes](docs/CASTING.md).

## Install

1. Download `OZLauncher-v1.0.0.apk` from the latest GitHub Release.
2. Verify the SHA-256 file published beside it.
3. Sideload the APK and open it once.
4. Choose OZLauncher in **Settings → Apps → Default apps → Home app**. OEM menus vary.

Keep your previous launcher enabled until OZLauncher has survived a reboot and you have tested inputs, streaming apps, sound, keyboard and remote controls.

## Performance

Measured results and the current optimization status are in [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Build

Requirements: JDK 17+ and Android SDK 36.

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleRelease
```

The release build uses R8 and resource shrinking. A signing configuration is optional locally and must live in the ignored `signing.properties` file.

## Privacy and permissions

OZLauncher does not contain ads, analytics, telemetry, crash uploading or networking code. See [PRIVACY.md](PRIVACY.md). The system uninstall permission only opens Android's own confirmation screen; OZLauncher cannot silently remove an app.

## Independent implementation

OZLauncher is an original implementation built with public Android platform APIs. It does not copy BareLauncher source code, branding, icons or assets.

## License

Apache License 2.0. See [LICENSE](LICENSE).
