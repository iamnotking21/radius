# Radius Distance Banding — v0

**Owner:** ble-protocol · **Implements:** safety invariants 1, 2, 3 · **Binds:** ADR-004
**Vectors:** `vectors/banding.json`, `vectors/display_jitter.json`

```
raw RSSI → validity gate → outlier gate → Kalman filter → normalise by tx_power_cal
        → 4 bands with hysteresis → display jitter → UI
```

**There is no metre value anywhere in this pipeline.** The number the UI shows is
manufactured from the band, not measured. §6 explains why that is a security
control and not a UX compromise.

---

## 1. Constants

```
TX_REF_DBM            = -60      reference calibration the band thresholds are defined against
Q                     = 1.0      Kalman process noise variance, dBm²
R                     = 16.0     Kalman measurement noise variance, dBm² (σ = 4 dB)
WARMUP_SAMPLES        = 3        below this, band is UNKNOWN and MUST NOT be displayed
STABLE_SAMPLES        = 10       at/above this, confidence = STABLE
OUTLIER_GATE_DB       = 20.0     |z - x| beyond this is provisionally rejected
MAX_CONSEC_REJECT     = 3        this many consecutive rejects ⇒ genuine step change, re-init
H_IN_DB               = 3.0      extra margin required to move to a CLOSER band
H_OUT_DB              = 2.0      extra margin required to move to a FARTHER band
K_IN                  = 3        consecutive updates required to promote
K_OUT                 = 2        consecutive updates required to demote
STALE_MS              = 30000    no advertisement for this long ⇒ confidence STALE
DROP_MS               = 90000    no advertisement for this long ⇒ peer removed
```

**Band thresholds, on the normalised value, exactly as ADR-004 specifies:**

```
HERE           adjusted ≥ -55        "in this room"
CLOSE          adjusted ≥ -70        "very close"
AROUND         adjusted ≥ -82        "nearby"
EDGE           adjusted ≥ -95        "somewhere around"
OUT_OF_RANGE   adjusted <  -95       not displayed
```

Band ordinals, used by the hysteresis comparison. Lower = closer.

```
HERE 0 · CLOSE 1 · AROUND 2 · EDGE 3 · OUT_OF_RANGE 4 · UNKNOWN -1
```

---

## 2. Filter

ADR-004 says "Kalman filter (10-sample window)". A Kalman filter is recursive
and has no window; the two ideas are reconciled as: the **filter** is recursive,
and **10 accepted samples** is the threshold at which the reading is considered
stable enough to be called STABLE. A 10-sample ring buffer of raw values is kept
for diagnostics and outlier context only, and never leaves the module.

Scalar constant-position model. Per accepted sample `z`:

```
predict:   P = P + Q
gain:      K = P / (P + R)
update:    x = x + K * (z - x)
           P = (1 - K) * P
           n = n + 1
```

Initialisation, and re-initialisation after a step change:

```
x = z ;  P = R ;  n = 1 ;  consecutive_rejects = 0
```

Operations MUST be performed in exactly this order and in IEEE-754 double
precision. The vectors assert filtered values to a tolerance of `1e-6`; band
outputs are discrete and MUST match exactly.

### 2.1 Validity gate

`z` outside `[-127, 20]` dBm is not a plausible BLE RSSI. Drop it, change no
state, count it. Some Android stacks report `127` or `0` as "unknown"; those must
never enter the filter.

### 2.2 Outlier gate, and the trap in it

```
if |z - x| > OUTLIER_GATE_DB:
    consecutive_rejects += 1
    if consecutive_rejects < MAX_CONSEC_REJECT:
        drop the sample, change no state
    else:
        re-initialise the filter to z      # genuine step change, not noise
        consecutive_rejects = 0
        reset in_count and out_count
        # band is NOT reset — see below
else:
    consecutive_rejects = 0
    run the filter update
```

**The trap, which the reference run caught before this reached either
platform.** The obvious implementation — reject up to three outliers, then
accept one and reset the counter — livelocks. A genuine 38 dB step change (peer
walks out of a pocket, or a door opens) produces: reject, reject, *accept one*,
counter resets to zero, reject, reject, accept one… The filter crawls toward the
true value at one sample in four while reporting a stale band the whole time. In
the reference run this left a peer stuck in CLOSE for eleven samples after the
signal had actually jumped to HERE.

