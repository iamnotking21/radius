package com.radius.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ================================ READ THIS BEFORE USING ================================
 *
 * !! PLACEHOLDER VALUES. NOT DESIGN TRUTH. !!
 *
 * `mobile/design-tokens/tokens.json` DOES NOT EXIST YET. It is owned by the design-system agent
 * and is a CONTRACT (40-contracts, surface TOKENS). I am not allowed to write it and I have not.
 *
 * The hex values in [RadiusColors.Placeholder] below are placeholders chosen only so the shell
 * renders something that is not Material purple. They will be WRONG. When tokens.json lands,
 * exactly ONE file changes — this one — and every screen picks up the real values, because no
 * screen in this app hardcodes a colour or a dimension. That is the whole point of the indirection.
 *
 * HANDOFF raised to orchestrator for design-system to publish tokens.json.
 * ========================================================================================
 *
 * Material 3 is used as a LAYOUT ENGINE here. Its component behaviour, touch targets and
 * accessibility semantics are excellent and free. Its default look is not ours and is overridden.
 */

/** Semantic colour roles. Screens name the ROLE, never the hue. */
public data class RadiusColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    /** DISCOVER accent. Token: `ember/400`. */
    val accentDiscover: Color,
    /** RADAR accent. Token: `signal/400`. */
    val accentRadar: Color,
    /** THREADS is neutral by design — it carries both origins and favours neither. */
    val accentThreads: Color,
    val danger: Color,
    val outline: Color,
) {
    public companion object {
        /** PLACEHOLDER. Replace wholesale from tokens.json. Do not tweak these by eye. */
        public val Placeholder: RadiusColors = RadiusColors(
            background = Color(0xFF0B0D10),
            surface = Color(0xFF14181D),
            surfaceRaised = Color(0xFF1D232A),
            ink = Color(0xFFF2F4F6),
            inkMuted = Color(0xFF9AA4AF),
            accentDiscover = Color(0xFFE0A63C),
            accentRadar = Color(0xFF35C7BE),
            accentThreads = Color(0xFFB9C2CC),
            danger = Color(0xFFE4574C),
            outline = Color(0xFF2A323B),
        )
    }
}

/**
 * Spacing scale. Screens use these names, never a raw `.dp`.
 *
 * Why it matters beyond tidiness: font scale 200% must not clip anything. A fixed `24.dp` written
 * inline is how that breaks. Scale steps get audited in one place.
 */
public data class RadiusSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    /** Minimum touch target. Never go below this, whatever the mock says. */
    val touchTarget: Dp = 48.dp,
)

public val LocalRadiusColors: androidx.compose.runtime.ProvidableCompositionLocal<RadiusColors> =
    staticCompositionLocalOf { RadiusColors.Placeholder }

public val LocalRadiusSpacing: androidx.compose.runtime.ProvidableCompositionLocal<RadiusSpacing> =
    staticCompositionLocalOf { RadiusSpacing() }

/**
 * Accessor object, mirroring `MaterialTheme`'s shape so it reads naturally at call sites:
 * `RadiusTheme.colors.accentRadar`.
 */
public object RadiusTheme {
    public val colors: RadiusColors
        @Composable @ReadOnlyComposable get() = LocalRadiusColors.current

    public val spacing: RadiusSpacing
        @Composable @ReadOnlyComposable get() = LocalRadiusSpacing.current
}

@Composable
public fun RadiusTheme(
    // Dark-first product. The light scheme is not designed yet; forcing dark is honest until it is.
    @Suppress("UNUSED_PARAMETER") useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = RadiusColors.Placeholder

    // M3 scheme is derived from our tokens so that stock M3 components (NavigationBar, Switch,
    // ripples) inherit the right look without every call site re-specifying colours.
    val materialScheme = darkColorScheme(
        primary = colors.accentRadar,
        onPrimary = colors.background,
        secondary = colors.accentDiscover,
        onSecondary = colors.background,
        background = colors.background,
        onBackground = colors.ink,
        surface = colors.surface,
        onSurface = colors.ink,
        surfaceVariant = colors.surfaceRaised,
        onSurfaceVariant = colors.inkMuted,
        error = colors.danger,
        outline = colors.outline,
    )

    CompositionLocalProvider(
        LocalRadiusColors provides colors,
        LocalRadiusSpacing provides RadiusSpacing(),
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            // Typography and shapes stay M3 defaults until the type tokens land. Deliberately not
            // guessed: a wrong type ramp is more visible than a wrong colour.
            content = content,
        )
    }
}
