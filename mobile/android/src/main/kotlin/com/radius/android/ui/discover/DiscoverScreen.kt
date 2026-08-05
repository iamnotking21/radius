package com.radius.android.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.radius.android.R
import com.radius.android.ui.theme.RadiusTheme

/**
 * DISCOVER — online dating, worldwide. Accent token `ember/400`.
 *
 * !! PLACEHOLDER !! Not implemented.
 *
 * WHAT THIS SCREEN WILL BE: a FINITE daily set, rendered as a scrollable list of full profiles.
 *
 * WHAT IT WILL NEVER BE, and these are bans not preferences (root CLAUDE.md):
 *  - a swipe deck. No card stack, no fling gesture, no `HorizontalPager` of faces.
 *  - an infinite feed. The set ends. That is the product.
 *  - a countdown, an expiry timer, or a streak.
 *  - a blurred-face paywall, or "3 people liked you" when three people did not.
 *
 * Progress framing ("4 of 12 seen") is allowed and encouraged — but only because it is TRUE. The
 * moment a number on this screen is not literally true, it is a dark pattern and it comes out.
 */
@Composable
fun DiscoverScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(RadiusTheme.spacing.space16),
        verticalArrangement = Arrangement.spacedBy(RadiusTheme.spacing.space8),
    ) {
        Text(
            text = stringResource(R.string.discover_title),
            style = MaterialTheme.typography.headlineMedium,
            color = RadiusTheme.colors.content.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.discover_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = RadiusTheme.colors.content.secondary,
        )
        Text(
            text = stringResource(R.string.discover_placeholder_note),
            style = MaterialTheme.typography.bodySmall,
            color = RadiusTheme.colors.content.secondary,
        )
    }
}
