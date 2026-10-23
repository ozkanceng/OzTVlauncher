# Changelog

All notable changes follow Keep a Changelog and Semantic Versioning.

## [Unreleased]

## [1.1.0] — 2026-10-23

### Added

- Native AirPlay receiver (`AirPlayServer`) with mDNS (`_airplay._tcp.`) service advertisement and RTSP handling for Apple devices (iPhone, iPad, Mac).
- Low-latency hardware-accelerated H.264 (`MediaCodecH264Decoder`) 60 FPS video decoder rendering directly to `SurfaceView`.
- Dedicated Screen Mirroring Hub (`MirroringHubActivity`) displaying connection status, device name, IP address, and step-by-step casting guides for Apple (AirPlay) and Android (Google Cast / Smart View).
- Built-in dynamic Action tile for Screen Mirroring (`action:mirror`) pinned directly to launcher favorites.
- Pixel-perfect custom TV brand cards for YouTube, Netflix, Spotify, Downloader, Apple TV, and mobile app fallbacks.

### Changed

- Complete TV interface redesign matching modern TV aesthetics:
  - Top translucent frosted glass pill dock for pinned/favorite apps.
  - Landscape aspect ratio cards (150x88dp) with 18dp rounded corners.
  - High-visibility remote D-pad focus: 1.14x smooth scaling with overshoot physics, 3dp luminous white outline with glowing halo, Z-index elevation lift, and dynamic text label appearing only under the focused card.
- Documentation for Google Cast and YouTube background service behavior on Android TV 14 (`docs/CASTING.md`).
- Helper script `scripts/fix-android-tv-cast.sh` to prevent Android 14's app freezer from halting the Cast receiver.

## [1.0.2] — 2026-08-27

### Added

- Distinct generated HDMI 1–3 artwork with port labels for MediaTek/Vestel TV inputs.

### Changed

- Refined the adaptive icon and TV banner with a navy-to-cyan brand gradient and subtle depth.

## [1.0.1] — 2026-08-27

### Changed

- Prefer native Android TV landscape banners, with uncropped app-icon fallback.
- Add tvOS-inspired focus depth, restrained typography and 18 dp rounded artwork.
- Keep the launcher lightweight with programmatic, dependency-free visuals.

## [1.0.0] — 2026-08-27

### Added

- Independent Android TV HOME launcher with favorites and a virtualized app drawer.
- App search, ordering, hiding, system app info/uninstall confirmation and custom icons.
- HDMI/AV tiles, remote-button shortcuts, six themes and configurable grid density.
- Three-state localized clock, static wallpaper, high contrast and reduced motion.
- Atomic, bounded `.ozbackup` export/import with custom icons.
- Ten languages including RTL Arabic.
- Reproducible Gradle wrapper, unit tests, lint, R8, CI and F-Droid metadata.
