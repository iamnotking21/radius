#!/usr/bin/env bash
# devops/ci/tests/gates/test_internal_escape_gate.sh
#
# Self-test for devops/ci/gates/internal_escape_gate.sh (decision 43, B11), against synthetic
# fixtures. Encodes the one real false-positive class found by running an earlier draft against the
# live repo (KDoc documentation of the finding itself, e.g. on KeySchedule.dailyKey) as a regression
# case.
#
# Run: bash devops/ci/tests/gates/test_internal_escape_gate.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
GATE="$REPO_ROOT/devops/ci/gates/internal_escape_gate.sh"

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

new_fixture() { mkdir -p "$FIXTURE/$1"; echo "$FIXTURE/$1"; }

# ---- clean code -> PASS -----------------------------------------------------------------------
d="$(new_fixture "clean")"
cat > "$d/X.kt" <<'EOF'
package x
internal fun accountKey(): ByteArray = byteArrayOf()
class C { fun use() { accountKey() } }
EOF
assert "ordinary internal-fun declaration and same-module call passes" \
  bash -c "'$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- real violation: one of the four named mangled symbols, called ----------------------------
d="$(new_fixture "named_escape")"
cat > "$d/Evil.kt" <<'EOF'
package x
class Evil(private val entry: Any) {
    fun steal() = (entry as java.lang.Object).javaClass // placeholder
    fun grab(e: KeyRingEntryLike): ByteArray = e.accountKey$shared_debug()
}
EOF
assert "a call to accountKey\$shared_debug() is detected" \
  bash -c "! '$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- real violation: generic $shared_release catch-all, symbol not in the named four -----------
d="$(new_fixture "generic_release")"
cat > "$d/Evil2.kt" <<'EOF'
package x
val leak = someObj.someOtherInternalMember$shared_release()
EOF
assert "the generic \$shared_release catch-all fires for a symbol NOT in the named four" \
  bash -c "! '$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- real violation: dailyKey$ and ephemeralIdFor$ and conformanceState$ ------------------------
for sym in "dailyKey\$shared_debug()" "ephemeralIdFor\$shared_debug(a, 1, 2)" "conformanceState\$shared_debug()"; do
  d="$(new_fixture "sym_$RANDOM")"
  echo "val x = obj.$sym" > "$d/E.kt"
  assert "call to $sym is detected" bash -c "! '$GATE' --source-dir '$d' >/dev/null 2>&1"
done

# ---- REGRESSION: KDoc documenting the finding (found in the real repo) must NOT be flagged ------
d="$(new_fixture "doc_regression")"
cat > "$d/KeySchedule.kt" <<'EOF'
package x
/**
 * `internal` IS NOT A SECURITY BOUNDARY ON THE JVM. A foreign compilation unit called
 * `accountKey$shared_debug()`, `dailyKey$shared_debug()` and `ephemeralIdFor$shared_debug()`
 * directly, with no reflection. See B11.
 */
internal fun dailyKey(accountKey: ByteArray, day: Long): ByteArray = accountKey
EOF
assert "REGRESSION: KDoc documenting the finding (real repo case) is NOT flagged" \
  bash -c "'$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- test source is out of scope regardless of content ------------------------------------------
d="$(new_fixture "testsrc")"
mkdir -p "$d/androidUnitTest"
echo 'val x = obj.accountKey$shared_debug() // PoC, lives in test source on purpose' \
  > "$d/androidUnitTest/PoCTest.kt"
assert "a PoC demonstrating the escape, in a *Test* path, is out of scope" \
  bash -c "'$GATE' --source-dir '$d' >/dev/null 2>&1"

echo
echo "test_internal_escape_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
