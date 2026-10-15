///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.PollingInputPin
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Il0373Minimal

// IL0373 minimal example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks until BUSY goes high (the panel datasheet gives no update time).

def dc = new SysfsOutputPin(25)                                                                  // create D/C# pin, (gpio=25) → OutputPin
def res = new SysfsOutputPin(17)                                                                 // create RES# pin, (gpio=17) → OutputPin
def busy = new PollingInputPin("/dev/gpiochip0", 24)                                             // create BUSY pin, (chip, line=24) → InputPin
def connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, busy, WireMode.SIMPLEX)      // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
def epd = new Il0373Minimal(connection)                                                         // construct driver, (connection) → Il0373Minimal
epd.clear(Il0373Minimal.WHITE)                                                                  // fill framebuffer, (color=WHITE) → void
epd.setPixel(10, 10, Il0373Minimal.BLACK)                                                       // set one pixel, (x, y, color=BLACK) → void
for (int x = 20; x < 100; x++) epd.setPixel(x, 40, Il0373Minimal.BLACK)                           // set one pixel, (x, y, color=BLACK) → void
epd.show()                                                                                       // refresh panel, () → void
epd.sleep()                                                                                      // enter deep sleep, () → void
connection.close()
