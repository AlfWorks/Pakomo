package com.pakomo.forwarding

import java.net.InetAddress

/**
 * Matches configured domains directly and remembers IPv4 addresses learned from plain DNS replies.
 * DNS-over-HTTPS/TLS cannot be observed by this policy.
 */
class DomainRoutingPolicy(
    domains: Collection<String>,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val configuredDomains = domains
        .mapNotNull(::normalizeDomain)
        .distinct()
    private val resolvedAddresses = mutableMapOf<String, Resolution>()

    @Synchronized
    fun shouldShape(host: String, port: Int): Boolean {
        if (port == DNS_PORT) return false
        val normalized = host.trim().trimEnd('.').lowercase()
        if (configuredDomains.any { normalized.matchesDomain(it) }) return true
        val resolution = resolvedAddresses[normalized] ?: return false
        if (resolution.expiresAtSeconds <= nowSeconds()) {
            resolvedAddresses.remove(normalized)
            return false
        }
        return configuredDomains.any { resolution.domain.matchesDomain(it) }
    }

    @Synchronized
    fun observeDnsResponse(message: ByteArray) {
        val parsed = runCatching { DnsResponseParser.parse(message) }.getOrNull() ?: return
        val now = nowSeconds()
        parsed.forEach { answer ->
            if (configuredDomains.none { answer.domain.matchesDomain(it) }) return@forEach
            resolvedAddresses[answer.address] = Resolution(
                domain = answer.domain,
                expiresAtSeconds = now + answer.ttlSeconds.coerceIn(1, MAX_TTL_SECONDS),
            )
        }
    }

    private fun String.matchesDomain(configured: String): Boolean =
        this == configured || endsWith(".$configured")

    private data class Resolution(
        val domain: String,
        val expiresAtSeconds: Long,
    )

    private companion object {
        const val DNS_PORT = 53
        const val MAX_TTL_SECONDS = 86_400L

        fun normalizeDomain(value: String): String? {
            val normalized = value.trim().trimEnd('.').lowercase()
            return normalized.takeIf { it.isNotEmpty() }
        }
    }
}

private object DnsResponseParser {
    data class AddressAnswer(
        val domain: String,
        val address: String,
        val ttlSeconds: Long,
    )

    fun parse(bytes: ByteArray): List<AddressAnswer> {
        if (bytes.size < HEADER_SIZE || readU16(bytes, 2) and RESPONSE_FLAG == 0) return emptyList()
        val questionCount = readU16(bytes, 4)
        val answerCount = readU16(bytes, 6)
        var offset = HEADER_SIZE
        var firstQuestion: String? = null
        repeat(questionCount) {
            val name = readName(bytes, offset)
            if (firstQuestion == null) firstQuestion = name.value
            offset = name.nextOffset + QUESTION_SUFFIX_SIZE
            require(offset <= bytes.size)
        }

        val answers = mutableListOf<AddressAnswer>()
        repeat(answerCount) {
            val owner = readName(bytes, offset)
            offset = owner.nextOffset
            require(offset + ANSWER_PREFIX_SIZE <= bytes.size)
            val type = readU16(bytes, offset)
            val dnsClass = readU16(bytes, offset + 2)
            val ttl = readU32(bytes, offset + 4)
            val dataLength = readU16(bytes, offset + 8)
            offset += ANSWER_PREFIX_SIZE
            require(offset + dataLength <= bytes.size)
            if (type == TYPE_A && dnsClass == CLASS_IN && dataLength == IPV4_BYTES) {
                val domain = firstQuestion?.takeIf { it.isNotEmpty() } ?: owner.value
                val address = InetAddress.getByAddress(bytes.copyOfRange(offset, offset + dataLength))
                    .hostAddress
                if (domain.isNotEmpty() && address != null) {
                    answers += AddressAnswer(domain.lowercase(), address, ttl)
                }
            }
            offset += dataLength
        }
        return answers
    }

    private fun readName(bytes: ByteArray, start: Int): NameResult {
        var cursor = start
        var nextOffset = -1
        var jumps = 0
        val labels = mutableListOf<String>()
        while (true) {
            require(cursor in bytes.indices)
            val length = bytes[cursor].toInt() and 0xFF
            if (length == 0) {
                if (nextOffset < 0) nextOffset = cursor + 1
                break
            }
            if (length and POINTER_MASK == POINTER_MASK) {
                require(cursor + 1 < bytes.size)
                val target = ((length and POINTER_VALUE_MASK) shl 8) or
                    (bytes[cursor + 1].toInt() and 0xFF)
                if (nextOffset < 0) nextOffset = cursor + 2
                cursor = target
                require(++jumps <= MAX_POINTER_JUMPS)
                continue
            }
            require(length <= MAX_LABEL_BYTES)
            val end = cursor + 1 + length
            require(end <= bytes.size)
            labels += bytes.copyOfRange(cursor + 1, end).toString(Charsets.US_ASCII)
            cursor = end
        }
        return NameResult(labels.joinToString("."), nextOffset)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        require(offset + 2 <= bytes.size)
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        require(offset + 4 <= bytes.size)
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private data class NameResult(
        val value: String,
        val nextOffset: Int,
    )

    private const val HEADER_SIZE = 12
    private const val QUESTION_SUFFIX_SIZE = 4
    private const val ANSWER_PREFIX_SIZE = 10
    private const val RESPONSE_FLAG = 0x8000
    private const val TYPE_A = 1
    private const val CLASS_IN = 1
    private const val IPV4_BYTES = 4
    private const val POINTER_MASK = 0xC0
    private const val POINTER_VALUE_MASK = 0x3F
    private const val MAX_POINTER_JUMPS = 16
    private const val MAX_LABEL_BYTES = 63
}
