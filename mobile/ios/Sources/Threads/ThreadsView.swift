// ThreadsView.swift
// Radius iOS — Threads. One inbox, both origins. PLACEHOLDER.
//
// !! UNVERIFIED !!  Never compiled. No Mac, no Xcode (blocker B4).
//
// One inbox. Conversations that started on Radar and conversations that started in Discover
// live in the same list, and each row LABELS ITS TRANSPORT — a thread carried over BLE with
// no internet behaves differently from one carried over the network, and hiding that from the
// user makes offline delivery feel broken rather than remarkable.
//
// Calls live here too (ADR-005). When they land:
//   C3 — invited, never cold-rung. A request is accepted BEFORE any ring or any SDP.
//        The phone does not ring first. Ever.
//   C4 — an in-call safety control within one tap of every active call.
//   C5 — ending a call is instant and never confirmed with a dialog.
//   C2 — calls are never recorded. There is no content column, by design.
//
// Message content is E2EE (invariant 9); the server holds ciphertext only. Decryption happens
// in the shared core via vodozemac, and plaintext reaches this layer as a Swift value type or
// not at all.

import SwiftUI

struct ThreadsView: View {

    @EnvironmentObject private var bridge: SharedBridge

    var body: some View {
        NavigationStack {
            // TODO: the unified inbox, from the shared core's messaging use case.
            // Not wired: mobile/shared/ does not exist, proto v0 is not locked, and the
            // ratchet has no implementation to talk to yet.
            ContentUnavailableViewCompat(
                title: "Threads",
                message: "Not built yet. Blocked on the shared core (mobile/shared) and proto v0."
            )
            .navigationTitle("Threads")
        }
    }
}
