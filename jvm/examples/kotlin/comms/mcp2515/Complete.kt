///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.chips.comms.Mcp2515Full
import it.uhde.periph.chips.comms.Mcp2515Minimal
import it.uhde.periph.connection.SPIConnection

fun main() {
    SPIConnection(0, 0).use { connection ->                                 // open SPI bus 0, CS 0, (busNum, deviceNum, mode=0, speed=1 MHz) → SPIConnection
        val can = Mcp2515Full(connection, 250, 8)                             // create MCP2515 full driver, (connection, bitrateKbps=250, oscMhz=8) → Mcp2515Full

        can.setMode(Mcp2515Full.MODE_CONFIG)                                  // enter Configuration mode, (mode="config") → Unit
                                                                              // required before writing filters or masks
        can.setMask(0, 0x7FF, false)                                         // configure mask 0, (maskNum=0, mask=0x7FF, extended=false) → Unit
                                                                              // require exact 11-bit match for RXB0/filters 0–1
        can.setFilter(0, 0x123, false)                                       // configure filter 0, (filterNum=0, id=0x123, extended=false) → Unit
                                                                              // accept only ID 0x123 standard
        can.setFilter(1, 0x456, false)                                       // configure filter 1, (filterNum=1, id=0x456, extended=false) → Unit
                                                                              // accept ID 0x456 standard
        can.setMask(1, 0x7FF, false)                                         // configure mask 1, (maskNum=1, mask=0x7FF, extended=false) → Unit
        can.setFilter(2, 0x789, false)                                       // configure filter 2, (filterNum=2, id=0x789, extended=false) → Unit

        can.setMode(Mcp2515Full.MODE_NORMAL)                                  // enter Normal mode, (mode="normal") → Unit
                                                                              // chip is now on the bus

        val mode = can.getMode()                                              // read current mode, () → String
        println("mode: $mode")                                               // expect "normal"

        can.sendBuffered(0x123, byteArrayOf(0x01, 0x02), false, 0)             // send via TXB0, (id=0x123, data=2 B, extended=false, buf=0) → Int
                                                                              // explicit TX buffer selection
        can.sendBuffered(0x456, byteArrayOf(0x03), false, 1)                  // send via TXB1, (id=0x456, data=1 B, extended=false, buf=1) → Int
        can.sendBuffered(0x1FFFFFFF, byteArrayOf(0x04), true, 2)             // send via TXB2, (id=0x1FFFFFFF, data=1 B, extended=true, buf=2) → Int
                                                                              // extended 29-bit identifier

        val frame = can.recv(500)                                             // receive one frame, (timeoutMs=500) → CanFrame?
        if (frame != null) {
            println("rx id=0x%X ext=%s".format(frame.id, frame.extended))
        }

        val errs = can.readErrors()                                           // read error counters, () → Errors
        println("tec=%d rec=%d eflg=0x%02X".format(errs.tec, errs.rec, errs.eflg))

        can.setOneShot(true)                                                  // enable one-shot mode, (enable=true) → Unit
                                                                              // no retransmission on error/loss of arbitration
        can.setOneShot(false)                                                 // disable one-shot mode, (enable=false) → Unit

        can.clearOverflow(Mcp2515Full.RXB0)                                   // clear RX0OVR flag, (buf=0) → Unit
        can.clearOverflow(Mcp2515Full.RXB1)                                   // clear RX1OVR flag, (buf=1) → Unit

        can.setRxMode(Mcp2515Full.RXB0, 3)                                    // accept all on RXB0, (buf=0, mode=3) → Unit
                                                                              // bypass filters on RXB0

        can.setMode(Mcp2515Full.MODE_LOOPBACK)                                // enter Loopback mode, (mode="loopback") → Unit
        can.setMode(Mcp2515Full.MODE_LISTEN_ONLY)                             // enter Listen-Only mode, (mode="listen_only") → Unit
        can.setMode(Mcp2515Full.MODE_SLEEP)                                   // enter Sleep mode, (mode="sleep") → Unit
        can.setMode(Mcp2515Full.MODE_NORMAL)                                  // back to Normal mode

        can.abortTx()                                                         // abort all pending TX, () → Unit
                                                                              // sets ABAT, waits for hardware clear

        can.reset()                                                           // SPI RESET, () → Unit
                                                                              // device returns to Configuration mode
    }
}
