///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.St7036Minimal

// ST7036 minimal example: EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi, two lines of text.
// Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.

fun main() {
    val dc = SysfsOutputPin(25)                                                                             // create RS (C/D) pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                            // create RESET pin, (gpio=17) → OutputPin
    val connection = DisplayConnection.hardware(0, 0, 1000000, dc, res, null, WireMode.SIMPLEX)             // open display connection, (bus, device, speedHz=1 MHz, dc, res, busy=null, wireMode)
    val lcd = St7036Minimal(connection)                                                                     // construct driver, (connection) → St7036Minimal

    lcd.printAt(0, 0, "Hello, Periph!")                                                                     // write text, (col, row, text)
    lcd.printAt(0, 1, "ST7036 DOGM162")                                                                     // write text, (col, row, text)
    connection.close()
}
