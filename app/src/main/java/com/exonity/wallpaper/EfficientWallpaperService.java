package com.exonity.wallpaper;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.SurfaceHolder;

import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.FilamentInstance;
import com.google.android.filament.gltfio.MaterialProvider;
import com.google.android.filament.gltfio.UbershaderProvider;
import com.google.android.filament.gltfio.Animator;

import java.io.InputStream;
import java.nio.ByteBuffer;

public final class EfficientWallpaperService extends WallpaperService {
    static {
        com.google.android.filament.Filament.init();
        com.google.android.filament.gltfio.Gltfio.init();
    }

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

        private BroadcastReceiver stateReceiver;

        // Filament fields
        private com.google.android.filament.Engine filamentEngine;
        private com.google.android.filament.Renderer renderer;
        private com.google.android.filament.Scene scene;
        private com.google.android.filament.View view;
        private com.google.android.filament.Camera camera;
        private com.google.android.filament.SwapChain swapChain;
        private com.google.android.filament.Skybox skybox;
        
        private AssetLoader assetLoader;
        private MaterialProvider materialProvider;
        private FilamentAsset asset;
        private com.google.android.filament.gltfio.FilamentInstance filamentInstance;
        private Animator animator;
        private ByteBuffer assetBuffer;

        private com.google.android.filament.IndirectLight indirectLight;

        private boolean filamentInitialized = false;
        private boolean running = false;
        private long lastRenderTime = 0;
        private float animationTime = 0.0f;
        private long lastFrameTime = 0;

        private void renderSingleFrame(float animTime, long frameTimeNanos) {
            if (filamentEngine != null && renderer != null && swapChain != null && view != null) {
                if (animator != null && animator.getAnimationCount() > 0) {
                    animator.applyAnimation(0, animTime);
                    animator.updateBoneMatrices();
                }
                if (renderer.beginFrame(swapChain, frameTimeNanos)) {
                    renderer.render(view);
                    renderer.endFrame();
                }
            }
        }

        private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (!running) return;

                long currentTime = System.currentTimeMillis();
                if (lastRenderTime == 0) {
                    lastRenderTime = currentTime;
                    lastFrameTime = currentTime;
                }

                long elapsed = currentTime - lastRenderTime;

                // Render at 24fps (approx 41.6ms per frame)
                if (elapsed >= 40) {
                    lastRenderTime = currentTime;

                    long frameDelta = currentTime - lastFrameTime;
                    lastFrameTime = currentTime;

                    if (animator != null && animator.getAnimationCount() > 0) {
                        float duration = animator.getAnimationDuration(0);
                        if (duration > 0.0f) {
                            animationTime = (animationTime + frameDelta / 1000.0f) % duration;
                        } else {
                            animationTime += frameDelta / 1000.0f;
                        }
                    }

                    renderSingleFrame(animationTime, frameTimeNanos);
                }

                if (running) {
                    Choreographer.getInstance().postFrameCallback(this);
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            surfaceHolder = holder;
            wallpaperFlags = getWallpaperFlags();
            setTouchEventsEnabled(false);

            stateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateRenderingState();
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_OFF);

