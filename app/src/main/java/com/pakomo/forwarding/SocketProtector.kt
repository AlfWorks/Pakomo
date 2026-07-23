package com.pakomo.forwarding

import java.net.DatagramSocket
import java.net.Socket

interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean
}
