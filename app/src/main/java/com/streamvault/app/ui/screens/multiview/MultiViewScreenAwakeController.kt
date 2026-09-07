package com.streamvault.app.ui.screens.multiview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}

class MultiViewScreenAwakeController(private val window: Window) {
    private var ownsKeepScreenOn: Boolean = false

    fun acquire() {
        val alreadySet = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (!alreadySet) {
            ownsKeepScreenOn = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            ownsKeepScreenOn = false
        }
    }

    fun release() {
        if (ownsKeepScreenOn) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ownsKeepScreenOn = false
        }
    }

    fun isScreenAwakeOwned(): Boolean = ownsKeepScreenOn
}
