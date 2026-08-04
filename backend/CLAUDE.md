# backend/ — MEMORY
owner: backend-go — EXCEPT services/calling/ (calling-webrtc), services/billing/ (growth-conversion),
services/discovery/ranking/ (data-ml), **/tests/ (qa-test). backend-go still reviews their proto + schema.
consulted: ble-protocol(proximity svc), security-privacy

lang Go1.23+ · Connect-RPC+buf · sqlc · chi · River · golang-migrate · protovalidate
svcs: identity profile discovery proximity messaging calling gateway media safety billing
calling: WebRTC signalling ONLY. never touches media. 1:1 = P2P, no SFU.

## rules
- proto/ = SOURCE OF TRUTH. change ⇒ ADR + architect review + notify ios/android/web FIRST.
- sqlc only. NO ORM. every query readable.
- migrations forward-only. never edit shipped one.
- ids ULID · time timestamptz · money minor-units int · cursor pagination
- every endpoint: authz check + rate limit + protovalidate. no exceptions.
- NEVER store lat/lng. city = geohash5. encounters store BAND only.
- messages table holds CIPHERTEXT. server cannot decrypt. do not add plaintext col.
- strip EXIF in media svc BEFORE durable write.
- block check happens at key-resolution AND at every read path AND at call authorisation.
- calls table has NO content column. never add one. calls are never recorded.
- calling svc does exactly 4 things: authorise · issue short-lived TURN creds · relay
  signalling · write ledger row. nothing else.
- entitlements are SERVER-AUTHORITATIVE. never trust a client claim of premium.

## layout
proto/ services/<name>/ pkg/ migrations/
svc internals: transport/ → service/ → store/   (no layer skipping)

## test
unit: table-driven. integration: testcontainers postgres+valkey+nats.
contract: buf breaking vs main. load: k6 at 10x projected.
