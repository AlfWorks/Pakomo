package com.alphynia.pakomo.forwarding

import java.util.Locale

/**
 * Extracts the destination hostname from the first bytes a client sends on a TCP connection:
 * the SNI of a TLS ClientHello, or the `Host` header of a plaintext HTTP request. This lets domain
 * matching work when the app connected to a bare IP (the usual case) and resolved the name via
 * encrypted (DoH/DoT) or cached DNS that the tunnel can't observe.
 */
object HostSniffer {
    /** The hostname carried by [data] (TLS SNI or HTTP Host), or null if none is present. */
    fun extract(data: ByteArray, length: Int): String? {
        if (length <= 0) return null
        return when {
            data[0].toInt() == TLS_HANDSHAKE -> runCatching { extractSni(data, length) }.getOrNull()
            looksLikeHttp(data, length) -> runCatching { extractHttpHost(data, length) }.getOrNull()
            else -> null
        }
    }

    /**
     * For a TLS record, the total bytes needed to hold the full ClientHello record, or -1 if the
     * buffer is not (yet) a TLS handshake record. Lets the caller read until the record is complete
     * before parsing, since SNI can sit past the first TCP segment.
     */
    fun tlsRecordSize(data: ByteArray, length: Int): Int {
        if (length < TLS_HEADER_SIZE || data[0].toInt() != TLS_HANDSHAKE) return -1
        val recordLength = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        return TLS_HEADER_SIZE + recordLength
    }

    fun looksLikeHttp(data: ByteArray, length: Int): Boolean {
        if (length < MIN_HTTP_PREFIX) return false
        val prefix = String(data, 0, minOf(length, 8), Charsets.US_ASCII)
        return HTTP_METHODS.any { prefix.startsWith(it) }
    }

    private fun extractSni(data: ByteArray, length: Int): String? {
        var offset = TLS_HEADER_SIZE
        // Handshake header: type(1) + length(3).
        if (offset + 4 > length || (data[offset].toInt() and 0xFF) != CLIENT_HELLO) return null
        offset += 4
        offset += 2 // client_version
        offset += 32 // random
        if (offset >= length) return null
        val sessionIdLength = data[offset].toInt() and 0xFF
        offset += 1 + sessionIdLength
        if (offset + 2 > length) return null
        val cipherSuitesLength = readU16(data, offset)
        offset += 2 + cipherSuitesLength
        if (offset + 1 > length) return null
        val compressionLength = data[offset].toInt() and 0xFF
        offset += 1 + compressionLength
        if (offset + 2 > length) return null
        val extensionsLength = readU16(data, offset)
        offset += 2
        val extensionsEnd = minOf(offset + extensionsLength, length)
        while (offset + 4 <= extensionsEnd) {
            val type = readU16(data, offset)
            val size = readU16(data, offset + 2)
            offset += 4
            if (type == EXT_SERVER_NAME) {
                return parseServerName(data, offset, minOf(offset + size, length))
            }
            offset += size
        }
        return null
    }

    private fun parseServerName(data: ByteArray, start: Int, end: Int): String? {
        var offset = start
        if (offset + 2 > end) return null
        offset += 2 // server_name_list length
        while (offset + 3 <= end) {
            val nameType = data[offset].toInt() and 0xFF
            val nameLength = readU16(data, offset + 1)
            offset += 3
            if (offset + nameLength > end) return null
            if (nameType == HOST_NAME_TYPE) {
                return String(data, offset, nameLength, Charsets.US_ASCII)
                    .trim()
                    .lowercase(Locale.US)
                    .takeIf { it.isNotEmpty() }
            }
            offset += nameLength
        }
        return null
    }

    private fun extractHttpHost(data: ByteArray, length: Int): String? {
        val text = String(data, 0, length, Charsets.US_ASCII)
        val headerEnd = text.indexOf("\r\n\r\n").let { if (it >= 0) it else text.length }
        text.substring(0, headerEnd).split("\r\n").forEach { line ->
            if (line.length > 5 && line.substring(0, 5).lowercase(Locale.US) == "host:") {
                return line.substring(5)
                    .trim()
                    .substringBefore(':') // strip an optional :port
                    .lowercase(Locale.US)
                    .takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private const val TLS_HANDSHAKE = 0x16
    private const val TLS_HEADER_SIZE = 5
    private const val CLIENT_HELLO = 0x01
    private const val EXT_SERVER_NAME = 0x0000
    private const val HOST_NAME_TYPE = 0x00
    private const val MIN_HTTP_PREFIX = 5
    private val HTTP_METHODS = listOf("GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "OPTIONS ", "PATCH ", "TRACE ")
}
