package com.streamvault.app.ui.screens.multiview

import android.app.Activity
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MultiViewScreenAwakeControllerTest {

    @Test
    fun acquire_whenFlagNotSet_addsFlagAndOwnsIt() {
        val window: Window = mock()
        val attrs = WindowManager.LayoutParams()
        attrs.flags = 0 // flag not set
        whenever(window.attributes).thenReturn(attrs)

        val controller = MultiViewScreenAwakeController(window)
        controller.acquire()

        assertThat(controller.isScreenAwakeOwned()).isTrue()
        verify(window, times(1)).addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        controller.release()
        assertThat(controller.isScreenAwakeOwned()).isFalse()
        verify(window, times(1)).clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun acquire_whenFlagAlreadySet_doesNotOwnOrClearOnRelease() {
        val window: Window = mock()
        val attrs = WindowManager.LayoutParams()
        attrs.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON // already set
        whenever(window.attributes).thenReturn(attrs)

        val controller = MultiViewScreenAwakeController(window)
        controller.acquire()

        assertThat(controller.isScreenAwakeOwned()).isFalse()
        verify(window, never()).addFlags(any())

        controller.release()
        verify(window, never()).clearFlags(any())
    }

    @Test
    fun findActivity_unwrapsNestedContextWrappers() {
        val activity: Activity = mock()
        val wrapper1 = ContextWrapper(activity)
        val wrapper2 = ContextWrapper(wrapper1)

        assertThat(wrapper2.findActivity()).isSameInstanceAs(activity)
    }
}