            EfficientWallpaperService.this.registerReceiver(stateReceiver, filter);
        }

        @Override
        public void onDestroy() {
            if (stateReceiver != null) {
                EfficientWallpaperService.this.unregisterReceiver(stateReceiver);
                stateReceiver = null;
            }

            surfaceReady = false;
            surfaceHolder = null;
            
            destroyFilament();
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
            surfaceReady = true;
            updateRenderingState();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceHolder = holder;
            if (filamentInitialized && filamentEngine != null) {
                if (swapChain != null) {
                    filamentEngine.destroySwapChain(swapChain);
                }
                swapChain = filamentEngine.createSwapChain(holder.getSurface());
                view.setViewport(new com.google.android.filament.Viewport(0, 0, width, height));
                double aspect = (double) width / (double) height;
                camera.setProjection(45.0, aspect, 0.1, 100.0, com.google.android.filament.Camera.Fov.VERTICAL);
            }
            updateRenderingState();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            surfaceHolder = null;
            updateRenderingState();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            updateRenderingState();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            updateRenderingState();
        }

        @Override
        public void onWallpaperFlagsChanged(int which) {
            super.onWallpaperFlagsChanged(which);
            wallpaperFlags = which;
            updateRenderingState();
        }

        private void initFilament() {
            if (filamentInitialized) return;
            try {
                filamentEngine = com.google.android.filament.Engine.create();
                renderer = filamentEngine.createRenderer();
                scene = filamentEngine.createScene();
                view = filamentEngine.createView();
                
                camera = filamentEngine.createCamera(com.google.android.filament.EntityManager.get().create());
                view.setCamera(camera);
                view.setScene(scene);

                // Black background
                skybox = new com.google.android.filament.Skybox.Builder()
                        .color(0.0f, 0.0f, 0.0f, 1.0f)
                        .build(filamentEngine);
                scene.setSkybox(skybox);

                // Flat ambient/unlit lighting simulation using uniform Spherical Harmonics
                float[] sh = new float[27];
                sh[0] = 1.0f;
                sh[1] = 1.0f;
                sh[2] = 1.0f;
                indirectLight = new com.google.android.filament.IndirectLight.Builder()
                        .irradiance(3, sh)
                        .intensity(100000.0f) // Full intensity for unlit look
                        .build(filamentEngine);
                scene.setIndirectLight(indirectLight);

                // Setup asset loader
                materialProvider = new UbershaderProvider(filamentEngine);
                assetLoader = new AssetLoader(filamentEngine, materialProvider, com.google.android.filament.EntityManager.get());

                // Read GLB file
                byte[] buffer;
                try (InputStream is = EfficientWallpaperService.this.getAssets().open("minecraft_bee_enby2.glb")) {
                    buffer = new byte[is.available()];
                    int read = is.read(buffer);
                }

                assetBuffer = ByteBuffer.allocateDirect(buffer.length);
                assetBuffer.order(java.nio.ByteOrder.nativeOrder());
                assetBuffer.put(buffer);
                assetBuffer.rewind();

                asset = assetLoader.createAsset(assetBuffer);

                if (asset != null) {
                    com.google.android.filament.gltfio.ResourceLoader resourceLoader =
                            new com.google.android.filament.gltfio.ResourceLoader(filamentEngine);
                    resourceLoader.loadResources(asset);
                    resourceLoader.destroy();

                    filamentInstance = asset.getInstance();
                    if (filamentInstance != null) {
                        scene.addEntities(asset.getEntities());
                        animator = filamentInstance.getAnimator();
                    }
                    
                    // Auto-center and position camera
                    com.google.android.filament.Box box = asset.getBoundingBox();
                    float[] center = box.getCenter();
                    float[] halfExtent = box.getHalfExtent();
                    float maxExtent = Math.max(halfExtent[0], Math.max(halfExtent[1], halfExtent[2]));
                    if (maxExtent == 0.0f) maxExtent = 1.0f;

                    camera.lookAt(
                        center[0], center[1], center[2] + maxExtent * 3.5f,
                        center[0], center[1], center[2],
                        0.0f, 1.0f, 0.0f
                    );
                }

                if (surfaceReady && surfaceHolder != null) {
                    swapChain = filamentEngine.createSwapChain(surfaceHolder.getSurface());
                    Rect frame = surfaceHolder.getSurfaceFrame();
                    int width = frame.width();
                    int height = frame.height();
                    view.setViewport(new com.google.android.filament.Viewport(0, 0, width, height));
                    double aspect = (double) width / (double) height;
                    camera.setProjection(45.0, aspect, 0.1, 100.0, com.google.android.filament.Camera.Fov.VERTICAL);
                }

                filamentInitialized = true;
            } catch (Exception e) {
                e.printStackTrace();
                destroyFilament();
            }
        }

        private void destroyFilament() {
            filamentInitialized = false;
            stopLoop();

            if (filamentEngine != null) {
                if (swapChain != null) {
                    filamentEngine.destroySwapChain(swapChain);
                    swapChain = null;
                }
                if (assetLoader != null && asset != null) {
                    assetLoader.destroyAsset(asset);
                    asset = null;
                }
                if (assetLoader != null) {
                    assetLoader.destroy();
                    assetLoader = null;
                }
                if (materialProvider != null) {
                    materialProvider.destroy();
                    materialProvider = null;
                }
                if (skybox != null) {
                    filamentEngine.destroySkybox(skybox);
                    skybox = null;
                }
                if (indirectLight != null) {
                    filamentEngine.destroyIndirectLight(indirectLight);
                    indirectLight = null;
                }
                if (view != null) {
                    filamentEngine.destroyView(view);
                    view = null;
                }
                if (scene != null) {
                    filamentEngine.destroyScene(scene);
                    scene = null;
                }
                if (camera != null) {
                    filamentEngine.destroyCameraComponent(camera.getEntity());
                    com.google.android.filament.EntityManager.get().destroy(camera.getEntity());
                    camera = null;
                }
                filamentEngine.destroy();
                filamentEngine = null;
            }
            animator = null;
            filamentInstance = null;
            assetBuffer = null;
        }

        private void startLoop() {
            if (running) return;
            running = true;
            lastRenderTime = 0;
            lastFrameTime = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }

        private void stopLoop() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }

        private void updateRenderingState() {
            boolean visible = isVisible();
            boolean surfaceReady = this.surfaceReady;
            boolean lockscreenMode = isLockscreenMode();

            if (visible && surfaceReady) {
                if (!filamentInitialized) {
                    initFilament();
                }

                if (filamentInitialized) {
                    if (lockscreenMode) {
                        startLoop();
                    } else {
                        stopLoop();
                        renderSingleFrame(0.0f, System.nanoTime());
                    }
                } else {
                    stopLoop();
                    drawColor();
                }
            } else {
                stopLoop();
                if (filamentInitialized) {
                    destroyFilament();
                }
            }
        }

        private void drawColor() {
            SurfaceHolder holder = surfaceHolder;
            if (holder == null || !this.surfaceReady) {
                return;
            }

            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    canvas.drawColor(resolveColor());
                }
            } catch (IllegalArgumentException ignored) {
                this.surfaceReady = false;
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        }

        private int resolveColor() {
            return Color.BLACK;
        }

        private boolean isLockscreenActive() {
            KeyguardManager keyguardManager =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager == null) {
                return false;
            }
            return keyguardManager.isDeviceLocked() || keyguardManager.isKeyguardLocked();
        }

        private boolean isLockscreenMode() {
            if (isPreview()) {
                return true;
            }

            boolean rendersHome = (wallpaperFlags & WallpaperManager.FLAG_SYSTEM) != 0;
            boolean rendersLock = (wallpaperFlags & WallpaperManager.FLAG_LOCK) != 0;

            if (rendersLock && !rendersHome) {
                return true;
            }
            if (rendersHome && !rendersLock) {
                return false;
            }
            return isLockscreenActive();
        }
    }
}
