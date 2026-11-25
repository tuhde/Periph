///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.HT16K33Minimal

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x70"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)                         // open I²C bus, (bus, address=0x70) → I2CConnection
try {
    def matrix = new HT16K33Minimal(connection)                      // construct driver, (connection) → HT16K33Minimal

    for (int row = 0; row < HT16K33Minimal.ROWS; row++) {
        matrix.setPixel(0, row)                                      // set one LED in the shadow RAM, (com 0–7, row 0–15, on) → void
    }
    matrix.show()                                                    // write the shadow RAM to the chip, () → void
    Thread.sleep(1000)
} finally {
    connection.close()
}
