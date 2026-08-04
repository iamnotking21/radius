// RadarView.swift
// Radius iOS — the Radar screen. BLE proximity. Works with no internet.
//
// !! UNVERIFIED !!  Never compiled. No Mac, no Xcode (blocker B4).
//
// Two non-negotiables shape this screen:
//
// 1. GHOST MODE IS ≤1 TAP (safety invariant 10). It is in the navigation bar, always visible
//    while Radar is on screen, and it takes effect immediately with no confirmation dialog.
//    A safety control behind a menu, a sheet, or a "are you sure?" is not a safety control.
//
// 2. THE CANVAS IS NOT THE INTERFACE (ADR-004 + a11y). Everything the canvas shows is also a
//    list, and the list is what VoiceOver reads. The canvas is decorative.
//
// And the framing rule (ADR-004, risk R1): Radar is a FOREGROUND DESTINATION. Copy on this
// screen says Radar is on while the screen is open. It never says "we'll keep looking in the
// background", because iOS will not, and a promise the platform breaks is worse than an
// absent feature.

import SwiftUI

struct RadarView: View {

    @EnvironmentObject private var bridge: SharedBridge

    var body: some View {
        NavigationStack {
            VStack {
                RadarCanvas(
                    peers: bridge.radar.peers,
                    isGhostMode: bridge.radar.isGhostMode
                )
                .accessibilityHidden(true) // The list below is the accessible equivalent.

                peerList
            }
            .navigationTitle("Radar")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    ghostModeButton
                }
            }
            .safeAreaInset(edge: .bottom) {
                foregroundFramingNotice
            }
        }
        .onAppear { bridge.startRadar() }
        .onDisappear { bridge.stopRadar() }
    }

    // MARK: Ghost mode — invariant 10

    private var ghostModeButton: some View {
        Button {
            bridge.setGhostMode(!bridge.radar.isGhostMode)
        } label: {
            Label(
                bridge.radar.isGhostMode ? "Ghost mode on" : "Ghost mode off",
                systemImage: bridge.radar.isGhostMode ? "eye.slash.fill" : "eye.fill"
            )
        }
        // One tap. No confirmation. No sheet. No menu.
        .accessibilityLabel(bridge.radar.isGhostMode ? "Turn off ghost mode" : "Turn on ghost mode")
        .accessibilityHint("Ghost mode hides you from everyone nearby, immediately.")
    }

    // MARK: The accessible equivalent of the canvas

    private var peerList: some View {
        List(bridge.radar.peers) { peer in
            RadarPeerRow(peer: peer)
        }
        .listStyle(.plain)
        .overlay {
            if bridge.radar.peers.isEmpty {
                emptyState
            }
        }
    }

    private var emptyState: some View {
        // Honest empty state. No fake nearby people, no invented activity, no "3 people were
        // here earlier" — that is a fabricated-social-proof dark pattern and it is banned.
        VStack {
            Text("No one nearby yet")
                .font(.headline)
            Text("Radar looks for people around you while this screen is open.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .accessibilityElement(children: .combine)
    }

    private var foregroundFramingNotice: some View {
        // ADR-004 / R1. This is product copy doing safety work: it sets the expectation the
        // platform can actually meet. Do not soften it into implying background discovery.
        Text("Radar is on while this screen is open.")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding()
            .frame(maxWidth: .infinity)
    }
}

// MARK: - Row

struct RadarPeerRow: View {

    let peer: RadarPeer

    var body: some View {
        // No fixed heights, no `.lineLimit(1)`, no fixed-size frames. Dynamic Type must reach
        // 200% without clipping, and 200% of a body font in a 44pt row does not fit.
        VStack(alignment: .leading) {
            Text(bandLabel)
                .font(.headline)

            Text(distanceText)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical)
        // One element, one announcement. VoiceOver should not read a band and a distance as
        // two unrelated fragments.
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(bandLabel), \(distanceText)")
    }

    private var bandLabel: String {
        // TODO(copy): final wording is a copy decision, not an engineering one.
        switch peer.band {
        case .here:   return "Right here"
        case .close:  return "Close by"
        case .around: return "Around"
        case .edge:   return "At the edge"
        }
    }

    private var distanceText: String {
        // HEDGED, ALWAYS. `peer.displayMeters` is a band midpoint plus jitter, produced by
        // the shared core (safety invariant 2). Presenting it as a precise measurement would
        // be both a lie and a re-identification aid. "About" is load-bearing.
        "about \(peer.displayMeters) m away"
    }
}
