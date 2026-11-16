package com.davidadrianrg.atril.core.sync

import com.davidadrianrg.atril.core.sync.SaveWatchState.Companion.shouldResumeOnForeground
import com.davidadrianrg.atril.core.sync.SaveWatchState.Companion.statusKey
import com.davidadrianrg.atril.core.sync.SaveWatchState.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveWatchStateTest {

    private val pausedByLimit = SaveWatchState.Paused(PauseReason.SYSTEM_TIME_LIMIT, 1L)

    @Test
    fun `paused watch resumes when the app comes to the foreground`() {
        assertTrue(shouldResumeOnForeground(watchEnabled = true, syncEnabled = true, state = pausedByLimit))
        assertTrue(shouldResumeOnForeground(true, true, SaveWatchState.Off))
        assertTrue(
            shouldResumeOnForeground(true, true, SaveWatchState.Paused(PauseReason.START_NOT_ALLOWED, 1L)),
        )
    }

    @Test
    fun `running or disabled watch is left alone`() {
        assertFalse(shouldResumeOnForeground(true, true, SaveWatchState.Watching(3)))
        assertFalse(shouldResumeOnForeground(true, true, SaveWatchState.Starting))
        assertFalse(shouldResumeOnForeground(watchEnabled = false, syncEnabled = true, state = pausedByLimit))
        assertFalse(shouldResumeOnForeground(watchEnabled = true, syncEnabled = false, state = pausedByLimit))
    }

    @Test
    fun `status explains a system pause instead of staying silent`() {
        assertEquals("android.sync.watch.status.paused_limit" to null, statusKey(true, pausedByLimit))
        assertEquals(
            "android.sync.watch.status.start_blocked" to null,
            statusKey(true, SaveWatchState.Paused(PauseReason.START_NOT_ALLOWED, 1L)),
        )
        assertEquals("android.sync.watch.status.watching" to 4, statusKey(true, SaveWatchState.Watching(4)))
    }

    @Test
    fun `no status line when the user disabled the watch`() {
        assertNull(statusKey(false, pausedByLimit))
    }
}
