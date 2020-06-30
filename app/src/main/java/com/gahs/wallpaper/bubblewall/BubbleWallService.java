package com.gahs.wallpaper.bubblewall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.wallpaper.WallpaperService;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Random;

public class BubbleWallService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new BubbleWallEngine();
    }

    private class BubbleWallEngine extends Engine {
        private static final int BUBBLE_PADDING = 50;
        private static final int MAX_BUBBLE_RADIUS = 250;
        private static final int MIN_BUBBLE_RADIUS = 20;
        private static final int MAX_OVERLAP_RETRY_COUNT = 50;
        private static final int OUTLINE_SIZE = 30;

        private BubbleMinToMaxDrawer mBubblesMinToMax = new BubbleMinToMaxDrawer();
        private BubbleTouchDrawer mBubbleTouch = new BubbleTouchDrawer();
        private UIModeTransitioner mUIModeTransitioner = new UIModeTransitioner();

        private Runnable mBubblesMinToMaxRunnable = new Runnable() {
            @Override
            public void run() {
                mBubblesMinToMax.draw();
            }
        };
        private Runnable mMinimizeBubblesRunnable = new Runnable() {
            @Override
            public void run() {
                minimizeBubbles();
            }
        };
        private Runnable mMaximizeBubblesRunnable = new Runnable() {
            @Override
            public void run() {
                maximizeBubbles();
            }
        };
        private Runnable mUIModeTransitionRunnable = new Runnable() {
            @Override
            public void run() {
                mUIModeTransitioner.draw();

            }
        };
        private Runnable mBubbleTouchRunnable = new Runnable() {
            @Override
            public void run() {
                mBubbleTouch.draw();
            }
        };

        private HandlerThread mHandlerThread = new HandlerThread("BubbleHandlerThread");
        private Handler mHandler;
        private BroadcastReceiver mReceiver = new BubbleWallReceiver();
        private ArrayList<Bubble> mBubbles = new ArrayList<>();
        private int mUsedBubbleColors;
        private Boolean mDarkBg;
        private Bubble mPressedBubble;

        private class BubbleWallReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case "android.intent.action.USER_PRESENT":
                        mHandler.postDelayed(mBubblesMinToMaxRunnable, 250);
                        break;
                    case "android.intent.action.SCREEN_OFF":
                        mHandler.post(mMinimizeBubblesRunnable);
                        break;
                    case "android.intent.action.CONFIGURATION_CHANGED":
                        boolean newNightModeDark = isNightMode();
                        if (mDarkBg && !newNightModeDark || !mDarkBg && newNightModeDark) {
                            mDarkBg = newNightModeDark;
                            mHandler.post(mUIModeTransitionRunnable);
                        }
                        break;
                }
            }
        }

        BubbleWallEngine() {
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            setOffsetNotificationsEnabled(false);

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("android.intent.action.SCREEN_OFF");
            intentFilter.addAction("android.intent.action.USER_PRESENT");
            intentFilter.addAction("android.intent.action.CONFIGURATION_CHANGED");
            if (!isPreview()) {
                registerReceiver(mReceiver, intentFilter);
            }
        }

        @Override
        public void onDestroy() {
            mHandlerThread.quit();
            if (!isPreview()) {
                unregisterReceiver(mReceiver);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder surfaceHolder, int format, int width,
                                     int height) {
            if (mDarkBg == null) {
                mDarkBg = isNightMode();
            }

            skipAllDrawers();
            resetBubbles();
            mHandler.post(mMaximizeBubblesRunnable);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            // Suppress rapid and non-down touch events
            if (mHandler.hasCallbacks(mBubbleTouchRunnable) || mPressedBubble != null ||
                    event.getAction() != MotionEvent.ACTION_DOWN) {
                return;
            }
            int x = (int)Math.floor(event.getX());
            int y = (int)Math.floor(event.getY());
            mPressedBubble = getBubbleInBounds(x, y);
            if (mPressedBubble != null) {
                mHandler.post(mBubbleTouchRunnable);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (!visible) {
                skipAllDrawers();
            }
        }

        private class BubbleTouchDrawer extends AnimationDrawer {
            @Override
            public void drawLoop() {
                SurfaceHolder surfaceHolder = getSurfaceHolder();
                for (int x = 0; x < 20; x++) {
                    Canvas canvas = surfaceHolder.lockHardwareCanvas();
                    drawCanvasBackground(canvas);
                    mPressedBubble.currentRadius += x <= 10 ? .5f : -.5f;
                    drawBubbles(canvas);
                    surfaceHolder.unlockCanvasAndPost(canvas);
                    if (stopDrawing) {
                        break;
                    }
                }
                mPressedBubble = null;
            }

            @Override
            public void skipDrawing() {
                super.skipDrawing();
                // return all bubbles to normal state
                maximizeBubbles();
            }
        }

        private class BubbleMinToMaxDrawer extends AnimationDrawer {
            @Override
            public void drawLoop() {
                SurfaceHolder surfaceHolder = getSurfaceHolder();
                boolean bubblesMinimized =
                        mBubbles.get(0).currentRadius == mBubbles.get(0).minimizedRadius;
                bubbleloop: while (!stopDrawing) {
                    Canvas canvas = surfaceHolder.lockHardwareCanvas();
                    drawCanvasBackground(canvas);
                    for (Bubble bubble : mBubbles) {
                        float addToRadius = bubble.maxRadius * .25f;
                        float speedModifier = bubble.currentRadius / ((float)bubble.maxRadius / 2);
                        if (speedModifier > 1) {
                            speedModifier = 2 - speedModifier;
                        } else if (bubble.currentRadius >= bubble.minimizedRadius && bubblesMinimized) {
                            speedModifier = (bubble.currentRadius - bubble.minimizedRadius) /
                                    ((float)bubble.maxRadius / 2);
                        }
                        speedModifier = Math.max(speedModifier, .001f);
                        bubble.currentRadius += addToRadius * speedModifier;
                        bubble.currentRadius = Math.min(bubble.currentRadius,
                                bubble.maxRadius);
                    }
                    drawBubbles(canvas);
                    surfaceHolder.unlockCanvasAndPost(canvas);
                    for (int x = 0; x < mBubbles.size(); ++x) {
                        Bubble bubble = mBubbles.get(x);
                        if (bubble.currentRadius < bubble.maxRadius) {
                            continue bubbleloop;
                        }
                    }
                    break;
                }
            }

            @Override
            public void skipDrawing() {
                super.skipDrawing();
                maximizeBubbles();
            }
        }

        private class UIModeTransitioner extends AnimationDrawer {
            @Override
            public void drawLoop() {
                SurfaceHolder surfaceHolder = getSurfaceHolder();
                for (float x = 0f; x < 1.05f; x += 0.05f) {
                    Canvas canvas = surfaceHolder.lockHardwareCanvas();
                    float brightness = Math.max(mDarkBg ? 1f - x : x, 0f);
                    drawCanvasBackground(canvas, brightness);
                    drawBubbles(canvas);
                    surfaceHolder.unlockCanvasAndPost(canvas);
                    if (stopDrawing) {
                        break;
                    }
                }
            }

            @Override
            public void skipDrawing() {
                super.skipDrawing();
                SurfaceHolder surfaceHolder = getSurfaceHolder();
                Canvas canvas = surfaceHolder.lockHardwareCanvas();
                drawCanvasBackground(canvas, mDarkBg ? 0f : 1f);
                drawBubbles(canvas);
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }

        private void skipAllDrawers() {
            mBubblesMinToMax.skipDrawing();
            mBubbleTouch.skipDrawing();
            mUIModeTransitioner.skipDrawing();
        }

        private Bubble getBubbleInBounds(int x, int y) {
            for (Bubble bubble : mBubbles) {
                if (Math.pow(x - bubble.x, 2) + Math.pow(y - bubble.y, 2) <
                        Math.pow(bubble.maxRadius, 2)) {
                    return bubble;
                }
            }
            return null;
        }

        private void resetBubbles() {
            mBubbles.clear();

            while (true) {
                Bubble bubble = genRandomBubble();
                if (bubble != null) {
                    mBubbles.add(bubble);
                } else {
                    // If bubble is null, the max overlap retry count was
                    // exceeded, stop adding bubbles
                    break;
                }
            }
        }

        private Bubble genRandomBubble() {
            Random random = new Random();

            // Display dimensions
            int displayWidth = getResources().getDisplayMetrics().widthPixels;
            int displayHeight = getResources().getDisplayMetrics().heightPixels;

            int radius = 0, x = 0, y = 0;
            int overlapCount = 0;
            while (bubbleOverlaps(x, y, radius) || radius == 0) {
                radius = Math.max(random.nextInt(MAX_BUBBLE_RADIUS), MIN_BUBBLE_RADIUS);
                x = Math.max(random.nextInt(displayWidth - radius - BUBBLE_PADDING),
                        radius + BUBBLE_PADDING);
                y = Math.max(random.nextInt(displayHeight - radius - BUBBLE_PADDING),
                        radius + BUBBLE_PADDING);
                ++overlapCount;
                if (overlapCount > MAX_OVERLAP_RETRY_COUNT) {
                    return null;
                }
            }

            String[] colorArray = getResources().getStringArray(R.array.wallpaper_bubble_colors);
            if (mUsedBubbleColors + 1 >= colorArray.length) {
                mUsedBubbleColors = 0;
            }
            return new Bubble(x, y, radius, getBubblePaints(colorArray[mUsedBubbleColors++],
                    colorArray[mUsedBubbleColors++]));
        }

        private boolean bubbleOverlaps(int x, int y, int radius) {
            for (Bubble bubble : mBubbles) {
                double distance = Math.sqrt(Math.pow(x - bubble.x, 2) + Math.pow(y - bubble.y, 2));
                if (distance < radius + bubble.maxRadius + BUBBLE_PADDING) {
                    return true;
                }
            }
            return false;
        }

        private void drawBubbles(Canvas canvas) {
            for (Bubble bubble : mBubbles) {
                int x1 = (int)(bubble.currentRadius * Math.cos(Math.PI*.75) + bubble.x);
                int y1 = (int)(bubble.currentRadius * Math.sin(Math.PI*.75) + bubble.y);
                int x2 = (int)(bubble.currentRadius * Math.cos(Math.PI*1.75) + bubble.x);
                int y2 = (int)(bubble.currentRadius * Math.sin(Math.PI*1.75) + bubble.y);
                drawBubbleShadow(canvas, bubble, x1, y1, x2, y2,
                        bubble.x + (int)bubble.currentRadius + 50,
                        bubble.y + (int)bubble.currentRadius + 50);
            }

            for (Bubble bubble : mBubbles) {
                canvas.drawCircle(bubble.x, bubble.y, bubble.currentRadius, bubble.fill);
                canvas.drawCircle(bubble.x, bubble.y,
                        Math.max(bubble.currentRadius - (float)OUTLINE_SIZE / 2, 1),
                        bubble.outline);
            }
        }

        private void minimizeBubbles() {
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            Canvas canvas = surfaceHolder.lockHardwareCanvas();
            drawCanvasBackground(canvas);
            for (Bubble bubble : mBubbles) {
                bubble.currentRadius = bubble.minimizedRadius;
            }
            drawBubbles(canvas);
            surfaceHolder.unlockCanvasAndPost(canvas);
        }

        private void maximizeBubbles() {
            for (int y = 0; y < 2; ++y) {
                SurfaceHolder surfaceHolder = getSurfaceHolder();
                Canvas canvas = surfaceHolder.lockHardwareCanvas();
                drawCanvasBackground(canvas);
                for (Bubble bubble : mBubbles) {
                    bubble.currentRadius = bubble.maxRadius;
                }
                drawBubbles(canvas);
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }

        private int getAccentColor() {
            TypedValue outValue = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.colorAccent, outValue, true);
            return outValue.data;
        }

        private void drawCanvasBackground(Canvas canvas, float brightness) {
            int r = Math.round(255 * brightness);
            int g = Math.round(255 * brightness);
            int b = Math.round(255 * brightness);

            canvas.drawARGB(255, r, g, b);

            int accent = getAccentColor();
            canvas.drawColor(Color.argb(mDarkBg ? 50 : 90, Color.red(accent),
                    Color.green(accent), Color.blue(accent)));
        }

        private void drawCanvasBackground(Canvas canvas) {
            drawCanvasBackground(canvas, mDarkBg ? 0f : 1f);
        }

        Paint[] getBubblePaints(String outline, String fill) {
            Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setColor(Color.parseColor(fill));
            fillPaint.setStyle(Paint.Style.FILL);

            Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            outlinePaint.setColor(Color.parseColor(outline));
            outlinePaint.setStrokeWidth(30);
            outlinePaint.setStyle(Paint.Style.STROKE);

            return new Paint[]{fillPaint, outlinePaint};
        }

        private void drawBubbleShadow(Canvas canvas, Bubble bubble, int x1, int y1, int x2, int y2,
                                  int x3, int y3) {
            Path path = new Path();

            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            path.lineTo(x1, y1);
            path.close();

            int color = bubble.outline.getColor();
            color = Color.argb(120, Color.red(color), Color.green(color), Color.blue(color));

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setShader(new LinearGradient((float)(x1 + x2) / 2, (float)(y1 + y2) / 2, x3, y3,
                    color, Color.TRANSPARENT, Shader.TileMode.MIRROR));

            canvas.drawPath(path, paint);
        }
    }

    private static class AnimationDrawer {
        public boolean stopDrawing;
        private boolean drawing;

        public void drawLoop() {
        }

        public boolean isDrawing() {
            return drawing;
        }

        public void draw() {
            drawing = true;
            drawLoop();
            drawing = false;
        }

        public void skipDrawing() {
            if (!isDrawing()) {
                return;
            }
            stopDrawing = true;
            // Wait for drawing to finish
            while (drawing) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // None of these threads are ever interrupted
                }
            }
            stopDrawing = false;
            // Final outcome to be drawn here in child class
        }
    }

    private static class Bubble {
        int x;
        int y;
        int maxRadius;
        float currentRadius;
        final int minimizedRadius;
        Paint outline;
        Paint fill;

        Bubble(int x, int y, int maxRadius, Paint[] paints) {
            this.x = x;
            this.y = y;
            this.maxRadius = maxRadius;
            this.minimizedRadius = Math.round((float)maxRadius / 3);
            this.outline = paints[1];
            this.fill = paints[0];
        }
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES;
    }
}