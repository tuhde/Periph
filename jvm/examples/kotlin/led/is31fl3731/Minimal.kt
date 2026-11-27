///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.Is31fl3731Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x74
    I2CConnection(bus, addr).use { connection ->                       // open I²C bus, (bus, address=0x74) → I2CConnection
        val matrix = Is31fl3731Minimal(connection)                  // construct driver, (connection) → Is31fl3731Minimal

        for (col in 0 until Is31fl3731Minimal.COLS) {
            matrix.setPixel(0, col, 128)                                // set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → void
        }
        Thread.sleep(1000)
        matrix.fill(32)                                                 // set every LED's PWM brightness, (brightness 0–255) → void
        Thread.sleep(1000)
        matrix.clear()                                                  // switch all LEDs off, () → void
    }
}
