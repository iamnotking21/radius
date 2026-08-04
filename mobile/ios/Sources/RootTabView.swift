// RootTabView.swift
// Radius iOS — the three-tab root. Three modes, no fourth.
//
// !! UNVERIFIED !!  Never compiled. No Mac, no Xcode (blocker B4).
//
// DISCOVER — online dating. A finite daily set. NO SWIPE DECK, ever (banned in root CLAUDE.md).
// RADAR    — BLE nearby. Works with no internet. A foreground destination you open.
// THREADS  — one inbox, both origins, transport labelled. Calls live here too.
//
// Nothing else belongs at this level. Settings, profile and paywalls are reached from inside
// a tab, not by growing a fourth one.

import SwiftUI

struct RootTabView: View {

    @EnvironmentObject private var bridge: SharedBridge

    enum Tab: Hashable {
        case discover
        case radar
        case threads
    }

    @State private var selection: Tab = .radar

    var body: some View {
        TabView(selection: $selection) {
            DiscoverView()
                .tabItem {
                    Label("Discover", systemImage: "sparkles")
                }
                .tag(Tab.discover)

            RadarView()
                .tabItem {
                    Label("Radar", systemImage: "dot.radiowaves.left.and.right")
                }
                .tag(Tab.radar)

            ThreadsView()
                .tabItem {
                    Label("Threads", systemImage: "bubble.left.and.bubble.right")
                }
                .tag(Tab.threads)
        }
        // TODO(design-tokens): per-mode accent — Discover = ember/400 (gold),
        // Radar = signal/400 (teal). Deliberately NOT set here: mobile/design-tokens/ is
        // empty, design-system has published nothing, and hardcoding a hex to "get the look"
        // is exactly how a token system dies in week one. System tint until tokens land.
        // Handoff owed to design-system — see README.
    }
}
