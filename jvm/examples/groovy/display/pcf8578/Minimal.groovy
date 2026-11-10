///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.PCF8578Minimal

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x3C"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)   // open I²C bus, (bus, address=0x3C) → I2CConnection
try {
    def lcd = new PCF8578Minimal(connection, 16)   // construct driver, (connection, rows=16 8/16/24/32) → PCF8578Minimal

    lcd.clear()   // clear display RAM, () → void
    lcd.setPixel(0, 0, true)   // set one pixel, (x 0–23, y 0–15, on) → void
    lcd.setPixel(23, 15, true)   // set opposite corner, (x 0–23, y 0–15, on) → void
    lcd.writeColumns(4, 0, [0xFF, 0x81, 0x81, 0xFF] as byte[])   // write column bytes, (x 0–23, bank 0–1, data bytes) → void
} finally {
    connection.close()
}
