package com.radius.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.radius.android.ui.theme.tokens.RadiusDesignTokens

/**
 * ================================ READ THIS BEFORE USING ================================
 *
 * These are REAL TOKENS. The placeholder hex that used to live in this file is gone, and no hex
 * literal exists anywhere under mobile/android/ any more — not even a vendored copy of the
 * generated token file.
 *
 * [RadiusDesignTokens] is GENERATED AT BUILD TIME from `mobile/design-tokens/tokens.json` by
 * design-system's `scripts/generate.mjs`, into `mobile/android/build/generated/` (gitignored). See
 * the long comment in `mobile/android/build.gradle.kts` for why generation beat vendoring a copy.
 * Practical consequence: `tokens.json` is the ONLY place a colour value exists, and it is not
 * mine — a value change is a design-system change, and it arrives here on the next build with no
 * action from me. That is the property the whole indirection was for.
 *
 * Side effect worth knowing: generate.mjs re-derives all 51 documented WCAG contrast pairings and
 * exits non-zero on regression, so `:android:assembleDebug` now FAILS on a contrast regression.
 * (48 at the first drop; +3 when `accent.*.onInverse` was added to cover M3's inverseSurface.)
 *
 * Material 3 is used as a LAYOUT ENGINE here. Its component behaviour, touch targets and
 * accessibility semantics are excellent and free. Its default look is not ours and is overridden
 * below, slot by slot.
 * ========================================================================================
 */

// -------------------------------------------------------------------------------------------
// COLOUR — organised by ROLE, mirroring tokens.json exactly. Screens name the role, never the hue.
// -------------------------------------------------------------------------------------------

/** The elevation ladder. On dark, elevation is surface LIGHTENING first, shadow second. */
@Immutable
public data class RadiusSurfaces(
    /** App background (elev/0). */
    val canvas: Color,
    /** Recessed wells: text-input fill, mono blocks. */
    val sunken: Color,
    /** Default content surface (elev/1). */
    val base: Color,
    /** Cards, chips, tiles (elev/2). */
    val raised: Color,
    /** Menus, tooltips, transient overlays (elev/3). */
    val overlay: Color,
    /** Sheets, dialogs, modals (elev/4). */
    val modal: Color,
)

/** Text and icon roles. */
@Immutable
public data class RadiusContentColors(
    /** Default text/icon. AAA on every surface (14.1–18.4:1). */
    val primary: Color,
    /** De-emphasised but still informational. AA on every surface (5.6–7.3:1). */
    val secondary: Color,
    /**
     * Least-emphasis READABLE text. AA only on [RadiusSurfaces.canvas] and [RadiusSurfaces.base]
     * (4.69 / 4.52:1). On raised/overlay/modal it drops to 4.29 / 3.98 / 3.60:1 — large text and
     * icons only there, never small body copy.
     */
    val tertiary: Color,
    /**
     * DISABLED STATE ONLY. 2.48–2.71:1 — fails AA text (4.5) and the non-text floor (3.0) on every
     * surface. It is legal solely under WCAG's disabled-control exemption.
     *
     * READ THIS BEFORE YOU REACH FOR IT: it must never carry text meant to be read as live
     * information, and it must never be the SOLE disabled cue — `enabled = false` semantics and the
     * absence of interactivity carry the meaning; this colour only agrees with them. If you find
     * yourself wanting this for secondary text, you want [secondary] or [tertiary]; if neither
     * looks right, the design needs a value that does not exist yet and that is a design-system
     * conversation, not a contrast rule to route around.
     */
    val disabled: Color,
    /** Text/icon drawn ON a saturated accent or status FILL. Verified 5.14–9.96:1 on all seven. */
    val onFill: Color,
    /** Text/icon drawn ON an accent WASH surface. Verified 5.20–7.02:1 on all three. */
    val onWash: Color,
)

