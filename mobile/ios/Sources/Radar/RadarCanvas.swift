// RadarCanvas.swift
// Radius iOS — the animated Radar canvas. PLACEHOLDER.
//
// !! UNVERIFIED !!  Never compiled, never run, never profiled. No Mac (blocker B4).
//
// ─────────────────────────────────────────────────────────────────────────────────────────
//  RENDERING APPROACH — CAShapeLayer + CADisplayLink (10-stack.md)
// ─────────────────────────────────────────────────────────────────────────────────────────
//  Rings and nodes are CAShapeLayers. The sweep is a single rotating layer driven by
//  CADisplayLink so its phase stays coupled to the real frame clock and to peer updates.
//
//  METAL IS NOT ON THE TABLE. 10-stack.md: "Metal ONLY if profiler demands." No profiler has
//  run. There is no Mac to run one on. A dozen shape layers on a 120 Hz display is not a
//  workload that justifies a renderer, and choosing one before measurement would be inventing
//  a maintenance burden to solve a problem nobody has observed.
//
//  If someone does profile this and Core Animation genuinely cannot hold frame rate, the
//  Instruments trace goes in the PR. Not a claim — a trace.
//
// ─────────────────────────────────────────────────────────────────────────────────────────
//  SAFETY: THE ANGLE IS RANDOM. THIS IS A SECURITY CONTROL.
// ─────────────────────────────────────────────────────────────────────────────────────────
//  Safety invariant 3, ADR-004: "Bearing is never computed. The angle at which a person
//  appears on the Radar canvas is randomised per session and carries no information."
//
//  The structural defence is that no direction input exists anywhere in Radius to leak. There
//  is no compass read, no bearing calculation, no lat/lng, no multi-antenna AoA, no map. The
//  angle CANNOT encode direction because the quantity is never computed in the first place.
//  Randomising it is belt-and-braces on top of that.
//
//  What this file must never acquire: CoreLocation, CLHeading, CMDeviceMotion attitude,
//  anything from a magnetometer, or any angle derived from RSSI deltas across time. If a PR
//  imports CoreLocation into the Radar module, that is a blocker, not a discussion.
//
// ─────────────────────────────────────────────────────────────────────────────────────────
//  ACCESSIBILITY
// ─────────────────────────────────────────────────────────────────────────────────────────
//  This canvas is DECORATIVE and is hidden from VoiceOver. The authoritative presentation of
//  who is nearby is the list in RadarView. A visual-only radar is an inaccessible radar, and
//  for a product whose entire value is "who is near me", that is not a rough edge — it is
//  locking a class of user out of the feature.
//
//  Reduced motion: the sweep is replaced by a static pulse. Continuous rotation is a
//  vestibular trigger, and honouring `UIAccessibility.isReduceMotionEnabled` is not optional.

import SwiftUI
import UIKit

// MARK: - Per-session random node angle

/// Assigns each peer a stable-for-this-session, random, meaningless angle.
///
/// Stable within a session so nodes do not jitter around the canvas every frame (that reads
/// as a bug and makes the list/canvas correspondence impossible to follow). Random across
/// sessions so that the position carries no information even across repeated encounters with
/// the same person.
enum RadarNodeAngle {

    /// Regenerated every process launch. Never persisted. Persisting it would make angles
    /// stable across sessions, which is precisely what invariant 3 forbids.
    private static let sessionSalt: UInt64 = UInt64.random(in: UInt64.min ... UInt64.max)

    /// Angle in radians for a peer handle. Uniform over [0, 2π).
    ///
    /// FNV-1a rather than Swift's `Hasher` because `Hasher`'s seed is opaque and its output
    /// is explicitly not guaranteed stable within a run for all types. Determinism here is a
    /// UI requirement (nodes must not move), so it is spelled out rather than assumed.
    static func radians(forPeerHandle handle: String) -> Double {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        let prime: UInt64 = 0x0000_0100_0000_01b3

        func mix(_ byte: UInt8) {
            hash ^= UInt64(byte)
            hash = hash &* prime
        }

        withUnsafeBytes(of: sessionSalt.littleEndian) { $0.forEach(mix) }
        handle.utf8.forEach(mix)

        // Top 53 bits → [0, 1) without modulo bias.
        let unit = Double(hash >> 11) * (1.0 / Double(1 << 53))
        return unit * 2 * .pi
    }
}

