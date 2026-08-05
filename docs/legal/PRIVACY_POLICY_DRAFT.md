# Radius — Privacy Policy (DRAFT)

> **STATUS: ENGINEERING DRAFT. NOT LEGAL ADVICE. NOT READY TO PUBLISH.**
>
> Revised 2026-08-05 after a security review returned BLOCK on the first draft: three claims were false, five were stronger than the code supported, and the overclaims clustered on exactly the things that do not exist yet — which is what happens when a document is written from the design instead of from the code.
>
> **Reading convention.** Every sentence describing a system is one of two things:
> - **plain text** — true today, checked against code
> - **`[NOT YET BUILT]`** — a commitment about something that does not exist. True as an intention. Not true as a description.
>
> A reader cannot tell these apart without the markers, so the markers stay until the thing is built.
>
> **Blockers before publication:** entity and jurisdiction undecided (`[JURISDICTION]`), counsel review, and the two publication gates named in §3 and §7.

---

## The short version

Radius finds people near you using Bluetooth. Doing that honestly required some unusual choices, and one trade-off we are going to state plainly rather than bury.

**What we never collect:** your GPS location, your coordinates, your direction of travel, or a map of where you have been. Our apps do not read GPS.

**What we cannot read:** your messages `[NOT YET BUILT]` will be end-to-end encrypted, so we hold only ciphertext. Your calls will never be recorded `[NOT YET BUILT]`.

**What we can do, and you should know it:** because we issue the cryptographic key your device uses for Bluetooth, **we are technically able to determine which account produced a given Bluetooth broadcast.** We do not do this in normal operation. But we are able to, and we would rather tell you than let you assume otherwise.

---

## 1. What we collect

### Account information
Your name, date of birth, gender and pronouns, who you want to meet, your photos, and your prompt answers. Your email address — and, if we later add SMS sign-in, your phone number — for sign-in codes.

### Verification
We create a mathematical representation of your face — a template — to compare your verification selfie against your profile photos.

**This is biometric data and we treat it as such.** We do not keep the selfie. We delete the template within 30 days. We are naming it plainly because a face template is more sensitive than a photograph, not less, and describing it as merely "the result of a comparison" would invite you to assume the opposite.

### Proximity data (Radar)
When Radar is on, your device broadcasts a short Bluetooth message and listens for others.

That message contains no name, no account identifier, no coordinates, and no identifier of ours that stays the same. It carries a rotating 16-byte value, a signal-calibration byte, and protocol flags. Nothing else.

Your phone also transmits a **hardware address** that we do not control and cannot change — see §3, which explains why that matters more than it sounds.

When you and another person are near each other, we record an **encounter**: that two accounts were in proximity, at what approximate closeness band, and roughly when. **We record proximity, not position.** We never learn *where* it happened, and we hold nothing that would let us put an encounter on a map.

### Distance
Displayed distance is a **band** — one of four ranges — not a measurement. The metre figure shown is deliberately imprecise, and is generated from a random session value rather than from signal strength, so it cannot be averaged out over repeated observations to recover a true distance.

### Messages and calls `[NOT YET BUILT]`
Messages will be end-to-end encrypted; we will store ciphertext and will not hold the keys.

Calls will connect directly between devices. **Calls will never be recorded** — the design has no field of any kind capable of holding call content, at any layer.

**Phone numbers are never exchanged between users**, at any point, by any feature.

### Technical data
Device model, operating system version, app version, crash reports, and a coarse regional cell.

---

## 2. What we do *not* collect

- **GPS or precise location.** Our apps do not read GPS. The most precise location we will hold is a coarse regional cell roughly 5 km across (a geohash-5), attached to your account so Discover can work — never a coordinate, never a trail, and never attached to a proximity encounter. `[NOT YET BUILT — no server exists yet]`
- **Direction or bearing.** We never compute which way another person is from you. An automated check fails our build if map, coordinate, or bearing code appears in our mobile apps. The same rule binds our server code; today that half is enforced by review rather than by a build check, **because the server does not exist yet.** When it does, the check extends with it.
- **Location history, or any map of your movements.**
- **Raw Bluetooth signal strength.** In the app you install, it never leaves your phone — it is converted into one of four coarse bands on the device, and the underlying number is never transmitted, logged, or stored. We treat it as location data, because with enough receivers it can be turned into one. *(Our own pre-release radio-test builds, which are never distributed and request no network permission at all, do record it to a file on the test handset. That is how the radio gets measured.)*
- **Your contacts, photo library, or calendar.**

---

## 3. Two things we want you to understand

### 3a. What someone with a scanner nearby can learn

The identifier your phone broadcasts changes every 15 minutes and carries nothing that names you.

For that change to actually break the trail, the **Bluetooth hardware address** your phone sends alongside it has to change at the same moment. That address is controlled by your phone's Bluetooth chip, not by our app — no app on Android or iOS can set it, or even read it.

Where the two change together, someone with a scanner cannot link your broadcasts across a rotation and cannot follow you between places. **Where they do not, they can.**

We do not yet know which phone models do which, and the only way to find out is to measure each one. **Until a model has passed that testing, Radius will not broadcast from that phone at all** — it will still find other people, other people will not find it, and the app will say so on the Radar screen rather than letting you assume you are anonymous. We would rather ship a phone that cannot be seen than a phone that believes it is invisible and is not.

