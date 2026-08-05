package com.radius.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.radius.android.ui.theme.tokens.RadiusDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Guards the WIRING, not the values.
 *
 * `mobile/design-tokens/scripts/generate.mjs` already re-derives all 48 WCAG pairings on every
 * build and fails on a regression, so nothing here needs to re-check that `border.interactive` is
 * a good colour. What generate.mjs CANNOT see is the other half of the bug it found: whether the
 * Android side points the right ROLE at the right widget. A token file can be perfect while
 * `OutlinedButton` still draws its only visible edge with a 1.14:1 hairline — that was the actual
 * defect, and it lived entirely on this side of the boundary.
 *
 * So these tests assert role assignment, plus one contrast floor on the specific role whose whole
 * reason for existing is that floor.
 */
class RadiusThemeTokenWiringTest {

    // -- the finding-#1 tripwire -------------------------------------------------------------

    /**
     * The one that matters. `border.interactive` is the only border role legal as the sole visible
     * edge of a control (WCAG SC 1.4.11, 3:1 non-text). If someone ever "tidies up" the border
     * roles back into one token by eye, this fails before a user has to squint at a button.
     */
    @Test
    fun `interactive border clears the 3 to 1 non-text floor on the surfaces controls sit on`() {
        val colors = RadiusColors.dark

        listOf(
            "canvas" to colors.surface.canvas,
            "base" to colors.surface.base,
            "raised" to colors.surface.raised,
        ).forEach { (name, surface) ->
            val ratio = contrastRatio(colors.border.interactive, surface)
            assertTrue(
                "border.interactive on surface.$name is " +
                    String.format(Locale.ROOT, "%.2f", ratio) + ":1, under the 3.0 " +
                    "non-text floor. A control whose only edge is this colour is invisible to " +
                    "anyone relying on contrast to find it.",
                ratio >= 3.0,
            )
        }
    }

    /**
     * The decorative border roles are KNOWINGLY sub-3:1 — legal only because a content divider is
     * not a UI component. This asserts they are still distinct from the interactive role, i.e. that
     * the three-way split has not silently collapsed back into one value.
     */
    @Test
    fun `decorative border roles are distinct from the interactive one`() {
        val border = RadiusColors.dark.border

        assertNotEquals(
            "border.hairline must never equal border.interactive — that collapse is the exact " +
                "accessibility bug the split exists to prevent.",
            border.interactive,
            border.hairline,
        )
        assertNotEquals(
            "border.subtle must never equal border.interactive.",
            border.interactive,
            border.subtle,
        )
    }

    // -- role mapping ------------------------------------------------------------------------

    @Test
    fun `every colour role maps to the generated token it names`() {
        val colors = RadiusColors.dark

        assertEquals(RadiusDesignTokens.Color.Surface.canvas, colors.surface.canvas)
        assertEquals(RadiusDesignTokens.Color.Surface.sunken, colors.surface.sunken)
        assertEquals(RadiusDesignTokens.Color.Surface.base, colors.surface.base)
        assertEquals(RadiusDesignTokens.Color.Surface.raised, colors.surface.raised)
        assertEquals(RadiusDesignTokens.Color.Surface.overlay, colors.surface.overlay)
        assertEquals(RadiusDesignTokens.Color.Surface.modal, colors.surface.modal)

        assertEquals(RadiusDesignTokens.Color.Content.primary, colors.content.primary)
        assertEquals(RadiusDesignTokens.Color.Content.secondary, colors.content.secondary)
        assertEquals(RadiusDesignTokens.Color.Content.tertiary, colors.content.tertiary)
        assertEquals(RadiusDesignTokens.Color.Content.disabled, colors.content.disabled)
        assertEquals(RadiusDesignTokens.Color.Content.onFill, colors.content.onFill)
        assertEquals(RadiusDesignTokens.Color.Content.onWash, colors.content.onWash)

        assertEquals(RadiusDesignTokens.Color.Border.hairline, colors.border.hairline)
        assertEquals(RadiusDesignTokens.Color.Border.subtle, colors.border.subtle)
        assertEquals(RadiusDesignTokens.Color.Border.interactive, colors.border.interactive)
        assertEquals(RadiusDesignTokens.Color.Border.danger, colors.border.danger)

        assertEquals(RadiusDesignTokens.Color.Accent.Radar.default, colors.accent.radar.default)
        assertEquals(RadiusDesignTokens.Color.Accent.Discover.default, colors.accent.discover.default)
        assertEquals(RadiusDesignTokens.Color.Accent.Like.default, colors.accent.like.default)
        assertEquals(RadiusDesignTokens.Color.Accent.Threads.default, colors.accent.threads)

        assertEquals(RadiusDesignTokens.Color.Status.Success.default, colors.status.success)
        assertEquals(RadiusDesignTokens.Color.Status.Warning.default, colors.status.warning)
        assertEquals(RadiusDesignTokens.Color.Status.Danger.default, colors.status.danger)
        assertEquals(RadiusDesignTokens.Color.Status.Info.default, colors.status.info)
    }

