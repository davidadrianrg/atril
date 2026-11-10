package com.davidadrianrg.atril.core.sync

/**
 * Helpers that make untrusted values safe to write in a single log line.
 *
 * Save names and exception messages come from the server: newlines or other
 * control characters in them could forge extra log entries. Callers log a
 * fixed classification plus these helpers, never `Throwable.message`.
 */
internal object LogSafe {

    /** Upper bound for an untrusted name included in a log line. */
    const val MAX_NAME_LENGTH = 120

    private const val UNNAMED = "(unnamed)"

    /**
     * Normalizes an untrusted name for logging.
     *
     * Drops ISO control characters and the Unicode line separators U+2028 and
     * U+2029, trims the result and caps it at [MAX_NAME_LENGTH] characters.
     *
     * @param raw untrusted name (may be null).
     * @return a single-line, bounded name, or `"(unnamed)"` when nothing is left.
     */
    fun fileName(raw: String?): String {
        if (raw.isNullOrBlank()) return UNNAMED
        val cleaned = raw
            .filterNot { it.isISOControl() || it == '\u2028' || it == '\u2029' }
            .trim()
        return cleaned.take(MAX_NAME_LENGTH).ifEmpty { UNNAMED }
    }

    /**
     * Classifies [error] by its simple class name only.
     *
     * Never includes the message, the cause chain or any server-provided data.
     *
     * @param error failure to classify.
     * @return the simple class name, or `"Throwable"` when it is blank.
     */
    fun exception(error: Throwable): String =
        error.javaClass.simpleName.ifBlank { "Throwable" }
}
