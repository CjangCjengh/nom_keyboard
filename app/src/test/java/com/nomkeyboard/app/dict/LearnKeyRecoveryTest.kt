package com.nomkeyboard.app.dict

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the segment-mode learner's ability to recover real Vietnamese readings when
 * the user types tone-marked letters that don't match any of the picked Nom's legal
 * pronunciations. Corresponds to the regression we hit where typing `bình ki` and
 * picking 病 + 嬌 (two single-char picks) ended up recording the verbatim-but-wrong
 * `bình ki -> 病嬌` in the user dictionary.
 *
 * The learner's public contract (as exercised here):
 *   1. For each single-char step, if [NomDictionary.isLegalReadingForNom] says the
 *      user's tone-marked input is already a legal reading of the picked Nom, keep
 *      it verbatim. User-dictionary single-syllable entries count as legal (user
 *      overrides beat the bundle).
 *   2. Otherwise, pick the closest legal reading by edit distance (ties broken by
 *      bundled-dictionary order) via [NomDictionary.pickClosestReadingForNom].
 *   3. The final user-dict key is the space-joined per-step reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LearnKeyRecoveryTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        NomDictionary.ensureLoaded(ctx)
        // Start every test from a clean user dictionary so cross-test pollution can't
        // accidentally make `bình` a legal reading of 病 (which one of the tests
        // specifically needs to add itself).
        UserDictionary.ensureLoaded(ctx)
        UserDictionary.clearAll(ctx)
    }

    @After
    fun tearDown() {
        UserDictionary.clearAll(ctx)
    }

    /**
     * Helper mirroring the per-step logic in
     * [com.nomkeyboard.app.NomInputMethodService.onCandidatePicked]:
     *   - If the user-typed [consumedRaw] is already a legal reading of [nom],
     *     use it verbatim.
     *   - Otherwise upgrade to the closest legal reading.
     */
    private fun learnKeyForStep(nom: String, consumedRaw: String): String {
        return if (NomDictionary.isLegalReadingForNom(nom, consumedRaw)) {
            consumedRaw.lowercase().trim()
        } else {
            NomDictionary.pickClosestReadingForNom(nom, consumedRaw)
        }
    }

    /** Joins the per-step learn keys into the full user-dict key, mirroring
     *  [com.nomkeyboard.app.NomInputMethodService.learnUserPhrases]. */
    private fun fullKey(vararg steps: Pair<String, String>): String =
        steps.joinToString(" ") { (nom, raw) -> learnKeyForStep(nom, raw).trim() }
            .replace(Regex("\\s+"), " ").trim().lowercase()

    /**
     * Scenario 1: empty user dictionary, lenient mode, user types `bềnh ki` and picks
     * 病 + 嬌 in segment mode. Expected learned key: `bệnh kiều`.
     *
     *   - `bềnh` is NOT a legal reading of 病 (bundled readings are bệnh/bịnh/bạnh/nạch).
     *     Closest by edit distance:
     *       bềnh vs bệnh = 1  (ề vs ệ)     <-- winner
     *       bềnh vs bịnh = 2
     *       bềnh vs bạnh = 2
     *   - `ki` is NOT a legal reading of 嬌 (all 嬌 readings strip to `kieu`).
     *     Closest by edit distance picks `kiều` (primary reading by dictionary
     *     position, tied at distance 2 with kiêu/kiểu/... and broken by order).
     */
    @Test
    fun `empty user dict - bềnh ki picking 病嬌 learns bệnh kiều`() {
        val learned = fullKey("病" to "bềnh", "嬌" to "ki")
        assertEquals("bệnh kiều", learned)
    }

    /**
     * Scenario 2: user dictionary already contains `bềnh: 病` (user manually taught
     * the IME that this tone-marked spelling is a legal reading of 病). In this
     * case the learner must respect the override and keep `bềnh` verbatim while
     * still upgrading the truncated `ki` to `kiều`. Expected: `bềnh kiều`.
     */
    @Test
    fun `user override bềnh equals 病 - bềnh ki picking 病嬌 learns bềnh kiều`() {
        UserDictionary.putEntry(ctx, "bềnh", listOf("病"))
        val learned = fullKey("病" to "bềnh", "嬌" to "ki")
        assertEquals("bềnh kiều", learned)
    }
}
