///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.Is31fl3731Minimal

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x74"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)            // open I²C bus, (bus, address=0x74) → I2CConnection
try {
    def matrix = new Is31fl3731Minimal(connection)                  // construct driver, (connection) → Is31fl3731Minimal

    for (int col = 0; col < Is31fl3731Minimal.COLS; col++) {
        matrix.setPixel(0, col, 128)                                    // set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → void
    }
    Thread.sleep(1000)
    matrix.fill(32)                                                     // set every LED's PWM brightness, (brightness 0–255) → void
    Thread.sleep(1000)
    matrix.clear()                                                      // switch all LEDs off, () → void
} finally {
    connection.close()
}
