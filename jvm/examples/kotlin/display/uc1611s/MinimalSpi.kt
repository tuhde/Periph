///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1
// UC1611s minimal example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi (JVM).
// Wiring: SI=GPIO10 (SPI0 MOSI), SCK=GPIO11, CS0=GPIO8 (CE0), CD=GPIO25, RST=GPIO17; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Minimal use: a border and a diagonal on the 240 x 128 panel.

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Uc1611sSpiMinimal

fun main() {
    val dc = SysfsOutputPin(25)                                                                             // create CD (D/C) pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                            // create RST pin, (gpio=17) → OutputPin
    DisplayConnection.hardware(0, 0, 4000000, dc, res, null, WireMode.SIMPLEX).use { connection ->          // open display connection, (bus, device, speedHz=4 MHz, dc, res, busy=null, wireMode)
    val lcd = Uc1611sSpiMinimal(connection)  // construct driver, (connection, width=240, height=128) → Uc1611sSpiMinimal

        lcd.clear()                                                       // clear framebuffer, () → void
        for (x in 0 until 240) {
            lcd.setPixel(x, 0, true)                                      // set pixel, (x 0..239, y 0..127, on) → void
            lcd.setPixel(x, 127, true)                                    // set pixel, (x 0..239, y 0..127, on) → void
            lcd.setPixel(x, x * 127 / 239, true)                          // set pixel, (x 0..239, y 0..127, on) → void
        }
        for (y in 0 until 128) {
            lcd.setPixel(0, y, true)                                      // set pixel, (x 0..239, y 0..127, on) → void
            lcd.setPixel(239, y, true)                                    // set pixel, (x 0..239, y 0..127, on) → void
        }
        lcd.show()                                                        // send framebuffer to the display, () → void
    }
}
