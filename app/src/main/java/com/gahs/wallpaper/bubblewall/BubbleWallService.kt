package com.gahs.wallpaper.bubblewall

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

import kotlin.math.*
import kotlin.random.Random

class BubbleWallService: WallpaperService() {
    override fun onCreateEngine(): Engine {
        return BubbleWallEngine()
    }

    companion object {
        var selectedPreviewTheme = 0
    }

    private inner class BubbleWallEngine: Engine() {
        private val receiver: BroadcastReceiver = BubbleWallReceiver()
        private val bubbles = ArrayList<Bubble>()
        private var pressedBubble: Bubble? = null
        private var surfaceHeight = 0
        private var surfaceWidth = 0
        private var currentFactor = 0f
        private var baseColor = 0
        private var themeChangePending = false
        private var protectedContext = applicationContext.createDeviceProtectedStorageContext()

        private var minRadius = resources.getInteger(R.integer.bubble_min_radius)
        private var maxRadius = resources.getInteger(R.integer.bubble_max_radius)
        private var bubblePadding = resources.getInteger(R.integer.bubble_padding)
        private var overlapRetryCount = resources.getInteger(R.integer.bubble_overlap_retry_count)

        private inner class BubbleWallReceiver: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                when (action) {
                    @Suppress("DEPRECATION")
                    Intent.ACTION_WALLPAPER_CHANGED -> {
                        themeChangePending = true
                    }
                    ACTION -> {
                        if (isPreview) {
                            val previewTheme = intent.getIntExtra(EXTRA, 0)
                            savePreferenceValue("previewTheme", previewTheme)
                            if (isFirstRun) {
                                savePreferenceValue("theme", previewTheme)
                            }
                            updateTheme(previewTheme)
                            selectedPreviewTheme = previewTheme
                            val sliceUri = Uri.parse("content://com.gahs.wallpaper.bubblewall")
                            context!!.contentResolver.notifyChange(sliceUri, null)
                        }
                    }
                }
            }
        }

        val isFirstRun: Boolean
            get() {
                val packageName = applicationContext.packageName
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                if (wallpaperManager.wallpaperInfo != null) {
                    return wallpaperManager.wallpaperInfo.packageName != packageName
                }
                return true
            }

        fun getPreferenceValue(pref: String, defValue: Int = 0): Int {
            val sharedPrefs = protectedContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return sharedPrefs.getInt(pref, defValue)
        }

        fun savePreferenceValue(pref: String, value: Int) {
            val sharedPrefs = protectedContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            sharedPrefs.edit().putInt(pref, value).apply()
        }

        fun updateTheme(theme: Int) {
            baseColor = getThemeColor(theme)
            notifyColorsChanged()
            changeBubbleColor(baseColor)
            drawBubblesCurrentRadius()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            val intentFilter = IntentFilter()
            intentFilter.addAction(ACTION)
            @Suppress("DEPRECATION")
            intentFilter.addAction(Intent.ACTION_WALLPAPER_CHANGED)

            registerReceiver(receiver, intentFilter)

            selectedPreviewTheme = getPreferenceValue("theme")
        }

        override fun onDestroy() {
            unregisterReceiver(receiver)
        }

        override fun onSurfaceChanged(surfaceHolder: SurfaceHolder, format: Int, width: Int,
                                      height: Int) {
            surfaceHeight = height
            surfaceWidth = width

            baseColor = getThemeColor(getPreferenceValue("theme"))

            regenAllBubbles()
            drawBubblesFactorOfMax(1f)
            drawBubblesFactorOfMax(1f)
        }

        override fun onTouchEvent(event: MotionEvent) {
            // Ignore unwanted touch events
            if (event.action != MotionEvent.ACTION_UP &&
                    event.action != MotionEvent.ACTION_DOWN) {
                return
            }

            if (event.action == MotionEvent.ACTION_DOWN) {
                pressedBubble = getBubbleFromTouchEvent(event)
                if (pressedBubble != null) {
                    drawBubbleTouch(true)
                }
            } else if (pressedBubble != null) {
                drawBubbleTouch(false)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (!isPreview && themeChangePending && visible) {
                // Preview mode ended, save and apply chosen theme
                val previewTheme = getPreferenceValue("previewTheme")
                savePreferenceValue("theme", previewTheme)
                updateTheme(previewTheme)
                themeChangePending = false
            }
        }

        override fun onZoomChanged(zoom: Float) {
            val adjustedZoomLevel = zoom - (zoom * .65f)
            adjustBubbleCoordinates(adjustedZoomLevel)
            drawBubblesFactorOfMax(1 - adjustedZoomLevel)
        }

        override fun onComputeColors(): WallpaperColors? {
            val color = Color.valueOf(baseColor)
            return WallpaperColors(color, color, color)
        }

        private fun drawCanvasBackground(canvas: Canvas,
                                         brightness: Float = if (isNightMode) 0f else 1f,
                                         factor: Float = currentFactor) {
            // Draw grayscale color
            val r = (255 * brightness).roundToInt()
            val g = (255 * brightness).roundToInt()
            val b = (255 * brightness).roundToInt()
            canvas.drawARGB(255, r, g, b)

            // Draw gradient
            currentFactor = factor
            val darkColor = adjustColorAlpha(baseColor, if (isNightMode) .1f else .6f)
            val brightColor = adjustColorAlpha(baseColor, .3f)
            val height = surfaceHeight - surfaceHeight * (factor * .75f)
            val paint = Paint()
            paint.shader = LinearGradient(0f, surfaceHeight.toFloat(), 0f, height, darkColor,
                    brightColor, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), paint)
        }

        private fun drawBubbles(canvas: Canvas) {
            for (bubble in bubbles) {
                // Bubble shadow
                canvas.drawCircle(bubble.shadowX, bubble.shadowY, bubble.shadowRadius,
                        bubble.shadowPaint)

                // Bubble
                canvas.drawCircle(bubble.currentX, bubble.currentY, bubble.currentRadius,
                        bubble.fillPaint)
            }
        }

        private fun drawBubblesCurrentRadius() {
            val surfaceHolder = surfaceHolder
            val canvas = lockHwCanvasIfPossible(surfaceHolder)
            drawCanvasBackground(canvas)
            drawBubbles(canvas)
            surfaceHolder.unlockCanvasAndPost(canvas)
        }

        private fun drawBubblesFactorOfMax(factor: Float) {
            val surfaceHolder = surfaceHolder
            val canvas = lockHwCanvasIfPossible(surfaceHolder)
            drawCanvasBackground(canvas, if (isNightMode) 0f else 1f, factor)
            for (bubble in bubbles) {
                bubble.currentRadius = bubble.baseRadius * factor
            }
            drawBubbles(canvas)
            surfaceHolder.unlockCanvasAndPost(canvas)
        }

        private fun drawUiModeTransition() {
            val surfaceHolder = surfaceHolder
            for (x in if (isNightMode) 20 downTo 0 else 0..20) {
                val canvas = lockHwCanvasIfPossible(surfaceHolder)
                val brightness = x / 20f
                drawCanvasBackground(canvas, brightness)
                drawBubbles(canvas)
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawBubbleTouch(expand: Boolean) {
            val surfaceHolder = surfaceHolder
            for (x in 0..4) {
                val canvas = lockHwCanvasIfPossible(surfaceHolder)
                drawCanvasBackground(canvas)
                pressedBubble!!.currentRadius += if (expand) 1f else -1f
                drawBubbles(canvas)
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
            if (!expand) {
                pressedBubble = null
            }
        }

        private fun getBubbleInBounds(x: Int, y: Int): Bubble? {
            for (bubble in bubbles) {
                if ((x - bubble.baseX.toDouble()).pow(2) + (y - bubble.baseY.toDouble()).pow(2) <
                        bubble.baseRadius.toDouble().pow(2)) {
                    return bubble
                }
            }
            return null
        }

        private fun getBubbleFromTouchEvent(event: MotionEvent): Bubble? {
            val x = event.x.toInt()
            val y = event.y.toInt()
            return getBubbleInBounds(x, y)
        }

        private fun adjustBubbleCoordinates(factor: Float) {
            for (bubble in bubbles) {
                // Convert Bubble coordinates into coordinate plane compatible coordinates
                val halfWidth = surfaceWidth / 2
                val halfHeight = surfaceHeight / 2

                var distanceFromXInt = halfWidth - bubble.baseX.toFloat()
                if (distanceFromXInt < halfWidth) distanceFromXInt *= -1f

                var distanceFromYInt = halfHeight - bubble.baseY.toFloat()
                if (distanceFromYInt < halfHeight) distanceFromYInt *= -1f

                bubble.currentX = bubble.baseX - distanceFromXInt * factor
                bubble.currentY = bubble.baseY - distanceFromYInt * factor
            }
        }

        private fun regenAllBubbles() {
            bubbles.clear()
            while (true) {
                val bubble = genRandomBubble()
                if (bubble != null) {
                    bubbles.add(bubble)
                } else {
                    // If bubble is null, the max overlap retry count was
                    // exceeded, stop adding bubbles
                    break
                }
            }
        }

        private fun genRandomBubble(): Bubble? {
            var radius = 0
            var x = 0
            var y = 0
            var overlapCount = 0

            /* Generate random radii and coordinates until we:
             *  A. Generate dimensions that don't overlap other Bubbles, or
             *  B. Exceed the retry count, in which case we return null
             */
            while (bubbleOverlaps(x, y, radius) || radius == 0) {
                radius = Random.nextInt(minRadius, maxRadius)
                x = Random.nextInt(radius + bubblePadding,surfaceWidth - radius - bubblePadding)
                y = Random.nextInt(radius + bubblePadding,surfaceHeight - radius - bubblePadding)
                if (++overlapCount > overlapRetryCount) {
                    return null
                }
            }
            return Bubble(x, y, radius, baseColor)
        }

        private fun bubbleOverlaps(x: Int, y: Int, radius: Int): Boolean {
            for (bubble in bubbles) {
                val distance = sqrt((x - bubble.baseX.toDouble()).pow(2)
                        + (y - bubble.baseY.toDouble()).pow(2))
                if (distance < radius + bubble.baseRadius + bubblePadding) {
                    return true
                }
            }
            return false
        }

        fun getThemeColor(theme: Int): Int {
            val colorArray = baseContext!!.resources.obtainTypedArray(R.array.theme_colors)
            val themeColor = colorArray.getResourceId(theme, 0)
            return ContextCompat.getColor(baseContext!!, themeColor)
        }

        fun changeBubbleColor(color: Int) {
            for (bubble in bubbles) {
                bubble.baseColor = color
            }
        }
    }

    private inner class Bubble constructor(
            var baseX: Int,
            var baseY: Int,
            var baseRadius: Int,
            var color: Int) {

        var currentX = baseX.toFloat()
        var currentY = baseY.toFloat()
        var currentRadius = baseRadius.toFloat()
        var baseColor = color

        val shadowX: Float
            get() = currentX + currentRadius / 5

        val shadowY: Float
            get() = currentY + currentRadius / 5

        val shadowRadius: Float
            get() = currentRadius * .9f

        val fillPaint: Paint
            get() {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val coordOffset = currentRadius * .5f
                // Use brighter color in light theme to minimize contrast
                val color = adjustColorBrightness(baseColor, if (isNightMode) .5f else .75f)
                paint.shader = RadialGradient(currentX - coordOffset, currentY - coordOffset,
                        currentRadius * 2f, baseColor, color, Shader.TileMode.REPEAT)
                return paint
            }

        val shadowPaint: Paint
            get() {
                val paint = Paint()
                paint.shader = RadialGradient(shadowX, shadowY, shadowRadius, Color.BLACK,
                        Color.TRANSPARENT, Shader.TileMode.CLAMP)
                return paint
            }
    }

    private val isNightMode: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES


    private fun lockHwCanvasIfPossible(surfaceHolder: SurfaceHolder): Canvas {
        return surfaceHolder.lockHardwareCanvas()
    }

    @ColorInt
    private fun adjustColorAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt()
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }

    @ColorInt
    private fun adjustColorBrightness(color: Int, factor: Float): Int {
        val alpha = Color.alpha(color)
        val red = (Color.red(color) * factor).toInt()
        val green = (Color.green(color) * factor).toInt()
        val blue = (Color.blue(color) * factor).toInt()
        return Color.argb(alpha, red, green, blue)
    }
}