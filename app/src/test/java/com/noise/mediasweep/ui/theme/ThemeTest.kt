package com.noise.mediasweep.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host-side theme tests (specification Phase 10: dark/light theme). No device or
 * emulator is used (constraint 0.1); dynamic colour is disabled so the branded
 * palette itself is what is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun composedScheme(darkTheme: Boolean?): ColorScheme {
        var scheme: ColorScheme? = null
        composeRule.setContent {
            if (darkTheme == null) {
                // Default parameter: must follow the system setting.
                MediaSweepTheme(dynamicColor = false) { scheme = MaterialTheme.colorScheme }
            } else {
                MediaSweepTheme(darkTheme = darkTheme, dynamicColor = false) {
                    scheme = MaterialTheme.colorScheme
                }
            }
        }
        return requireNotNull(scheme) { "theme was not composed" }
    }

    @Test
    fun `light mode uses the branded light palette`() {
        val scheme = composedScheme(darkTheme = false)

        assertEquals(BackgroundLight, scheme.background)
        assertEquals(OnBackgroundLight, scheme.onBackground)
        assertEquals(SurfaceLight, scheme.surface)
        assertEquals(PrimaryLight, scheme.primary)
        assertEquals(OnPrimaryLight, scheme.onPrimary)
        assertEquals(OutlineLight, scheme.outline)
    }

    @Test
    fun `dark mode uses the branded dark palette`() {
        val scheme = composedScheme(darkTheme = true)

        assertEquals(BackgroundDark, scheme.background)
        assertEquals(OnBackgroundDark, scheme.onBackground)
        assertEquals(SurfaceDark, scheme.surface)
        assertEquals(PrimaryDark, scheme.primary)
        assertEquals(SurfaceContainerDark, scheme.surfaceContainer)
    }

    @Test
    fun `the two modes are visually distinct`() {
        // Guard against a theme that silently renders the same colours in both modes.
        assertNotEquals(BackgroundLight, BackgroundDark)
        assertNotEquals(PrimaryLight, PrimaryDark)
        assertNotEquals(SurfaceContainerLight, SurfaceContainerDark)
    }

    @Test
    @Config(qualifiers = "+night")
    fun `the default theme follows the system dark setting`() {
        val scheme = composedScheme(darkTheme = null)

        assertEquals(BackgroundDark, scheme.background)
        assertEquals(PrimaryDark, scheme.primary)
    }
}
