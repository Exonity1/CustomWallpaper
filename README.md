# EXO Live Wallpaper Template

Small Java/Canvas live wallpaper template focused on low battery use and separate home/lock rendering paths.

## Home vs lock detection

Android exposes the exact rendering target through `WallpaperService.Engine.getWallpaperFlags()` and `onWallpaperFlagsChanged()` starting with API 34. Android 16/API 36 is covered by that API.

This template keeps `minSdk 16` so it can be used as an older-device base too. On API 16-33 Android does not expose a reliable home-vs-lock target for third-party live wallpapers, so the engine falls back to `UNKNOWN` and draws the home-style layer.

For an Android 16-only app, install SDK platform 36 and change this in `app/build.gradle`:

```gradle
compileSdk 36

defaultConfig {
    minSdk 36
    targetSdk 36
}
```

## Battery behavior

- No wake locks.
- No external render engine or background service.
- Rendering starts only while the wallpaper is visible and the surface exists.
- Screen-off broadcasts stop scheduled frames immediately.
- Power saver mode disables continuous animation.
- The lockscreen layer is static by default.
- The homescreen layer animates at 8 FPS by default.

## Where to customize

- `EfficientWallpaperService.drawHomeLayer(...)` for homescreen-only elements.
- `EfficientWallpaperService.drawLockLayer(...)` for lockscreen-only elements.
- `EfficientWallpaperService.DEBUG_TARGET_LABEL` to hide/show the current detected target label.
- `EfficientWallpaperService.ANIMATE_HOME`, `ANIMATE_LOCK`, and `HOME_FRAME_DELAY_MS` for the energy profile.
