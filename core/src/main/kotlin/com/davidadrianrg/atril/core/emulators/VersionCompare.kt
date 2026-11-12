package com.davidadrianrg.atril.core.emulators

/** Comparación tolerante de versiones tipo `1.4.2`, `v1.4`, `2024.10`. */
object VersionCompare {

    /** true si [latest] parece más nueva que [installed]. */
    fun isNewer(latest: String?, installed: String?): Boolean {
        if (latest.isNullOrBlank()) return false
        if (installed.isNullOrBlank()) return true
        val a = parse(latest)
        val b = parse(installed)
        if (a.isEmpty() || b.isEmpty()) return latest.trim() != installed.trim()
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(value: String): List<Int> {
        val cleaned = value.trim().removePrefix("v").removePrefix("V")
        val numbers = Regex("""\d+""").findAll(cleaned).map { it.value.toIntOrNull() ?: 0 }.toList()
        return numbers
    }
}