/**
 * Border roles. THIS SPLIT IS AN ACCESSIBILITY CONTROL, NOT A NAMING PREFERENCE.
 *
 * Every ink step dark enough to *look* like a quiet hairline measures 1.14–1.49:1 against
 * canvas/base — far under SC 1.4.11's 3:1 non-text floor. That is fine for a decorative divider
 * (not a UI component) and fatal for the only visible edge of something you are meant to tap. The
 * only ink step that clears 3:1 is three steps lighter than instinct says it should be.
 *
 * So: [hairline] and [subtle] are DECORATIVE and knowingly sub-3:1. [interactive] is the only value
 * legal as the sole edge of a control. Picking by eye is how this bug comes back.
 */
@Immutable
public data class RadiusBorders(
    /** Decorative dividers only. 1.14–1.18:1. Never the edge of a control. */
    val hairline: Color,
    /** Resting container border. 1.44–1.49:1 — must ALWAYS pair with a fill/elevation difference. */
    val subtle: Color,
    /** Sole edge of a tappable/focusable control: inputs, OutlinedButton, focus rings. 4.52–4.69:1. */
    val interactive: Color,
    /** Error-state field border. 5.14–5.33:1. */
    val danger: Color,
)

/** An accent with its full four-role ramp. */
@Immutable
public data class RadiusAccent(
    val default: Color,
    val pressed: Color,
    /** Tinted background for the accent. Pair with [RadiusContentColors.onWash], never the accent itself. */
    val wash: Color,
    /**
     * Accent-coloured FOREGROUND for text/icons drawn on an INVERTED surface — M3's `inverseSurface`,
     * which we map to [RadiusContentColors.primary] (near-white). The Snackbar action label is the
     * live consumer. Verified 5.20–7.02:1 across the three ramps, gated by generate.mjs.
     *
     * IT IS NOT [wash], EVEN WHERE THE HEX MATCHES. Every ramp currently darkens toward its high
     * stops, so "dark enough to read as a quiet wash near a dark canvas" and "dark enough to read as
     * text on a near-white surface" happen to select the same primitive. That is a coincidence of
     * today's ramps, not a rule — the two roles are measured against different backgrounds and are
     * allowed to diverge. Wiring one to the other is how a Snackbar breaks when a wash tier moves for
     * reasons that have nothing to do with Snackbars.
     */
    val onInverse: Color,
)

/**
 * Four accents, each scoped to a MODE or a MOMENT. They replace one another per screen; they never
 * co-occur as competing primaries on the same screen.
 */
@Immutable
public data class RadiusAccents(
    /** DISCOVER mode. ember. */
    val discover: RadiusAccent,
    /** RADAR mode. signal/teal, RESERVED for Radar/BLE/offline — reusing it elsewhere breaks the model. */
    val radar: RadiusAccent,
    /** The like/match moment specifically. Replaces ember as the one accent on such a screen. */
    val like: RadiusAccent,
    /**
     * THREADS. Deliberately a neutral ink tone and not a hue: one inbox carrying both origins should
     * not be promoted to a third competing mode colour. One stop only, by design — there is nothing
     * to add a pressed/wash tier from.
     *
     * There is also no `threads.onInverse`, and that is a refusal rather than a gap: with no Threads
     * ramp there is no Threads hue to pick a foreground stop from, and any ink step dark enough to
     * clear AA on an inverted surface would be generic dark-neutral text wearing an accent role's
     * name. Recorded as `$onInverseRefused` in tokens.json. Do not fill it locally — if Threads ever
     * gets a real ramp, the role becomes fillable and that is design-system's call.
     */
    val threads: Color,
)

/**
 * Status colours. ONE STOP EACH, and that is deliberate on design-system's side, not an omission:
 * Figma bound only a `/400` for each, so no pressed or wash tier can exist without inventing hex.
 *
 * If you need a pressed state for a status-coloured control, RAISE IT. Do not interpolate one —
 * an interpolated value is a fifth palette nobody reviewed, and this is the exact failure mode the
 * token drop existed to end.
 */
@Immutable
public data class RadiusStatusColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
)

