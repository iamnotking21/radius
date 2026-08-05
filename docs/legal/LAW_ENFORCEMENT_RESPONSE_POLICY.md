# Radius — Law Enforcement Response Policy (DRAFT)

> **STATUS: ENGINEERING DRAFT. NOT LEGAL ADVICE. NOT READY TO PUBLISH.**
>
> Required by ADR-008 mitigation M6 — a release gate, not a nicety.
>
> Revised 2026-08-05 after a security review returned BLOCK. The first draft stated as present fact several things about a system that does not exist yet, contained a paragraph that contradicted ADR-008 M4 in the exact way M4 was written to forbid, and denied in one table a capability it conceded twenty lines later.
>
> **Reading convention:** plain text is true today and checked against code. **`[NOT YET BUILT]`** is a forward commitment about something that does not exist.
>
> **Publication gates:** §2.3 bound 2 and §7 must not publish until the controls they describe exist and are independently verified.

---

## 1. Purpose, and why this is public

**For our users:** you are entitled to know what could be handed over about you before you decide to use the product. A privacy promise that only holds until someone asks firmly is not a promise.

**For ourselves:** writing this down *before* the first request arrives is the only way to answer the tenth one consistently. A policy improvised under pressure by whoever is on call is how companies over-disclose — a far more common failure than refusing outright, and much harder to notice.

---

## 2. What we can and cannot produce

### 2.1 We cannot produce — the data does not exist in a form we can access

| Requested | Why not |
|---|---|
| **Message content** | `[NOT YET BUILT]` Messages will be end-to-end encrypted; we will hold ciphertext and not the keys. *This row will be restated as completed fact, with the implementation referenced, once messaging exists and has been reviewed. It must not be published in the present tense before then.* |
| **Call content or recordings** | `[NOT YET BUILT]` Calls will not be recorded. The calling design has no content field at any layer, and the database will contain no column capable of holding call audio or video — a schema-level guarantee rather than a retention setting. *Not yet built; must not be published in the present tense until the schema exists.* Media travels directly between devices, encrypted end to end. Where network conditions force a relay, the relay forwards packets it cannot decrypt; we do not capture, store, or log relayed media. |
| **Precise location, coordinates, or location history** | Never collected. Our apps do not read GPS. No bearing or direction is ever computed. |
| **Raw Bluetooth signal strength** | Never leaves the user's device in any distributed build. Treated as location data, because with enough receivers it becomes one. |
| **A user's phone number as shared with another user** | Numbers are never exchanged between users by any feature. |
| **Bluetooth observations collected by us** | We do not operate, fund, contract with, or ingest from any scanning network, and our apps never upload observed identifiers. **We hold no corpus of broadcasts to search.** |

### 2.2 We can produce, with valid legal process

| Requested | Notes |
|---|---|
| Basic subscriber information | Name, date of birth, email, account creation date, last-seen date. **Will include phone number if and when SMS sign-in is added.** |
| Encounter records | That two accounts were in proximity, approximate closeness band, approximate time. **Retained 30 days; 24 hours on the free tier.** |
| Message metadata | Who messaged whom, and when. Not content. |
| Call ledger entries | Participants, start/end time, whether peer-to-peer or relayed, outcome. **No content.** |
| Reports and moderation records | Including content a user voluntarily attached to a report |
| Photos and profile content | As stored |
| **IP addresses and connection logs** | **`[UNDECIDED — must be settled before publication]`** Our signalling gateway will see both parties' IP addresses by construction, and a call relay sees the relayed client's. Whether these are logged, and for how long, is not yet decided. This row exists because an omitted category in this table would read as a denial, and law enforcement will ask on the first request. |

### 2.3 The capability we will not pretend away

**We issue the cryptographic key from which a user's rotating Bluetooth identifier is derived. We are therefore technically able to determine which account produced a given Bluetooth broadcast, including past broadcasts.**

We state this rather than let it be discovered, because the alternative is a policy that reads as a stronger guarantee than we can keep.

Two bounds are real today. One is not yet built.

