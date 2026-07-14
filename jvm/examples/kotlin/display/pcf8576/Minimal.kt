///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.display.Pcf8576Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x38
    I2CTransport(bus, addr).use { transport ->                                   // open I²C bus, (bus, address=0x38) → I2CTransport
        val lcd = Pcf8576Minimal(transport)                                       // construct driver, (transport) → Pcf8576Minimal

        val digits = intArrayOf(1, 2, 3, 4)
        for (i in digits.indices) {
            val seg = Pcf8576Minimal.SEVEN_SEG[digits[i]]                        // encode 7-segment digit, (digit 0–9) → int
            lcd.setDigit7seg(i, seg)                                              // write one digit, (position 0–19, segments 0–255) → void
        }
    }
}
