///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.chips.comms.Mcp2515Full
import it.uhde.periph.chips.comms.Mcp2515Minimal
import it.uhde.periph.connection.SPIConnection

/**
 * MCP2515 demo — loopback heartbeat test.
 *
 * Configures the MCP2515 in Loopback mode (no physical CAN bus required).
 * Sends a heartbeat frame with standard ID 0x001 and a 4-byte payload
 * containing the uptime in seconds (big-endian) once per second. Also polls
 * for received frames and prints each one's ID (hex), DLC, data (hex bytes),
 * and frame type (standard/extended/RTR). In Loopback mode the sent heartbeat
 * is received back immediately, demonstrating the full TX→RX round trip
 * without external hardware.
 */
fun main() {
    SPIConnection(0, 0).use { connection ->                                 // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
        val can = Mcp2515Full(connection, 125, 8)                            // create MCP2515 driver, (connection, bitrateKbps=125, oscMhz=8) → Mcp2515Full

        // --- Switch to Loopback mode so we don't need a real CAN bus ---
        // RXM=11 + BUKT=1 are already set by the Minimal init; Loopback mode
        // routes every transmitted frame back into the RX buffers internally.
        can.setMode(Mcp2515Full.MODE_LOOPBACK)                                // enter Loopback mode, (mode="loopback") → Unit

        val startSec = System.currentTimeMillis() / 1000L
        var sent = 0
        var received = 0

        // --- Run for 5 seconds: send a heartbeat, then drain any RX frames ---
        // Loopback mode echoes the frame back instantly, so each send produces
        // exactly one recv. We poll recv with a short timeout to avoid blocking
        // when the chip is slow to release the buffer.
        while ((System.currentTimeMillis() / 1000L) - startSec < 5) {
            val uptime = System.currentTimeMillis() / 1000L - startSec
            val payload = byteArrayOf(
                ((uptime ushr 24) and 0xFF).toByte(),
                ((uptime ushr 16) and 0xFF).toByte(),
                ((uptime ushr  8) and 0xFF).toByte(),
                ( uptime         and 0xFF).toByte()
            )
            can.send(0x001, payload)                                          // send heartbeat, (id=0x001, data=4 bytes, extended=false) → Int
            sent++

            val frame = can.recv(50)                                          // receive one frame, (timeoutMs=50) → CanFrame?
            if (frame != null) {
                received++
                val hex = frame.data.joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
                println("rx id=0x%X dlc=%d ext=%s rtr=%s data=%s".format(
                    frame.id, frame.data.size, frame.extended, frame.rtr, hex))
            }

            Thread.sleep(1000)
        }

        println("done — sent=$sent received=$received")
    }
}
