///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.comms.Mcp2515Minimal

def connection = new SPIConnection(0, 0)                                      // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
try {
    def can = new Mcp2515Minimal(connection)                                    // create MCP2515 driver, (connection, bitrateKbps=125, oscMhz=8) → Mcp2515Minimal

    can.send(0x123, [0x01, 0x02, 0x03, 0x04] as byte[])                        // send standard frame, (id=0x123, data=4 bytes, extended=false) → int
                                                                                // loads TXB0 at 125 kbit/s, requests TX, waits for completion

    Mcp2515Minimal.CanFrame frame = can.recv(1000)                             // receive one frame, (timeoutMs=1000) → CanFrame | null
    if (frame != null) {
        printf("rx id=0x%X dlc=%d ext=%s rtr=%s data=%s%n",
                frame.id, frame.data.length, frame.extended, frame.rtr,
                Arrays.toString(frame.data))
    }
} finally {
    connection.close()
}
