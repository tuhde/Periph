///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.PollingInputPin
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Il0373Full
import it.uhde.periph.chips.display.Il0373Minimal

// IL0373 complete example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// The read* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO (GPIO9)
// and use HALF_DUPLEX_PIN. Each refresh blocks until BUSY goes high.

fun main() {
    val dc = SysfsOutputPin(25)                                                                  // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                 // create RES# pin, (gpio=17) → OutputPin
    val busy = PollingInputPin("/dev/gpiochip0", 24)                                             // create BUSY pin, (chip, line=24) → InputPin
    val connection = DisplayConnection.hardware(0, 0, 1000000, dc, res, busy, WireMode.HALF_DUPLEX_PIN) // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
    val epd = Il0373Full(connection)                                                            // construct driver, (connection) → Il0373Full
    epd.clear(Il0373Minimal.WHITE)                                                                 // fill framebuffer, (color=WHITE) → void
                                                                                                 // every pixel set to white
    epd.setPixel(5, 5, Il0373Minimal.BLACK)                                                        // set one pixel, (x, y, color=BLACK) → void
                                                                                                 // clears the bit for that pixel
    epd.drawRect(20, 20, 60, 40, Il0373Minimal.BLACK, true)                                          // draw rectangle, (x, y, w, h, color, fill) → void
                                                                                                 // filled black block
    epd.drawLine(0, 0, 103, 211, Il0373Minimal.BLACK)                                              // draw line, (x0, y0, x1, y1, color) → void
                                                                                                 // Bresenham diagonal
    epd.blit(70, 20, byteArrayOf(0xff.toByte(), 0x81.toByte(), 0x81.toByte(), 0xff.toByte()), 8, 4, Il0373Minimal.BLACK) // copy 1-bpp bitmap, (x, y, data, w, h, color) → void
                                                                                                 // 8 x 4 box, MSB first
    val colour = epd.getPixel(30, 30)                                                            // read framebuffer pixel, (x, y) → int colour
                                                                                                 // BLACK here
    epd.setFlip(false, false)                                                                    // mirror output, (horizontal, vertical) → void
                                                                                                 // PSR.SHL / UD at power-on values
    epd.setPower(14.0, 14.0, 20.0)                                                         // set source/gate power, (vdh V, vdl V, vgh V) → void
                                                                                                 // PWR: +-14 V sources, +-20 V gates (the power-on values)
    epd.setFrameRate(50)                                                                         // set frame rate, (hz 4–200) → void
                                                                                                 // nearest PLL M/N entry (M7 N4)
    epd.setSoftStart(0x17, 0x17, 0x17)                                                           // set booster soft start, (a, b, c) → void
                                                                                                 // vendor BTST phases
    epd.setInterval(7, 2, 1)                                                                     // set VCOM/data interval, (cdi 0–15, border, polarity) → void
                                                                                                 // keeps DDX = 01 so the framebuffer mapping holds
    epd.setTemperatureSensor(false, 0)                                                           // select temperature sensor, (external, offset -8..7 °C) → void
                                                                                                 // internal sensor, no offset
    val status = epd.readStatus()                                                                // read FLG, () → int bitfield
                                                                                                 // needs DOUT wired (half-duplex)
    val temperature = epd.readTemperature()                                                      // read sensor, () → int °C
                                                                                                 // signed internal temperature
    val revision = epd.readRevision()                                                       // read REV, () → int LUT_REV
                                                                                                 // LUT revision byte
    epd.show()                                                                                   // refresh panel, () → void
                                                                                                 // OLD/NEW planes, DRF, border floating, POF
    epd.drawRect(16, 100, 64, 40, Il0373Minimal.BLACK, true)                                       // draw rectangle, (x, y, w, h, color, fill) → void
    epd.showRegion(16, 100, 64, 40)                                                              // refresh a window, (x, y, w, h) → void
                                                                                                 // sends only the window's rows (RAM saving)
    val ready = epd.waitBusy(1000)                                                               // wait for BUSY high, (timeoutMs) → boolean
                                                                                                 // true when the controller is idle
    epd.sendCommand(0x50, byteArrayOf(0x97.toByte()))                                            // send raw command, (command, data) → void
                                                                                                 // rewrites CDI with the initialisation value
    epd.sleep()                                                                                  // enter deep sleep, () → void
    epd.wake()                                                                                   // reset and re-initialise, () → void
                                                                                                 // framebuffer is kept
    println("colour=$colour status=$status temperature=$temperature revision=$revision ready=$ready")
    connection.close()
    println("===DONE: 0 passed, 0 failed===")
}
