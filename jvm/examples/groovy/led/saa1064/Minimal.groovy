///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-groovy:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.SAA1064Minimal

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x38"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)                     // open I²C bus, (bus, address=0x38) → I2CConnection
try {
    def display = new SAA1064Minimal(connection)                        // construct driver, (connection) → SAA1064Minimal
    for (int n = 0; n < 100; n += 1) {
        display.showNumber(n)                                           // show a right-aligned number, (value 0–9999) → void
        Thread.sleep(100)
    }
} finally {
    connection.close()
}
