///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-kotlin:1.3.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.PCF8579Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x3C
    I2CConnection(bus, addr).use { connection ->   // open I²C bus, (bus, address=0x3C) → I2CConnection
        val lcd = PCF8579Minimal(connection, 0, 16)   // construct driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false) → PCF8579Minimal

        lcd.clear()   // clear display RAM, () → void
        lcd.setPixel(0, 0, true)   // set one pixel, (x 0–39, y 0–15, on) → void
        lcd.setPixel(39, 15, true)   // set opposite corner, (x 0–39, y 0–15, on) → void
        lcd.writeColumns(4, 0, byteArrayOf(0xFF.toByte(), 0x81.toByte(), 0x81.toByte(), 0xFF.toByte()))   // write column bytes, (x 0–39, bank 0–1, data bytes) → void
    }
}
