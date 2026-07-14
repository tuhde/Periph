///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.chips.display.Pcf8576Full
import it.uhde.periph.transport.I2CTransport

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x38
    I2CTransport(bus, addr).use { transport ->

        // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
        // The PCF8576 drives four 7-segment digits from a single I2C bus; the host
        // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
        // and writes all four with one writeRaw() call. The countdown runs once per
        // second and the terminal mirrors the value sent to the display.
        val lcd = Pcf8576Full(transport)                                          // construct driver, (transport) → Pcf8576Full

        for (n in 9999 downTo 0) {
            val d0 = (n / 1000) % 10
            val d1 = (n / 100) % 10
            val d2 = (n / 10) % 10
            val d3 = n % 10
            val out = byteArrayOf(
                Pcf8576Full.SEVEN_SEG[d0].toByte(),                               // encode 7-segment digit, (digit 0–9) → int
                Pcf8576Full.SEVEN_SEG[d1].toByte(),                               // encode 7-segment digit, (digit 0–9) → int
                Pcf8576Full.SEVEN_SEG[d2].toByte(),                               // encode 7-segment digit, (digit 0–9) → int
                Pcf8576Full.SEVEN_SEG[d3].toByte(),                               // encode 7-segment digit, (digit 0–9) → int
            )
            lcd.writeRaw(0, out)                                                  // write all four digits, (address 0, 4 bytes) → void
            println("countdown: %04d".format(n))
            Thread.sleep(1000)
        }

        // --- Stop indicator: light only the middle segments (g) on every digit ---
        // When the counter reaches zero we replace the "0000" pattern with "----" to
        // signal that the demo has finished. Each digit's g segment is bit 1, so a
        // 0x02 byte lights just the bar across the middle.
        val dash = byteArrayOf(0x02, 0x02, 0x02, 0x02)
        lcd.writeRaw(0, dash)                                                     // write dash pattern, (address 0, 4 bytes) → void
        println("countdown complete")
    }
}
