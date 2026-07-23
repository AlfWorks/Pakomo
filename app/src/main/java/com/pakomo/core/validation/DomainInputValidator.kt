package com.pakomo.core.validation

import java.net.IDN
import java.util.Locale

object DomainInputValidator {
    fun normalizeOrNull(input: String): String? {
        val raw = input.trim()
            .lowercase(Locale.ROOT)
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trimEnd('.')
        if (raw.isBlank() || raw.length > 253 || raw.contains(' ')) return null
        val ascii = runCatching { IDN.toASCII(raw) }.getOrNull() ?: return null
        val labels = ascii.split('.')
        if (labels.size < 2) return null
        if (labels.any { label ->
                label.isBlank() ||
                    label.length > 63 ||
                    label.startsWith('-') ||
                    label.endsWith('-') ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }
        ) return null
        return ascii
    }
}