The fix is that the third consecutive outlier is not treated as a sample to
accept but as **evidence that the model is wrong**, which re-initialises the
filter. Recovery then takes `MAX_CONSEC_REJECT` samples instead of being
unbounded. `vectors/banding.json` case `step-change-reinit-edge-to-here` pins
this behaviour; it is a regression test for a bug that has already been made
once.

Band is deliberately **not** reset to `UNKNOWN` on re-initialisation. Doing so
would blank the peer out of the Radar UI every time someone put their phone in a
pocket. Confidence drops to `COARSE` instead (because `n` resets to 1), which is
the honest signal without the flicker.

---

## 3. Normalisation by `tx_power_cal`

```
adjusted = x + (TX_REF_DBM - tx_power_cal)
```

`tx_power_cal` is the RSSI a reference receiver measures at 1 m from that
transmitter (`SPEC.md` §3.2), quantised to seven legal values.

Sanity check, both directions:

```
loud device,  tx_power_cal = -50, measured -78  →  -78 + (-60 - -50) = -88
quiet device, tx_power_cal = -70, measured -58  →  -58 + (-60 - -70) = -88
```

Two devices at the same physical distance normalise to the same value. Both are
`EDGE`. Pinned by vector cases `txcal-loud-device` and `txcal-quiet-device`.

Filter first, normalise second. `tx_power_cal` is constant for a given peer
within a session, so the order is mathematically irrelevant (the operation is a
linear offset) — but it is fixed here so both platforms produce bit-identical
intermediate values, and it matches ADR-004's wording.

**If `tx_power_cal` is unavailable** — which happens under fallback F3a
(`SPEC.md` §5.3), where iOS foreground peers may carry no calibration byte —
implementations MUST substitute `TX_REF_DBM` and MUST degrade confidence by one
level (`STABLE` → `COARSE`). They MUST NOT guess a value from the platform or
from any device-model inference; inferring the model from the radio would
re-create exactly the fingerprint that quantising `tx_power_cal` exists to
suppress.

---

## 4. Hysteresis

Without hysteresis a peer sitting near a threshold flaps between two bands
several times a second, which is visually awful and, worse, leaks information: a
flapping band is a high-resolution signal about position that the banding was
supposed to destroy. **Hysteresis is a privacy control as much as a UI one.**

```
raw_band = threshold lookup on adjusted        (no hysteresis)

if band == UNKNOWN:
    if n >= WARMUP_SAMPLES:
        band = raw_band                        # acquisition uses ADR thresholds exactly
        in_count = out_count = 0

else if ordinal(raw_band) < ordinal(band):     # candidate is CLOSER — promote
    if adjusted >= threshold(raw_band) + H_IN_DB: in_count += 1
    else:                                      in_count  = 0
    out_count = 0
    if in_count >= K_IN: band = raw_band; in_count = 0

else if ordinal(raw_band) > ordinal(band):     # candidate is FARTHER — demote
    if adjusted <  threshold(band) - H_OUT_DB:  out_count += 1
    else:                                       out_count  = 0
    in_count = 0
    if out_count >= K_OUT: band = raw_band; out_count = 0

else:
    in_count = out_count = 0
```

Note the asymmetry in which threshold each branch uses: promotion tests against
the **candidate** band's threshold, demotion against the **current** band's. That
is what makes the dead zone sit between the bands rather than inside one, and it
lets a two-band jump promote directly to the correct band rather than stepping.

### 4.1 Deliberate asymmetry

Promotion needs `+3 dB` sustained over 3 updates; demotion needs `−2 dB` over 2.
Moving closer is harder than moving away. This is intentional: `HERE` means "in
this room", and over-claiming closeness is the error with product consequences.

Measured cost, from the reference run (`promote-close-to-here`): after a clean
20 dB step, demotion completes in 2 samples and promotion in 8. At the 250 ms
foreground advertising interval that is roughly 0.5 s and 2 s. At the 1000 ms
background interval, 2 s and 8 s. Acceptable, and re-checkable against the
vectors if anyone later wants to tune `Q`.

