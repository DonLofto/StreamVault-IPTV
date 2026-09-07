package com.streamvault.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.ui.components.shell.LiveChannelRowCard
import com.streamvault.app.ui.test.TestFixtures
import com.streamvault.app.ui.theme.StreamVaultTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveChannelProgressClockTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun oneScopedClock_advancesBothCards_andStopsWhileLifecycleIsInactive() {
        val program = TestFixtures.currentProgram
        val quarter = program.startTime + (program.endTime - program.startTime) / 4
        val threeQuarters = program.startTime + (program.endTime - program.startTime) * 3 / 4
        val clock = MutableStateFlow(quarter)
        val lifecycleOwner = TestLifecycleOwner()

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                StreamVaultTheme {
                    val nowMs = rememberLiveChannelProgressNowMs(clock, initialNowMs = quarter)
                    Column {
                        ChannelCard(
                            channel = TestFixtures.liveChannel,
                            nowMs = nowMs,
                            onClick = {}
                        )
                        LiveChannelRowCard(
                            channel = TestFixtures.liveChannel,
                            nowMs = nowMs
                        )
                    }
                }
            }
        }

        lifecycleOwner.moveTo(Lifecycle.State.STARTED)
        composeRule.waitForIdle()
        composeRule.onAllNodes(progressAt(0.25f)).assertCountEquals(2)

        clock.value = threeQuarters
        composeRule.waitForIdle()
        composeRule.onAllNodes(progressAt(0.75f)).assertCountEquals(2)

        lifecycleOwner.moveTo(Lifecycle.State.CREATED)
        clock.value = program.endTime
        composeRule.waitForIdle()
        composeRule.onAllNodes(progressAt(0.75f)).assertCountEquals(2)
    }

    private fun progressAt(value: Float) = hasProgressBarRangeInfo(
        ProgressBarRangeInfo(
            current = value,
            range = 0f..1f,
            steps = 0
        )
    )

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
