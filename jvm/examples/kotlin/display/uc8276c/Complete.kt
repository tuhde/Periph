///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.DisplayConnection
import it.uhde.periph.connection.PollingInputPin
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.connection.WireMode
import it.uhde.periph.chips.display.Uc8276cFull

// UC8276C complete example: Waveshare 4.2inch e-Paper (B) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// The read* calls need DOUT: join DIN and DOUT through a ~1 kOhm resistor to MISO (GPIO9)
// and use HALF_DUPLEX_PIN. Each refresh takes about 17 s.

fun main() {
    val dc = SysfsOutputPin(25)                                                                  // create D/C# pin, (gpio=25) → OutputPin
    val res = SysfsOutputPin(17)                                                                 // create RES# pin, (gpio=17) → OutputPin
    val busy = PollingInputPin("/dev/gpiochip0", 24)                                             // create BUSY_N pin, (chip, line=24) → InputPin
    val connection = DisplayConnection.hardware(0, 0, 1000000, dc, res, busy, WireMode.HALF_DUPLEX_PIN) // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
    val epd = Uc8276cFull(connection)                                                            // construct driver, (connection) → Uc8276cFull
    epd.clear(Uc8276cFull.WHITE)                                                                 // fill framebuffer, (color=WHITE) → void
                                                                                                 // both planes set to white
    epd.setPixel(5, 5, Uc8276cFull.BLACK)                                                        // set one pixel, (x, y, color=BLACK) → void
                                                                                                 // clears the B/W bit for that pixel
    epd.drawRect(20, 20, 100, 60, Uc8276cFull.RED, true)                                         // draw rectangle, (x, y, w, h, color, fill) → void
                                                                                                 // filled red block
    epd.drawLine(0, 0, 399, 299, Uc8276cFull.BLACK)                                              // draw line, (x0, y0, x1, y1, color) → void
                                                                                                 // Bresenham diagonal
    epd.blit(150, 20, byteArrayOf(0xff.toByte(), 0x81.toByte(), 0x81.toByte(), 0xff.toByte()), 8, 4, Uc8276cFull.BLACK) // copy 1-bpp bitmap, (x, y, data, w, h, color) → void
                                                                                                 // 8 x 4 box, MSB first
    val colour = epd.getPixel(30, 30)                                                            // read framebuffer pixel, (x, y) → int colour
                                                                                                 // RED here
    epd.setMode(Uc8276cFull.MODE_KWR)                                                            // select pixel mode, (mode) → void
                                                                                                 // PSR.KW/R cleared: black/white/red
    epd.setFlip(false, false)                                                                    // mirror output, (horizontal, vertical) → void
                                                                                                 // PSR.SHL / UD at power-on values
    epd.setPower(10.0, 10.0, 10.0, 16.0)                                                         // set source/gate power, (vdh V, vdl V, vdhr V, vgh V) → void
                                                                                                 // panel-layout PWR: +-10 V sources, +-16 V gates
    epd.setFrameRate(50)                                                                         // set frame rate, (hz 4–200) → void
                                                                                                 // nearest PLL M/N entry (M7 N4)
    epd.setSoftStart(0x17, 0x17, 0x17)                                                           // set booster soft start, (a, b, c) → void
                                                                                                 // vendor BTST phases
    epd.setInterval(7, 3, 1)                                                                     // set VCOM/data interval, (cdi 0–15, border, polarity) → void
                                                                                                 // keeps DDX = 01 so the framebuffer mapping holds
    epd.setTemperatureSensor(false, 0)                                                           // select temperature sensor, (external, offset -8..7 °C) → void
                                                                                                 // internal sensor, no offset
    val status = epd.readStatus()                                                                // read FLG, () → int bitfield
                                                                                                 // needs DOUT wired (half-duplex)
    val temperature = epd.readTemperature()                                                      // read sensor, () → int °C
                                                                                                 // signed internal temperature
    val revision = epd.readRevision(false)                                                       // read REV, (longForm) → {chipRev, lutRev}
                                                                                                 // panel form: chipRev is -1
    epd.show()                                                                                   // refresh panel, () → void
                                                                                                 // DTM1, DTM2, PON, DRF, POF; takes ~17 s
    epd.drawRect(40, 100, 80, 40, Uc8276cFull.BLACK, true)                                       // draw rectangle, (x, y, w, h, color, fill) → void
    epd.showRegion(40, 100, 80, 40)                                                              // refresh a window, (x, y, w, h) → void
                                                                                                 // sends only the window's rows (RAM saving)
    val ready = epd.waitBusy(1000)                                                               // wait for BUSY_N high, (timeoutMs) → boolean
                                                                                                 // true when the controller is idle
    epd.sendCommand(0x50, byteArrayOf(0xd7.toByte()))                                            // send raw command, (command, data) → void
                                                                                                 // rewrites CDI with its default
    epd.sleep()                                                                                  // enter deep sleep, () → void
    epd.wake()                                                                                   // reset and re-initialise, () → void
                                                                                                 // framebuffer is kept
    println("colour=$colour status=$status temperature=$temperature revision=${revision.toList()} ready=$ready")
    connection.close()
    println("===DONE: 0 passed, 0 failed===")
}