    /**
     * Dark-first is the design, not a default. `light` being null is a deliberate statement that no
     * light-mode variable has been designed; if it ever stops being null, that is a design event
     * that should be noticed here rather than discovered on a device.
     */
    @Test
    fun `light scheme is explicitly absent`() {
        assertNull(RadiusColors.light)
    }

    // -- spacing -----------------------------------------------------------------------------

    /**
     * The old 4/8/16/24/32 ramp was invented locally. This asserts the real Figma 4pt grid, step by
     * step — including the half-step at 6 and the tail (40/64/80) the old scale never had.
     *
     * The expected values are written out as literals ON PURPOSE. A test that reads its expectation
     * from the thing under test proves nothing; this one is the specification.
     */
    @Test
    fun `spacing is Figma's 4pt grid, not the old five-step invention`() {
        val spacing = RadiusSpacing()

        val expected = listOf(
            0f to spacing.space0,
            2f to spacing.space2,
            4f to spacing.space4,
            6f to spacing.space6,
            8f to spacing.space8,
            12f to spacing.space12,
            16f to spacing.space16,
            20f to spacing.space20,
            24f to spacing.space24,
            32f to spacing.space32,
            40f to spacing.space40,
            48f to spacing.space48,
            64f to spacing.space64,
            80f to spacing.space80,
        )

        expected.forEach { (dp, actual) ->
            assertEquals("space$dp", dp, actual.value, 0f)
        }
    }

    /**
     * The touch target is an accessibility FLOOR, not a spacing step. 44dp is the derived minimum;
     * we sit at 48. Asserted independently of which scale step it happens to borrow its value from.
     */
    @Test
    fun `touch target never drops below the accessibility floor`() {
        assertTrue(
            "touchTarget is ${RadiusSpacing().touchTarget}, below the 44dp minimum.",
            RadiusSpacing().touchTarget.value >= 44f,
        )
    }

    // -- type --------------------------------------------------------------------------------

    /**
     * Metrics come from tokens even though the font FILES do not. This catches the failure where
     * someone finally wires Fraunces/Inter and quietly rounds the leading or drops the tracking
     * while they are in there.
     */
    @Test
    fun `type scale carries the token metrics verbatim`() {
        val headingL = radiusTypography.headingL
        assertEquals(22f, headingL.fontSize.value, 0f)
        assertEquals(28f, headingL.lineHeight.value, 0f)
        assertEquals(-0.5f, headingL.letterSpacing.value, 0f)
        assertEquals(600, headingL.fontWeight?.weight)
        assertEquals(RadiusFontFamilies.ui, headingL.fontFamily)

        val displayXl = radiusTypography.displayXl
        assertEquals(44f, displayXl.fontSize.value, 0f)
        assertEquals(48f, displayXl.lineHeight.value, 0f)
        assertEquals(-2f, displayXl.letterSpacing.value, 0f)
        assertEquals(RadiusFontFamilies.display, displayXl.fontFamily)

        // The flagged one. design-system could not confirm whether Figma meant 8px or 8% here, and
        // applied it literally rather than guessing. Asserting the literal value means that if the
        // ambiguity is ever resolved the other way, it is resolved deliberately, in tokens.json,
        // and this test is what makes someone say so out loud.
        assertEquals(8f, radiusTypography.overline.letterSpacing.value, 0f)
        assertTrue(
            "overline's token declares uppercase; call sites must supply uppercased text.",
            RadiusDesignTokens.Type.overline.uppercase,
        )
    }

    // -- WCAG 2.1 relative luminance / contrast ----------------------------------------------
    //
    // Deliberately a second, independent implementation of what generate.mjs does. If both agree,
    // the number is probably right; if they disagree, one of them is wrong and that is worth
    // knowing. Neither is load-bearing on its own.

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
