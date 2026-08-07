# OEM quirks — Android BLE + background survival

Owner: android-kotlin. Living document. **Every row must be confirmed on a physical device
before it is trusted.** Rows marked UNCONFIRMED are prior knowledge, not evidence.

Status: **MOSTLY UNVERIFIED, WITH ONE HANDSET NOW PARTLY REPORTED ON.** B5 is closed and the code
builds and packages, but a build is not a radio. This is still the pre-flight checklist for the
Phase 0 spike rather than a report of results — EXCEPT for the rows below marked CONFIRMED, which
were observed on a physical Xiaomi Redmi 15 5G during the first hardware run of the harness. Those
rows are about the HOST TOOLCHAIN (what adb can do to the phone), not yet about the radio.

| device | model | OS | notes |
|---|---|---|---|
| Xiaomi Redmi 15 5G | 25057RN09G | Android 16 / API 36, Qualcomm SM6375, 1080x2340 @450dpi | first device the harness ever ran on. Host-toolchain rows below are from this handset. |

**The instrument that fills this file in now exists**: `mobile/android/src/debug/kotlin/com/radius/android/spike/`,
a debug-only harness reachable as a separate launcher icon ("Radius Spike") or by

```
adb shell am start -n com.radius.android.debug/com.radius.android.spike.SpikeActivity
adb pull /sdcard/Android/data/com.radius.android.debug/files/spike/<run-id> .
```

It records every advertisement AND every radio lifecycle event to `events.jsonl` + `sightings.csv`,
with a `meta.json` carrying `Build.FINGERPRINT` — which is the row key §4.3.3 wants, and what makes
"same model, different firmware" distinguishable in the results.

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
| `pm grant` blocked | `adb shell pm grant` throws `SecurityException: Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS`. Runtime permissions MUST be granted by tapping on the device. No workaround. | **CONFIRMED** Redmi 15 5G |
| `input` blocked | `adb shell input tap/swipe/keyevent` throws `SecurityException: ... INJECT_EVENTS`. MIUI gates input injection behind "USB debugging (Security settings)", which requires a signed-in Xiaomi account. | **CONFIRMED** Redmi 15 5G |
| `sendevent` blocked | SELinux (Enforcing) denies `shell` write access to `input_device`, even though `shell` is in group `input` and `/dev/input/event*` is `crw-rw---- root:input`. DAC permits, MAC refuses. | **CONFIRMED** Redmi 15 5G |
| `svc bluetooth` works | `adb shell svc bluetooth enable` returns `Success` and the radio comes up. This is the reliable scripted way to get Bluetooth on for a run. | **CONFIRMED** Redmi 15 5G |

#### The Xiaomi adb trap, and why it cost a session

`adb shell input` prints its `SecurityException` to **stderr and still exits 0**. A swipe that was
never delivered is therefore indistinguishable, from the shell's exit status alone, from a swipe
that was delivered and did nothing.

That is not hypothetical: the first hardware run of the spike harness was reported as "the screen
does not scroll — two swipes produced pixel-identical screenshots". The screen scrolled fine. The
swipes never reached the app. `uiautomator dump` confirmed the Compose scroll container was present
and `scrollable="true"` the whole time.

**Rule: on a Xiaomi, never conclude anything about the UI from an `adb shell input` gesture.**
Read stderr, or drive the gesture a way that works.

The way that works is `/system/bin/uinput`, which registers a virtual input device in the kernel and
so is subject to neither `INJECT_EVENTS` nor the SELinux rule on the real touchscreen node:

```
# uinput takes a POSITIONAL file argument (not -f), and the file is a
# STREAM of JSON objects, NOT a JSON array — an array gives
# "IllegalStateException: No element left to skip".
adb push swipe.json /data/local/tmp/ && adb shell uinput /data/local/tmp/swipe.json
```

Register a Type-B multitouch device (`ABS_MT_SLOT`/`ABS_MT_TRACKING_ID`/`BTN_TOUCH`), allow ~1s
after `register` for InputReader to enumerate it, then inject. Verified working on this handset.

