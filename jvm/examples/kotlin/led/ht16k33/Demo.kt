///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.HT16K33Full
import it.uhde.periph.chips.led.HT16K33Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x70
    I2CConnection(bus, addr).use { connection ->                                   // open I²C bus, (bus, address=0x70) → I2CConnection
        // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
        // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
        // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
        // for 20 s via the INT output before standby and wake.
        val matrix = HT16K33Full(connection)                         // construct driver, (connection) → HT16K33Full

        for (com in 0 until HT16K33Minimal.COMMONS) {
            for (row in 0 until HT16K33Minimal.ROWS) {
                matrix.clear()                                           // blank the display, () → void
                matrix.setPixel(com, row, true)                          // set one LED, (com 0–7, row 0–15, on) → void
                matrix.show()                                            // write the shadow RAM, () → void
                Thread.sleep(50)
            }
        }

        val allOn = ByteArray(16) { 0xFF.toByte() }
        matrix.writeRaw(allOn, 0)                                        // all LEDs on, (data, address) → void
        for (level in 0 until 16) {
            matrix.setBrightness(level)                                  // sweep dimming, (level 0–15) → void
            Thread.sleep(100)
        }

        for (f in doubleArrayOf(2.0, 1.0, 0.5, 0.0)) {
            matrix.setBlink(f)                                           // set blink frequency, (0, 0.5, 1, 2 Hz) → void
            Thread.sleep(3000)
        }

        matrix.setIntOutput(true, false)                                 // INT output, active low, (enable, activeHigh) → void
        for (i in 0 until 1000) {                                  // 1000 x 20 ms = 20 s
            val keys = matrix.pollInterrupt()                           // read keys if pending, () → long bitmask
            if (keys != 0L) println("keys: 0x" + keys.toString(16))
            Thread.sleep(20)
        }

        matrix.standby()                                                 // oscillator off, () → void
        Thread.sleep(1000)
        matrix.wake()                                                    // oscillator on, state restored, () → void
    }
}
