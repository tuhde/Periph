///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.Pcf8574Minimal

def connection = new I2CConnection(1, 0x20)           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
try {
    def chip = new Pcf8574Minimal(connection)          // construct driver, (connection) → Pcf8574Minimal

    def p0 = chip.pin(0)                               // get pin proxy, (n) → Pin
    p0.setOutput()                                     // set output mode — drives P0 low, () → void

    def p4 = chip.pin(4)                               // get pin proxy, (n) → Pin
    p4.setInput()                                      // set input mode — release P4 to quasi-input, () → void

    while (true) {
        int port = chip.readPort()                     // read all 8 pins, () → int bitmask
        if (((port >> 4) & 1) == 0) p0.setLow()       // drive P0 low (LED on), () → void
        else                         p0.setHigh()      // release P0 high (LED off), () → void
        Thread.sleep(200)
    }
} finally {
    connection.close()
}
