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
 * `mobile/design-tokens/scripts/generate.mjs` already re-derives all 51 WCAG pairings on every
 * build and fails on a regression, so nothing here needs to re-check that `border.interactive` is
 * a good colour. What generate.mjs CANNOT see is the other half of the bug it found: whether the
 * Android side points the right ROLE at the right widget. A token file can be perfect while
 * `OutlinedButton` still draws its only visible edge with a 1.14:1 hairline — that was the actual
 * defect, and it lived entirely on this side of the boundary.
 *
 * So these tests assert role assignment — including at the M3 SLOT level, via
 * [radiusMaterialColorScheme], since every slot-mapping defect found so far (`outline`,
 * `inversePrimary`, `surfaceDim`/`surfaceBright`) was invisible to both the generator and a
 * screenshot — plus contrast floors on the specific roles whose whole reason for existing is a
 * floor.
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

    // -- the inversePrimary tripwire ---------------------------------------------------------

    /**
     * `inversePrimary` is the Snackbar ACTION LABEL, drawn on `inverseSurface` — which we map, so an
     * unmapped value here was baseline M3 purple on our own inverted surface. It is now the
     * `accent.radar.onInverse` ROLE, and this asserts the slot reads that role.
     *
     * HOW MUCH THIS CATCHES, STATED HONESTLY, BECAUSE IT IS LESS THAN IT LOOKS: `onInverse` and
     * `wash` are the same primitive today (`signal/600`), so a revert to `colors.accent.radar.wash`
     * would still pass this assertion — [onInverse and wash are the same value today, deliberately]
     * documents exactly that. What this DOES catch is the whole class of "point it at something
     * plausible": `accent.radar.default` (1.84:1, the obvious wrong answer), a neutral, or a
     * baseline M3 value. And the moment design-system moves either role independently — which is the
     * entire reason the two exist separately — it starts catching the wash revert too.
     */
    @Test
    fun `inversePrimary is the onInverse role, not the accent and not a baseline default`() {
        val colors = RadiusColors.dark
        val scheme = radiusMaterialColorScheme(colors)

        assertEquals(
            "inversePrimary must be accent.radar.onInverse — the role designed and gated for a " +
                "foreground on an inverted surface. It is NOT accent.radar.wash (a background " +
                "role that happens to share the primitive today) and NOT accent.radar.default.",
            RadiusDesignTokens.Color.Accent.Radar.onInverse,
            scheme.inversePrimary,
        )
        assertNotEquals(
            "inversePrimary must never be accent.radar.default — signal/400 measures 1.84:1 on " +
                "inverseSurface, i.e. an invisible Snackbar action label.",
            colors.accent.radar.default,
            scheme.inversePrimary,
        )
    }

    /**
     * The teeth the assertion above cannot have while the two primitives coincide. This one is
     * value-based and independent: whatever `inversePrimary` is pointed at, it must be readable on
     * `inverseSurface`. Computed here with this file's own WCAG implementation, so it holds even if
     * someone bypasses the token layer entirely and drops a literal into the slot.
     */
    @Test
    fun `inversePrimary clears AA text contrast against inverseSurface`() {
        val scheme = radiusMaterialColorScheme(RadiusColors.dark)
        val ratio = contrastRatio(scheme.inversePrimary, scheme.inverseSurface)

        assertTrue(
            "inversePrimary on inverseSurface is " +
                String.format(Locale.ROOT, "%.2f", ratio) + ":1, under AA (4.5). That slot is the " +
                "Snackbar action label — the first thing a user taps after an error.",
            ratio >= 4.5,
        )
    }

    /**
     * Records the coincidence rather than relying on it. `wash` (a background role, measured against
     * our dark surfaces) and `onInverse` (a foreground role, measured against near-white) select the
     * same primitive in all three ramps today, purely because every ramp darkens toward its high
     * stops.
     *
     * WHEN THIS FAILS, IT IS PROBABLY CORRECT AND YOU SHOULD DELETE IT. A failure means design-system
     * moved one role without the other — which is allowed, expected, and the reason they are separate
     * tokens. Do NOT "fix" it by re-aliasing one to the other. What it buys in the meantime is that
     * nobody reads the equal values and concludes the two roles are interchangeable.
     */
    @Test
    fun `onInverse and wash are the same value today, deliberately`() {
        val accents = RadiusColors.dark.accent
        val note = "onInverse and wash have diverged. That is a legitimate design-system change, " +
            "not a regression — delete this test, and note that the inversePrimary role assertion " +
            "just gained real teeth. Do not re-alias the roles to make this pass."

        assertEquals(note, accents.discover.wash, accents.discover.onInverse)
        assertEquals(note, accents.radar.wash, accents.radar.onInverse)
        assertEquals(note, accents.like.wash, accents.like.onInverse)
    }

    // -- the tonal-elevation slots (surfaceDim / surfaceBright) -------------------------------

    /**
     * M3 pairs both of these with `onSurface`, which IS mapped (`content.primary`). Left at M3
     * baseline they are the "mapped foreground on an unmapped background" trap — the foreground half
     * passes its own gate, so nothing fires. They are mapped to the two ends of our own ladder so
     * they inherit pairings generate.mjs already checks (18.36:1 and 14.12:1).
     *
     * Asserting the mapping rather than the ratio is the point: the ratio is the generator's job, and
     * the only way to get it wrong here is to point the slot somewhere unverified.
     */
    @Test
    fun `tonal elevation slots resolve to verified surfaces, not M3 baseline`() {
        val colors = RadiusColors.dark
        val scheme = radiusMaterialColorScheme(colors)

        assertEquals(
            "surfaceDim must be one of OUR surfaces. At M3 baseline it is an off-brand tone that " +
                "content.primary (mapped to onSurface) would land on unwatched.",
            colors.surface.canvas,
            scheme.surfaceDim,
        )
        assertEquals(
            "surfaceBright must be one of OUR surfaces, same reason as surfaceDim.",
            colors.surface.modal,
            scheme.surfaceBright,
        )

        // Both must be pairings the generator actually checks. sunken is ours too, but
        // content.primary-on-sunken is not among the 51 — mapping to it would trade an unwatched
        // background for an unwatched pairing.
        listOf("surfaceDim" to scheme.surfaceDim, "surfaceBright" to scheme.surfaceBright)
            .forEach { (name, value) ->
                assertTrue(
                    "$name must resolve to a surface whose pairing with content.primary is in " +
                        "generate.mjs's checks (canvas/base/raised/overlay/modal).",
                    value in listOf(
                        colors.surface.canvas,
                        colors.surface.base,
                        colors.surface.raised,
                        colors.surface.overlay,
                        colors.surface.modal,
                    ),
                )
            }
    }

    /**
     * The `outline` split, asserted at the SLOT rather than at the token. The original defect was not
     * a bad colour, it was a good colour in the wrong slot — `RadiusColors.border.interactive` was
     * always correct; `outline` just wasn't reading it.
     */
    @Test
    fun `outline slots keep the interactive and decorative split`() {
        val colors = RadiusColors.dark
        val scheme = radiusMaterialColorScheme(colors)

        assertEquals(
            "outline draws OutlinedButton strokes, focus rings and text-field edges — the sole " +
                "visible affordance of a control. It must be border.interactive (SC 1.4.11).",
            colors.border.interactive,
            scheme.outline,
        )
        assertEquals(
            "outlineVariant is for decorative dividers, which are allowed to be quiet.",
            colors.border.hairline,
            scheme.outlineVariant,
        )
        assertEquals(
            "surfaceTint must stay transparent — elevation is surface lightening here, and M3's " +
                "tonal tint would double-apply it and drift our measured ratios.",
            Color.Transparent,
            scheme.surfaceTint,
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

        assertEquals(RadiusDesignTokens.Color.Accent.Radar.wash, colors.accent.radar.wash)
        assertEquals(RadiusDesignTokens.Color.Accent.Radar.onInverse, colors.accent.radar.onInverse)
        assertEquals(RadiusDesignTokens.Color.Accent.Discover.onInverse, colors.accent.discover.onInverse)
        assertEquals(RadiusDesignTokens.Color.Accent.Like.onInverse, colors.accent.like.onInverse)

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
