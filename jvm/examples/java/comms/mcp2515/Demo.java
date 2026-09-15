///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.comms.Mcp2515Full;
import it.uhde.periph.chips.comms.Mcp2515Minimal;
import it.uhde.periph.connection.SPIConnection;

/**
 * MCP2515 demo — loopback heartbeat test.
 *
 * <p>Configures the MCP2515 in Loopback mode (no physical CAN bus required).
 * Sends a heartbeat frame with standard ID 0x001 and a 4-byte payload
 * containing the uptime in seconds (big-endian) once per second. Also polls
 * for received frames and prints each one's ID (hex), DLC, data (hex bytes),
 * and frame type (standard/extended/RTR). In Loopback mode the sent heartbeat
 * is received back immediately, demonstrating the full TX→RX round trip
 * without external hardware.
 */
public class Demo {

    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0)) {                    // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
            var can = new Mcp2515Full(connection, 125, 8);                     // create MCP2515 driver, (connection, bitrateKbps=125, oscMhz=8) → Mcp2515Full

            // --- Switch to Loopback mode so we don't need a real CAN bus ---
            // RXM=11 + BUKT=1 are already set by the Minimal init; Loopback mode
            // routes every transmitted frame back into the RX buffers internally.
            can.setMode(Mcp2515Full.MODE_LOOPBACK);                             // enter Loopback mode, (mode="loopback") → void

            long startSec = System.currentTimeMillis() / 1000L;
            int sent = 0;
            int received = 0;

            // --- Run for 5 seconds: send a heartbeat, then drain any RX frames ---
            // Loopback mode echoes the frame back instantly, so each send produces
            // exactly one recv. We poll recv with a short timeout to avoid blocking
            // when the chip is slow to release the buffer.
            while ((System.currentTimeMillis() / 1000L) - startSec < 5) {
                long uptime = System.currentTimeMillis() / 1000L - startSec;
                byte[] payload = new byte[] {
                    (byte) ((uptime >>> 24) & 0xFF),
                    (byte) ((uptime >>> 16) & 0xFF),
                    (byte) ((uptime >>>  8) & 0xFF),
                    (byte) ( uptime         & 0xFF)
                };
                can.send(0x001, payload);                                       // send heartbeat, (id=0x001, data=4 bytes, extended=false) → int
                sent++;

                Mcp2515Minimal.CanFrame frame = can.recv(50);                   // receive one frame, (timeoutMs=50) → CanFrame | null
                if (frame != null) {
                    received++;
                    System.out.printf("rx id=0x%X dlc=%d ext=%s rtr=%s data=",
                            frame.id, frame.data.length, frame.extended, frame.rtr);
                    for (byte b : frame.data) System.out.printf("%02X ", b & 0xFF);
                    System.out.println();
                }

                Thread.sleep(1000);
            }

            System.out.printf("done — sent=%d received=%d%n", sent, received);
        }
    }
}
