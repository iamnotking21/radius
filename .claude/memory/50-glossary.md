# 50 · GLOSSARY (use these words exactly, in code + UI + docs)

wave — proximity like sent over Radar. NOT "like".
handshake — mutual wave. Radar's version of "it's a match".
match — mutual like from Discover (online path).
band — one of HERE / CLOSE / AROUND / EDGE. only distance vocabulary allowed.
ephemeral id — 16B rotating BLE broadcast id. never "user id" on air.
daily key — 24h key, derives ephemeral ids. server-known for resolution.
ghost mode — invisible on Radar. see but not seen.
blackout zone — user-declared place (home/work) where advertising fully stops.
encounter — record that 2 accounts shared a band+time. NEVER a location.
standout — premium super-like currency.
beacon — paid 1h boosted Radar visibility.
daily set — the finite curated Discover list. NOT a deck, NOT a queue.
transport — how a msg travelled: `net` or `ble`. always labelled in UI.
relay — TWO meanings, always qualify: "BLE relay" (multi-hop forward, P4) vs
  "TURN relay" (WebRTC media fallback). never say bare "relay".
call request — the invitation. NOT a "ring". phones do not ring before acceptance.
push-to-talk — BLE voice clips. NEVER call it a "Bluetooth call" — it isn't one.
the gift — the day-3 no-card week of Gold (E7). NOT a "free trial" (that implies a card).

## banned words (in code + copy)
"users" / "singles" / "candidates" → say **people**
"swipe" → we have none
"location" for radar → say **proximity** or **band**
"tracking" → never. we do not.