@Immutable
public data class RadiusColors(
    val surface: RadiusSurfaces,
    val content: RadiusContentColors,
    val border: RadiusBorders,
    val accent: RadiusAccents,
    val status: RadiusStatusColors,
) {
    public companion object {
        /** The designed scheme. Dark is not a variant here — it is the design. */
        public val dark: RadiusColors = RadiusColors(
            surface = RadiusSurfaces(
                canvas = RadiusDesignTokens.Color.Surface.canvas,
                sunken = RadiusDesignTokens.Color.Surface.sunken,
                base = RadiusDesignTokens.Color.Surface.base,
                raised = RadiusDesignTokens.Color.Surface.raised,
                overlay = RadiusDesignTokens.Color.Surface.overlay,
                modal = RadiusDesignTokens.Color.Surface.modal,
            ),
            content = RadiusContentColors(
                primary = RadiusDesignTokens.Color.Content.primary,
                secondary = RadiusDesignTokens.Color.Content.secondary,
                tertiary = RadiusDesignTokens.Color.Content.tertiary,
                disabled = RadiusDesignTokens.Color.Content.disabled,
                onFill = RadiusDesignTokens.Color.Content.onFill,
                onWash = RadiusDesignTokens.Color.Content.onWash,
            ),
            border = RadiusBorders(
                hairline = RadiusDesignTokens.Color.Border.hairline,
                subtle = RadiusDesignTokens.Color.Border.subtle,
                interactive = RadiusDesignTokens.Color.Border.interactive,
                danger = RadiusDesignTokens.Color.Border.danger,
            ),
            accent = RadiusAccents(
                discover = RadiusAccent(
                    default = RadiusDesignTokens.Color.Accent.Discover.default,
                    pressed = RadiusDesignTokens.Color.Accent.Discover.pressed,
                    wash = RadiusDesignTokens.Color.Accent.Discover.wash,
                    onInverse = RadiusDesignTokens.Color.Accent.Discover.onInverse,
                ),
                radar = RadiusAccent(
                    default = RadiusDesignTokens.Color.Accent.Radar.default,
                    pressed = RadiusDesignTokens.Color.Accent.Radar.pressed,
                    wash = RadiusDesignTokens.Color.Accent.Radar.wash,
                    onInverse = RadiusDesignTokens.Color.Accent.Radar.onInverse,
                ),
                like = RadiusAccent(
                    default = RadiusDesignTokens.Color.Accent.Like.default,
                    pressed = RadiusDesignTokens.Color.Accent.Like.pressed,
                    wash = RadiusDesignTokens.Color.Accent.Like.wash,
                    onInverse = RadiusDesignTokens.Color.Accent.Like.onInverse,
                ),
                threads = RadiusDesignTokens.Color.Accent.Threads.default,
            ),
            status = RadiusStatusColors(
                success = RadiusDesignTokens.Color.Status.Success.default,
                warning = RadiusDesignTokens.Color.Status.Warning.default,
                danger = RadiusDesignTokens.Color.Status.Danger.default,
                info = RadiusDesignTokens.Color.Status.Info.default,
            ),
        )

        /**
         * NOT AN OVERSIGHT AND NOT A TODO WITH A DATE ON IT.
         *
         * Figma Foundations has not bound a single light-mode variable. Fabricating one here would
         * be exactly the thing this file just stopped doing. Note the direction, because it matters
         * for how a light theme gets designed if it ever is: dark-first does not mean "a dark
         * variant of a light theme" — the light theme would be the variant.
         *
         * The shape is ready. The values are not. [RadiusTheme] forcing dark is therefore honest,
         * not a shortcut.
         */
        public val light: RadiusColors? = null
    }
}

// -------------------------------------------------------------------------------------------
// SPACING — Figma's real 4pt grid.
// -------------------------------------------------------------------------------------------

/**
 * The 4pt grid, verbatim from Foundations. Note it is not a pure multiple-of-4 ramp: `space6` is a
 * deliberate half-step.
 *
 * THE OLD `xs/sm/md/lg/xl` RAMP (4/8/16/24/32) IS GONE AND WAS NOT MAPPED ONTO THESE NAMES. It was
 * invented locally, it was missing the 2/6/12/20/40/64/80 steps, and quietly aliasing `md` to
 * `space16` would have preserved a second, unreviewed scale under a friendly name — which is the
 * precise failure this token drop existed to end. Call sites now name the step.
 *
 * Why this matters beyond tidiness: at 200% font scale a hardcoded inline `24.dp` is how content
 * clips. Steps get audited in one place, and that place is `tokens.json`.
 */
