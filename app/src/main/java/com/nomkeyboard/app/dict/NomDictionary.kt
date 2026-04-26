package com.nomkeyboard.app.dict

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * Singleton Nom-character dictionary loader.
 *
 * Data files (under assets/):
 *   - nom_dict_single.tsv     Single-syllable dictionary. key: Vietnamese syllable (with diacritics) -> list<Nom char>
 *   - nom_dict_word.tsv       Flattened multi-syllable index. key: compound word (with or without spaces) -> list<Nom word>
 *   - nom_dict_word_raw.tsv   Raw multi-syllable dictionary (camelCase keys, kept for reference, unused at runtime)
 *
 * In addition, an "ascii key -> original key(s)" index is built so that users who type
 * plain ASCII without diacritics can still get reasonable matches.
 */
object NomDictionary {
    private const val TAG = "NomDict"
    private const val PREFIX_LIMIT = 24

    private val singleMap: HashMap<String, List<String>> = HashMap(7000)
    private val wordMap: HashMap<String, List<String>> = HashMap(3000)
    // ascii key -> list of original keys (kept in dictionary order)
    private val asciiSingleIndex: HashMap<String, MutableList<String>> = HashMap(5000)
    private val asciiWordIndex: HashMap<String, MutableList<String>> = HashMap(2500)
    /**
     * Viết tắt (abbreviated-input) helper index: for every multi-syllable word, remember
     * the ascii form of each syllable so a segment-by-segment prefix match can be done
     * without re-splitting on every query. Value is an array of lowercase, diacritic-
     * stripped syllables in the same order as the original key.
     *
     * Example: key "quốc gia" -> ["quoc", "gia"].
     */
    private val wordSyllablesAscii: HashMap<String, Array<String>> = HashMap(3000)
    /**
     * Viết tắt helper: word keys bucketed by syllable count. Keys are the **number of
     * syllables** (2..N); values are the word keys with exactly that many syllables,
     * preserved in dictionary-insertion order so candidate ranking stays deterministic.
     */
    private val wordsBySyllableCount: HashMap<Int, MutableList<String>> = HashMap(8)
    /**
     * Viết tắt helper: first-letter bucketing on top of [wordsBySyllableCount]. Key is
     * `"<count>|<firstAsciiChar>"`, e.g. `"2|q"` holds every 2-syllable word whose
     * first syllable starts with `q`. Used as a cheap pre-filter so the DP verifier
     * only runs on entries that could plausibly match.
     */
    private val wordsBySyllableCountAndFirstChar: HashMap<String, MutableList<String>> = HashMap(500)

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val t0 = System.currentTimeMillis()
            loadTsv(context, "nom_dict_single.tsv", singleMap, asciiSingleIndex)
            loadTsv(context, "nom_dict_word.tsv", wordMap, asciiWordIndex)
            buildVietTatIndex()
            loaded = true
            Log.i(TAG, "dictionary loaded: single=${singleMap.size}, word=${wordMap.size}, words2syl=${wordsBySyllableCount[2]?.size ?: 0}, cost=${System.currentTimeMillis() - t0}ms")
        }
    }

    /**
     * Build the viết tắt (abbreviated-input) helper indices. We bucket every
     * space-separated multi-syllable word by its syllable count AND by the first
     * ascii character of its first syllable, which are the two cheapest filters we
     * can apply before running the per-word DP verifier in [lookupWordByVietTat].
     */
    private fun buildVietTatIndex() {
        for (origKey in wordMap.keys) {
            if (!origKey.contains(' ')) continue
            val sylls = origKey.split(' ').filter { it.isNotEmpty() }
            if (sylls.size < 2) continue
            val asciiSylls = Array(sylls.size) { i -> stripDiacritics(sylls[i].lowercase()) }
            // Skip entries with any empty ascii syllable – defensive, shouldn't happen.
            if (asciiSylls.any { it.isEmpty() }) continue
            wordSyllablesAscii[origKey] = asciiSylls
            wordsBySyllableCount.getOrPut(asciiSylls.size) { ArrayList(512) }.add(origKey)
            val firstChar = asciiSylls[0][0]
            val bucketKey = "${asciiSylls.size}|$firstChar"
            wordsBySyllableCountAndFirstChar.getOrPut(bucketKey) { ArrayList(64) }.add(origKey)
        }
    }

    /**
     * @return true iff [asciiLower] is a prefix of at least one ascii single-syllable
     *   key. Used by the viết tắt splitter to detect "still mid-syllable" inputs so it
     *   stays out of the way for normal typing like `qu`, `ngh`, `an`. Empty input
     *   returns true (every key starts with the empty string) so callers should guard.
     */
    fun hasAsciiSinglePrefix(asciiLower: String): Boolean {
        if (asciiLower.isEmpty()) return true
        for (k in asciiSingleIndex.keys) {
            if (k.startsWith(asciiLower)) return true
        }
        return false
    }

    /**
     * @return true iff [asciiLower] is an exact ascii single-syllable key (e.g. `sao`,
     *   `quoc`). Used by the viết tắt splitter to greedy-swallow real syllable chunks
     *   mid-run so that inputs like `tsao` cleanly split into `[t, sao]`.
     */
    fun isAsciiSingleKey(asciiLower: String): Boolean {
        if (asciiLower.isEmpty()) return false
        return asciiSingleIndex.containsKey(asciiLower)
    }

    /**
     * Return every single-syllable candidate whose ascii syllable key STARTS WITH
     * [prefix]. Used for the "single syllable -> single character only" mode so that
     * typing just `q` still surfaces every single-char candidate that could complete
     * to a real syllable. [prefix] is lowercased and diacritic-stripped internally.
     *
     * Results keep the bundled dictionary's original ordering (common syllables first)
     * so popular readings float to the top without extra sorting work on our side.
     */
    fun lookupSinglePrefix(prefix: String, limit: Int = PREFIX_LIMIT): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = stripDiacritics(prefix.lowercase())
        if (p.isEmpty()) return emptyList()
        val result = LinkedHashSet<String>()
        // First: prefer exact-ascii hits (these are usually the "right" pronunciation).
        asciiSingleIndex[p]?.forEach { k ->
            singleMap[k]?.let { values ->
                for (v in values) {
                    result.add(v)
                    if (result.size >= limit) return result.toList()
                }
            }
        }
        // Then: sweep the ascii-single index for longer keys that start with p. We
        // iterate the whole map because the index isn't sorted by key; for ~5000
        // entries this is cheap enough on every keystroke.
        for ((asciiKey, origs) in asciiSingleIndex) {
            if (result.size >= limit) break
            if (asciiKey.length > p.length && asciiKey.startsWith(p)) {
                for (orig in origs) {
                    singleMap[orig]?.let { values ->
                        for (v in values) {
                            result.add(v)
                            if (result.size >= limit) break
                        }
                    }
                    if (result.size >= limit) break
                }
            }
        }
        return result.toList()
    }

    /**
     * Viết tắt compound lookup.
     *
     * Given a list of user-typed [segments] (each segment is a non-empty ascii run that
     * the splitter produced, e.g. `["t", "sao"]` for "tsao"), return every bundled word
     * whose syllables can be matched segment-by-segment, where each syllable's ascii
     * form STARTS WITH the corresponding segment.
     *
     * Concretely, for a candidate word with ascii syllables `[a_1, a_2, ..., a_n]`:
     *   match iff  n == segments.size  AND  a_i.startsWith(segments[i]) for all i.
     *
     * Example matches:
     *   segments = ["q", "g"]      -> "quốc gia", every "q*"+"g*" 2-syllable word
     *   segments = ["t", "sao"]    -> "tại sao" (tai startsWith t ✓, sao startsWith sao ✓)
     *   segments = ["n", "ma"]     -> "nhưng mà" (nhung startsWith n ✓, ma startsWith ma ✓)
     *   segments = ["nh", "ma"]    -> "nhưng mà" too (nhung startsWith nh ✓, ma startsWith ma ✓)
     *
     * Returns a list of `(origKey, values)` pairs preserving bundled-dictionary order so
     * the caller can mix these with user-dictionary and recency data.
     */
    fun lookupWordByVietTat(
        segments: List<String>,
        limit: Int = PREFIX_LIMIT
    ): List<Pair<String, List<String>>> {
        if (segments.size < 2) return emptyList()
        if (segments.any { it.isEmpty() }) return emptyList()
        val n = segments.size
        val firstChar = segments[0][0]
        val bucket = wordsBySyllableCountAndFirstChar["$n|$firstChar"] ?: return emptyList()
        val result = ArrayList<Pair<String, List<String>>>()
        for (origKey in bucket) {
            if (result.size >= limit) break
            val asciiSylls = wordSyllablesAscii[origKey] ?: continue
            if (asciiSylls.size != n) continue
            var ok = true
            for (i in 0 until n) {
                if (!asciiSylls[i].startsWith(segments[i])) { ok = false; break }
            }
            if (!ok) continue
            val values = wordMap[origKey] ?: continue
            result.add(origKey to values)
        }
        return result
    }

    private fun loadTsv(
        context: Context,
        name: String,
        target: HashMap<String, List<String>>,
        asciiIdx: HashMap<String, MutableList<String>>
    ) {
        try {
            context.assets.open(name).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { br ->
                    var line = br.readLine()
                    while (line != null) {
                        if (line.isNotEmpty()) {
                            val parts = line.split('\t')
                            if (parts.size >= 2) {
                                val k = parts[0]
                                val values = parts.drop(1).filter { it.isNotEmpty() }
                                if (values.isNotEmpty()) {
                                    target[k] = values
                                    val ascii = stripDiacritics(k)
                                    // record the mapping ascii -> original key so ascii queries also hit
                                    asciiIdx.getOrPut(ascii) { ArrayList(2) }.add(k)
                                }
                            }
                        }
                        line = br.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to load $name", e)
        }
    }

    /**
     * Look up candidates for a single Vietnamese syllable (with or without diacritics).
     * Order:
     *   1. exact match (with diacritics),
     *   2. all entries whose ascii form equals the query's ascii form (deduplicated).
     */
    fun lookupSingle(query: String): List<String> {
        if (query.isEmpty()) return emptyList()
        val q = query.lowercase()
        val result = LinkedHashSet<String>()
        // User dictionary hits take priority so the user's overrides float to the top.
        result.addAll(UserDictionary.lookupSingle(q))
        singleMap[q]?.let { result.addAll(it) }
        val qAscii = stripDiacritics(q)
        asciiSingleIndex[qAscii]?.forEach { k ->
            singleMap[k]?.let { result.addAll(it) }
        }
        return result.toList()
    }

    /**
     * Look up candidates for a compound word (possibly multi-syllable).
     * Tries several normalisations: original / no-space / ascii / ascii-no-space.
     */
    fun lookupWord(query: String): List<String> {
        if (query.isEmpty()) return emptyList()
        val q = query.lowercase()
        val result = LinkedHashSet<String>()
        // User dictionary hits are prepended so user overrides win over the bundled data.
        result.addAll(UserDictionary.lookupWord(q))
        wordMap[q]?.let { result.addAll(it) }
        wordMap[q.replace(" ", "")]?.let { result.addAll(it) }
        val qAscii = stripDiacritics(q)
        asciiWordIndex[qAscii]?.forEach { k -> wordMap[k]?.let { result.addAll(it) } }
        val qAsciiNoSp = qAscii.replace(" ", "")
        asciiWordIndex[qAsciiNoSp]?.forEach { k -> wordMap[k]?.let { result.addAll(it) } }
        return result.toList()
    }

    /**
     * Combined lookup: try compound word first, then fall back to single-syllable candidates,
     * and finally – if the caller is still typing – do a prefix search over the compound index
     * so partial inputs such as "anhquo" still surface useful suggestions (e.g. the candidates
     * for "anhquoc" -> 英國) instead of an empty bar.
     */
    fun lookup(query: String): List<String> {
        if (query.isEmpty()) return emptyList()
        val merged = LinkedHashSet<String>()
        merged.addAll(lookupWord(query))
        merged.addAll(lookupSingle(query))
        // User dictionary prefix matches come first so user-added compound completions win.
        merged.addAll(UserDictionary.lookupPrefix(query, PREFIX_LIMIT))
        merged.addAll(lookupPrefix(query, PREFIX_LIMIT))
        return merged.toList()
    }

    /**
     * Return candidates whose (ascii, no-space) compound key STARTS WITH the ascii form of
     * [query]. Useful for incremental typing: the user has not finished the syllable yet but
     * we still want to show plausible compound completions.
     *
     * The returned list is capped at [limit] entries to keep the candidate strip responsive.
     */
    fun lookupPrefix(query: String, limit: Int = PREFIX_LIMIT): List<String> {
        if (query.isEmpty()) return emptyList()
        val qAscii = stripDiacritics(query.lowercase()).replace(" ", "")
        if (qAscii.isEmpty()) return emptyList()
        val result = LinkedHashSet<String>()
        for ((asciiKey, originals) in asciiWordIndex) {
            if (result.size >= limit) break
            if (asciiKey.length > qAscii.length && asciiKey.startsWith(qAscii)) {
                for (orig in originals) {
                    wordMap[orig]?.let { values ->
                        for (v in values) {
                            result.add(v)
                            if (result.size >= limit) break
                        }
                    }
                    if (result.size >= limit) break
                }
            }
        }
        return result.toList()
    }

    /**
     * Split the query by whitespace and look up candidates per syllable.
     * Useful for long queries that the user typed with spaces (e.g. "tại sao").
     */
    fun lookupBySyllables(query: String): List<List<String>> {
        val syllables = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return syllables.map { lookupSingle(it) }
    }

    /**
     * Remove Vietnamese diacritics (and map đ/Đ to d/D).
     * Implementation: NFD decompose, strip combining marks, then handle đ/Đ manually.
     */
    fun stripDiacritics(s: String): String {
        if (s.isEmpty()) return s
        val normalized = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(normalized.length)
        for (ch in normalized) {
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt()) continue
            when (ch) {
                'đ', 'Đ' -> sb.append(if (ch == 'Đ') 'D' else 'd')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
