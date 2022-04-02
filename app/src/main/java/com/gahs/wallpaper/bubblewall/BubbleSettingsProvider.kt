package com.gahs.wallpaper.bubblewall

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Log
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.slice.Slice
import androidx.slice.SliceProvider
import androidx.slice.builders.*
import androidx.slice.builders.ListBuilder.ICON_IMAGE
import kotlin.random.Random

const val ACTION = "com.gahs.wallpaper.bubblewall.UPDATE_SETTINGS"
const val EXTRA = "com.gahs.wallpaper.bubblewall.THEME"

class BubbleSettingsProvider: SliceProvider() {
    override fun onCreateSliceProvider(): Boolean {
        return true
    }

    override fun onBindSlice(sliceUri: Uri): Slice? {
        return buildSettingsSlice(sliceUri)
    }

    fun buildSettingsSlice(sliceUri: Uri): Slice {
        return list(context!!, sliceUri, ListBuilder.INFINITY) {
            header {
                title = context!!.resources.getString(R.string.wallpaper_color_theme_title)
            }
            gridRow {
                primaryAction = createSettingSliceAction()
                for (i in 0..3) {
                    cell {
                        addImage(getThemeIcon(i == BubbleWallService.selectedPreviewTheme, i), ICON_IMAGE)
                        contentIntent = createSettingIntent(i)
                    }
                }
            }
        }
    }

    fun getThemeIcon(checked: Boolean, iconNumber: Int): IconCompat {
        val buttonArray: TypedArray
        if (checked) {
            buttonArray = context!!.resources.obtainTypedArray(R.array.button_drawables_checked)
        } else {
            buttonArray = context!!.resources.obtainTypedArray(R.array.button_drawables_unchecked)
        }

        return IconCompat.createWithResource(context, buttonArray.getResourceId(iconNumber, 0))
    }

    fun createSettingIntent(buttonNumber: Int): PendingIntent {
        var intent = Intent()
        intent.action = ACTION
        intent.putExtra(EXTRA, buttonNumber)

        return PendingIntent.getBroadcast(context, buttonNumber + 40, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun createSettingSliceAction(): SliceAction {
        return SliceAction.create(createSettingIntent(0), IconCompat.createWithResource(context, R.drawable.abc_ic_arrow_forward), ICON_IMAGE, "Next theme")
    }
}