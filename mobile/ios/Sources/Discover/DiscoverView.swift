// DiscoverView.swift
// Radius iOS — Discover. Online dating, worldwide. PLACEHOLDER.
//
// !! UNVERIFIED !!  Never compiled. No Mac, no Xcode (blocker B4).
//
// ─────────────────────────────────────────────────────────────────────────────────────────
//  THERE IS NO SWIPE DECK. THERE WILL NEVER BE A SWIPE DECK.
// ─────────────────────────────────────────────────────────────────────────────────────────
//  Banned in root CLAUDE.md alongside streaks, expiry timers and blurred-face paywalls.
//  Discover is a FINITE DAILY SET: a small number of people, considered, and then you are
//  done for the day. Running out is the intended experience, not a failure state to be
//  patched with an infinite feed.
//
//  If a future ticket asks for "just a card stack for engagement", the answer is no, and the
//  escalation path is ORCHESTRATION §8.
//
//  Also banned on this screen, explicitly: fake likes, bot profiles, invented "someone viewed
//  you", countdown scarcity, and throttling the set to manufacture disappointment. FTC ROSCA
//  enforcement is live and the EU Digital Fairness Act draft lands Q3/Q4 2026 — but the real
//  reason is that Radius sells honesty, and a manipulative feed contradicts the product.

import SwiftUI

struct DiscoverView: View {

    @EnvironmentObject private var bridge: SharedBridge

    var body: some View {
        NavigationStack {
            // TODO: the finite daily set, from the shared core's discovery use case.
            // Not wired: mobile/shared/ does not exist and proto v0 is not locked, so there
            // is no contract to consume yet.
            ContentUnavailableViewCompat(
                title: "Discover",
                message: "Not built yet. Blocked on the shared core (mobile/shared) and proto v0."
            )
            .navigationTitle("Discover")
        }
    }
}

/// `ContentUnavailableView` is iOS 17+. Deployment target is iOS 16, so this stands in.
/// Trivial, but it exists so nobody "fixes" the build by raising the deployment target.
struct ContentUnavailableViewCompat: View {

    let title: String
    let message: String

    var body: some View {
        VStack {
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .accessibilityElement(children: .combine)
    }
}
