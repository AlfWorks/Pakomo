package com.alphynia.pakomo.forwarding

/**
 * Minimal DNS message helpers for the DNS-failure fault: read the queried name from a request and
 * build a same-id failure reply (NXDOMAIN / SERVFAIL / REFUSED). Only what the fault needs — this
 * is not a general DNS codec.
 */
object DnsMessage {
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_REFUSED = 5

    private const val HEADER_SIZE = 12
    private const val FLAG_RESPONSE = 0x80        // QR bit in flags high byte
    private const val FLAG_RECURSION_AVAILABLE = 0x80 // RA bit in flags low byte
    private const val POINTER_MASK = 0xC0
    private const val MAX_LABEL_BYTES = 63
    private const val MAX_NAME_BYTES = 255

    /** Returns the first question's QNAME as a dotted lowercase string, or null if unparseable. */
    fun queryName(bytes: ByteArray, length: Int = bytes.size): String? {
        if (length < HEADER_SIZE) return null
        // Must be a query (QR=0) with at least one question.
        if (bytes[2].toInt() and FLAG_RESPONSE != 0) return null
        val questionCount = readU16(bytes, 4, length) ?: return null
        if (questionCount < 1) return null
        return runCatching { readName(bytes, HEADER_SIZE, length).first }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Builds a failure response for [query]: copies the header + question section, sets QR=1 and the
     * given [rcode], and zeroes the answer/authority/additional counts. Returns null if the query is
     * too short or the question can't be delimited.
     */
    fun failureResponse(query: ByteArray, length: Int, rcode: Int): ByteArray? {
        if (length < HEADER_SIZE) return null
        val questionCount = readU16(query, 4, length) ?: return null
        if (questionCount < 1) return null
        // Delimit the single question we echo back (name + qtype + qclass).
        val nameEnd = runCatching { readName(query, HEADER_SIZE, length).second }.getOrNull()
            ?: return null
        val questionEnd = nameEnd + 4
        if (questionEnd > length) return null

        val response = query.copyOf(questionEnd)
        // Flags: QR=1, keep opcode + RD from the request, RA=1, set rcode.
        response[2] = (response[2].toInt() or FLAG_RESPONSE).toByte()
        response[3] = (FLAG_RECURSION_AVAILABLE or (rcode and 0x0F)).toByte()
        // QDCOUNT stays 1; ANCOUNT / NSCOUNT / ARCOUNT = 0.
        writeU16(response, 4, 1)
        writeU16(response, 6, 0)
        writeU16(response, 8, 0)
        writeU16(response, 10, 0)
        return response
    }

    /** Reads a (possibly compressed) domain name; returns dotted name and the offset just past it. */
    private fun readName(bytes: ByteArray, start: Int, length: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var cursor = start
        var afterPointer = -1
        var total = 0
        var jumps = 0
        while (true) {
            require(cursor < length)
            val len = bytes[cursor].toInt() and 0xFF
            if (len == 0) {
                if (afterPointer < 0) afterPointer = cursor + 1
                break
            }
            if (len and POINTER_MASK == POINTER_MASK) {
                require(cursor + 1 < length)
                val target = ((len and 0x3F) shl 8) or (bytes[cursor + 1].toInt() and 0xFF)
                if (afterPointer < 0) afterPointer = cursor + 2
                require(++jumps <= 16 && target < length)
                cursor = target
                continue
            }
            require(len <= MAX_LABEL_BYTES)
            val end = cursor + 1 + len
            require(end <= length)
            labels += String(bytes, cursor + 1, len, Charsets.US_ASCII)
            total += len + 1
            require(total <= MAX_NAME_BYTES)
            cursor = end
        }
        return labels.joinToString(".").lowercase() to afterPointer
    }

    private fun readU16(bytes: ByteArray, offset: Int, length: Int): Int? {
        if (offset + 2 > length) return null
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }
}
