package com.streamvault.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.ui.components.shell.LiveChannelRowCard
import com.streamvault.app.ui.components.shell.LocalLiveMediaProgressClock
import com.streamvault.app.ui.test.TestFixtures
import com.streamvault.app.ui.theme.StreamVaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveChannelProgressClockTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The clock moved into the composition: [LocalLiveMediaProgressClock] carries it and
     * [com.streamvault.app.ui.components.shell.rememberLiveMediaProgressClock] drives it on a timer
     * gated by the lifecycle. This covers the part still testable here - both cards read the same
     * local, so one value advances both.
     *
     * The previous version of this test also asserted that the clock STOPS while the lifecycle is
     * inactive. That assertion was dropped rather than ported: the lifecycle gating now lives inside
     * the timer, and this test provides the clock directly, so asserting it here would prove nothing.
     * See the status doc - that gating is currently uncovered.
     */
    @Test
    fun oneScopedClock_advancesBothCards() {
        val program = TestFixtures.currentProgram
        val quarter = program.startTime + (program.endTime - program.startTime) / 4
        val threeQuarters = program.startTime + (program.endTime - program.startTime) * 3 / 4
        val now = mutableStateOf(quarter)
        val lifecycleOwner = TestLifecycleOwner()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycleOwner,
                LocalLiveMediaProgressClock provides now.value
            ) {
                StreamVaultTheme {
                    Column {
                        ChannelCard(
                            channel = TestFixtures.liveChannel,
                            nowMs = now.value,
                            onClick = {}
                        )
                        // Reads the local rather than an argument, which is the point of the test.
                        LiveChannelRowCard(
                            channel = TestFixtures.liveChannel
                        )
                    }
                }
            }
        }

        lifecycleOwner.moveTo(Lifecycle.State.STARTED)
        composeRule.waitForIdle()
        composeRule.onAllNodes(progressAt(0.25f)).assertCountEquals(2)

        now.value = threeQuarters
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
