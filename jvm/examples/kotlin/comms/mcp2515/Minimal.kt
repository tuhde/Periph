///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.comms.Mcp2515Minimal

fun main() {
    SPIConnection(0, 0).use { connection ->                                 // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
        val can = Mcp2515Minimal(connection)                                  // create MCP2515 driver, (connection, bitrateKbps=125, oscMhz=8) → Mcp2515Minimal

        can.send(0x123, byteArrayOf(0x01, 0x02, 0x03, 0x04))                 // send standard frame, (id=0x123, data=4 bytes, extended=false) → Int
                                                                              // loads TXB0 at 125 kbit/s, requests TX, waits for completion

        val frame = can.recv(1000)                                            // receive one frame, (timeoutMs=1000) → CanFrame?
        if (frame != null) {
            println("rx id=0x%X dlc=%d ext=%s rtr=%s data=%s".format(
                frame.id, frame.data.size, frame.extended, frame.rtr,
                frame.data.joinToString(prefix = "[", postfix = "]")))
        }
    }
}
