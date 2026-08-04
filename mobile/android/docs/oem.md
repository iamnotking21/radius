# OEM quirks — Android BLE + background survival

Owner: android-kotlin. Living document. **Every row must be confirmed on a physical device
before it is trusted.** Rows marked UNCONFIRMED are prior knowledge, not evidence.

Status at time of writing: **NOTHING IN THIS FILE HAS BEEN VERIFIED ON HARDWARE.** No JDK on the
dev machine (blocker B5), no build, no device run. This is the pre-flight checklist for the Phase 0
spike, not a report of results.

---

## Why this file decides whether the product works

Radar is a background BLE product. On stock Android that is a solved problem. On the devices most
of our users actually own, the OS ships an additional, undocumented, vendor-written battery
manager that will kill a foreground service anyway. `START_STICKY` does not save you. A wake lock
does not save you. The only things that help are: being on the user's allow-list, keeping the duty
cycle genuinely low, and being honest in the UI when the OS has silently stopped us.

The dishonest failure mode is the dangerous one: the user believes they are discoverable, and they
are not. Radar must be able to detect that it was killed and say so.

---

## Per-vendor

### Samsung (One UI) — MUST TEST, highest market share in target region
| item | detail | status |
|---|---|---|
| Sleeping apps / Deep sleeping apps | One UI auto-adds apps to "Sleeping apps" after ~3 days unused. A deep-sleeping app gets no background execution at all. | UNCONFIRMED |
| Adaptive battery | Throttles background work by usage prediction. | UNCONFIRMED |
| Advertising | Historically reliable, multi-advertisement supported on mid-range and up. | UNCONFIRMED |
| Scan throttle | Standard AOSP 5-scans-per-30s applies. | UNCONFIRMED |
| Workaround | Guide user to Settings → Battery → Background usage limits → never sleeping apps. | UNCONFIRMED |

### Xiaomi (MIUI / HyperOS) — MUST TEST
| item | detail | status |
|---|---|---|
| MIUI battery saver | Kills background services aggressively; separate from AOSP Doze. | UNCONFIRMED |
| Autostart permission | Off by default. Without it the app cannot restart after being killed. | UNCONFIRMED |
| "Lock" in recents | User must lock the task in the recents switcher to survive a swipe-away. | UNCONFIRMED |
| Workaround | Onboarding step with a deep link to the autostart settings screen. | UNCONFIRMED |

### Huawei / Honor (EMUI / MagicOS)
| item | detail | status |
|---|---|---|
| Protected apps | Not on the protected list ⇒ killed on screen off. | UNCONFIRMED |
| No Google Play Services | Irrelevant for BLE; relevant for FCM push. Radar itself must not depend on GMS. | UNCONFIRMED |

### Pixel / AOSP — MUST TEST (the control group)
Baseline behaviour. If something works here and nowhere else, the problem is the OEM, not our code.

### OnePlus / Oppo / Vivo (ColorOS / OxygenOS / FuntouchOS)
Aggressive background management similar to MIUI. Test after the two required OEMs.

---

## AOSP-level constraints that are NOT vendor quirks

These apply everywhere and are not worked around, only respected:

1. **Scan start throttle.** More than ~5 `startScan` calls in 30 seconds and Android silently stops
   delivering results — no error, no callback. Any duty-cycling scheduler must respect it or Radar
   dies quietly. See `BleRadio.android.kt`.
2. **`SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`** is the usual symptom of the above, and also of
   an adapter mid-restart.
3. **RPA rotation is controller-owned.** Apps cannot set or read the resolvable-private-address
   rotation interval. Safety invariant 5 requires the MAC to rotate in step with `ephemeral_id`
   ("both or neither"). Whether stop→start advertising reliably forces a new RPA is
   **chipset-dependent and must be measured with a sniffer per OEM.** If it cannot be forced, that
   is a finding for the go/no-go memo — not something to paper over.
4. **Legacy advertisement is 31 bytes.** flags (3) + 16-bit service UUID (4) + service data
   (4 + payload). With the 19-byte v0 payload that is 30 of 31 bytes used. The device name must
   never be included — it would overflow, and it is a stable identifier (safety invariant 4).
5. **Peripheral role is not universal.** `isMultipleAdvertisementSupported == false` devices can
   scan but never be seen. Radar is half-dead there and the UI must say so rather than pretend.

---

## Required test matrix before any Phase 0 GO

Minimum two OEMs, and one of them must be Samsung (per team rules). Pixel is the control.

| scenario | Samsung | Pixel | notes |
|---|---|---|---|
| foreground discovery latency (<5s target) | ☐ | ☐ | |
| background discovery latency (<60s target) | ☐ | ☐ | |
| survives screen off 1h | ☐ | ☐ | |
| survives swipe-away from recents | ☐ | ☐ | |
| survives 8h overnight | ☐ | ☐ | the one that usually fails |
| RPA rotates in step with ephemeral_id | ☐ | ☐ | sniffer required |
| battery <4%/hr scanning | ☐ | ☐ | Battery Historian trace attached to PR |
| battery <1%/day idle | ☐ | ☐ | |
| Android ↔ iOS discovery, both directions | ☐ | ☐ | see the iOS findings in `BleRadio.ios.kt` |

Emulator results do not go in this table. A simulator BLE result is invalid and must never be
reported as a pass.