One limit worth stating: rotation breaks the link *between* 15-minute windows, not inside one. A scanner that sees you twice in the same window knows it saw the same device both times.

### 3b. What we can do, and why

Your rotating Bluetooth identifier is derived from a cryptographic key. **We generate that key and issue it to your device.** That means: given a record of a Bluetooth broadcast, we can determine which account produced it — including broadcasts from the past.

Three things bound it:

1. **We hold no record of broadcasts.** We do not operate a scanning network, we do not fund, contract with, or accept observations from one, and our apps do not upload the identifiers they observe — not for analytics, not for debugging, not for any reason. Today our app requests **no network permission at all**, so this is not merely a rule we follow: it is something the app is incapable of doing. **That will change when the app starts talking to our own API.** At that point it becomes a rule we enforce in our own architecture and code review, rather than one your phone's operating system enforces for us. We are telling you now, in advance, so the change is visible rather than silent.
2. **Access is restricted.** `[NOT YET BUILT — publication gate]` No production service will read this key material in ordinary operation; access will require an explicit break-glass procedure that is logged, alerted, and attributable. **This sentence must not be published until that control exists and has been independently verified.** Offering a safeguard that does not exist, in exchange for disclosing a risk, would be worse than not disclosing at all.
3. **Deletion ends it. Rotation does not undo it.** All key material is destroyed when your account is deleted. Rotating a key limits what a *future* compromise would expose; it does not reduce what we could derive about the past, and we will not describe it as if it did.

**Why we chose this.** Generating the key on your device instead would mean we genuinely could not make that link. It would also mean losing your matches and history if you lost your phone, no support for more than one device, and a much weaker ability to act on harassment reports. We judged the recovery and safety cost too high. You may disagree, and you are entitled to know in order to disagree.

`[JURISDICTION]` — legal basis for this processing.

---

## 4. How long we keep things

| Data | Retention |
|---|---|
| Encounters (proximity records) | 30 days, then deleted. 24 hours on the free tier. |
| Face templates | 30 days maximum |
| Messages | Until deleted by either person, or the account is deleted `[NOT YET BUILT]` |
| Account data after deletion | 30-day grace period, then deleted from live systems |
| Backups containing deleted data | `[NOT YET BUILT]` Deleted from backups as those backups expire. **We have not yet designed this and will not publish a number until we have.** |
| Bluetooth key material | Destroyed on our servers when your account is deleted. On your phone, a key is destroyed once a newer one replaces it — that happens while Radar is running, so a phone that has not opened Radar in a long time may still hold a replaced key until it next does. |

**What we cannot delete.** When you delete your account we delete our copy. We cannot delete the copies on the phones of people you have already met or messaged — those are on their devices, not ours, and **no service that works offline can reach them.** We would rather say this than let "deleted everywhere" be inferred.

---

## 5. Who we share with

**We do not sell your personal information.** Not to advertisers, not to data brokers. We do not run ads.

A deliberately small list, only where there is no realistic alternative: **Apple and Google** (push notifications, app-store payments), **an email provider** (sign-in codes), **app store review** (as required to publish).

That is the complete list. `[NOT YET BUILT]` We intend to self-host everything else — databases, media storage, calling infrastructure — specifically to keep the number of parties holding your data as small as possible.

---

## 6. Your choices

- **Ghost mode** — become invisible on Radar, one tap from the Radar screen, no confirmation dialog. `[NOT YET BUILT — the control exists in the interface; it is not yet connected to the radio]`
- **Turn Radar off** — Discover works without Bluetooth entirely.
- **Block** — a blocked person's app is refused when it asks our servers to identify you, so you stop appearing for them entirely rather than merely being hidden from their screen. `[NOT YET BUILT]`
- **Chat requires mutual interest.** Nobody can message you because they walked past you.
- **Delete your account** — from Settings, 30-day grace period, then permanent.

`[JURISDICTION]` — rights of access, correction, portability, objection, and complaint depend on where you live and where we are established.

---

## 7. Law enforcement

We require valid legal process. We notify you of requests about your account unless legally prohibited.

Our full response policy — including what we are technically incapable of providing — is published separately as `LAW_ENFORCEMENT_RESPONSE_POLICY.md`. It is more specific than this section and is the honest answer to "what could they hand over?"

---

## 8. Age

Radius is 18+. We ask for your date of birth at sign-up and we remove accounts we find to be under 18.

We are not calling that age verification, because it is not. If we add real age assurance later, we will say so here.

---

## 9. Security, breaches, and where your data lives

`[JURISDICTION + COUNSEL]` — this section is missing and must be written. It needs: our security practices, our breach-notification commitment and timeline, and an international-transfer section. Data is intended to sit in Singapore while users are elsewhere, which is a cross-border transfer under several regimes regardless of which entity is chosen.

---

## 10. Changes

If we change this policy in a way that materially affects you, we will tell you in the app before it takes effect — not by quietly updating a page and changing a date.

---

`[JURISDICTION]` — controller identity, contact address, data-protection officer if required, supervisory authority.

**Last updated:** DRAFT — not yet published.
