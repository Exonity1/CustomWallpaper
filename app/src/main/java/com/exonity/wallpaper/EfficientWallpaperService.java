package com.exonity.wallpaper;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public final class EfficientWallpaperService extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new ColorEngine();
    }

    private final class ColorEngine extends Engine {
        private static final int HOME_COLOR = Color.GREEN;
        private static final int LOCK_COLOR = Color.RED;

        private SurfaceHolder surfaceHolder;
        private int wallpaperFlags;
        private boolean surfaceReady;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            surfaceHolder = holder;
            wallpaperFlags = getWallpaperFlags();
            setTouchEventsEnabled(false);
        }

        @Override
        public void onDestroy() {
            surfaceReady = false;
            surfaceHolder = null;
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
            surfaceReady = true;
            drawColor();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceHolder = holder;
            drawColor();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            surfaceHolder = null;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            drawColor();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                drawColor();
            }
        }

        @Override
        public void onWallpaperFlagsChanged(int which) {
            super.onWallpaperFlagsChanged(which);
            wallpaperFlags = which;
            drawColor();
        }

        private void drawColor() {
            SurfaceHolder holder = surfaceHolder;
            if (holder == null || !surfaceReady) {
                return;
            }

            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    canvas.drawColor(resolveColor());
                }
            } catch (IllegalArgumentException ignored) {
                surfaceReady = false;
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        }

        private int resolveColor() {
            if (isPreview()) {
                return HOME_COLOR;
            }

            boolean rendersHome = (wallpaperFlags & WallpaperManager.FLAG_SYSTEM) != 0;
            boolean rendersLock = (wallpaperFlags & WallpaperManager.FLAG_LOCK) != 0;

            if (rendersLock && !rendersHome) {
                return LOCK_COLOR;
            }
            if (rendersHome && !rendersLock) {
                return HOME_COLOR;
            }
            return isLockscreenActive() ? LOCK_COLOR : HOME_COLOR;
        }

        private boolean isLockscreenActive() {
            KeyguardManager keyguardManager =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager == null) {
                return false;
            }
            return keyguardManager.isDeviceLocked() || keyguardManager.isKeyguardLocked();
        }
    }
}
