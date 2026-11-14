///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.memory.AT24C02NMinimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x50").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def ee = new AT24C02NMinimal(conn)                                                     // Create AT24C02N driver, (connection) → void

    int counter = 0
    while (true) {
        ee.writeByte(0x0000, counter)                                                       // Write a single byte, (address 0-255, value 0-255) → void
        def v = ee.readByte(0x0000)                                                         // Read a single byte, (address 0-255) → int
        printf("Stored: %d%n", v)
        counter = (counter + 1) & 0xFF
        Thread.sleep(1000)
    }
} finally {
    conn.close()
}