@Immutable
public data class RadiusSpacing(
    val space0: Dp = RadiusDesignTokens.Spacing.space0,
    val space2: Dp = RadiusDesignTokens.Spacing.space2,
    val space4: Dp = RadiusDesignTokens.Spacing.space4,
    val space6: Dp = RadiusDesignTokens.Spacing.space6,
    val space8: Dp = RadiusDesignTokens.Spacing.space8,
    val space12: Dp = RadiusDesignTokens.Spacing.space12,
    val space16: Dp = RadiusDesignTokens.Spacing.space16,
    val space20: Dp = RadiusDesignTokens.Spacing.space20,
    val space24: Dp = RadiusDesignTokens.Spacing.space24,
    val space32: Dp = RadiusDesignTokens.Spacing.space32,
    val space40: Dp = RadiusDesignTokens.Spacing.space40,
    val space48: Dp = RadiusDesignTokens.Spacing.space48,
    val space64: Dp = RadiusDesignTokens.Spacing.space64,
    val space80: Dp = RadiusDesignTokens.Spacing.space80,
    /**
     * Minimum touch target. NOT a spacing step and not a Figma token — it is an accessibility
     * FLOOR, and it lives here because it is a layout constant our screens must reach for.
     *
     * It takes its value from the scale rather than restating `48.dp`, so there is still no
     * magic number in this module; it happens to coincide with `space48`. Never go below it,
     * whatever a mock says.
     */
    val touchTarget: Dp = RadiusDesignTokens.Spacing.space48,
)

/** Corner radii, by component class. */
@Immutable
public data class RadiusRadii(
    /** Badges. */
    val xs: Dp = RadiusDesignTokens.Radius.xs,
    /** Inputs, small buttons. */
    val s: Dp = RadiusDesignTokens.Radius.s,
    /** Buttons, cards. */
    val m: Dp = RadiusDesignTokens.Radius.m,
    /** Cards, tiles, message bubbles. */
    val l: Dp = RadiusDesignTokens.Radius.l,
    /** Sheets, modals. */
    val xl: Dp = RadiusDesignTokens.Radius.xl,
    /** Hero media. */
    val xxl: Dp = RadiusDesignTokens.Radius.xxl,
    /** Avatars, pills, FABs. */
    val full: Dp = RadiusDesignTokens.Radius.full,
)

// -------------------------------------------------------------------------------------------
// TYPE
// -------------------------------------------------------------------------------------------

/**
 * FONT FILES ARE NOT BUNDLED, AND I DID NOT ADD A DEPENDENCY TO FIX THAT.
 *
 * The token type scale names two families: Fraunces (display) and Inter (interface). Both are OFL,
 * so licensing is not the obstacle. Getting them into the APK is: either bundled `.ttf` resources
 * (~+400KB, offline-safe, deterministic) or `androidx.compose.ui:ui-text-google-fonts` +
 * Downloadable Fonts (smaller APK, adds a Google Play Services dependency and a network path, and
 * silently falls back on devices without GMS — which for a Tencent/SEA-region product is not a
 * rounding error). Either one is a new resource/dependency decision, and ORCHESTRATION §8 says
 * that is escalated, not decided unilaterally inside a token-wiring task.
 *
 * So the METRICS below are real tokens — every size, leading and tracking value is exactly what
 * `tokens.json` specifies — and only the glyphs are a fallback. `display` maps to the platform
 * serif and `ui` to the platform sans, which preserves the serif/sans contrast the scale is built
 * around without pretending to be the brand faces.
 *
 * DO NOT read a rendered screenshot as "type is done". It is not. Escalated in the HANDOFF.
 */
public object RadiusFontFamilies {
    /** UNBRANDED FALLBACK for Fraunces. */
    public val display: FontFamily = FontFamily.Serif

