package com.radius.shared.protocol.vectors

import com.radius.shared.protocol.ProtocolError
import kotlin.test.Test

/**
 * The manifest must describe the vectors that actually exist.
 *
 * `index.json` is what CI, both platforms and every future reviewer trust when they ask "did all
 * of them run?". A manifest that misstates its own contents is worse than no manifest, because it
 * launders an unexecuted case into a green count. This test recomputes the counts from the files
 * and fails if the two ever drift.
 */
class VectorManifestTest {

    /**
     * THE COUNTING RULE, normative from here on: a CASE is one named, independently executable
     * assertion unit. That means every entry of `cases`, `invalid`, `property_assertions`,
     * `skew_window_across_seam`, `self_eid_rejection.cases` and `accepted_not_rejected`.
     *
     * The earlier manifest counted `cases` only for most files but something else for
     * `key_rotation.json`, which is how it arrived at a total nobody could reproduce.
     */
    private val countedSections = listOf(
        "cases",
        "invalid",
        "property_assertions",
        "skew_window_across_seam",
        "destruction_at_seam",
        "accepted_not_rejected",
    )

    private fun countCases(file: Map<String, Any?>): Int {
        var total = 0
        for (section in countedSections) {
            if (file.containsKey(section)) total += file.arr(section).size
        }
        // self_eid_rejection nests its cases one level down.
        if (file.containsKey("self_eid_rejection")) {
            total += file.obj("self_eid_rejection").arr("cases").size
        }
        return total
    }

    @Test
    fun everyManifestedFileExistsParsesAndMatchesItsDeclaredCount() {
        val index = VectorSource.index
        val files = index.arr("files").map { it.asObj() }
        val run = VectorRun("manifest")

        var grandTotal = 0
        for (f in files) {
            val name = f.str("file")
            run.case(name) {
                val parsed = VectorSource.load(name)
                val actual = countCases(parsed)
                grandTotal += actual
                expectEquals(
                    f.int("cases"),
                    actual,
                    "$name declares ${f.int("cases")} cases but contains $actual executable cases",
                )
            }
        }

        run.case("case_total") {
            expectEquals(index.int("case_total"), grandTotal, "case_total")
        }
        run.finish(files.size + 1)
        println("VECTORS TOTAL: $grandTotal executable cases across ${files.size} files")
    }

    /** Every error code the manifest advertises must exist in the implementation, and vice versa. */
    @Test
    fun manifestErrorCodesMatchTheImplementation() {
        val declared = VectorSource.index.arr("error_codes").map { it as String }.toSet()
        val implemented = ProtocolError.entries.map { it.name }.toSet()

        expectEquals(
            emptySet<String>(),
            declared - implemented,
            "manifest declares codes the implementation does not have",
        )
        expectEquals(
            emptySet<String>(),
            implemented - declared,
            "implementation has codes the manifest does not declare",
        )
    }

    /**
     * The service UUID is provisional and MUST NOT ship (decision 34, blocker B9). This asserts
     * the two artefacts still agree on the value, so nobody quietly changes one of them.
     */
    @Test
    fun serviceUuidMatchesTheAdvAssemblyVectors() {
        val adv = VectorSource.load("adv_assembly.json").obj("service_uuid")
        expectEquals(
            com.radius.shared.protocol.AdvertisementCodec.SERVICE_UUID16,
            adv.str("uuid16").removePrefix("0x").removePrefix("0X").toInt(16),
            "service uuid16",
        )
        expectTrue(
            adv.str("status").contains("MUST NOT SHIP"),
            "the provisional-UUID warning has been removed from the vectors — B9 is not closed",
        )
    }
}