### 4.2 Acquisition uses the raw thresholds — and why

Warm-up adopts `raw_band` with no margin, so first acquisition matches ADR-004's
thresholds exactly.

An earlier draft applied the promotion margin at acquisition too, on the theory
that a fresh peer should be assumed farther rather than nearer. The reference run
showed this shifts every effective threshold by 3 dB — `HERE` would begin at
−52, not −55 — which silently redefines the ADR's numbers. Worse, it is a
one-way ratchet: a peer acquired at a steady −68 lands in `AROUND` and can never
promote to `CLOSE`, because promotion requires ≥ −67. Permanently wrong, in the
direction that looks conservative. Rejected. Fidelity to the ADR wins.

### 4.3 Path dependence is expected

Within the dead zone the reported band depends on which side the peer came from.
A peer at a steady −68 reports `CLOSE` if it arrived from closer and `AROUND` if
it arrived from farther. That is what hysteresis *is*, not a defect. It is
bounded by `H_IN` + `H_OUT` = 5 dB against band widths of 13-15 dB.

---

## 5. Confidence

```
WARMING   band == UNKNOWN (n < WARMUP_SAMPLES)     MUST NOT be displayed
COARSE    band known, n < STABLE_SAMPLES           displayable, hedge the copy harder
STABLE    band known, n >= STABLE_SAMPLES          displayable
STALE     no advertisement for > STALE_MS          displayable, must be visibly marked
          no advertisement for > DROP_MS           peer removed entirely
```

**A band MUST NEVER be derived from a single reading.** One RSSI sample is
noise. `WARMUP_SAMPLES = 3` is the floor and it is not configurable downward.

---

## 6. Display jitter — and why raw RSSI must never leave this module

### 6.1 The rule

```
displayed_metres = band_midpoint + deterministic_jitter(session_salt, peer_id, band)
```

**The displayed number is a function of `(session_salt, local_peer_id, band)`
and of nothing else. It is not a function of RSSI.**

That is the entire security property, and it is the part that is easy to get
wrong. The naive implementation — take a real distance estimate and add random
noise to it — is broken. An attacker who can observe the displayed value
repeatedly just averages the samples; the noise cancels and the true value falls
out. Averaging `n` observations reduces the noise by `√n`. Any jitter added to a
real value is a delay, not a defence.

Because our displayed value never contains a real distance in the first place,
averaging it converges to the band midpoint, which the attacker already knew from
the band. There is nothing to recover.

The value is also **stable** while the band and session are stable, so the UI
does not shimmer, and re-rendering leaks nothing.

### 6.2 Derivation

```
seed    = HMAC-SHA256(key = session_salt, message = local_peer_id || band_ordinal)
u32     = big-endian uint32 of seed[0..3]
u       = u32 / 2^32                                # [0, 1)
offset  = (u * 2 - 1) * span(band)                  # (-span, +span)
metres  = round_half_away_from_zero(midpoint(band) + offset)
metres  = clamp(metres, midpoint - span, midpoint + span)
```

| Band | midpoint | span | displayed range | ADR distance intuition |
|---|---|---|---|---|
| HERE | 1 | 0 | 1 | ~0-2 m |
| CLOSE | 6 | 2 | 4-8 | ~2-10 m |
| AROUND | 20 | 6 | 14-26 | ~10-30 m |
| EDGE | 65 | 20 | 45-85 | ~30-100 m |

- `session_salt` is 32 CSPRNG bytes generated when the Radar session starts. It
  is never persisted and never leaves the device. The same salt seeds the
  randomised node angle required by invariant 3, so both display randomisations
  share one lifetime.
- `local_peer_id` is the resolved local account handle (16 bytes), **not** the
  ephemeral ID. Seeding on the eid would make the displayed number jump every 15
  minutes while the peer stood still. Seeding on the local handle keeps it
  stable within a session and different across sessions.
- `HERE` has `span = 0`: at that range a metre figure is theatre. The band copy
  carries the meaning.

Worked values are in `vectors/display_jitter.json`, generated with real
HMAC-SHA256.