    /** UNBRANDED FALLBACK for Inter. */
    public val ui: FontFamily = FontFamily.SansSerif

    internal fun forLogicalName(logical: String): FontFamily = when (logical) {
        "display" -> display
        "ui" -> ui
        // Loud on purpose: a new logical family in tokens.json must not silently render as sans.
        else -> error("Unknown token font family \"$logical\". Map it in RadiusFontFamilies.")
    }
}

/**
 * The full type ramp as Compose [TextStyle]s. Sixteen roles; M3's [Typography] only has fifteen
 * slots and none of them mean "overline" or "mono", so the ramp is exposed here in full and mapped
 * onto M3 separately for stock components.
 */
@Immutable
public data class RadiusTypography(
    val displayXl: TextStyle,
    val displayL: TextStyle,
    val displayM: TextStyle,
    val displayS: TextStyle,
    val headingL: TextStyle,
    val headingM: TextStyle,
    val headingS: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val bodyS: TextStyle,
    val labelL: TextStyle,
    val labelM: TextStyle,
    val labelS: TextStyle,
    val caption: TextStyle,
    /**
     * The token declares `uppercase = true` and 8sp of tracking. Compose has no text-transform, so
     * the CALL SITE must uppercase the string — and must do it in the resource, or via a locale-
     * aware transform, never `String.uppercase()` with the default locale (Turkish dotless-i).
     *
     * design-system flagged the tracking value itself as unconfirmed (8 is a 5x jump from the next
     * step down; it is plausible as px for an editorial eyebrow label and equally plausible as a
     * percent-of-size figure that would land near 0.9sp). It is applied literally. Confirm against
     * live Figma before this style carries anything load-bearing.
     */
    val overline: TextStyle,
    val monoS: TextStyle,
)