1. **We hold no corpus of broadcasts.** The capability is only meaningful against a record of observed identifiers, and we deliberately do not collect one. A request to identify "who broadcast this identifier" requires the requester to supply the observation — **we cannot search our own data for it, because that data does not exist.**
2. **Access is break-glass.** `[NOT YET BUILT — PUBLICATION GATE]` No production service will read this key material in ordinary operation; access will be logged, alerted, and attributable. **This bound must not be published until ADR-008 M1 (separate encryption domain, key in a secrets manager) and M3 (audited break-glass with alerting) are implemented and independently verified.** Offering a control that does not exist, as consideration for a disclosed risk, is materially worse than never disclosing the risk.
3. **Deletion ends it. Rotation does not undo it.** All key material for an account is destroyed when the account is deleted, and after that we can derive nothing. Rotating a key limits what a *future* compromise of our key store would expose; **it does not reduce what we could derive about the past, and we will not describe it as if it did.** Destruction of superseded keys on the user's own device limits what someone who compromises that phone can derive — it does not limit us.

We have documented the alternative design — generating the key on the device, which would make this impossible — and why we did not choose it, in ADR-008. If we later adopt a scheme that removes this capability, we will say so here.

### 2.4 The honest limit on "we cannot produce a track"

We hold no coordinates and no Bluetooth observations, so **we cannot produce a track from our own data.**

The limit we state rather than leave to be discovered: a requester who supplies **their own** Bluetooth captures, from several places and times, can be told which account produced each one under §2.3. We do not hold those observations and cannot search for them. We can confirm identity against ones we are given. That is the same capability §2.3 describes, and it is why §2.3 exists.

---

## 3. What we require

- **Valid legal process**, appropriate to the data sought and our jurisdiction. We do not disclose on the basis of an informal request, a phone call, or a letter on official paper.
- Requests must **identify the account specifically.** We reject searches by geographic area or any bulk criterion — we hold no coordinates, so those searches are not possible for us to run at all.
- **We can, for a specifically identified account, produce its encounter records**, which name the other accounts that were nearby. That is a proximity search, and we say so plainly rather than let the point above be read as covering it. It is limited to a named account, to the 30-day retention window, and it never says *where*.
- We reject requests that are overbroad, vague, or seek data we have said we do not hold — and we say which, rather than silently producing a subset.

---

## 4. Emergency requests

`[COUNSEL REVIEW REQUIRED — highest legal risk in this document.]`

We will consider disclosure without legal process **only** where there is a genuine, specific, and imminent risk of death or serious physical harm.

- The request must come from a verifiable law-enforcement address and identify a specific person at risk.
- Disclosure is limited to what is necessary for that specific emergency.
- Every emergency disclosure is logged, reviewed afterwards by someone who did not approve it, and counted in our transparency report.

**The failure mode we are guarding against is our own.** Emergency channels are the most commonly abused route to user data, precisely because they bypass the checks that would otherwise apply and because refusing feels unconscionable in the moment. A pre-written standard exists so the decision is not made under pressure by one person who wants to help.

---

## 5. Notice to users

We notify a user when we receive a request for their data, before disclosing, unless legally prohibited or in a genuine emergency as defined above.

Where a non-disclosure order is time-limited, **we notify the user when it expires.** We do not treat an expired gag order as permanent.

---

## 6. Preservation

We will honour a valid preservation request for a specific account, for the period the law requires.

**Preservation is not collection.** It does not cause us to begin retaining data we do not otherwise retain, and it does not extend our 30-day encounter window into an indefinite one for anyone.

---

## 7. Jurisdiction

`[JURISDICTION — BLOCKING. Cannot be completed until the controlling entity is decided.]`

Must state: which entity controls the data, which country's law governs, which foreign requests we will and will not act on, and the process for requests originating outside that jurisdiction.

This is a genuine blocker, not paperwork. **Until the entity is decided we cannot state whose law binds us — which means we cannot honestly tell a user what protection they have.**

---

## 8. Transparency reporting

At least annually: requests received by type and jurisdiction; how many we complied with, fully or partly; how many we rejected and on what grounds; emergency disclosures; accounts affected.

**We will publish from the first reporting period, including when the numbers are zero.** A transparency report that begins only once the numbers are uncomfortable is not transparency.

---

## 9. Contact

`[JURISDICTION]` — official address for service of legal process.

---

**Owner:** `[UNASSIGNED — requires a named human before launch]`
**Review cadence:** annually, **and on any change to what we are technically capable of producing.** The trigger list lives in `CLAIMS_REGISTER.md`.
**Last updated:** DRAFT — not yet published.
