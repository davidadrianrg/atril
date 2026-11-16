package com.davidadrianrg.atril.core.report

import java.net.URLEncoder

/** URL de una issue de GitHub prellenada, compartida por Android y escritorio. */
object GitHubIssueUrl {

    /** Repo donde se abren las issues de reportes de usuario. */
    const val REPO = "davidadrianrg/atril"

    fun prefilled(
        message: String,
        diagnostics: String,
        labels: String = "user-report",
        repo: String = REPO,
    ): String {
        val body = buildString {
            appendLine(message.trim())
            if (diagnostics.isNotBlank()) {
                appendLine()
                appendLine("---")
                appendLine(diagnostics.trim())
            }
        }.take(7000)
        val title = "[Report] " + message.trim().lineSequence().first().take(80)
        fun q(value: String) = URLEncoder.encode(value, "UTF-8")
        return "https://github.com/$repo/issues/new" +
            "?title=${q(title)}&body=${q(body)}&labels=${q(labels)}"
    }
}
