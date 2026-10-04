package com.sshapp.data

data class Suggestion(
    val command: String,
    val description: String?,
    val timesUsed: Int,
) {
    val hasPlaceholder: Boolean get() = CatalogCommand.PLACEHOLDER.containsMatchIn(command)
}

/** Ranks command suggestions from the user's history on this host plus the curated catalog. */
object Suggester {
    fun suggest(input: String, history: List<HistoryEntry>, limit: Int = 8): List<Suggestion> {
        val q = input.trim()
        val now = System.currentTimeMillis()
        val descriptions = CommandCatalog.all.associate { it.command to it.description }

        if (q.isEmpty()) {
            val frequent = history.sortedByDescending { it.score(now) }.take(limit)
                .map { Suggestion(it.command, descriptions[it.command], it.count) }
            val filler = CommandCatalog.essentials
                .filter { e -> frequent.none { it.command == e.command } }
                .map { Suggestion(it.command, it.description, 0) }
            return (frequent + filler).take(limit)
        }

        val ql = q.lowercase()
        val scored = LinkedHashMap<String, Pair<Double, Suggestion>>()
        fun offer(cmd: String, score: Double, s: Suggestion) {
            if (cmd == q) return
            val prev = scored[cmd]
            if (prev == null || prev.first < score) scored[cmd] = score to s
        }

        for (h in history) {
            val cl = h.command.lowercase()
            val base = h.score(now)
            val match = when {
                cl.startsWith(ql) -> 100.0
                cl.split(' ').any { it.startsWith(ql) } -> 40.0
                cl.contains(ql) -> 20.0
                else -> continue
            }
            offer(h.command, match + base * 5, Suggestion(h.command, descriptions[h.command], h.count))
        }
        for (c in CommandCatalog.all) {
            val cl = c.command.lowercase()
            val match = when {
                cl.startsWith(ql) -> 60.0
                cl.split(' ').any { it.startsWith(ql) } -> 30.0
                cl.contains(ql) -> 15.0
                c.description.lowercase().contains(ql) -> 10.0
                else -> continue
            }
            val used = history.firstOrNull { it.command == c.command }?.count ?: 0
            offer(c.command, match, Suggestion(c.command, c.description, used))
        }
        return scored.values.sortedByDescending { it.first }.map { it.second }.take(limit)
    }
}
