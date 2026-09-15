///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.comms.Mcp2515Full;
import it.uhde.periph.connection.SPIConnection;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new SPIConnection(0, 0)) {                    // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
            var can = new Mcp2515Full(connection, 250, 8);                     // create MCP2515 full driver, (connection, bitrateKbps=250, oscMhz=8) → Mcp2515Full

            can.setMode(Mcp2515Full.MODE_CONFIG);                              // enter Configuration mode, (mode="config") → void
                                                                                // required before writing filters or masks
            can.setMask(0, 0x7FF, false);                                       // configure mask 0, (maskNum=0, mask=0x7FF, extended=false) → void
                                                                                // require exact 11-bit match for RXB0/filters 0–1
            can.setFilter(0, 0x123, false);                                     // configure filter 0, (filterNum=0, id=0x123, extended=false) → void
                                                                                // accept only ID 0x123 standard
            can.setFilter(1, 0x456, false);                                     // configure filter 1, (filterNum=1, id=0x456, extended=false) → void
                                                                                // accept ID 0x456 standard
            can.setMask(1, 0x7FF, false);                                       // configure mask 1, (maskNum=1, mask=0x7FF, extended=false) → void
            can.setFilter(2, 0x789, false);                                     // configure filter 2, (filterNum=2, id=0x789, extended=false) → void

            can.setMode(Mcp2515Full.MODE_NORMAL);                              // enter Normal mode, (mode="normal") → void
                                                                                // chip is now on the bus

            String mode = can.getMode();                                        // read current mode, () → String
            System.out.println("mode: " + mode);                               // expect "normal"

            can.sendBuffered(0x123, new byte[]{0x01, 0x02}, false, 0);          // send via TXB0, (id=0x123, data=2 B, extended=false, buf=0) → int
                                                                                // explicit TX buffer selection
            can.sendBuffered(0x456, new byte[]{0x03}, false, 1);                // send via TXB1, (id=0x456, data=1 B, extended=false, buf=1) → int
            can.sendBuffered(0x1FFFFFFF, new byte[]{0x04}, true, 2);           // send via TXB2, (id=0x1FFFFFFF, data=1 B, extended=true, buf=2) → int
                                                                                // extended 29-bit identifier

            Mcp2515Minimal.CanFrame frame = can.recv(500);                      // receive one frame, (timeoutMs=500) → CanFrame | null
            if (frame != null) {
                System.out.printf("rx id=0x%X ext=%s%n", frame.id, frame.extended);
            }

            Mcp2515Full.Errors errs = can.readErrors();                         // read error counters, () → Errors
            System.out.printf("tec=%d rec=%d eflg=0x%02X%n", errs.tec, errs.rec, errs.eflg);

            can.setOneShot(true);                                               // enable one-shot mode, (enable=true) → void
                                                                                // no retransmission on error/loss of arbitration
            can.setOneShot(false);                                              // disable one-shot mode, (enable=false) → void

            can.clearOverflow(Mcp2515Full.RXB0);                                // clear RX0OVR flag, (buf=0) → void
            can.clearOverflow(Mcp2515Full.RXB1);                                // clear RX1OVR flag, (buf=1) → void

            can.setRxMode(Mcp2515Full.RXB0, 3);                                 // accept all on RXB0, (buf=0, mode=3) → void
                                                                                // bypass filters on RXB0

            can.setMode(Mcp2515Full.MODE_LOOPBACK);                             // enter Loopback mode, (mode="loopback") → void
            can.setMode(Mcp2515Full.MODE_LISTEN_ONLY);                          // enter Listen-Only mode, (mode="listen_only") → void
            can.setMode(Mcp2515Full.MODE_SLEEP);                                // enter Sleep mode, (mode="sleep") → void
            can.setMode(Mcp2515Full.MODE_NORMAL);                              // back to Normal mode

            can.abortTx();                                                      // abort all pending TX, () → void
                                                                                // sets ABAT, waits for hardware clear

            can.reset();                                                        // SPI RESET, () → void
                                                                                // device returns to Configuration mode
        }
    }
}
