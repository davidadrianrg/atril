package com.davidadrianrg.atril.data.local

import com.davidadrianrg.atril.core.library.LibraryPreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La clave de DataStore del estilo de carátula es la canónica de `:core`.
 *
 * Solo se lee la constante del companion: no se construye [SettingsDataStore],
 * no se necesita `Context` ni `EncryptedSharedPreferences` y no se toca ningún
 * DataStore real.
 */
class SettingsCoverStyleKeyTest {

    @Test
    fun `the cover style data store key is the canonical library key`() {
        assertEquals("library_cover_style", SettingsDataStore.LIBRARY_COVER_STYLE.name)
        assertEquals(
            LibraryPreferenceKeys.COVER_STYLE,
            SettingsDataStore.LIBRARY_COVER_STYLE.name,
        )
    }
}
