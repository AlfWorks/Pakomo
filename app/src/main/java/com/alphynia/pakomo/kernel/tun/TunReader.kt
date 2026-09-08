package com.alphynia.pakomo.kernel.tun

/**
 * A connection's full 4-tuple, used as the lookup key for TCP connections and UDP sessions.
 *
 * A data class gives correct structural equals/hashCode over all 96 bits. The previous scheme packed
 * the tuple into a single 64-bit Long with overlapping shifts — the source IP was truncated to its low
 * 16 bits and the destination IP's high 16 bits overlapped the source port — so distinct flows could
 * map to the same key (e.g. `1.1.1.1:443` and `129.1.1.1:443` from the same source), causing
 * cross-stream delivery or lost requests. See kernel audit #1.
 */
data class FlowKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

object TunReader {
    fun tcpKey(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): FlowKey =
        FlowKey(srcIp, srcPort, dstIp, dstPort)

    fun udpKey(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): FlowKey =
        FlowKey(srcIp, srcPort, dstIp, dstPort)
}
