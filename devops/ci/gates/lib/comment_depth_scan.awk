#!/usr/bin/awk -f
# devops/ci/gates/lib/comment_depth_scan.awk
#
# Nesting-aware Kotlin/Kotlin-script block-comment scanner. Owner: qa-test. Used by
# comment_nesting_gate.sh — see that file's header for the four-hit incident history this exists
# to catch mechanically instead of by reviewer vigilance.
#
# CONTRACT: run once per file — `awk -f comment_depth_scan.awk -- <file>`. Tracks block-comment
# NESTING DEPTH char-by-char across the WHOLE file (state persists line to line, unlike a
# line-oriented regex, which cannot see past one line at a time). At EOF:
#   depth == 0  -> silent, exit 0. (Includes a comment that legitimately nests AND closes, e.g.
#                  `/* outer /* inner */ still outer */` — nesting itself is valid Kotlin; only an
#                  UNBALANCED nest, which swallows the rest of the file, is the bug.)
#   depth != 0  -> one line per still-open comment, OUTERMOST FIRST, `line:col: ...`, exit 1.
#
# WHY CHAR-BY-CHAR STATE TRACKING RATHER THAN THE REGEX
#   `grep -rnE '^[[:space:]]*\*.*(/\*|\*/)'`
# only catches a `/*`/`*/` that lands on a KDoc CONTINUATION line (one that already starts with a
# bare `*`). It cannot tell whether the nesting it found actually balances by EOF — which is the
# only question that matters here, because the whole failure mode is silent-past-EOF (hit 3's
# Gradle error named a missing dependency, nowhere near the KDoc; hit 4's compiler error is
# "unclosed comment / expecting a top level declaration" at EOF, ten-plus lines from line 16). A
# scanner that tracks depth answers that question directly instead of leaving a human to add up
# opens and closes by eye. It also has no way to except a `/*` sitting inside a STRING or CHAR
# literal — the false positive this gate's own self-test pins on a "glob path as a string
# constant" fixture — without a second heuristic bolted on. Depth-tracking gets both for free by
# construction: a `/*`/`*/`/`//` encountered while inside a STRING/CHAR/raw-string state is just
# two ordinary characters, not a comment token, because the state machine does not evaluate
# comment-start tokens while inside a string state at all.
#
# WHAT THIS DOES NOT CATCH, stated rather than hidden:
#   - Kotlin string TEMPLATES (`"...${expr}..."`): once the opening `"` is seen this scanner treats
#     everything up to the next unescaped `"` as opaque STRING content, including the inside of a
#     `${ }` expression. An unescaped `"` inside such an expression (e.g. `"${if (x) "a" else "b"}"`)
#     closes the STRING state early. Checked against the live repo: zero such templates exist today.
#     Importantly, this gap can only produce a false POSITIVE (the string state exits too early, so
#     a `/*` immediately after might get misread as code) — it can never HIDE a real unclosed block
#     comment, which is the one failure mode this gate exists to prevent. Fails safe for this gate's
#     specific purpose.
#   - A single-line `"..."` or `'.'` that runs off the end of a line with no closing quote is reset
#     to CODE at end-of-line rather than carried into the next line. That is already a Kotlin syntax
#     error the compiler reports immediately, at the line it happened, with no ambiguity about the
#     cause — unlike the nested-comment trap, there is nothing confusing about where it points, so
#     it is out of this gate's scope by design, not by oversight.
#   - Kotlin raw strings (`"""…"""`) ARE tracked, so a `/*` inside one does not falsely start a
#     nest, but the closer is recognised on the first literal `"""` encountered. A raw string whose
#     content ends in a literal `"` immediately before its own closing `"""` (four-or-more
#     consecutive quote characters) is a known, accepted edge case, not exercised anywhere in this
#     repo today.
#
# EXIT: 0 clean. 1 with diagnostics on stdout if any block comment is still open at EOF.

BEGIN {
    state = "CODE"   # CODE | BLOCK | STRING | CHAR | TSTRING
    depth = 0
    stack_n = 0
}

{
    line = $0
    len = length(line)
    i = 1
    while (i <= len) {
        c1 = substr(line, i, 1)
        c2 = substr(line, i, 2)
        c3 = substr(line, i, 3)

        if (state == "CODE") {
            if (c2 == "//") {
                break  # rest of the line is a line comment; nothing after it can open/close anything
            } else if (c2 == "/*") {
                state = "BLOCK"; depth = 1
                stack_n++; stack_line[stack_n] = FNR; stack_col[stack_n] = i
                i += 2
            } else if (c3 == "\"\"\"") {
                state = "TSTRING"; i += 3
            } else if (c1 == "\"") {
                state = "STRING"; i += 1
            } else if (c1 == "'") {
                state = "CHAR"; i += 1
            } else {
                i += 1
            }
        } else if (state == "BLOCK") {
            if (c2 == "/*") {
                depth++
                stack_n++; stack_line[stack_n] = FNR; stack_col[stack_n] = i
                i += 2
            } else if (c2 == "*/") {
                depth--
                if (stack_n > 0) stack_n--
                if (depth == 0) { state = "CODE" }
                i += 2
            } else {
                i += 1
            }
        } else if (state == "STRING") {
            if (c1 == "\\") { i += 2 }
            else if (c1 == "\"") { state = "CODE"; i += 1 }
            else { i += 1 }
        } else if (state == "CHAR") {
            if (c1 == "\\") { i += 2 }
            else if (c1 == "'") { state = "CODE"; i += 1 }
            else { i += 1 }
        } else if (state == "TSTRING") {
            if (c3 == "\"\"\"") { state = "CODE"; i += 3 }
            else { i += 1 }
        }
    }
    # A single-line STRING/CHAR that ran off the end of the line is a separate, already-obvious
    # Kotlin syntax error (see header) — do not carry it forward as this gate's state.
    if (state == "STRING" || state == "CHAR") { state = "CODE" }
}

END {
    if (depth != 0) {
        for (k = 1; k <= stack_n; k++) {
            printf "%d:%d: block comment opened here (nest level %d) is still open at end of file\n", stack_line[k], stack_col[k], k
        }
        exit 1
    }
    exit 0
}
