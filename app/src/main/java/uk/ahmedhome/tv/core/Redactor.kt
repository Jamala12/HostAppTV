package uk.ahmedhome.tv.core

/**
 * Removes credential-bearing data from anything that may reach logs, error text or diagnostics.
 * Xtream URLs carry the username/password both as query parameters and as path segments.
 */
object Redactor {
    private val queryParam = Regex("""(?i)([?&](?:username|password|user|pass|token|key|auth)=)[^&#\s]*""")
    private val streamPath = Regex("""(?i)(/(?:live|movie|series|timeshift)/)[^/\s]+/[^/\s]+/""")
    private val userInfo = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^/@\s]+@""")

    fun redact(text: String?, vararg secrets: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text
        out = queryParam.replace(out, "$1***")
        out = streamPath.replace(out, "$1***/***/")
        out = userInfo.replace(out, "$1***@")
        for (secret in secrets) {
            if (!secret.isNullOrBlank() && secret.length >= 2) out = out.replace(secret, "***")
        }
        return out
    }
}