private fun RadiusDesignTokens.TypeSpec.toTextStyle(): TextStyle = TextStyle(
    fontFamily = RadiusFontFamilies.forLogicalName(family),
    fontWeight = FontWeight(weight),
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    // includeFontPadding = false: the legacy Android padding predates the token leading values and
    // would add unspecified space on top of them, which is exactly how a 200%-font-scale layout
    // ends up taller than anyone measured.
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Internal rather than private so the wiring test can assert the metrics without a composition. */
internal val radiusTypography = RadiusTypography(
    displayXl = RadiusDesignTokens.Type.displayXl.toTextStyle(),
    displayL = RadiusDesignTokens.Type.displayL.toTextStyle(),
    displayM = RadiusDesignTokens.Type.displayM.toTextStyle(),
    displayS = RadiusDesignTokens.Type.displayS.toTextStyle(),
    headingL = RadiusDesignTokens.Type.headingL.toTextStyle(),
    headingM = RadiusDesignTokens.Type.headingM.toTextStyle(),
    headingS = RadiusDesignTokens.Type.headingS.toTextStyle(),
    bodyL = RadiusDesignTokens.Type.bodyL.toTextStyle(),
    bodyM = RadiusDesignTokens.Type.bodyM.toTextStyle(),
    bodyS = RadiusDesignTokens.Type.bodyS.toTextStyle(),
    labelL = RadiusDesignTokens.Type.labelL.toTextStyle(),
    labelM = RadiusDesignTokens.Type.labelM.toTextStyle(),
    labelS = RadiusDesignTokens.Type.labelS.toTextStyle(),
    caption = RadiusDesignTokens.Type.caption.toTextStyle(),
    overline = RadiusDesignTokens.Type.overline.toTextStyle(),
    monoS = RadiusDesignTokens.Type.monoS.toTextStyle(),
)

/**
 * Our ramp projected onto M3's fifteen slots, so stock components (Button, NavigationBarItem,
 * Snackbar) inherit real type without every call site re-specifying it.
 *
 * The mapping is deliberate, not alphabetical: M3's `display*` are our editorial serif sizes;
 * `headline*` step down through the interface headings; `title*` are the semibold interface labels
 * screens actually use for row and section headers. `caption`, `overline` and `monoS` have no M3
 * home and are reachable only via `RadiusTheme.type`.
 */
private val materialTypography = Typography(
    displayLarge = radiusTypography.displayXl,
    displayMedium = radiusTypography.displayL,
    displaySmall = radiusTypography.displayM,
    headlineLarge = radiusTypography.displayS,
    headlineMedium = radiusTypography.headingL,
    headlineSmall = radiusTypography.headingM,
    titleLarge = radiusTypography.headingS,
    titleMedium = radiusTypography.labelL,
    titleSmall = radiusTypography.labelM,
    bodyLarge = radiusTypography.bodyL,
    bodyMedium = radiusTypography.bodyM,
    bodySmall = radiusTypography.bodyS,
    labelLarge = radiusTypography.labelL,
    labelMedium = radiusTypography.labelM,
    labelSmall = radiusTypography.labelS,
)

// -------------------------------------------------------------------------------------------
// THEME
// -------------------------------------------------------------------------------------------

public val LocalRadiusColors: ProvidableCompositionLocal<RadiusColors> =
    staticCompositionLocalOf { RadiusColors.dark }

public val LocalRadiusSpacing: ProvidableCompositionLocal<RadiusSpacing> =
    staticCompositionLocalOf { RadiusSpacing() }

public val LocalRadiusRadii: ProvidableCompositionLocal<RadiusRadii> =
    staticCompositionLocalOf { RadiusRadii() }

public val LocalRadiusTypography: ProvidableCompositionLocal<RadiusTypography> =
    staticCompositionLocalOf { radiusTypography }

/**
 * Accessor object, mirroring `MaterialTheme`'s shape so it reads naturally at call sites:
 * `RadiusTheme.colors.accent.radar.default`, `RadiusTheme.spacing.space16`.
 */
public object RadiusTheme {
    public val colors: RadiusColors
        @Composable @ReadOnlyComposable get() = LocalRadiusColors.current

    public val spacing: RadiusSpacing
        @Composable @ReadOnlyComposable get() = LocalRadiusSpacing.current

    public val radii: RadiusRadii
        @Composable @ReadOnlyComposable get() = LocalRadiusRadii.current

    public val type: RadiusTypography
        @Composable @ReadOnlyComposable get() = LocalRadiusTypography.current
}

/**
 * Our tokens projected onto M3's colour slots, so stock components inherit the right look without
 * every call site re-specifying colours.
 *
 * NOT INLINE IN [RadiusTheme], AND NOT PRIVATE, ON PURPOSE. Slot mapping is where the two real bugs
 * in this file have lived so far (`outline` drawn with a 1.14:1 hairline; `inversePrimary` shipping
 * baseline M3 purple onto a mapped inverseSurface). Both were assignment errors, invisible to the
 * token generator and to a screenshot alike. A plain function is unit-testable without a composition
 * or Robolectric, so the wiring test can assert slot-by-slot that the right ROLE reaches the right
 * widget — which is the half of the problem generate.mjs structurally cannot see.
 */
internal fun radiusMaterialColorScheme(colors: RadiusColors): ColorScheme =
    darkColorScheme(
        primary = colors.accent.radar.default,
        onPrimary = colors.content.onFill,
        primaryContainer = colors.accent.radar.wash,
        onPrimaryContainer = colors.content.onWash,
        secondary = colors.accent.discover.default,
        onSecondary = colors.content.onFill,
        secondaryContainer = colors.accent.discover.wash,
        onSecondaryContainer = colors.content.onWash,
        tertiary = colors.accent.like.default,
        onTertiary = colors.content.onFill,
        tertiaryContainer = colors.accent.like.wash,
        onTertiaryContainer = colors.content.onWash,
        background = colors.surface.canvas,
        onBackground = colors.content.primary,
        surface = colors.surface.base,
        onSurface = colors.content.primary,
        surfaceVariant = colors.surface.raised,
        onSurfaceVariant = colors.content.secondary,
        surfaceContainerLowest = colors.surface.sunken,
        surfaceContainerLow = colors.surface.base,
        surfaceContainer = colors.surface.raised,
        surfaceContainerHigh = colors.surface.overlay,
        surfaceContainerHighest = colors.surface.modal,

        // ---- THE SAME SHAPE AS inversePrimary, CAUGHT BEFORE IT SHIPPED. ----
        // surfaceDim/surfaceBright are the two ends of M3's tonal-elevation range, reached by
        // `Surface(tonalElevation = …)` and some Scaffold/sheet internals. No component in our
        // current screen set draws on them — but M3 pairs them with `onSurface`, and `onSurface` IS
        // mapped (content.primary). Left unmapped, the first component that reaches for one puts a
        // VERIFIED foreground on an UNVERIFIED, off-brand M3 baseline background, and nothing
        // catches it, because the foreground half passes on its own. "Unmapped" only stays safe
        // while BOTH halves of a pairing are unmapped.
        //
        // So they take the two ends of our own ladder: canvas is the dimmest broad surface we have,
        // modal the brightest. Both pairings against content.primary are already in the generator's
        // 51 (18.36:1 and 14.12:1), so this slot inherits a GATED pairing instead of one nobody can
        // compute — which also means design-system never has to publish a ratio against an M3
        // baseline hex it could not read. The question stops existing rather than getting a guessed
        // answer.
        //
        // NOT sunken, though it is darker (ink/1000 vs ink/950): content.primary-on-sunken is not
        // one of the 51 checks, so picking it would swap an unwatched background for an unwatched
        // pairing. Same reason surfaceContainerLowest = sunken is flagged below.
        surfaceDim = colors.surface.canvas,
        surfaceBright = colors.surface.modal,

        inverseSurface = colors.content.primary,
        inverseOnSurface = colors.surface.canvas,

        // inversePrimary = "primary, as drawn on inverseSurface" — in practice a Snackbar's ACTION
        // LABEL. `primary` itself (signal/400) measures 1.84:1 there and is invisible, so this slot
        // needs its own role, not the accent. It now has one: accent.*.onInverse, gated by
        // generate.mjs at 5.20:1. It reads as the same signal/600 it did when this was borrowing the
        // `wash` value behind a paragraph of justification — the difference is that the build now
        // watches it. Reasoning lives in tokens.json and design-tokens/README.md finding #5.
        inversePrimary = colors.accent.radar.onInverse,
        error = colors.status.danger,
        onError = colors.content.onFill,
        // Elevation is expressed as SURFACE LIGHTENING plus a hairline, per the token notes — not
        // as M3's tonal tint. Leaving surfaceTint non-transparent would double-apply elevation and
        // drift our surfaces off their measured contrast ratios.
        surfaceTint = Color.Transparent,

        // ---- FINDING #1, THE ACCESSIBILITY FIX. NOT A RENAME. ----
        // `outline` is what M3 draws an OutlinedButton's stroke, a focus indicator and an
        // OutlinedTextField's edge with — i.e. the sole visible affordance of a tappable control.
        // The old single `outline` placeholder was a hairline-weight ink step, which measures
        // ~1.1–1.5:1 and is under SC 1.4.11's 3:1 floor: the tap boundary was effectively invisible
        // to anyone relying on contrast to find it.
        //
        // It now takes border.interactive (4.52–4.69:1). Decorative dividers, which are NOT UI
        // components under 1.4.11 and are legitimately allowed to be quiet, take outlineVariant.
        // Mapping both here rather than only at call sites means the next OutlinedTextField anyone
        // adds is compliant by default instead of by memory.
        outline = colors.border.interactive,
        outlineVariant = colors.border.hairline,
    )

@Composable
public fun RadiusTheme(
    // Dark-first product. RadiusColors.light is null because no light-mode variable is designed, so
    // this currently selects dark either way — but it IS read, on the next line, and the day a light
    // palette exists it starts selecting. (It carried a @Suppress("UNUSED_PARAMETER") that was
    // simply false, and a suppression that lies is worse than none: it trains the next reader to
    // believe the parameter is decorative.)
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = RadiusColors.light.takeIf { !useDarkTheme } ?: RadiusColors.dark
    val materialScheme = radiusMaterialColorScheme(colors)

    CompositionLocalProvider(
        LocalRadiusColors provides colors,
        LocalRadiusSpacing provides RadiusSpacing(),
        LocalRadiusRadii provides RadiusRadii(),
        LocalRadiusTypography provides radiusTypography,
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = materialTypography,
            // Shapes stay M3 default for now. RadiusTheme.radii carries the real corner scale and
            // wiring M3's Shapes is a visual change across every stock component at once — worth
            // doing deliberately with screens to look at, not blind inside a token swap.
            content = content,
        )
    }
}