Also confirmed working over adb on this device, for the avoidance of doubt: `am start`,
`am force-stop`, `uiautomator dump`, `exec-out screencap -p`, `pull`, and
`settings put system font_scale` (so the 200 % accessibility check IS scriptable here).

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
5. **Peripheral role is not universal.** Some devices can scan and never be seen. Radar is
   half-dead there and the UI must say so rather than pretend (`KEY_SCHEDULE.md` §4.3.6: "This
   phone can see people nearby but cannot be seen by them" is a sentence a user is entitled to).

   **CORRECTED 2026-08-04.** An earlier version of this file, and of `BleRadio.android.kt`, used
   `isMultipleAdvertisementSupported == false` as the test for "cannot advertise". That is wrong:
   that flag answers "can this controller run SEVERAL advertising sets at once", which is a
   different question, and gating on it would have refused to advertise on hardware that is
   perfectly capable of a single advertising set. The correct check is
   `BluetoothAdapter.getBluetoothLeAdvertiser() != null`, exposed as `BleRadio.peripheralRoleSupported`.
   `isMultipleAdvertisementSupported` is still RECORDED per device by the spike harness, because it
   is useful OEM data — it is simply not a gate.

6. **`ScanSettings` scan modes ARE the duty cycle**, and AOSP's numbers are the reason
   `DutyProfile.FOREGROUND` maps to `SCAN_MODE_BALANCED` rather than `SCAN_MODE_LOW_LATENCY`:

   ```
   SCAN_MODE_LOW_POWER      512 ms / 5120 ms   =  10 %
   SCAN_MODE_BALANCED      1024 ms / 4096 ms   =  25 %
   SCAN_MODE_LOW_LATENCY   4096 ms / 4096 ms   = 100 %
   ```

   Root `CLAUDE.md` contracts a 30 % scan duty. BALANCED (25 %) is the nearest Android offers.
   LOW_LATENCY is a continuous receiver at four times the contracted duty and there is no credible
   path from it to <4 %/hr. **Several vendors ship their own window/interval values**, so the real
   duty is a per-device measurement, not a number read off this table — which is itself a row the
   spike should fill in.

7. **Filter on the service UUID, not on service data.** Both match, but service-data filters are
   the ones OEM controllers are least likely to offload, and a filter that falls back to software
   filtering does not survive a screen-off scan — which is most of Radar's life. Bonus: a UUID
   filter still delivers a Carrier B peer (our UUID, no service data), so the §5.0 matrix can tell
   "an iOS-shaped peer was here" apart from "nothing was here".

8. **`ACTION_STATE_CHANGED`/`STATE_ON` arrives before the adapter is usable.** On a large fraction
   of handsets `getBluetoothLeScanner()` returns null, or `startScan` fails with `INTERNAL_ERROR`,
   for roughly a second after the broadcast. Retrying immediately burns a scan-start from the
   5-per-30s budget and fails anyway. `BleRadio.ADAPTER_SETTLE_MS` is currently 1500 ms and that
   number is a GUESS — measuring the real per-OEM settle time is a spike row.

9. **`ScanResult.timestampNanos` is not trustworthy on the budget tier, and a zero there corrupts
   three measurements at once.** The field is documented as elapsed-realtime since boot. Several
   budget chipsets — disproportionately the MediaTek devices `PHASE0_SPIKE_MATRIX.md` §2 puts in
   the FIRST batch — report `0`.

   Taken at face value that computes an age of "everything since boot" and back-dates the sighting
   to near the boot instant. The damage is not one wrong column:

   - the latency cycle index goes backwards, so the sample is attributed to a cycle hours in the
     past and either discarded or counted as enormous clock skew;
   - the density bucket is wrong, so acquisition rate lands in a bucket that was written long ago;
   - `wall_utc_ms` in `sightings.csv` is wrong, and nothing else in the row disagrees with it, so
     the error is unrecoverable in analysis.

   `BleRadio.epochMillisOf` therefore clamps: a timestamp that is `<= 0`, in the future, or older
   than `MAX_SCAN_RESULT_AGE_MS` (60 s) falls back to callback time. **The clamp emits a
   `TIMESTAMP_CLAMPED` radio event** (rate-limited, 1st and every 100th) so it appears in the
   capture — a run on a handset that fabricates scan timestamps has a timing caveat that must
   travel with its numbers, and that has to be discoverable from the file rather than from
   remembering which phone it was.

   **Record the model in the per-vendor section above when you see it.** A non-zero
   `TIMESTAMP_CLAMPED` count means discovery latency on that handset includes the callback delivery
   delay and cannot be separated from it — P2 percentiles from that device are an upper bound, not
   a measurement.

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
| Android ↔ iOS discovery, both directions | ☐ | ☐ | DEFERRED with iOS (decision 33). Not a risk on the current plan; still owed. |

Emulator results do not go in this table. A simulator BLE result is invalid and must never be
reported as a pass.

**Sample order is deliberately backwards from instinct.** `KEY_SCHEDULE.md` §4.3.3 puts MediaTek
budget devices (Transsion/Infinix/Tecno, Realme, low-tier Xiaomi) in the FIRST batch, not the
second: they have the least documented BT controllers and the largest share of the install base in
the target markets. Flagships are the devices most likely to pass, so testing them first tells you
least. Samsung must be tested in both its Exynos and Snapdragon variants — same marketing name,
different controller.

---

## Running the spike harness

1. Install the debug APK on two handsets with **different slots** (slot 1 and slot 2). Two handsets
   on the same slot is the decision-35 twin case and the log will fill with `E_SELF_EID`.
2. Grant Bluetooth permissions. On API 29-30 that includes `ACCESS_FINE_LOCATION` — without it a
   scan returns **zero results silently**, which looks exactly like a hardware finding and is not.
   **On Xiaomi you cannot do this from the laptop** — `pm grant` is refused (see the MIUI table
   above). Tap the dialogs on the phone. The harness says so on screen when permissions are missing.
2b. Turn the radio on however you like; `adb shell svc bluetooth enable` is confirmed working on
   Xiaomi and is the least error-prone way. The harness records adapter state either way.
3. One handset: **Advertise ON**. The other: leave it off, or turn it on too for a symmetric run.
4. For a **co-rotation / latency capture**: turn **Max capture ON**. Yield matters, battery does
   not, and the run header records that any battery figure from it is invalid.
5. For a **battery capture**: Max capture **OFF**, duty = the profile being measured, and attach a
   Battery Historian trace to the PR.
6. Leave it ≥90 minutes, spanning ≥6 UTC 15-minute boundaries (`KEY_SCHEDULE.md` §5.2). **Do not
   use a compressed test epoch** — the question includes the interaction between our boundary and
   the controller's own ~900 s RPA timer, and shortening our period changes the thing being
   measured.
7. Pull the directory and check `meta.json` FIRST, before any number in it. These are the fields
   that decide whether the rest of the file may be quoted at all:

   | Field | Must be | If it is not |
   |---|---|---|
   | `diagnostics_dropped` | 0 | records we lost. Absence claims (B8, §5.0) are void. |
   | `radio_events_dropped` | 0 | a lifecycle event was lost. A missing `SCAN_STOPPED` leaves the duty ledger's interval open and **inflates `scan_on_ms`**, so the battery attribution is wrong, not merely gappy. |
   | `write_failures` | 0 | rows never reached the disk. |
   | `stop_collectors_joined` | `true` | rows may have been written after the summary was computed; the counters in `meta.json` are then a lower bound. |
   | `wall_clock_step_ms` | 0 | the platform re-synced mid-run. `%/hr` is unaffected (it divides by `elapsed_realtime_ms`), but every `latency_ms` in `latency.csv` is on the wall clock and IS affected. |
   | `latency_unaccounted_cycles` | 0 | the harness was not running for that many latency cycles — Doze or an OEM kill. Not a peer failure, and on a battery-manager run it is the finding. |

8. For a latency run, read `latency_missed_peer_cycles` and `latency_peers_departed` TOGETHER
   before reading `p50`. A clean p50 over a run where the peer departed at minute 10 is a
   percentile over ten minutes, not over the run — the miss and departure counters are what make
   that visible, and they are timer-driven so a total blackout still counts.

9. Divide battery figures by `elapsed_realtime_ms`, never by `wall_utc_ms`. Both columns are in
   `battery.csv` for the express purpose of making a clock re-sync visible as a step between them.

**What the on-screen bijection counters are and are not.** The harness shows, live, "addresses seen
with >1 eid" and "eids seen with >1 address" — the §4.3.1 bijection, computed from what this phone's
own receiver saw. A **non-zero** value is real evidence that invariant 5 fails on that hardware. A
**zero** value is NOT evidence that it holds: a phone's scanner hops channels and misses packets, and
Android never exposes the TxAdd bit, so the catastrophic public-address case can pass the on-device
screen. §5.1 buys three nRF52840 dongles for exactly this reason and the asymmetry is the whole
point of §5.3. Use the harness to find failures early and cheaply; use the sniffer to declare a pass.
