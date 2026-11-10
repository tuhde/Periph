///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.display.PCF8578Full

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x3C
    I2CConnection(bus, addr).use { connection ->   // open I²C bus, (bus, address=0x3C) → I2CConnection
        // --- Initialise a 16 x 24 dot-matrix panel ---
        // 1:16 multiplex gives 16 rows and 24 columns; the constructor clears the RAM
        // before switching the display on so no random start-up pattern is shown.
        val lcd = PCF8578Full(connection, 16)   // construct driver, (connection, rows=16 8/16/24/32) → PCF8578Full

        // --- Draw a border and both diagonals ---
        // Lines and rectangles are rendered in the shadow buffer and sent as one
        // batch of changed bytes per run of columns.
        lcd.drawRect(0, 0, 24, 16)   // draw rectangle outline, (x, y, w, h, on, fill) → void
        lcd.drawLine(0, 0, 23, 15)   // draw line, (x0, y0, x1, y1, on) → void
        lcd.drawLine(0, 15, 23, 0)   // draw line, (x0, y0, x1, y1, on) → void

        // --- Flash the display twice with inverse video ---
        // Inverse video only changes how the RAM is shown, so the picture is kept.
        repeat(2) {
            lcd.setDisplay(PCF8578Full.DISPLAY_INVERSE)   // set display status, (status 0–3) → void
            Thread.sleep(400L)
            lcd.setDisplay(PCF8578Full.DISPLAY_NORMAL)   // set display status, (status 0–3) → void
            Thread.sleep(400L)
        }

        // --- Fill all four RAM banks with distinct patterns ---
        // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
        // buffers that the start bank can scroll into view.
        val patterns = intArrayOf(0x55, 0x33, 0x0F, 0xFF)
        for (bank in 0 until 4) {
            val col = ByteArray(24) { patterns[bank].toByte() }
            lcd.writeRam(16, bank, col, PCF8578Full.ACCESS_CHARACTER)   // write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → void
        }

        // --- Scroll through the banks with the hardware start bank ---
        // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
        for (bank in 0 until 4) {
            lcd.setStartBank(bank)   // set start bank, (bank 0–3) → void
            val first = lcd.readRam(16, bank, 1)   // read raw RAM, (x 0–39, bank 0–3, n, mode) → byte[]
            println("start bank $bank first column byte 0x%02X".format(first[0]))
            Thread.sleep(1000L)
        }

        // --- Read back the first 8 RAM columns of bank 0 ---
        // RAM columns 0-7 are never displayed at 1:16, so they serve as scratch
        // storage; the chip supports reads in mixed mode, so the bytes can be verified.
        lcd.setStartBank(0)   // set start bank, (bank 0–3) → void
        lcd.writeRam(0, 0, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))   // write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → void
        println(lcd.readRam(0, 0, 8).toList())   // read raw RAM, (x 0–39, bank 0–3, n, mode) → byte[]
    }
}
