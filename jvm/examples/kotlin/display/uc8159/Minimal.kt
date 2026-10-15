///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.PollingInputPin
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Uc8159Minimal

// UC8159 minimal example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks for about 30 s.

fun main() {
    val dc = SysfsOutputPin(25)                                                                  // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                 // create RES# pin, (gpio=17) → OutputPin
    val busy = PollingInputPin("/dev/gpiochip0", 24)                                             // create BUSY pin, (chip, line=24) → InputPin
    val connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, busy, WireMode.SIMPLEX)  // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
    val epd = Uc8159Minimal(connection)                                                             // construct driver, (connection, busyTimeoutMs=60000) → Uc8159Minimal
    epd.clear(Uc8159Minimal.WHITE)                                                                  // fill framebuffer, (color=WHITE) → void
    for (x in 0 until 600) {
        val colour = x * 7 / 600                                            // seven vertical bars: black .. orange
        for (y in 0 until 448) epd.setPixel(x, y, colour)                    // set one pixel, (x, y, color=BLACK) → void
    }
    epd.show()                                                                                      // refresh panel (about 30 s), () → void
    epd.sleep()                                                                                     // enter deep sleep, () → void
    connection.close()
}
