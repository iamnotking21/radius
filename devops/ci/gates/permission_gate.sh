#!/usr/bin/env bash
# devops/ci/gates/permission_gate.sh
#
# MERGED-MANIFEST PERMISSION GATE — decision 50, CLAIMS_REGISTER.md gap G2.
#
#   Decision 50: a permission audit MUST read the MERGED manifest, not the source one, because
#   `androidx.work` once injected `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED`
#   into the shipped APK with nobody typing any of the three (see the postmortem comment inline in
#   `mobile/android/src/main/AndroidManifest.xml`). Nobody wrote the check after that ruling. This
#   is it.
#
#   Why this is urgent rather than routine: `docs/legal/PRIVACY_POLICY_DRAFT.md` §3b bound 1 tells
#   users, in writing, "today our app requests no network permission at all... that is not merely a
#   rule we follow: it is something the app is incapable of doing." CLAIMS_REGISTER.md row A4 backs
#   the same claim. Both are TRUE TODAY and FALSE the moment a transitive dependency re-adds
#   `INTERNET` and nobody notices — which is exactly the failure mode decision 50 already caught
#   once. An unenforced claim in a published privacy policy is a false statement waiting to happen.
#
# SCOPE: `build/intermediates/packaged_manifests/**/AndroidManifest.xml`, BOTH the `debug` and
# `release` variant directories. NOT `mobile/android/src/main/AndroidManifest.xml` — the whole point
# of decision 50 is that the source manifest is not what ships.
#
# WHAT COUNTS AS A VIOLATION: any `<uses-permission>` (or `<uses-permission-sdk-23>`) element whose
# `android:name` is not in the explicit allowlist below. Fail closed: a permission this script has
# never seen is exactly the case it exists to catch, not a case to wave through.
#
# THE ALLOWLIST, VERIFIED AGAINST THE LIVE MERGED MANIFEST (both variants) ON 2026-08-05, not just
# copied from the brief that requested this gate:
#
#   android.permission.BLUETOOTH_SCAN                    (neverForLocation — see manifest comment)
#   android.permission.BLUETOOTH_ADMIN                    (maxSdk 30, legacy)
#   android.permission.BLUETOOTH_ADVERTISE
#   android.permission.BLUETOOTH_CONNECT
#   android.permission.BLUETOOTH                          (maxSdk 30, legacy)
#   android.permission.ACCESS_FINE_LOCATION                (maxSdk 30 — pre-31 scan-result delivery)
#   android.permission.FOREGROUND_SERVICE
#   android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
#   android.permission.POST_NOTIFICATIONS
#
# ONE ADDITIONAL PATTERN, FOUND BY RUNNING THIS GATE AGAINST THE REAL MERGED MANIFEST RATHER THAN
# TRUSTING THE REQUESTED LIST (the brief said "verify that against the real merged manifest rather
# than trusting my list" — this is that verification): both variants also declare
#
#   <permission android:name="<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
#               android:protectionLevel="signature" />
#   <uses-permission android:name="<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
#
# (`com.radius.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` in release,
# `com.radius.android.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` in debug — the applicationId
# differs per variant, which is why this is a PATTERN, not one more exact string). This is an
# AndroidX-tooling artifact (Core / Profileinstaller-family libraries emit it whenever anything in
# the dependency graph registers a dynamic `BroadcastReceiver`), not a permission we asked for and
# not a capability grant to anything: it is `signature`-protected, scoped to our OWN application id,
# declared and required by us in the same manifest, and its only effect is that no OTHER app can
# send a broadcast to our own unexported receiver pre-API-33. It carries no privacy or network
# capability and is unrelated to the INTERNET/location concerns this gate exists for. Allowlisted by
# PATTERN (must end in exactly that literal permission name, scoped under some application id) rather
# than by the two exact strings, so it survives an applicationId rename without a gate edit.
#
# CRITICAL: THE FAILURE MESSAGE NAMES THE CONSEQUENCE, NOT JUST THE PERMISSION. `INTERNET`
# specifically gets a dedicated message naming the privacy-policy paragraph and the claims-register
# row it invalidates — see `internet_consequence_message` below — because the point of this gate is
# that the person adding the permission learns what they just invalidated, not just that a build went
# red. INTERNET is EXPECTED to land eventually for the API client (PRIVACY_POLICY_DRAFT.md §3b bound
# 1 says so itself: "that will change when the app starts talking to our own API"). This gate does
# not try to prevent that. It exists so it cannot happen silently — the day it lands, §3b bound 1 and
# CLAIMS_REGISTER row A4 must be rewritten in the SAME PR, not discovered later by security-privacy
# or, worse, by a user.
#
# WHAT THIS GATE DOES NOT DO: judge whether a new non-INTERNET permission is safe. Same posture as
# `the_line_gate.sh` decision 41 — widening the allowlist is a design conversation (privacy-policy
# rewrite, CLAIMS_REGISTER update), and this script forces the conversation to be visible rather than
# skippable, it does not have the opinion itself.
#
# USAGE:
#   devops/ci/gates/permission_gate.sh [--manifest-root DIR]
#   --manifest-root DIR   Override the root searched for
#                          debug/**/AndroidManifest.xml and release/**/AndroidManifest.xml.
#                          Defaults to <repo>/mobile/android/build/intermediates/packaged_manifests.
#                          Self-test harness only in real use; a human may also point it at a local
#                          build to check before pushing.
#
# EXIT CODE: 0 only if BOTH variants were found and both contain nothing but allowlisted permissions.
# Non-zero if a variant is missing (the check did not run — a gate that cannot run must fail loudly,
# never pass silently, same rule `release_uuid_gate.sh` follows) or an unlisted permission is found.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REPO_ROOT="$(qa_repo_root)"
MANIFEST_ROOT="$REPO_ROOT/mobile/android/build/intermediates/packaged_manifests"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest-root) MANIFEST_ROOT="$2"; shift 2 ;;
    *) qa_hardfail "unknown argument: $1" ;;
  esac
