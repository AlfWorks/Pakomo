package com.alphynia.pakomo.kernel.tun

object TunReader {
    fun tcpKey(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Long =
        ((srcIp.toLong() and 0xFFFF_FFFFL) shl 48) or
        ((srcPort.toLong() and 0xFFFF) shl 32) or
        ((dstIp.toLong() and 0xFFFF_FFFFL) shl 16) or
        (dstPort.toLong() and 0xFFFF)

    fun udpKey(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Long =
        ((srcIp.toLong() and 0xFFFF_FFFFL) shl 48) or
        ((srcPort.toLong() and 0xFFFF) shl 32) or
        ((dstIp.toLong() and 0xFFFF_FFFFL) shl 16) or
        (dstPort.toLong() and 0xFFFF)
}
