package com.davidadrianrg.atril.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M4: qué hace Atril con cada intent que le llega estando ya abierto. */
class AppIntentRouterTest {

    private val main = AppIntentRouter.ACTION_MAIN
    private val home = setOf(AppIntentRouter.CATEGORY_HOME, "android.intent.category.DEFAULT")
    private val launcher = setOf("android.intent.category.LAUNCHER")

    @Test
    fun `home button returns to play`() {
        assertEquals(NavRequest.HOME, AppIntentRouter.route(main, home, openUpdates = false, openEmulators = false))
    }

    @Test
    fun `app icon keeps the current screen`() {
        assertNull(AppIntentRouter.route(main, launcher, openUpdates = false, openEmulators = false))
    }

    @Test
    fun `notification extras navigate`() {
        assertEquals(NavRequest.UPDATES, AppIntentRouter.route(null, null, openUpdates = true, openEmulators = false))
        assertEquals(NavRequest.EMULATORS, AppIntentRouter.route(null, null, openUpdates = false, openEmulators = true))
    }

    @Test
    fun `notification extras win over the home category`() {
        assertEquals(NavRequest.EMULATORS, AppIntentRouter.route(main, home, openUpdates = false, openEmulators = true))
    }

    @Test
    fun `unknown intents do nothing`() {
        assertNull(AppIntentRouter.route("android.intent.action.VIEW", null, openUpdates = false, openEmulators = false))
        assertNull(AppIntentRouter.route(null, null, openUpdates = false, openEmulators = false))
    }
}
