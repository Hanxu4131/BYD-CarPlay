package com.shilapi.xcertplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class FirstRunPermissionControllerTest {
    class Entry : ComponentActivity() {
        // Matches the real launcher: result launchers register before Activity has a base Context.
        internal val permissions = FirstRunPermissionController(this, { it() }, {})
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            permissions.prepare(savedInstanceState)
        }
        override fun onDestroy() {
            permissions.close()
            super.onDestroy()
        }
    }

    @Test fun controllerCanBeConstructedBeforeAttachAndDoesNotReopenForConfiguredUser() {
        val first = Robolectric.buildActivity(Entry::class.java).setup()
        first.get().getSharedPreferences("diplay", 0).edit().putString("language", "zh").commit()
        first.pause().stop().destroy()
        val next = Robolectric.buildActivity(Entry::class.java).setup()
        assertFalse(next.get().permissions.active)
        next.pause().stop().destroy()
    }
}
