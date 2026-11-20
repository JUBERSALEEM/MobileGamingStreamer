package com.jubersaleem.mobilegamingstreamer

import java.net.URI
import java.util.Locale

/** Strict, side-effect-free validation for the RTMP server URL entered by the user. */
internal object StreamUrlValidator {
    fun isValidRtmpServerUrl(value: String): Boolean {
        val candidate = value.trim()
        if (candidate.isEmpty() || candidate.any { it.isWhitespace() }) return false
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            (scheme == "rtmp" || scheme == "rtmps") &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null
        } catch (_: Exception) {
            false
        }
    }
}
