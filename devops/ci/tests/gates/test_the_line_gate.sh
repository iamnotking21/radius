#!/usr/bin/env bash
# devops/ci/tests/gates/test_the_line_gate.sh
#
# Self-test for devops/ci/gates/the_line_gate.sh (decision 41), against synthetic fixtures
# standing in for Crypto.kt via --crypto-file. Does NOT touch the pinned set in the real gate
# script (that set is real and reviewed) — instead, each fixture is checked against a TEMPORARY
# COPY of the_line_gate.sh with a substituted pin, so the test proves the mechanism (diffing
# imports/top-level symbols against a pin) without asserting anything about the live pin's
# specific current contents, which would go stale the moment Crypto.kt legitimately changes.
#
# Run: bash devops/ci/tests/gates/test_the_line_gate.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
REAL_GATE="$REPO_ROOT/devops/ci/gates/the_line_gate.sh"

FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT

pass_count=0
fail_count=0
assert() {
  local desc="$1"; shift
  if "$@"; then
    echo "  ok  - $desc"
    pass_count=$((pass_count + 1))
  else
    echo "  FAIL - $desc"
    fail_count=$((fail_count + 1))
  fi
}

# A gate copy with a small, known pin (two functions, one import) instead of the real Crypto.kt
# pin, so fixtures can be small and self-contained rather than reproducing the real file. Needs its
# own lib/common.sh alongside it — SCRIPT_DIR resolves relative to wherever the copy lives.
mkdir -p "$FIXTURE/lib"
cp "$REPO_ROOT/devops/ci/gates/lib/common.sh" "$FIXTURE/lib/common.sh"
TEST_GATE="$FIXTURE/the_line_gate_test_copy.sh"
sed -E \
  -e 's/^declare -a PINNED_IMPORTS=\(\)$/declare -a PINNED_IMPORTS=("kotlin.math.abs")/' \
  -e 's/^  "constantTimeEquals"$/  "constantTimeEquals"\n  "foo"/' \
  "$REAL_GATE" > "$TEST_GATE"
chmod +x "$TEST_GATE"

# ---- exact match of the test pin -> PASS -----------------------------------------------------
cat > "$FIXTURE/exact.kt" <<'EOF'
package x
import kotlin.math.abs
internal object Sha256 { internal fun digest(m: ByteArray): ByteArray = m }
internal fun hmacSha256(k: ByteArray, m: ByteArray): ByteArray = m
internal fun hkdfSha256(a: ByteArray, b: ByteArray, c: ByteArray, l: Int): ByteArray = a
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = true
internal fun foo(): Int = 1
EOF
assert "exact match of pinned imports+symbols passes" \
  bash -c "'$TEST_GATE' --crypto-file '$FIXTURE/exact.kt' >/dev/null 2>&1"

# ---- a SUBSET of the pin (one pinned symbol removed) -> still PASS (warn only) ------------------
cat > "$FIXTURE/subset.kt" <<'EOF'
package x
internal object Sha256 { internal fun digest(m: ByteArray): ByteArray = m }
internal fun hmacSha256(k: ByteArray, m: ByteArray): ByteArray = m
EOF
assert "a subset of the pinned symbols (nothing new) still passes" \
  bash -c "'$TEST_GATE' --crypto-file '$FIXTURE/subset.kt' >/dev/null 2>&1"

# ---- a NEW top-level function beyond the pin -> FAIL (THE LINE gate's core purpose) --------------
cat > "$FIXTURE/new_fn.kt" <<'EOF'
package x
internal object Sha256 { internal fun digest(m: ByteArray): ByteArray = m }
internal fun hmacSha256(k: ByteArray, m: ByteArray): ByteArray = m
internal fun hkdfSha256(a: ByteArray, b: ByteArray, c: ByteArray, l: Int): ByteArray = a
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = true
internal fun foo(): Int = 1
internal fun aesEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray = plaintext
EOF
assert "a NEW top-level function (e.g. a cipher creeping in) is detected and fails" \
  bash -c "! '$TEST_GATE' --crypto-file '$FIXTURE/new_fn.kt' >/dev/null 2>&1"

# ---- a NEW import beyond the pin -> FAIL, even a harmless-looking stdlib one ---------------------
cat > "$FIXTURE/new_import.kt" <<'EOF'
package x
import kotlin.math.abs
import kotlin.random.Random
internal object Sha256 { internal fun digest(m: ByteArray): ByteArray = m }
internal fun hmacSha256(k: ByteArray, m: ByteArray): ByteArray = m
internal fun hkdfSha256(a: ByteArray, b: ByteArray, c: ByteArray, l: Int): ByteArray = a
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = true
internal fun foo(): Int = 1
EOF
assert "a NEW import (kotlin.random.Random, not in the pin) is detected and fails" \
  bash -c "! '$TEST_GATE' --crypto-file '$FIXTURE/new_import.kt' >/dev/null 2>&1"

# ---- nested members are NOT part of the top-level surface (column-0 discipline) ------------------
cat > "$FIXTURE/nested.kt" <<'EOF'
package x
internal object Sha256 {
    internal fun digest(m: ByteArray): ByteArray = m
    private fun rotr(v: Int, b: Int): Int = v
    val K = intArrayOf(1, 2, 3)
}
internal fun hmacSha256(k: ByteArray, m: ByteArray): ByteArray = m
internal fun hkdfSha256(a: ByteArray, b: ByteArray, c: ByteArray, l: Int): ByteArray = a
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = true
internal fun foo(): Int = 1
EOF
assert "nested members (rotr, K) inside Sha256 do not count as new top-level surface" \
  bash -c "'$TEST_GATE' --crypto-file '$FIXTURE/nested.kt' >/dev/null 2>&1"

# ---- REGRESSION: a comment discussing what NOT to add must not itself trip the gate -------------
cat > "$FIXTURE/doc_regression.kt" <<'EOF'
package x
/*
 * THE LINE: this file must never grow a cipher. If anyone imports javax.crypto.Cipher or adds
 * aesEncrypt() here, that is a review block. Decision 41.
 */
internal object Sha256 { internal fun digest(m: ByteArray): ByteArray = m }
internal fun hmacSha256(k: ByteArray, m: ByteArray): ByteArray = m
internal fun hkdfSha256(a: ByteArray, b: ByteArray, c: ByteArray, l: Int): ByteArray = a
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = true
internal fun foo(): Int = 1
EOF
assert "REGRESSION: a comment naming the forbidden things (javax.crypto.Cipher, aesEncrypt) as prose is NOT flagged" \
  bash -c "'$TEST_GATE' --crypto-file '$FIXTURE/doc_regression.kt' >/dev/null 2>&1"

# ---- the REAL gate against the REAL live Crypto.kt, sanity check --------------------------------
assert "the real gate, unmodified, passes against the real live Crypto.kt today" \
  bash -c "'$REAL_GATE' >/dev/null 2>&1"

echo
echo "test_the_line_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
