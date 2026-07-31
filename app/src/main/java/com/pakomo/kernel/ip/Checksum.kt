package com.pakomo.kernel.ip

/**
 * RFC 1071 Internet checksum with incremental-update support for NAT-style
 * address rewrites inside the tun2socks pipeline.
 */
object Checksum {
    /** One's-complement sum of 16-bit words over [data] from [offset] for [length] bytes. */
    fun sum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var total = 0
        var i = offset
        val end = offset + (length - (length and 1)) // round down to even boundary
        while (i < end) {
            total += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) {
            total += (data[i].toInt() and 0xFF) shl 8
        }
        return fold(total)
    }

    /** Fold a 32-bit accumulator into the final 16-bit one's-complement checksum. */
    fun fold(accumulator: Int): Int {
        var a = accumulator
        a = (a and 0xFFFF) + (a ushr 16)
        a = (a and 0xFFFF) + (a ushr 16)
        return a
    }

    /**
     * Incrementally update a checksum when an old 16-bit field is replaced with a
     * new value. Both [oldVal] and [newVal] are in host byte order.
     */
    fun incremental(oldChecksum: Int, oldField: Int, newField: Int): Int {
        var c = oldChecksum - oldField + newField
        while (c < 0) c += 0xFFFF
        return fold(c)
    }

    /** One's complement of [value], masking to 16 bits. */
    fun complement(value: Int): Int = (value.inv()) and 0xFFFF
}