done

declare -a ALLOWLIST_EXACT=(
  "android.permission.BLUETOOTH_SCAN"
  "android.permission.BLUETOOTH_ADMIN"
  "android.permission.BLUETOOTH_ADVERTISE"
  "android.permission.BLUETOOTH_CONNECT"
  "android.permission.BLUETOOTH"
  "android.permission.ACCESS_FINE_LOCATION"
  "android.permission.FOREGROUND_SERVICE"
  "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"
  "android.permission.POST_NOTIFICATIONS"
)
# See header — AndroidX dynamic-receiver guard permission, applicationId-scoped, no capability.
ALLOWLIST_PATTERN='^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$'

is_allowed() {
  local perm="$1" x
  for x in "${ALLOWLIST_EXACT[@]}"; do
    [[ "$perm" == "$x" ]] && return 0
  done
  [[ "$perm" =~ $ALLOWLIST_PATTERN ]] && return 0
  return 1
}

# Extract every android:name from <uses-permission ...> / <uses-permission-sdk-23 ...> elements,
# tolerant of the tag's attributes being split across multiple lines (AAPT/manifest-merger output
# routinely does this — see the real merged manifest). -P (PCRE) + -z (NUL-separated, i.e. "the
# whole file is one string") is what makes a multi-line tag matchable at all with grep; both are
# standard GNU grep, present on every runner this project targets (Gitea self-hosted Linux,
# ubuntu-latest today). -o extracts only the matched tag text, which is then re-parsed for the name
# attribute — two greps, not one, because a tag can carry android:maxSdkVersion and other attributes
# before or after android:name and a single-pass regex trying to anchor on ordering would be fragile.
extract_permissions() {
  local file="$1"
  grep -Pzo '<uses-permission(-sdk-23)?\b[^>]*>' -- "$file" 2>/dev/null \
    | tr '\0' '\n' \
    | grep -oE 'android:name="[^"]+"' \
    | sed -E 's/^android:name="(.*)"$/\1/'
}

# Full <uses-permission ...> tag text (attributes included), one tag per output line (internal
# newlines within the original multi-line tag are collapsed to a space) — used for the
# maxSdkVersion hygiene check below, which needs an attribute extract_permissions() throws away.
#
# NOTE: the sentinel byte MUST be produced by bash's own $'\x01' ANSI-C quoting, not passed as the
# literal 4-character string '\x01' — some `tr` implementations (confirmed against this runner's)
# do not parse a `\xHH` hex escape in their own argument and instead treat it as four literal
# characters `\`, `x`, `0`, `1`, silently corrupting any tag text that happens to contain any of
# those four characters (e.g. `maxSdkVersion="30"` — verified the hard way, the first draft of this
# function shredded exactly that attribute on the real merged manifest and produced a false hygiene
# WARN). Letting bash resolve the escape before `tr` ever sees it sidesteps the portability gap.
extract_permission_tags() {
  local file="$1"
  grep -Pzo '<uses-permission(-sdk-23)?\b[^>]*>' -- "$file" 2>/dev/null \
    | tr '\0' $'\x01' \
    | tr '\n' ' ' \
    | tr $'\x01' '\n'
}

overall=0
declare -a found_variants=()