// MARK: - SwiftUI wrapper

/// PLACEHOLDER. Renders nothing meaningful yet — it exists so the Radar screen has the right
/// shape and so the accessibility and reduced-motion decisions are made before, not after,
/// someone gets attached to an animation.
struct RadarCanvas: UIViewRepresentable {

    let peers: [RadarPeer]

    /// Ghost mode dims the canvas: you are not on the air, and the UI should say so visually
    /// as well as in text.
    let isGhostMode: Bool

    func makeUIView(context: Context) -> RadarCanvasView {
        let view = RadarCanvasView()
        // Decorative. The list in RadarView is the accessible equivalent.
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        return view
    }

    func updateUIView(_ uiView: RadarCanvasView, context: Context) {
        uiView.update(peers: peers, isGhostMode: isGhostMode)
    }
}

// MARK: - UIKit canvas

/// PLACEHOLDER implementation. Structure is real; the drawing is not written.
final class RadarCanvasView: UIView {

    // One layer per band ring. Four bands, four rings — HERE innermost, EDGE outermost.
    private var ringLayers: [ProximityBand: CAShapeLayer] = [:]

    // Peer nodes, keyed by the opaque session handle.
    private var nodeLayers: [String: CAShapeLayer] = [:]

    private let sweepLayer = CAShapeLayer()
    private var displayLink: CADisplayLink?

    private var reduceMotion: Bool { UIAccessibility.isReduceMotionEnabled }

    override init(frame: CGRect) {
        super.init(frame: frame)
        // TODO: build ring layers, sweep layer, node layer factory.
        // TODO(design-tokens): every colour, stroke width and ring radius ratio comes from
        // mobile/design-tokens/tokens.json. That file does not exist yet. NOTHING is
        // hardcoded here in the meantime — an "obviously temporary" hex never gets removed.
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("RadarCanvasView is code-only")
    }

    // MARK: Display link lifecycle
    //
    // Start only when on screen AND animating. A CADisplayLink left running while the view is
    // off screen is a silent 60–120 Hz wakeup source, and this app has a CI-gated battery
    // budget of <4%/hr while scanning. Retaining `self` strongly in a display link target is
    // also the classic leak; use a proxy when this is really implemented.

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil {
            stopAnimating()
        } else {
            startAnimating()
        }
    }

    private func startAnimating() {
        guard displayLink == nil else { return }

        if reduceMotion {
            // Reduced motion: NO continuous sweep. A slow, low-amplitude static pulse (or no
            // animation at all) conveys "listening" without rotation. Honour the setting —
            // "but it looks better" is not a counter-argument to a vestibular disorder.
            renderStaticPulse()
            return
        }

        // TODO: create the display link with a weak proxy target and add to .main /
        // .common run loop mode so the sweep does not freeze during scrolling.
    }

    private func stopAnimating() {
        displayLink?.invalidate()
        displayLink = nil
    }

    private func renderStaticPulse() {
        // TODO: single non-repeating (or very slow, opacity-only) state. No rotation.
    }

    // MARK: Update

    func update(peers: [RadarPeer], isGhostMode: Bool) {
        // TODO: diff `peers` against `nodeLayers`, add/remove node layers, position each at
        //       (radius(for: peer.band), RadarNodeAngle.radians(forPeerHandle: peer.id)).
        //
        // The angle MUST come from RadarNodeAngle and nowhere else. Do not sort peers by
        // anything spatial. Do not "spread them evenly" by distance — an even spread that
        // depends on peer ordering can accidentally correlate with discovery order, and
        // discovery order weakly correlates with proximity. Random is random.
        //
        // Ghost mode: dim the whole canvas and stop the sweep. You are not advertising, and
        // the canvas must not imply otherwise.
    }

    /// Ring radius for a band. HERE innermost → EDGE outermost.
    /// Ratios come from design tokens once published; no magic numbers land here before then.
    private func radius(for band: ProximityBand) -> CGFloat {
        // TODO(design-tokens)
        0
    }
}
