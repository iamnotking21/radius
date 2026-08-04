package com.radius.shared.protocol.vectors

import java.io.File

/**
 * Loads the JSON vector files under `mobile/protocol/vectors` FROM DISK, through `index.json`.
 *
 * READING THE REAL FILES IS THE POINT. An embedded or generated copy would let the vectors and the
 * implementation drift apart silently, which destroys the one property that makes a vector file
 * worth having: that a diff in it means a diff in BEHAVIOUR.
 *
 * WHY THIS LIVES IN `androidUnitTest` AND NOT `commonTest`. `commonTest` has no filesystem. Running
 * the same runner on Kotlin/Native needs the vector text embedded at build time (a codegen task in
 * `mobile/shared/build.gradle.kts`), and that file belongs to android-kotlin. See the HANDOFF in
 * the report. Today the runner executes on the JVM against the ONE shared implementation both
 * platforms consume (ADR-007), so it does test the shipped code — it just does not yet prove that
 * the Kotlin/Native backend produces identical results.
 */
internal object VectorSource {

    /** `mobile/protocol/vectors`, located by walking up from the test working directory. */
    val dir: File by lazy {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null) {
            val vectors = File(candidate, "protocol/vectors/index.json")
            if (vectors.isFile) return@lazy vectors.parentFile
            val nested = File(candidate, "mobile/protocol/vectors/index.json")
            if (nested.isFile) return@lazy nested.parentFile
            candidate = candidate.parentFile
        }
        error(
            "Could not locate mobile/protocol/vectors/index.json from ${File(".").absolutePath}. " +
                "The conformance vectors are not optional — a build that cannot find them must fail.",
        )
    }

    val index: Map<String, Any?> by lazy { load("index.json") }

    fun load(fileName: String): Map<String, Any?> {
        val f = File(dir, fileName)
        check(f.isFile) { "vector file missing: ${f.absolutePath}" }
        return MiniJson.parse(f.readText()).asObj()
    }
}

/**
 * Runs every case in a vector file, collects ALL failures, and fails once with the complete list.
 *
 * Stopping at the first failure is the wrong shape here. When a codec change breaks conformance it
 * usually breaks a whole class of cases, and the useful information is which class — not which
 * alphabetically-first member of it.
 */
internal class VectorRun(private val label: String) {

    private val failures = ArrayList<String>()
    private val executed = ArrayList<String>()

    fun case(name: String, body: () -> Unit) {
        executed.add(name)
        try {
            body()
        } catch (t: Throwable) {
            failures.add("  [$label/$name] ${t.message ?: t::class.simpleName}")
        }
    }

    val executedCount: Int get() = executed.size

    fun finish(expectedCases: Int) {
        val problems = ArrayList<String>()
        if (executed.size != expectedCases) {
            problems.add(
                "  [$label] executed ${executed.size} cases, manifest/file declares $expectedCases. " +
                    "A vector that does not run is not a vector.",
            )
        }
        problems.addAll(failures)
        if (problems.isNotEmpty()) {
            throw AssertionError(
                "$label: ${failures.size} of ${executed.size} cases failed\n" + problems.joinToString("\n"),
            )
        }
        println("VECTORS OK  $label: ${executed.size} cases")
    }
}

internal fun expectEquals(expected: Any?, actual: Any?, what: String) {
    if (expected != actual) throw AssertionError("$what: expected <$expected> but was <$actual>")
}

internal fun expectTrue(condition: Boolean, what: String) {
    if (!condition) throw AssertionError(what)
}

internal fun expectClose(expected: Double, actual: Double, tolerance: Double, what: String) {
    val delta = kotlin.math.abs(expected - actual)
    if (delta > tolerance) {
        throw AssertionError("$what: expected <$expected> but was <$actual> (delta $delta > $tolerance)")
    }
}
