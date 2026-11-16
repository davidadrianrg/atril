package com.davidadrianrg.atril.core.emulators

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun `numeric versions compare by component`() {
        assertTrue(VersionCompare.isNewer("v1.4.10", "1.4.2"))
        assertFalse(VersionCompare.isNewer("1.4", "1.4.0"))
        assertFalse(VersionCompare.isNewer("1.3.9", "1.4"))
    }

    @Test
    fun `unknown remote version is never newer`() {
        assertFalse(VersionCompare.isNewer("latest", "1.2.3"))
        assertFalse(VersionCompare.isNewer("latest", null))
        assertFalse(VersionCompare.isNewer("nightly", "nightly-old"))
        assertFalse(VersionCompare.isNewer("", "1.0"))
    }

    @Test
    fun `a numeric remote over a non numeric install is reported`() {
        assertTrue(VersionCompare.isNewer("2.0", null))
        assertTrue(VersionCompare.isNewer("2.0", "git"))
    }
}
