package io.nekohasekai.sagernet.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * LvovFlow — TV Detection Utility
 * Detects if the current device is an Android TV (or TV box like Mi Box, Nvidia Shield, etc.)
 */
object TvUtils {

    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            ?: return false
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
