# Phase 0 Spike — Day One Runbook

**For the person holding the phones.** Assumes no BLE knowledge. If you can install an APK and copy a file, you can run this.

The full test design is `mobile/tests/PHASE0_SPIKE_MATRIX.md`. That document is written for an engineer. **This one is written for the day the boxes arrive.**

---

## The one rule

**You are measuring, not demonstrating.** A result that looks bad is a successful test. The whole point of doing this in week one is to find out cheaply, and a spike that only produces good news has told you nothing.

Do not tune anything to make a number look better. If something breaks, write down exactly what broke.

---

## Before you start

**What you need:**

- 2 Android phones minimum, 3-4 better, on **different chipset vendors**. Include a cheap MediaTek device — that's where the privacy feature is most likely to fail, and testing flagships first is backwards because they're the ones most likely to pass.
- **At least two of them on Android 13 or newer.** The independent clock-check used by the latency test only exists from Android 13. On older phones you can still measure latency, but only by running two phones against each other — see Step 4. This does not change what to buy, it changes which phones you pair.
- A USB cable for each.
- A laptop with the project checked out and `adb` available (it's in the Android SDK you already have, under `platform-tools`).
- A notebook. Genuinely — a paper notebook. You will be walking around holding two phones.

**What you do NOT need yet:** the sniffer dongles. Start without them. See "When you need the dongles" at the end.

---

## Step 1 — Build and install (once per phone)

From the project root:

```bash
cd mobile && ./gradlew :android:assembleDebug
```

The APK lands at `mobile/android/build/outputs/apk/debug/android-debug.apk`.

With a phone plugged in and USB debugging enabled:

```bash
adb devices
adb install -r mobile/android/build/outputs/apk/debug/android-debug.apk
```

`adb devices` must list the phone before `install` will work. If it says `unauthorized`, unlock the phone and accept the prompt.

**Label each phone physically.** Masking tape and a marker. You will confuse them within ten minutes otherwise, and a mislabelled phone silently corrupts every result that follows.

Record for each: make, model, Android version, and **chipset** (Settings → About phone, or `adb shell getprop ro.board.platform`). The chipset is the variable that actually matters.

---

## Step 2 — Open the spike app

It installs as a separate icon: **"Radius Spike"**. Not the main app.

Or launch it directly:

```bash
adb shell am start -n com.radius.android.debug/com.radius.android.spike.SpikeActivity
```

Grant every permission it asks for. On Android 12+ that's Nearby Devices; on older versions it will ask for Location, which is Android's legacy requirement for Bluetooth scanning — **the app does not read GPS and requests no network permission at all.**

---

## Step 3 — The first real test (RPA co-rotation, B8)

**This is the one that decides the project.** Everything else is secondary.

**What you're checking:** the phone broadcasts a random ID that changes every 15 minutes. It also broadcasts a hardware address controlled by the Bluetooth chip. **Both must change at the same moment.** If the address stays fixed while the ID rotates, anyone can follow the address and the rotation is decoration.

**Procedure, per phone:**

1. Phone A: set it to advertise. Phone B: set it to scan. (The harness screen has both controls.)
2. Put them a metre apart on a table. Leave them alone.
3. **Run for at least 90 minutes.** You need to cross several 15-minute rotation boundaries, and boundaries are where the failure shows up. Less than 90 minutes tells you nothing.
4. Watch the **bijection counter** on the scanning phone. It shows addresses seen with more than one ID, and IDs seen with more than one address.

**Reading the result — this matters more than the number:**

- **Counter is non-zero → you have found a real failure.** That is a genuine result. Write down the model and move on to the next phone.
- **Counter is zero → this does NOT mean it passed.** Android hides the one bit that distinguishes a real rotating address from a permanently fixed one. **The worst case looks identical to the best case on this screen.**

That asymmetry is the whole reason the dongles exist. Zero on-screen is "no failure detected," never "confirmed safe."

**Pull the data when the run ends:**

```bash
adb shell "run-as com.radius.android.debug ls files/spike"
adb exec-out "run-as com.radius.android.debug tar c files/spike" > spike-PHONENAME-DATE.tar
```

Do this for every run, on every phone. Name the files after the phone. The raw capture is the evidence; the on-screen counter is only a preview.

**The phone tells you the procedure too.** Each mode renders its own step-by-step on screen, and the `adb pull` command is printed there — because you will be in a car park, not at a laptop. The last section of that screen also lists which numbers stand on their own and which need a second phone or a dongle to mean anything. **Read that table before trusting any figure.**

---

## Step 4 — Discovery latency (mode: LATENCY_PROBE)

Only after Step 3 is done for every phone.

**Do not mix this with Step 3.** A latency run restarts the transmitter once a minute, and on some phones that changes the Bluetooth address mid-cycle — which looks exactly like the B8 failure you are hunting. **The app marks latency runs as VOID for B8 automatically**, on screen and in the data file, so you cannot accidentally report one as the other. Different runs, different purpose.

**Procedure:** two phones, different slots, each set to resolve the other. **Advertising ON on both** — that symmetry is what cancels the clock difference between them. Turn Automatic date & time ON on both. Then just start them; they synchronise themselves from the clock, so there is no countdown to tap and no reaction time to worry about.

Run 30 minutes minimum, an hour is better. **Pull the folder from both phones** — the calculation needs both halves.

**Watch the MIN LATENCY figure.** If it goes negative, that is shown in red, and it means the two phones' clocks disagree by at least that much. A packet cannot arrive before it was sent, so a negative number is proof of clock skew, not of a fast connection.

## Step 5 — Battery (modes: BATTERY_BASELINE, then CAPTURE)

**Two runs, and the answer is the difference.** A single run measures the whole phone, not our app.

1. `adb shell dumpsys batterystats --reset`
2. Run **BATTERY_BASELINE** for at least 60 minutes, unplugged. The radio never starts; everything else does.
3. `adb bugreport` — this is the baseline.
4. Now run the **identical** session in CAPTURE mode. Same phone, same screen state, same duration.

The gap between the two is what Radar actually costs. Target is under 4% per hour while scanning.

**One caution.** The scan mode was recently corrected from a setting that ran the radio at 100% duty against a contracted 30%. **Any battery figure from an older build is meaningless.** Build from current `main`.

## Step 6 — Peer density

Passive — it records during every other mode, so you get it for free from the runs above. 1 and 5 peers you can do with the phones you have. 15 and 30 concurrent peers need the dongles.

---

## Step 7 — Walk around

The table test is the controlled case. The product lives in the world.

Indoors, outdoors, through a wall, in a bag, in a pocket, across a room, at 5 m / 10 m / 20 m / 30 m. Screen on and screen off. Note where it stops working. **Bodies and bags absorb 2.4 GHz badly**, so "in a pocket" is not a nice-to-have case — it is the normal case.

Write down what the app claimed and what was actually true. That gap is the product.

---

## What to record for every single run

Bare minimum, in the notebook:

- Which phones, which roles
- Start and end time
- Where you were, and what was between the phones
- What the screen said
- **Anything odd.** Especially gaps — a four-minute silence could be an empty room, a throttled scan, a Bluetooth adapter cycle, or the OEM killing the app. Those are four different findings that look identical unless you noticed what was happening.

The harness logs radio lifecycle events alongside sightings for exactly this reason, but your note of "I walked into the lift here" is not in any log.

---

## When you need the dongles

Buy the three nRF52840 dongles (~$35) **only if** Step 3 comes back clean on every phone.

Clean is the untrustworthy result. If phones show failures, you already have your answer and the dongles change nothing. If everything looks fine, that is exactly when you need an instrument that can tell "genuinely rotating" from "never rotating at all."

One dongle is not enough and would be worse than none: Bluetooth hops across three channels, one dongle hears one, and a packet you missed looks identical to a packet that never existed. **You would get a clean-looking result that means nothing.** Three dongles, one per channel.

---

## When you're done

Send the tar files and the notebook. The results go into `docs/PHASE0_GO_NO_GO.md`, whose thresholds were written down **before any measurement existed** specifically so nobody argues about the bar after seeing the data.

Its verdict section currently reads `NOT YET REACHED`. Your runs are what change that.

---

## If something goes wrong

- **`adb devices` shows nothing** — USB debugging off, or a charge-only cable. Try a different cable first; it is usually the cable.
- **App installs but shows no peers** — check both phones granted Bluetooth permissions, and that one is actually advertising rather than both scanning.
- **Nothing happens for minutes** — expected sometimes. Android throttles apps that start scans too often, and its punishment is *silence*, not an error. The harness counts this. Note the time and keep going.
- **App is killed in the background** — that is a finding, not a bug. Write down the phone model. Aggressive OEM power management is a known risk and we need the list of which vendors do it.
