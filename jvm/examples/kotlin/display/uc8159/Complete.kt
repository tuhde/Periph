///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.PollingInputPin
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Uc8159Full
import it.uhde.periph.chips.display.Uc8159Minimal

// UC8159 complete example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// The read* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO (GPIO9)
// and use HALF_DUPLEX_PIN. Each refresh blocks for about 30 s.

fun main() {
    val dc = SysfsOutputPin(25)                                                                  // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                 // create RES# pin, (gpio=17) → OutputPin
    val busy = PollingInputPin("/dev/gpiochip0", 24)                                             // create BUSY pin, (chip, line=24) → InputPin
    val connection = DisplayConnection.hardware(0, 0, 1000000, dc, res, busy, WireMode.HALF_DUPLEX_PIN) // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
    val epd = Uc8159Full(connection)                                                                // construct driver, (connection, busyTimeoutMs=60000) → Uc8159Full
    epd.clear(Uc8159Minimal.WHITE)                                                                     // fill framebuffer, (color=WHITE) → void
                                                                                                        // every pixel set to white
    epd.setPixel(5, 5, Uc8159Minimal.BLACK)                                                            // set one pixel, (x, y, color=BLACK) → void
                                                                                                        // colour code 0..7
    epd.drawRect(20, 20, 200, 120, Uc8159Minimal.RED, true)                                            // draw rectangle, (x, y, w, h, color, fill) → void
                                                                                                        // filled red block
    epd.drawRect(240, 20, 200, 120, Uc8159Minimal.BLUE, false)                                         // draw rectangle, (x, y, w, h, color, fill) → void
                                                                                                        // blue outline
    epd.drawLine(0, 0, 599, 447, Uc8159Minimal.GREEN)                                                  // draw line, (x0, y0, x1, y1, color) → void
                                                                                                        // Bresenham diagonal
    epd.blit(460, 20, byteArrayOf(0xff.toByte(), 0x81.toByte(), 0x81.toByte(), 0xff.toByte()), 8, 4, Uc8159Minimal.ORANGE) // copy 1-bpp bitmap, (x, y, data, w, h, color) → void
                                                                                                        // 8 x 4 box, MSB first
    val colour = epd.getPixel(30, 30)                                                               // read framebuffer pixel, (x, y) → int colour code
                                                                                                        // RED here
    epd.setFlip(false, false)                                                                       // mirror output, (horizontal, vertical) → void
                                                                                                        // PSR.SHL / UD at power-on values
    epd.setFrameRate(0x3C)                                                                          // set PLL frame rate code, (code) → void
                                                                                                        // 50 Hz, the vendor value
    epd.setInterval(7, Uc8159Minimal.WHITE, 1)                                                         // set VCOM/data interval, (cdi 0–15, border, polarity) → void
                                                                                                        // white border, 10 hsync
    epd.setTemperatureSensor(false, 0)                                                              // select temperature sensor, (external, offset -8..7 °C) → void
                                                                                                        // internal sensor, no offset
    val status = epd.readStatus()                                                                   // read FLG, () → int bitfield
                                                                                                        // needs DOUT wired (half-duplex)
    val temperature = epd.readTemperature()                                                         // read sensor, () → int °C
                                                                                                        // signed internal temperature
    epd.show()                                                                                      // refresh panel, () → void
                                                                                                        // DTM1, PON, DRF, POF; takes about 30 s
    val ready = epd.waitBusy(1000)                                                                  // wait for BUSY high, (timeoutMs) → boolean
                                                                                                        // true when the controller is idle
    epd.sendCommand(0x50, byteArrayOf(0x37.toByte()))                                               // send raw command, (command, data) → void
                                                                                                        // rewrites CDI with the vendor value
    epd.sleep()                                                                                     // enter deep sleep, () → void
    epd.wake()                                                                                      // reset and re-initialise, () → void
                                                                                                        // framebuffer is kept
    println("colour=$colour status=$status temperature=$temperature ready=$ready")
    connection.close()
    println("===DONE: 0 passed, 0 failed===")
}
