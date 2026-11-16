package com.davidadrianrg.atril.core.report

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIssueUrlTest {

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    @Test
    fun `encodes title, body and labels so they survive the query string`() {
        val url = GitHubIssueUrl.prefilled("  Crash al abrir & cerrar #1\nmás detalle", "- OS: Linux")
        assertTrue(url.startsWith("https://github.com/${GitHubIssueUrl.REPO}/issues/new?"))
        val p = params(url)
        assertEquals("[Report] Crash al abrir & cerrar #1", p["title"])
        assertEquals("Crash al abrir & cerrar #1\nmás detalle\n\n---\n- OS: Linux\n", p["body"])
        assertEquals("user-report", p["labels"])
    }

    @Test
    fun `caps title and body length and skips empty diagnostics`() {
        val p = params(GitHubIssueUrl.prefilled("x".repeat(9000), ""))
        assertEquals("[Report] " + "x".repeat(80), p["title"])
        assertEquals(7000, p.getValue("body").length)
        assertTrue("---" !in p.getValue("body"))
    }
}
