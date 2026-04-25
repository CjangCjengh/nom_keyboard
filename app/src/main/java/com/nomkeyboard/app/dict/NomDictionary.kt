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

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val t0 = System.currentTimeMillis()
            loadTsv(context, "nom_dict_single.tsv", singleMap, asciiSingleIndex)
            loadTsv(context, "nom_dict_word.tsv", wordMap, asciiWordIndex)
            loaded = true
            Log.i(TAG, "dictionary loaded: single=${singleMap.size}, word=${wordMap.size}, cost=${System.currentTimeMillis() - t0}ms")
        }
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
