package com.gahs.wallpaper.bubblewall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Random;

public class BubbleWallService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new BubbleWallEngine();
    }

    private class BubbleWallEngine extends Engine {
        BroadcastReceiver mReceiver = new BubbleWallReceiver();
        Handler mHandler = new Handler();
        Runnable mExpansionRunnable = new Runnable() {
            @Override
            public void run() {
                animateBubbleExpansion();
            }
        };
        Runnable mMinimizeRunnable = new Runnable() {
            @Override
            public void run() {
                minimizeBubbles();
            }
        };
        Runnable mMaximizeRunnable = new Runnable() {
            @Override
            public void run() {
                maximizeBubbles();
            }
        };
        ArrayList<Bubble> mBubbles = new ArrayList<>();
        int mUsedBubbleColors;

        private class BubbleWallReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == "android.intent.action.USER_PRESENT") {
                    mHandler.postDelayed(mExpansionRunnable, 250);
                } else if (action == "android.intent.action.SCREEN_OFF") {
                    mHandler.post(mMinimizeRunnable);
                }
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            setOffsetNotificationsEnabled(false);
            setTouchEventsEnabled(false);

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction("android.intent.action.SCREEN_OFF");
            intentFilter.addAction("android.intent.action.USER_PRESENT");
            if (!isPreview()) {
                registerReceiver(mReceiver, intentFilter);
            }

            resetBubbles();
        }

        @Override
        public void onDestroy() {
            if (!isPreview()) {
                unregisterReceiver(mReceiver);
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder surfaceHolder) {
            mHandler.postDelayed(mExpansionRunnable, 500);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
            resetBubbles();
            mHandler.post(mMaximizeRunnable);
        }

        private void resetBubbles() {
            mBubbles.clear();
            int numBubbles = getResources().getInteger(R.integer.number_bubbles);
            for (int x = 0; x < numBubbles; ++x) {
                mBubbles.add(genRandomBubble());
            }
        }

        private Bubble genRandomBubble() {
            Random random = new Random();

            // Display dimensions
            int displayWidth = getResources().getDisplayMetrics().widthPixels;
            int displayHeight = getResources().getDisplayMetrics().heightPixels;

            int radius = Math.max(random.nextInt(250), 10);
            int x = Math.max(random.nextInt(displayWidth - radius), radius + 50);
            int y = Math.max(random.nextInt(displayHeight - radius), radius + 50);

            String[] colorArray = getResources().getStringArray(R.array.wallpaper_bubble_colors);
            if (mUsedBubbleColors + 1 >= colorArray.length) {
                mUsedBubbleColors = 0;
            }
            Log.i("BubbleWall", "mUsedBubbleColors: " + mUsedBubbleColors);
            return new Bubble(x, y, radius, getBubblePaints(colorArray[mUsedBubbleColors++],
                    colorArray[mUsedBubbleColors++]));
        }

        private void animateBubbleExpansion() {
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            boolean bubblesMinimized =
                    mBubbles.get(0).currentRadius == mBubbles.get(0).minimizedRadius;
            bubbleloop: while (true) {
                Canvas canvas = surfaceHolder.lockHardwareCanvas();
                drawCanvasBackground(canvas);
                for (int x = 0; x < mBubbles.size(); ++x) {
                    Bubble bubble = mBubbles.get(x);

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
                    canvas.drawCircle(bubble.x, bubble.y, bubble.currentRadius, bubble.fill);
                    canvas.drawCircle(bubble.x, bubble.y, bubble.currentRadius, bubble.outline);
                }
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

        private void minimizeBubbles() {
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            Canvas canvas = surfaceHolder.lockHardwareCanvas();
            drawCanvasBackground(canvas);
            for (int x = 0; x < mBubbles.size(); ++x) {
                Bubble bubble = mBubbles.get(x);
                bubble.currentRadius = bubble.minimizedRadius;
                canvas.drawCircle(bubble.x, bubble.y, bubble.currentRadius, bubble.fill);
                canvas.drawCircle(bubble.x, bubble.y, bubble.currentRadius, bubble.outline);
            }
            surfaceHolder.unlockCanvasAndPost(canvas);
        }

        private void maximizeBubbles() {
            for (int y = 0; y < 2; ++y) {
                SurfaceHolder surfaceHolder = getSurfaceHolder();
                Canvas canvas = surfaceHolder.lockHardwareCanvas();
                drawCanvasBackground(canvas);
                for (int x = 0; x < mBubbles.size(); ++x) {
                    Bubble bubble = mBubbles.get(x);
                    bubble.currentRadius = bubble.maxRadius;
                    canvas.drawCircle(bubble.x, bubble.y, bubble.currentRadius, bubble.fill);
                    canvas.drawCircle(bubble.x, bubble.y, bubble.currentRadius, bubble.outline);
                }
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }

        private void drawCanvasBackground(Canvas canvas) {
            canvas.drawColor(isNightMode() ? Color.BLACK : Color.argb(255, 215, 215, 215));
        }
    }

    Paint[] getBubblePaints(String outline, String fill) {
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(Color.parseColor(fill));
        fillPaint.setStyle(Paint.Style.FILL);

        Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setColor(Color.parseColor(outline));
        outlinePaint.setStrokeWidth(30);
        outlinePaint.setStyle(Paint.Style.STROKE);

        return new Paint[] {fillPaint, outlinePaint};
    }

    private class Bubble {
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