for variant in debug release; do
  variant_dir="$MANIFEST_ROOT/$variant"
  if [[ ! -d "$variant_dir" ]]; then
    qa_fail "PERMISSION GATE: no '$variant' manifest directory under $MANIFEST_ROOT."
    qa_fail "This check did NOT run for '$variant' — that is a failure, not a pass. Build it first,"
    qa_fail "e.g. (cd mobile && ./gradlew[.bat] :android:assemble${variant^} --no-daemon), which"
    qa_fail "produces the merged manifest as a side effect."
    overall=1
    continue
  fi

  mapfile -t manifests < <(find "$variant_dir" -type f -name 'AndroidManifest.xml')
  if [[ ${#manifests[@]} -eq 0 ]]; then
    qa_fail "PERMISSION GATE: '$variant_dir' exists but contains no AndroidManifest.xml. Unbuilt or"
    qa_fail "unexpected AGP output layout — this check did NOT run for '$variant'."
    overall=1
    continue
  fi

  found_variants+=("$variant")
  qa_info "checking $variant (${#manifests[@]} manifest file(s) found)"

  for manifest in "${manifests[@]}"; do
    rel="${manifest#"$REPO_ROOT"/}"
    mapfile -t perms < <(extract_permissions "$manifest")
    for perm in "${perms[@]}"; do
      [[ -z "$perm" ]] && continue
      if is_allowed "$perm"; then
        continue
      fi
      overall=1
      if [[ "$perm" == "android.permission.INTERNET" ]]; then
        qa_fail "PERMISSION GATE: android.permission.INTERNET present in $rel ($variant)."
        qa_fail ""
        qa_fail "  THIS IS THE CONSEQUENTIAL ONE. Adding it makes the following FALSE, as of this build:"
        qa_fail "    - docs/legal/PRIVACY_POLICY_DRAFT.md §3b bound 1: \"today our app requests no"
        qa_fail "      network permission at all ... it is something the app is incapable of doing.\""
        qa_fail "    - docs/legal/CLAIMS_REGISTER.md row A4: \"The app requests no network permission\""
        qa_fail "      (class VERIFIED)."
        qa_fail ""
        qa_fail "  INTERNET is EXPECTED to land eventually for the API client — §3b bound 1 says so"
        qa_fail "  itself. That is NOT what this failure means. It means: rewrite §3b bound 1 (it stops"
        qa_fail "  being a structural fact and becomes a code-review rule — the paragraph already"
        qa_fail "  drafts that transition, use it) and CLAIMS_REGISTER row A4 (VERIFIED -> re-justify"
        qa_fail "  or reclass) IN THIS SAME PR, then update this gate's allowlist. Do not let a build"
        qa_fail "  go green with the permission present and the policy still claiming its absence."
      else
        qa_fail "PERMISSION GATE: unexpected permission '$perm' in $rel ($variant), not on the"
        qa_fail "allowlist in this script's header. A permission not seen before is exactly what"
        qa_fail "decision 50 exists to catch — it means either a dependency silently added a"
        qa_fail "capability (the androidx.work / WAKE_LOCK+ACCESS_NETWORK_STATE+RECEIVE_BOOT_COMPLETED"
        qa_fail "precedent this gate exists because of) or a deliberate new permission that has not"
        qa_fail "had the privacy-policy / CLAIMS_REGISTER.md conversation yet. Resolve which, then"
        qa_fail "either remove the dependency that added it or extend the allowlist in this script"
        qa_fail "with a one-sentence justification, in the same PR as the corresponding doc update."
      fi
    done

    # Non-blocking hygiene check: ACCESS_FINE_LOCATION should carry maxSdkVersion="30" per the
    # documented rationale in AndroidManifest.xml. WARN only, never fails the gate — the allowlist
    # match above is the enforced contract; this is defense-in-depth visibility.
    fine_loc_tag="$(extract_permission_tags "$manifest" \
      | grep -F 'android:name="android.permission.ACCESS_FINE_LOCATION"' || true)"
    if [[ -n "$fine_loc_tag" ]] && ! printf '%s' "$fine_loc_tag" | grep -q 'android:maxSdkVersion="30"'; then
      qa_warn "$rel ($variant): ACCESS_FINE_LOCATION is present WITHOUT maxSdkVersion=\"30\" — the"
      qa_warn "documented justification in AndroidManifest.xml is specifically about pre-API31 scan"
      qa_warn "delivery. Not failing the gate over it (allowlist match is the enforced contract), but"
      qa_warn "worth a look — an uncapped grant asks Android 12+ users for something they do not need."
    fi
  done
done

if [[ ${#found_variants[@]} -lt 2 ]]; then
  qa_fail "PERMISSION GATE: could not check both variants (found: ${found_variants[*]:-none})."
  overall=1
fi

if [[ $overall -ne 0 ]]; then
  qa_fail "PERMISSION GATE: FAILED."
  exit 1
fi

qa_pass "PERMISSION GATE: PASSED. Both debug and release merged manifests contain only allowlisted"
qa_pass "  permissions (decision 50)."
exit 0
