package com.octopas.sexy.data

enum class DiffType { SAME, ADD, DEL, GAP }

data class DiffLine(val type: DiffType, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

/** Small line-based diff (LCS) for showing the AI's changes before committing. */
object LineDiff {
    private fun lines(s: String): List<String> =
        if (s.isEmpty()) emptyList() else s.replace("\r\n", "\n").removeSuffix("\n").split("\n")

    fun diff(old: String, new: String): List<DiffLine> {
        val a = lines(old)
        val b = lines(new)
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++

        val out = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until pre) out += DiffLine(DiffType.SAME, a[i], i + 1, i + 1)

        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)
        if (am.size.toLong() * bm.size > 12_000_000L) {
            // Too big for LCS on a phone: show as full replace.
            am.forEachIndexed { i, t -> out += DiffLine(DiffType.DEL, t, pre + i + 1, null) }
            bm.forEachIndexed { j, t -> out += DiffLine(DiffType.ADD, t, null, pre + j + 1) }
        } else {
            val n = am.size
            val m = bm.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i][j] = if (am[i] == bm[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && am[i] == bm[j] -> { out += DiffLine(DiffType.SAME, am[i], pre + i + 1, pre + j + 1); i++; j++ }
                    i < n && (j == m || dp[i + 1][j] >= dp[i][j + 1]) -> { out += DiffLine(DiffType.DEL, am[i], pre + i + 1, null); i++ }
                    else -> { out += DiffLine(DiffType.ADD, bm[j], null, pre + j + 1); j++ }
                }
            }
        }
        for (k in 0 until suf) {
            val ai = a.size - suf + k
            val bi = b.size - suf + k
            out += DiffLine(DiffType.SAME, a[ai], ai + 1, bi + 1)
        }
        return out
    }

    /** Keeps [context] unchanged lines around each change and collapses the rest into GAP rows. */
    fun compact(lines: List<DiffLine>, context: Int = 3): List<DiffLine> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { idx, l ->
            if (l.type != DiffType.SAME) {
                for (k in maxOf(0, idx - context)..minOf(lines.lastIndex, idx + context)) keep[k] = true
            }
        }
        val out = ArrayList<DiffLine>()
        var skipped = 0
        lines.forEachIndexed { idx, l ->
            if (keep[idx]) {
                if (skipped > 0) { out += DiffLine(DiffType.GAP, "$skipped unchanged lines"); skipped = 0 }
                out += l
            } else skipped++
        }
        if (skipped > 0 && out.isNotEmpty()) out += DiffLine(DiffType.GAP, "$skipped unchanged lines")
        return out
    }

    fun counts(lines: List<DiffLine>): Pair<Int, Int> =
        lines.count { it.type == DiffType.ADD } to lines.count { it.type == DiffType.DEL }
}
