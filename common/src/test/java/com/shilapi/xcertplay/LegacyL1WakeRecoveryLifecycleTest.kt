package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyL1WakeRecoveryLifecycleTest {
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var receiver: BroadcastReceiver? = null
        var filter: IntentFilter? = null
        var unregisters = 0
        override fun getApplicationContext(): Context = this
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            this.receiver = receiver
            this.filter = filter
            return null
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver?) {
            assertSame(this.receiver, receiver)
            unregisters++
            this.receiver = null
        }
        fun screen(action: String) { receiver?.onReceive(this, Intent(action)) }
    }
    @After fun cleanup() { LegacyL1WakeRecovery.cancel() }

    @Test fun screenOffAndNextScreenOnRetainTheMapLifecycleSubscription() {
        val context = RecordingContext()
        LegacyL1WakeRecovery.start(context) { true }
        assertTrue(context.filter!!.hasAction(Intent.ACTION_SCREEN_ON))
        assertTrue(context.filter!!.hasAction(Intent.ACTION_SCREEN_OFF))
        context.screen(Intent.ACTION_SCREEN_OFF)
        assertNotNull(context.receiver)
        assertEquals(0, context.unregisters)
        context.screen(Intent.ACTION_SCREEN_ON)
        assertNotNull(context.receiver)
        assertEquals(0, context.unregisters)
        LegacyL1WakeRecovery.cancel()
        assertNull(context.receiver)
        assertEquals(1, context.unregisters)
    }

    @Test fun nextScreenOnAfterMapOptOutEndsTheSubscription() {
        val context = RecordingContext()
        var wanted = true
        LegacyL1WakeRecovery.start(context) { wanted }
        context.screen(Intent.ACTION_SCREEN_OFF)
        wanted = false
        context.screen(Intent.ACTION_SCREEN_ON)
        assertNull(context.receiver)
        assertEquals(1, context.unregisters)
    }
}