// KNOWN GAPS, recorded here rather than discovered later.
//
// "Unmapped" is not one category, it is three, and conflating them is what let inversePrimary ship
// baseline M3 purple into a Snackbar. Every entry below states which one it is:
//
//   UNMAPPED-AND-SAFE      — both halves of every pairing it appears in are unmapped, so M3's own
//                            baseline is internally consistent, or nothing is ever drawn on it.
//   UNMAPPED-AND-UNWATCHED — a MAPPED token can land against it. THE DANGEROUS ONE: the mapped half
//                            passes its own check, so no gate fires and the defect is invisible to
//                            generate.mjs and to a screenshot alike. Fix by mapping the slot to one
//                            of our verified surfaces — never by measuring against an M3 baseline
//                            value we cannot read out of the artifact.
//   MAPPED, PAIRING UNGATED— both halves are ours, but the combination is not one of generate.mjs's
//                            51 checks. Safe only for a reason you can state; state it.
//
//  - errorContainer / onErrorContainer — UNMAPPED-AND-SAFE. M3 always consumes these as a matched
//    pair, so baseline-on-baseline is internally consistent by M3's own guarantee. The condition on
//    that safety is real, though: it holds ONLY while no call site mixes one of them with a mapped
//    token from outside the pair (e.g. content.primary on errorContainer, or onErrorContainer on
//    surface.raised). If you need one half, map both, and add the pairing to generate.mjs.
//  - scrim — UNMAPPED-AND-SAFE, and more strongly so: M3 has no `onScrim` role at all. Nothing is
//    drawn on a scrim, so there is no pairing that could go unwatched.
//  - surfaceDim / surfaceBright — WAS unmapped-and-unwatched, now mapped (canvas / modal, see the
//    block above). M3 pairs them with onSurface, which is mapped to content.primary, so a verified
//    foreground could have landed on an unverified baseline tone the first time any component
//    reached for tonal elevation. Mapped rather than measured, deliberately.
//  - inversePrimary — WAS unmapped by omission and was the live instance of that failure: the
//    Snackbar action label, drawn on a mapped inverseSurface. Now mapped to accent.radar.onInverse
//    and gated by generate.mjs at 5.20:1, so it is no longer a gap at all.
//  - surfaceContainerLowest = surface.sunken — MAPPED, PAIRING UNGATED. Both halves are ours, but
//    content.primary-on-sunken is not one of generate.mjs's 51 checks. Not a risk today and the
//    reason is arithmetic rather than optimism: sunken is ink/1000, strictly darker than canvas
//    (ink/950), and contrast against a near-white foreground is monotone in background luminance,
//    so it can only exceed canvas's gated 18.36:1. Worth asking design-system to add the pairing so
//    the argument lives in the gate instead of in this comment.
//
// THE TEST FOR ADDING TO THIS LIST is "can a MAPPED token appear against it", not "is it unmapped".
// Anything added from now on names its category and the surfaces it can appear on.
//  - M3 derives DISABLED colours arithmetically (onSurface at 38% alpha) and offers no hook to
//    substitute content.disabled. The two are close in intent but not equal. Anywhere disabled
//    state is load-bearing, set the colour explicitly from content.disabled — and remember it can
//    never be the only cue.
//  - `primary` is Radar's accent app-wide, so stock M3 components on Discover currently ripple
//    teal. "One accent per screen" wants the scheme scoped per mode. That is a RadiusApp-level
//    change (the selected destination already knows its accent) and is deliberately not bundled
//    into a token swap.
