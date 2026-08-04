package com.radius.shared.domain

/**
 * The three modes. Memorised from root CLAUDE.md; the split is structural, not cosmetic.
 *
 * Accents are named tokens resolved by each platform from `mobile/design-tokens/tokens.json`.
 * Never hardcode the hex.
 */
public enum class AppMode {
    /** Online dating. Finite daily set. NO swipe deck — banned. Accent token: `ember/400`. */
    DISCOVER,

    /** BLE nearby. Works with no internet. Accent token: `signal/400`. */
    RADAR,

    /** One inbox, both origins, transport labelled. Calls live here too. */
    THREADS,
}

/**
 * Ids are plain ULID strings on purpose.
 *
 * A Kotlin `value class` over `String` would be nicer inside Kotlin, but inline value classes
 * export badly through Kotlin/Native → Objective-C (name mangling, boxing at the boundary), and
 * risk R12 says keep the interop surface boring and value-type-only. Boring wins.
 */
public typealias UlidString = String
