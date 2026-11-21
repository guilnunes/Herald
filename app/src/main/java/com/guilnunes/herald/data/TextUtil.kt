package com.guilnunes.herald.data

object TextUtil {

    private val ID_REGEX = Regex("([0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12})")

    /**
     * Pulls a Notion page id out of shared text: a notion.so / notion.site URL or a raw id.
     * Uses the last id found in the URL path, ignoring query and fragment.
     */
    fun extractPageId(shared: String): String? {
        val candidate = shared.trim().split(Regex("\\s+")).firstOrNull { "notion" in it || ID_REGEX.containsMatchIn(it) }
            ?: return null
        val path = candidate.substringBefore('?').substringBefore('#')
        val match = ID_REGEX.findAll(path).lastOrNull() ?: return null
        return normalizeId(match.value)
    }

    fun normalizeId(id: String): String {
        val hex = id.replace("-", "").lowercase()
        if (hex.length != 32) return id
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private val ABBREVIATIONS = setOf(
        "e.g", "i.e", "etc", "vs", "mr", "mrs", "ms", "dr", "sr", "jr", "st", "fig", "no", "approx", "sra", "dra", "ex",
    )

    /**
     * Splits text into sentence ranges (start inclusive, end exclusive) over the original string,
     * so the UI can highlight exactly what is being spoken. Long sentences are cut at commas or spaces
     * to stay well below the TTS input limit.
     */
    fun sentences(text: String, maxLen: Int = 400): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        var i = 0
        val n = text.length
        fun emit(endExclusive: Int) {
            var s = start
            while (s < endExclusive && text[s].isWhitespace()) s++
            var e = endExclusive
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) out += splitLong(text, s, e, maxLen)
            start = endExclusive
        }
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                emit(i + 1)
            } else if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < n && (text[j] == '.' || text[j] == '!' || text[j] == '?' || text[j] == '"' || text[j] == '\'' || text[j] == ')' || text[j] == '”')) j++
                val atBoundary = j >= n || text[j].isWhitespace()
                if (atBoundary && !(c == '.' && isAbbreviation(text, i)) && !(c == '.' && isNumberDot(text, i))) {
                    emit(j)
                    i = j
                    continue
                }
            }
            i++
        }
        emit(n)
        return out
    }

    private fun isAbbreviation(text: String, dot: Int): Boolean {
        var s = dot - 1
        while (s >= 0 && (text[s].isLetter() || text[s] == '.')) s--
        val word = text.substring(s + 1, dot).lowercase()
        return word in ABBREVIATIONS || (word.length == 1 && word[0].isLetter())
    }

    private fun isNumberDot(text: String, dot: Int): Boolean =
        dot > 0 && text[dot - 1].isDigit() && dot + 1 < text.length && text[dot + 1].isDigit()

    private fun splitLong(text: String, s: Int, e: Int, maxLen: Int): List<IntRange> {
        if (e - s <= maxLen) return listOf(s until e)
        val parts = mutableListOf<IntRange>()
        var a = s
        while (e - a > maxLen) {
            val window = text.substring(a, a + maxLen)
            var cut = window.lastIndexOfAny(charArrayOf(',', ';', ':'))
            if (cut < maxLen / 3) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = maxLen - 1
            val b = a + cut + 1
            var trimmed = b
            while (trimmed > a && text[trimmed - 1].isWhitespace()) trimmed--
            parts += a until trimmed
            a = b
            while (a < e && text[a].isWhitespace()) a++
        }
        if (a < e) parts += a until e
        return parts
    }
}
