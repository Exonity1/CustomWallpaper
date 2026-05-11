package com.exonity.wallpaper;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public final class EfficientWallpaperService extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new EfficientEngine();
    }

    private final class EfficientEngine extends Engine {
        private static final boolean DEBUG_TARGET_LABEL = true;
        private static final boolean ANIMATE_HOME = true;
        private static final boolean ANIMATE_LOCK = false;
        private static final long HOME_FRAME_DELAY_MS = 1000L / 8L;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF scratchRect = new RectF();
        private final Runnable frameCallback = new Runnable() {
            @Override
            public void run() {
                renderFrame();
                scheduleNextFrame();
            }
        };

        private SurfaceHolder surfaceHolder;
        private PowerManager powerManager;
        private BroadcastReceiver screenReceiver;
        private Shader homeShader;
        private Shader lockShader;
        private Target target = Target.UNKNOWN;
        private boolean visible;
        private boolean surfaceReady;
        private boolean screenInteractive = true;
        private int surfaceWidth;
        private int surfaceHeight;
        private float wallpaperXOffset = 0.5f;
        private long startedAtMs;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            surfaceHolder = holder;
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            screenInteractive = isDeviceInteractive();
            startedAtMs = SystemClock.uptimeMillis();

            setTouchEventsEnabled(false);
            updateTargetFromSystem();
            registerScreenReceiver();
        }

        @Override
        public void onDestroy() {
            stopRendering();
            unregisterScreenReceiver();
            surfaceHolder = null;
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
            surfaceReady = true;
            requestRender();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceWidth = width;
            surfaceHeight = height;
            updateShaders(width, height);
            requestRender();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            stopRendering();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            renderFrame();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            this.visible = visible;
            updateTargetFromSystem();

            if (visible) {
                requestRender();
            } else {
                stopRendering();
            }
        }

        @Override
        public void onOffsetsChanged(
                float xOffset,
                float yOffset,
                float xOffsetStep,
                float yOffsetStep,
                int xPixelOffset,
                int yPixelOffset
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset);
            wallpaperXOffset = xOffset;
            requestRender();
        }

        @Override
        public void onWallpaperFlagsChanged(int which) {
            super.onWallpaperFlagsChanged(which);
            target = targetFromFlags(which);
            requestRender();
        }

        private void registerScreenReceiver() {
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        screenInteractive = false;
                        stopRendering();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)
                            || Intent.ACTION_USER_PRESENT.equals(action)) {
                        screenInteractive = true;
                        requestRender();
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
        }

        private void unregisterScreenReceiver() {
            if (screenReceiver == null) {
                return;
            }

            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver is best-effort only; rendering lifecycle still comes from Engine callbacks.
            }
            screenReceiver = null;
        }

        private void requestRender() {
            handler.removeCallbacks(frameCallback);
            if (!surfaceReady || !visible) {
                return;
            }

            renderFrame();
            scheduleNextFrame();
        }

        private void stopRendering() {
            handler.removeCallbacks(frameCallback);
        }

        private void scheduleNextFrame() {
            handler.removeCallbacks(frameCallback);
            if (shouldAnimate()) {
                handler.postDelayed(frameCallback, HOME_FRAME_DELAY_MS);
            }
        }

        private boolean shouldAnimate() {
            if (!surfaceReady || !visible || !screenInteractive || isPowerSaveMode()) {
                return false;
            }

            if (target == Target.LOCK) {
                return ANIMATE_LOCK;
            }

            if (target == Target.HOME || target == Target.PREVIEW || target == Target.UNKNOWN) {
                return ANIMATE_HOME;
            }

            return false;
        }

        private void renderFrame() {
            SurfaceHolder holder = surfaceHolder;
            if (holder == null || !surfaceReady) {
                return;
            }

            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    drawWallpaper(canvas);
                }
            } catch (IllegalArgumentException ignored) {
                surfaceReady = false;
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        }

        private void drawWallpaper(Canvas canvas) {
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            if (width != surfaceWidth || height != surfaceHeight || homeShader == null || lockShader == null) {
                surfaceWidth = width;
                surfaceHeight = height;
                updateShaders(width, height);
            }

            Target drawTarget = target;
            if (drawTarget == Target.UNKNOWN || drawTarget == Target.PREVIEW) {
                drawTarget = Target.HOME;
            }

            backgroundPaint.setShader(drawTarget == Target.LOCK ? lockShader : homeShader);
            canvas.drawRect(0f, 0f, width, height, backgroundPaint);
            backgroundPaint.setShader(null);

            drawSharedLayer(canvas, width, height);

            long elapsedMs = SystemClock.uptimeMillis() - startedAtMs;
            float seconds = elapsedMs / 1000f;

            if (drawTarget == Target.LOCK) {
                drawLockLayer(canvas, width, height, seconds);
            } else if (drawTarget == Target.BOTH) {
                drawHomeLayer(canvas, width, height, seconds);
                drawLockHintLayer(canvas, width, height);
            } else {
                drawHomeLayer(canvas, width, height, seconds);
            }

            if (DEBUG_TARGET_LABEL) {
                drawTargetLabel(canvas, width, height);
            }
        }

        private void updateShaders(int width, int height) {
            int safeHeight = Math.max(1, height);
            homeShader = new LinearGradient(
                    0f,
                    0f,
                    0f,
                    safeHeight,
                    Color.rgb(7, 14, 24),
                    Color.rgb(10, 38, 43),
                    Shader.TileMode.CLAMP
            );
            lockShader = new LinearGradient(
                    0f,
                    0f,
                    0f,
                    safeHeight,
                    Color.rgb(7, 8, 15),
                    Color.rgb(31, 24, 45),
                    Shader.TileMode.CLAMP
            );
        }

        private void drawSharedLayer(Canvas canvas, int width, int height) {
            accentPaint.setStyle(Paint.Style.STROKE);
            accentPaint.setStrokeWidth(Math.max(1f, width * 0.0025f));
            accentPaint.setColor(Color.argb(36, 255, 255, 255));

            float baseY = height * 0.74f;
            float parallax = (wallpaperXOffset - 0.5f) * width * 0.12f;
            for (int i = 0; i < 4; i++) {
                float inset = width * (0.08f + i * 0.08f);
                scratchRect.set(
                        inset + parallax,
                        baseY - i * height * 0.055f,
                        width - inset + parallax,
                        baseY + height * 0.22f
                );
                canvas.drawOval(scratchRect, accentPaint);
            }
        }

        private void drawHomeLayer(Canvas canvas, int width, int height, float seconds) {
            accentPaint.setStyle(Paint.Style.FILL);
            int[] colors = {
                    Color.argb(46, 88, 214, 141),
                    Color.argb(34, 78, 184, 232),
                    Color.argb(36, 242, 188, 91)
            };

            float minSide = Math.min(width, height);
            for (int i = 0; i < 7; i++) {
                float phase = seconds * 0.22f + i * 1.31f;
                float column = (i % 3) / 2f;
                float row = (i / 3) / 2f;
                float cx = width * (0.18f + 0.64f * column) + (float) Math.sin(phase) * minSide * 0.025f;
                float cy = height * (0.18f + 0.54f * row) + (float) Math.cos(phase * 0.9f) * minSide * 0.025f;
                float radius = minSide * (0.045f + (i % 3) * 0.018f);

                accentPaint.setColor(colors[i % colors.length]);
                canvas.drawCircle(cx, cy, radius, accentPaint);
            }

            accentPaint.setStyle(Paint.Style.STROKE);
            accentPaint.setStrokeWidth(Math.max(2f, minSide * 0.006f));
            accentPaint.setColor(Color.argb(92, 148, 236, 190));
            canvas.drawCircle(width * 0.5f, height * 0.45f, minSide * 0.18f, accentPaint);
        }

        private void drawLockLayer(Canvas canvas, int width, int height, float seconds) {
            float minSide = Math.min(width, height);
            float centerX = width * 0.5f;
            float centerY = height * 0.48f;
            float lockSize = minSide * 0.2f;

            accentPaint.setStyle(Paint.Style.STROKE);
            accentPaint.setStrokeCap(Paint.Cap.ROUND);
            accentPaint.setStrokeWidth(Math.max(5f, minSide * 0.012f));
            accentPaint.setColor(Color.argb(150, 238, 229, 255));

            scratchRect.set(
                    centerX - lockSize * 0.55f,
                    centerY - lockSize * 0.78f,
                    centerX + lockSize * 0.55f,
                    centerY + lockSize * 0.18f
            );
            canvas.drawArc(scratchRect, 205f, 130f, false, accentPaint);

            scratchRect.set(
                    centerX - lockSize * 0.72f,
                    centerY - lockSize * 0.06f,
                    centerX + lockSize * 0.72f,
                    centerY + lockSize * 0.92f
            );
            canvas.drawRoundRect(scratchRect, lockSize * 0.16f, lockSize * 0.16f, accentPaint);

            accentPaint.setStyle(Paint.Style.FILL);
            accentPaint.setColor(Color.argb(96, 238, 229, 255));
            canvas.drawCircle(centerX, centerY + lockSize * 0.36f, lockSize * 0.08f, accentPaint);

            accentPaint.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawLockHintLayer(Canvas canvas, int width, int height) {
            float minSide = Math.min(width, height);
            accentPaint.setStyle(Paint.Style.STROKE);
            accentPaint.setStrokeWidth(Math.max(3f, minSide * 0.008f));
            accentPaint.setColor(Color.argb(90, 238, 229, 255));

            float size = minSide * 0.11f;
            scratchRect.set(
                    width - size * 1.85f,
                    height - size * 2.35f,
                    width - size * 0.65f,
                    height - size * 1.15f
            );
            canvas.drawRoundRect(scratchRect, size * 0.12f, size * 0.12f, accentPaint);
        }

        private void drawTargetLabel(Canvas canvas, int width, int height) {
            labelPaint.setStyle(Paint.Style.FILL);
            labelPaint.setTextAlign(Paint.Align.LEFT);
            labelPaint.setTextSize(Math.max(18f, Math.min(width, height) * 0.032f));
            labelPaint.setColor(Color.argb(180, 255, 255, 255));
            canvas.drawText(target.label, width * 0.06f, height * 0.92f, labelPaint);
        }

        private void updateTargetFromSystem() {
            if (isPreview()) {
                target = Target.PREVIEW;
                return;
            }

            if (Build.VERSION.SDK_INT >= 34) {
                target = targetFromFlags(getWallpaperFlags());
            } else {
                target = Target.UNKNOWN;
            }
        }

        private Target targetFromFlags(int flags) {
            boolean rendersHome = (flags & WallpaperManager.FLAG_SYSTEM) != 0;
            boolean rendersLock = (flags & WallpaperManager.FLAG_LOCK) != 0;

            if (rendersHome && rendersLock) {
                return Target.BOTH;
            }
            if (rendersLock) {
                return Target.LOCK;
            }
            if (rendersHome) {
                return Target.HOME;
            }
            return Target.UNKNOWN;
        }

        private boolean isPowerSaveMode() {
            return powerManager != null
                    && Build.VERSION.SDK_INT >= 21
                    && powerManager.isPowerSaveMode();
        }

        private boolean isDeviceInteractive() {
            if (powerManager == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 20) {
                return powerManager.isInteractive();
            }
            return powerManager.isScreenOn();
        }
    }

    private enum Target {
        HOME("HOME"),
        LOCK("LOCK"),
        BOTH("HOME + LOCK"),
        PREVIEW("PREVIEW"),
        UNKNOWN("UNKNOWN");

        final String label;

        Target(String label) {
            this.label = label;
        }
    }
}