### 6.3 Raw RSSI is location data. Treat it as such.

This is the justification for the hard API boundary in `SPEC.md` §8, where
`PeerReading` exposes `band`, `confidence` and `displayMetres` and has no RSSI
accessor of any kind.

- **Three receivers with raw RSSI multilaterate to a position.** Not a band — a
  position. RSSI plus known receiver geometry is a distance-to-each-anchor
  problem, and it is solved. A "nearby people" feature that returned raw RSSI to
  three colluding phones would be a location API with extra steps, and it would
  violate invariant 1 while every individual component looked innocent.
- **One receiver plus time and motion is nearly as good.** An RSSI trace from a
  single moving observer yields a tight distance track, and combined with the
  observer's own movement it constrains the target's position hard.
- **Sub-band resolution is a fingerprint.** Fine-grained RSSI is stable enough
  over short intervals to help link identities across an ephemeral-ID rotation
  boundary, partially undoing `KEY_SCHEDULE.md` §3.

Therefore:

- Raw, filtered, and adjusted RSSI MUST NOT cross the `mobile/shared/protocol/`
  boundary. Not in a public type, not in a debug build, not behind a feature
  flag, not in a crash report, not in an analytics event. A debug-only accessor
  is not safer — it is the same accessor with a comment on it.

  **The one carve-out, named so it stays one.** `BandingPipeline.conformanceState()`
  is `internal` and returns `adjusted_dbm`. It exists because `vectors/banding.json`
  asserts `adjusted_dbm` per step, and those assertions are what caught two real
  bugs (outlier-gate livelock; warm-up margin shifting the ADR thresholds) — a
  vector whose expectations cannot be checked is not a vector. Ruled to stay
  (decision 42). Two honest qualifications: `internal` is a Kotlin compile-time
  property and **not** JVM access control, so on Android this is a mangled-name
  call away from app code rather than genuinely unreachable; and the reflection
  lint in the test suite (`noNewRssiShapedAccessorAppears`) is a NAME check on
  public members, not a proof of containment. Containment here rests on review.
  Any second carve-out needs the same paragraph written for it, or it does not
  happen.
- RSSI MUST NOT be transmitted to the server in any form. The proximity service
  receives `(account, band, epoch)` and nothing finer. Timestamps are bucketed to
  the epoch before leaving the device, because a fine-grained encounter timeline
  across many peers reconstructs a movement trace even without any distance.
- No API may return a distance in metres derived from a measurement. The only
  metre value that exists is the manufactured one in §6.2.
- Bearing, heading, and angle-of-arrival MUST NOT be computed, stored, or
  approximated anywhere. Invariant 1 and 3. The Radar node angle is random per
  session and carries no information; it MUST NOT be seeded by anything
  observable, including RSSI, band, or acquisition order.

### 6.4 Copy is part of the control

Displayed distances MUST be hedged in the UI ("about 6 m", "a few metres away"),
never stated as fact ("6 m"). The hedge is honest — the number genuinely is not a
measurement — and it sets the expectation that Radius does not do precision. Exact
copy belongs to design-system; this document only mandates that it hedges.

---

## 7. What must be measured on real hardware

Every constant in §1 is a starting point from ADR-004 and from the reference run.
None is validated against a radio.

1. Band accuracy versus true distance, per device pair, indoors and outdoors,
   line-of-sight and body-blocked and in-pocket.
2. Whether `R = 16.0` (σ = 4 dB) matches observed RSSI variance. If real variance
   is materially different, `Q`/`R` need retuning and the vectors regenerate.
3. Promotion and demotion latency in the field against §4.1's predicted 8 and 2
   samples.
4. Flap rate at each threshold over a long stationary observation — the direct
   test of whether `H_IN`/`H_OUT`/`K_IN`/`K_OUT` are large enough.
5. Real `tx_power_cal` per device model, to populate `calibration/`.

**BLE RSSI cannot be validated in a simulator.** There is no radio, no
multipath, no body attenuation, and no antenna. A simulator run of this pipeline
tests the arithmetic only — which is what `vectors/banding.json` is for — and
proves exactly nothing about band accuracy. Simulator results MUST NOT be
reported as validation of any row above.
