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
import android.os.Build;
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

        private BroadcastReceiver mReceiver = new BubbleWallReceiver();
        private ArrayList<Bubble> mBubbles = new ArrayList<>();
        private Boolean mNightUiMode;
        private Bubble mPressedBubble;
        private int[] mSurfaceDimensions = new int[2];
        private int mAccentColor;

        private class BubbleWallReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case "android.intent.action.USER_PRESENT":
                        drawBubbleSizeTransition(1f);
                        break;
                    case "android.intent.action.SCREEN_OFF":
                        drawBubblesFactorOfMax(1/3f);
                        break;
                    case "android.intent.action.CONFIGURATION_CHANGED":
                        boolean newUiModeNight = isNightMode();
                        if (mNightUiMode != newUiModeNight) {
                            mNightUiMode = newUiModeNight;
                            drawUiModeTransition();
                        }
                        break;
                    case "android.intent.action.PACKAGE_CHANGED":
                        int newAccentColor = getAccentColor();
                        if (mAccentColor != newAccentColor) {
                            mAccentColor = newAccentColor;
                            drawBubblesCurrentRadius();
                        }
                        break;
                }
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("android.intent.action.SCREEN_OFF");
            intentFilter.addAction("android.intent.action.USER_PRESENT");
            intentFilter.addAction("android.intent.action.CONFIGURATION_CHANGED");

            IntentFilter intentFilter2 = new IntentFilter();
            intentFilter2.addAction("android.intent.action.PACKAGE_CHANGED");
            intentFilter2.addDataScheme("package");
            if (!isPreview()) {
                registerReceiver(mReceiver, intentFilter);
                registerReceiver(mReceiver, intentFilter2);
            }
        }

        @Override
        public void onDestroy() {
            if (!isPreview()) {
                unregisterReceiver(mReceiver);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder surfaceHolder, int format, int width,
                                     int height) {
            if (mNightUiMode == null) {
                mNightUiMode = isNightMode();
            }

            mSurfaceDimensions[0] = width;
            mSurfaceDimensions[1] = height;

            mAccentColor = getAccentColor();

            regenAllBubbles();
            drawBubblesFactorOfMax(1f);
            drawBubblesFactorOfMax(1f);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            // Ignore unwanted touch events
            if (event.getAction() != MotionEvent.ACTION_UP &&
                    event.getAction() != MotionEvent.ACTION_DOWN) {
                return;
            }

            if (event.getAction() != MotionEvent.ACTION_UP) {
                mPressedBubble = getBubbleFromTouchEvent(event);
                if (mPressedBubble != null) {
                    drawBubbleTouch(true);
                }
            } else if (mPressedBubble != null) {
                drawBubbleTouch(false);
            }
        }

        @Override
        public void onZoomChanged(float zoom) {
            drawBubblesFactorOfMax(Math.max(1 - zoom, .3f));
        }

        public void drawUiModeTransition() {
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            for (float x = 0f; x < 1f; x += 0.05f) {
                Canvas canvas = lockHwCanvasIfPossible(surfaceHolder);
                float brightness = mNightUiMode ? 1f - x : x;
                drawCanvasBackground(canvas, brightness);
                drawBubbles(canvas);
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }

        private void drawCanvasBackground(Canvas canvas, float brightness) {
            int r = Math.round(255 * brightness);
            int g = Math.round(255 * brightness);
            int b = Math.round(255 * brightness);

            canvas.drawARGB(255, r, g, b);

            canvas.drawColor(Color.argb(mNightUiMode ? 50 : 90, Color.red(mAccentColor),
                    Color.green(mAccentColor), Color.blue(mAccentColor)));
        }

        private void drawCanvasBackground(Canvas canvas) {
            drawCanvasBackground(canvas, mNightUiMode ? 0f : 1f);
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
                        bubble.currentRadius - (float)OUTLINE_SIZE / 2, bubble.outline);
            }
        }

        private void drawBubblesCurrentRadius() {
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            Canvas canvas = lockHwCanvasIfPossible(surfaceHolder);
            drawCanvasBackground(canvas);
            drawBubbles(canvas);
            surfaceHolder.unlockCanvasAndPost(canvas);
        }

        private void drawBubblesFactorOfMax(float factor) {
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            Canvas canvas = lockHwCanvasIfPossible(surfaceHolder);
            drawCanvasBackground(canvas);
            for (Bubble bubble : mBubbles) {
                bubble.currentRadius = bubble.maxRadius * factor;
            }
            drawBubbles(canvas);
            surfaceHolder.unlockCanvasAndPost(canvas);
        }

        private void drawBubbleTouch(boolean expand) {
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            for (int x = 0; x < 5; x++) {
                Canvas canvas = lockHwCanvasIfPossible(surfaceHolder);
                drawCanvasBackground(canvas);
                mPressedBubble.currentRadius += expand ? 1f : -1f;
                drawBubbles(canvas);
                surfaceHolder.unlockCanvasAndPost(canvas);
            }

            if (!expand) {
                mPressedBubble = null;
            }
        }

        public void drawBubbleSizeTransition(float targetFactor) {
            float[] ranges = new float[mBubbles.size()];
            for (Bubble bubble : mBubbles) {
                ranges[mBubbles.indexOf(bubble)] = bubble.maxRadius * targetFactor - bubble.currentRadius;
            }

            boolean expansion = ranges[0] > 0;

            SurfaceHolder surfaceHolder = getSurfaceHolder();
            while (mBubbles.get(0).currentRadius != mBubbles.get(0).maxRadius * targetFactor) {
                Canvas canvas = lockHwCanvasIfPossible(surfaceHolder);
                drawCanvasBackground(canvas);
                for (Bubble bubble : mBubbles) {
                    float targetRadius = bubble.maxRadius * targetFactor;
                    float addToRadius = bubble.maxRadius * .05f;
                    float currentRange = targetRadius - bubble.currentRadius;
                    float speedModifier = getSpeedModifier(
                            Math.abs(ranges[mBubbles.indexOf(bubble)]), Math.abs(currentRange));
                    bubble.currentRadius += addToRadius * speedModifier * (expansion ? 1 : -1);
                    bubble.currentRadius = expansion ?
                            Math.min(bubble.currentRadius, targetRadius) :
                            Math.max(bubble.currentRadius, targetRadius);
                }
                drawBubbles(canvas);
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
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

        private Bubble getBubbleInBounds(int x, int y) {
            for (Bubble bubble : mBubbles) {
                if (Math.pow(x - bubble.x, 2) + Math.pow(y - bubble.y, 2) <
                        Math.pow(bubble.maxRadius, 2)) {
                    return bubble;
                }
            }
            return null;
        }

        private Bubble getBubbleFromTouchEvent(MotionEvent event) {
            int x = (int)event.getX();
            int y = (int)event.getY();
            return getBubbleInBounds(x, y);
        }

        private void regenAllBubbles() {
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

            int radius = 0, x = 0, y = 0;
            int overlapCount = 0;

            /* Generate random radii and coordinates until we:
             *  A. Generate dimensions that don't overlap other Bubbles, or
             *  B. Exceed the retry count, in which case we return null
             */
            while (bubbleOverlaps(x, y, radius) || radius == 0) {
                radius = Math.max(random.nextInt(MAX_BUBBLE_RADIUS), MIN_BUBBLE_RADIUS);
                x = Math.max(random.nextInt(mSurfaceDimensions[0] - radius - BUBBLE_PADDING),
                        radius + BUBBLE_PADDING);
                y = Math.max(random.nextInt(mSurfaceDimensions[1] - radius - BUBBLE_PADDING),
                        radius + BUBBLE_PADDING);

                if (++overlapCount > MAX_OVERLAP_RETRY_COUNT) {
                    return null;
                }
            }

            int[] colorPair = getRandomColorPairFromResource();
            return new Bubble(x, y, radius, getBubblePaints(colorPair[0], colorPair[1]));
        }

        private float getSpeedModifier(float range, float toGo) {
            float adjustedRange = range / 2;
            float speedModifier = toGo / adjustedRange;
            if (speedModifier > 1) {
                // Start bringing the modifier back down when we reach half the range
                speedModifier = 2 - speedModifier;
            }
            return Math.max(speedModifier, .001f);
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

        private int[] getRandomColorPairFromResource() {
            String[] colorArray = getResources().getStringArray(R.array.wallpaper_bubble_colors);
            Random random = new Random();

            // Outline color index is even, fill color is after
            int outlineColorIndex = random.nextInt(colorArray.length / 2) * 2;

            return new int[]{Color.parseColor(colorArray[outlineColorIndex]),
                    Color.parseColor(colorArray[++outlineColorIndex])};
        }

        private int getAccentColor() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                // Use holo blue as accent when on Android < Lollipop
                return Color.parseColor("#ff33b5e5");
            }
            TypedValue outValue = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.colorAccent, outValue, true);
            return outValue.data;
        }

        Paint[] getBubblePaints(int outline, int fill) {
            Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setColor(fill);
            fillPaint.setStyle(Paint.Style.FILL);

            Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            outlinePaint.setColor(outline);
            outlinePaint.setStrokeWidth(30);
            outlinePaint.setStyle(Paint.Style.STROKE);

            return new Paint[]{fillPaint, outlinePaint};
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

    private Canvas lockHwCanvasIfPossible(SurfaceHolder surfaceHolder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return surfaceHolder.lockCanvas();
        }
        return surfaceHolder.lockHardwareCanvas();
    }
}