package com.davidadrianrg.atril.core.remote

/**
 * Single validation rule for the RomM `platform_slug` field.
 *
 * The slug is server-controlled data that both platforms use as a path
 * component (`saves/<slug>`, `gamelists/<slug>`, media folders). A hostile
 * value such as `../../../../.config/autostart` must never reach a path
 * builder, so every read is funnelled through [sanitize].
 *
 * A valid slug is returned verbatim: no trimming, no lowercasing and no other
 * rewriting, because preferences and database keys use it as an identity.
 */
object PlatformSlug {

    /** Accepted form: lowercase alphanumeric first character, then up to 64 safe characters. */
    val RULE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    /**
     * Indicates whether [value] is an acceptable platform slug.
     *
     * @param value raw slug from the server or persisted storage (may be null).
     * @return `true` only when [value] matches [RULE].
     */
    fun isValid(value: String?): Boolean = value != null && RULE.matches(value)

    /**
     * Returns [value] unchanged when it is valid, or `null` otherwise.
     *
     * Total function: it never throws and never rewrites an accepted value.
     *
     * @param value raw slug from the server or persisted storage (may be null).
     * @return the same slug when valid, `null` for missing or hostile input.
     */
    fun sanitize(value: String?): String? = value?.takeIf(RULE::matches)
}
