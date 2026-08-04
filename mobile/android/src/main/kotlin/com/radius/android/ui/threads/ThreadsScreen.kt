package com.radius.android.ui.threads

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
 * THREADS — one inbox, both origins, transport labelled. Calls live here too.
 *
 * !! PLACEHOLDER !! Not implemented.
 *
 * DESIGN RULES CARRIED FORWARD:
 *  - ONE inbox. Radar-origin and online-origin conversations sit in the same list. A user should
 *    never have to remember where they met someone in order to find the conversation.
 *  - TRANSPORT IS ALWAYS LABELLED ("Nearby" / "Online"). It changes what the user can expect —
 *    a nearby thread keeps working with no internet, an online one does not.
 *  - A thread exists only after a MUTUAL wave (safety invariant 6). There is no inbox request
 *    folder full of unsolicited messages, because unsolicited messages cannot be sent.
 *  - Calls appear here as timeline events with NO content, ever (calling invariant C2).
 */
@Composable
fun ThreadsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(RadiusTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(RadiusTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.threads_title),
            style = MaterialTheme.typography.headlineMedium,
            color = RadiusTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.threads_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = RadiusTheme.colors.inkMuted,
        )
        Text(
            text = stringResource(R.string.threads_placeholder_note),
            style = MaterialTheme.typography.bodySmall,
            color = RadiusTheme.colors.inkMuted,
        )
    }
}
